package com.crunchy3d.synccamphone;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.RggbChannelVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * White balance by colour temperature (K) and tint, calibrated from the camera's own presets.
 *
 * The Pixel 7a's stored sensor calibration is only a placeholder (its D65 colour matrix is the
 * textbook XYZ->sRGB matrix), so K cannot be computed from it. Instead, "Calibrate WB" lets the
 * camera apply its presets one after another and records the gains and colour matrix it uses for
 * each; every preset gets its usual colour temperature. A K value is interpolated between the
 * presets in mireds (1e6 / K), linearly extrapolated a little beyond them. Tint scales the red
 * and blue gains against green (+ = magenta).
 */
final class ColorTemp {

    /** Presets used for the calibration and their usual colour temperatures. */
    static final int[] PRESETS = {CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_SHADE};
    static final double[] PRESET_K = {2850, 4200, 5500, 6500, 7500};

    /** Gains (R, Geven, Godd, B) and transform (18 ints = 9 rationals). */
    static final class Result {
        final float[] gains;
        final int[] xform;

        Result(float[] gains, int[] xform) {
            this.gains = gains;
            this.xform = xform;
        }
    }

    final double[] mired;                 // ascending (= descending K)
    final float[][] gains;
    final int[][] xform;

    private ColorTemp(double[] mired, float[][] gains, int[][] xform) {
        this.mired = mired;
        this.gains = gains;
        this.xform = xform;
    }

    /**
     * Builds the table from the measured presets; drops points that break the expected order
     * (higher K -> higher R/B gain ratio). Returns null with fewer than 2 points.
     */
    static ColorTemp fromPresets(double[] kelvin, float[][] g, int[][] x) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < kelvin.length; i++) if (g[i] != null && x[i] != null) idx.add(i);
        idx.sort((a, b) -> Double.compare(1e6 / kelvin[a], 1e6 / kelvin[b]));
        List<Integer> keep = new ArrayList<>();
        for (int i : idx) {
            // ascending mired = falling K: the R/B gain ratio must fall too
            if (!keep.isEmpty()) {
                int p = keep.get(keep.size() - 1);
                if (g[i][0] / g[i][3] >= g[p][0] / g[p][3]) continue;
            }
            keep.add(i);
        }
        if (keep.size() < 2) return null;
        double[] m = new double[keep.size()];
        float[][] gg = new float[keep.size()][];
        int[][] xx = new int[keep.size()][];
        for (int j = 0; j < keep.size(); j++) {
            int i = keep.get(j);
            m[j] = 1e6 / kelvin[i];
            gg[j] = g[i].clone();
            xx[j] = x[i].clone();
        }
        return new ColorTemp(m, gg, xx);
    }

    Result compute(double kelvin, double tint) {
        double m = 1e6 / kelvin;
        // segment (extrapolate at most half a segment beyond the ends)
        int j = 0;
        while (j < mired.length - 2 && m > mired[j + 1]) j++;
        double span = mired[j + 1] - mired[j];
        double w = (m - mired[j]) / span;
        w = Math.max(-0.5, Math.min(1.5, w));
        float[] g = new float[4];
        for (int c = 0; c < 4; c++) g[c] = (float) (gains[j][c] + w * (gains[j + 1][c] - gains[j][c]));
        double f = 1 + tint / 500.0;                      // + = magenta: less green relative to R, B
        g[0] *= f;
        g[3] *= f;
        int[] x = new int[18];
        for (int e = 0; e < 9; e++) {
            double a = rational(xform[j], e), b = rational(xform[j + 1], e);
            x[2 * e] = (int) Math.round((a + w * (b - a)) * 10000);
            x[2 * e + 1] = 10000;
        }
        return new Result(g, x);
    }

    /** Colour temperature whose (tint 0) gains have the same R/B ratio; NaN if outside. */
    double estimate(RggbChannelVector g) {
        if (g == null) return Double.NaN;
        double target = g.getRed() / g.getBlue();
        double lo = 1e6 / 12000, hi = 1e6 / 1800;          // mired range
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            Result r = compute(1e6 / mid, 0);
            // higher mired (lower K) -> lower R/B ratio
            if (r.gains[0] / r.gains[3] > target) lo = mid;
            else hi = mid;
        }
        return 1e6 / ((lo + hi) / 2);
    }

    private static double rational(int[] x, int e) {
        return x[2 * e] / (double) x[2 * e + 1];
    }

    // ------------------------------------------------------------------ storage

    String serialize() {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < mired.length; j++) {
            if (j > 0) sb.append('|');
            sb.append(String.format(Locale.US, "%.4f", mired[j])).append(':').append(SyncNet.join(gains[j]))
                    .append(':').append(SyncNet.join(xform[j]));
        }
        return sb.toString();
    }

    static ColorTemp parse(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] rows = s.split("\\|");
            double[] m = new double[rows.length];
            float[][] g = new float[rows.length][];
            int[][] x = new int[rows.length][];
            for (int j = 0; j < rows.length; j++) {
                String[] p = rows[j].split(":");
                m[j] = Double.parseDouble(p[0]);
                g[j] = SyncNet.floats(p[1]);
                x[j] = SyncNet.ints(p[2]);
            }
            return m.length >= 2 ? new ColorTemp(m, g, x) : null;
        } catch (Exception e) {
            return null;
        }
    }

    String describe() {
        StringBuilder sb = new StringBuilder();
        for (int j = mired.length - 1; j >= 0; j--) {
            sb.append(String.format(Locale.US, "%s%.0fK R%.2f B%.2f", sb.length() > 0 ? ", " : "",
                    1e6 / mired[j], gains[j][0], gains[j][3]));
        }
        return sb.toString();
    }
}
