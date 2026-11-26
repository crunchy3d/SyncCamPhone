package com.crunchy3d.synccamphone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.view.Surface;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Synchronized H.264 + AAC recorder fed by the camera through a persistent input surface.
 *
 * The surface is part of the camera session all the time; frames reach it only while the camera's
 * requests target it, so starting a recording does not restart the camera. All phones get the
 * same start and stop time in master time. Recording starts with a pre-roll (every frame forced
 * to be a keyframe); the MP4 gets only the frames from the one nearest the start time up to the
 * stop time, so the files of all phones begin with the same, simultaneous keyframe and have the
 * same number of frames. Sound (microphone, 48 kHz mono AAC) goes into the same MP4 with
 * timestamps from AudioRecord.getTimestamp, converted to the video's time base.
 *
 * vid_*_frames.csv next to the MP4:
 *   F,tsLocal,tsMaster       every frame written (SENSOR_TIMESTAMP, master time)
 *   E,ptsUs,flags,size       the same frame's encoded sample
 */
final class VideoRecorder {
    static final int AUDIO_RATE = 48_000;

    private final Surface surface = MediaCodec.createPersistentInputSurface();
    final int width, height, fps, orientationHint;
    volatile int bitrate;
    volatile String mime = MediaFormat.MIMETYPE_VIDEO_AVC;     // or MIMETYPE_VIDEO_HEVC
    private final long halfPeriodNs;
    private MediaCodec codec;

    private final Object lock = new Object();     // muxer + csv
    private volatile boolean active;
    private MediaMuxer muxer;
    private ParcelFileDescriptor pfd;
    private BufferedWriter csv;
    private int videoTrack = -1, audioTrack = -1;
    private boolean muxerStarted;
    private volatile boolean audioEnabled;
    private Thread drainThread, audioThread;
    private volatile boolean abortDrain, audioRun;
    private LedCapture.Clock clock;

    // synchronization of start/stop (master time)
    private volatile long startMaster, stopMaster = Long.MAX_VALUE;
    private volatile long soundOffsetNs;                            // input latency correction

    // conform: frame n is written at T0 + n / conformFps (0 = real timing); frameReal[n] = its real
    // presentation time (ns, encoder time base) - the sound is mapped through these frame times
    private long periodNs;
    private volatile int conformFps;
    private final long[] frameReal = new long[1 << 15];
    private volatile long framesN;                                  // frames in frameReal
    private long lastN = -1, lastTsLocal;
    private final ArrayDeque<long[]> pending = new ArrayDeque<>();   // frames sent: {tsLocal, tsMaster}
    private volatile long encOffsetNs = Long.MIN_VALUE;             // encoder pts - SENSOR_TIMESTAMP
    private volatile boolean started;                              // first frame written
    private volatile long firstPtsUs = -1, lastPtsUs = -1;
    volatile boolean doneWriting;                                   // past the stop time

    volatile int frames, audioFrames, skipped, skippedNoKey, skippedNoMuxer;
    // item 41 (2026-11-25): camera frames that never reached the encoder (Beam Pro 4K: ~1 in 300) - with
    // "Conform" the frame before the gap lasts 2/30 s (MediaInfo: variable frame rate) and tools that pair
    // the eyes by frame NUMBER shift by one; noted in the frame list and the result
    volatile int missing;
    // diagnostics (#41, 2026-11-26): who lost a missing frame - the camera (no frame delivered: G rows) or the
    // encoder (frame delivered, no encoded sample: D rows, e.g. CBR rate control skipping)
    volatile int camGaps, encDropped;
    volatile int filled, fillExtra;                // gaps filled with the encoder's repeat (R rows), extra frames (X)
    private boolean lastWasFill;
    private final StringBuilder filledAt = new StringBuilder();
    // no camera frame for 50 ms (1.5 periods): the encoder repeats the last one (user 2026-11-26: the frame COUNT
    // must be right; 62 ms left some real gaps unfilled, 50 ms filled all - sometimes a late real frame becomes a copy)
    static final long REPEAT_AFTER_US = 50_000;
    private long lastCamTs;
    private final StringBuilder diag = new StringBuilder();       // rows to write (guarded by pending)
    static volatile String vendorParams = "";                     // encoder vendor parameters (Android 12+)
    volatile String doneKey = "-";          // "#29: ..." of the last FINISHED recording with lost frames
    volatile int number;                     // this recording's number (3D_vid_NNNN)
    private final StringBuilder missingAt = new StringBuilder();

