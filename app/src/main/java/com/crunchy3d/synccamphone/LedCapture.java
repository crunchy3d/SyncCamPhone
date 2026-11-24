package com.crunchy3d.synccamphone;

import android.content.Context;
import android.media.Image;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Optical sync test (2c): finds the brightest spot (the LED) in every small YUV frame and, while
 * a capture runs, writes per-frame data to Download/CamSyncProbe/led_*.csv:
 *
 *   I,tsLocal,tsMaster,x,y,peak,sum,bg     one per image (LED position, brightness above background)
 *   R,tsLocal,exposureNs,skewNs,durationNs one per capture result (rolling-shutter skew etc.)
 *
 * tsLocal = SENSOR_TIMESTAMP (start of exposure of the first row), tsMaster = the same in
 * master time. The analysis joins I and R lines by tsLocal.
 */
final class LedCapture implements SyncCamera.Analyzer {

    interface Clock {
        long toMaster(long localNs);   // Long.MIN_VALUE if unknown
    }

    private static final int STEP = 2;      // search grid
    private static final int HALF = 12;     // half size of the measuring window (pixels)

    private final Context ctx;
    private final String name;
    private final Clock clock;
    private byte[] y = new byte[0];

    // live values for the UI and the status message
    volatile int ledX = -1, ledY = -1, peak;
    volatile double sum;
    volatile int width, height;

    // capture
    private final Object lock = new Object();
    private volatile BufferedWriter out;
    private long endNs;                     // local elapsedRealtime ns
    private volatile long durationS;
    private volatile long startNs;
    volatile int frames;
    volatile String path = "";
    volatile String result = "";

    LedCapture(Context ctx, String name, Clock clock) {
        this.ctx = ctx.getApplicationContext();
        this.name = name;
        this.clock = clock;
    }

    boolean isCapturing() {
        synchronized (lock) {
            return out != null;
        }
    }

    /** Seconds done of the running capture, or -1. */
    int progressS() {
        synchronized (lock) {
            if (out == null) return -1;
        }
        return (int) ((android.os.SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000_000L);
    }

    long durationS() {
        return durationS;
    }

    void start(int seconds, String info) {
        synchronized (lock) {
            closeLocked("restarted");
            try {
                Downloads.Out o = Downloads.open(ctx, "Info/" + Downloads.fileName("led", "csv"), "text/csv");
                out = new BufferedWriter(new OutputStreamWriter(o.stream, StandardCharsets.US_ASCII), 1 << 16);
                out.write("# " + App.TITLE + " LED capture, " + name + ", " + info + "\n");
                out.write("# image " + width + "x" + height + ", window " + (2 * HALF + 1) + " px\n");
                out.write("# I,tsLocal,tsMaster,x,y,peak,sum,bg / R,tsLocal,exposureNs,skewNs,durationNs\n");
                path = o.path;
                result = "";
                frames = 0;
                durationS = seconds;
                startNs = android.os.SystemClock.elapsedRealtimeNanos();
                endNs = startNs + seconds * 1_000_000_000L;
            } catch (Exception e) {
                out = null;
                result = "capture failed: " + e.getMessage();
            }
        }
    }

    void stop() {
        synchronized (lock) {
            closeLocked("stopped");
        }
    }

    private void closeLocked(String why) {
        if (out == null) return;
        try {
            out.write("# end (" + why + "), " + frames + " frames\n");
            out.close();
        } catch (Exception ignored) {
        }
        out = null;
        result = why + ": " + frames + " frames -> " + path;
    }

    private void write(String line) {
        synchronized (lock) {
            if (out == null) return;
            try {
                out.write(line);
            } catch (Exception ignored) {
            }
            if (android.os.SystemClock.elapsedRealtimeNanos() > endNs) closeLocked("done");
        }
    }

    // ------------------------------------------------------------------ SyncCamera.Analyzer

    @Override
    public void onResult(long tsLocal, long exposureNs, long skewNs, long durationNs) {
        if (out == null) return;
        write("R," + tsLocal + "," + exposureNs + "," + skewNs + "," + durationNs + "\n");
    }

    /** Called on the analyzer thread; the image is closed by the caller. */
    @Override
    public void onImage(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int stride = p.getRowStride();
        int n = buf.remaining();
        if (y.length < n) y = new byte[n];
        buf.get(y, 0, n);
        width = w;
        height = h;

        // brightest point on a coarse grid, then refine in its neighbourhood
        int best = -1, bx = 0, by = 0;
        for (int r = 0; r < h; r += STEP) {
            int o = r * stride;
            for (int c = 0; c < w; c += STEP) {
                int v = y[o + c] & 0xFF;
                if (v > best) {
                    best = v;
                    bx = c;
                    by = r;
                }
            }
        }
        int x0 = Math.max(0, bx - HALF), x1 = Math.min(w - 1, bx + HALF);
        int y0 = Math.max(0, by - HALF), y1 = Math.min(h - 1, by + HALF);
        // background = mean of the window border, signal = sum above background
        long border = 0;
        int nb = 0;
        for (int c = x0; c <= x1; c++) {
            border += (y[y0 * stride + c] & 0xFF) + (y[y1 * stride + c] & 0xFF);
            nb += 2;
        }
        for (int r = y0 + 1; r < y1; r++) {
            border += (y[r * stride + x0] & 0xFF) + (y[r * stride + x1] & 0xFF);
            nb += 2;
        }
        double bg = nb > 0 ? (double) border / nb : 0;
        double s = 0, sy = 0, sx = 0;
        int pk = 0;
        for (int r = y0; r <= y1; r++) {
            int o = r * stride;
            for (int c = x0; c <= x1; c++) {
                int v = y[o + c] & 0xFF;
                if (v > pk) pk = v;
                double d = v - bg;
                if (d > 0) {
                    s += d;
                    sx += d * c;
                    sy += d * r;
                }
            }
        }
        double cx = s > 0 ? sx / s : bx, cy = s > 0 ? sy / s : by;
        ledX = (int) Math.round(cx);
        ledY = (int) Math.round(cy);
        peak = pk;
        sum = s;

        if (out != null) {
            long ts = img.getTimestamp();
            long tm = clock.toMaster(ts);
            frames++;
            write(String.format(Locale.US, "I,%d,%s,%.2f,%.2f,%d,%.0f,%.1f\n", ts,
                    tm == Long.MIN_VALUE ? "nan" : Long.toString(tm), cx, cy, pk, s, bg));
        }
    }
}
