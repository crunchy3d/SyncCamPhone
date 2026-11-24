package com.crunchy3d.synccamphone;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Item 20 (2026-11-19, experiment): REMOTE TRIGGER of another camera app (Google Camera). Our cameras are
 * off, only the Wi-Fi clock sync runs (RemoteService keeps the app alive in the background); a tap on the
 * floating button (master) gives a common moment T; at T every phone's TapService clicks the other
 * app's shutter. The tap times are logged (Android/data/com.crunchy3d.synccamphone/files/remote_taps.txt).
 */
final class RemoteTrigger {
    static final long LEAD_NS = 400_000_000L;         // T = tap on the button + 0.4 s (Wi-Fi delivery)
    static volatile Runnable onFloatTap;              // master: what the floating button does
    static volatile Context appCtx;

    private RemoteTrigger() {
    }

    /** At the local time targetNs (elapsedRealtimeNanos): click the shutter; the timing is logged. */
    static void tapAt(long targetNs, String tag) {
        new Thread(() -> {
            try {
                // the shutter is looked up ~150 ms before (the search takes a few ms), clicked at T
                long pre = (targetNs - 150_000_000L - SystemClock.elapsedRealtimeNanos()) / 1_000_000L;
                if (pre > 0) Thread.sleep(pre);
                TapService.prepare();
                long wait = (targetNs - 3_000_000L - SystemClock.elapsedRealtimeNanos()) / 1_000_000L;
                if (wait > 0) Thread.sleep(wait);
                while (SystemClock.elapsedRealtimeNanos() < targetNs) Thread.onSpinWait();
                long t0 = SystemClock.elapsedRealtimeNanos();
                String how = TapService.tap();
                long t1 = SystemClock.elapsedRealtimeNanos();
                log(String.format(Locale.US, "%s: tap %+.2f ms after T (call %.1f ms) - %s", tag,
                        (t0 - targetNs) / 1e6, (t1 - t0) / 1e6, how));
            } catch (Throwable e) {
                log(tag + ": tap failed: " + e);
            }
        }, "remote-tap").start();
    }

    static synchronized void log(String s) {
        android.util.Log.i("CamSyncRemote", s);
        Context c = appCtx;
        if (c == null) return;
        try {
            File f = new File(c.getExternalFilesDir(null), "remote_taps.txt");
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + s + "\n");
            }
        } catch (Exception ignored) {
        }
    }
}