    /** "1 frame missing at 0:07.2" or "". */
    String missingText() {
        int m = missing, fl = filled;
        String t = m == 0 ? "" : m + (m == 1 ? " frame" : " frames") + " missing at " + missingAt
                + " (frame numbers of the two eyes differ after it)";
        if (fl > 0) t += (t.isEmpty() ? "" : "; ") + fl + (fl == 1 ? " frame" : " frames") + " filled at " + filledAt
                + " (copy of the previous frame)";
        return t;
    }
    private long syncWorstNs, syncSumNs;
    private int syncN, syncUnknown;

    /** Sync quality of this camera during the recording (ns; < 0 = unknown), noted ~4x per second. */
    void noteSync(long qualityNs) {
        if (!active || !started) return;
        syncN++;
        if (qualityNs < 0) {
            syncUnknown++;
            return;
        }
        syncSumNs += qualityNs;
        syncWorstNs = Math.max(syncWorstNs, qualityNs);
    }
    volatile long startNs;
    volatile String path = "", result = "";

    VideoRecorder(int width, int height, int fps, int orientationHint) throws IOException {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.orientationHint = orientationHint;
        this.bitrate = autoBitrate();
        this.halfPeriodNs = 500_000_000L / Math.max(1, fps);
        prepareCodec();
    }
    /** Automatic bitrate: about 0.32 bit per pixel (4K30: 80 Mbit/s, 1080p30: 20 Mbit/s). */
    int autoBitrate() {
        return (int) Math.min(maxBitrate(mime), (long) width * height * fps * 32 / 100);
    }

