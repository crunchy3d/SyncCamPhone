package com.crunchy3d.synccamphone;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Continuous manual-exposure camera stream with a fixed frame duration, plus single-frame
 * duration steps for phase steering. All camera work runs on one background thread; frame
 * callbacks are delivered on that thread.
 */
final class SyncCamera {

    interface FrameListener {
        /** timestampNs = SENSOR_TIMESTAMP (elapsedRealtime base), tag = "R" or step tag. */
        void onFrame(long timestampNs, String tag);
    }

    /** Optional per-frame image analysis (small YUV stream) and per-frame result data. */
    interface Analyzer {
        void onImage(Image img);                 // analyzer thread; image closed by the caller
        void onResult(long tsLocal, long exposureNs, long skewNs, long durationNs);  // camera thread
    }

    static final int ANALYSIS_WIDTH = 640;
    static final String VIDEO_SUFFIX = "+v";   // request tag suffix: frame goes to the encoder

    static final class Settings {
        final long periodNs, exposureNs;
        final int iso;
        final int videoHeight;                // encoder size: 1080 or 2160 (16:9)
        final boolean photoMode;              // full-resolution JPEG stream instead of the encoder
        final int photoFormat;                // PhotoCapture.FORMATS: 0 JPEG, 1 RAW, 2 RAW+JPEG, 3 HDR burst
        final boolean appJpeg;                // JPEG made in the app from full-res YUV (camera JPEG stalls)
        final int tone;                       // TONES: 0 camera standard, 1 soft, 2 strong highlight roll-off
        // the small YUV analysis stream only for the LED test (power: an unused stream at 30 fps)
        boolean analysis;
        // lens (Lenses): camera ID + zoom ratio (< 1 = ultra-wide inside a logical camera)
        String cameraId = "0";
        float zoom = 1f;

        Settings(long periodNs, long exposureNs, int iso, int videoHeight, boolean photoMode, int photoFormat,
                 boolean appJpeg, int tone) {
            this.tone = tone;
            this.periodNs = periodNs;
            this.exposureNs = exposureNs;
            this.iso = iso;
            this.videoHeight = videoHeight;
            this.photoMode = photoMode;
            this.photoFormat = photoFormat;
            this.appJpeg = appJpeg;
        }

        boolean jpeg() {
            return photoFormat == 0 || photoFormat == 2;
        }

        boolean raw() {
            return photoFormat != 0;
        }

        /** HDR burst: RAW stream all the time, zero shutter lag (PhotoBurst). */
        boolean burst() {
            return photoMode && photoFormat == 3;
        }

        boolean same(Settings o) {
            return sameSession(o) && o.exposureNs == exposureNs && o.iso == iso && o.tone == tone;
        }

        /** Same camera session (exposure and ISO only change the requests, no restart). */
        boolean sameSession(Settings o) {
            return o != null && o.periodNs == periodNs && o.videoHeight == videoHeight && o.photoMode == photoMode
                    && o.analysis == analysis && o.cameraId.equals(cameraId) && o.zoom == zoom
                    && (!photoMode || (o.photoFormat == photoFormat && o.appJpeg == appJpeg));
        }
    }

    private final CameraManager cm;
    private final String cameraId;
    private final TextureView view;
    private final FrameListener listener;

    private final HandlerThread thread;
    private final Handler handler;
    private final HandlerThread analysisThread;
    private final Handler analysisHandler;
    volatile Analyzer analyzer;
    private CameraCharacteristics chars;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface surface;
    private ImageReader reader;
    private Size size, analysisSize, videoSize;
    private volatile long videoMinFrameDurationNs;
    private VideoRecorder recorder;           // persistent encoder surface, kept across sessions
    private volatile boolean recording;       // requests target the encoder (camera thread writes)
    private ImageReader jpegReader;           // photo mode: full-resolution JPEG stream
    private ImageReader rawReader;            // photo mode: RAW_SENSOR stream (DNG)
    private final PhotoBurst.Ring ring = new PhotoBurst.Ring();          // photo thread
    private final PhotoBurst.Results burstResults = new PhotoBurst.Results();
    private volatile PhotoBurst burst;        // burst being collected
    private volatile boolean burstWriting;    // burst being merged / written: do not refill the ring
    private volatile AstroCapture astro;      // astro series being captured
    volatile boolean statsWanted;             // AE is running: RAW statistics often
    private volatile long astroSubmitNs;      // local time the astro requests were queued (0 = not yet)
    // After the preview stream stops, the Pixel 7a and 4 run one hidden frame with the settings of
    // the FIRST new request (balcony tests 2026-11-16: +16 s after the first long frame, both when
    // all frames were queued at once and when requested one by one). So a short lead-in frame
    // (tag "W", discarded) goes first; all frames are queued at once (no gaps between them).
    static final long ASTRO_LEAD_NS = 200_000_000L;
    // SYNCHRONISED START (stereo, 2026-11-17: the phones started 12-23 ms apart - a car moves 17-32
    // cm in that time, more than the stereo base): the shortest frames go FIRST and must start at
    // the same master time T on all phones. The HAL does not keep the requested frame length (the
    // time between exposure starts is "exposure + a gap" that it chooses), so it is MEASURED: two
    // identical lead-in frames W0, W1 give the gap; when W1 starts, a spacer W2 is queued whose
    // exposure makes the first frame start at T, then all frames. A remaining model error is the
    // same on phones of the same model (cancels in stereo).
    // T = start + this. 1 s was too short (balcony test 2026-11-17: the HAL made each 0.2 s lead-in frame
    // 440 ms long and started ~0.35 s after the request -> spacer -0.5 s, both phones fell back, 40 ms
    // apart); with 2.5 s the spacer is ~1 s on both phones (a timing-model error then is the same on
    // both and cancels in the stereo pair)
    static final long ASTRO_SYNC_NS = 2_500_000_000L;
    static final long ASTRO_SPACER_MIN_NS = 5_000_000L, ASTRO_SPACER_MAX_NS = 3_000_000_000L;
    private AstroCapture astroPending;        // camera thread: waiting for W0 / W1 to start
    // the countdown on the screen: the series from its start until 5 s after the result
    private volatile AstroCapture astroUi;
    private volatile long astroUiDoneMs;      // elapsedRealtime of the result (0 = not yet)
    private long astroW0Ts, astroT;
    private long[] astroE;
    private int[] astroS;
    private volatile android.hardware.camera2.params.ColorSpaceTransform appliedXform;
    /** Start of an astro series this long after the press (the tap must not shake the phone). */
    static final long ASTRO_DELAY_NS = 2_000_000_000L;
    volatile boolean keepBurstFrames;         // also save the 8 single DNGs (PC comparison)
    // HDR plan of the next burst (from the master's state): mode (PhotoBurst.MODE_*), longer frames
    volatile int hdrMode = PhotoBurst.MODE_AUTO;
    volatile int nightOut = -1;               // night mode of JPEG / RAW: which files the burst saves
    volatile long[] planExp = new long[0];
    volatile int[] planIso = new int[0];
    volatile boolean natural;
    volatile int brightness;

    /** Stereo: the master's rendering of a burst (publish on the master, await on a camera). */
    interface ToneBus {
        void publish(int seq, String tone);

        String await(int seq, long timeoutMs);
    }

    volatile ToneBus toneBus;
    volatile int toneRole;                    // 0 alone, 1 publish (master), 2 wait for the master

    /** Plan "exp:iso;exp:iso" (ns, ISO) or "-" -> the two arrays. */
    void setPlan(String s) {
        if (s == null || s.equals("-") || s.isEmpty()) {
            planExp = new long[0];
            planIso = new int[0];
            return;
        }
        String[] p = s.split(";");
        long[] e = new long[p.length];
        int[] i = new int[p.length];
        for (int k = 0; k < p.length; k++) {
            String[] f = p[k].split(":");
            e[k] = Long.parseLong(f[0]);
            i[k] = Integer.parseInt(f[1]);
        }
        planIso = i;
        planExp = e;
    }

    private ImageReader yuvReader;            // photo mode, in-app JPEG: full-resolution YUV stream
    private Size jpegSize, rawSize, yuvSize;
    private volatile boolean jpegStalls;      // the camera's full-size JPEG has a stall (Pixel 4)
    private volatile boolean appJpegAuto;     // in-app JPEG because every camera JPEG size stalls

    /** Photo stream size while streaming in photo mode, else null (for display). */
    Size photoStreamSize() {
        Settings a = active;
        return a != null && a.photoMode ? photoSize() : null;
    }

    /** Size of the photo stream that gives the JPEG (camera JPEG or full-res YUV), else RAW. */
    private Size photoSize() {
        return yuvReader != null ? yuvSize : jpegReader != null ? jpegSize : rawSize;
    }
    private volatile long jpegMinFrameDurationNs;
    private volatile PhotoCapture photo;      // photo window in progress
    private volatile boolean photoWindow;     // requests target the JPEG stream
    private final HandlerThread photoThread;
    private final Handler photoHandler;
    volatile String photoStatus = "";
    volatile android.graphics.Bitmap lastThumb;   // upright thumbnail of the last saved JPEG
    volatile android.net.Uri lastJpeg;            // that JPEG (set before lastThumb)
    volatile int lastPhotoSeq = -1;
    volatile long lastPhotoErrNs;
    private Range<Integer> fpsRange;
    private boolean afContinuous;
    private boolean afAuto;                   // the lens can focus (the front camera may be fixed focus)

