package com.crunchy3d.synccamphone;

import android.os.Process;
import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Time reference and controller: answers clock pings, broadcasts beacons, collects camera status,
 * distributes the control state (start/stop/align + settings) and the reference frame grid.
 */
final class MasterNode {

    static final class ClientInfo {
        String id, name;
        InetAddress address;
        int port;
        volatile long lastSeenMs;
        double offsetUs, offsetErrUs, scatterUs, minRttMs, medRttMs, driftPpm, residualUs;
        int used;
        long windowS;
        boolean streaming, phaseValid, ownValid, aligned;
        double phaseUs, ownPeriodNs;
        long ownT0;
        int steps;
        boolean lost;
        int capProgressS = -1, ledY = -1, ledPeak, recFrames = -1, photoSeq = -1;
        int side = -1, maxImg, maxVid;          // Downloads.SIDE_* (-1 = unknown), highest file numbers
        int lens = -1;                            // Lenses kind in use on that camera (-1 = unknown)
        // joint AE / WB: the camera's latest measurement (aeMs = when it arrived, 0 = none)
        int aeSeq = -1;
        long aeMs;
        double aeRatio = Double.NaN, aeLight, aeClip;
        float rawDark = Float.NaN, rawMedian = Float.NaN;
        float[] awb;                              // its own AWB gains (r, ge, go, b) or null
        double photoErrUs;
    }

    /** A value for the graph: series key, time (elapsedRealtime ms), value. */
    static final class Point {
        final String key;
        final long timeMs;
        final double value;

        Point(String key, long timeMs, double value) {
            this.key = key;
            this.timeMs = timeMs;
            this.value = value;
        }
    }

    final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<Point> points = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> log = new ConcurrentLinkedQueue<>();

    // control state
    private volatile int stateSeq;
    volatile boolean running, align;
    volatile long periodNs = 33_333_333, exposureNs = 10_000_000;
    volatile int iso = 400;
    volatile int captureSeq, captureSeconds;
    /** asymNs / soundOffsetNs value "auto": each camera uses the value measured for its own model. */
    static final long AUTO = Long.MIN_VALUE;
    volatile long asymNs = AUTO;
    volatile boolean record;
    volatile int recordId;
    volatile int videoHeight = 1080;
    volatile long recStart, recStop;          // master time of the first / last recorded frame
    volatile long soundOffsetNs = AUTO;           // AUTO or a manual value for all cameras
    volatile boolean photoMode;
    volatile int photoSeq;
    volatile int ownMaxImg, ownMaxVid;        // highest file numbers on the master phone itself
    volatile boolean aeActive;                // the cameras measure for the joint AE
    volatile boolean standby;                 // cameras asleep (power), the master wakes them
    volatile double aeAllow = 0.001;          // AE: allowed clipped fraction (the master's setting)
    volatile long photoTime;                  // master time of the photo frame
    // white balance + focus (same on all cameras)
    volatile int wbMode = SyncCamera.WB_AUTO;
    volatile int awbPreset = android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT;
    volatile float[] wbGains;
    volatile int[] wbXform;
    volatile boolean focusManual;
    volatile float focusDiopters;
    volatile int photoAspect;                 // index into PhotoCapture.ASPECTS
    volatile int photoFormat;                 // index into PhotoCapture.FORMATS
    volatile int conformFps = 30;             // video written at exactly 30 fps (0 = real timing)
    volatile int videoBitrate;                // bit/s, 0 = automatic
    volatile boolean hevc;                    // HEVC instead of H.264
    volatile boolean appJpeg;                 // photo JPEG made in the app from full-res YUV
    // SyncCamera.TONES; default standard: on the Pixel 7a the app's own curve (soft / strong)
    // gave banding and crushed blacks (test 2026-11-14), so it is only an experiment
    volatile int tone = 0;
    volatile boolean keepBurstFrames;          // HDR burst: also save the 8 single DNGs
    volatile int jpegQuality = 95;             // PhotoCapture.JPEG_QUALITIES
    volatile int sharpLevel = MergeCore.SHARP_DEFAULT;   // MergeCore.SHARP_NAMES
    volatile int clarityLevel = MergeCore.CLARITY_DEFAULT;   // MergeCore.CLARITY_NAMES
    volatile int astroStopSeq;                // +1: all phones stop their astro series
    // NIGHT MODE of JPEG / RAW (item 19, 2026-11-18): -1, or the user's format, shot as an averaging
    // burst (HDR Auto, noise frames only) because the scene is too dark for one frame at the max ISO
    volatile int nightOut = -1;
    volatile int gpuMode = 1;                 // MergeCore.GPU_NAMES (On; Check = GPU + CPU compared)
    volatile int remoteMode;                  // item 20: 1 = remote trigger of another camera app
    volatile int lens;                        // Lenses kind (0 = main, always at start)
    volatile boolean cbr;                     // constant video bitrate (else VBR: the bitrate is a maximum)
    volatile int timelapse;                   // 0, or the first photo number of the running time-lapse
    volatile String sideWarn = "-";           // phones that share a side (file names will not pair), or "-"