    /** The hardware encoder for mime supports constant bitrate. */
    static boolean cbrSupported(String mime) {
        try {
            for (MediaCodecInfo ci : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                    .getCodecInfos()) {
                if (!ci.isEncoder() || !ci.isHardwareAccelerated()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(mime)) {
                        MediaCodecInfo.EncoderCapabilities ec = ci.getCapabilitiesForType(t).getEncoderCapabilities();
                        return ec != null && ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Highest bitrate accepted by the hardware encoder for mime, but at most 200 Mbit/s. */
    static int maxBitrate(String mime) {
        int max = 200_000_000;
        try {
            for (android.media.MediaCodecInfo ci : new android.media.MediaCodecList(
                    android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(mime) && ci.isHardwareAccelerated()) {
                        return Math.min(max, ci.getCapabilitiesForType(t).getVideoCapabilities()
                                .getBitrateRange().getUpper());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return max;
    }

    /**
     * Sets the bitrate for the next recording (0 = automatic); re-configures the encoder if
     * needed. Call only while not recording. Returns the bitrate in use.
     */
    // VBR / CBR (user 2026-11-21): the Xreal Beam Pro's Qualcomm encoder used only ~50 of 100 Mbit/s in
    // the default VBR (the bitrate is a maximum there); CBR holds it. Set by the master for all phones.
    static volatile boolean cbrWanted;
    // "Fill lost frames" (user setting, default OFF, 2026-11-26): on = the encoder repeats the previous frame when no
    // camera frame came within REPEAT_AFTER_US (count always right; a late real frame is then discarded and replaced
    // by the copy); off = every camera frame as it is (a frame lost by the camera leaves a gap, reported)
    static volatile boolean fillWanted;
    private boolean fill;                     // the mode the codec was configured with
    private boolean cbr;                      // the mode the codec was configured with
    private String modeUsed = "VBR";

    /** "VBR" / "CBR" / "VBR (no CBR on this encoder)" - for the info file. */
    String modeText() {
        return "bitrate mode " + modeUsed + ", fill lost frames " + (fill ? "on" : "off");
    }

    synchronized int configure(int bps, boolean hevc) {
        String m = hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        if (active) return bitrate;
        boolean newCodec = !m.equals(mime) || cbr != cbrWanted || fill != fillWanted;
        cbr = cbrWanted;
        fill = fillWanted;
        mime = m;
        int want = bps <= 0 ? autoBitrate() : Math.min(maxBitrate(m), Math.max(1_000_000, bps));
        if (want == bitrate && !newCodec) return bitrate;
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        codec.release();
        bitrate = want;
        try {
            prepareCodec();
        } catch (Exception e) {
            result = "encoder restart failed: " + e.getMessage();
        }
        return bitrate;
    }

    Surface surface() {
        return surface;
    }

    boolean isRecording() {
        return active;
    }

    boolean isStarted() {
        return started;
    }

    private void prepareCodec() throws IOException {
        MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
        // no B-frames: the encoder delivers the frames in capture order (frame numbering, cutting)
        if (android.os.Build.VERSION.SDK_INT >= 29) f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        modeUsed = "VBR";
        if (cbr) {
            if (cbrSupported(mime)) {
                f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                modeUsed = "CBR";
            } else {
                modeUsed = "VBR (no CBR on this encoder)";
            }
        }
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (fill) f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_AFTER_US);   // #41 gap fill (user setting)
        codec = MediaCodec.createEncoderByType(mime);
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        if (android.os.Build.VERSION.SDK_INT >= 31) {               // probe (#41): what the encoder offers
            try {
                java.util.List<String> vp = codec.getSupportedVendorParameters();
                vendorParams = codec.getName() + " (" + vp.size() + "): " + String.join(" ", vp);
            } catch (Exception e) {
                vendorParams = codec.getName() + ": vendor parameters not readable (" + e + ")";
            }
        } else {
            vendorParams = codec.getName();
        }
        codec.setInputSurface(surface);
        codec.start();
    }

    /** Number of the next recording (the master's record number): 3D_vid_NNNN-l.mp4 etc. */
    static volatile int nextNumber = 1;

    /** Opens the output and starts the pre-roll. startMaster = master time of the first frame. */
    synchronized void begin(Context ctx, String name, String info, LedCapture.Clock clock, int rotation,
                            long startMaster, long soundOffsetNs, long periodNs, int conformFps) throws IOException {
        if (active) return;
        this.soundOffsetNs = soundOffsetNs;
        this.periodNs = periodNs;
        this.conformFps = conformFps;
        framesN = 0;
        lastN = -1;
        int no = nextNumber;
        number = no;
        String mp4 = Downloads.mainName("vid", no, "mp4");
        Downloads.Fd fd = Downloads.openFd(ctx, mp4, "video/mp4");
        pfd = fd.pfd;
        path = fd.path;
        muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.setOrientationHint(rotation);
        Downloads.Out o = Downloads.open(ctx, Downloads.infoName("vid", no, "frames", "csv"), "text/csv");
        synchronized (lock) {
            csv = new BufferedWriter(new OutputStreamWriter(o.stream, StandardCharsets.US_ASCII), 1 << 16);
            csv.write("# " + App.TITLE + " video frames, " + name + ", " + info + "\n");
            csv.write(String.format(java.util.Locale.US,
                    "# video %s, " + (mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) ? "HEVC" : "H.264")
                            + ", %dx%d, %d fps, %d bit/s, rotation %d, start %d, sound AAC %d Hz, "
                            + "sound offset %d ns, recorded at %.3f fps, %s\n",
                    mp4, width, height, fps, bitrate, rotation, startMaster, AUDIO_RATE, soundOffsetNs,
                    1e9 / periodNs, conformFps > 0 ? "conformed to " + conformFps + " fps (frame n at n/"
                            + conformFps + " s, sound resampled, pitch +"
                            + String.format(java.util.Locale.US, "%.2f %%", (conformFps * periodNs / 1e9 - 1) * 100)
                            + ")" : "real timing"));
            if (!vendorParams.isEmpty()) csv.write("# encoder vendor parameters: " + vendorParams + "\n");
            csv.write("# F,tsLocal,tsMaster (frames written) / E,ptsUs,flags,size,realPtsUs (their samples)\n");
            videoTrack = -1;
            audioTrack = -1;
            muxerStarted = false;
        }
        synchronized (pending) {
            pending.clear();
        }
        this.clock = clock;
        this.startMaster = startMaster;
        stopMaster = Long.MAX_VALUE;
        encOffsetNs = Long.MIN_VALUE;
        started = false;
        doneWriting = false;
        firstPtsUs = -1;
        lastPtsUs = -1;
        frames = 0;
        audioFrames = 0;
        skipped = 0;
        skippedNoKey = 0;
        skippedNoMuxer = 0;
        missing = 0;
        camGaps = 0;
        filled = 0;
        fillExtra = 0;
        lastWasFill = false;
        filledAt.setLength(0);
        encDropped = 0;
        lastCamTs = 0;
        synchronized (pending) {
            diag.setLength(0);
        }
        missingAt.setLength(0);
        syncWorstNs = 0;
        syncSumNs = 0;
        syncN = 0;
        syncUnknown = 0;
        result = "";
        abortDrain = false;
        startNs = android.os.SystemClock.elapsedRealtimeNanos();
        active = true;
        drainThread = new Thread(this::drainVideo, "video-drain");
        drainThread.start();
        audioEnabled = true;
        audioRun = true;
        audioThread = new Thread(this::audioLoop, "video-audio");
        audioThread.start();
        new Thread(this::keyframeLoop, "video-keyframes").start();
    }

    /**
     * Pre-roll: request a keyframe every 4 ms until the first frame is written, so that every
     * frame entering the encoder is a keyframe (one request per camera frame missed ~5 %).
     */
    private void keyframeLoop() {
        Bundle b = new Bundle();
        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        while (active && !started && !doneWriting) {
            try {
                codec.setParameters(b);
            } catch (Exception e) {
                return;
            }
            android.os.SystemClock.sleep(4);
        }
    }

    /** Master time after which no frame is written (0 = now). */
    void setStop(long stop) {
        stopMaster = stop;
    }

    /** A camera frame was sent to the encoder (camera thread). */
    void onFrame(long tsLocal) {
        if (!active) return;
        long tm = clock.toMaster(tsLocal);
        synchronized (pending) {
            if (lastCamTs > 0 && periodNs > 0 && tsLocal - lastCamTs > periodNs * 3 / 2) {
                camGaps++;
                diag.append("G,").append(lastCamTs).append(',').append(tsLocal).append('\n');
            }
            lastCamTs = tsLocal;
            pending.addLast(new long[]{tsLocal, tm});
            while (pending.size() > 120) pending.pollFirst();
            pending.notifyAll();
        }
    }

    /** Finishes the file and prepares a fresh codec for the next recording. */
    synchronized void end() {
        if (!active) return;
        audioRun = false;
        try {
            codec.signalEndOfInputStream();
        } catch (Exception ignored) {
        }
        join(drainThread, 3000);
        if (drainThread.isAlive()) {
            abortDrain = true;
            join(drainThread, 1000);
        }
        join(audioThread, 2000);
        synchronized (lock) {
            try {
                if (muxerStarted) muxer.stop();
            } catch (Exception ignored) {
            }
            try {
                muxer.release();
            } catch (Exception ignored) {
            }
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
            try {
                csv.write(String.format(java.util.Locale.US,
                        "# end, %d frames, %d sound frames, %d skipped (%d no keyframe, %d file not ready, "
                                + "%d no timestamp)\n", frames, audioFrames, skipped, skippedNoKey, skippedNoMuxer,
                        skipped - skippedNoKey - skippedNoMuxer));
                csv.write(String.format(java.util.Locale.US, "# lost frames: camera gaps %d (G rows), encoder dropped %d "
                        + "(D rows), filled with the previous frame %d (R rows), extra frames after a fill %d (X rows)%n",
                        camGaps, encDropped, filled, fillExtra));
                if (missing > 0 || filled > 0) {
                    csv.write("# missing camera frames: " + missingText() + " (missing: the frame before the gap "
                            + "lasts longer, variable frame rate; filled: the encoder repeated the previous frame, "
                            + "constant frame rate, frame numbers stay equal)\n");
                }
                if (syncN > 0) {
                    csv.write(String.format(java.util.Locale.US,
                            "# sync quality (|phase| + clock uncertainty): worst %.0f us, mean %.0f us, "
                                    + "unknown %d of %d checks\n",
                            syncWorstNs / 1e3, syncSumNs / 1e3 / Math.max(1, syncN - syncUnknown), syncUnknown, syncN));
                }
                csv.close();
            } catch (Exception ignored) {
            }
            csv = null;
        }
        active = false;
        doneKey = missing > 0 || filled > 0 ? "#" + number + ": " + missingText() : "-";   // snapshot (note)
        result = frames + " frames" + (audioFrames > 0 ? " + sound" : " (no sound)")
                + (skipped > 0 ? ", " + skipped + " skipped!" : "")
                + (missing > 0 || filled > 0 ? ", " + missingText() + "!" : "") + " -> " + path;
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        codec.release();
        try {
            prepareCodec();
        } catch (Exception e) {
            result += " (encoder restart failed: " + e.getMessage() + ")";
        }
    }

    void release() {
        end();
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        try {
            codec.release();
        } catch (Exception ignored) {
        }
        surface.release();
    }

    private static void join(Thread t, long ms) {
        if (t == null) return;
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** Starts the muxer once all tracks are known (call with lock held). */
    private void maybeStartMuxer() {
        if (muxerStarted || videoTrack < 0 || (audioEnabled && audioTrack < 0)) return;
        muxer.start();
        muxerStarted = true;
    }

    // ------------------------------------------------------------------ video

    /**
     * {tsLocal, tsMaster} of the frame with this encoder time, or null. The encoder can deliver a
     * frame before the camera's capture result (with the timestamp) arrives, so wait for it.
     */
    private long[] frameFor(long ptsNs) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 200;
        synchronized (pending) {
            while (true) {
                if (encOffsetNs == Long.MIN_VALUE) {
                    long[] first = pending.peekFirst();
                    // the first encoded frame is the first frame sent
                    if (first != null) encOffsetNs = ptsNs - first[0];
                }
                if (encOffsetNs != Long.MIN_VALUE) {
                    long local = ptsNs - encOffsetNs;
                    while (!pending.isEmpty() && pending.peekFirst()[0] < local - 1_000_000) {
                        long[] d = pending.pollFirst();   // older frame without a sample (dropped)
                        if (started) {
                            encDropped++;
                            diag.append("D,").append(d[0]).append('\n');
                        }
                    }
                    long[] f = pending.peekFirst();
                    if (f != null) return Math.abs(f[0] - local) <= 1_000_000 ? pending.pollFirst() : null;
                }
                long wait = deadline - android.os.SystemClock.elapsedRealtime();
                if (wait <= 0) return null;
                try {
                    pending.wait(wait);
                } catch (InterruptedException e) {
                    return null;
                }
            }
        }
    }

    private void drainVideo() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!abortDrain) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, 10_000);
            } catch (Exception e) {
                return;
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (lock) {
                    videoTrack = muxer.addTrack(codec.getOutputFormat());
                    maybeStartMuxer();
                }
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer b = codec.getOutputBuffer(idx);
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && b != null) {
                handleVideoSample(b, info);
            }
            codec.releaseOutputBuffer(idx, false);
            if (eos) return;
        }
    }

