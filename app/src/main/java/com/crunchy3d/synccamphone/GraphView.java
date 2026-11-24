package com.crunchy3d.synccamphone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Minimal scrolling line graph: one series per camera, last 60 s, symmetric auto scale. */
final class GraphView extends View {
    private static final long WINDOW_MS = 60_000;
    private static final int[] COLORS = {0xFF4FC3F7, 0xFFFFB74D, 0xFFAED581, 0xFFF06292, 0xFFBA68C8};
    private static final double[] SCALES = {20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000};

    private final Map<String, ArrayDeque<double[]>> series = new LinkedHashMap<>();
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String title = "";

    GraphView(Context ctx) {
        super(ctx);
        float d = getResources().getDisplayMetrics().density;
        axis.setColor(0xFF808080);
        axis.setStrokeWidth(d);
        text.setColor(0xFFB0B0B0);
        text.setTextSize(11 * d);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * d);
    }

    void setTitle(String t) {
        title = t;
    }

    void clear() {
        series.clear();
        invalidate();
    }

    void add(String key, long timeMs, double value) {
        ArrayDeque<double[]> q = series.get(key);
        if (q == null) {
            q = new ArrayDeque<>();
            series.put(key, q);
        }
        q.addLast(new double[]{timeMs, value});
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.elapsedRealtime();
        double maxAbs = 0;
        for (ArrayDeque<double[]> q : series.values()) {
            while (!q.isEmpty() && q.peekFirst()[0] < now - WINDOW_MS) q.pollFirst();
            for (double[] p : q) maxAbs = Math.max(maxAbs, Math.abs(p[1]));
        }
        double scale = SCALES[SCALES.length - 1];
        for (double s : SCALES) {
            if (maxAbs <= s) {
                scale = s;
                break;
            }
        }

        float d = getResources().getDisplayMetrics().density;
        float left = 48 * d, top = 18 * d, right = getWidth() - 4 * d, bottom = getHeight() - 4 * d;
        float mid = (top + bottom) / 2;
        c.drawText(title, left, 12 * d, text);
        c.drawLine(left, mid, right, mid, axis);
        c.drawLine(left, top, left, bottom, axis);
        c.drawText(String.format(Locale.US, "%+.0f", scale), 2 * d, top + 10 * d, text);
        c.drawText("0", 2 * d, mid + 4 * d, text);
        c.drawText(String.format(Locale.US, "%+.0f", -scale), 2 * d, bottom, text);

        int i = 0;
        float legendX = left + 6 * d;
        for (Map.Entry<String, ArrayDeque<double[]>> e : series.entrySet()) {
            int color = COLORS[i++ % COLORS.length];
            line.setColor(color);
            Path path = new Path();
            boolean first = true;
            Iterator<double[]> it = e.getValue().iterator();
            while (it.hasNext()) {
                double[] p = it.next();
                float x = (float) (right - (now - p[0]) / (double) WINDOW_MS * (right - left));
                double v = Math.max(-scale, Math.min(scale, p[1]));
                float y = (float) (mid - v / scale * (mid - top));
                if (first) path.moveTo(x, y);
                else path.lineTo(x, y);
                first = false;
            }
            c.drawPath(path, line);
            text.setColor(color);
            c.drawText(e.getKey(), legendX, top + 10 * d, text);
            legendX += text.measureText(e.getKey()) + 12 * d;
            text.setColor(0xFFB0B0B0);
        }
    }
}