    /** The format the phones shoot: 3 (burst) in the night mode of JPEG / RAW. */
    int effFormat() {
        return photoMode && nightOut >= 0 ? 3 : photoFormat;
    }

    int effHdrMode() {
        return photoMode && nightOut >= 0 ? PhotoBurst.MODE_AUTO : hdrMode;
    }
    volatile int hdrMode = PhotoBurst.MODE_AUTO;   // HDR burst: PhotoBurst.MODES
    volatile int brightness = MergeCore.BRIGHT_NATURAL;   // develop: 0 night look, 1 bright, 2 natural
    volatile boolean natural;                  // longer frames near 1/31 s: natural motion blur
    volatile String hdrPlan = "-";             // longer frames "exp:iso;..." (set by AE / at the press)
    volatile boolean toneShare;                // the master develops a burst too and sends its rendering

    SyncCamera.Look look() {
        return new SyncCamera.Look(wbMode, awbPreset, wbGains, wbXform, focusManual, focusDiopters);
    }

    /** Sends the changed white balance / focus to all cameras. */
    void lookChanged(String what) {
        stateSeq++;
        log.add(what);
        new Thread(this::sendStateToAll).start();
    }

    // reference grid
    volatile String refId = "";
    volatile boolean refValid;
    volatile long refT0;
    volatile double refPeriodNs;
    private final boolean hasOwnCamera;

    private final String name;
    private DatagramSocket socket;
    private Thread rxThread, beaconThread;
    private volatile boolean runningNet;

    MasterNode(String name, boolean hasOwnCamera) {
        this.name = name;
        this.hasOwnCamera = hasOwnCamera;
    }