    private void handleVideoSample(ByteBuffer b, MediaCodec.BufferInfo info) {
        if (doneWriting) return;
        long[] f = frameFor(info.presentationTimeUs * 1000);
        boolean fill = false;
        if (f == null && started && lastN >= 0 && encOffsetNs != Long.MIN_VALUE) {
            // #41 fill: a sample without a camera frame, between 0.5 and ~1.95 periods after the last one, is
            // the encoder's repeat of the previous frame (KEY_REPEAT_PREVIOUS_FRAME_AFTER: the camera skipped
            // a frame) - it takes the missing frame's slot, so the file stays at exactly one frame per period
            long d = info.presentationTimeUs * 1000 - encOffsetNs - lastTsLocal;
            if (d > periodNs / 2 && d < periodNs * 2 - periodNs / 20) {
                long synth = lastTsLocal + periodNs;
                f = new long[]{synth, clock.toMaster(synth)};
                fill = true;
            }
        }
        if (f == null || f[1] == Long.MIN_VALUE) {
            if (started) skipped++;                                // frame without timestamp
            return;
        }
        long tm = f[1];
        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (!started) {
            synchronized (lock) {                                  // diagnostics: pre-roll frames
                try {
                    csv.write("P," + f[0] + "," + tm + "," + info.flags + "\n");
                } catch (IOException ignored) {
                }
            }
            if (tm < startMaster - halfPeriodNs) return;            // pre-roll
            if (!key) {
                skipped++;                                         // should not happen (pre-roll keyframes)
                skippedNoKey++;
                return;
            }
        }
        if (tm - halfPeriodNs > stopMaster) {                     // (no overflow for MAX_VALUE)
            doneWriting = true;
            return;
        }
        synchronized (lock) {
            if (!muxerStarted) {
                skipped++;                                         // file not ready yet
                skippedNoMuxer++;
                return;
            }
            // frame number from the real intervals (robust against a missing frame)
            long realUs = info.presentationTimeUs;
            long slots = lastN < 0 ? 1 : Math.round((f[0] - lastTsLocal) / (double) periodNs);
            if (slots < 1 && lastWasFill) {                       // a late real frame after a fill (extra frame)
                fillExtra++;
                try {
                    csv.write("X," + f[0] + "\n");
                } catch (IOException ignored) {
                }
            }
            long n = lastN < 0 ? 0 : lastN + Math.max(1, slots);
            if (fill) {
                filled++;
                double tf = n / (conformFps > 0 ? (double) conformFps : 1e9 / periodNs);
                if (filled <= 20) {
                    if (filledAt.length() > 0) filledAt.append(", ");
                    filledAt.append(String.format(java.util.Locale.US, "%d:%04.1f", (int) (tf / 60), tf % 60));
                }
                try {
                    csv.write("R," + f[0] + "\n");
                } catch (IOException ignored) {
                }
            }
            lastWasFill = fill;
            if (lastN >= 0 && n - lastN > 1) {                     // item 41: frames missing here
                double t = (lastN + 1) / (conformFps > 0 ? (double) conformFps : 1e9 / periodNs);
                if (missing < 20) {
                    if (missingAt.length() > 0) missingAt.append(", ");
                    missingAt.append(String.format(java.util.Locale.US, "%d:%04.1f", (int) (t / 60), t % 60));
                }
                missing += (int) (n - lastN - 1);
            }
            for (long k = lastN + 1; k < n; k++) {                // fill a gap (missing frame)
                frameReal[(int) (k % frameReal.length)] = frameReal[(int) (lastN % frameReal.length)]
                        + (realUs * 1000 - frameReal[(int) (lastN % frameReal.length)]) * (k - lastN) / (n - lastN);
            }
            frameReal[(int) (n % frameReal.length)] = realUs * 1000;
            lastN = n;
            lastTsLocal = f[0];
            if (!started) firstPtsUs = realUs;
            if (conformFps > 0) info.presentationTimeUs = firstPtsUs + Math.round(n * 1e6 / conformFps);
            muxer.writeSampleData(videoTrack, b, info);
            framesN = n + 1;
            started = true;
            lastPtsUs = info.presentationTimeUs;
            frames++;
            try {
                String dg;
                synchronized (pending) {
                    dg = diag.toString();
                    diag.setLength(0);
                }
                if (!dg.isEmpty()) csv.write(dg);
                csv.write("F," + f[0] + "," + tm + "\nE," + info.presentationTimeUs + "," + info.flags + ","
                        + info.size + "," + realUs + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ sound

    @SuppressLint("MissingPermission")
    private void audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = null;
        MediaCodec aac = null;
        try {
            int min = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, AUDIO_RATE / 5 * 2) * 2);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("no microphone");
            MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aac.start();
            rec.startRecording();
        } catch (Exception e) {
            if (rec != null) rec.release();
            if (aac != null) aac.release();
            synchronized (lock) {
                audioEnabled = false;
                maybeStartMuxer();
            }
            return;
        }

        // The sound is resampled to exactly 48 kHz on the written time scale: output sample j has the
        // written time T0 + j / 48000; its real time follows from the real times of the video frames
        // around it (conform) or equals it (real timing); the microphone sample position of that
        // real time comes from AudioRecord.getTimestamp; cubic interpolation between the samples.
        // This also removes the microphone clock drift and keeps lip sync exact through phase steps.
        final int ring = 1 << 17;                                   // 2.7 s of input
        short[] in = new short[ring];
        long inCount = 0;
        byte[] pcm = new byte[1024 * 2];
        byte[] outBytes = new byte[1024 * 2];
        int outFill = 0;
        long outCount = 0, outChunkStart = 0;
        AudioTimestamp ts = new AudioTimestamp();
        boolean haveTs = false;
        long lastTsMs = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (audioRun) {
                int n = rec.read(pcm, 0, pcm.length);
                if (n <= 0) continue;
                for (int i = 0; i + 1 < n; i += 2) {
                    in[(int) ((inCount + i / 2) % ring)] = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                }
                inCount += n / 2;
                long nowMs = android.os.SystemClock.elapsedRealtime();
                if (nowMs - lastTsMs >= 500
                        && rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    haveTs = true;
                    lastTsMs = nowMs;
                }
                long off = encOffsetNs;
                if (!started || firstPtsUs < 0 || !haveTs || off == Long.MIN_VALUE) {
                    // prime the AAC encoder (its format is needed to start the file); time 0, never written
                    if (audioTrack < 0) queue(aac, pcm, n, 0);
                    drainAudio(aac, info);
                    continue;
                }
                long t0 = firstPtsUs * 1000;
                while (true) {
                    long tOut = t0 + outCount * 1_000_000_000L / AUDIO_RATE;
                    long tReal = realTime(tOut, t0);
                    if (tReal == Long.MIN_VALUE) break;               // its video frames not known yet
                    double x = ts.framePosition + (tReal - off - soundOffsetNs - ts.nanoTime) * (AUDIO_RATE / 1e9);
                    if (x + 3 > inCount) break;                        // not recorded yet
                    short v = x < Math.max(1, inCount - ring + 2) ? 0 : cubic(in, x, ring);
                    outBytes[2 * outFill] = (byte) v;
                    outBytes[2 * outFill + 1] = (byte) (v >> 8);
                    outFill++;
                    outCount++;
                    if (outFill == 1024) {
                        queue(aac, outBytes, outFill * 2, (t0 + outChunkStart * 1_000_000_000L / AUDIO_RATE) / 1000);
                        outChunkStart = outCount;
                        outFill = 0;
                        drainAudio(aac, info);
                    }
                }
                drainAudio(aac, info);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            rec.release();
            try {
                aac.stop();
            } catch (Exception ignored) {
            }
            aac.release();
        }
    }

    private static void queue(MediaCodec aac, byte[] data, int len, long ptsUs) {
        int idx = aac.dequeueInputBuffer(10_000);
        if (idx < 0) return;
        ByteBuffer ib = aac.getInputBuffer(idx);
        ib.clear();
        ib.put(data, 0, len);
        aac.queueInputBuffer(idx, 0, len, ptsUs, 0);
    }

    /** Cubic (Catmull-Rom) interpolation of the input ring at fractional position x. */
    private static short cubic(short[] r, double x, int ring) {
        long i = (long) Math.floor(x);
        double f = x - i;
        double y0 = r[(int) ((i - 1) % ring)], y1 = r[(int) (i % ring)];
        double y2 = r[(int) ((i + 1) % ring)], y3 = r[(int) ((i + 2) % ring)];
        double c1 = 0.5 * (y2 - y0), c2 = y0 - 2.5 * y1 + 2 * y2 - 0.5 * y3, c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2);
        double v = ((c3 * f + c2) * f + c1) * f + y1;
        return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
    }

