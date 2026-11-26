package com.crunchy3d.synccamphone;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Camera side: finds the master, pings it continuously and reports clock status.
 * Re-attaches automatically when Wi-Fi reconnects or the master stops answering, and logs
 * every ping (t1..t4) to Download/CamSyncProbe/clock_*.csv for offline analysis.
 */
final class CameraNode {

    // 10 pings per second once settled (was 20: Wi-Fi power; the clock filter keeps the lowest 10 %
    // of 120 s = still ~120 samples)
    static final long PING_INTERVAL_MS = 100;
    static final long FAST_PING_INTERVAL_MS = 10; // 100 pings per second ...
    static final long FAST_PING_MS = 10_000;      // ... for the first 10 s after (re)attach
    static final long STATUS_INTERVAL_MS = 500;
    static final long NO_REPLY_REBIND_MS = 5000;

    final ClockEstimator estimator = new ClockEstimator();
    volatile InetAddress master;
    volatile String masterName = "";
    volatile String masterSource = "";
    volatile long lastPongMs;
    volatile int reattachCount;
    volatile String logPath = "";

    // control state received from the master (STATE message)
    volatile int stateSeq = -1;
    volatile boolean running, align;
    volatile long periodNs, exposureNs;
    volatile int iso;
    volatile String refId = "";
    volatile PhaseAligner.Ref ref;
    volatile long lastStateMs;
    volatile int captureSeq = -1, captureSeconds;
    volatile long asymNs;                     // Wi-Fi asymmetry correction from the master
    volatile boolean record;
    volatile int recordId;
    volatile int videoHeight = 1080;
    volatile long recStart, recStop;          // master time of the first / last recorded frame
    volatile long soundOffsetNs;
    volatile boolean photoMode;
    volatile int photoSeq = -1;
    /** Called (network thread) when a state with a new photo number arrives: no wait for the UI tick. */
    volatile Runnable onNewPhoto;
    volatile long photoTime;
    volatile int photoSeqDone = -1;
    volatile int maxImg, maxVid;              // highest 3D_/2D_ img / vid number found on this phone
    // joint AE / WB (SyncNet STATUS 29-35, STATE 43-44)
    volatile boolean aeActive;
    volatile boolean standby;                 // the master put the cameras to sleep
    volatile double aeAllow = 0.001;
    volatile int aeSeq;
    volatile double aeRatio = Double.NaN, aeLight, aeClip;
    volatile float rawDark = Float.NaN, rawMedian = Float.NaN;
    volatile float[] awb;
    private int prevPhotoSeq = -1;           // set by the activity
    volatile SyncCamera.Look look;            // white balance + focus from the master
    volatile int photoAspect;
    volatile int photoFormat;
    volatile int conformFps;
    volatile int videoBitrate;
    volatile boolean hevc;
    volatile boolean appJpeg;
    volatile int tone;
    volatile boolean keepBurstFrames;
    volatile int jpegQuality = 95;
    volatile int sharpLevel = MergeCore.SHARP_DEFAULT;
    volatile int clarityLevel = MergeCore.CLARITY_DEFAULT;
    volatile int astroStopSeq = -1;
    volatile int nightOut = -1;               // night mode: the user's format (photoFormat is then 3)
    volatile int gpuMode;                     // MergeCore.GPU_NAMES
    volatile int remoteMode;                  // item 20: remote trigger of another camera app
    volatile int lens;                        // the master's lens (Lenses kind)
    volatile boolean fillLost;                // fill lost video frames (STATE 57)
    volatile int previewW = 1920;             // preview width limit (STATE 56)
    volatile boolean cbr;                     // constant video bitrate
    volatile int timelapse;                   // 0, or the folder number of the running time-lapse
    volatile String sideWarn = "-";           // the CONTROL phone's same-side warning, or "-"
    // item 38 (2026-11-24): tap focus on a camera phone (CONTROL without a camera): the measured distance
    // goes to the CONTROL phone (STATUS 37-39), which sets it for all phones
    volatile int focusSeq;
    volatile float focusTapD;
    volatile int focusTapSharp;
    volatile String vidMissing = "-";       // item 41: "#5 1 frame missing at 0:07.2" (STATUS 40)
    volatile int lensInUse = -1;              // the lens this camera uses (sent in STATUS 36)

