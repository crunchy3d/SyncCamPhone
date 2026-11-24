package com.crunchy3d.synccamphone;

import android.hardware.camera2.CameraCharacteristics;

/**
 * Colour matrix from the sensor's own calibration (as DNG converters do; from BeamCam, 2026-11-19):
 * the colour matrices give the shot's colour temperature (its neutral = 1 / white balance), the
 * forward matrices for that temperature map camera RGB to XYZ D50, then XYZ D50 -> linear sRGB.
 * Used when a phone's result colour matrix is much too weak (Xreal Beam Pro: about half the
 * saturation - dull HDR photos); the Pixels' own matrix is kept.
 */
final class ColorCal {
    private ColorCal() {}

    /** Colour strength of a camera RGB -> sRGB matrix: its diagonal beyond identity. */
    static double strength(float[] m) {
        return m[0] + m[4] + m[8] - 3;
    }

    /** White-balanced camera RGB -> linear sRGB (9, row-major), or null when not calibrated. */
    static float[] calibrated(CameraCharacteristics ch, float[] wb) {
        double[] cm1 = mat(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
        double[] cm2 = mat(ch.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
        double[] fm1 = mat(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1));
        double[] fm2 = mat(ch.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2));
        Integer il1 = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        Byte il2b = ch.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        double t1 = cctOf(il1), t2 = cctOf(il2b == null ? null : (int) il2b);
        if (fm1 == null || cm1 == null || t1 <= 0 || wb[0] <= 0 || wb[3] <= 0) return null;
        if (fm2 == null || cm2 == null || t2 <= 0) {
            fm2 = fm1;
            cm2 = cm1;
            t2 = t1;
        }
        double g = (wb[1] + wb[2]) / 2;
        double[] neutral = {g / wb[0], 1, g / wb[3]};     // camera RGB of white (1 / gains, green 1)
        double cct = 5000, w = 0;
        for (int it = 0; it < 8; it++) {
            w = t1 == t2 ? 0 : Math.max(0, Math.min(1, (1 / cct - 1 / t1) / (1 / t2 - 1 / t1)));
            double[] ci = inv(lerp(cm1, cm2, w));       // camera -> XYZ
            if (ci == null) return null;
            double X = ci[0] * neutral[0] + ci[1] * neutral[1] + ci[2] * neutral[2];
            double Y = ci[3] * neutral[0] + ci[4] * neutral[1] + ci[5] * neutral[2];
            double Z = ci[6] * neutral[0] + ci[7] * neutral[1] + ci[8] * neutral[2];
            double s = X + Y + Z;
            if (s <= 0) return null;
            double x = X / s, y = Y / s, n = (x - 0.3320) / (0.1858 - y);      // McCamy
            cct = Math.max(2000, Math.min(12000, 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33));
        }
        double[] fm = lerp(fm1, fm2, w);
        double[] xyz2srgb = {3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540,
                0.0719453, -0.2289914, 1.4052427};      // XYZ D50 -> linear sRGB (D65, Bradford)
        double[] c = mul(xyz2srgb, fm);
        float[] out = new float[9];
        for (int i = 0; i < 9; i++) out[i] = (float) c[i];
        return out;
    }

    private static double[] mat(android.hardware.camera2.params.ColorSpaceTransform t) {
        if (t == null) return null;
        double[] a = new double[9];
        for (int i = 0; i < 9; i++) a[i] = t.getElement(i % 3, i / 3).doubleValue();
        return a;
    }

    private static double[] mul(double[] a, double[] b) {
        double[] c = new double[9];
        for (int r = 0; r < 3; r++)
            for (int k = 0; k < 3; k++) c[r * 3 + k] = a[r * 3] * b[k] + a[r * 3 + 1] * b[3 + k] + a[r * 3 + 2] * b[6 + k];
        return c;
    }

    private static double[] inv(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12) return null;
        return new double[]{A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
    }

    private static double[] lerp(double[] a, double[] b, double w) {
        double[] c = new double[9];
        for (int i = 0; i < 9; i++) c[i] = a[i] * (1 - w) + b[i] * w;
        return c;
    }

    /** Correlated colour temperature of a DNG / EXIF illuminant code. */
    private static double cctOf(Integer code) {
        if (code == null) return 0;
        switch (code) {
            case 17: case 3: return 2856;      // standard A, tungsten
            case 24: return 3200;              // ISO studio tungsten
            case 15: return 3450;              // white fluorescent
            case 2: case 14: return 4150;      // fluorescent, cool white
            case 18: return 4874;              // B
            case 13: case 23: return 5003;     // day white, D50
            case 1: case 9: case 20: return 5503;   // daylight, fine weather, D55
            case 10: case 12: case 21: return 6504; // cloudy, daylight fluorescent, D65
            case 19: return 6774;              // C
            case 11: case 22: return 7504;     // shade, D75
            default: return 0;
        }
    }
}