    /**
     * Real time (encoder time base, ns) of the written time tOut: through the real times of the
     * video frames around it (conform), or the same (real timing). MIN_VALUE if not known yet.
     */
    private long realTime(long tOut, long t0) {
        if (conformFps <= 0) return tOut;
        double pos = (tOut - t0) * conformFps / 1e9;
        long n = (long) Math.floor(pos);
        if (n + 1 >= framesN) return Long.MIN_VALUE;
        long a = frameReal[(int) (n % frameReal.length)], b = frameReal[(int) ((n + 1) % frameReal.length)];
        return a + Math.round((pos - n) * (b - a));
    }

    private void drainAudio(MediaCodec aac, MediaCodec.BufferInfo info) {
        while (true) {
            int idx = aac.dequeueOutputBuffer(info, 0);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (lock) {
                    audioTrack = muxer.addTrack(aac.getOutputFormat());
                    maybeStartMuxer();
                }
                continue;
            }
            if (idx < 0) return;
            ByteBuffer b = aac.getOutputBuffer(idx);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            // sound only between the first and the last written video frame
            long first = firstPtsUs;
            long stopPts = doneWriting ? lastPtsUs : Long.MAX_VALUE;
            if (!config && info.size > 0 && b != null && started && first >= 0
                    && info.presentationTimeUs >= first && info.presentationTimeUs <= stopPts) {
                synchronized (lock) {
                    if (muxerStarted) {
                        muxer.writeSampleData(audioTrack, b, info);
                        audioFrames++;
                    }
                }
            }
            aac.releaseOutputBuffer(idx, false);
        }
    }
}