    /** The format the user chose (the AE meters for it). */
    int userFormat() {
        return nightOut >= 0 ? nightOut : photoFormat;
    }
    volatile int hdrMode = PhotoBurst.MODE_AUTO, brightness;
    volatile boolean natural;
    volatile String hdrPlan = "-";
    volatile boolean toneShare;
    volatile int toneSeq = -1;                // the master's rendering of HDR burst #toneSeq
    volatile String toneText;
    volatile long photoErrNs;
    volatile int recFrames = -1;              // set by the activity, -1 = not recording

    // local camera, set by the activity (for the status message)
    volatile PhaseAligner aligner;
    volatile LedCapture led;
    volatile boolean streaming;

    // cached clock fit for fast per-frame conversion: offset(t) = a + b * (t - tc)
    private volatile double[] conversion;

    private final Context ctx;
    private final String id, name;
    private volatile DatagramSocket socket;
    private volatile Network boundNetwork;
    private volatile long lastRebindMs;
    private Thread rxThread, txThread;
    private volatile boolean netActive;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Object logLock = new Object();
    private BufferedWriter log;

    CameraNode(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.id = SyncNet.deviceId(ctx);
        this.name = SyncNet.deviceName(ctx);
    }

    String name() {
        return name;
    }

    @SuppressWarnings("deprecation")
    void start() {
        WifiManager wm = ctx.getSystemService(WifiManager.class);
        multicastLock = wm.createMulticastLock(App.NAME);
        multicastLock.acquire();
        int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wm.createWifiLock(mode, App.NAME);
        wifiLock.acquire();
        openLog();
        loadPrior();

        netActive = true;
        rebind(SyncNet.wifiNetwork(ctx), "start");

        // Re-attach whenever a (new) Wi-Fi network appears, e.g. after the hotspot restarted.
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                if (!n.equals(boundNetwork)) rebind(n, "Wi-Fi connected");
            }
            @Override public void onLost(Network n) {
                logEvent("Wi-Fi lost");
            }
        };
        ctx.getSystemService(ConnectivityManager.class).registerNetworkCallback(req, networkCallback);

        rxThread = new Thread(this::receiveLoop, "camera-rx");
        txThread = new Thread(this::sendLoop, "camera-tx");
        rxThread.start();
        txThread.start();
    }

    void stop() {
        netActive = false;
        try {
            ctx.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {
        }
        DatagramSocket s = socket;
        if (s != null) s.close();
        if (txThread != null) txThread.interrupt();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        synchronized (logLock) {
            try {
                if (log != null) log.close();
            } catch (Exception ignored) {
            }
            log = null;
        }
    }

    String id() {
        return id;
    }

    /**
     * Converts a local elapsedRealtime timestamp (ns) to master time (MIN_VALUE if unknown).
     * The ping offset is biased by the Wi-Fi delay asymmetry; asymNs (from the master) removes it.
     */
    long toMaster(long localNs) {
        double[] c = conversion;
        if (c == null) return Long.MIN_VALUE;
        return localNs + Math.round(c[0] + c[1] * (localNs - c[2])) - effAsymNs();
    }

    /** The asymmetry correction in use: the master's manual value, or this phone model's own. */
    long effAsymNs() {
        long a = asymNs;
        return a == MasterNode.AUTO ? SyncActivity.modelDefaults()[0] * 1000L : a;
    }

    // Clock fit remembered across app restarts (valid while neither phone rebooted): used until
    // the fresh fit has 30 s of data, if the first fresh pings agree with it within 2 ms.
    private static final long PRIOR_MAX_AGE_NS = 30L * 60 * 1_000_000_000L;
    private volatile double[] prior;          // {offsetNs at tc, drift (ns/ns), tc}
    private volatile String priorMaster = "";
    private volatile long priorSavedNs;
    private long lastPriorSaveMs;
    volatile long clockErrNs = -1;            // uncertainty of the conversion in use (-1 = none)
    volatile String clockSource = "-";

    private void loadPrior() {
        android.content.SharedPreferences p = ctx.getSharedPreferences("clock", Context.MODE_PRIVATE);
        long saved = p.getLong("saved", 0), now = SystemClock.elapsedRealtimeNanos();
        if (saved <= 0 || saved > now || now - saved > PRIOR_MAX_AGE_NS) return;   // rebooted or old
        prior = new double[]{Double.longBitsToDouble(p.getLong("a", 0)), Double.longBitsToDouble(p.getLong("b", 0)),
                p.getLong("tc", 0)};
        priorMaster = p.getString("master", "");
        priorSavedNs = saved;
        logEvent("clock prior loaded, age " + (now - saved) / 1_000_000_000L + " s");
    }

    private void savePrior(ClockEstimator.Result r, long now) {
        InetAddress m = master;
        if (m == null) return;
        ctx.getSharedPreferences("clock", Context.MODE_PRIVATE).edit()
                .putLong("a", Double.doubleToLongBits(r.offsetNs))
                .putLong("b", Double.doubleToLongBits(r.driftPpm * 1e-6))
                .putLong("tc", now).putLong("saved", now).putString("master", m.getHostAddress()).apply();
    }

    private void updateConversion() {
        long now = SystemClock.elapsedRealtimeNanos();
        ClockEstimator.Result r = estimator.compute(now);
        double[] fit = r.valid && r.used >= 8 ? new double[]{r.offsetNs, r.driftPpm * 1e-6, now} : null;
        double[] pr = prior;
        if (pr != null) {
            InetAddress m = master;
            boolean sameMaster = m != null && m.getHostAddress().equals(priorMaster);
            double predicted = pr[0] + pr[1] * (now - pr[2]);
            if (!sameMaster || (fit != null && Math.abs(fit[0] - predicted) > 2_000_000)) {
                logEvent("clock prior dropped (" + (sameMaster ? "disagrees by "
                        + Math.round((fit[0] - predicted) / 1e3) + " us" : "other master") + ")");
                prior = pr = null;
            }
        }
        double ageS = (now - priorSavedNs) / 1e9;
        long priorErr = (long) (20_000 + 0.2e-6 * ageS * 1e9);  // ~20 us + 0.2 ppm drift uncertainty
        // keep the remembered fit until the fresh one is more precise (at most 2 min)
        if (pr != null && (fit == null || r.windowS < 30
                || (r.windowS < 120 && r.offsetErrNs > priorErr))) {
            conversion = pr;
            clockErrNs = priorErr;
            clockSource = "remembered";
        } else {
            conversion = fit;
            clockErrNs = fit == null ? -1 : (long) r.offsetErrNs;
            clockSource = fit == null ? "-" : "fit " + Math.round(r.windowS) + " s";
        }
        long nowMs = SystemClock.elapsedRealtime();
        if (fit != null && r.windowS >= 60 && nowMs - lastPriorSaveMs > 10_000) {
            savePrior(r, now);
            lastPriorSaveMs = nowMs;
        }
    }

    // ------------------------------------------------------------------ socket handling

    /** (Re)creates the UDP socket bound to the given Wi-Fi network. */
    private synchronized void rebind(Network n, String reason) {
        if (!netActive) return;
        DatagramSocket old = socket;
        socket = null;
        if (old != null) old.close();
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(SyncNet.PORT));
            // The hotspot has no internet, so Android may prefer mobile data: bind to Wi-Fi explicitly.
            if (n != null) {
                try {
                    n.bindSocket(s);
                } catch (Exception ignored) {
                }
            }
            InetAddress gw = SyncNet.wifiGateway(ctx, n);
            if (gw != null && !gw.equals(master)) {
                master = gw;
                masterSource = "Wi-Fi gateway";
            }
            boundNetwork = n;
            socket = s;
        } catch (Exception e) {
            logEvent("socket error: " + e.getMessage());
        }
        estimator.reset();
        conversion = null;
        lastRebindMs = SystemClock.elapsedRealtime();
        if (!"start".equals(reason)) reattachCount++;
        logEvent("attach (" + reason + "), master " + (master == null ? "-" : master.getHostAddress()));
    }

    private void receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        byte[] buf = new byte[512];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (netActive) {
            DatagramSocket s = socket;
            if (s == null) {
                SystemClock.sleep(50);
                continue;
            }
            try {
                p.setLength(buf.length);
                s.receive(p);
                long t4 = SystemClock.elapsedRealtimeNanos();
                String[] f = SyncNet.parse(p);
                if (f == null) continue;
                if (SyncNet.PONG.equals(f[0]) && f.length >= 5) {
                    long t1 = Long.parseLong(f[2]), t2 = Long.parseLong(f[3]), t3 = Long.parseLong(f[4]);
                    estimator.add(t1, t2, t3, t4);
                    lastPongMs = SystemClock.elapsedRealtime();
                    logSample(t1, t2, t3, t4);
                } else if (SyncNet.TONE.equals(f[0]) && f.length >= 3) {
                    // the payload itself contains '|': everything after the sequence number
                    StringBuilder tb = new StringBuilder();
                    for (int i = 2; i < f.length; i++) tb.append(i > 2 ? "|" : "").append(f[i]);
                    toneText = tb.toString();
                    toneSeq = Integer.parseInt(f[1]);
                } else if (SyncNet.STATE.equals(f[0]) && f.length >= SyncNet.STATE_FIELDS) {
                    handleState(f);
                } else if (SyncNet.BEACON.equals(f[0]) && f.length >= 2) {
                    InetAddress from = p.getAddress();
                    if (!from.equals(master)) {
                        estimator.reset();
                        conversion = null;
                        master = from;
                        logEvent("master from beacon: " + from.getHostAddress());
                    }
                    masterName = f[1];
                    masterSource = "beacon";
                }
            } catch (Exception e) {
                if (!netActive) return;
                SystemClock.sleep(20);   // socket replaced or closed during rebind
            }
        }
    }

    private void handleState(String[] f) {
        int seq = Integer.parseInt(f[1]);
        if (seq != stateSeq) {
            logEvent("state " + String.join("|", f));
            stateSeq = seq;
        }
        running = "1".equals(f[2]);
        periodNs = Long.parseLong(f[3]);
        exposureNs = Long.parseLong(f[4]);
        iso = Integer.parseInt(f[5]);
        align = "1".equals(f[6]);
        refId = f[7];
        ref = "1".equals(f[8]) ? new PhaseAligner.Ref(Long.parseLong(f[9]), Double.parseDouble(f[10])) : null;
        captureSeconds = Integer.parseInt(f[12]);
        captureSeq = Integer.parseInt(f[11]);
        asymNs = Long.parseLong(f[13]);
        record = "1".equals(f[14]);
        recordId = Integer.parseInt(f[15]);
        videoHeight = Integer.parseInt(f[16]);
        recStart = Long.parseLong(f[17]);
        recStop = Long.parseLong(f[18]);
        soundOffsetNs = Long.parseLong(f[19]);
        photoMode = "1".equals(f[20]);
        photoSeq = Integer.parseInt(f[21]);
        photoTime = Long.parseLong(f[22]);
        look = new SyncCamera.Look(Integer.parseInt(f[23]), Integer.parseInt(f[24]), SyncNet.floats(f[25]),
                SyncNet.ints(f[26]), "1".equals(f[27]), Float.parseFloat(f[28]));
        photoAspect = Integer.parseInt(f[29]);
        photoFormat = Integer.parseInt(f[30]);
        conformFps = Integer.parseInt(f[31]);
        videoBitrate = Integer.parseInt(f[32]);
        hevc = "1".equals(f[33]);
        appJpeg = "1".equals(f[34]);
        tone = Integer.parseInt(f[35]);
        keepBurstFrames = "1".equals(f[36]);
        hdrMode = f.length > 37 ? Integer.parseInt(f[37]) : PhotoBurst.MODE_AUTO;
        brightness = f.length > 38 ? Integer.parseInt(f[38]) : 0;
        natural = f.length > 39 && "1".equals(f[39]);
        hdrPlan = f.length > 40 ? f[40] : "-";
        toneShare = f.length > 41 && "1".equals(f[41]);
        jpegQuality = f.length > 42 ? Math.max(50, Math.min(100, Integer.parseInt(f[42]))) : 95;
        aeActive = f.length > 44 && "1".equals(f[43]);
        if (f.length > 44) aeAllow = Double.parseDouble(f[44]);
        standby = f.length > 45 && "1".equals(f[45]);
        sharpLevel = f.length > 46 ? Math.max(0, Math.min(MergeCore.SHARP_AMOUNTS.length - 1, Integer.parseInt(f[46])))
                : MergeCore.SHARP_DEFAULT;
        clarityLevel = f.length > 47 ? Math.max(0, Math.min(MergeCore.CLARITY_AMOUNTS.length - 1,
                Integer.parseInt(f[47]))) : MergeCore.CLARITY_DEFAULT;
        if (f.length > 48) astroStopSeq = Integer.parseInt(f[48]);
        nightOut = f.length > 49 ? Integer.parseInt(f[49]) : -1;
        gpuMode = f.length > 50 ? Math.max(0, Math.min(MergeCore.GPU_NAMES.length - 1, Integer.parseInt(f[50]))) : 0;
        remoteMode = f.length > 51 ? Integer.parseInt(f[51]) : 0;
        lens = f.length > 52 ? Integer.parseInt(f[52]) : 0;
        cbr = f.length > 53 && "1".equals(f[53]);
        timelapse = f.length > 54 ? Integer.parseInt(f[54]) : 0;
        sideWarn = f.length > 55 ? f[55] : "-";
        previewW = f.length > 56 ? Integer.parseInt(f[56]) : 1920;
        fillLost = f.length > 57 && "1".equals(f[57]);
        lastStateMs = SystemClock.elapsedRealtime();
        Runnable np = onNewPhoto;
        if (np != null && photoSeq != prevPhotoSeq) np.run();
        prevPhotoSeq = photoSeq;
    }

    /** True if this camera is the phase reference (it must not steer itself). */
    boolean isReference() {
        return id.equals(refId);
    }

    private void sendLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        long seq = 0;
        long lastFlush = SystemClock.elapsedRealtime();
        long lastConversion = 0;
        long lastStatusMs = 0;
        while (netActive) {
            if (SystemClock.elapsedRealtime() - lastConversion >= 200) {
                updateConversion();
                lastConversion = SystemClock.elapsedRealtime();
            }
            long nowMs = SystemClock.elapsedRealtime();
            if (nowMs - Math.max(lastPongMs, lastRebindMs) > NO_REPLY_REBIND_MS) {
                rebind(SyncNet.wifiNetwork(ctx), "no reply for 5 s");
            }
            InetAddress m = master;
            DatagramSocket s = socket;
            if (m != null && s != null) {
                try {
                    seq++;
                    long t1 = SystemClock.elapsedRealtimeNanos();
                    byte[] out = SyncNet.msg(SyncNet.PING, seq, t1);
                    s.send(new DatagramPacket(out, out.length, m, SyncNet.PORT));
                    if (nowMs - lastStatusMs >= STATUS_INTERVAL_MS) {
                        lastStatusMs = nowMs;
                        sendStatus(s, m);
                    }
                } catch (Exception ignored) {
                }
            }
            if (nowMs - lastFlush > 5000) {
                flushLog();
                lastFlush = nowMs;
            }
            try {
                Thread.sleep(nowMs - lastRebindMs < FAST_PING_MS ? FAST_PING_INTERVAL_MS : PING_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void sendStatus(DatagramSocket s, InetAddress m) throws Exception {
        ClockEstimator.Result r = estimator.compute(SystemClock.elapsedRealtimeNanos());
        if (!r.valid) return;
        PhaseAligner a = aligner;
        boolean phaseValid = a != null && a.phaseValid;
        boolean ownValid = a != null && a.ownValid;
        if (streaming && a != null) {
            // "# <t> phase <phaseNs|nan> <aligned> <steps> <lastStepNs> <clockOffsetNs> <clockErrNs>
            //  <driftNsPerS|nan> <rawPhaseNs|nan> <coarseSteps> (phase = filtered once aligned)"
            logEvent(String.format(java.util.Locale.US, "phase %s %d %d %.0f %.0f %.0f %s %s %.0f",
                    phaseValid ? String.format(java.util.Locale.US, "%.0f", a.phaseNs) : "nan",
                    a.aligned ? 1 : 0, a.steps, a.lastStepNs, r.offsetNs, r.offsetErrNs,
                    a.driftValid ? String.format(java.util.Locale.US, "%.0f", a.driftNsPerS) : "nan",
                    Double.isNaN(a.rawPhaseNs) ? "nan" : String.format(java.util.Locale.US, "%.0f", a.rawPhaseNs),
                    (double) a.coarseSteps));   // + unfiltered phase, coarse steps (#5 filter)
        }
        byte[] out = SyncNet.msg(SyncNet.STATUS, id, name.replace('|', '/'),
                Math.round(r.offsetNs), clockErrNs >= 0 ? clockErrNs : Math.round(r.offsetErrNs),
                Math.round(r.scatterNs),
                Math.round(r.minRttNs), Math.round(r.medRttNs), Math.round(r.driftPpm * 1e3),
                Math.round(r.residualNs), r.used, Math.round(r.windowS),
                streaming ? 1 : 0, phaseValid ? 1 : 0, phaseValid ? Math.round(a.phaseNs) : 0,
                ownValid ? 1 : 0, ownValid ? a.ownT0 : 0, ownValid ? a.ownPeriodNs : 0,
                a != null ? a.steps : 0, a != null && a.aligned ? 1 : 0,
                led != null ? led.progressS() : -1, led != null ? led.ledY : -1, led != null ? led.peak : 0,
                recFrames, photoSeqDone, photoErrNs,
                Downloads.side, Math.max(maxImg, photoSeqDone), maxVid,
                aeSeq, Double.isNaN(aeRatio) ? 1.0 : aeRatio, aeLight, aeClip,
                Float.isNaN(rawDark) ? -1f : rawDark, Float.isNaN(rawMedian) ? -1f : rawMedian, SyncNet.join(awb),
                lensInUse, focusSeq, focusTapD, focusTapSharp, vidMissing);
        s.send(new DatagramPacket(out, out.length, m, SyncNet.PORT));
    }

    // ------------------------------------------------------------------ raw log

    private void openLog() {
        try {
            Downloads.Out out = Downloads.open(ctx, "Info/" + Downloads.fileName("clock", "csv"), "text/csv");
            synchronized (logLock) {
                log = new BufferedWriter(new OutputStreamWriter(out.stream, StandardCharsets.US_ASCII), 1 << 16);
                log.write("# " + App.TITLE + " clock log, " + name + ", all times elapsedRealtime ns\n");
                log.write("# t1 = camera send, t2 = master receive, t3 = master send, t4 = camera receive\n");
                log.write("t1,t2,t3,t4\n");
            }
            logPath = out.path;
        } catch (Exception e) {
            logPath = "(log failed: " + e.getMessage() + ")";
        }
    }

    private void logSample(long t1, long t2, long t3, long t4) {
        synchronized (logLock) {
            if (log == null) return;
            try {
                log.write(t1 + "," + t2 + "," + t3 + "," + t4 + "\n");
            } catch (Exception ignored) {
            }
        }
    }

    private void logEvent(String text) {
        synchronized (logLock) {
            if (log == null) return;
            try {
                log.write("# " + SystemClock.elapsedRealtimeNanos() + " " + text + "\n");
            } catch (Exception ignored) {
            }
        }
    }

    private void flushLog() {
        synchronized (logLock) {
            try {
                if (log != null) log.flush();
            } catch (Exception ignored) {
            }
        }
    }
}
