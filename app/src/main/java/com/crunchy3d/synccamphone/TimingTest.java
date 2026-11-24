package com.crunchy3d.synccamphone;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Streams one camera with manual exposure and a fixed frame duration T, measures the frame period
 * and timestamp jitter, then injects single requests with frame duration T +/- step and checks
 * whether all following frame boundaries shift by exactly that step (= phase steering works).
 */
final class TimingTest {

    interface Listener {
        void onProgress(String message);
        void onDone(String cameraId, String report, String csv);
        void onError(String message);
    }

    static final class Params {
        String cameraId = "0";
        float zoom = 1f;                 // lens test: CONTROL_ZOOM_RATIO (< 1 = ultra-wide on a logical camera)
        double fps = 30;
        double exposureMs = 10;
        int iso = 400;
        double stepMs = 5;
        int warmupFrames = 30;
        int baselineFrames = 150;
        int steps = 6;
        int framesBetweenSteps = 40;
    }

    private static final class Rec {
        long frameNumber;
        String tag;
        long ts;
        long frameDuration = -1;
        long exposure = -1;
        long callbackDelay;
    }

    private final CameraManager cm;
    private final Params p;
    private final SurfaceTexture texture;
    private final Listener listener;

    private HandlerThread thread;
    private Handler handler;
    private CameraCharacteristics chars;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface surface;

    private Size size;
    private long periodNs, stepNs, exposureNs, minFrameDurationNs;
    private int iso;
    private boolean allowNegative, afContinuous;
    private Range<Integer> fpsRange;
    private final StringBuilder setup = new StringBuilder();

    private final List<Rec> recs = new ArrayList<>();
    private final Map<String, Long> stepDeltas = new HashMap<>();
    private final Map<String, Integer> physicalIds = new java.util.TreeMap<>();
    private int completed, failed, stepsSent, nextStepAt;
    private boolean finished;

    TimingTest(CameraManager cm, Params p, SurfaceTexture texture, Listener listener) {
        this.cm = cm;
        this.p = p;
        this.texture = texture;
        this.listener = listener;
    }

