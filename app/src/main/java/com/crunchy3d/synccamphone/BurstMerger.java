package com.crunchy3d.synccamphone;

import android.graphics.Bitmap;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;

import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Android adapter of the HDR burst merge + development: camera metadata -> MergeCore.Meta, camera
 * Images -> MergeCore.Frames. The algorithm itself is MergeCore (the same source file is run on
 * the PC by tools/bench on the test set, so phone and bench give identical results).
 */
final class BurstMerger {
    static {
        MergeCore.gpuFactory = GpuRobust.FACTORY;    // robustness weights on the GPU (item 21)
    }

    static final boolean USE_FACTORY_CAL = false;
    final MergeCore core;
    final int W, H;
    private final int[] color = new int[4];

    BurstMerger(CameraCharacteristics chars, TotalCaptureResult ref, int width, int height) {
        W = width;
        H = height;
        MergeCore.Meta m = new MergeCore.Meta();
        m.W = width;
        m.H = height;
        Integer wl = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        m.white = wl == null ? 1023 : wl;
        Integer arr = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        int a = arr == null ? 0 : arr;
        int[][] pat = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};   // RGGB GRBG GBRG BGGR
        System.arraycopy(pat[Math.min(3, Math.max(0, a))], 0, m.color, 0, 4);
        System.arraycopy(m.color, 0, color, 0, 4);
        float[] bl = ref.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        android.hardware.camera2.params.BlackLevelPattern bp =
                chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        for (int i = 0; i < 4; i++) {
            m.black[i] = bl != null ? bl[i] : bp != null ? bp.getOffsetForIndex(i % 2, i / 2) : 64;
        }
        android.hardware.camera2.params.RggbChannelVector g = ref.get(CaptureResult.COLOR_CORRECTION_GAINS);
        if (g != null) {
            m.wb[0] = g.getRed();
            m.wb[1] = g.getGreenEven();
            m.wb[2] = g.getGreenOdd();
            m.wb[3] = g.getBlue();
        }
        // factory calibration of this unit (diagonal, mean of both illuminants) - NOT used: on a
        // white card (2026-11-16) it over-corrected two Pixel 7a (predicted +1.7 % red / -3.1 % blue,
        // measured +0.8 % / -1.8 %); MergeCore keeps the hook for a measured per-phone colour match
        if (USE_FACTORY_CAL) {
        android.hardware.camera2.params.ColorSpaceTransform c1 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1);
        android.hardware.camera2.params.ColorSpaceTransform c2 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2);
        for (int i = 0; i < 3; i++) {
            float a1 = c1 != null ? c1.getElement(i, i).floatValue() : 1f;
            float a2 = c2 != null ? c2.getElement(i, i).floatValue() : a1;
            if (a1 > 0.5f && a1 < 2f && a2 > 0.5f && a2 < 2f) m.cal[i] = (a1 + a2) / 2;
        }
        }
        android.hardware.camera2.params.ColorSpaceTransform t = ref.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        float[] def = {1.6758f, -0.5117f, -0.1641f, -0.3164f, 1.4883f, -0.168f, -0.0586f, -0.9453f, 2.0078f};
        for (int i = 0; i < 9; i++) m.ccm[i] = t == null ? def[i] : t.getElement(i % 3, i / 3).floatValue();
        // a much too weak result matrix (Xreal Beam Pro: about half the saturation): the matrix from
        // the sensor's calibration instead (Pixels: their own matrix is kept, it is as strong)
        float[] cal = ColorCal.calibrated(chars, m.wb);
        if (cal != null && ColorCal.strength(m.ccm) < 0.5 * ColorCal.strength(cal)) System.arraycopy(cal, 0, m.ccm, 0, 9);
        android.hardware.camera2.params.LensShadingMap sh = ref.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if (sh != null) {
            int rows = sh.getRowCount(), cols = sh.getColumnCount();
            m.shading = new float[rows][cols * 4];
            for (int j = 0; j < rows; j++)
                for (int i = 0; i < cols; i++)
                    for (int c = 0; c < 4; c++) m.shading[j][i * 4 + c] = sh.getGainFactor(c, i, j);
        }
        Long et = ref.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = ref.get(CaptureResult.SENSOR_SENSITIVITY);
        if (et != null) m.exposureNs = et;
        if (iso != null) m.iso = iso;
        core = new MergeCore(m);
    }

    /**
     * The camera's noise model of each frame (SENSOR_NOISE_PROFILE, the green channels' mean S and O:
     * variance = S x + O, x = signal / sensor range) -> core.noiseS / noiseO (noise-optimal weights
     * of the longer frames). Frames without a profile: the merge falls back to the exposure ratio.
     */
    // at least ~1 DN of read noise (normalised (1 / 1023)^2): the Xreal Beam Pro reports O = 0, which
    // gave NaN weights - black blocks in HDR photos (BeamCam 2026-11-19, Pixel app 2026-11-20)
    static final double O_MIN = 1.0 / (1023.0 * 1023.0);

    void setNoise(java.util.List<TotalCaptureResult> results) {
        int n = results.size();
        double[] s = new double[n], o = new double[n];
        for (int k = 0; k < n; k++) {
            TotalCaptureResult r = results.get(k);
            android.util.Pair<Double, Double>[] p = r == null ? null : r.get(CaptureResult.SENSOR_NOISE_PROFILE);
            if (p == null || p.length < 4) return;
            double ss = 0, oo = 0;
            int c = 0;
            for (int i = 0; i < 4; i++) {
                if (color[i] != 1) continue;
                ss += p[i].first;
                oo += p[i].second;
                c++;
            }
            if (c == 0) return;
            s[k] = Math.max(ss / c, 1e-9);
            o[k] = Math.max(oo / c, O_MIN);
        }
        core.noiseS = s;
        core.noiseO = o;
    }

    String log() {
        return core.log;
    }

    /**
     * Merges the frames (index ref = reference); every Image is closed (and set to null) as soon
     * as it has been used.
     */
    float[] merge(PhotoBurst.Frm[] frames, int refIdx, float[][] wsumOut, float[] expRatio) {
        return core.merge(source(frames, true), frames.length, refIdx, wsumOut, expRatio);
    }

    /**
     * The frames as a merge source (close: release closes each frame when it has been used; the
     * merge during the capture does not close them - the burst does, at the end).
     */
    MergeCore.Frames source(PhotoBurst.Frm[] frames, boolean close) {
        return new MergeCore.Frames() {
            @Override public void read(int k, short[] buf) {
                if (frames[k].img == null) {                  // copied frame (rows packed)
                    ShortBuffer cb = frames[k].copy.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    cb.get(buf, 0, W * H);
                    return;
                }
                Image.Plane p = frames[k].img.getPlanes()[0];
                ShortBuffer sb = p.getBuffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                int stride = p.getRowStride() / 2;
                for (int y = 0; y < H; y++) {             // bulk row copies (fast)
                    sb.position(y * stride);
                    sb.get(buf, y * W, W);
                }
            }

            @Override public void release(int k) {
                if (!close) return;
                if (frames[k] != null) frames[k].close();
                frames[k] = null;
            }

            // the GPU uploads straight from the camera buffer / the cache file (no copy in the heap)
            @Override public java.nio.ByteBuffer direct(int k) {
                if (frames[k].img == null) return frames[k].copy.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                return frames[k].img.getPlanes()[0].getBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            }

            @Override public int directStride(int k) {
                return frames[k].img == null ? W * 2 : frames[k].img.getPlanes()[0].getRowStride();
            }
        };
    }

    /** Noise model of one frame (greens' mean S, O), or null. */
    private double[] noiseOf(TotalCaptureResult r) {
        android.util.Pair<Double, Double>[] p = r == null ? null : r.get(CaptureResult.SENSOR_NOISE_PROFILE);
        if (p == null || p.length < 4) return null;
        double ss = 0, oo = 0;
        int c = 0;
        for (int i = 0; i < 4; i++) {
            if (color[i] != 1) continue;
            ss += p[i].first;
            oo += p[i].second;
            c++;
        }
        return c == 0 ? null : new double[]{Math.max(ss / c, 1e-9), Math.max(oo / c, O_MIN)};
    }

    /** Merge during the capture: n frames, all with the reference's noise until their own is known. */
    void initNoise(int n, TotalCaptureResult ref) {
        double[] so = noiseOf(ref);
        if (so == null) return;
        core.noiseS = new double[n];
        core.noiseO = new double[n];
        java.util.Arrays.fill(core.noiseS, so[0]);
        java.util.Arrays.fill(core.noiseO, so[1]);
    }

    void setNoise(int k, TotalCaptureResult r) {
        double[] so = noiseOf(r);
        if (so == null || core.noiseS == null) return;
        core.noiseS[k] = so[0];
        core.noiseO[k] = so[1];
    }

    /** Merged Bayer -> 16-bit little-endian raw buffer for DngCreator. */
    java.nio.ByteBuffer toDngPixels(float[] m) {
        short[] s = core.toRaw16(m);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.asShortBuffer().put(s);
        bb.rewind();
        return bb;
    }

    int[] develop(float[] m, float[] wsum, int cx, int cy, int cw, int ch) {
        return core.develop(m, wsum, cx, cy, cw, ch);
    }

    /** ARGB pixels -> Bitmap. */
    static Bitmap toBitmap(int[] px, int w, int h) {
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }
}