    void start() throws Exception {
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(SyncNet.PORT));
        runningNet = true;
        rxThread = new Thread(this::receiveLoop, "master-rx");
        beaconThread = new Thread(this::beaconLoop, "master-beacon");
        rxThread.start();
        beaconThread.start();
    }

    void stop() {
        runningNet = false;
        if (socket != null) socket.close();
        if (beaconThread != null) beaconThread.interrupt();
    }

    // ------------------------------------------------------------------ control

    void setState(boolean running, boolean align, long periodNs, long exposureNs, int iso) {
        this.running = running;
        this.align = running && align;
        if (!running && record) {
            record = false;
            recStop = SystemClock.elapsedRealtimeNanos();
        }
        this.periodNs = periodNs;
        this.exposureNs = exposureNs;
        this.iso = iso;
        stateSeq++;
        log.add((running ? "start " : "stop") + (running ? String.format(java.util.Locale.US,
                "%.3f fps, exp %.2f ms, ISO %d%s", 1e9 / periodNs, exposureNs / 1e6, iso,
                this.align ? ", align ON" : "") : ""));
        new Thread(this::sendStateToAll).start();
    }

    /** Starts an LED capture of the given length on all cameras (and the master's own). */
    void startCapture(int seconds) {
        captureSeconds = seconds;
        captureSeq++;
        stateSeq++;
        log.add("LED capture " + seconds + " s");
        new Thread(this::sendStateToAll).start();
    }

    /**
     * Starts / stops video recording on all cameras. The start time (master time) is 1.5 s ahead so
     * every phone can pre-roll its encoder; all files then begin with the same frame. The stop
     * time is 0.3 s ahead so all files end with the same frame.
     */
    void setRecord(boolean on) {
        if (on == record) return;
        long now = SystemClock.elapsedRealtimeNanos();
        if (on) {
            int mx = Math.max(recordId, ownMaxVid);
            for (ClientInfo c : clients.values()) if (!c.lost) mx = Math.max(mx, c.maxVid);
            recordId = mx + 1;
            recStart = now + 1_500_000_000L;      // 4K encoders need about 1 s to start
            recStop = 0;
        } else {
            recStop = Math.max(now + 300_000_000L, recStart);
        }
        record = on;
        stateSeq++;
        log.add(on ? "record #" + recordId + " start" : "record #" + recordId + " stop");
        new Thread(this::sendStateToAll).start();
    }

    /** Switches all cameras between video and photo mode (cameras restart). */
    void setPhotoMode(boolean on) {
        if (on == photoMode) return;
        if (on && record) setRecord(false);
        photoMode = on;
        stateSeq++;
        log.add(on ? "photo mode" : "video mode");
        new Thread(this::sendStateToAll).start();
    }

    /** Synchronized photo of the reference frame about 0.6 s from now, on all cameras. */
    /**
     * Photo at master time t: HDR burst = the moment the shutter was touched (frames from the ring,
     * zero lag); camera JPEG / RAW = about 0.25 s ahead (the full-size stream needs ~0.2 s to start).
     */
    volatile long lastPhotoMs;

    /**
     * True while a camera phone (seen in the last 5 s) has not reported the last photo as saved
     * (HDR burst: ~4 s per burst). At most 15 s, in case a phone missed the photo.
     */
    boolean photoBusy() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPhotoMs > 15000) return false;
        for (ClientInfo c : clients.values()) {
            if (now - c.lastSeenMs < 5000 && c.photoSeq < photoSeq) return true;
        }
        return false;
    }

    /** Item 20: a remote photo at master time atNs (all phones press the other app's shutter then). */
    void remoteShot(long atNs) {
        lastPhotoMs = SystemClock.elapsedRealtime();
        photoTime = atNs;
        photoSeq++;
        stateSeq++;
        log.add("remote photo #" + photoSeq);
        new Thread(() -> {
            for (int i = 0; i < 3; i++) {
                sendStateToAll();
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ignored) {
                }
            }
        }).start();
    }

    /** The number the next photo gets: after the highest file number on any phone. */
    int nextPhotoNumber() {
        int mx = Math.max(photoSeq, ownMaxImg);
        for (ClientInfo c : clients.values()) if (!c.lost) mx = Math.max(mx, c.maxImg);
        return mx + 1;
    }

    /** Every connected camera has saved photo seq (and the master's own photo is done). */
    boolean photoSavedEverywhere(int seq) {
        if (photoBusy()) return false;
        for (ClientInfo c : clients.values()) if (!c.lost && c.photoSeq < seq) return false;
        return true;
    }

    void takePhoto(long touchNs) {
        lastPhotoMs = SystemClock.elapsedRealtime();
        long now = SystemClock.elapsedRealtimeNanos();
        long t = effFormat() == 3 ? Math.min(touchNs, now) : now + 250_000_000L;
        if (refValid && refPeriodNs > 0) {
            // exactly on a frame of the reference grid: every camera's nearest frame is ~0 away
            double k = effFormat() == 3 ? Math.floor((t - refT0) / refPeriodNs) : Math.ceil((t - refT0) / refPeriodNs);
            t = refT0 + Math.round(k * refPeriodNs);
        }
        photoTime = t;
        // the number continues after the highest file number on any phone (file names
        // 3D_img_NNNN-l / -r: the same number on both phones of a stereo pair)
        photoSeq = nextPhotoNumber();
        stateSeq++;
        log.add("photo #" + photoSeq);
        // three sends: one lost Wi-Fi packet must not make a camera miss the photo
        new Thread(() -> {
            for (int i = 0; i < 3; i++) {
                sendStateToAll();
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }).start();
    }

    /** Reference = master's own camera if present, else the first streaming camera. */
    void updateReference(PhaseAligner self) {
        if (hasOwnCamera) {
            refId = SyncNet.SELF;
            if (self != null && self.ownValid && running) {
                refT0 = self.ownT0;
                refPeriodNs = self.ownPeriodNs;
                refValid = true;
            } else {
                refValid = false;
            }
            return;
        }
        ClientInfo best = null;
        for (ClientInfo c : clients.values()) {
            if (c.lost || !c.streaming || !c.ownValid) continue;
            if (best == null || c.name.compareTo(best.name) < 0) best = c;
        }
        if (best == null || !running) {
            refValid = false;
            return;
        }
        if (!best.id.equals(refId)) log.add("reference: " + best.name);
        refId = best.id;
        refT0 = best.ownT0;
        refPeriodNs = best.ownPeriodNs;
        refValid = true;
    }

    private byte[] stateMsg() {
        return SyncNet.msg(SyncNet.STATE, stateSeq, running ? 1 : 0, periodNs, exposureNs, iso,
                align ? 1 : 0, refId, refValid ? 1 : 0, refT0, refPeriodNs, captureSeq, captureSeconds, asymNs,
                record && running ? 1 : 0, recordId, videoHeight, recStart, recStop, soundOffsetNs,
                photoMode ? 1 : 0, photoSeq, photoTime,
                wbMode, awbPreset, SyncNet.join(wbGains), SyncNet.join(wbXform), focusManual ? 1 : 0, focusDiopters,
                photoAspect, effFormat(), conformFps, videoBitrate, hevc ? 1 : 0, appJpeg ? 1 : 0, tone, keepBurstFrames ? 1 : 0,
                effHdrMode(), brightness, natural ? 1 : 0, hdrPlan, toneShare ? 1 : 0, jpegQuality,
                aeActive ? 1 : 0, aeAllow, standby ? 1 : 0, sharpLevel, clarityLevel, astroStopSeq,
                photoMode ? nightOut : -1, gpuMode, remoteMode, lens, cbr ? 1 : 0, timelapse, sideWarn.replace('|', '/'));
    }

    /** The master's rendering of HDR burst #seq -> all cameras (sent 3x: UDP). */
    void sendTone(int seq, String tone) {
        byte[] out = SyncNet.msg(SyncNet.TONE, seq, tone);
        List<ClientInfo> list = new ArrayList<>(clients.values());
        new Thread(() -> {
            for (int i = 0; i < 3; i++) {
                for (ClientInfo c : list) {
                    try {
                        socket.send(new DatagramPacket(out, out.length, c.address, c.port));
                    } catch (Exception ignored) {
                    }
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }).start();
    }

    private void sendStateToAll() {
        byte[] out = stateMsg();
        List<ClientInfo> list = new ArrayList<>(clients.values());
        for (ClientInfo c : list) {
            try {
                socket.send(new DatagramPacket(out, out.length, c.address, c.port));
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ network

    private void receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[1024];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (runningNet) {
            try {
                p.setLength(buf.length);
                socket.receive(p);
                long t2 = SystemClock.elapsedRealtimeNanos();
                String[] f = SyncNet.parse(p);
                if (f == null) continue;
                if (SyncNet.PING.equals(f[0]) && f.length >= 3) {
                    long t3 = SystemClock.elapsedRealtimeNanos();
                    byte[] out = SyncNet.msg(SyncNet.PONG, f[1], f[2], t2, t3);
                    socket.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                } else if (SyncNet.STATUS.equals(f[0]) && f.length >= SyncNet.STATUS_FIELDS) {
                    handleStatus(f, p.getAddress(), p.getPort());
                    byte[] out = stateMsg();
                    socket.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                }
            } catch (Exception e) {
                if (!runningNet) return;
            }
        }
    }

    private void handleStatus(String[] f, InetAddress from, int port) {
        String id = f[1];
        ClientInfo c = clients.get(id);
        if (c == null) {
            c = new ClientInfo();
            c.id = id;
            clients.put(id, c);
            log.add("connected: " + f[2] + " (" + from.getHostAddress() + ")");
        } else if (c.lost) {
            log.add("back: " + f[2]);
        }
        c.lost = false;
        c.name = f[2];
        c.address = from;
        c.port = port;
        c.offsetUs = Double.parseDouble(f[3]) / 1e3;
        c.offsetErrUs = Double.parseDouble(f[4]) / 1e3;
        c.scatterUs = Double.parseDouble(f[5]) / 1e3;
        c.minRttMs = Double.parseDouble(f[6]) / 1e6;
        c.medRttMs = Double.parseDouble(f[7]) / 1e6;
        c.driftPpm = Double.parseDouble(f[8]) / 1e3;
        c.residualUs = Double.parseDouble(f[9]) / 1e3;
        c.used = Integer.parseInt(f[10]);
        c.windowS = Long.parseLong(f[11]);
        c.streaming = "1".equals(f[12]);
        c.phaseValid = "1".equals(f[13]);
        c.phaseUs = Double.parseDouble(f[14]) / 1e3;
        c.ownValid = "1".equals(f[15]);
        c.ownT0 = Long.parseLong(f[16]);
        c.ownPeriodNs = Double.parseDouble(f[17]);
        int steps = Integer.parseInt(f[18]);
        if (steps > c.steps) log.add(String.format(java.util.Locale.US, "%s: step #%d", c.name, steps));
        c.steps = steps;
        c.aligned = "1".equals(f[19]);
        c.capProgressS = Integer.parseInt(f[20]);
        c.ledY = Integer.parseInt(f[21]);
        c.ledPeak = Integer.parseInt(f[22]);
        c.recFrames = Integer.parseInt(f[23]);
        int ps = Integer.parseInt(f[24]);
        if (ps > c.photoSeq && ps > 0) {
            log.add(String.format(java.util.Locale.US, "%s: photo #%d %+.0f us", c.name, ps,
                    Long.parseLong(f[25]) / 1e3));
        }
        c.photoSeq = ps;
        c.photoErrUs = Long.parseLong(f[25]) / 1e3;
        if (f.length > 28) {
            c.side = Integer.parseInt(f[26]);
            c.maxImg = Integer.parseInt(f[27]);
            c.maxVid = Integer.parseInt(f[28]);
        }
        if (f.length > 35) {
            int seq = Integer.parseInt(f[29]);
            if (seq != c.aeSeq) {
                c.aeSeq = seq;
                c.aeMs = SystemClock.elapsedRealtime();
                c.aeRatio = Double.parseDouble(f[30]);
                c.aeLight = Double.parseDouble(f[31]);
                c.aeClip = Double.parseDouble(f[32]);
                c.rawDark = Float.parseFloat(f[33]);
                c.rawMedian = Float.parseFloat(f[34]);
                c.awb = SyncNet.floats(f[35]);
            }
        }
        if (f.length > 36) c.lens = Integer.parseInt(f[36]);
        c.lastSeenMs = SystemClock.elapsedRealtime();
        if (running) {
            if (c.phaseValid && !id.equals(refId)) points.add(new Point(c.name, c.lastSeenMs, c.phaseUs));
        } else {
            points.add(new Point(c.name, c.lastSeenMs, c.residualUs));
        }
    }

    /** Marks clients without status for 3 s as lost (called from the UI tick). */
    void checkLost() {
        long now = SystemClock.elapsedRealtime();
        for (ClientInfo c : clients.values()) {
            if (!c.lost && now - c.lastSeenMs > 3000) {
                c.lost = true;
                log.add("lost: " + c.name);
            }
        }
    }

    private void beaconLoop() {
        while (runningNet) {
            // (only a MONO camera phone: a MASTER-only phone has no side and counts as MONO too, but it
            // must announce itself - the Beam Pro as master-only was never found, 2026-11-22)
            if (Downloads.side == Downloads.SIDE_MONO && hasOwnCamera) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            byte[] out = SyncNet.msg(SyncNet.BEACON, name);
            for (InetAddress b : SyncNet.broadcastAddresses()) {
                try {
                    socket.send(new DatagramPacket(out, out.length, b, SyncNet.PORT));
                } catch (Exception ignored) {
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