    /** The lens' zoom ratio on every request (ultra-wide inside the logical camera: 0.53 on the 7a). */
    private void applyZoom(CaptureRequest.Builder b) {
        Settings s = active != null ? active : settings;
        if (s != null && s.zoom != 1f && android.os.Build.VERSION.SDK_INT >= 30) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoom);
        }
    }

    private volatile Settings settings;       // requested
    private volatile Settings active;         // currently streaming
    private volatile long minFrameDurationNs;
    private volatile String status = "idle";
    private int stepCounter;
    private int generation;                   // invalidates callbacks of closed sessions

    SyncCamera(CameraManager cm, String cameraId, TextureView view, FrameListener listener) {
        this.cm = cm;
        this.cameraId = cameraId;
        this.view = view;
        this.listener = listener;
        thread = new HandlerThread("SyncCamera");
        thread.start();
        handler = new Handler(thread.getLooper());
        analysisThread = new HandlerThread("SyncCameraAnalysis");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
        photoThread = new HandlerThread("SyncCameraPhoto");
        photoThread.start();
        photoHandler = new Handler(photoThread.getLooper());
        // After the app was in the background Android gives the preview a new SurfaceTexture; the
        // running session still draws into the old one (frozen preview): reopen with the new one.
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                handler.post(() -> {
                    // not during a recording (that would end the file): reopen after its end
                    if (settings == null || surface == null) return;
                    if (recording) reopenAfterRecording = true;
                    else reopen();
                });
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
    }

    // ------------------------------------------------------------------ photo

    /**
     * Takes a synchronized photo: the JPEG stream is on for the frames around master time T and the
     * frame closest to T is saved. Only in photo mode.
     */
    void takePhoto(android.content.Context ctx, String name, String info, int seq, long targetMaster,
                   LedCapture.Clock clock, int deviceDegrees, int aspect) {
        // open the JPEG window only ~0.2 s before T (16 MP JPEGs are heavy: keep it to ~8 frames)
        long nowLocal = android.os.SystemClock.elapsedRealtimeNanos();
        long nowMaster = clock.toMaster(nowLocal);
        long delayMs = nowMaster == Long.MIN_VALUE ? 0
                : Math.max(0, (targetMaster - nowMaster) / 1_000_000L - 200);
        Settings ab = active;
        if (ab != null && ab.burst()) {
            // zero shutter lag: T may already be in the past; the ring has the frames before it
            final int rot = rotationFor(deviceDegrees);
            if (hdrMode == PhotoBurst.MODE_ASTRO) {
                photoHandler.post(() -> startAstro(ctx, name, info, seq, targetMaster, clock, rot, aspect));
                return;
            }
            photoHandler.post(() -> {
                if (rawReader == null || burst != null || burstWriting || astro != null) {
                    photoStatus = "burst #" + seq + ": " + (rawReader == null ? "no RAW stream" : "busy");
                    return;
                }
                final int md = hdrMode;
                final long[] pe = md == PhotoBurst.MODE_SINGLE ? new long[0] : planExp;
                final int[] pi = md == PhotoBurst.MODE_SINGLE ? new int[0] : planIso;
                StringBuilder pl = new StringBuilder();
                for (int j = 0; j < pe.length; j++) pl.append(String.format(Locale.US, " + 1/%.0f s ISO %d", 1e9 / pe[j], pi[j]));
                PhotoBurst b = new PhotoBurst(ctx, name, info + ", " + rawSize + ", " + PhotoBurst.MODES[md] + pl + ", " + focusInfo(),
                        seq, targetMaster, ab.periodNs, clock, chars, burstResults, rot, aspect, md, pe, pi, natural);
                b.brightness = brightness;
                b.outFormat = nightOut;
                b.keepFrames = keepBurstFrames;
                b.toneBus = toneBus;
                b.toneRole = toneRole;
                b.shortExpNs = Math.min(ab.exposureNs, ab.periodNs - EXPOSURE_MARGIN_NS);
                b.shortIso = ab.iso;
                b.takeFromRing(ring);
                burst = b;
                burstUi = b;
                burstUiDoneMs = 0;
                if (pe.length > 0) {
                    // the longer frames: RAW only (the preview keeps its brightness), same frame duration
                    // (the timing grid is not disturbed), queued right after the press, the whole plan
                    // repeated so that every phone gets a matching frame in the agreed slots
                    // requested at the same MASTER time on every phone (the master handles the
                    // press at once, a camera ~30-60 ms later: requesting at once put their frames
                    // into different slots) -> identical pipelines give identical slots
                    long nowM = clock.toMaster(android.os.SystemClock.elapsedRealtimeNanos());
                    long waitMs = nowM == Long.MIN_VALUE ? 0
                            : Math.max(0, (targetMaster + Math.round(PhotoBurst.SUBMIT_AT * ab.periodNs) - nowM) / 1_000_000L);
                    handler.postDelayed(() -> {
                        Settings a = active;
                        if (a == null || session == null || rawReader == null) return;
                        try {
                            // each entry REPEAT times in a row; a run of identical (averaging) frames as one
                            // block of run + REPEAT - 1 requests (the same slot safety, little extra time)
                            for (int[] run : PhotoBurst.requestRuns(pe, pi)) {
                                int j = run[0];
                                // item 34: frames like the stream's (Night / noise averaging) come from it
                                if (PhotoBurst.sameAsStream(pe[j], pi[j], b.shortExpNs, b.shortIso)) continue;
                                for (int r = 0; r < run[1]; r++) {
                                    session.capture(buildRequest(a, a.periodNs, "L" + j, pe[j], pi[j]), callback, handler);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }, waitMs);
                }
                photoStatus = "burst #" + seq + "...";
                checkBurst(b);
            });
            return;
        }
        handler.postDelayed(() -> {
            Settings a = active;
            if (a != null && a.photoMode && a.jpeg() && jpegReader == null && yuvReader == null
                    && rawReader == null && jpegStalls) {
                photoStatus = "photo #" + seq + ": camera JPEG stalls - Settings > Advanced > Photo JPEG: app";
                return;
            }
            if (a == null || !a.photoMode || (jpegReader == null && rawReader == null && yuvReader == null)
                    || session == null || photo != null) {
                photoStatus = "photo #" + seq + ": not in photo mode";
                return;
            }
            photoRotation = rotationFor(deviceDegrees);
            photo = new PhotoCapture(ctx, name, info + ", " + photoSize()
                    + ", " + PhotoCapture.FORMATS[a.photoFormat] + (yuvReader != null ? " (in-app JPEG)" : "")
                    + "\n" + wbInfo() + "\n" + focusInfo(),
                    seq, targetMaster, clock, aspect);
            photo.setRaw(chars, photoRotation);
            photoWindow = true;
            photoStatus = "photo #" + seq + "...";
            try {
                session.setRepeatingRequest(buildRequest(a, a.periodNs, "R"), callback, handler);
            } catch (Exception e) {
                endPhotoWindow();
            }
        }, delayMs);
    }

    // ------------------------------------------------------------------ astro

    /**
     * Photo thread: an astro series. The repeating preview stops at the common start time
     * (master time T + ASTRO_DELAY_NS on every phone), the planned frames are queued back to back
     * (each phone clamps the exposure to its own maximum: Pixel 7a 16 s, Pixel 4 10.2 s), the
     * preview shows each long frame as it comes ("post-shutter viewfinder"), then the preview
     * stream resumes.
     */
    private void startAstro(android.content.Context ctx, String name, String info, int seq, long targetMaster,
                            LedCapture.Clock clock, int rot, int aspect) {
        if (rawReader == null || burst != null || burstWriting || astro != null) {
            photoStatus = "astro #" + seq + ": " + (rawReader == null ? "no RAW stream" : "busy");
            return;
        }
        MergeCore.nightLook = false;
        long[] pe = planExp;
        int[] pi = planIso;
        if (pe.length == 0) {
            photoStatus = "astro #" + seq + ": no plan (press AE first)";
            return;
        }
        Range<Long> er = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> ir = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        long[] e = new long[pe.length];
        int[] s = new int[pe.length];
        StringBuilder pl = new StringBuilder();
        for (int j = 0; j < pe.length; j++) {
            e[j] = er != null ? Math.max(er.getLower(), Math.min(er.getUpper(), pe[j])) : pe[j];
            s[j] = ir != null ? Math.max(ir.getLower(), Math.min(ir.getUpper(), pi[j])) : pi[j];
            pl.append(String.format(Locale.US, " %.3gs/%d", e[j] / 1e9, s[j]));
        }
        // shortest first: the fast frames are the ones that must be synchronised (moving lights)
        for (int a = 1; a < e.length; a++) {
            for (int b = a; b > 0 && e[b] < e[b - 1]; b--) {
                long te = e[b]; e[b] = e[b - 1]; e[b - 1] = te;
                int ts2 = s[b]; s[b] = s[b - 1]; s[b - 1] = ts2;
            }
        }
        pl.setLength(0);
        for (int j = 0; j < e.length; j++) pl.append(String.format(Locale.US, " %.3gs/%d", e[j] / 1e9, s[j]));
        prefCtx = ctx.getApplicationContext();
        ring.clear();                           // buffers free for the series
        long start = targetMaster + ASTRO_DELAY_NS;
        final long syncT = start + ASTRO_SYNC_NS;
        // (target in the info file = T: "master-target_us" of the first frame = its start error)
        AstroCapture as = new AstroCapture(ctx, name, info + ", " + rawSize + ", Astro" + pl, seq, syncT, e, s,
                clock, chars, burstResults, rot, aspect, brightness);
        as.onComplete = () -> photoHandler.post(() -> completeAstro(as));
        as.toneBus = toneBus;
        as.toneRole = toneRole;
        astroSubmitNs = 0;
        astro = as;
        astroUiDoneMs = 0;
        astroUi = as;
        photoStatus = String.format(Locale.US, "astro #%d: %d frames, starting...", seq, e.length);
        long nowM = clock.toMaster(android.os.SystemClock.elapsedRealtimeNanos());
        long waitMs = nowM == Long.MIN_VALUE ? ASTRO_DELAY_NS / 1_000_000L
                : Math.max(0, (start - nowM) / 1_000_000L);
        handler.postDelayed(() -> {
            Settings a = active;
            if (as.finished) return;                     // stopped by the user before the start
            if (a == null || session == null || rawReader == null || astro != as) {
                abortAstro(as, "camera not ready");
                return;
            }
            try {
                session.stopRepeating();
                astroSubmitNs = android.os.SystemClock.elapsedRealtimeNanos();
                astroPending = as;
                astroW0Ts = 0;
                astroT = syncT;
                astroE = e;
                astroS = s;
                session.capture(buildAstroRequest(ASTRO_LEAD_NS, s[0], "W0"), callback, handler);
                session.capture(buildAstroRequest(ASTRO_LEAD_NS, s[0], "W1"), callback, handler);
                // safety: no start report of W1 -> the frames go anyway (not synchronised)
                handler.postDelayed(() -> astroQueueFrames(as, -1, "start sync FAILED (no start report): not synchronised"),
                        3000);
                photoStatus = String.format(Locale.US, "astro #%d: %d frames running (about %.0f s)", seq, e.length,
                        (as.deadlineMs - 30_000L - android.os.SystemClock.elapsedRealtime()) / 1e3);
            } catch (Exception ex) {
                abortAstro(as, ex.getMessage());
                return;
            }
            // safety: frames lost -> finish at the deadline anyway
            photoHandler.postDelayed(() -> {
                if (astro == as) completeAstro(as);
            }, Math.max(0, as.deadlineMs - android.os.SystemClock.elapsedRealtime()));
        }, waitMs);
    }

    /** Camera thread: a lead-in frame started (sensor time ts). */
    private void astroLeadStarted(String tag, long ts) {
        AstroCapture as = astroPending;
        if (as == null || astro != as) return;
        if ("W0".equals(tag)) {
            astroW0Ts = ts;
            return;
        }
        long tm = as.clock.toMaster(ts);
        if (astroW0Ts == 0 || tm == Long.MIN_VALUE) {
            astroQueueFrames(as, -1, "start sync FAILED (no W0 start / no clock): not synchronised");
            return;
        }
        long p = ts - astroW0Ts;                 // W period: exposure start to exposure start
        long gap = p - ASTRO_LEAD_NS;            // the HAL's gap after an exposure
        long w2 = tm + p;                        // master time W2 will start
        // this phone's own correction, learned from its previous series (the camera adds a phone-
        // specific pause after the spacer: 0.4 ms on one Pixel 7a, 5-7 ms on the other, 2026-11-17)
        long corr = astroCorrNs();
        long spacer = astroT - w2 - gap - corr;  // W2 exposure: the first frame starts at T
        if (spacer < ASTRO_SPACER_MIN_NS || spacer > ASTRO_SPACER_MAX_NS) {
            astroQueueFrames(as, -1, String.format(Locale.US,
                    "start sync FAILED (spacer %.1f ms out of range, W period %.3f ms): not synchronised",
                    spacer / 1e6, p / 1e6));
            return;
        }
        as.syncOk = true;
        astroQueueFrames(as, spacer, String.format(Locale.US,
                "start sync: W period %.3f ms (gap %.3f ms), spacer %.3f ms (phone correction %.3f ms) -> first frame at T",
                p / 1e6, gap / 1e6, spacer / 1e6, corr / 1e6));
    }

    /** Camera thread: spacer W2 (spacerNs, or a plain lead-in when < 0) + all frames of the series, once. */
    private void astroQueueFrames(AstroCapture as, long spacerNs, String note) {
        if (astroPending != as || astro != as) return;
        astroPending = null;
        as.syncNote = note;
        try {
            session.capture(buildAstroRequest(spacerNs > 0 ? spacerNs : ASTRO_LEAD_NS, astroS[0], "W2"), callback, handler);
            for (int j = 0; j < astroE.length; j++) {
                session.capture(buildAstroRequest(astroE[j], astroS[j], "A" + j), callback, handler);
            }
        } catch (Exception ex) {
            abortAstro(as, ex.getMessage());
        }
    }

    /** Photo thread: all frames there (or deadline): preview back on, info file written. */
    // SELF-CALIBRATION of the synchronised start (item 8): after a synchronised series the phone knows
    // how far its first frame was from the model (target: its exposure ENDS at T, the same on all
    // phones) and corrects the next spacer by 70 % of that (running average, survives restarts)
    static final double ASTRO_CORR_GAIN = 0.7;
    static final long ASTRO_CORR_MAX_NS = 50_000_000L;   // larger errors: something else went wrong
    private android.content.Context prefCtx;

    private long astroCorrNs() {
        if (prefCtx == null) return 0;
        return prefCtx.getSharedPreferences("astroSync", android.content.Context.MODE_PRIVATE).getLong("corrNs", 0);
    }

    private void learnAstroCorr(AstroCapture as) {
        if (prefCtx == null || !as.syncOk || as.a0StartMaster == Long.MIN_VALUE || as.a0ExposureNs <= 0) return;
        long err = as.a0StartMaster - as.startMaster + as.a0ExposureNs;
        if (Math.abs(err) > ASTRO_CORR_MAX_NS) return;
        long corr = astroCorrNs() + Math.round(ASTRO_CORR_GAIN * err);
        prefCtx.getSharedPreferences("astroSync", android.content.Context.MODE_PRIVATE).edit().putLong("corrNs", corr).apply();
        as.syncNote += String.format(Locale.US, "; first frame %+.3f ms from the model -> correction now %.3f ms",
                err / 1e6, corr / 1e6);
    }

    private void completeAstro(AstroCapture as) {
        if (astro != as) return;
        learnAstroCorr(as);
        astro = null;
        astroSubmitNs = 0;
        as.finished = true;
        resumePreview();
        photoStatus = String.format(Locale.US, "astro #%d: merging...", as.seq);
        burstWriting = true;                    // no new burst / series while merging (memory)
        new Thread(() -> {
            try {
                photoStatus = as.finish();
            } finally {
                burstWriting = false;
            }
            if (as.thumb != null) {
                lastJpeg = as.jpegUri;
                lastThumb = as.thumb;
            }
            lastPhotoErrNs = 0;
            lastPhotoSeq = as.seq;
            astroUiDoneMs = android.os.SystemClock.elapsedRealtime();
        }, "astro-write").start();
    }

    private void abortAstro(AstroCapture as, String why) {
        if (astro == as) astro = null;
        astroSubmitNs = 0;
        resumePreview();
        photoStatus = "astro #" + as.seq + " stopped: " + why;
        lastPhotoSeq = as.seq;
        astroUiDoneMs = android.os.SystemClock.elapsedRealtime();
    }

    private void resumePreview() {
        handler.post(() -> {
            Settings a = active;
            try {
                if (session != null && a != null) session.setRepeatingRequest(buildRequest(a, a.periodNs, "R"), callback, handler);
            } catch (Exception ignored) {
                // a new session will start the stream
            }
        });
    }

    /** Astro frames are being captured (no phase steps, no other photo). */
    /**
     * Text of the astro countdown ("starts in 3", "0:47", "merging…", "saved 3D_img_0007-l"), or
     * null when no astro series is to be shown.
     */
    String astroCountdown() {
        AstroCapture as = astroUi;
        if (as == null) return null;
        long done = astroUiDoneMs;
        if (done > 0) {
            if (android.os.SystemClock.elapsedRealtime() - done > 5000) {
                if (astroUi == as) astroUi = null;
                return null;
            }
            if (as.jpegUri == null) {
                String st = photoStatus;
                int c = st.indexOf(": ");
                return c > 0 ? st.substring(c + 2) : st;
            }
            String n = Downloads.mainName("img", as.seq, "jpg");
            n = n.substring(n.lastIndexOf('/') + 1);
            return "saved " + n.replace(".jpg", "");
        }
        if (as.finished) return "merging\u2026";
        long now = as.clock.toMaster(android.os.SystemClock.elapsedRealtimeNanos());
        if (now == Long.MIN_VALUE) return "running\u2026";
        if (now < as.startMaster) return "starts in " + ((as.startMaster - now) / 1_000_000_000L + 1);
        long left = Math.max(0, (as.endMaster - now + 999_999_999L) / 1_000_000_000L);
        return String.format(Locale.US, "%d:%02d", left / 60, left % 60);
    }

    // HDR notice on the screen (user 2026-11-17: show when the phone may be moved)
    private volatile PhotoBurst burstUi;
    private volatile boolean burstMerging;
    private volatile long burstUiDoneMs;

    /** "hold still…" / "merging images – you can move the phone" / "saved …" (3 s), or null. */
    String burstNotice() {
        PhotoBurst b = burstUi;
        if (b == null) return null;
        if (burst == b) return "hold still…";
        if (burstMerging) return "merging images – you can move the phone";
        long done = burstUiDoneMs;
        if (done > 0 && android.os.SystemClock.elapsedRealtime() - done < 3000) {
            if (b.jpegUri == null) {
                String st = photoStatus;
                int c = st.indexOf(": ");
                return c > 0 ? st.substring(c + 2) : st;
            }
            String n = Downloads.mainName("img", b.seq, "jpg");
            return "saved " + n.substring(n.lastIndexOf('/') + 1).replace(".jpg", "");
        }
        if (done > 0 && burstUi == b) burstUi = null;
        return null;
    }

    /** The user stopped the astro series (shutter on the master): all frames dropped, preview back. */
    void stopAstro() {
        handler.post(() -> {
            AstroCapture as = astro;
            if (as == null) return;
            astroPending = null;
            try {
                if (session != null) session.abortCaptures();
            } catch (Exception ignored) {
            }
            as.discard();
            abortAstro(as, "stopped");
            photoStatus = "astro #" + as.seq + ": stopped";
        });
    }

    boolean astroBusy() {
        return astro != null;
    }

    /**
     * One astro frame: RAW + preview (the preview shows the long frame). Full manual control: the
     * Pixel HAL limits the frame duration to the AE fps range unless the control mode is OFF;
     * white balance = the gains / matrix in use just before (AWB is off in this mode); focus at
     * infinity; lens shading map for the merge.
     */
    private CaptureRequest buildAstroRequest(long exposureNs, int iso, String tag) throws Exception {
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL);
        applyZoom(b);
        b.addTarget(rawReader.getSurface());
        b.addTarget(surface);
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
        Range<Integer>[] fr = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (fr != null && fr.length > 0) {
            Range<Integer> lo = fr[0];
            for (Range<Integer> r : fr) if (r.getLower() < lo.getLower()) lo = r;
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, lo);
        }
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(minFrameDurationNs, exposureNs + 20_000_000L));
        android.hardware.camera2.params.RggbChannelVector g = appliedGains;
        android.hardware.camera2.params.ColorSpaceTransform x = appliedXform;
        if (g != null && x != null) {
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, g);
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, x);
        }
        b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
        b.setTag(tag);
        return b.build();
    }

    /** The small analysis stream is part of the running session (LED test). */
    boolean hasAnalysis() {
        return reader != null;
    }

    /**
     * The white balance the camera uses now (from the latest frames) and what was asked for - written
     * into every photo's info file (2026-11-24: the colours of a Pixel 4 pair differed; the reason
     * could not be seen afterwards).
     */
    private volatile float appliedFocusD = Float.NaN;
    private volatile int appliedAfMode = -1;

    /** "focus manual 1.20 m, lens 1.18 m (0.85 dpt)": requested by the CONTROL phone / used by the lens (item 38). */
    String focusInfo() {
        Look l = look;
        String req = l == null ? "-" : l.focusManual ? "manual " + (l.diopters > 0.001f
                ? String.format(java.util.Locale.US, "%.2f m", 1 / l.diopters) : "infinity") : "auto";
        float d = appliedFocusD;
        String lens = Float.isNaN(d) ? "?" : d > 0.001f
                ? String.format(java.util.Locale.US, "%.2f m (%.2f dpt)", 1 / d, d) : "infinity (0 dpt)";
        return "focus " + req + ", lens " + lens + (appliedAfMode == CaptureResult.CONTROL_AF_MODE_OFF ? ", AF off" : ", AF on");
    }

    String wbInfo() {
        android.hardware.camera2.params.RggbChannelVector g = appliedGains;
        android.hardware.camera2.params.ColorSpaceTransform x = appliedXform;
        Look l = look;
        StringBuilder sb = new StringBuilder("white balance ").append(appliedAwbOff ? "locked (AWB off)" : "auto (AWB on)");
        sb.append(", requested ").append(l == null ? "-" : l.wbMode == WB_COPY
                ? (l.gains == null ? "shared, not locked yet" : l.xform != null ? "shared gains + matrix" : "shared gains, own matrix")
                : l.wbMode == WB_PRESET ? "preset " + l.awbPreset : "auto");
        if (calPresets != null) sb.append(", calibrating");
        if (g != null) sb.append(String.format(java.util.Locale.US, "%nwb gains R %.4f Ge %.4f Go %.4f B %.4f",
                g.getRed(), g.getGreenEven(), g.getGreenOdd(), g.getBlue()));
        if (x != null) {
            sb.append("\nwb matrix");
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++)
                sb.append(String.format(java.util.Locale.US, " %.4f", x.getElement(c, r).floatValue()));
        }
        return sb.toString();
    }

    /** Exposure (ns) x ISO of the stream now running, or -1. */
    double activeLight() {
        Settings a = active;
        return a == null ? -1 : Math.min(a.exposureNs, a.periodNs - EXPOSURE_MARGIN_NS) * (double) a.iso;
    }

    /** A burst is being collected or written (a new one would be skipped). */
    boolean burstBusy() {
        return burst != null || burstWriting || astro != null;
    }

    /**
     * RAW highlight statistics of a recent frame (burst mode): {99.5 % point, clipped fraction, 99.9 % point},
     * both relative to the sensor range (0 = black, 1 = white level); null if none.
     */
    volatile float[] rawStats;
    volatile float[] rawHist;                 // histogram of the sampled RAW values (1025 bins over 0..1)
    volatile float rawDark = Float.NaN;       // mean of the darker half of 8x8 block means (0..1)
    volatile float rawMedian = Float.NaN;
    private int rawStatsCount;

    private void updateRawStats(Image img) {
        Image.Plane p = img.getPlanes()[0];
        java.nio.ShortBuffer sb = p.getBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int stride = p.getRowStride() / 2, w = img.getWidth(), h = img.getHeight();
        Integer wl = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        android.hardware.camera2.params.BlackLevelPattern bp =
                chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        float white = wl == null ? 1023 : wl, black = bp == null ? 64 : bp.getOffsetForIndex(0, 0);
        int[] hist = new int[1025];
        int n = 0, clipped = 0;
        for (int y = 0; y < h; y += 5) {             // odd step: all four CFA colours
            for (int x = 0; x < w; x += 5) {
                float v = ((sb.get(y * stride + x) & 0xFFFF) - black) / (white - black);
                int b = Math.min(1024, Math.max(0, Math.round(v * 1024)));
                hist[b]++;
                if (v >= 0.98f) clipped++;
                n++;
            }
        }
        // 99.5 % and 99.9 % points
        int acc = 0, p995 = -1, p999 = -1;
        for (int b = 1024; b >= 0; b--) {
            acc += hist[b];
            if (p999 < 0 && acc >= n * 0.001) p999 = b;
            if (acc >= n * 0.005) {
                p995 = b;
                break;
            }
        }
        if (p995 < 0) p995 = 0;
        if (p999 < 0) p999 = p995;
        rawStats = new float[]{p995 / 1024f, clipped / (float) n, p999 / 1024f};
        // dark half: mean of the darker half of 8x8 block means (block means average the noise, so
        // the fast frame predicts the dark parts of a longer frame: lamp test 0.0415 vs 0.0409)
        int bw = w / 8, bh = h / 8;
        float[] bm = new float[bw * bh];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int s = 0;
                for (int yy = 0; yy < 8; yy += 2) {
                    int o = (by * 8 + yy) * stride + bx * 8;
                    for (int xx = 0; xx < 8; xx++) s += sb.get(o + xx) & 0xFFFF;
                }
                bm[by * bw + bx] = (s / 32f - black) / (white - black);
            }
        }
        float[] sorted = bm.clone();
        java.util.Arrays.sort(sorted);
        double sum = 0;
        int half = sorted.length / 2;
        for (int i = 0; i < half; i++) sum += sorted[i];
        rawDark = (float) (sum / Math.max(1, half));
        rawMedian = sorted[half];
        float[] hf = new float[hist.length];
        for (int b = 0; b < hist.length; b++) hf[b] = hist[b];
        rawHist = hf;
    }

    /** Photo thread: a RAW image in burst mode -> running burst, or the ring. */
    private void onBurstRaw(Image img) {
        AstroCapture as = astro;
        if (as != null) {
            // frames of the series: those that started after the requests were queued (the
            // preview frames still in flight are older)
            long sub = astroSubmitNs;
            if (sub > 0 && img.getTimestamp() >= sub && !as.finished) {
                as.offer(img);                // the writer keeps only the astro requests' frames
            } else {
                img.close();
            }
            return;
        }
        // RAW statistics (16 MP): every 10th frame only while AE runs (statsWanted), else every 90th
        // (~3 s) - computed continuously they took 35 % of a big CPU core (~0.3 W, 2026-11-17)
        if (burst == null && !burstWriting && (rawStatsCount++ % (statsWanted ? 10 : 90)) == 0) {
            try {
                updateRawStats(img);
            } catch (Exception ignored) {
            }
        }
        PhotoBurst b = burst;
        if (b != null) {
            b.offer(img);                     // keeps or closes it
            checkBurst(b);
        } else if (burstWriting) {
            img.close();                      // buffers are held by the frames being written
        } else {
            ring.add(img);
        }
    }

    /** Photo thread: when the burst is complete, write it on its own thread. */
    private void checkBurst(PhotoBurst b) {
        long nowMaster = b.clock.toMaster(android.os.SystemClock.elapsedRealtimeNanos());
        if (!b.complete(nowMaster)) return;
        burst = null;
        burstWriting = true;
        b.finished = true;
        burstMerging = true;
        new Thread(() -> {
            String st;
            try {
                st = b.finish(keepBurstFrames);
            } finally {
                burstMerging = false;
                burstUiDoneMs = android.os.SystemClock.elapsedRealtime();
            }
            if (b.thumb != null) {
                lastJpeg = b.jpegUri;
                lastThumb = b.thumb;
            }
            photoStatus = st;
            lastPhotoErrNs = b.refErrorNs();
            lastPhotoSeq = b.seq;
            burstWriting = false;
        }, "burst-write").start();
    }

    private int photoRotation;

    private int rotationFor(int deviceDegrees) {
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
        int dev = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? -deviceDegrees : deviceDegrees;
        return ((so == null ? 0 : so) + dev + 360) % 360;
    }

    /** Camera thread: switch the JPEG stream off and save the best frame a bit later. */
    private void endPhotoWindow() {
        if (!photoWindow) return;
        photoWindow = false;
        Settings a = active;
        try {
            if (session != null && a != null) {
                session.setRepeatingRequest(buildRequest(a, a.periodNs, "R"), callback, handler);
            }
        } catch (Exception ignored) {
        }
        final PhotoCapture p = photo;
        photoHandler.postDelayed(() -> {
            photoStatus = p.finish();
            if (p.thumb != null) {
                lastJpeg = p.jpegUri;
                lastThumb = p.thumb;
            }
            lastPhotoErrNs = p.errorNs();
            lastPhotoSeq = p.seq;
            photo = null;
        }, 1500);
    }

    String status() {
        return status;
    }

    boolean isStreaming() {
        return active != null;
    }

    Settings activeSettings() {
        return active;
    }

    long minFrameDurationNs() {
        return minFrameDurationNs;
    }

    /** Starts streaming, or restarts if the settings changed. Safe to call repeatedly. */
    void start(Settings s) {
        if (s.same(settings)) return;
        Settings old = settings;
        settings = s;
        if (s.sameSession(old) && active != null) {
            // exposure / ISO only: new repeating request, the camera keeps running (phase lock too)
            handler.post(() -> {
                if (active != null && settings == s) {
                    active = s;
                    refreshRepeating();
                }
            });
            return;
        }
        handler.post(this::reopen);
    }

    void stop() {
        if (settings == null && active == null) return;
        settings = null;
        handler.post(() -> {
            close();
            status = "idle";
        });
    }

    // ------------------------------------------------------------------ video

    boolean isRecording() {
        VideoRecorder r = recorder;
        return r != null && r.isRecording();
    }

    /** The current (or last) recorder, for status display; may be null. */
    VideoRecorder recorder() {
        return recorder;
    }

    /** Starts recording without restarting the camera. */
    /**
     * Starts recording without restarting the camera. deviceDegrees = physical device rotation
     * (OrientationEventListener, snapped to 0/90/180/270) -> rotation flag of the MP4.
     */
    void startRecording(android.content.Context ctx, String name, String info, LedCapture.Clock clock,
                        int deviceDegrees, long startMaster, long soundOffsetNs, int conformFps,
                        int bitrateBps, boolean hevc) {
        handler.post(() -> {
            Settings a = active;
            if (recording || recorder == null || session == null || a == null) return;
            if (a.photoMode) {
                status = "photo mode: no video";
                return;
            }
            try {
                Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                int dev = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT
                        ? -deviceDegrees : deviceDegrees;
                int rotation = ((so == null ? 0 : so) + dev + 360) % 360;
                recorder.configure(bitrateBps, hevc);
                recorder.begin(ctx, name, info + ", device " + deviceDegrees + " deg, " + recorder.modeText(), clock, rotation,
                        startMaster, soundOffsetNs, a.periodNs, conformFps);
                recording = true;
                session.setRepeatingRequest(buildRequest(a, a.periodNs, "R"), callback, handler);
            } catch (Exception e) {
                recording = false;
                status = "cannot start recording: " + e.getMessage();
                new Thread(recorder::end).start();
            }
        });
    }

    /** Master time of the last frame to record; 0 = stop now. The recording ends by itself. */
    void setRecordingStop(long stopMaster) {
        VideoRecorder r = recorder;
        if (r == null || !r.isRecording()) return;
        r.setStop(stopMaster);
        if (stopMaster == 0) handler.post(this::stopRecordingOnThread);
    }

    private void stopRecordingOnThread() {
        if (!recording) return;
        recording = false;
        Settings a = active;
        try {
            if (session != null && a != null) {
                session.setRepeatingRequest(buildRequest(a, a.periodNs, "R"), callback, handler);
            }
        } catch (Exception ignored) {
        }
        // let the frames in flight reach the encoder, then finish the file off the camera thread
        final VideoRecorder r = recorder;
        handler.postDelayed(() -> new Thread(r::end, "video-end").start(), 500);
        if (reopenAfterRecording) {
            reopenAfterRecording = false;
            handler.postDelayed(this::reopen, 900);
        }
    }

    private boolean reopenAfterRecording;     // camera thread: preview surface replaced while recording

    void release() {
        settings = null;
        handler.post(() -> {
            stopRecordingOnThread();
            if (recorder != null) {
                final VideoRecorder r = recorder;
                recorder = null;
                new Thread(r::release, "video-release").start();
            }
            close();
            thread.quitSafely();
            analysisThread.quitSafely();
            photoThread.quitSafely();
        });
    }

    /**
     * Requests one frame with duration period + delta (call on the camera thread, i.e. from
     * onFrame). Returns the tag of the step frame, or null if not possible.
     */
    String requestStep(long deltaNs) {
        Settings a = active;
        if (a == null || session == null) return null;
        // no phase correction while an HDR burst is collected: all 8 frames keep the same timing
        if (burst != null || astro != null) return null;
        long d = a.periodNs + deltaNs;
        // With the full-resolution JPEG stream in the session the camera refuses frames shorter than
        // the JPEG minimum even when the JPEG stream is not used (photo mode: use 29.9 fps).
        long limit = recording ? Math.max(minFrameDurationNs, videoMinFrameDurationNs)
                : a.photoMode ? Math.max(minFrameDurationNs, jpegMinFrameDurationNs) : minFrameDurationNs;
        if (recording || photoWindow) {
            // never insert a whole extra frame period into a recording (that drops a video frame);
            // a too large negative step is clamped to the shortest allowed frame (partial step)
            if (deltaNs > a.periodNs / 2) return null;
            if (d < limit) {
                if (a.periodNs <= limit) return null;
                d = limit;
            }
        } else if (d < limit) {
            return null;
        }
        String tag = "S" + (++stepCounter) + (recording ? VIDEO_SUFFIX : "");
        try {
            session.capture(buildRequest(a, d, tag), callback, handler);
            return tag;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ camera thread

    private void close() {
        lookFrames = 0;
        activePhysicalId = null;
        if (recording) {
            recording = false;
            final VideoRecorder r = recorder;
            new Thread(r::end, "video-end").start();
        }
        generation++;
        active = null;
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (device != null) device.close();
        } catch (Exception ignored) {
        }
        if (photoWindow) {
            photoWindow = false;
            final PhotoCapture p = photo;
            photoHandler.post(() -> {
                photoStatus = p.finish();
                photo = null;
            });
        }
        // burst: the ring images belong to the reader that is closed now
        photoHandler.post(() -> {
            ring.clear();
            PhotoBurst b = burst;
            burst = null;
            if (b != null) {
                b.finished = true;
                photoStatus = "burst #" + b.seq + ": camera restarted";
            }
        });
        if (surface != null) surface.release();
        if (reader != null) reader.close();
        if (jpegReader != null) jpegReader.close();
        if (rawReader != null) rawReader.close();
        if (yuvReader != null) yuvReader.close();
        session = null;
        device = null;
        surface = null;
        reader = null;
        jpegReader = null;
        rawReader = null;
        yuvReader = null;
    }

    @SuppressLint("MissingPermission")
    private void reopen() {
        close();
        final Settings s = settings;
        if (s == null) return;
        if (!view.isAvailable()) {
            status = "waiting for preview";
            handler.postDelayed(this::reopen, 200);
            return;
        }
        try {
            chars = cm.getCameraCharacteristics(s.cameraId);
            prepare(s.periodNs, s.videoHeight, s.photoMode, s.appJpeg);
            final int gen = generation;
            status = "opening camera " + s.cameraId + (s.zoom != 1f ? " zoom " + s.zoom : "");
            cm.openCamera(s.cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    if (gen != generation || settings != s) {
                        d.close();
                        return;
                    }
                    device = d;
                    createSession(s, gen);
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (gen == generation) {
                        active = null;
                        status = "camera disconnected";
                        retryOpen();
                    }
                }
                @Override public void onError(CameraDevice d, int error) {
                    d.close();
                    if (gen == generation) {
                        active = null;
                        status = "camera error " + error;
                        retryOpen();
                    }
                }
            }, handler);
        } catch (Exception e) {
            status = "cannot open camera: " + e.getMessage();
        }
    }

    /** Camera thread: open again a little later (e.g. another app had the camera). */
    private void retryOpen() {
        handler.postDelayed(() -> {
            if (settings != null && active == null && !recording) reopen();
        }, 1000);
    }

    /**
     * The activity is visible again: the preview surface may be new and the camera may have been
     * taken by another app in the meantime -> open the camera again (not during a recording).
     */
    void onResume() {
        handler.post(() -> {
            if (settings == null) return;
            if (recording) {
                reopenAfterRecording = true;
                return;
            }
            if (view.isAvailable()) reopen();
        });
    }

    /** Size of the preview stream (landscape sensor orientation), or null. */
    Size previewSize() {
        return active != null ? size : null;
    }

    private void prepare(long periodNs, int videoHeight, boolean photoMode, boolean appJpeg) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        Size best = null;
        long bestArea = -1;
        // preview with the aspect of the output: photo 4:3 (whole sensor), video 16:9
        double want = photoMode ? 4.0 / 3 : 16.0 / 9;
        // leave room for steps down to half a period; a camera that runs no size faster than the
        // frame rate (Xreal Beam Pro: 30 fps max, 2026-11-20 - the smallest size was taken: blurry
        // preview) gets the best size at the frame rate: the aligner then steps only forwards
        // (delta + one period), which works as well
        for (int pass = 0; pass < 2 && best == null; pass++) {
            long maxDur = pass == 0 ? periodNs / 2 : (long) (periodNs * 1.005);
            for (Size z : sizes) {
                if (z.getWidth() > 1920 || z.getHeight() > 1080) continue;
                if (Math.abs(z.getWidth() / (double) z.getHeight() / want - 1) > 0.01) continue;
                if (map.getOutputMinFrameDuration(SurfaceTexture.class, z) > maxDur) continue;
                long area = (long) z.getWidth() * z.getHeight();
                if (area > bestArea) {
                    best = z;
                    bestArea = area;
                }
            }
        }
        if (best == null) best = sizes[sizes.length - 1];
        size = best;
        minFrameDurationNs = map.getOutputMinFrameDuration(SurfaceTexture.class, size);

        // Pixel HAL clamps SENSOR_FRAME_DURATION to the AE target fps range even with AE off.
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        fpsRange = null;
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (fpsRange == null || r.getUpper() > fpsRange.getUpper()
                        || (r.getUpper().equals(fpsRange.getUpper()) && r.getLower() < fpsRange.getLower())) {
                    fpsRange = r;
                }
            }
        }
        // small 4:3 YUV stream for image analysis: 4:3 covers the full sensor height, so image
        // row r corresponds to the rolling-shutter position (r + 0.5) / height
        analysisSize = null;
        Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (yuv != null) {
            for (Size z : yuv) {
                if (z.getWidth() * 3 != z.getHeight() * 4 || z.getWidth() < ANALYSIS_WIDTH) continue;
                if (analysisSize == null || z.getWidth() < analysisSize.getWidth()) analysisSize = z;
            }
        }

        // video encoder size: 16:9 with the requested height (e.g. 3840x2160), else the preview size
        videoSize = size;
        Size[] vs = map.getOutputSizes(android.media.MediaCodec.class);
        if (vs != null) {
            for (Size z : vs) {
                if (z.getHeight() == videoHeight && z.getWidth() * 9 == z.getHeight() * 16) {
                    videoSize = z;
                    break;
                }
            }
        }
        videoMinFrameDurationNs = map.getOutputMinFrameDuration(android.media.MediaCodec.class, videoSize);

        // video encoder (re-created only if size or frame rate change)
        int fps = (int) Math.round(1e9 / periodNs);
        if (recorder == null || recorder.width != videoSize.getWidth() || recorder.height != videoSize.getHeight()
                || recorder.fps != fps) {
            if (recorder != null) {
                final VideoRecorder old = recorder;
                new Thread(old::release, "video-release").start();
            }
            Integer orient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            try {
                recorder = new VideoRecorder(videoSize.getWidth(), videoSize.getHeight(), fps,
                        orient == null ? 0 : orient);
            } catch (Exception e) {
                recorder = null;
            }
        }

        // photo mode: largest 4:3 JPEG that keeps the frame rate (Pixel 7a: 4624x3472 = 16 MP)
        jpegSize = null;
        yuvSize = null;
        jpegMinFrameDurationNs = 0;
        if (photoMode) {
            Size[] js = map.getOutputSizes(ImageFormat.JPEG);
            long bestA = -1, largest = -1;
            boolean largestStalls = false;
            if (js != null) {
                for (Size z : js) {
                    // about 4:3 (4624x3472 is 1.3318, not exactly 4:3)
                    if (Math.abs(z.getWidth() * 3.0 / (z.getHeight() * 4.0) - 1) > 0.01) continue;
                    long ar = (long) z.getWidth() * z.getHeight();
                    if (ar > largest) {
                        largest = ar;
                        largestStalls = map.getOutputStallDuration(ImageFormat.JPEG, z) > 0;
                    }
                    // 0.5 % tolerance: 4624x3472 reports a hair more than 33,333,333 ns
                    if (map.getOutputMinFrameDuration(ImageFormat.JPEG, z) > periodNs * 1.005) continue;
                    if (map.getOutputStallDuration(ImageFormat.JPEG, z) > 0) continue;
                    if (ar > bestA) {
                        bestA = ar;
                        jpegSize = z;
                    }
                }
            }
            jpegStalls = largestStalls;
            // no camera JPEG size keeps the frame rate (all stall: Xreal Beam Pro, 2026-11-20): the
            // in-app JPEG by itself, no setting needed (Pixel 4: the setting, as before)
            appJpegAuto = !appJpeg && jpegSize == null && largestStalls;
            if (appJpeg || appJpegAuto) {
                // in-app JPEG: largest about-4:3 YUV size that keeps the frame rate (no stall)
                jpegSize = null;
                Size[] ys = map.getOutputSizes(ImageFormat.YUV_420_888);
                long bestY = -1;
                if (ys != null) {
                    for (Size z : ys) {
                        if (Math.abs(z.getWidth() * 3.0 / (z.getHeight() * 4.0) - 1) > 0.01) continue;
                        if (map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, z) > periodNs * 1.005) continue;
                        if (map.getOutputStallDuration(ImageFormat.YUV_420_888, z) > 0) continue;
                        long ar = (long) z.getWidth() * z.getHeight();
                        if (ar > bestY) {
                            bestY = ar;
                            yuvSize = z;
                        }
                    }
                }
                if (yuvSize != null) {
                    jpegMinFrameDurationNs = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, yuvSize);
                }
            } else if (jpegSize != null) {
                jpegMinFrameDurationNs = map.getOutputMinFrameDuration(ImageFormat.JPEG, jpegSize);
            }
            // RAW: the largest RAW_SENSOR size (full sensor, e.g. 4624x3472)
            rawSize = null;
            Size[] rs = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            if (rs != null) {
                for (Size z : rs) {
                    if (rawSize == null || (long) z.getWidth() * z.getHeight()
                            > (long) rawSize.getWidth() * rawSize.getHeight()) rawSize = z;
                }
            }
            if (rawSize != null) {
                jpegMinFrameDurationNs = Math.max(jpegMinFrameDurationNs,
                        map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rawSize));
            }
        }

        afContinuous = CameraInfo.has(chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        afAuto = CameraInfo.has(chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AF_MODE_AUTO);
    }

    @SuppressWarnings("deprecation")
    private void createSession(Settings s, int gen) {
        try {
            SurfaceTexture st = view.getSurfaceTexture();
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            surface = new Surface(st);
            List<Surface> outputs = new ArrayList<>();
            outputs.add(surface);
            // the small analysis stream (LED test) is left out with RAW: not too many streams
            if (s.analysis && analysisSize != null && !(s.photoMode && s.raw() && rawSize != null)) {
                reader = ImageReader.newInstance(analysisSize.getWidth(), analysisSize.getHeight(),
                        ImageFormat.YUV_420_888, 4);
                final ImageReader rd = reader;
                reader.setOnImageAvailableListener(r -> {
                    Image img;
                    try {
                        img = r.acquireNextImage();
                    } catch (Exception e) {
                        return;
                    }
                    if (img == null) return;
                    try {
                        Analyzer a = analyzer;
                        if (a != null && rd == reader) a.onImage(img);
                    } catch (Exception ignored) {
                    } finally {
                        img.close();
                    }
                }, analysisHandler);
                outputs.add(reader.getSurface());
            }
            if (s.photoMode && s.raw() && rawSize != null) {
                // burst: ring (8) + spare buffers so the stream never waits for us
                final boolean bm = s.burst();
                rawReader = ImageReader.newInstance(rawSize.getWidth(), rawSize.getHeight(), ImageFormat.RAW_SENSOR,
                        bm ? PhotoBurst.RING + PhotoBurst.FRAMES / 2 + 1 : 4);
                rawReader.setOnImageAvailableListener(r -> {
                    Image img;
                    try {
                        img = r.acquireNextImage();
                    } catch (Exception e) {
                        return;
                    }
                    if (img == null) return;
                    if (bm) {
                        try {
                            onBurstRaw(img);
                        } catch (Exception e) {
                            img.close();
                        }
                        return;
                    }
                    PhotoCapture p = photo;
                    boolean kept = false;
                    try {
                        kept = p != null && !p.finished && p.onRaw(img);
                    } catch (Exception ignored) {
                    } finally {
                        if (!kept) img.close();
                    }
                }, photoHandler);
                outputs.add(rawReader.getSurface());
            }
            if (s.photoMode && s.jpeg() && (s.appJpeg || appJpegAuto) && yuvSize != null) {
                yuvReader = ImageReader.newInstance(yuvSize.getWidth(), yuvSize.getHeight(),
                        ImageFormat.YUV_420_888, 3);
                yuvReader.setOnImageAvailableListener(r -> {
                    Image img;
                    try {
                        img = r.acquireNextImage();
                    } catch (Exception e) {
                        return;
                    }
                    if (img == null) return;
                    PhotoCapture p = photo;
                    boolean kept = false;
                    try {
                        kept = p != null && !p.finished && p.onYuv(img);
                    } catch (Exception ignored) {
                    } finally {
                        if (!kept) img.close();
                    }
                }, photoHandler);
                outputs.add(yuvReader.getSurface());
            } else if (s.photoMode && s.jpeg() && !s.appJpeg && !appJpegAuto && jpegSize != null) {
                jpegReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 3);
                jpegReader.setOnImageAvailableListener(r -> {
                    Image img;
                    try {
                        img = r.acquireNextImage();
                    } catch (Exception e) {
                        return;
                    }
                    if (img == null) return;
                    try {
                        PhotoCapture p = photo;
                        if (p != null && !p.finished) p.onJpeg(img);
                    } catch (Exception ignored) {
                    } finally {
                        img.close();
                    }
                }, photoHandler);
                outputs.add(jpegReader.getSurface());
            } else if (!s.photoMode && recorder != null) {
                outputs.add(recorder.surface());
            }
            device.createCaptureSession(outputs,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession cs) {
                            if (gen != generation) {
                                cs.close();
                                return;
                            }
                            session = cs;
                            try {
                                cs.setRepeatingRequest(buildRequest(s, s.periodNs, "R"), callback, handler);
                                active = s;
                                status = String.format(Locale.US, "streaming %s @ %.3f fps, %s %s",
                                        size, 1e9 / s.periodNs, s.photoMode ? "photo" : "video",
                                        s.photoMode ? photoSize() + (yuvReader != null ? " YUV (in-app JPEG)"
                                                : s.jpeg() && jpegReader == null && jpegStalls
                                                ? " (camera JPEG stalls: Advanced > Photo JPEG: app)" : "")
                                                : videoSize);
                            } catch (Exception e) {
                                status = "cannot start stream: " + e.getMessage();
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession cs) {
                            status = "session configuration failed";
                        }
                    }, handler);
        } catch (Exception e) {
            status = "cannot create session: " + e.getMessage();
        }
    }

    private CaptureRequest buildRequest(Settings s, long frameDurationNs, String tag) throws Exception {
        return buildRequest(s, frameDurationNs, tag, 0, 0);
    }

    /** longExposureNs > 0: an HDR-burst longer frame (RAW target only, that exposure and ISO). */
    private CaptureRequest buildRequest(Settings s, long frameDurationNs, String tag, long longExposureNs, int longIso)
            throws Exception {
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        applyZoom(b);
        if (longExposureNs > 0) {
            b.addTarget(rawReader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Math.min(longExposureNs, frameDurationNs - EXPOSURE_MARGIN_NS));
            b.set(CaptureRequest.SENSOR_SENSITIVITY, longIso > 0 ? longIso : s.iso);
            applyTone(b, s.tone);
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            applyOis(b, s);
            applyLook(b);
            b.setTag(tag);
            return b.build();
        }
        b.addTarget(surface);
        if (reader != null) b.addTarget(reader.getSurface());
        if (recording && recorder != null) {
            b.addTarget(recorder.surface());
            if (!tag.endsWith(VIDEO_SUFFIX)) tag += VIDEO_SUFFIX;
        }
        if (photoWindow && jpegReader != null) {
            b.addTarget(jpegReader.getSurface());
            b.set(CaptureRequest.JPEG_ORIENTATION, photoRotation);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) PhotoCapture.jpegQuality);
        }
        // "Sharpening: Off" also for the camera's own JPEG / the app JPEG from YUV (the camera's
        // edge enhancement; its strength cannot be set - Low .. Strong leave the camera default)
        if (photoWindow && MergeCore.sharpLevel == 0) b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
        if (rawReader != null && (photoWindow || s.burst())) b.addTarget(rawReader.getSurface());
        if (photoWindow && yuvReader != null) b.addTarget(yuvReader.getSurface());
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs);
        // the exposure must fit into the frame (else the camera lengthens the frame and the timing
        // is lost): up to the period minus a margin; a shortened step frame (locking) gets a
        // shorter exposure for that one frame only
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Math.min(s.exposureNs,
                Math.min(s.periodNs, frameDurationNs) - EXPOSURE_MARGIN_NS));
        b.set(CaptureRequest.SENSOR_SENSITIVITY, s.iso);
        applyTone(b, s.tone);
        if (s.burst()) {
            // the RAW frames have no lens shading correction: the merge applies this map
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
        }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        applyOis(b, s);
        applyLook(b);
        b.setTag(tag);
        return b.build();
    }

    /** Photo mode: optical stabilisation on (if the lens has it); video: the camera's default. */
    private void applyOis(CaptureRequest.Builder b, Settings s) {
        if (!s.photoMode || chars == null) return;
        int[] modes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (modes == null) return;
        for (int m : modes) {
            if (m == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ tone curve

    /** Frame time not available for the exposure (readout / blanking reserve). */
    static final long EXPOSURE_MARGIN_NS = 1_200_000L;
    static final String[] TONES = {"standard", "soft", "strong"};
    private static final float[][] toneCurves = new float[3][];

    /**
     * Highlight roll-off curve (linear in -> sRGB out). Exposure for the highlights (AE) leaves the
     * scene ~1-2 EV darker; this curve lifts mid-tones and shadows by b = 2 (soft) or 4 (strong)
     * and compresses the highlights (extended Reinhard, white point 1 -> 1), so bright areas keep
     * their detail instead of clipping. Same curve for R, G, B.
     */
    private static float[] toneCurve(int tone, int maxPoints) {
        if (toneCurves[tone] != null) return toneCurves[tone];
        double boost = tone == 2 ? 4 : 2;
        // evenly spaced points, as many as allowed: the Pixel HAL resamples the curve on a
        // uniform grid (unevenly spaced points gave a blotchy, posterized image)
        int n = Math.max(32, Math.min(256, maxPoints));
        float[] c = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double xs = i / (double) (n - 1);
            double xb = xs * boost;
            double y = xb * (1 + xb / (boost * boost)) / (1 + xb);
            double srgb = y <= 0.0031308 ? 12.92 * y : 1.055 * Math.pow(y, 1 / 2.4) - 0.055;
            c[2 * i] = (float) xs;
            c[2 * i + 1] = (float) Math.max(0, Math.min(1, srgb));
        }
        toneCurves[tone] = c;
        return c;
    }

    private void applyTone(CaptureRequest.Builder b, int tone) {
        if (tone <= 0 || tone >= TONES.length) return;
        if (!CameraInfo.has(chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
                CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)) return;
        Integer mp = chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
        float[] c = toneCurve(tone, mp == null ? 64 : mp);
        b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
        b.set(CaptureRequest.TONEMAP_CURVE, new android.hardware.camera2.params.TonemapCurve(c, c, c));
    }

    // ------------------------------------------------------------------ white balance + focus

    static final int WB_AUTO = 0, WB_PRESET = 1, WB_COPY = 2;

    /** White balance and focus: changes only the repeating request (no camera restart). */
    static final class Look {
        final int wbMode, awbPreset;          // awbPreset = CONTROL_AWB_MODE_* for WB_PRESET
        final float[] gains;                  // WB_COPY: R, Geven, Godd, B (null = not locked yet)
        final int[] xform;                    // WB_COPY: 3x3 rationals as 18 ints
        final boolean focusManual;
        final float diopters;                 // manual focus distance (1 / metres; 0 = infinity)

        Look(int wbMode, int awbPreset, float[] gains, int[] xform, boolean focusManual, float diopters) {
            this.wbMode = wbMode;
            this.awbPreset = awbPreset;
            this.gains = gains;
            this.xform = xform;
            this.focusManual = focusManual;
            this.diopters = diopters;
        }

        boolean same(Look o) {
            return o != null && o.wbMode == wbMode && o.awbPreset == awbPreset
                    && java.util.Arrays.equals(o.gains, gains) && java.util.Arrays.equals(o.xform, xform)
                    && o.focusManual == focusManual && o.diopters == diopters;
        }
    }

    interface FocusCallback {
        void onFocused(float diopters, boolean sharp);
    }

    private volatile Look look = new Look(WB_AUTO, CaptureRequest.CONTROL_AWB_MODE_AUTO, null, null, false, 0);
    private volatile android.hardware.camera2.params.MeteringRectangle tapRegion;
    private volatile FocusCallback tapCallback;
    private volatile long tapStartNs;
    private volatile boolean tapTriggered;
    private boolean afTriggerNext;            // camera thread: next built request starts the AF scan
    // latest values measured by this camera (for "copy" white balance and the focus display)
    volatile android.hardware.camera2.params.RggbChannelVector lastGains;
    volatile android.hardware.camera2.params.ColorSpaceTransform lastXform;
    volatile float lastFocusDiopters = -1;
    volatile android.hardware.camera2.params.RggbChannelVector appliedGains;   // in use (any mode)
    volatile boolean appliedAwbOff;

    // white balance calibration: the camera applies its presets one by one; gains/matrix recorded
    interface WbCalCallback {
        void done(float[][] gains, int[][] xforms);      // null entries = preset not measured
    }

    private volatile int[] calPresets;
    private int calIdx, calFrames;
    private float[][] calGains;
    private int[][] calXforms;
    private WbCalCallback calCallback;
    private long calStartNs;

    void calibrateWb(int[] presets, WbCalCallback cb) {
        handler.post(() -> {
            if (active == null || session == null || calPresets != null) {
                cb.done(null, null);
                return;
            }
            calIdx = 0;
            calFrames = 0;
            calGains = new float[presets.length][];
            calXforms = new int[presets.length][];
            calCallback = cb;
            calStartNs = android.os.SystemClock.elapsedRealtimeNanos();
            calPresets = presets;
            refreshRepeating();
        });
    }

    /** Camera thread: one result during the calibration. */
    private void onCalResult(TotalCaptureResult result) {
        int[] presets = calPresets;
        Integer awb = result.get(CaptureResult.CONTROL_AWB_MODE);
        boolean timeout = android.os.SystemClock.elapsedRealtimeNanos() - calStartNs > 15_000_000_000L;
        if (!timeout && (awb == null || awb != presets[calIdx])) return;     // request still in flight
        android.hardware.camera2.params.RggbChannelVector g = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        android.hardware.camera2.params.ColorSpaceTransform x = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        if (!timeout && ++calFrames < 12) return;                              // let the preset settle
        if (!timeout && g != null && x != null) {
            calGains[calIdx] = new float[]{g.getRed(), g.getGreenEven(), g.getGreenOdd(), g.getBlue()};
            int[] xi = new int[18];
            x.copyElements(xi, 0);
            calXforms[calIdx] = xi;
        }
        calIdx++;
        calFrames = 0;
        if (timeout || calIdx >= presets.length) {
            WbCalCallback cb = calCallback;
            calPresets = null;
            refreshRepeating();
            cb.done(calGains, calXforms);
        } else {
            refreshRepeating();
        }
    }

    /** Applies white balance / focus settings (only rebuilds the repeating request if changed). */
    // shared gains without a colour matrix (CONTROL only phone, 2026-11-22): this camera's own matrix
    // at the moment of the lock (its automatic one; the same model gives the same matrices)
    private volatile android.hardware.camera2.params.ColorSpaceTransform copyXform;

    void setLook(Look l) {
        if (l.same(look)) return;
        if (l.wbMode == WB_COPY && l.gains != null && l.xform == null) {
            if (copyXform == null) copyXform = lastXform;
        } else {
            copyXform = null;
        }
        look = l;
        handler.post(this::refreshRepeating);
    }

    private void refreshRepeating() {
        Settings a = active;
        if (session == null || a == null) return;
        try {
            session.setRepeatingRequest(buildRequest(a, a.periodNs, "R"), callback, handler);
        } catch (Exception ignored) {
        }
    }

    /**
     * Tap to focus at (u, v) = position in the preview view, 0..1, shown with the display
     * rotation displayDeg; the point is rotated back into sensor coordinates.
     */
    void tapFocus(float u, float v, int displayDeg, FocusCallback cb) {
        handler.post(() -> {
            Settings a = active;
            android.graphics.Rect arr = chars == null ? null
                    : chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (a == null || session == null || arr == null) return;
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            float nx, ny;
            switch (((so == null ? 0 : so) - displayDeg + 360) % 360) {
                case 90: nx = v; ny = 1 - u; break;
                case 180: nx = 1 - u; ny = 1 - v; break;
                case 270: nx = 1 - v; ny = u; break;
                default: nx = u; ny = v;
            }
            // the preview is a centred crop of the sensor with the preview's aspect ratio
            float aspect = size.getWidth() / (float) size.getHeight();
            float cw = arr.width(), ch = arr.width() / aspect;
            if (ch > arr.height()) {
                ch = arr.height();
                cw = ch * aspect;
            }
            float x = arr.left + (arr.width() - cw) / 2 + nx * cw;
            float y = arr.top + (arr.height() - ch) / 2 + ny * ch;
            int half = (int) (arr.width() * 0.05f);
            int l = (int) Math.max(arr.left, x - half), t = (int) Math.max(arr.top, y - half);
            int r = (int) Math.min(arr.right - 1, x + half), bo = (int) Math.min(arr.bottom - 1, y + half);
            tapRegion = new android.hardware.camera2.params.MeteringRectangle(l, t, r - l, bo - t,
                    android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX);
            tapCallback = cb;
            tapTriggered = false;
            tapStartNs = android.os.SystemClock.elapsedRealtimeNanos();
            try {
                session.setRepeatingRequest(buildRequest(a, a.periodNs, "R"), callback, handler);
                // one ordinary frame (all outputs, same duration) that also starts the AF scan
                afTriggerNext = true;
                CaptureRequest trig = buildRequest(a, a.periodNs, "AF");
                afTriggerNext = false;
                session.capture(trig, callback, handler);
            } catch (Exception e) {
                afTriggerNext = false;
                tapRegion = null;
            }
        });
    }

    private void applyLook(CaptureRequest.Builder b) {
        Look l = look;
        int[] cal = calPresets;
        if (cal != null) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, cal[Math.min(calIdx, cal.length - 1)]);
        } else if (l.wbMode == WB_COPY && l.gains != null && (l.xform != null || copyXform != null)) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, new android.hardware.camera2.params.RggbChannelVector(
                    l.gains[0], l.gains[1], l.gains[2], l.gains[3]));
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, l.xform != null
                    ? new android.hardware.camera2.params.ColorSpaceTransform(l.xform) : copyXform);
        } else if (l.wbMode == WB_PRESET) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, l.awbPreset);
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
        android.hardware.camera2.params.MeteringRectangle tr = tapRegion;
        if (tr != null && afAuto) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_REGIONS, new android.hardware.camera2.params.MeteringRectangle[]{tr});
        } else if (l.focusManual) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, l.diopters);
        } else if (afContinuous) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }
        if (afTriggerNext) b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
    }

    /** Camera thread: measured values and the tap-to-focus state machine. */
    volatile String activePhysical = "-";       // lens check: the physical sensor delivering the frames
    volatile String activePhysicalId;           // (null = not a logical camera / not known yet)
    volatile int lookFrames;                    // results of the current session (lens check)

    private void onLookResult(CaptureRequest request, TotalCaptureResult result) {
        if (calPresets != null) onCalResult(result);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            String ph = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
            activePhysicalId = ph;
            lookFrames++;
            Float zr = android.os.Build.VERSION.SDK_INT >= 30 ? result.get(CaptureResult.CONTROL_ZOOM_RATIO) : null;
            String now = (ph == null ? "-" : ph) + " zoom " + zr;
            if (!now.equals(activePhysical)) {
                activePhysical = now;
                android.util.Log.i("CamSyncLens", "active physical camera " + now + " tag " + request.getTag());
            }
        }
        android.hardware.camera2.params.RggbChannelVector g = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        android.hardware.camera2.params.ColorSpaceTransform x = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        Integer awb = result.get(CaptureResult.CONTROL_AWB_MODE);
        if (g != null) appliedGains = g;
        if (x != null) appliedXform = x;
        if (awb != null) appliedAwbOff = awb == CaptureResult.CONTROL_AWB_MODE_OFF;
        Float lfd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);     // item 38: focus in the info file
        Integer afm = result.get(CaptureResult.CONTROL_AF_MODE);
        if (lfd != null) appliedFocusD = lfd;
        if (afm != null) appliedAfMode = afm;
        if (g != null && x != null && awb != null && awb != CaptureResult.CONTROL_AWB_MODE_OFF) {
            lastGains = g;                    // only values found by the automatic white balance
            lastXform = x;
        }
        Float fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (fd != null) lastFocusDiopters = fd;
        if (tapRegion == null) return;
        Object tg = request.getTag();
        if (tg != null && tg.toString().startsWith("AF")) tapTriggered = true;
        Integer st = result.get(CaptureResult.CONTROL_AF_STATE);
        boolean locked = st != null && (st == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                || st == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
        boolean timeout = android.os.SystemClock.elapsedRealtimeNanos() - tapStartNs > 3_000_000_000L;
        if ((tapTriggered && locked) || timeout) {
            FocusCallback cb = tapCallback;
            tapRegion = null;
            tapCallback = null;
            if (cb != null) cb.onFocused(fd != null ? fd : 0,
                    st != null && st == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED);
        }
    }

    private final CameraCaptureSession.CaptureCallback callback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureStarted(CameraCaptureSession cs, CaptureRequest request, long timestamp, long frameNumber) {
            if (cs != session) return;
            Object tag = request.getTag();
            AstroCapture asL = astro;                      // item 8: log the lead-in / spacer timing
            if (asL != null && tag != null && tag.toString().startsWith("W")) {
                asL.leadStarted(tag.toString(), asL.clock.toMaster(timestamp));
            }
            if (asL != null && tag != null && tag.toString().startsWith("A")) {   // countdown: real timing
                try {
                    asL.frameStarted(Integer.parseInt(tag.toString().substring(1)), asL.clock.toMaster(timestamp));
                } catch (NumberFormatException ignored) {
                }
            }
            if (astroPending == null) return;
            if ("W0".equals(tag) || "W1".equals(tag)) astroLeadStarted(tag.toString(), timestamp);
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession cs, CaptureRequest request,
                                    android.hardware.camera2.CaptureFailure failure) {
            Object tag = request.getTag();
            AstroCapture as = astro;
            if (as != null && tag != null && tag.toString().startsWith("A")) {
                try {
                    as.frameFailed(Integer.parseInt(tag.toString().substring(1)));
                } catch (NumberFormatException ignored) {
                    // not an astro tag
                }
            }
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession cs, CaptureRequest request, TotalCaptureResult result) {
            if (cs != session) return;
            Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (ts == null) return;
            Object tag = request.getTag();
            String t = tag == null ? "?" : tag.toString();
            AstroCapture asR = astro;
            if (asR != null && t.startsWith("W")) {
                Long ex = result.get(CaptureResult.SENSOR_EXPOSURE_TIME), fd = result.get(CaptureResult.SENSOR_FRAME_DURATION);
                asR.leadResult(t, ex == null ? -1 : ex, fd == null ? -1 : fd,
                        request.get(CaptureRequest.SENSOR_EXPOSURE_TIME), request.get(CaptureRequest.SENSOR_FRAME_DURATION));
            }
            listener.onFrame(ts, t);
            onLookResult(request, result);
            PhotoCapture ph = photo;
            if (photoWindow && ph != null) ph.onResult(ts, result);
            Settings act = active;
            if (act != null && act.burst()) burstResults.put(ts, result);
            if (photoWindow && ph != null) {
                // window: from now until two frames after T (or 3 s as a safety limit)
                long tm = ph.clock.toMaster(ts);
                Settings a = active;
                long p = a != null ? a.periodNs : 33_333_333L;
                if ((tm != Long.MIN_VALUE && tm > ph.targetMaster + 2 * p)
                        || android.os.SystemClock.elapsedRealtimeNanos() - ph.startedNs > 3_000_000_000L) {
                    endPhotoWindow();
                }
            }
            VideoRecorder vr = recorder;
            if (vr != null && t.endsWith(VIDEO_SUFFIX)) {
                vr.onFrame(ts);
                if (recording && vr.doneWriting) stopRecordingOnThread();   // past the stop time
            }
            Analyzer a = analyzer;
            if (a != null) {
                Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Long skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
                Long dur = result.get(CaptureResult.SENSOR_FRAME_DURATION);
                a.onResult(ts, exp == null ? -1 : exp, skew == null ? -1 : skew, dur == null ? -1 : dur);
            }
        }
    };
}