    void start() {
        thread = new HandlerThread(App.NAME);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::open);
    }

    void cancel() {
        if (handler == null) return;
        handler.post(() -> fail("Test cancelled."));
    }

    // ------------------------------------------------------------------ camera control

    @SuppressLint("MissingPermission")
    private void open() {
        try {
            chars = cm.getCameraCharacteristics(p.cameraId);
            prepareSettings();
            listener.onProgress(setup.toString() + "Opening camera...\n");
            cm.openCamera(p.cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    device = d;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice d) {
                    device = d;
                    fail("Camera disconnected (another app took it?).");
                }
                @Override public void onError(CameraDevice d, int error) {
                    device = d;
                    fail("Camera device error " + error + ".");
                }
            }, handler);
        } catch (Exception e) {
            fail("Cannot open camera: " + e);
        }
    }

    private void prepareSettings() {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException("no stream configuration map");
        periodNs = Math.round(1e9 / p.fps);
        stepNs = Math.round(Math.abs(p.stepMs) * 1e6);
        size = chooseSize(map, periodNs);
        minFrameDurationNs = map.getOutputMinFrameDuration(SurfaceTexture.class, size);

        setup.append(String.format(Locale.US, "Camera %s, stream %s (min frame duration %.3f ms)%n",
                p.cameraId, size, minFrameDurationNs / 1e6));
        if (periodNs < minFrameDurationNs) {
            setup.append("NOTE: requested fps too high for this stream, using the minimum frame duration.\n");
            periodNs = minFrameDurationNs;
        }
        Long maxFd = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        if (maxFd != null && periodNs + stepNs > maxFd) {
            stepNs = Math.max(0, maxFd - periodNs);
            setup.append("NOTE: step reduced to fit the max frame duration.\n");
        }
        allowNegative = periodNs - stepNs >= minFrameDurationNs;

        long shortestFrame = allowNegative ? periodNs - stepNs : periodNs;
        exposureNs = Math.round(p.exposureMs * 1e6);
        long expLimit = shortestFrame * 9 / 10;
        if (exposureNs > expLimit) {
            exposureNs = expLimit;
            setup.append("NOTE: exposure limited to 90% of the shortest frame.\n");
        }
        Range<Long> er = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (er != null) exposureNs = er.clamp(exposureNs);
        iso = p.iso;
        Range<Integer> ir = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (ir != null) iso = ir.clamp(iso);
        afContinuous = CameraInfo.has(chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

        // Some HALs (e.g. Pixel) clamp SENSOR_FRAME_DURATION to the AE target fps range even with
        // AE off. The record template uses e.g. [30,30], which forbids shorter frames. Use the
        // range with the highest upper limit (and lowest lower limit among those).
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null && ranges.length > 0) {
            Range<Integer> best = ranges[0];
            for (Range<Integer> r : ranges) {
                if (r.getUpper() > best.getUpper()
                        || (r.getUpper().equals(best.getUpper()) && r.getLower() < best.getLower())) best = r;
            }
            fpsRange = best;
        }

        setup.append(String.format(Locale.US,
                "Period T = %.3f ms (%.3f fps), exposure %.3f ms, ISO %d%n",
                periodNs / 1e6, 1e9 / periodNs, exposureNs / 1e6, iso));
        setup.append(String.format(Locale.US, "Step = %.3f ms, %s%n", stepNs / 1e6,
                allowNegative ? "alternating + and -" : "positive only (T - step below min duration)"));
        setup.append("AE target fps range = ").append(fpsRange == null ? "template default" : fpsRange).append('\n');
    }

    private static Size chooseSize(StreamConfigurationMap map, long periodNs) {
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        Size best = null;
        long bestArea = -1;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;
            if (map.getOutputMinFrameDuration(SurfaceTexture.class, s) > periodNs) continue;
            long area = (long) s.getWidth() * s.getHeight();
            if (area > bestArea) {
                best = s;
                bestArea = area;
            }
        }
        if (best == null) {   // nothing fast enough: take the smallest
            best = sizes[0];
            for (Size s : sizes) {
                if ((long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()) best = s;
            }
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    private void createSession() {
        try {
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            surface = new Surface(texture);
            device.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            startRepeating();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            fail("Capture session configuration failed.");
                        }
                    }, handler);
        } catch (Exception e) {
            fail("Cannot create session: " + e);
        }
    }

    private CaptureRequest buildRequest(long frameDurationNs, String tag) throws CameraAccessException {
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        b.addTarget(surface);
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs);
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        if (afContinuous) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }
        if (p.zoom != 1f && android.os.Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, p.zoom);
        b.setTag(tag);
        return b.build();
    }

    private void startRepeating() {
        try {
            session.setRepeatingRequest(buildRequest(periodNs, "R"), captureCallback, handler);
            listener.onProgress(setup.toString() + "Warming up (" + p.warmupFrames + " frames)...\n");
        } catch (Exception e) {
            fail("Cannot start streaming: " + e);
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                               TotalCaptureResult result) {
                    onFrame(request, result);
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request,
                                            CaptureFailure failure) {
                    failed++;
                }
            };

    private void onFrame(CaptureRequest request, TotalCaptureResult result) {
        if (finished) return;
        long now = SystemClock.elapsedRealtimeNanos();
        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 29) {         // lens test: which physical sensor streams
            String ph = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
            physicalIds.merge(ph == null ? "-" : ph, 1, Integer::sum);
        }
        Rec r = new Rec();
        r.frameNumber = result.getFrameNumber();
        Object tag = request.getTag();
        r.tag = tag == null ? "?" : tag.toString();
        r.ts = ts;
        Long fd = result.get(CaptureResult.SENSOR_FRAME_DURATION);
        if (fd != null) r.frameDuration = fd;
        Long ex = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (ex != null) r.exposure = ex;
        r.callbackDelay = now - ts;
        recs.add(r);
        completed++;

        int baselineEnd = p.warmupFrames + p.baselineFrames;
        if (completed == p.warmupFrames) {
            listener.onProgress(setup.toString() + "Measuring baseline (" + p.baselineFrames + " frames)...\n");
        }
        if (completed < baselineEnd || completed < nextStepAt) return;
        if (stepsSent < p.steps) {
            sendStep();
        } else {
            finish();
        }
    }

    private void sendStep() {
        boolean negative = allowNegative && (stepsSent % 2 == 1);
        long delta = negative ? -stepNs : stepNs;
        String tag = "S" + (stepsSent + 1) + (negative ? "-" : "+");
        try {
            session.capture(buildRequest(periodNs + delta, tag), captureCallback, handler);
        } catch (Exception e) {
            fail("Cannot submit step request: " + e);
            return;
        }
        stepDeltas.put(tag, delta);
        stepsSent++;
        nextStepAt = completed + p.framesBetweenSteps;
        listener.onProgress(setup.toString() + String.format(Locale.US,
                "Step %d/%d: one frame of %.3f ms...%n", stepsSent, p.steps, (periodNs + delta) / 1e6));
    }

    private void finish() {
        finished = true;
        try {
            session.stopRepeating();
        } catch (Exception ignored) {
        }
        closeCamera();
        String report, csv;
        try {
            report = analyze();
            csv = buildCsv();
        } catch (Exception e) {
            report = setup + "\nAnalysis failed: " + e;
            csv = buildCsv();
        }
        listener.onDone(p.cameraId, report, csv);
        thread.quitSafely();
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        closeCamera();
        listener.onError(message);
        if (thread != null) thread.quitSafely();
    }

    private void closeCamera() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (device != null) device.close();
        } catch (Exception ignored) {
        }
        if (surface != null) surface.release();
        session = null;
        device = null;
        surface = null;
    }

    // ------------------------------------------------------------------ analysis

    private static double median(double[] v) {
        if (v.length == 0) return Double.NaN;
        double[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2]);
    }

    private static double[] slice(double[] v, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(v.length, to);
        return to > from ? Arrays.copyOfRange(v, from, to) : new double[0];
    }

    private List<Rec> sortedRecs() {
        List<Rec> all = new ArrayList<>(recs);
        Collections.sort(all, (a, b) -> Long.compare(a.ts, b.ts));
        return all;
    }

    private String analyze() {
        StringBuilder sb = new StringBuilder("=== " + App.TITLE + ": timing test ===\n");
        sb.append(CameraInfo.deviceHeader()).append('\n').append(setup).append('\n');
        sb.append(String.format(Locale.US, "Zoom ratio %.2f, active physical camera (frames): %s%n%n", p.zoom,
                physicalIds));

        List<Rec> all = sortedRecs();
        if (all.size() <= p.warmupFrames + 20) {
            return sb.append("Too few frames received (").append(all.size()).append(").\n").toString();
        }
        List<Rec> f = all.subList(p.warmupFrames, all.size());
        int n = f.size();
        long[] t = new long[n];
        for (int i = 0; i < n; i++) t[i] = f.get(i).ts;

        // --- timestamp base
        Integer tsSource = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        double[] delays = new double[n];
        for (int i = 0; i < n; i++) delays[i] = f.get(i).callbackDelay / 1e6;
        double dMin = Arrays.stream(delays).min().orElse(0), dMax = Arrays.stream(delays).max().orElse(0);
        sb.append("[1] Timestamp base\n");
        sb.append("  Declared source: ").append(tsSource != null && tsSource == 1 ? "REALTIME" : "UNKNOWN").append('\n');
        sb.append(String.format(Locale.US,
                "  Result callback arrives %.2f / %.2f / %.2f ms (min/median/max) after SENSOR_TIMESTAMP%n",
                dMin, median(delays), dMax));
        boolean realtimeOk = dMin >= 0 && dMax < 1000;
        sb.append(realtimeOk
                ? "  -> consistent with elapsedRealtimeNanos(): timestamps can be mapped to a synced clock.\n\n"
                : "  -> NOT consistent with elapsedRealtimeNanos(): timestamps use another time base!\n\n");

        // --- baseline period
        int nb = Math.min(p.baselineFrames, n);
        List<Double> good = new ArrayList<>();
        int drops = 0;
        for (int i = 0; i + 1 < nb; i++) {
            long d = t[i + 1] - t[i];
            if (d > periodNs * 3 / 2) drops++;
            else good.add((double) d);
        }
        double[] iv = new double[good.size()];
        for (int i = 0; i < iv.length; i++) iv[i] = good.get(i);
        double mean = Arrays.stream(iv).average().orElse(periodNs);
        double var = 0;
        for (double d : iv) var += (d - mean) * (d - mean);
        double std = iv.length > 1 ? Math.sqrt(var / (iv.length - 1)) : 0;
        double ivMin = Arrays.stream(iv).min().orElse(0), ivMax = Arrays.stream(iv).max().orElse(0);
        double ivMed = median(iv);
        double[] absDev = new double[iv.length];
        for (int i = 0; i < iv.length; i++) absDev[i] = Math.abs(iv[i] - ivMed);
        double robustStd = 1.4826 * median(absDev);
        int outliers = 0;
        for (double d : iv) if (Math.abs(d - ivMed) > Math.max(50e3, 5 * robustStd)) outliers++;
        double fdSum = 0, exSum = 0;
        int fdN = 0, exN = 0;
        for (int i = 0; i < nb; i++) {
            if (f.get(i).frameDuration > 0) { fdSum += f.get(i).frameDuration; fdN++; }
            if (f.get(i).exposure > 0) { exSum += f.get(i).exposure; exN++; }
        }
        sb.append("[2] Baseline frame period (").append(nb).append(" frames)\n");
        sb.append(String.format(Locale.US, "  Requested T     : %.4f ms%n", periodNs / 1e6));
        sb.append(String.format(Locale.US, "  Measured mean   : %.4f ms  (%+.1f us, %+.0f ppm vs requested)%n",
                mean / 1e6, (mean - periodNs) / 1e3, (mean - periodNs) / periodNs * 1e6));
        sb.append(String.format(Locale.US, "  Interval jitter : std %.2f us, robust std %.2f us, min %.4f ms, max %.4f ms%n",
                std / 1e3, robustStd / 1e3, ivMin / 1e6, ivMax / 1e6));
        sb.append(String.format(Locale.US,
                "  Outlier intervals: %d of %d (usually +x/-x pairs = noise on one timestamp, not the frame clock)%n",
                outliers, iv.length));
        sb.append(String.format(Locale.US, "  Reported in results: frame duration %.4f ms, exposure %.4f ms%n",
                fdN > 0 ? fdSum / fdN / 1e6 : Double.NaN, exN > 0 ? exSum / exN / 1e6 : Double.NaN));
        sb.append("  Dropped frames  : ").append(drops).append(", failed captures: ").append(failed).append("\n\n");

        // --- steps
        double P = mean;
        double[] res = new double[n];
        for (int i = 0; i < n; i++) res[i] = (t[i] - t[0]) - i * P;  // phase residual vs ideal clock
        double tol = Math.max(50e3, 5 * std);  // threshold for "this interval deviates"

        sb.append("[3] One-frame duration steps (shift of all later frame boundaries)\n");
        sb.append("  step   requested   measured     error   first moved frame   intervals-T around step (ms)\n");
        int ok = 0, ignored = 0, total = 0;
        TreeSet<Integer> latencies = new TreeSet<>();
        for (int j = 0; j < n; j++) {
            Rec r = f.get(j);
            if (!r.tag.startsWith("S")) continue;
            Long deltaObj = stepDeltas.get(r.tag);
            if (deltaObj == null) continue;
            long delta = deltaObj;
            total++;
            double pre = median(slice(res, j - 15, j));
            double post = median(slice(res, j + 10, j + 26));
            double shift = post - pre;
            double err = shift - delta;

            int moved = Integer.MIN_VALUE;
            for (int i = Math.max(0, j - 3); i < Math.min(n - 1, j + 10); i++) {
                if (Math.abs((t[i + 1] - t[i]) - P) > stepNs / 2.0) {
                    moved = i + 1 - j;
                    break;
                }
            }
            StringBuilder around = new StringBuilder();
            for (int i = Math.max(0, j - 1); i < Math.min(n - 1, j + 5); i++) {
                around.append(String.format(Locale.US, "%+.2f ", ((t[i + 1] - t[i]) - P) / 1e6));
            }
            boolean good1 = Math.abs(err) <= Math.max(100e3, 0.05 * Math.abs(delta));
            if (good1) ok++;
            if (Math.abs(shift) < 0.2 * Math.abs(delta)) ignored++;
            if (moved != Integer.MIN_VALUE) latencies.add(moved);
            sb.append(String.format(Locale.US, "  %-5s %+8.3f ms %+8.3f ms %+7.3f ms   %-18s  %s%s%n",
                    r.tag, delta / 1e6, shift / 1e6, err / 1e6,
                    moved == Integer.MIN_VALUE ? "none" : String.format(Locale.US, "%+d", moved),
                    around, good1 ? "" : " <-- off"));
        }
        if (total == 0) sb.append("  (no step frames found in results)\n");
        sb.append("  ('first moved frame' +1 = the frame right after the step request starts later,\n");
        sb.append("   i.e. the duration was applied exactly to that request's own frame.)\n");
        sb.append(String.format(Locale.US, "  Interval tolerance used: %.0f us%n%n", tol / 1e3));

        // --- verdict
        sb.append("[4] Verdict\n");
        if (!realtimeOk) {
            sb.append("  Timestamps are not in elapsedRealtime base - cross-phone phase measurement is hard.\n");
        }
        if (total == 0) {
            sb.append("  Inconclusive.\n");
        } else if (ok == total) {
            sb.append("  PASS: all ").append(total).append(" one-frame duration changes shifted the phase exactly.\n");
            sb.append("  Phase steering via SENSOR_FRAME_DURATION should work on this camera.\n");
            sb.append("  Step applied at frame offset(s): ").append(latencies).append('\n');
        } else if (ignored == total) {
            sb.append("  FAIL: the HAL ignored the single-frame duration changes.\n");
            sb.append("  Phase steering would need the slow 'slightly different fps' (beat) method instead.\n");
        } else {
            sb.append("  PARTIAL: ").append(ok).append(" of ").append(total)
                    .append(" steps were exact. Check the table and the CSV.\n");
        }
        sb.append(String.format(Locale.US,
                "  Typical timestamp jitter ~%.2f us (robust); occasional single-frame outliers -> use%n"
                        + "  median/linear fits over many frames, not single timestamps, to measure phase.%n",
                robustStd / Math.sqrt(2) / 1e3));
        return sb.toString();
    }

    private String buildCsv() {
        StringBuilder sb = new StringBuilder(
                "frame_number,tag,timestamp_ns,interval_us,result_frame_duration_ns,result_exposure_ns,callback_delay_us\n");
        List<Rec> all = sortedRecs();
        long prev = -1;
        for (Rec r : all) {
            sb.append(r.frameNumber).append(',').append(r.tag).append(',').append(r.ts).append(',')
                    .append(prev < 0 ? "" : String.format(Locale.US, "%.3f", (r.ts - prev) / 1e3)).append(',')
                    .append(r.frameDuration).append(',').append(r.exposure).append(',')
                    .append(String.format(Locale.US, "%.1f", r.callbackDelay / 1e3)).append('\n');
            prev = r.ts;
        }
        return sb.toString();
    }
}
