package com.crunchy3d.synccamphone;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * HDR burst merge + development without Android classes: the app (BurstMerger, an adapter for
 * Camera2 images and metadata) and the PC bench (tools/bench) run exactly this code.
 *
 * Merge (HDR+-like): reference frame + others; per frame: half-resolution green image, global
 * shift (1/8 res search), 64 px tiles (+/-3 px), difference to the reference -> noise variance
 * measured per brightness level -> weight 1 up to ROBUST * variance, 0 from twice that, 3x3
 * erosion (no ghosts) -> weighted sum per 2x2 Bayer quad. Long frames (bracketing) are scaled to
 * the reference exposure, weight x ratio, near-clipped pixels fade out. Then hot pixels.
 *
 * Develop (standard methods, see develop()):
 *  1. black / white level, white balance and colour matrix of the camera (capture result: the
 *     camera's own auto white balance), lens shading map;
 *  2. tone mapping by exposure fusion (Mertens et al. 2007, as in Google's HDR+ paper, Hasinoff
 *     et al. 2016): the merged image is rendered at several synthetic exposures (2 EV apart, from
 *     "highlights kept" to "median at mid-grey"), each pixel weighted by how well exposed it is,
 *     blended with Laplacian pyramids (no halos); done on the half-resolution luminance, the
 *     resulting gain is applied to the full-resolution colour image;
 *  3. sRGB with a mild S-curve, colour noise reduction, motion-area median.
 */
final class MergeCore {
    static final float ROBUST = 3f;
    static final float LOW_SIGNAL = 10f;          // raw units: below, the reference cannot show motion
    static final float ALIGN_MIN = 20f;           // raw units: darker reference tiles take the global shift
    static final float ALIGN_MIN_FIRST = 3f;      // the same for the first longer frame (+ the 3 % rule)
    // near-clip colour: from this raw level (fraction of white) towards neutral white (green
    // compresses slightly before it clips: magenta fringes at a bulb's edge with 0.80)
    static final float CLIP_DESAT = 0.65f;
    static final float DEFRINGE_FROM = 0.35f;     // clip map level (blurred): defringe from here
    // longer frames near their clip level (raw max of the quad, fraction of white): usable fully
    // below CLIP_USE_LO, not from CLIP_USE_HI (the sensor is slightly non-linear just below clipping)
    static final float CLIP_USE_LO = 0.70f, CLIP_USE_HI = 0.85f;
    static final int CLIP_GROW = 1;               // quads (= 2 px) excluded around clipped parts
    static final float CLIP_FEATHER = 1.5f;       // quads: soft transition
    static final int TILE = 64, SEARCH = 3;
    // motion test: tolerated misalignment next to edges (quarter-res pixels = 4 sensor px x this):
    // the paper's lit edge / shelf edge were rejected as motion -> noisy blocks
    static final float EDGE_TOL = 1.5f;

    /** Camera data of the burst (from the reference frame's capture result). */
    static final class Meta {
        int W, H, white = 1023;
        final float[] black = {64, 64, 64, 64};   // per 2x2 position (row-major)
        final int[] color = {0, 1, 1, 2};          // 0 R, 1 G, 2 B per 2x2 position (row-major)
        final float[] wb = {2, 1, 1, 2};           // white balance gains r, g_even, g_odd, b
        final float[] ccm = {1, 0, 0, 0, 1, 0, 0, 0, 1};   // white-balanced camera RGB -> linear sRGB, rows
        float[][] shading;                         // lens shading: rows x (cols*4: R Geven Godd B), or null
        long exposureNs = 10_000_000L;             // of the reference frame: the scene's light level
        // this unit's factory colour calibration (diagonal of SENSOR_CALIBRATION_TRANSFORM /
        // DNG CameraCalibration: reference camera -> this camera), R, G, B
        final float[] cal = {1, 1, 1};
        int iso = 100;
    }

    /**
     * Global rendering of a burst, shared by all phones of a stereo set: the master publishes its
     * own (white balance + colour matrix of its reference frame, fusion exposures, brightness), the
     * cameras develop with exactly these numbers, so both images look the same (only the local
     * tone mapping follows each image). Text form: "r;ge;go;b|ccm(9 ;)|g0|step|ne|bright".
     */
    static final class Tone {
        final float[] wb = new float[4];
        final float[] ccm = new float[9];
        float g0 = 1, step = 1, bright = 1;
        int ne = 1;

        String encode() {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 4; i++) s.append(i > 0 ? ";" : "").append(wb[i]);
            s.append('|');
            for (int i = 0; i < 9; i++) s.append(i > 0 ? ";" : "").append(ccm[i]);
            s.append('|').append(g0).append('|').append(step).append('|').append(ne).append('|').append(bright);
            return s.toString();
        }

        static Tone decode(String s) {
            try {
                String[] f = s.split("\\|");
                Tone t = new Tone();
                String[] w = f[0].split(";"), c = f[1].split(";");
                for (int i = 0; i < 4; i++) t.wb[i] = Float.parseFloat(w[i]);
                for (int i = 0; i < 9; i++) t.ccm[i] = Float.parseFloat(c[i]);
                t.g0 = Float.parseFloat(f[2]);
                t.step = Float.parseFloat(f[3]);
                t.ne = Integer.parseInt(f[4]);
                t.bright = Float.parseFloat(f[5]);
                return t;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** Master: publish(); camera: await() (null = develop alone). */
    interface ToneLink {
        void publish(Tone t);

        Tone await();
    }

    ToneLink toneLink;
    private Tone shared;                          // the master's rendering (camera), or null
    private final float[] wbOwn;
    private final float[] cal;                    // R, G, B factory calibration of this unit

    /** The burst's raw frames (row-major Bayer, W*H unsigned 16 bit values). */
    interface Frames {
        void read(int k, short[] buf);

        /**
         * The raw data of frame k as it is (row-major Bayer, 2 bytes per pixel little endian, rows
         * directStride bytes apart), or null: the GPU uploads it without a copy in the Java heap.
         */
        default java.nio.ByteBuffer direct(int k) {
            return null;
        }

        default int directStride(int k) {
            return 0;
        }

        /**
         * Frame k is there (true) or missing (false: skipped). The merge during the capture
         * (PhotoBurst) waits here until the frame is final.
         */
        default boolean has(int k) {
            return true;
        }

        /** Frame k is no longer needed (the app closes the camera Image). */
        void release(int k);
    }

    // ---------------------------------------------------------------- GPU (item 21, 2026-11-18)
    // The robustness weights (half of the merge time) can run on the phone's GPU (GpuRobust, OpenGL ES
    // 3.1 compute). The PC bench has no factory: always the CPU. gpuMode 0 CPU, 1 GPU (CPU when it
    // fails), 2 CHECK: both, the CPU result used, the difference + timings in the log.
    static final String[] GPU_NAMES = {"Off", "On", "Check"};
    static volatile int gpuMode = Integer.getInteger("gpuMode", 0);

    /**
     * The merge on the GPU: same maths as the CPU code (float instead of double). Per merge: the
     * reference; per frame: its raw data + shifts, the robustness weights (robustWeightsCpu), the
     * longer frame's use map + blurred green, the accumulation. The accumulator stays on the GPU.
     */
    interface Gpu {
        /** Reference colour planes, its raw data (accumulator start) and its noise variance. */
        void setReference(float[][] refPl, short[] refRaw, float[] refVar);

        /** Frame k's raw data (row-major Bayer), from a Java array or a direct buffer. */
        void uploadRaw(short[] raw);

        void uploadRaw(java.nio.ByteBuffer bb, int rowStride);

        /** fixWarm on the GPU's frame; returns the number of pixels fixed. */
        int fixWarm(float abs, float rel, float neighbour);

        /** fixBlack: the 16x16 block medians per CFA channel (4 x bw x bh, row-major blocks). */
        float[] blockMedians(int bw, int bh);

        /** fixBlack pre-test: per block the number of values below below[d] (4 x bw x bh). */
        float[] blockBelow(int bw, int bh, float[] below);

        /** fixBlack: adds add[d] to the frame (clipped at white). */
        void addBlack(int[] add, int white);

        /** Half-res green of the frame (greenHalf) into plane p (MergeCore.GP_*). */
        void green(int p);

        /** measuredRatio samples: v / p where valid, else -1 (every step-th quad). */
        float[] ratioSamples(int gP, int prevP, float clip, int step, int count);

        void copyPlane(int src, int dst);

        void divPlane(int p, float d);

        /** align(): coarse errors, row partial sums {169 x rows}. */
        float[] alignCoarse(int refP, int gP, float clamp, int rows);

        /** align(): refine, per shift and sampled row {sum, count}. */
        float[] alignRefine(int refP, int gP, float clamp, int cy, int cx, int R, int step, int rows);

        /** align(): per tile and shift the error, then per tile the reference sum. */
        float[] alignTiles(int refP, int gP, float clamp, int gy, int gx, int search);

        /** The tile shifts (half-res px) of the frame for the weights / accumulation. */
        void setShifts(int[][][] sh);

        /**
         * Aligned colour planes (+ clip mask when doNear); returns the bias samples (every 7th quad:
         * 3x3 mean of the reference and the frame, R, G, B: 6 floats), valid until weights().
         */
        java.nio.FloatBuffer samples(float rk, float clipG, boolean doNear);

        /** Robustness + erosion + blur -> the frame's weight plane; returns its mean (every 31st quad). */
        double weights(double[] bias, boolean doNear, boolean doClipQ, double rS, double rO, double kS2,
                       double kO2, double RR, float rk, boolean oldErode);

        /** Check mode: the weight plane against the CPU's: {max |d|, sum |d|, count > 0.02}. */
        double[] compareWeights(float[] cpu);

        /** Longer frame: use map (clip ramp, erosion, feather) + its blurred green (noise model). */
        void longMaps(float rk, float lo, float hi, int grow, int featherR, boolean featherCap, boolean clipPixel,
                      int greenR, float greenBlack);

        /** Adds the frame to the accumulator (CPU merge loop: the same formula). */
        void accumulate(boolean isLong, boolean natK, float rk, double kS, double kO, double kR);

        /** Accumulator (W x H) and weight sum (W2 x H2) back to the CPU. */
        void readAccumulator(float[] acc, float[] wsum);

        /**
         * End of the merge on the GPU: accumulator / weight sum, hot pixels (removeHotPixels), adaptive
         * denoise (adaptiveDenoise). Returns {hot pixels, denoised pixels}.
         */
        int[] finishMerge(boolean hot, boolean newHot, float warmNb, boolean den, double rS, double rO,
                          float denK, float denSpace, double denMin, double R);

        /** Check mode: the GPU's final merged data against the CPU's: {max |d| DN, sum |d|, count > 0.5 DN, n}. */
        double[] compareFinal(float[] acc);

        /** Develop on the GPU: frees the merge buffers, output crop cw x ch. */
        void beginDevelop(int cw, int ch);

        /** develop() step 5 (demosaic, colour, gain, sRGB + S-curve) of the crop at (cx, cy). */
        void render(float[] m, float[] clipQ, float[] gmap, float[] lift, float[] toneRatio, int lw4, int lh4,
                    float[][] shading, int shCh, float[] ccm, float minGain, float lk, int cx, int cy, int[] color);

        void chroma();

        void localContrast(float amount, int r, float eps, float clMax);

        void sharpen(float amount);

        void readPixels(int[] out);

        /** Check mode: {max |d| levels, sum |d|, count > 1 level, n}. */
        double[] comparePixels(int[] cpu);

        String info();

        /** Waits until the GPU has finished (timings). */
        void sync();

        /** The GL context on the calling thread / off it (merge and develop may run on different threads). */
        void makeCurrent();

        void releaseCurrent();

        void close();
    }

    interface GpuFactory {
        /** ds = the 2x2 positions of R, G1, G2, B. Called on the merge thread (GL context there). */
        Gpu create(int W, int H, int tile, float[] black, int[] ds) throws Exception;
    }

    static volatile GpuFactory gpuFactory;        // set by the app (BurstMerger)
    private Gpu gpu;                              // during a merge, or null
    private long gpuMs, cpuMs;                    // weights time (check mode: both)
    private double gpuMaxD, gpuSumD;
    private long gpuBad, gpuN;
    private double[] gpuAccCmp;
    private long gpuPreShiftDiff, gpuPreTiles, gpuPreBlackDiff, gpuPreWarmDiff;
    private double gpuPreRatioDiff;
    // timings of the GPU pre-steps (synchronised: GL calls return before the GPU is done)
    static final boolean GPU_TIMES = !Boolean.getBoolean("noGpuTimes");
    private long gtUp, gtWarm, gtBlack, gtGreen, gtRatio, gtAlign;

    private long gpuLap(long t0) {
        if (GPU_TIMES) gpu.sync();
        return now() - t0;
    }
    private int[] gpuCnt;

    final int W, H, W2, H2;
    private final int white;
    private final float[] black;
    private final int[] color;
    private final float[] gain = new float[4];    // per 2x2 position
    private final float[] ccm;
    private final float[][] shading;
    private final double sceneScale;              // normalised value -> scene light level (exposure x ISO)
    private float minGain = 1;                    // clip level after WB (where the first channel saturates)
    String log = "";

    MergeCore(Meta m) {
        W = m.W;
        H = m.H;
        W2 = W / 2;
        H2 = H / 2;
        white = m.white;
        black = m.black.clone();
        color = m.color.clone();
        ccm = m.ccm.clone();
        shading = m.shading;
        sceneScale = 1.0 / (m.exposureNs / 1e9 * m.iso / 50.0);
        wbOwn = m.wb.clone();
        cal = m.cal.clone();
        setWb(m.wb);
    }

    /** Calibration factor of a white-balance entry (r, g_even, g_odd, b). */
    private float calOfWb(int i) {
        return i == 0 ? cal[0] : i == 3 ? cal[2] : cal[1];
    }

    private void setWb(float[] wb) {
        float gg = (wb[1] + wb[2]) / 2;
        for (int i = 0; i < 4; i++) gain[i] = color[i] == 0 ? wb[0] : color[i] == 2 ? wb[3] : gg;
        minGain = Math.min(gain[0], Math.min(gain[1], Math.min(gain[2], gain[3])));
    }

    static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    /** One raw frame (a view of the shared buffer). */
    private final class Raw {
        final short[] px;

        Raw(short[] buf) {
            px = buf;
        }

        int get(int y, int x) {
            return px[y * W + x] & 0xFFFF;
        }
    }

    /** Merged Bayer -> 16-bit raw values (rounded, clipped at the white level) for the DNG. */
    /**
     * Scale for the merged DNG: the largest power of 2 with white x scale <= 65535 (10-bit
     * sensor: 64, i.e. 6 extra bits). The merge averages many frames and scales the longer
     * ones down, so its shadows have far finer steps than 1 sensor unit; rounding to sensor
     * units would throw that away. Black and white level are scaled the same (DngPatch).
     */
    int dngScale() {
        int s = 1;
        while ((long) white * s * 2 <= 65535) s *= 2;
        return s;
    }

    /** Merged Bayer -> unsigned 16-bit (stored in short), scaled by dngScale(). */
    short[] toRaw16(float[] m) {
        int sc = dngScale();
        int top = white * sc;
        short[] s = new short[m.length];
        for (int i = 0; i < m.length; i++) s[i] = (short) Math.min(top, Math.max(0, Math.round(m[i] * sc)));
        return s;
    }

    // ------------------------------------------------------------------ merge

    /**
     * Merges the frames (index ref = reference); releases every frame when it has been
     * used. Returns the merged Bayer data (raw units, float) and fills wsumOut (half res).
     * expRatio[k] = exposure of frame k / exposure of the reference (null = all 1).
     */
    float[] merge(Frames frames, int n, int refIdx, float[][] wsumOut, float[] expRatio) {
        try {
            return mergeImpl(frames, n, refIdx, wsumOut, expRatio);
        } catch (RuntimeException | Error e) {
            closeGpu();
            throw e;
        }
    }

    /** Frees the GPU (after develop, or when the photo is given up). */
    void releaseGpu() {
        closeGpu();
    }

    private float[] mergeImpl(Frames frames, int n, int refIdx, float[][] wsumOut, float[] expRatio) {
        long t0 = now();
        short[] buf = new short[W * H];
        long tRead = 0, tAlign = 0, tWeight = 0, tAcc = 0, ts;
        ts = now();
        frames.read(refIdx, buf);
        int warmFixed = fixWarm(buf, refIdx);
        StringBuilder blackLog = new StringBuilder();
        fixBlack(buf, refIdx, blackLog);
        Raw rr = new Raw(buf);
        tRead += now() - ts;
        float[] acc = new float[W * H];
        // ASTRO: a clipped longer frame still says "at least its clip level / ratio" - in the glow
        // around a lamp all long frames clip and only the short reference (a few DN, read noise)
        // was left: black dots after the strong astro brightening (2026-11-17). Static tripod
        // scene: the merge is not allowed below that bound. (Not for handheld HDR: motion.)
        final float[] lowBound = astro && Boolean.getBoolean("clipBound") ? new float[W * H] : null;
        IntStream.range(0, H).parallel().forEach(y -> {
            for (int x = 0; x < W; x++) acc[y * W + x] = rr.get(y, x);
        });
        float[] refG = greenHalf(rr);
        // (3x3 median of the reference: only the old motion weights use it - memory)
        boolean oldW = noiseS == null || noiseO == null || Boolean.getBoolean("oldRobust");
        refGmCache = expRatio != null && oldW ? median3(refG, W2, H2) : refG;
        final float[] refQ = down(refG, W2, H2, 2);         // quarter res, for the motion weights
        // local edge strength of the reference (quarter res): next to strong edges a sub-pixel
        // misalignment gives large differences that are not motion (edge-aware tolerance)
        final float[] edgeQ;
        {
            final int w4 = W2 / 2, h4 = H2 / 2;
            float[] rb = gauss(refQ, w4, h4, 1f);
            edgeQ = new float[w4 * h4];
            IntStream.range(1, h4 - 1).parallel().forEach(y -> {
                for (int x = 1; x < w4 - 1; x++) {
                    int i = y * w4 + x;
                    float gx = (rb[i + 1] - rb[i - 1]) / 2, gy = (rb[i + w4] - rb[i - w4]) / 2;
                    float e = EDGE_TOL * Math.max(Math.abs(gx), Math.abs(gy));
                    edgeQ[i] = e * e;
                }
            });
        }
        float[] wsum = new float[W2 * H2];
        Arrays.fill(wsum, 1f);
        // reference colour planes at half resolution (one value per Bayer quad, black subtracted)
        // and their local 3x3 means / variances: the per-pixel robustness (step 3)
        final boolean newRobust = noiseS != null && noiseO != null && !Boolean.getBoolean("oldRobust");
        final float[][] refPl = newRobust ? quadPlanes(rr, null, 1f, 0f, 0f) : null;
        // (their 3x3 means / variances are computed on the fly in robustWeights - storing them took
        // 96 MB for the whole merge: out of memory on the phone, 2026-11-17)
        // noise of the reference per quad (DN^2), from its local mean (a noisy value would bias it)
        final float[] refVar;
        final boolean noiseModel = noiseS != null && noiseO != null && noiseS.length > refIdx;
        if (noiseModel) {
            float[] rm = gauss(refG, W2, H2, 1.5f);
            final double rS = noiseS[refIdx], rO = noiseO[refIdx], R = white - (black[0] + black[1] + black[2] + black[3]) / 4;
            refVar = new float[W2 * H2];
            for (int i = 0; i < rm.length; i++) refVar[i] = (float) ((rS * Math.max(0, rm[i]) / R + rO) * R * R);
        } else {
            refVar = null;
        }
        // GPU (item 21): weights + long-frame maps + accumulation on the graphics chip; the
        // accumulator stays there until the end (gpuMode 1). Check (2): the CPU does everything as
        // before and its result is used, the GPU runs alongside and is compared.
        closeGpu();
        gpuMs = cpuMs = 0;
        gpuMaxD = gpuSumD = 0;
        gpuBad = gpuN = 0;
        String gpuNote = "";
        if (newRobust && noiseModel && lowBound == null && gpuMode > 0 && gpuFactory != null
                && System.getProperty("dbgRow") == null && !Boolean.getBoolean("robStats")) {
            long tg = now();
            try {
                closeStaleGpu();
                gpu = gpuFactory.create(W, H, TILE, black, bayerOrder());
                synchronized (MergeCore.class) {
                    openGpu = gpu;
                }
                long tr = now();
                gpu.setReference(refPl, buf, refVar);
                gpuNote = String.format(java.util.Locale.US, " [gpu %s, start %d ms, reference %d ms]", gpu.info(),
                        now() - tg, now() - tr);
            } catch (Throwable e) {
                gpuNote = " [gpu failed: " + e + "]";
                closeGpu();
            }
        }
        StringBuilder lg = new StringBuilder();
        frames.release(refIdx);
        // frames in order of their light (the bracketing chain: fast -> longer -> longest)
        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) order[k] = k;
        if (expRatio != null) Arrays.sort(order, (a, b) -> Float.compare(expRatio[a], expRatio[b]));
        PreState cst = new PreState();           // CPU chain state (previous longer frame, chain of ratios)
        cst.chainG = refG;
        GPreState gst = new GPreState();         // the GPU's own (On: alone; Check: alongside, compared)
        int[] warmBox = {warmFixed};
        for (int oi = 0; oi < n; oi++) {
            final int k = order[oi];
            if (k == refIdx) continue;
            if (skip != null && k < skip.length && skip[k]) {    // (astro: a shorter frame)
                frames.release(k);
                continue;
            }
            if (!frames.has(k)) continue;                     // (merge during the capture: may wait)
            ts = now();
            final boolean natK = noMotion != null && k < noMotion.length && noMotion[k];
            final float gbk = (black[0] + black[1] + black[2] + black[3]) / 4;
            // ---- GPU: upload (On: straight from the camera / cache buffer), warm + black fix, green,
            // ratio, alignment (item 21, 2026-11-18)
            Pre gp = null;
            boolean bufRead = false;
            if (gpu != null) {
                try {
                    long tu = now();
                    java.nio.ByteBuffer dir = gpuMode == 1 ? frames.direct(k) : null;
                    if (dir != null) {
                        gpu.uploadRaw(dir, frames.directStride(k));
                    } else {
                        frames.read(k, buf);
                        bufRead = true;
                        gpu.uploadRaw(buf);
                    }
                    gtUp += gpuLap(tu);
                    gp = gpuPreFrame(k, expRatio, gst, gpuMode == 2 ? null : lg, gpuMode == 2 ? null : blackLog, warmBox);
                } catch (Throwable e) {
                    lg.append(" [gpu failed at frame ").append(k).append(": ").append(e).append(" - cpu from here]");
                    if (gpuMode == 1) gpu.readAccumulator(acc, wsum);
                    closeGpu();
                    gp = null;
                }
            }
            long tGpuPre = now() - ts;
            // ---- CPU (Off, Check, or after a GPU failure)
            Pre cp = null;
            if (gpu == null || gpuMode == 2) {
                cp = cpuPreFrame(frames, k, buf, bufRead, expRatio, refG, cst, lg, blackLog, warmBox);
                if (gp != null) {                             // check: the GPU's shifts / ratio
                    gpuPreTiles += cp.sh.length * cp.sh[0].length;
                    for (int i = 0; i < cp.sh.length; i++)
                        for (int j = 0; j < cp.sh[0].length; j++)
                            if (cp.sh[i][j][0] != gp.sh[i][j][0] || cp.sh[i][j][1] != gp.sh[i][j][1]) gpuPreShiftDiff++;
                    gpuPreRatioDiff = Math.max(gpuPreRatioDiff, Math.abs(cp.rk - gp.rk));
                    if (!java.util.Arrays.equals(cp.blackAdd, gp.blackAdd)) gpuPreBlackDiff++;
                    if (cp.warm != gp.warm) gpuPreWarmDiff++;
                }
            }
            Pre pr = cp != null ? cp : gp;
            if (cp != null) {
                tRead += cp.tRead;
                tAlign += cp.tAlign;
            } else {
                tRead += tGpuPre - gp.tAlign;                 // upload + fixes + green
                tAlign += gp.tAlign;
            }
            final Raw r = cp != null ? cp.r : null;
            final float[] g = cp != null ? cp.g : null;
            final float rk = pr.rk;
            final boolean isLong = rk > 1f;
            final int[][][] sh = pr.sh;
            ts = now();
            // aligned green - reference, blurred
            final float clipG = (CLIP_USE_LO * white - gbk) / rk;   // longer frame's clip level (reference units)
            // weights per Bayer quad: the per-pixel robustness (newRobust), else the old quarter-
            // resolution tile weights (their temporaries are not built when they are not used: memory)
            double wm = 0;
            boolean cpuDo = gpu == null || gpuMode == 2;
            if (gpu != null) {
                long tg = now();
                try {
                    float rkG = gp.rk;
                    double wg = gpuFrame(gp.sh, rkG, k, refIdx, rkG > 1f, natK, (CLIP_USE_LO * white - gbk) / rkG, gbk,
                            cpuDo ? null : lg);
                    if (!cpuDo) wm = wg;
                    gpuMs += now() - tg;
                } catch (Throwable e) {
                    // the GPU failed: its accumulator (frames before this one) back to the CPU, the
                    // CPU does this frame again from the start (a failure leaves the merge to the CPU)
                    lg.append(" [gpu failed at frame ").append(k).append(": ").append(e).append(" - cpu from here]");
                    if (!cpuDo) gpu.readAccumulator(acc, wsum);
                    closeGpu();
                    if (!cpuDo) {
                        oi--;                                 // this frame again, on the CPU
                        continue;
                    }
                }
            }            if (!cpuDo) {
                tWeight += now() - ts;
                frames.release(k);
                lg.append(String.format(java.util.Locale.US, " %d:%.2f%s", k, wm,
                        isLong ? String.format(java.util.Locale.US, "(long x%.1f)", rk) : ""));
                continue;
            }
            ts = now();
            final float[] we;
            if (newRobust && !natK) {
                we = robustWeights(r, sh, rk, k, refIdx, isLong, clipG, gbk, refPl, lg);
                for (int i = 0; i < we.length; i += 31) wm += we[i];
                wm /= (we.length + 30) / 31;
                if (gpu != null) {                            // check mode: the GPU's weights
                    double[] c = gpu.compareWeights(we);
                    gpuMaxD = Math.max(gpuMaxD, c[0]);
                    gpuSumD += c[1];
                    gpuBad += (long) c[2];
                    gpuN += we.length;
                }
            } else {
                we = oldWeights(sh, g, rk, isLong, clipG, refG, refQ, edgeQ);
                for (int i = 0; i < we.length; i += 31) wm += we[i];
                wm /= (we.length + 30) / 31;
            }
            tWeight += now() - ts;
            cpuMs += now() - ts;
            ts = now();
            // longer frame: where it may be used, decided over an AREA, not per pixel - near its
            // clip level the decision flickered from pixel to pixel between the frames (colour
            // specks / blocks at bright edges). Usable 1 below CLIP_USE_LO, 0 from CLIP_USE_HI
            // (fraction of white, any channel), then excluded CLIP_GROW quads around clipped
            // parts and blended over CLIP_FEATHER quads.
            // noise-optimal weight of a longer frame (inverse variance, Granados et al. 2010):
            // var(frame, its own units) = S_k x + O_k; scaled to the reference by 1/rk -> its
            // variance / rk^2; weight relative to the reference = rk^2 var_ref / var_k. In the
            // shot-noise range this is ~rk (as before), where the read noise dominates (the dark
            // parts) up to rk^2: the longer frame counts much more there.
            final float[] gMean = noiseModel && isLong ? gauss(g, W2, H2, 1.5f) : null;
            final double kS = noiseModel ? noiseS[Math.min(k, noiseS.length - 1)] : 0;
            final double kO = noiseModel ? noiseO[Math.min(k, noiseO.length - 1)] : 0;
            final double kR = white - gbk;
            final float[] use;
            if (isLong) {
                final float[] u0 = new float[W2 * H2];
                final float lo = CLIP_USE_LO * white, hi = CLIP_USE_HI * white;
                IntStream.range(0, H2).parallel().forEach(qy -> {
                    for (int qx = 0; qx < W2; qx++) {
                        float fy = Math.max(0, Math.min(H2 - 1.001f, qy + shiftF(sh, qy, qx, 0)));
                        float fx = Math.max(0, Math.min(W2 - 1.001f, qx + shiftF(sh, qy, qx, 1)));
                        int sy = (int) fy, sx = (int) fx, mx = 0;
                        for (int d = 0; d < 4; d++) {
                            int yy = 2 * sy + d / 2, xx = 2 * sx + d % 2;
                            mx = Math.max(mx, Math.max(Math.max(r.get(yy, xx), r.get(yy, xx + 2)),
                                    Math.max(r.get(yy + 2, xx), r.get(yy + 2, xx + 2))));
                        }
                        u0[qy * W2 + qx] = Math.min(1f, Math.max(0f, (hi - mx) / (hi - lo)));
                    }
                });
                float[] u = u0;
                if (newRobust && CLIP_PIXEL) {
                    // STEP 4: with the per-pixel robustness the longer frame is limited only by its
                    // own clip ramp (CLIP_USE_LO..HI); the transitions are left to the robustness
                    // (no extra margin / blur around bright parts: the dotted light edges)
                    use = u0;
                } else {
                for (int g2 = 0; g2 < CLIP_GROW; g2++) u = erode3(u, W2, H2);
                float[] ug = gauss(u, W2, H2, CLIP_FEATHER);
                // the blur must not reach back into the clipped pixels themselves: there it gave
                // ~25 % weight, x rk (up to ~46) = far more than the reference - clipped (too
                // dark) values at bright edges, a dark / pink line around a white paper (test
                // 2026-11-15). Capped by the pixel's own limit.
                if (!Boolean.getBoolean("oldFeather")) {          // bench switch
                    for (int i = 0; i < ug.length; i++) ug[i] = Math.min(ug[i], u0[i]);
                }
                use = ug;
                }
            } else {
                use = null;
            }
            // accumulate the aligned Bayer quads
            IntStream.range(0, H2).parallel().forEach(qy -> {
                for (int qx = 0; qx < W2; qx++) {
                    if (lowBound != null && isLong) {
                        // nearest pixel of the aligned frame: clipped -> lower bound (reference units)
                        int ny = Math.min(H2 - 1, Math.max(0, Math.round(qy + shiftF(sh, qy, qx, 0))));
                        int nx = Math.min(W2 - 1, Math.max(0, Math.round(qx + shiftF(sh, qy, qx, 1))));
                        for (int d = 0; d < 4; d++) {
                            if (r.get(2 * ny + d / 2, 2 * nx + d % 2) < CLIP_USE_HI * white) continue;
                            float bk = black[d], lb = bk + (CLIP_USE_HI * white - bk) / rk;
                            int j = (2 * qy + d / 2) * W + 2 * qx + d % 2;
                            if (lb > lowBound[j]) lowBound[j] = lb;
                        }
                    }
                    float wq = natK ? 1f : we[qy * W2 + qx];
                    if (wq <= 0) continue;
                    // the shift varies smoothly between the tile centres (bilinear, sub-pixel): a
                    // per-tile jump of the shift made rectangles on smooth bright gradients
                    float fy = Math.max(0, Math.min(H2 - 1.001f, qy + shiftF(sh, qy, qx, 0)));
                    float fx = Math.max(0, Math.min(W2 - 1.001f, qx + shiftF(sh, qy, qx, 1)));
                    int sy = (int) fy, sx = (int) fx;
                    float ay = fy - sy, ax = fx - sx;
                    float w00 = (1 - ay) * (1 - ax), w01 = (1 - ay) * ax, w10 = ay * (1 - ax), w11 = ay * ax;
                    float wk = rk;                    // weight factor of a longer frame vs the reference
                    if (isLong) {
                        wq *= use[qy * W2 + qx];
                        if (wq <= 1e-4f) continue;
                        if (gMean != null) {
                            double own = Math.max(0, gMean[sy * W2 + sx]) * rk;    // its own units (DN)
                            double vk = (kS * own / kR + kO) * kR * kR;
                            // (no lower limit at rk: a longer frame at a higher ISO collects only its
                            // exposure ratio in photons - the noise model gives it less weight)
                            wk = (float) Math.min(rk * rk, Math.max(0.01 * rk, rk * rk * refVar[qy * W2 + qx] / vk));
                        }
                    }
                    for (int d = 0; d < 4; d++) {
                        int yy = 2 * sy + d / 2, xx = 2 * sx + d % 2;
                        float v = w00 * r.get(yy, xx) + w01 * r.get(yy, xx + 2) + w10 * r.get(yy + 2, xx) + w11 * r.get(yy + 2, xx + 2);
                        if (isLong) {                         // scaled to the reference exposure, weight x rk
                            float bk = black[d];
                            v = wk * (bk + (v - bk) / rk);
                        }
                        acc[(2 * qy + d / 2) * W + 2 * qx + d % 2] += wq * v;
                    }
                    wsum[qy * W2 + qx] += isLong ? wq * wk : wq;
                }
            });
            tAcc += now() - ts;
            cpuMs += now() - ts;
            frames.release(k);
            lg.append(String.format(java.util.Locale.US, " %d:%.2f%s", k, wm,
                    isLong ? String.format(java.util.Locale.US, "(long x%.1f)", rk) : ""));
        }
        // end of the merge: division, hot pixels, adaptive denoise - on the GPU (On), or the CPU
        // (Off, Check: the GPU's result compared below)
        final boolean doHot = !Boolean.getBoolean("noHot");
        final boolean doDen = noiseS != null && noiseO != null && !Boolean.getBoolean("noAdaptDenoise");
        warmFixed = warmBox[0];
        int hot = 0, den = 0;
        boolean gpuDone = false;
        if (gpu != null) {
            long tg = now();
            try {
                int[] c = gpu.finishMerge(doHot, !Boolean.getBoolean("oldHot"), WARM_NEIGHBOUR, doDen,
                        doDen ? noiseS[refIdx] : 0, doDen ? noiseO[refIdx] : 0, DEN_K, DEN_SPACE, DEN_MIN,
                        white - (black[0] + black[1] + black[2] + black[3]) / 4.0);
                if (gpuMode == 2) {
                    gpuCnt = c;
                } else {
                    gpu.readAccumulator(acc, wsum);
                    hot = c[0];
                    den = c[1];
                    gpuDone = true;
                }
            } catch (Throwable e) {
                if (gpuMode != 2) throw new IllegalStateException("GPU merge end failed: " + e, e);
                gpuNote += " [gpu end failed: " + e + "]";
                closeGpu();
            }
            gpuMs += now() - tg;
        }
        if (!gpuDone) {
            IntStream.range(0, H).parallel().forEach(y -> {
                for (int x = 0; x < W; x++) acc[y * W + x] /= wsum[(y / 2) * W2 + x / 2];
            });
        }
        if (lowBound != null) {
            int nb = 0;
            for (int i = 0; i < acc.length; i++) {
                if (acc[i] < lowBound[i]) {
                    acc[i] = lowBound[i];
                    nb++;
                }
            }
            lg.append(String.format(java.util.Locale.US, " [clip bound %d px]", nb));
        }
        if (noiseS != null && noiseO != null && refIdx < noiseS.length) {
            refNoiseS = noiseS[refIdx];
            refNoiseO = noiseO[refIdx];
        }
        if (!gpuDone) {
            long tc = now();
            hot = doHot ? removeHotPixels(acc) : 0;
            den = doDen ? adaptiveDenoise(acc, wsum, noiseS[refIdx], noiseO[refIdx]) : 0;
            cpuMs += now() - tc;
            if (gpu != null && gpuMode == 2) {
                try {
                    gpuAccCmp = gpu.compareFinal(acc);
                } catch (Throwable e) {
                    gpuNote += " [gpu compare failed: " + e + "]";
                }
            }
        }
        // (the GPU stays open for develop: closed there, or by releaseGpu / the next merge; its
        // context is released from this thread - develop may run on another one)
        if (gpu != null) {
            try {
                gpu.releaseCurrent();
            } catch (Throwable ignored) {
            }
        }
        boolean gpuRan = gpu != null || gpuMs > 0;
        if (gpuMode == 2 && gpuRan) {
            gpuNote += String.format(java.util.Locale.US, " [gpu check: gpu %d ms, cpu %d ms (weights, accumulate,"
                    + " hot px, denoise); weights diff max %.4f, mean %.5f, %d of %d quads > 0.02", gpuMs, cpuMs, gpuMaxD,
                    gpuN > 0 ? gpuSumD / gpuN : 0, gpuBad, gpuN);
            if (gpuAccCmp != null) {
                gpuNote += String.format(java.util.Locale.US, "; result diff max %.3f DN, mean %.5f DN, %d of %d px > 0.5 DN",
                        gpuAccCmp[0], gpuAccCmp[1] / Math.max(1, gpuAccCmp[3]), (long) gpuAccCmp[2], (long) gpuAccCmp[3]);
            }
            if (gpuCnt != null) {
                gpuNote += String.format(java.util.Locale.US, "; hot px gpu %d / cpu %d, denoised gpu %d / cpu %d",
                        gpuCnt[0], hot, gpuCnt[1], den);
            }
            gpuNote += String.format(java.util.Locale.US, "; shifts differ in %d of %d tiles, ratio diff max %.4f,"
                    + " black fix differs in %d, warm fix in %d frames", gpuPreShiftDiff, gpuPreTiles, gpuPreRatioDiff,
                    gpuPreBlackDiff, gpuPreWarmDiff);
            gpuNote += "]";
        } else if (gpuMode == 1 && gpuRan) {
            gpuNote += String.format(java.util.Locale.US, " [gpu: merge %d ms]", gpuMs);
        }
        if (gpuRan && GPU_TIMES) {
            gpuNote += String.format(java.util.Locale.US, " [gpu pre: upload %d, warm %d, black %d, green %d, ratio %d,"
                    + " align %d ms]", gtUp, gtWarm, gtBlack, gtGreen, gtRatio, gtAlign);
        }
        gtUp = gtWarm = gtBlack = gtGreen = gtRatio = gtAlign = 0;
        gpuAccCmp = null;
        gpuCnt = null;
        gpuPreShiftDiff = gpuPreTiles = gpuPreBlackDiff = gpuPreWarmDiff = 0;
        gpuPreRatioDiff = 0;
        lg.append(gpuNote);
        if (den > 0) lg.append(String.format(java.util.Locale.US, " [adaptive denoise %.0f%%]", 100.0 * den / acc.length));
        wsumOut[0] = wsum;
        log = String.format(java.util.Locale.US,
                "merge %d ms (read %d, align %d, weights %d, accumulate %d), weights%s, %d hot px%s",
                now() - t0, tRead, tAlign, tWeight, tAcc, lg, hot,
                (warmFixed > 0 ? ", " + warmFixed + " warm px fixed in the frames" : "") + blackLog);
        return acc;
    }

    // ------------------------------------------------------------------ develop

    // exposure fusion (Mertens et al. 2007; HDR+ finishing, Hasinoff et al. 2016)
    static final float SHOULDER = 0.80f;      // linear output: highlight compression above this
    static final boolean OLD_SHOULDER = Boolean.getBoolean("oldShoulder");   // bench test switch
    static final double FUSE_HI = 0.80;      // shortest synthetic exposure: the 99.5 % point here
    static final double FUSE_TARGET = 0.18;   // brightest synthetic exposure: the median here (mid-grey)
    // output median (EV below white) = BRIGHT_A + BRIGHT_DR * contrast EV (99.5 % / median)
    //   + BRIGHT_DARK * max(0, 2 - log2 scene light level): fitted on Google night mode, test set
    //   2026-11-15 (6 scenes: lamp at night .. overcast outdoors .. dim corridor), within ~0.7 EV
    static final double BRIGHT_A = 0.586, BRIGHT_DR = 0.543, BRIGHT_DARK = 0.375, BRIGHT_LV = 2.0;
    static final int FUSE_MAX = 7;            // at most 7 synthetic exposures (~2 EV apart; was 4 before the shadow lift)
    // SHADOW LIFT (2026-11-17: the astro house was black although 4 x 16 s frames held it): the fusion's
    // brightest exposure goes beyond "median at mid-grey" until the dark end (DARK_P point) reaches
    // DARK_TARGET, at most DARK_MAX x - but only as far as the merged data is clean there: SNR of the dark
    // level from the camera's noise model and the merge weights (averaged frames -> more lift)
    static final double DARK_P = 0.10, DARK_TARGET = Double.parseDouble(System.getProperty("darkTarget", "0.05"));
    static final double DARK_MAX = Double.parseDouble(System.getProperty("darkMax", "64"));
    static final double DARK_SNR_LO = 3, DARK_SNR_HI = 12;
    double refNoiseS, refNoiseO;              // the reference frame's noise model (set by merge)
    float[] devWsum;                          // merge weights for the develop (shadow lift)
    static final float FUSE_SIGMA = 0.2f;     // well-exposedness weight width (gamma-encoded value)
    // finishing
    static final float CONTRAST = 0.35f;      // S-curve strength on the sRGB-encoded image
    static final float SATURATION = 1.15f;
    static final float TONE_MAX = Float.parseFloat(System.getProperty("toneMax", "1"));   // 0 luminance (before 2026-11-19) .. 1 max channel
    static final float TONE_SIGMA = Float.parseFloat(System.getProperty("toneSigma", "3"));   // half-res px
    static final float TONE_RATIO_MAX = 6f;
    private float[] toneRatio;                    // develop: smoothed max channel / luminance (half res)

    private static final float[] SRGB = new float[4097];

    static {
        for (int i = 0; i <= 4096; i++) {
            double l = i / 4096.0;
            SRGB[i] = (float) (l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1 / 2.4) - 0.055);
        }
    }

    /** linear 0..1 -> sRGB-encoded 0..1 */
    private static float srgb(float l) {
        return SRGB[(int) (Math.min(1f, Math.max(0f, l)) * 4096)];
    }

    private float[] clipQ;                        // per Bayer quad: max raw level / white (dilated, blurred)

    /**
     * Develops the merged Bayer data (modified in place) into an ARGB crop [cx, cy, cw, ch] of the
     * sensor image. wsum = merge weights (half res).
     */
    int[] develop(float[] m, float[] wsum, int cx, int cy, int cw, int ch) {
        long t0 = now();
        shared = toneLink != null ? toneLink.await() : null;
        long tWait = now() - t0;
        if (shared != null) {
            // the master's white balance is shared for the REFERENCE camera (its gains x its
            // calibration); this unit's sensor differs slightly (white card test 2026-11-16: two
            // Pixel 7a 0.8 % in red, 1.8 % in blue apart) - divided by its own calibration
            float[] w = new float[4];
            for (int i = 0; i < 4; i++) w[i] = shared.wb[i] / calOfWb(i);
            setWb(w);
            System.arraycopy(shared.ccm, 0, ccm, 0, 9);
        }
        // ---- 1. near-clipped quads (any channel >= 80 % of white): rendered neutral white later
        // (a clipped green next to an unclipped red gives pink edges otherwise)
        float[] cq = new float[W2 * H2];
        IntStream.range(0, H2).parallel().forEach(qy -> {
            for (int qx = 0; qx < W2; qx++) {
                float mx = 0;
                for (int d = 0; d < 4; d++) {
                    int i = (2 * qy + d / 2) * W + 2 * qx + d % 2;
                    mx = Math.max(mx, (m[i] - black[d]) / (white - black[d]));
                }
                cq[qy * W2 + qx] = mx;
            }
        });
        float[] cd = new float[W2 * H2];
        IntStream.range(0, H2).parallel().forEach(qy -> {
            for (int qx = 0; qx < W2; qx++) {
                float mx = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.min(H2 - 1, Math.max(0, qy + dy));
                    for (int dx = -1; dx <= 1; dx++) mx = Math.max(mx, cq[yy * W2 + Math.min(W2 - 1, Math.max(0, qx + dx))]);
                }
                cd[qy * W2 + qx] = mx;
            }
        });
        clipQ = gauss(cd, W2, H2, 2f);
        // ---- 2. black / white level, white balance (soft knee where the first channel clips),
        // lens shading
        IntStream.range(0, H).parallel().forEach(y -> {
            float[] sr = shadingAt(shading, y);
            for (int x = 0; x < W; x++) {
                int p = (y % 2) * 2 + x % 2;
                float v = (m[y * W + x] - black[p]) / (white - black[p]) * gain[p];
                float k0 = 0.85f * minGain;
                if (v > k0) v = k0 + 0.15f * minGain * (float) Math.tanh((v - k0) / (0.15f * minGain));
                if (sr != null) v *= shadeGain(sr, p, x);
                m[y * W + x] = v;
            }
        });
        // ---- 3. half-resolution luminance (linear sRGB) for the tone mapping
        int pr = 0, pb = 0, pg1 = -1, pg2 = -1;
        for (int i = 0; i < 4; i++) {
            if (color[i] == 0) pr = i;
            else if (color[i] == 2) pb = i;
            else if (pg1 < 0) pg1 = i;
            else pg2 = i;
        }
        final int fr = pr, fb = pb, fg1 = pg1, fg2 = pg2;
        float[] lum = new float[W2 * H2];
        IntStream.range(0, H2).parallel().forEach(qy -> {
            for (int qx = 0; qx < W2; qx++) {
                int o = 2 * qy * W + 2 * qx;
                float r = m[o + (fr / 2) * W + fr % 2], b = m[o + (fb / 2) * W + fb % 2];
                float g = (m[o + (fg1 / 2) * W + fg1 % 2] + m[o + (fg2 / 2) * W + fg2 % 2]) / 2;
                float lr = ccm[0] * r + ccm[1] * g + ccm[2] * b;
                float lg = ccm[3] * r + ccm[4] * g + ccm[5] * b;
                float lb = ccm[6] * r + ccm[7] * g + ccm[8] * b;
                lum[qy * W2 + qx] = Math.max(0, 0.2126f * lr + 0.7152f * lg + 0.0722f * lb);
            }
        });
        // colour ratio for the brightness curve (item 24, 2026-11-19): brightest channel / luminance of
        // the SMOOTHED half-res colour - a saturated colour is not darkened like a shadow, while noise
        // (averaged out here) does not lift dark parts (the per-pixel max channel: +50 % noise, #84)
        toneRatio = TONE_MAX > 0 ? toneRatioOf(m, fr, fb, fg1, fg2) : null;
        long t1 = now();
        // ---- 4. exposure fusion -> gain per half-res pixel, then one global factor: the median
        // where Google puts it (darker for night scenes and for very high contrast)
        devWsum = wsum;
        final float[] gmap = fuseGain(lum, W2, H2);
        {
            int ns = (lum.length + 6) / 7;
            float[] s = new float[ns], f = new float[ns];
            for (int i = 0, j = 0; j < ns; i += 7, j++) {
                s[j] = lum[i];
                f[j] = lum[i] * gmap[i];
            }
            Arrays.sort(s);
            Arrays.sort(f);
            double med = Math.max(1e-6, s[ns / 2]), hi = Math.min(1.0, Math.max(1e-4, s[(int) (ns * 0.995)]));
            double dr = Math.max(0, Math.log(hi / med) / Math.log(2));
            // a bare bulb in the picture (its 99.5 % point) must not darken the whole room: the
            // contrast counts at most DR_MAX (bulb scene 2026-11-17: 11.2 EV -> the room at -7.1 EV)
            double drUsed = Boolean.getBoolean("oldDr") ? dr : Math.min(dr, DR_MAX);
            double lv = Math.log(med * sceneScale) / Math.log(2);
            double ev = BRIGHT_A + BRIGHT_DR * drUsed + BRIGHT_DARK * Math.max(0, BRIGHT_LV - lv);
            if (brightness == BRIGHT_BRIGHT) ev = Math.min(ev, BRIGHT_MIN_EV);
            if (brightness == BRIGHT_NATURAL) ev = Math.min(ev, NATURAL_MIN_EV);
            // astro (tripod series of long frames): the night-look rule above was fitted to Google's
            // handheld night photos and put a moonless valley at -8 EV (almost black); balcony test
            // 2026-11-16: -5 EV looks like a natural night photo, "bright" -3 EV almost like dusk
            if (astro) ev = brightness == BRIGHT_BRIGHT ? BRIGHT_MIN_EV : ASTRO_EV;
            if (!Double.isNaN(medianEv)) ev = -medianEv;   // fixed median brightness (EV below white)
            double target = Math.pow(2, -ev), fmed = Math.min(0.9, Math.max(1e-6, f[ns / 2]));
            // brightness curve y = x * k^(1 - sqrt(x)): factor k for the dark and middle tones, 1 at
            // white (highlights stay where the fusion put them); k so that the median hits the target
            bright = (float) Math.pow(target / fmed, 1 / (1 - Math.sqrt(fmed)));
            fuseLog += String.format(java.util.Locale.US, ", contrast %.1f EV, light %.1f, median -> %.1f EV (k %.2f)",
                    dr, lv, -ev, bright) + fuseDarkLog;
        }
        if (shared != null) {
            bright = shared.bright;
            fuseLog += String.format(java.util.Locale.US, ", SHARED rendering from the master (waited %d ms, k %.2f)",
                    tWait, bright);
        } else if (toneLink != null) {
            Tone t = new Tone();
            for (int i = 0; i < 4; i++) t.wb[i] = wbOwn[i] * calOfWb(i);    // for the reference camera
            System.arraycopy(ccm, 0, t.ccm, 0, 9);
            t.g0 = fuseG0;
            t.step = fuseStep;
            t.ne = fuseNe;
            t.bright = bright;
            toneLink.publish(t);
            fuseLog += ", rendering published";
        }
        final float lk = (float) (Math.log(bright) / Math.log(2));
        final float[] lift = Boolean.getBoolean("noShadow") ? null : shadowLift(lum, gmap, wsum, lk);
        final int lw4 = (W2 + 3) / 4, lh4 = (H2 + 3) / 4;
        long t2 = now();
        // ---- 5. full resolution: demosaic, colour, gain, sRGB + S-curve
        // GPU (item 21): step 5 + chroma denoise + local contrast + sharpening there (On); Check: the
        // CPU's result is used, the GPU's compared
        if (gpu != null) {
            try {
                gpu.makeCurrent();                         // (merged on another thread: during the capture)
            } catch (Throwable e) {
                fuseLog += ", gpu context: " + e;
                closeGpu();
            }
        }
        int pcR = Integer.getInteger("pointColour", astro ? 6 : 0);
        // item 34: Night photos (people) - local contrast off, sharpening at most Low (user test #151)
        int clLev = nightLook ? 0 : Math.max(0, Math.min(CLARITY_AMOUNTS.length - 1, clarityLevel));
        float clarity = System.getProperty("clarity") != null ? Float.parseFloat(System.getProperty("clarity"))
                : CLARITY_AMOUNTS[clLev];
        int sl = Math.max(0, Math.min(SHARP_AMOUNTS.length - 1, nightLook ? Math.min(1, sharpLevel) : sharpLevel));
        boolean sharpOn = SHARP_AMOUNTS[sl] > 0 && !Boolean.getBoolean("noSharpen")
                && !(astro && !Boolean.getBoolean("astroSharpen"));
        boolean lcOn = clarity > 0 && !astro;
        // the GPU finishes the image unless a CPU-only step comes in between (astro colour fix, bench modes)
        boolean gpuFinish = !(pcR > 0 && wsum != null) && !Boolean.getBoolean("clarityFull")
                && System.getProperty("clarityMode") == null && !Boolean.getBoolean("motionDenoise");
        int[] gOut = null;
        long tGpu = 0;
        String gpuDevNote = "";
        if (gpu != null) {
            long tg = now();
            try {
                gOut = gpuDevelop(m, gmap, lift, lw4, lh4, lk, cx, cy, cw, ch, gpuFinish,
                        lcOn ? clarity : 0, sharpOn ? SHARP_AMOUNTS[sl] : 0, gpuMode == 1);
                tGpu = now() - tg;
            } catch (Throwable e) {
                gpuDevNote = ", gpu develop failed: " + e;
                gOut = null;
                closeGpu();
            }
        }
        if (gOut != null && gpuMode == 1) {
            long t3g = now();
            if (!gpuFinish) {
                finishCpu(gOut, cw, ch, wsum, cx, cy, pcR, clarity, clLev, lcOn, sharpOn, sl, true);
                if (Boolean.getBoolean("motionDenoise")) motionDenoise(gOut, cw, ch, wsum, cx, cy);
            } else {
                fuseLog += finishNote(clarity, clLev, sharpOn, sl);
            }
            closeGpu();
            long t5g = now();
            log += String.format(java.util.Locale.US, ", develop %d ms (prepare %d, fusion %d, gpu %d, cpu finish %d)%s",
                    t5g - t0, t1 - t0, t2 - t1, tGpu, t5g - t3g, fuseLog + gpuDevNote);
            return gOut;
        }
        int[] out = new int[cw * ch];
        IntStream.range(0, ch).parallel().forEach(oy -> {
            int y = cy + oy;
            float[] rgb = new float[3];
            float[] srow = shadingAt(shading, y);
            for (int ox = 0; ox < cw; ox++) {
                int x = cx + ox;
                demosaic(m, y, x, rgb);
                float r = Math.max(0, ccm[0] * rgb[0] + ccm[1] * rgb[1] + ccm[2] * rgb[2]);
                float g = Math.max(0, ccm[3] * rgb[0] + ccm[4] * rgb[1] + ccm[5] * rgb[2]);
                float b = Math.max(0, ccm[6] * rgb[0] + ccm[7] * rgb[1] + ccm[8] * rgb[2]);
                float cl = clipQ[Math.min(H2 - 1, y / 2) * W2 + Math.min(W2 - 1, x / 2)];
                if (cl > DEFRINGE_FROM) {
                    // purple / magenta fringe next to very bright areas (lens + demosaic at a hard
                    // edge, e.g. a lamp shade's lip): magenta (green below red and blue) removed
                    float mag = Math.min(r, b) - g;
                    if (mag > 0) {
                        float s2 = Math.min(1f, (cl - DEFRINGE_FROM) / 0.25f);
                        r -= mag * s2;
                        b -= mag * s2;
                    }
                }
                if (cl > CLIP_DESAT) {                    // near clipping: towards neutral white
                    float s = Math.min(1f, (cl - CLIP_DESAT) / (0.95f - CLIP_DESAT)), mxc = Math.max(r, Math.max(g, b));
                    s = s * s * (3 - 2 * s);
                    r += (mxc - r) * s;
                    g += (mxc - g) * s;
                    b += (mxc - b) * s;
                }
                float gn = bilinear(gmap, W2, H2, (x + 0.5f) / 2 - 0.5f, (y + 0.5f) / 2 - 0.5f);
                // the curve's input: luminance mixed with the brightest channel (TONE_MAX) - with luminance
                // alone a saturated colour (red: 21 % of the luminance) was darkened like a shadow
                // (SanDisk red dark, the preview light red; user 2026-11-19)
                float trv = toneRatio == null ? 1f : bilinear(toneRatio, W2, H2, (x + 0.5f) / 2 - 0.5f, (y + 0.5f) / 2 - 0.5f);
                float l = (0.2126f * r + 0.7152f * g + 0.0722f * b) * (1 + TONE_MAX * (trv - 1)) * gn;
                gn *= fastExp2(lk * (1 - (float) Math.sqrt(Math.min(1f, Math.max(0f, l)))));
                if (lift != null) gn *= bilinear(lift, lw4, lh4, ((x + 0.5f) / 2 - 0.5f) / 4f - 0.375f, ((y + 0.5f) / 2 - 0.5f) / 4f - 0.375f);
                r *= gn;
                g *= gn;
                b *= gn;
                l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                r = Math.max(0, l + (r - l) * SATURATION);
                g = Math.max(0, l + (g - l) * SATURATION);
                b = Math.max(0, l + (b - l) * SATURATION);
                float mx = Math.max(r, Math.max(g, b));
                if (mx > SHOULDER && OLD_SHOULDER) {      // bench comparison only
                    float k = (float) (0.8 + 0.2 * Math.tanh((mx - 0.8) / 0.2)) / mx;
                    r *= k;
                    g *= k;
                    b *= k;
                } else if (mx > SHOULDER) {
                    // highlight shoulder, keeps the hue: extended Reinhard with the white point at
                    // the SENSOR's clip level of this pixel (gain x WB clip x lens shading), so
                    // only really clipped raw reaches 255 - a fixed shoulder made unclipped
                    // highlights white whenever the image was scaled up (dark main frame)
                    float wp = gn * minGain * (srow != null ? shadeGain(srow, fg1, x) : 1f);
                    float t = (mx - SHOULDER) / (1 - SHOULDER), tw = (wp - SHOULDER) / (1 - SHOULDER);
                    float yv = tw > 1 ? t * (1 + t / (tw * tw)) / (1 + t) : t;
                    float k = (SHOULDER + (1 - SHOULDER) * yv) / mx;
                    r *= k;
                    g *= k;
                    b *= k;
                }
                float mx2 = Math.max(r, Math.max(g, b));
                if (mx2 > 1f && !OLD_SHOULDER) {
                    // out of range although the sensor did not clip (a saturated colour - warm
                    // light on orange wood): keep the luminance, desaturate just enough that the
                    // largest channel is 1 (gamut compression, as camera ISPs do) instead of
                    // cutting that channel flat
                    float lu = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    if (lu >= 1f) {
                        r = g = b = 1f;
                    } else {
                        float sd = (1f - lu) / (mx2 - lu);
                        r = lu + (r - lu) * sd;
                        g = lu + (g - lu) * sd;
                        b = lu + (b - lu) * sd;
                    }
                }
                out[oy * cw + ox] = 0xFF000000 | curve(srgb(r)) << 16 | curve(srgb(g)) << 8 | curve(srgb(b));
            }
        });
        long t3 = now();
        finishCpu(out, cw, ch, wsum, cx, cy, pcR, clarity, clLev, lcOn, sharpOn, sl, false);
        long t4 = now();
        if (gOut != null && gpu != null && gpuFinish) {          // check mode: the GPU's image
            try {
                double[] c = gpu.comparePixels(out);
                gpuDevNote = String.format(java.util.Locale.US,
                        ", gpu develop check: gpu %d ms; diff max %.0f levels, mean %.4f, %d of %d px > 1 level",
                        tGpu, c[0], c[1] / Math.max(1, c[3]), (long) c[2], (long) c[3]);
            } catch (Throwable e) {
                gpuDevNote = ", gpu develop compare failed: " + e;
            }
        }
        closeGpu();
        fuseLog += gpuDevNote;
        // the old "motion denoise" (3x3 median wherever < 3 frames were merged) is off: with the
        // exposure-bracketed burst most bright areas come from the reference alone, so it
        // softened text and fine detail everywhere (test 2026-11-15, paper on the shelf)
        if (Boolean.getBoolean("motionDenoise")) motionDenoise(out, cw, ch, wsum, cx, cy);   // bench switch
        long t5 = now();
        log += String.format(java.util.Locale.US, ", develop %d ms (prepare %d, fusion %d, pixels %d, chroma %d,"
                + " motion %d)%s", t5 - t0, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, fuseLog);
        return out;
    }

    /** The finishing steps after step 5 on the CPU (chroma unless done on the GPU, astro colour, local contrast, sharpening). */
    private void finishCpu(int[] out, int cw, int ch, float[] wsum, int cx, int cy, int pcR, float clarity, int cl,
                           boolean lcOn, boolean sharpOn, int sl, boolean chromaDone) {
        if (!chromaDone) chromaDenoise(out, cw, ch);
        // ASTRO: false colour of tiny lights + glows removed (item 7, option B, 2026-11-17: colour averaged
        // over r = 6 quads over the bright parts, saturation 0.8); bench: -DpointColour=r, -DpcBrightLo/Hi, -DpcSat
        if (pcR > 0 && wsum != null) {
            float lo = System.getProperty("pcBrightLo") != null ? PC_BRIGHT_LO : (astro ? 30f : 0f);
            float hi = System.getProperty("pcBrightHi") != null ? PC_BRIGHT_HI : (astro ? 90f : 120f);
            float sat = System.getProperty("pcSat") != null ? PC_SAT : (astro ? 0.8f : 1f);
            pointColour(out, cw, ch, wsum, cx, cy, pcR, lo, hi, sat);
        }
        // not in ASTRO: stars are points, the lamp glows are (short-frame) noise there - the
        // sharpening turned it into black dots (2026-11-17)
        // LOCAL CONTRAST before the fine sharpening (bench: -Dclarity=amount overrides the level)
        if (lcOn) localContrast(out, cw, ch, clarity);
        if (sharpOn) sharpen(out, cw, ch, SHARP_AMOUNTS[sl]);
        fuseLog += finishNote(clarity, cl, sharpOn, sl);
    }

    private String finishNote(float clarity, int cl, boolean sharpOn, int sl) {
        return ", local contrast " + (astro ? "off (astro)" : clarity > 0 ? CLARITY_NAMES[cl] : "Off")
                + ", sharpening " + (sharpOn ? SHARP_NAMES[sl] : astro ? "off (astro)" : "Off");
    }

    /**
     * develop() step 5 + finishing on the GPU. use = the result is used (On): read back; else
     * (Check) only compared with the CPU's image later (gpuCmpPx, after the CPU has finished).
     */
    private int[] gpuDevelop(float[] m, float[] gmap, float[] lift, int lw4, int lh4, float lk, int cx, int cy,
                             int cw, int ch, boolean finish, float clarity, float sharpAmt, boolean use) {
        int[] ds = bayerOrder();
        gpu.beginDevelop(cw, ch);
        gpu.render(m, clipQ, gmap, lift, toneRatio, lw4, lh4, shading, shading != null ? shadeChannel(ds[1]) : 0, ccm,
                minGain, lk, cx, cy, color);
        gpu.chroma();
        if (finish) {
            if (clarity > 0) {
                float rRel = Float.parseFloat(System.getProperty("clarityR", "24"));
                float eps = Float.parseFloat(System.getProperty("clarityEps", "12"));
                float clMax = Float.parseFloat(System.getProperty("clarityMax", "30"));
                gpu.localContrast(clarity, Math.max(1, Math.round(rRel * Math.max(cw, ch) / 4000f / 4)), eps, clMax);
            }
            if (sharpAmt > 0) gpu.sharpen(sharpAmt);
        }
        if (!use) return new int[0];              // check: compared after the CPU (comparePixels)
        int[] out = new int[cw * ch];
        gpu.readPixels(out);
        return out;
    }

    /** S-curve on an sRGB-encoded value -> 8 bit. */
    private static int curve(float v) {
        float s = v + CONTRAST * (v * v * (3 - 2 * v) - v);
        return Math.min(255, Math.max(0, Math.round(s * 255)));
    }

    private String fuseLog = "";
    private float fuseG0 = 1, fuseStep = 1;
    private int fuseNe = 1;
    private float bright = 1;
    double medianEv = Double.NaN;                 // optional fixed median (EV, e.g. -2.1); NaN = automatic
    int brightness;                               // BRIGHT_NIGHT (like Google) / BRIGHT_BRIGHT / BRIGHT_NATURAL
    // brightness setting (STATE field 38): 0 night look (Google night photos), 1 bright (median >= -3 EV),
    // 2 natural (median >= -4 EV; default since 2026-11-17: a lamp-lit room at night look was far too dark)
    static final int BRIGHT_NIGHT = 0, BRIGHT_BRIGHT = 1, BRIGHT_NATURAL = 2;
    static final double NATURAL_MIN_EV = 4.0;
    static final double DR_MAX = 9.5;             // night look: the contrast term counts at most this (EV)
    boolean[] noMotion;                           // per frame: no motion rejection (natural blur), or null
    boolean[] skip;                               // per frame: not merged (astro: only the longest frames), or null

    // ASTRO, LONGEST FRAMES ONLY (item 1, 2026-11-19): the shorter frames of the series put black dots
    // and dotted rings into the lamp cores (where the long frames clip only they were left: their
    // noise and wind-moved twigs, and the fusion's local gain dropped around such tiny very bright
    // points). The longest frames alone (reference = the first of them) gave clean white lamps, as a
    // real long exposure. Bench / test switch -DastroAll=true: all frames as before.
    static final boolean ASTRO_ALL = Boolean.getBoolean("astroAll");
    static final double ASTRO_LONG = 0.5;         // frames with >= this x the longest frame's light

    /** Item 1: the first of the longest frames (the astro reference); skipOut[k] = a shorter frame. */
    static int astroLongest(double[] light, boolean[] skipOut) {
        double mx = 0;
        for (double l : light) mx = Math.max(mx, l);
        int ref = -1;
        for (int k = 0; k < light.length; k++) {
            boolean lng = light[k] >= ASTRO_LONG * mx;
            skipOut[k] = !lng;
            if (lng && ref < 0) ref = k;
        }
        return Math.max(0, ref);
    }
    long[] frameExposureNs;                       // per frame (optional): long frames get the warm-pixel fix
    // per frame (optional): the camera's noise model, variance = S * x + O with x the signal as a
    // fraction of the sensor range (Camera2 SENSOR_NOISE_PROFILE / DNG NoiseProfile, green)
    double[] noiseS, noiseO;
    boolean astro;                                // astro series: own brightness (ASTRO_EV)
    static final double ASTRO_EV = 5.0;
    static final long WARM_FROM_NS = 250_000_000L;
    static final float WARM_ABS = 10f, WARM_REL = 0.2f, WARM_NEIGHBOUR = 0.25f;

    /**
     * Warm / hot pixels of a long exposure (dark current; Pixel 4 at 10 s: ~0.4 % of the pixels),
     * corrected in each frame BEFORE the merge (the merge took them for motion and filled the spot
     * from the dark reference: black specks). A pixel is warm when it exceeds all 8 same-colour
     * neighbours (2 px away) by WARM_ABS + WARM_REL x their median, while its 4 direct neighbours
     * (other colours, 1 px) are not raised by more than WARM_NEIGHBOUR of that excess: the lens
     * spreads a star over several pixels, a warm pixel is alone. Replaced by the median of the
     * same-colour neighbours. Returns the count.
     */
    /**
     * A frame darker than its black level: the sensor's stated black was too high (Pixel 4, the
     * 0.13 s frame after 10 s frames: true zero ~5.5 DN lower) - too much subtracted, the dim
     * edges of lights turned green / cyan. Per CFA channel the zero-light level = 2 % point of the
     * 16x16 block medians; only when it is clearly below the stated black (physically impossible
     * otherwise) the frame is shifted up by the difference. Never darkens a frame.
     */
    private int[] fixBlack(short[] buf, int k, StringBuilder lg) {
        if (Boolean.getBoolean("noBlackFix")) return new int[4];     // bench switch
        final int B = 16;
        int bw = W / 2 / B, bh = H / 2 / B;
        if (bw < 4 || bh < 4) return new int[4];
        int[] add = new int[4];
        boolean any = false;
        for (int d = 0; d < 4; d++) {
            final int dy = d / 2, dx = d % 2;
            float[] med = new float[bw * bh];
            IntStream.range(0, bh).parallel().forEach(by -> {
                short[] v = new short[B * B];
                for (int bx = 0; bx < bw; bx++) {
                    int n = 0;
                    for (int yy = 0; yy < B; yy++) {
                        int row = (2 * (by * B + yy) + dy) * W;
                        for (int xx = 0; xx < B; xx++) v[n++] = buf[row + 2 * (bx * B + xx) + dx];
                    }
                    Arrays.sort(v);
                    med[by * bw + bx] = ((v[B * B / 2 - 1] & 0xFFFF) + (v[B * B / 2] & 0xFFFF)) / 2f;
                }
            });
            Arrays.sort(med);
            float zero = med[(int) (med.length * 0.02)];
            float off = black[d] - zero;
            if (off > 1.5f) {
                add[d] = Math.round(off);
                any = true;
            }
        }
        if (!any) return add;
        IntStream.range(0, H).parallel().forEach(y -> {
            for (int x = 0; x < W; x++) {
                int a = add[(y & 1) * 2 + (x & 1)];
                if (a == 0) continue;
                int i = y * W + x;
                buf[i] = (short) Math.min(white, (buf[i] & 0xFFFF) + a);
            }
        });
        lg.append(String.format(java.util.Locale.US, ", frame %d black +%d/%d/%d/%d", k, add[0], add[1], add[2], add[3]));
        return add;
    }

    private int fixWarm(short[] buf, int k) {
        if (Boolean.getBoolean("noWarmFix")) return 0;    // bench switch
        long[] fe = frameExposureNs;
        if (fe == null || k >= fe.length || fe[k] < WARM_FROM_NS) return 0;
        final short[] src = buf.clone();
        int[] count = new int[H];
        IntStream.range(2, H - 2).parallel().forEach(y -> {
            float[] nb = new float[8];
            float[] tmp = new float[8];
            for (int x = 2; x < W - 2; x++) {
                int i = y * W + x;
                float bk = black[(y & 1) * 2 + (x & 1)];
                float v = (src[i] & 0xFFFF) - bk;
                if (v < WARM_ABS) continue;
                int n = 0;
                float mx = -1e9f;
                for (int dy = -2; dy <= 2; dy += 2) {
                    for (int dx = -2; dx <= 2; dx += 2) {
                        if (dx == 0 && dy == 0) continue;
                        float u = (src[i + dy * W + dx] & 0xFFFF) - bk;
                        nb[n++] = u;
                        if (u > mx) mx = u;
                    }
                }
                System.arraycopy(nb, 0, tmp, 0, 8);
                Arrays.sort(tmp);
                float med = (tmp[3] + tmp[4]) / 2;
                float excess = v - Math.max(mx, med);
                if (excess <= WARM_ABS + WARM_REL * Math.max(0, med)) continue;
                // direct neighbours (other colours): raised relative to their own surroundings?
                float raise = 0;
                int[] dn = {i - 1, i + 1, i - W, i + W};
                for (int j : dn) {
                    int yy = j / W, xx = j % W;
                    float bj = black[(yy & 1) * 2 + (xx & 1)];
                    float vj = (src[j] & 0xFFFF) - bj;
                    float s = 0;
                    int c = 0;
                    if (xx >= 2 && xx < W - 2 && yy >= 2 && yy < H - 2) {
                        s += (src[j - 2] & 0xFFFF) + (src[j + 2] & 0xFFFF) + (src[j - 2 * W] & 0xFFFF) + (src[j + 2 * W] & 0xFFFF);
                        c = 4;
                    }
                    if (c == 0) continue;
                    raise = Math.max(raise, vj - (s / c - bj));
                }
                if (raise > WARM_NEIGHBOUR * excess) continue;           // part of a star / detail
                buf[i] = (short) Math.round(med + bk);
                count[y]++;
            }
        });
        int s = 0;
        for (int c : count) s += c;
        return s;
    }
    static final double BRIGHT_MIN_EV = 3.0;      // "bright": the median at most this far below white                     // global brightness factor (dark / middle tones)
    float statMed, statHi;                        // luminance median / 99.5 % point (normalised, linear)

    private static float gam(float x) {                  // linear -> gamma 2.2 (fusion domain)
        return x <= 1e-7f ? 0 : fastExp2(fastLog2(x) / 2.2f);
    }

    private static float degam(float v) {
        return v <= 1e-7f ? 0 : fastExp2(fastLog2(v) * 2.2f);
    }

    /**
     * Exposure fusion of synthetic exposures of the luminance (w x h); returns the gain per pixel
     * (fused linear output / input). The shortest exposure puts the 99.5 % point at FUSE_HI, the
     * longest the median at FUSE_TARGET; up to FUSE_MAX exposures, equally spaced in EV.
     */
    String fuseDarkLog = "";

    // LOCAL SHADOW LIFT (2026-11-17, user: "the house is pitch black although the RAW frames show it"):
    // areas more than SHADOW_BELOW EV below the image's median are brightened towards that level
    // (SHADOW_STRENGTH of the deficit, at most SHADOW_MAX EV), local level from an edge-aware (guided)
    // filter of log brightness (no halos at the sky / house border), and only as far as the merged
    // data is clean there (SNR from the noise model and the merge weights: averaged frames -> more lift)
    static final double SHADOW_BELOW = Double.parseDouble(System.getProperty("shadowBelow", "2.5"));
    static final double SHADOW_STRENGTH = Double.parseDouble(System.getProperty("shadowStrength", "0.75"));
    static final double SHADOW_MAX = Double.parseDouble(System.getProperty("shadowMax", "4"));
    static final double SHADOW_SNR_LO = 3, SHADOW_SNR_HI = 10;
    // absolute target: areas darker than this (EV below white in the output) are lifted towards it -
    // the median alone did not work when the dark landscape itself sets the median (astro house)
    static final double SHADOW_ABS = Double.parseDouble(System.getProperty("shadowAbs", "5.0"));

    /** Gain map (linear factor, quarter of the half-res grid) for the shadow lift, or null. */
    private float[] shadowLift(float[] lum, float[] gmap, float[] wsum, float lk) {
        final int w4 = (W2 + 3) / 4, h4 = (H2 + 3) / 4;
        float[] v = new float[w4 * h4], raw = new float[w4 * h4], ws = new float[w4 * h4];
        for (int y = 0; y < h4; y++) {
            for (int x = 0; x < w4; x++) {
                double s = 0, sr = 0, sw = 0;
                int n = 0;
                for (int yy = y * 4; yy < Math.min(H2, y * 4 + 4); yy++) {
                    for (int xx = x * 4; xx < Math.min(W2, x * 4 + 4); xx++) {
                        int i = yy * W2 + xx;
                        double l = lum[i] * gmap[i];
                        l *= Math.pow(2, lk * (1 - Math.sqrt(Math.min(1, Math.max(0, l)))));
                        s += l;
                        sr += lum[i];
                        sw += wsum != null ? wsum[i] : 1;
                        n++;
                    }
                }
                int k = y * w4 + x;
                v[k] = (float) (Math.log(Math.max(s / n, 1e-7)) / Math.log(2));
                raw[k] = (float) (sr / n);
                ws[k] = (float) (sw / n);
            }
        }
        float[] sv = v.clone();
        Arrays.sort(sv);
        float med = sv[sv.length / 2];
        // edge-aware local level: self-guided filter in log2, radius 8 (= 64 px), eps (0.7 EV)^2
        int r = 8;
        float eps = 0.49f;
        float[] tmp = new float[v.length], m = new float[v.length], m2 = new float[v.length], vv = new float[v.length];
        for (int i = 0; i < v.length; i++) vv[i] = v[i] * v[i];
        boxH(v, tmp, w4, h4, r);
        boxV(tmp, m, w4, h4, r);
        boxH(vv, tmp, w4, h4, r);
        boxV(tmp, m2, w4, h4, r);
        float[] a = new float[v.length], b = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            float var = Math.max(0, m2[i] - m[i] * m[i]);
            a[i] = var / (var + eps);
            b[i] = m[i] - a[i] * m[i];
        }
        float[] ma = new float[v.length], mb = new float[v.length];
        boxH(a, tmp, w4, h4, r);
        boxV(tmp, ma, w4, h4, r);
        boxH(b, tmp, w4, h4, r);
        boxV(tmp, mb, w4, h4, r);
        float target = (float) Math.max(med - SHADOW_BELOW, -SHADOW_ABS);
        float[] ev = new float[v.length];
        double maxEv = 0, sumEv = 0;
        int lifted = 0;
        for (int i = 0; i < v.length; i++) {
            float base = ma[i] * v[i] + mb[i];
            double d = target - base;
            if (d <= 0) continue;
            double e = Math.min(SHADOW_MAX, d * SHADOW_STRENGTH);
            // noise: SNR of this area in the merged data
            double x = Math.max(1e-9, raw[i]);
            double snr = refNoiseS > 0 ? x / Math.sqrt((refNoiseS * x + refNoiseO) / Math.max(1, ws[i])) : SHADOW_SNR_HI;
            double f = Math.min(1, Math.max(0, (snr - SHADOW_SNR_LO) / (SHADOW_SNR_HI - SHADOW_SNR_LO)));
            ev[i] = (float) (e * f);
            if (ev[i] > 0.05) {
                lifted++;
                sumEv += ev[i];
                maxEv = Math.max(maxEv, ev[i]);
            }
        }
        float[] evs = new float[v.length];                 // a little smoothing (no blocks)
        boxH(ev, tmp, w4, h4, 1);
        boxV(tmp, evs, w4, h4, 1);
        float[] gain = new float[v.length];
        for (int i = 0; i < v.length; i++) gain[i] = (float) Math.pow(2, evs[i]);
        fuseDarkLog += String.format(java.util.Locale.US, ", shadow lift %.0f %% of the image, mean %.1f EV, max %.1f EV",
                100.0 * lifted / v.length, lifted > 0 ? sumEv / lifted : 0, maxEv);
        return gain;
    }

    float[] fuseGain(float[] lum, int w, int h) {
        int ns = (lum.length + 6) / 7;
        float[] s = new float[ns];
        for (int i = 0, j = 0; j < ns; i += 7, j++) s[j] = lum[i];
        Arrays.sort(s);
        float hi = Math.max(1e-4f, s[(int) (ns * 0.995)]), med = Math.max(1e-6f, s[ns / 2]);
        float g0v = (float) (FUSE_HI / hi);
        double boost = FUSE_TARGET / (med * g0v);
        double lift = 1;
        if (Boolean.getBoolean("fuseLift") && devWsum != null && refNoiseS > 0 && devWsum.length == lum.length) {
            float dark = s[(int) (ns * DARK_P)];
            if (dark > 1e-7f) {
                double want = DARK_TARGET / (dark * g0v * boost);
                if (want > 1.2) {
                    // merge weight around the dark level (pixels between the 5 % and 15 % points)
                    float lo = s[(int) (ns * 0.05)], hi2 = s[(int) (ns * 0.15)];
                    float[] wl = new float[ns];
                    int nw = 0;
                    for (int i = 0; i < lum.length && nw < ns; i += 7) if (lum[i] >= lo && lum[i] <= hi2) wl[nw++] = devWsum[i];
                    double wd = 1;
                    if (nw > 100) {
                        Arrays.sort(wl, 0, nw);
                        wd = Math.max(1, wl[nw / 2]);
                    }
                    double snr = dark / Math.sqrt((refNoiseS * dark + refNoiseO) / wd);
                    double f = Math.min(1, Math.max(0, (snr - DARK_SNR_LO) / (DARK_SNR_HI - DARK_SNR_LO)));
                    lift = Math.pow(Math.min(want, DARK_MAX), f);
                    fuseDarkLog = String.format(java.util.Locale.US, ", shadow lift x%.1f (dark %.5f, weight %.0f, SNR %.1f)",
                            lift, dark, wd, snr);
                }
            }
        }
        boost *= lift;
        statMed = med;
        statHi = hi;
        float[] gm = new float[w * h];
        int nev = boost <= 1.5 ? 1 : Math.min(FUSE_MAX, 1 + (int) Math.ceil(Math.log(boost) / Math.log(4)));
        float stepv = nev > 1 ? (float) Math.pow(boost, 1.0 / (nev - 1)) : 1f;
        if (shared != null) {                        // the master's synthetic exposures
            g0v = shared.g0;
            nev = shared.ne;
            stepv = shared.step;
            boost = Math.pow(stepv, nev - 1);
        }
        fuseG0 = g0v;
        fuseNe = nev;
        fuseStep = stepv;
        final float g0 = g0v;
        if (nev <= 1) {                              // low contrast: one exposure is enough
            Arrays.fill(gm, g0);
            fuseLog = String.format(java.util.Locale.US, ", fusion 1 exposure (gain %.2f)", g0);
            return gm;
        }
        final int ne = nev;
        final float step = stepv;
        final float[] gk = new float[ne];
        for (int k = 0; k < ne; k++) gk[k] = g0 * (float) Math.pow(step, k);
        // well-exposedness weights, normalised
        final float[][] wt = new float[ne][w * h];
        final float inv2s2 = 1f / (2 * FUSE_SIGMA * FUSE_SIGMA);
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float sum = 0;
                for (int k = 0; k < ne; k++) {
                    float v = gam(Math.min(1f, lum[i] * gk[k])) - 0.5f;
                    float wv = (float) Math.exp(-v * v * inv2s2) + 1e-6f;
                    wt[k][i] = wv;
                    sum += wv;
                }
                for (int k = 0; k < ne; k++) wt[k][i] /= sum;
            }
        });
        // Laplacian pyramid blending
        int levels = 1;
        for (int lw = w, lh = h; Math.min(lw, lh) > 16; lw = (lw + 1) / 2, lh = (lh + 1) / 2) levels++;
        int[] lw = new int[levels], lh = new int[levels];
        lw[0] = w;
        lh[0] = h;
        for (int l = 1; l < levels; l++) {
            lw[l] = (lw[l - 1] + 1) / 2;
            lh[l] = (lh[l - 1] + 1) / 2;
        }
        float[][] res = new float[levels][];
        for (int l = 0; l < levels; l++) res[l] = new float[lw[l] * lh[l]];
        for (int k = 0; k < ne; k++) {
            final float g = gk[k];
            float[] v = new float[w * h];
            IntStream.range(0, h).parallel().forEach(y -> {
                for (int x = 0; x < w; x++) v[y * w + x] = gam(Math.min(1f, lum[y * w + x] * g));
            });
            float[] gv = v, gw = wt[k];
            for (int l = 0; l < levels; l++) {
                float[] nv = null, nw = null;
                float[] lap;
                if (l < levels - 1) {
                    nv = down2(gv, lw[l], lh[l]);
                    nw = down2(gw, lw[l], lh[l]);
                    float[] u = up2(nv, lw[l + 1], lh[l + 1], lw[l], lh[l]);
                    lap = u;
                    for (int i = 0; i < lap.length; i++) lap[i] = gv[i] - u[i];
                } else {
                    lap = gv;
                }
                float[] r = res[l];
                for (int i = 0; i < r.length; i++) r[i] += gw[i] * lap[i];
                gv = nv;
                gw = nw;
            }
            wt[k] = null;
        }
        float[] o = res[levels - 1];
        for (int l = levels - 2; l >= 0; l--) {
            float[] u = up2(o, lw[l + 1], lh[l + 1], lw[l], lh[l]);
            for (int i = 0; i < u.length; i++) u[i] += res[l][i];
            o = u;
        }
        final float[] fo = o;
        final float gmax = gk[ne - 1], eps = 2f / (white - black[0]);
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float lo = degam(Math.min(1f, Math.max(0f, fo[i])));
                gm[i] = Math.min(gmax, lo / Math.max(lum[i], eps));
            }
        });
        fuseLog = String.format(java.util.Locale.US, ", fusion %d exposures, %.1f EV (gain %.2f .. %.2f)",
                ne, Math.log(boost) / Math.log(2), g0, gmax);
        return gm;
    }

    /** Pyramid reduce: [1 2 1]^2 / 16 blur at the even pixels -> ((w+1)/2) x ((h+1)/2). */
    private static float[] down2(float[] a, int w, int h) {
        int w2 = (w + 1) / 2, h2 = (h + 1) / 2;
        float[] o = new float[w2 * h2];
        IntStream.range(0, h2).parallel().forEach(y -> {
            int y0 = Math.max(0, 2 * y - 1), y1 = 2 * y, y2 = Math.min(h - 1, 2 * y + 1);
            for (int x = 0; x < w2; x++) {
                int x0 = Math.max(0, 2 * x - 1), x1 = Math.min(w - 1, 2 * x), x2 = Math.min(w - 1, 2 * x + 1);
                int yy1 = Math.min(h - 1, y1);
                float s = a[y0 * w + x0] + 2 * a[y0 * w + x1] + a[y0 * w + x2]
                        + 2 * (a[yy1 * w + x0] + 2 * a[yy1 * w + x1] + a[yy1 * w + x2])
                        + a[y2 * w + x0] + 2 * a[y2 * w + x1] + a[y2 * w + x2];
                o[y * w2 + x] = s / 16;
            }
        });
        return o;
    }

    /** Pyramid expand: bilinear from (w2 x h2) at the even pixels to (w x h). */
    private static float[] up2(float[] a, int w2, int h2, int w, int h) {
        float[] o = new float[w * h];
        IntStream.range(0, h).parallel().forEach(y -> {
            float fy = Math.min(h2 - 1.001f, y * 0.5f);
            int y0 = (int) fy;
            float ay = fy - y0;
            for (int x = 0; x < w; x++) {
                float fx = Math.min(w2 - 1.001f, x * 0.5f);
                int x0 = (int) fx;
                float ax = fx - x0;
                int i = y0 * w2 + x0;
                int x1 = Math.min(w2 - 1, x0 + 1), y1 = Math.min(h2 - 1, y0 + 1);
                o[y * w + x] = (a[i] * (1 - ax) + a[y0 * w2 + x1] * ax) * (1 - ay)
                        + (a[y1 * w2 + x0] * (1 - ax) + a[y1 * w2 + x1] * ax) * ay;
            }
        });
        return o;
    }

    // ------------------------------------------------------------------ tools

    /** Half-resolution green (mean of the two greens of each quad, black removed). */
    private float[] greenHalf(Raw r) {
        float[] g = new float[W2 * H2];
        int g1 = -1, g2 = -1;
        for (int i = 0; i < 4; i++) {
            if (color[i] == 1) {
                if (g1 < 0) g1 = i;
                else g2 = i;
            }
        }
        final int a = g1, b = g2;
        final float bk = (black[a] + black[b]) / 2;
        IntStream.range(0, H2).parallel().forEach(y -> {
            for (int x = 0; x < W2; x++) {
                g[y * W2 + x] = (r.get(2 * y + a / 2, 2 * x + a % 2) + r.get(2 * y + b / 2, 2 * x + b % 2)) / 2f - bk;
            }
        });
        return g;
    }

    /** Gaussian-like blur: three running-sum box passes per direction (O(1) per pixel). */
    private static float[] gauss(float[] in, int w, int h, float sigma) {
        int r = Math.max(1, Math.round(sigma * 0.8f));
        float[] a = in.clone(), b = new float[in.length];
        for (int pass = 0; pass < 3; pass++) {
            boxH(a, b, w, h, r);
            boxV(b, a, w, h, r);
        }
        return a;
    }

    private static void boxH(float[] in, float[] out, int w, int h, int r) {
        float n = 2 * r + 1;
        IntStream.range(0, h).parallel().forEach(y -> {
            int o = y * w;
            float s = 0;
            for (int i = -r; i <= r; i++) s += in[o + Math.min(w - 1, Math.max(0, i))];
            for (int x = 0; x < w; x++) {
                out[o + x] = s / n;
                s += in[o + Math.min(w - 1, x + r + 1)] - in[o + Math.max(0, x - r)];
            }
        });
    }

    private static void boxV(float[] in, float[] out, int w, int h, int r) {
        float n = 2 * r + 1;
        IntStream.range(0, w).parallel().forEach(x -> {
            float s = 0;
            for (int i = -r; i <= r; i++) s += in[Math.min(h - 1, Math.max(0, i)) * w + x];
            for (int y = 0; y < h; y++) {
                out[y * w + x] = s / n;
                s += in[Math.min(h - 1, y + r + 1) * w + x] - in[Math.max(0, y - r) * w + x];
            }
        });
    }

    private static float[] erode3(float[] in, int w, int h) {
        float[] out = new float[in.length];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float m = Float.MAX_VALUE;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + dx));
                        m = Math.min(m, in[yy * w + xx]);
                    }
                }
                out[y * w + x] = m;
            }
        });
        return out;
    }

    private static float[] down(float[] in, int w, int h, int f) {
        int w2 = w / f, h2 = h / f;
        float[] out = new float[w2 * h2];
        IntStream.range(0, h2).parallel().forEach(y -> {
            for (int x = 0; x < w2; x++) {
                float s = 0;
                for (int dy = 0; dy < f; dy++) for (int dx = 0; dx < f; dx++) s += in[(y * f + dy) * w + x * f + dx];
                out[y * w2 + x] = s / (f * f);
            }
        });
        return out;
    }

    /** Per-tile shift (half-res px) of g relative to ref: int[ty][tx][2]. */
    private int[][][] align(float[] ref, float[] g) {
        return align(ref, g, 0f, null);
    }

    /**
     * Per-tile shift of g relative to ref. A tile keeps the global shift unless its own search is
     * clearly better (> 3 % less difference: flat or noise-only tiles have no reliable minimum) and
     * the reference tile has signal (mean >= minSig; a black reference tile is pure noise - random
     * shifts there doubled text in the longer frames). reliable[ty][tx] (if given): own tile shift.
     */
    final int[] globalShift = new int[2];   // last align(): global shift (bench diagnostics)
    double globalGain;                      // last align(): error reduction of the refined global shift
    static final double GLOBAL_BETTER = Double.parseDouble(System.getProperty("globalBetter", "0.97"));

    private int[][][] align(float[] ref, float[] g, float minSig, boolean[][] reliable) {
        // global: 1/8 of the half-res image, +/-6 px there (+/-48 half-res px)
        final int f = 8, ws = W2 / f, hs = H2 / f;
        float[] rs = down(ref, W2, H2, f), gs = down(g, W2, H2, f);
        int bestY = 0, bestX = 0;
        double best = Double.MAX_VALUE;
        for (int sy = -6; sy <= 6; sy++) {
            for (int sx = -6; sx <= 6; sx++) {
                double e = 0;
                int n = 0;
                for (int y = 8; y < hs - 8; y++) {
                    for (int x = 8; x < ws - 8; x++) {
                        e += Math.abs(rs[y * ws + x] - gs[(y + sy) * ws + x + sx]);
                        n++;
                    }
                }
                e /= n;
                if (e < best) {
                    best = e;
                    bestY = sy;
                    bestX = sx;
                }
            }
        }
        int gy0 = bestY * f, gx0 = bestX * f;
        if ((!astro || Boolean.getBoolean("astroGlobal")) && !Boolean.getBoolean("oldGlobal")) {   // (astro: 2 s after the tap, stars move: unchanged)
            // REFINE the global shift to whole quads (item 7, 2026-11-17): the coarse search above
            // has steps of 8 quads, so a tap on the tripod (3-4 quads) gave 0 - the dark tiles kept 0
            // while the textured ones hit their +-SEARCH limit at -3: a shift field 0 .. -3 blended
            // across the tiles misaligned a longer frame at a paper edge (dotted coloured edge).
            // Full half-res, every 4th pixel, +-R around the coarse result.
            final int R = 8, st = Integer.getInteger("globalStep", 8), cy = gy0, cx = gx0;
            final double[] err = new double[(2 * R + 1) * (2 * R + 1)];
            IntStream.range(0, err.length).parallel().forEach(c -> {
                int sy = cy + c / (2 * R + 1) - R, sx = cx + c % (2 * R + 1) - R;
                double e = 0;
                int n = 0;
                for (int y = 64; y < H2 - 64; y += st) {
                    int yy = y + sy;
                    if (yy < 0 || yy >= H2) continue;
                    for (int x = 64; x < W2 - 64; x += st) {
                        int xx = x + sx;
                        if (xx < 0 || xx >= W2) continue;
                        e += Math.abs(ref[y * W2 + x] - g[yy * W2 + xx]);
                        n++;
                    }
                }
                err[c] = n > 0 ? e / n : Double.MAX_VALUE;
            });
            int bc = 0;
            for (int c = 1; c < err.length; c++) if (err[c] < err[bc]) bc = c;
            // only when clearly better than the coarse result (like the tiles): in a noise-only
            // astro sky the minimum is flat and a noise dip gave -1,2 quads on a tripod
            int c0 = R * (2 * R + 1) + R;
            globalGain = err[c0] > 0 ? 1 - err[bc] / err[c0] : 0;
            if (err[bc] <= err[c0] * GLOBAL_BETTER) {
                gy0 = cy + bc / (2 * R + 1) - R;
                gx0 = cx + bc % (2 * R + 1) - R;
            }
        }
        final int gy = gy0, gx = gx0;
        globalShift[0] = gy;
        globalShift[1] = gx;
        final int ty = H2 / TILE, tx = W2 / TILE;
        int[][][] sh = new int[ty][tx][2];
        IntStream.range(0, ty * tx).parallel().forEach(t -> {
            int i = t / tx, j = t % tx;
            double be = Double.MAX_VALUE, eg = 0, sum = 0;
            int by = gy, bx = gx;
            for (int y = 0; y < TILE; y += 2) {               // tile signal + error at the global shift
                int ry = i * TILE + y, yy = Math.min(H2 - 1, Math.max(0, ry + gy));
                for (int x = 0; x < TILE; x += 2) {
                    int rx = j * TILE + x, xx = Math.min(W2 - 1, Math.max(0, rx + gx));
                    sum += ref[ry * W2 + rx];
                    eg += Math.abs(ref[ry * W2 + rx] - g[yy * W2 + xx]);
                }
            }
            if (sum / (TILE * TILE / 4.0) < minSig) {
                sh[i][j][0] = gy;
                sh[i][j][1] = gx;
                return;
            }
            for (int sy = gy - SEARCH; sy <= gy + SEARCH; sy++) {
                for (int sx = gx - SEARCH; sx <= gx + SEARCH; sx++) {
                    double e = 0;
                    for (int y = 0; y < TILE; y += 2) {
                        int ry = i * TILE + y, yy = Math.min(H2 - 1, Math.max(0, ry + sy));
                        for (int x = 0; x < TILE; x += 2) {
                            int rx = j * TILE + x, xx = Math.min(W2 - 1, Math.max(0, rx + sx));
                            e += Math.abs(ref[ry * W2 + rx] - g[yy * W2 + xx]);
                        }
                    }
                    if (e < be) {
                        be = e;
                        by = sy;
                        bx = sx;
                    }
                }
            }
            if (be > eg * 0.97) {                             // not clearly better: the global shift
                by = gy;
                bx = gx;
            } else if (reliable != null) {
                reliable[i][j] = true;
            }
            sh[i][j][0] = by;
            sh[i][j][1] = bx;
        });
        return sh;
    }

    // tolerances tuned on the bench (2026-11-16): 2/4 sigma (Google-like) rejected too much for us - we
    // have ONE short reference (Google: ~12), so a rejected longer frame leaves only that frame's
    // noise (coloured speckles near the image edge of a handheld Auto HDR); 6/12 sigma + 2x local
    // structure keeps the per-pixel decision (no blocky edges) without the speckles
    static final double ROB_LO = Double.parseDouble(System.getProperty("robLo", "6.0"));   // robustness: sigma of the mean difference
    static final double ROB_HI = Double.parseDouble(System.getProperty("robHi", "12.0"));
    static final boolean CLIP_PIXEL = Boolean.getBoolean("clipPixel");   // step 4 test switch
    // ITEM 34 (V0.106): NIGHT averaging of equal frames (people): stricter - a moving mouth / hand passed
    // the 6/12 sigma + 2x structure test (a face's local structure is large); there the reference alone
    // (a bit noisier, but sharp). Set by PhotoBurst for the JPEG / RAW Night mode.
    static volatile boolean nightLook;
    static final double NIGHT_ROB_LO = Double.parseDouble(System.getProperty("nightRobLo", "3.0"));
    static final double NIGHT_ROB_HI = Double.parseDouble(System.getProperty("nightRobHi", "6.0"));
    static final double NIGHT_ROB_STRUCT = Double.parseDouble(System.getProperty("nightRobStruct", "0.5"));

    static double robLo() {
        return nightLook ? NIGHT_ROB_LO : ROB_LO;
    }

    static double robHi() {
        return nightLook ? NIGHT_ROB_HI : ROB_HI;
    }

    static double robStruct() {
        return nightLook ? NIGHT_ROB_STRUCT : ROB_STRUCT;
    }
    static final double ROB_STRUCT = Double.parseDouble(System.getProperty("robStruct", "2.0"));   // share of the local structure variance tolerated



    /**
     * Alignment of a longer frame (reference units, clamped at its clip level cg): to the reference
     * where it has detail, else chained to the previous longer frame. Own method: the clamped copies
     * (3 x 16 MB) are freed on return (memory, 2026-11-17).
     */
    private int[][][] alignLong(float[] refG, float[] g, float cg, float[] prevLong, float prevLongR,
                                int[][][] prevSh, StringBuilder lg) {
        int[][][] sh;
        float[] ra = new float[refG.length], ga = new float[g.length];
        for (int i = 0; i < g.length; i++) {
            ra[i] = Math.min(refG[i], cg);
            ga[i] = Math.min(g[i], cg);
        }
        boolean[][] relRef = new boolean[H2 / TILE][W2 / TILE];
        // the first longer frame: tile alignment to the reference wherever it has some
        // detail (the bright parts, where its data meets the reference's: lamp rim, wood)
        sh = align(ra, ga, prevLong == null ? ALIGN_MIN_FIRST : ALIGN_MIN, relRef);
        if (prevLong != null) {
            // where the reference is too dark: align to the previous longer frame (plenty of
            // signal there) and add its shift -> all longer frames lie exactly on each other
            float[] pa = new float[g.length];
            for (int i = 0; i < g.length; i++) {
                pa[i] = Math.min(prevLong[i], cg);
            }
            int[][][] shRel = align(pa, ga, ALIGN_MIN / prevLongR, null);
            int nChain = 0;
            for (int i = 0; i < sh.length; i++) {
                for (int j = 0; j < sh[0].length; j++) {
                    if (relRef[i][j]) continue;
                    sh[i][j][0] = prevSh[i][j][0] + shRel[i][j][0];
                    sh[i][j][1] = prevSh[i][j][1] + shRel[i][j][1];
                    nChain++;
                }
            }
            lg.append(String.format(java.util.Locale.US, " [chain %d%%]", 100 * nChain / (sh.length * sh[0].length)));
        }
        return sh;
    }

    /**
     * Per-pixel robustness weights of frame k (STEP 3) - its own method so that its temporary planes
     * (~120 MB: colour planes, 3x3 means, masks) are freed on return (memory: the phone allows 512 MB,
     * the merge of 4 frames ran out, 2026-11-17).
     */
    // the GPU of a photo whose develop never ran (failure): closed when the next merge starts
    private static Gpu openGpu;

    private void closeGpu() {
        if (gpu != null) {
            try {
                gpu.close();
            } catch (Throwable ignored) {
            }
            synchronized (MergeCore.class) {
                if (openGpu == gpu) openGpu = null;
            }
            gpu = null;
        }
    }

    private static void closeStaleGpu() {
        Gpu g;
        synchronized (MergeCore.class) {
            g = openGpu;
            openGpu = null;
        }
        if (g != null) {
            try {
                g.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** The 2x2 positions of R, G1, G2, B (quadPlanes order). */
    private int[] bayerOrder() {
        int pr = 0, pb = 0, g1 = -1, g2 = -1;
        for (int i = 0; i < 4; i++) {
            if (color[i] == 0) pr = i;
            else if (color[i] == 2) pb = i;
            else if (g1 < 0) g1 = i;
            else g2 = i;
        }
        return new int[]{pr, g1, g2, pb};
    }

    private float[] robustWeights(Raw r, int[][][] sh, float rk, int k, int refIdx, boolean isLong, float clipG,
                                  float gbk, float[][] refPl, StringBuilder lg) {
        return robustWeightsCpu(r, sh, rk, k, refIdx, isLong, clipG, gbk, refPl, lg);
    }

    // GPU planes of the pre-steps (GpuRobust AUX): reference green, frame green, previous longer
    // frame, chain frame
    static final int GP_REF = 7, GP_G = 8, GP_PREV = 9, GP_CHAIN = 10;

    /** Result of a frame's pre-steps (fixes, green, ratio, alignment). */
    private static final class Pre {
        Raw r;
        float[] g;
        float rk = 1f;
        int[][][] sh;
        int[] blackAdd = new int[4];
        int warm;
        long tRead, tAlign;
    }

    private static final class PreState {
        float[] chainG;
        float chainR = 1f;
        float[] prevLong;
        float prevLongR = 1f;
        int[][][] prevSh;
    }

    private static final class GPreState {
        int chainP = GP_REF;
        float chainR = 1f;
        boolean havePrev;
        float prevLongR = 1f;
        int[][][] prevSh;
    }

    /** The CPU pre-steps of frame k (the merge loop as before). */
    private Pre cpuPreFrame(Frames frames, int k, short[] buf, boolean bufRead, float[] expRatio, float[] refG,
                            PreState st, StringBuilder lg, StringBuilder blackLog, int[] warmBox) {
        Pre p = new Pre();
        long ts = now();
        if (!bufRead) frames.read(k, buf);
        p.warm = fixWarm(buf, k);
        warmBox[0] += p.warm;
        p.blackAdd = fixBlack(buf, k, blackLog);
        Raw r = new Raw(buf);
        final float[] g = greenHalf(r);
        float rNom = expRatio != null && expRatio[k] > 1.2f ? expRatio[k] : 1f;
        if (rNom > 1f) {
            // the real ratio, measured against the previous frame of the chain (very short
            // exposures deviate from their nominal value: 1/3900 s was 6 % brighter -> the
            // longer frames looked like motion and were rejected in patches)
            float m = measuredRatio(g, st.chainG, 0.82f * white - (black[0] + black[1] + black[2] + black[3]) / 4);
            float rMeas = m > 0 ? st.chainR * m : rNom;
            if (Math.abs(rMeas / rNom - 1) > 0.25f) rMeas = rNom;   // implausible: keep the nominal
            lg.append(String.format(java.util.Locale.US, " [x%.1f->%.1f]", rNom, rMeas));
            st.chainG = g.clone();
            st.chainR = rMeas;
            rNom = rMeas;
        }
        final float rk = rNom;
        final boolean isLong = rk > 1f;
        final float gbk = (black[0] + black[1] + black[2] + black[3]) / 4;
        final float clipLo = 0.82f * white;
        int[][][] sh;
        p.tRead = now() - ts;
        ts = now();
        if (isLong) {
            // to the reference exposure; align on images clamped at the long frame's clip level
            final float cg = (clipLo - gbk) / rk;
            for (int i = 0; i < g.length; i++) g[i] /= rk;
            sh = alignLong(refG, g, cg, st.prevLong, st.prevLongR, st.prevSh, lg);
            st.prevLong = g.clone();
            st.prevLongR = rk;
            st.prevSh = sh;
        } else {
            sh = align(refG, g);
        }
        if (Boolean.getBoolean("zeroShift")) {            // bench: tripod test, no alignment
            for (int[][] row : sh) for (int[] v : row) { v[0] = 0; v[1] = 0; }
        }
        if (Boolean.getBoolean("shiftStats")) {           // bench: how many tiles are shifted
            int nz = 0, tot = 0;
            for (int[][] row : sh) for (int[] v : row) { tot++; if (v[0] != 0 || v[1] != 0) nz++; }
            lg.append(String.format(java.util.Locale.US, " [shifted tiles %d of %d, global %d,%d gain %.1f%%]", nz, tot,
                    globalShift[0], globalShift[1], 100 * globalGain));
            java.util.Map<String, Integer> hist = new java.util.TreeMap<>();
            for (int[][] row : sh) for (int[] v : row) hist.merge(v[0] + "," + v[1], 1, Integer::sum);
            lg.append(" ").append(hist);
            StringBuilder rows = new StringBuilder(" rows(dy,dx):");
            for (int[][] row : sh) {
                double sy = 0, sx = 0;
                for (int[] v : row) { sy += v[0]; sx += v[1]; }
                rows.append(String.format(java.util.Locale.US, " %.1f,%.1f", sy / row.length, sx / row.length));
            }
            lg.append(rows);
            if (System.getProperty("dbgTile") != null) {     // DBG item 7: shifts around a quad
                String[] q = System.getProperty("dbgTile").split(",");
                int ty = Integer.parseInt(q[0]) / TILE, tx = Integer.parseInt(q[1]) / TILE;
                StringBuilder sb = new StringBuilder(" [tiles around " + ty + "," + tx + ":");
                for (int a2 = Math.max(0, ty - 2); a2 <= Math.min(sh.length - 1, ty + 2); a2++) {
                    sb.append(" |");
                    for (int b2 = Math.max(0, tx - 2); b2 <= Math.min(sh[0].length - 1, tx + 2); b2++)
                        sb.append(' ').append(sh[a2][b2][0]).append(',').append(sh[a2][b2][1]);
                }
                lg.append(sb).append(']');
            }
        }
        p.tAlign = now() - ts;
        p.r = r;
        p.g = g;
        p.rk = rk;
        p.sh = sh;
        return p;
    }

    /**
     * The same pre-steps on the GPU (the frame is uploaded already): the GPU computes the pixel
     * work (block medians, error sums, ratio samples), the decisions stay here, as in the CPU code.
     * lg / blackLog null: check mode (the CPU logs).
     */
    private Pre gpuPreFrame(int k, float[] expRatio, GPreState st, StringBuilder lg, StringBuilder blackLog,
                            int[] warmBox) {
        Pre p = new Pre();
        long ts = now();
        long tl = now();
        long[] fe = frameExposureNs;
        if (!Boolean.getBoolean("noWarmFix") && fe != null && k < fe.length && fe[k] >= WARM_FROM_NS) {
            p.warm = gpu.fixWarm(WARM_ABS, WARM_REL, WARM_NEIGHBOUR);
            if (lg != null) warmBox[0] += p.warm;
        }
        gtWarm += gpuLap(tl);
        tl = now();
        // fixBlack: the 2 % point of the 16x16 block medians per channel
        final int B = 16;
        int bw = W / 2 / B, bh = H / 2 / B;
        if (!Boolean.getBoolean("noBlackFix") && bw >= 4 && bh >= 4) {
            // PRE-TEST (item 25): the fix needs the 2 % point of the block medians below black - 1.5; a
            // block median (128th + 129th value) / 2 is below L for sure with >= 129 values < L, maybe with
            // 128, never with fewer. Too few possible blocks in every channel: no fix - the same result
            // as the full medians, without them (normal frames: always)
            float[] L = new float[4];
            for (int d = 0; d < 4; d++) L[d] = black[d] - 1.5f;
            float[] cb = gpu.blockBelow(bw, bh, L);
            int nb = bw * bh, need = (int) (nb * 0.02) + 1;
            boolean full = false;
            for (int d = 0; d < 4 && !full; d++) {
                int possible = 0;
                for (int i = 0; i < nb; i++) if (cb[d * nb + i] >= 128f) possible++;
                if (possible >= need) full = true;
            }
            float[] med = full ? gpu.blockMedians(bw, bh) : null;
            boolean any = false;
            for (int d = 0; d < 4 && med != null; d++) {
                float[] md = Arrays.copyOfRange(med, d * bw * bh, (d + 1) * bw * bh);
                Arrays.sort(md);
                float zero = md[(int) (md.length * 0.02)];
                float off = black[d] - zero;
                if (off > 1.5f) {
                    p.blackAdd[d] = Math.round(off);
                    any = true;
                }
            }
            if (any) {
                gpu.addBlack(p.blackAdd, white);
                if (blackLog != null) blackLog.append(String.format(java.util.Locale.US, ", frame %d black +%d/%d/%d/%d", k,
                        p.blackAdd[0], p.blackAdd[1], p.blackAdd[2], p.blackAdd[3]));
            }
        }
        gtBlack += gpuLap(tl);
        tl = now();
        gpu.green(GP_G);
        gtGreen += gpuLap(tl);
        tl = now();
        float rNom = expRatio != null && expRatio[k] > 1.2f ? expRatio[k] : 1f;
        if (rNom > 1f) {
            // measuredRatio: the median of g / chain over valid samples
            int step = Math.max(1, W2 * H2 / 400_000);
            int count = (W2 * H2 + step - 1) / step;
            float[] s = gpu.ratioSamples(GP_G, st.chainP, 0.82f * white - (black[0] + black[1] + black[2] + black[3]) / 4,
                    step, count);
            float[] rr = new float[count];
            int nn = 0;
            for (int i = 0; i < count; i++) if (s[i] >= 0) rr[nn++] = s[i];
            float m = 0;
            if (nn >= 2000) {
                Arrays.sort(rr, 0, nn);
                m = rr[nn / 2];
            }
            float rMeas = m > 0 ? st.chainR * m : rNom;
            if (Math.abs(rMeas / rNom - 1) > 0.25f) rMeas = rNom;
            if (lg != null) lg.append(String.format(java.util.Locale.US, " [x%.1f->%.1f]", rNom, rMeas));
            gpu.copyPlane(GP_G, GP_CHAIN);
            st.chainP = GP_CHAIN;
            st.chainR = rMeas;
            rNom = rMeas;
        }
        gtRatio += gpuLap(tl);
        final float rk = rNom;
        final float gbk = (black[0] + black[1] + black[2] + black[3]) / 4;
        p.tRead = now() - ts;
        ts = now();
        int[][][] sh;
        if (rk > 1f) {
            final float cg = (0.82f * white - gbk) / rk;
            gpu.divPlane(GP_G, rk);
            boolean[][] relRef = new boolean[H2 / TILE][W2 / TILE];
            sh = alignGpu(GP_REF, GP_G, cg, st.havePrev ? ALIGN_MIN : ALIGN_MIN_FIRST, relRef);
            if (st.havePrev) {
                int[][][] shRel = alignGpu(GP_PREV, GP_G, cg, ALIGN_MIN / st.prevLongR, null);
                int nChain = 0;
                for (int i = 0; i < sh.length; i++) {
                    for (int j = 0; j < sh[0].length; j++) {
                        if (relRef[i][j]) continue;
                        sh[i][j][0] = st.prevSh[i][j][0] + shRel[i][j][0];
                        sh[i][j][1] = st.prevSh[i][j][1] + shRel[i][j][1];
                        nChain++;
                    }
                }
                if (lg != null) lg.append(String.format(java.util.Locale.US, " [chain %d%%]", 100 * nChain / (sh.length * sh[0].length)));
            }
            gpu.copyPlane(GP_G, GP_PREV);
            st.havePrev = true;
            st.prevLongR = rk;
            st.prevSh = sh;
        } else {
            sh = alignGpu(GP_REF, GP_G, 3e38f, 0f, null);
        }
        if (Boolean.getBoolean("zeroShift")) {
            for (int[][] row : sh) for (int[] v : row) { v[0] = 0; v[1] = 0; }
        }
        p.tAlign = now() - ts;
        gtAlign += p.tAlign;
        p.rk = rk;
        p.sh = sh;
        return p;
    }

    /** align() with the GPU's error sums (same search and rules; clamp = the longer frame's clip level). */
    private int[][][] alignGpu(int refP, int gP, float clamp, float minSig, boolean[][] reliable) {
        final int f = 8, ws = W2 / f, hs = H2 / f;
        int rows = hs - 16;
        float[] ce = gpu.alignCoarse(refP, gP, clamp, rows);
        long nC = (long) rows * (ws - 16);
        int bestY = 0, bestX = 0;
        double best = Double.MAX_VALUE;
        for (int sy = -6; sy <= 6; sy++) {
            for (int sx = -6; sx <= 6; sx++) {
                int c = (sy + 6) * 13 + sx + 6;
                double e = 0;
                for (int y = 0; y < rows; y++) e += ce[c * rows + y];
                e /= nC;
                if (e < best) {
                    best = e;
                    bestY = sy;
                    bestX = sx;
                }
            }
        }
        int gy0 = bestY * f, gx0 = bestX * f;
        if ((!astro || Boolean.getBoolean("astroGlobal")) && !Boolean.getBoolean("oldGlobal")) {
            final int R = 8, st = Integer.getInteger("globalStep", 8), cy = gy0, cx = gx0;
            int nRows = 0;
            for (int y = 64; y < H2 - 64; y += st) nRows++;
            float[] re = gpu.alignRefine(refP, gP, clamp, cy, cx, R, st, nRows);
            final double[] err = new double[(2 * R + 1) * (2 * R + 1)];
            for (int c = 0; c < err.length; c++) {
                double e = 0;
                long n = 0;
                for (int y = 0; y < nRows; y++) {
                    e += re[(c * nRows + y) * 2];
                    n += (long) re[(c * nRows + y) * 2 + 1];
                }
                err[c] = n > 0 ? e / n : Double.MAX_VALUE;
            }
            int bc = 0;
            for (int c = 1; c < err.length; c++) if (err[c] < err[bc]) bc = c;
            int c0 = R * (2 * R + 1) + R;
            globalGain = err[c0] > 0 ? 1 - err[bc] / err[c0] : 0;
            if (err[bc] <= err[c0] * GLOBAL_BETTER) {
                gy0 = cy + bc / (2 * R + 1) - R;
                gx0 = cx + bc % (2 * R + 1) - R;
            }
        }
        final int gy = gy0, gx = gx0;
        globalShift[0] = gy;
        globalShift[1] = gx;
        final int ty = H2 / TILE, tx = W2 / TILE, ns = (2 * SEARCH + 1) * (2 * SEARCH + 1);
        float[] te = gpu.alignTiles(refP, gP, clamp, gy, gx, SEARCH);
        int[][][] sh = new int[ty][tx][2];
        for (int t = 0; t < ty * tx; t++) {
            int i = t / tx, j = t % tx;
            double sum = te[ty * tx * ns + t];
            if (sum / (TILE * TILE / 4.0) < minSig) {
                sh[i][j][0] = gy;
                sh[i][j][1] = gx;
                continue;
            }
            double be = Double.MAX_VALUE, eg = te[t * ns + SEARCH * (2 * SEARCH + 1) + SEARCH];
            int by = gy, bx = gx;
            for (int s = 0; s < ns; s++) {
                double e = te[t * ns + s];
                if (e < be) {
                    be = e;
                    by = gy - SEARCH + s / (2 * SEARCH + 1);
                    bx = gx - SEARCH + s % (2 * SEARCH + 1);
                }
            }
            if (be > eg * 0.97) {
                by = gy;
                bx = gx;
            } else if (reliable != null) {
                reliable[i][j] = true;
            }
            sh[i][j][0] = by;
            sh[i][j][1] = bx;
        }
        return sh;
    }


    /** Smoothed brightest channel / luminance of the half-res colour (own method: its planes are freed on return). */
    private float[] toneRatioOf(float[] m, int fr, int fb, int fg1, int fg2) {
        float[] cr = new float[W2 * H2], cg = new float[W2 * H2], cb = new float[W2 * H2];
        IntStream.range(0, H2).parallel().forEach(qy -> {
            for (int qx = 0; qx < W2; qx++) {
                int o = 2 * qy * W + 2 * qx, i = qy * W2 + qx;
                float r = m[o + (fr / 2) * W + fr % 2], b = m[o + (fb / 2) * W + fb % 2];
                float g = (m[o + (fg1 / 2) * W + fg1 % 2] + m[o + (fg2 / 2) * W + fg2 % 2]) / 2;
                cr[i] = ccm[0] * r + ccm[1] * g + ccm[2] * b;
                cg[i] = ccm[3] * r + ccm[4] * g + ccm[5] * b;
                cb[i] = ccm[6] * r + ccm[7] * g + ccm[8] * b;
            }
        });
        float[] sr = gauss(cr, W2, H2, TONE_SIGMA), sg = gauss(cg, W2, H2, TONE_SIGMA), sb = gauss(cb, W2, H2, TONE_SIGMA);
        final float[] tr = new float[W2 * H2];
        IntStream.range(0, H2).parallel().forEach(qy -> {
            for (int qx = 0; qx < W2; qx++) {
                int i = qy * W2 + qx;
                float r = Math.max(0, sr[i]), g = Math.max(0, sg[i]), b = Math.max(0, sb[i]);
                float lu = 0.2126f * r + 0.7152f * g + 0.0722f * b, mx = Math.max(r, Math.max(g, b));
                tr[i] = lu > 1e-6f ? Math.min(TONE_RATIO_MAX, Math.max(1f, mx / lu)) : 1f;
            }
        });
        return tr;
    }

    private static int gaussR(float sigma) {             // the box radius of gauss()
        return Math.max(1, Math.round(sigma * 0.8f));
    }

    /**
     * Frame k on the GPU: weights (unless natK), the longer frame's maps, the accumulation - the
     * CPU merge loop step by step. Returns the mean weight (log).
     */
    private double gpuFrame(int[][][] sh, float rk, int k, int refIdx, boolean isLong, boolean natK,
                            float clipG, float gbk, StringBuilder lg) {
        gpu.setShifts(sh);
        double wm = 1;
        final double kS2 = noiseS[Math.min(k, noiseS.length - 1)], kO2 = noiseO[Math.min(k, noiseO.length - 1)];
        final double RR = white - gbk;
        if (!natK) {
            final double rS = noiseS[refIdx], rO = noiseO[refIdx];
            final boolean doNear = isLong && !Boolean.getBoolean("oldStruct");
            final boolean doClipQ = isLong && !Boolean.getBoolean("oldClipRob");
            java.nio.FloatBuffer smp = gpu.samples(rk, clipG, doNear);
            double[] bias = biasOf(smp);
            if (lg != null) lg.append(String.format(java.util.Locale.US, " [bias %.2f/%.2f/%.2f]", bias[0], bias[1], bias[2]));
            wm = gpu.weights(bias, doNear, doClipQ, rS, rO, kS2, kO2, RR, rk, Boolean.getBoolean("oldErode"));
        }
        if (isLong) {
            int[] ds = bayerOrder();
            gpu.longMaps(rk, CLIP_USE_LO * white, CLIP_USE_HI * white, CLIP_GROW, gaussR(CLIP_FEATHER),
                    !Boolean.getBoolean("oldFeather"), CLIP_PIXEL, gaussR(1.5f), (black[ds[1]] + black[ds[2]]) / 2);
        }
        gpu.accumulate(isLong, natK, rk, kS2, kO2, RR);
        return wm;
    }

    /** The black-level bias from the GPU's samples (same rule as robustWeightsCpu). */
    private double[] biasOf(java.nio.FloatBuffer smp) {
        // the same bias as the CPU (median of the dark sample differences, per colour)
        final double[] bias = new double[3];
        final int M = (W2 * H2 + 6) / 7;
        for (int c = 0; c < 3; c++) {
            int nS = 0;
            float[] s = new float[Math.max(1, W2 * H2 / 49)];
            for (int j = 0; j < M && nS < s.length; j++) {
                float mr0 = smp.get(j * 6 + 2 * c), mf0 = smp.get(j * 6 + 2 * c + 1);
                if (mr0 < 8f && mf0 < 8f) s[nS++] = mr0 - mf0;
            }
            if (nS > 200) {
                Arrays.sort(s, 0, nS);
                bias[c] = s[nS / 2];
            }
        }
        return bias;
    }

    private float[] robustWeightsCpu(Raw r, int[][][] sh, float rk, int k, int refIdx, boolean isLong, float clipG,
                                     float gbk, float[][] refPl, StringBuilder lg) {
            // STEP 3 - per-pixel robustness (after Wronski et al. 2019, Google Super Res / HDR+
            // with bracketing): per Bayer quad, the frame (sampled with the same sub-pixel shift
            // as the accumulation, scaled to the reference) against the reference: local 3x3
            // means of R, G, B; their difference judged against the expected noise of that
            // difference (the camera's noise model, both frames) plus a little of the local
            // structure (sub-pixel misalignment on edges). <= ROB_LO sigma: used, >= ROB_HI:
            // not used. Replaces the quarter-resolution tile weights (blocky edges at motion).
            // A clipped longer frame fails by itself (its value is too low).
            final float[][] fPl = quadPlanes(r, sh, rk, 0f, 0f);
            final double rS = noiseS[refIdx], rO = noiseO[refIdx];
            final double kS2 = noiseS[Math.min(k, noiseS.length - 1)], kO2 = noiseO[Math.min(k, noiseO.length - 1)];
            final double RR = white - gbk;
            // systematic offset between the frames (black level ~1 DN apart: in the dark parts
            // that alone was "5 sigma" against the reference noise and threw the longer frame
            // out exactly where it is needed): median difference in the darkest quads, per colour
            final double[] bias = new double[3];
            for (int c = 0; c < 3; c++) {
                int nS = 0;
                float[] smp = new float[Math.max(1, W2 * H2 / 49)];
                float lim = 8f;                                   // DN: dark (reference noise dominates)
                for (int i = 0; i < W2 * H2 && nS < smp.length; i += 7) {
                    int qx = i % W2, qy = i / W2;
                    float mr0 = m3(refPl[c], qx, qy), mf0 = m3(fPl[c], qx, qy);
                    if (mr0 < lim && mf0 < lim) smp[nS++] = mr0 - mf0;
                }
                if (nS > 200) {
                    Arrays.sort(smp, 0, nS);
                    bias[c] = smp[nS / 2];
                }
            }
            lg.append(String.format(java.util.Locale.US, " [bias %.2f/%.2f/%.2f]", bias[0], bias[1], bias[2]));
            // next to the longer frame's (near) clipped parts the structure tolerance is off: at
            // a tiny bright light the local variance is huge, so a longer frame whose light is
            // displaced / blurred by 1 px (x ratio up to 250) passed and put dark dots into the
            // light's core (astro 2026-11-17); edges there are covered by the clip map anyway
            final boolean[] nearClip = new boolean[W2 * H2];
            final boolean[] clipQ = isLong && !Boolean.getBoolean("oldClipRob") ? new boolean[W2 * H2] : null;
            if (isLong && !Boolean.getBoolean("oldStruct")) {
                final float cl = clipG;
                final int RAD = 2;
                IntStream.range(0, H2).parallel().forEach(y -> {
                    for (int x = 0; x < W2; x++) {
                        float m = Math.max(fPl[0][y * W2 + x], Math.max(fPl[1][y * W2 + x], fPl[2][y * W2 + x]));
                        if (m < cl) continue;
                        if (clipQ != null) clipQ[y * W2 + x] = true;
                        for (int yy = Math.max(0, y - RAD); yy <= Math.min(H2 - 1, y + RAD); yy++)
                            for (int xx = Math.max(0, x - RAD); xx <= Math.min(W2 - 1, x + RAD); xx++)
                                nearClip[yy * W2 + xx] = true;
                    }
                });
            }
            final float[] rob = new float[W2 * H2];
            IntStream.range(0, H2).parallel().forEach(y -> {
                for (int x = 0; x < W2; x++) {
                    int i = y * W2 + x;
                    final double st = nearClip[i] ? 0 : robStruct();
                    double dsum = 0;
                    for (int c = 0; c < 3; c++) {
                        double mr = m3(refPl[c], x, y), mf = m3(fPl[c], x, y);
                        double d = mr - mf - bias[c];
                        double vr = (rS * Math.max(0, mr) / RR + rO) * RR * RR;
                        double own = Math.max(0, mf) * rk;
                        double vf = (kS2 * own / RR + kO2) * RR * RR / (rk * rk);
                        double vn = (vr + vf) / 9.0;                    // variance of a 3x3 mean difference
                        double vs = Math.max(0, v3(refPl[c], x, y, mr) - vr);    // structure (beyond the noise)
                        dsum += d * d / (vn + st * vs);
                    }
                    double dd = Math.sqrt(dsum / 3);
                    if (clipQ != null && clipQ[i]) {
                        // the longer frame is clipped here: excluded by the clip map (use), not by
                        // the robustness - its failure here spread by the erosion below threw the
                        // frame out of the unclipped ring around every light (only the noisy
                        // short reference left there: dark dots in the astro lamp glows)
                        rob[i] = 1;
                        continue;
                    }
                    if (nearClip[i]) {
                        // ... and the quad itself, not only the 3x3 means: a light blurred by
                        // 1 px keeps its 3x3 mean, so the mean test cannot see it
                        double qsum = 0;
                        for (int c = 0; c < 3; c++) {
                            double pr = refPl[c][i], pf = fPl[c][i];
                            double d = pr - pf - bias[c];
                            double vr = (rS * Math.max(0, pr) / RR + rO) * RR * RR;
                            double vf = (kS2 * Math.max(0, pf) * rk / RR + kO2) * RR * RR / (rk * rk);
                            qsum += d * d / (vr + vf);
                        }
                        dd = Math.max(dd, Math.sqrt(qsum / 3));
                    }
                    rob[i] = (float) Math.min(1, Math.max(0, (robHi() - dd) / (robHi() - robLo())));
                }
            });
            if (Boolean.getBoolean("robStats")) {            // bench diagnostics: accepted share, median distance
                double acc1 = 0;
                for (int i = 0; i < rob.length; i += 13) acc1 += rob[i];
                lg.append(String.format(java.util.Locale.US, " [rob %.2f, medD %.2f]", acc1 / ((rob.length + 12) / 13),
                        medianDist(refPl, fPl, bias, rS, rO, kS2, kO2, RR, rk)));
            }
            if (System.getProperty("dbgRow") == null) {      // planes no longer needed: free 96 MB
                for (int c = 0; c < 3; c++) fPl[c] = null;     // before the blur (memory, 2026-11-17)
            }
            float[] er = erode3(rob, W2, H2);
            // next to clipped parts the quads are judged one by one (above): no erosion there -
            // it widened a 1-quad disagreement at a distant lamp (air shimmer: the short
            // reference catches the lamp at one instant) into a ring left to the noisy reference
            if (!Boolean.getBoolean("oldErode")) {
                for (int i = 0; i < er.length; i++) if (nearClip[i]) er[i] = rob[i];
            }
            float[] re = gauss(er, W2, H2, 0.7f);
            // next to clipped parts the blur must not carry weight back into a rejected quad
            // (x ratio up to 250: a little weight there is still far more than the reference)
            for (int i = 0; i < re.length; i++) if (nearClip[i]) re[i] = Math.min(re[i], er[i]);
            if (System.getProperty("dbgRow") != null) {          // DBG item 7
                String[] q = System.getProperty("dbgRow").split(",");
                int qy = Integer.parseInt(q[0]), qa = Integer.parseInt(q[1]), qb = Integer.parseInt(q[2]);
                StringBuilder sb = new StringBuilder("DBG k=" + k + " rk=" + rk + (char) 10);
                for (int qx = qa; qx <= qb; qx++) {
                    int i = qy * W2 + qx;
                    sb.append(String.format(java.util.Locale.US, "  qx %d rob %.2f er %.2f re %.2f %s%s refG %.1f fG %.1f refM %.1f fM %.1f refVar %.0f%n",
                            qx, rob[i], er[i], re[i], nearClip[i] ? "n" : "-", clipQ != null && clipQ[i] ? "c" : "-",
                            refPl[1][i], fPl[1][i], m3(refPl[1], qx, qy), m3(fPl[1], qx, qy),
                            v3(refPl[1], qx, qy, m3(refPl[1], qx, qy))));
                }
                System.out.print(sb);
            }
            return re;
    }

    /** The old quarter-resolution motion weights (only without the noise model / with natural motion). */
    private float[] oldWeights(int[][][] sh, float[] g, float rk, boolean isLong, float clipG, float[] refG,
                               float[] refQ, float[] edgeQ) {
        float[] diff = new float[W2 * H2];
        IntStream.range(0, H2).parallel().forEach(y -> {
            for (int x = 0; x < W2; x++) {
                int yy = Math.min(H2 - 1, Math.max(0, y + shiftOf(sh, y, x, 0)));
                int xx = Math.min(W2 - 1, Math.max(0, x + shiftOf(sh, y, x, 1)));
                float gv = g[yy * W2 + xx];
                // a (near) clipped longer frame says nothing about motion (its value is cut off):
                // no difference there - else whole blocks next to bright edges were rejected
                diff[y * W2 + x] = isLong && gv >= clipG ? 0f
                        : gv - (isLong ? refGm()[y * W2 + x] : refG[y * W2 + x]);
            }
        });
        // motion weights on a quarter-resolution map (2x2 mean of the half-res difference =
        // the noise smoothing; 4x less work than at half resolution, same result in practice)
        final int W4 = W2 / 2, H4 = H2 / 2;
        float[] dq = down(diff, W2, H2, 2);
        float[] db = gauss(dq, W4, H4, 0.8f);
        for (int i = 0; i < db.length; i++) db[i] *= db[i];       // d^2
        // noise variance per brightness level: median of d^2 / 0.455 (sampled)
        float maxLv = 0;
        for (int i = 0; i < refQ.length; i += 23) maxLv = Math.max(maxLv, log2(Math.max(refQ[i], 1)));
        final int NB = 16;
        final float lvMax = maxLv + 1e-3f;
        float[][] samples = new float[NB][];
        int[] cnt = new int[NB];
        for (int i = 0; i < refQ.length; i += 5) cnt[bin(refQ[i], lvMax, NB)]++;
        for (int b = 0; b < NB; b++) samples[b] = new float[cnt[b]];
        Arrays.fill(cnt, 0);
        for (int i = 0; i < refQ.length; i += 5) {
            int b = bin(refQ[i], lvMax, NB);
            samples[b][cnt[b]++] = db[i];
        }
        float[] var = new float[NB];
        for (int b = 0; b < NB; b++) {
            if (samples[b].length > 50) {
                Arrays.sort(samples[b]);
                var[b] = Math.max(1e-3f, samples[b][samples[b].length / 2] / 0.455f);
            } else {
                var[b] = Float.NaN;
            }
        }
        fillNaN(var);
        float[] d2s = gauss(db, W4, H4, 1f);
        db = null;
        float[] w = new float[W4 * H4];
        IntStream.range(0, H4).parallel().forEach(y -> {
            for (int x = 0; x < W4; x++) {
                int i = y * W4 + x;
                if (isLong && refQ[i] < LOW_SIGNAL) {
                    // the reference is too dark to show motion here (pure noise + outliers):
                    // the longer frame is trusted (static assumption in deep shadows)
                    w[i] = 1f;
                    continue;
                }
                float ratio = d2s[i] / (var[bin(refQ[i], lvMax, NB)] + edgeQ[i]);
                w[i] = Math.min(1f, Math.max(0f, (2 * ROBUST - ratio) / ROBUST));
            }
        });
        float[] w4 = erode3(w, W4, H4);
        // back to one weight per Bayer quad (half resolution)
        final float[] we = new float[W2 * H2];
        IntStream.range(0, H2).parallel().forEach(y -> {
            int y4 = Math.min(H4 - 1, y / 2);
            for (int x = 0; x < W2; x++) we[y * W2 + x] = w4[y4 * W4 + Math.min(W4 - 1, x / 2)];
        });
        return we;
    }

    /**
     * One value per Bayer quad for R, G (mean of both), B, black subtracted; with sh the frame is
     * sampled at the smooth sub-pixel shift (as in the accumulation) and divided by scale.
     */
    private float[][] quadPlanes(Raw r, int[][][] sh, float scale, float unusedA, float unusedB) {
        int pr = 0, pb = 0, g1 = -1, g2 = -1;
        for (int i = 0; i < 4; i++) {
            if (color[i] == 0) pr = i;
            else if (color[i] == 2) pb = i;
            else if (g1 < 0) g1 = i;
            else g2 = i;
        }
        final int fr = pr, fb = pb, fg1 = g1, fg2 = g2;
        float[][] out = {new float[W2 * H2], new float[W2 * H2], new float[W2 * H2]};
        IntStream.range(0, H2).parallel().forEach(qy -> {
            for (int qx = 0; qx < W2; qx++) {
                float fy = qy, fx = qx;
                if (sh != null) {
                    fy = Math.max(0, Math.min(H2 - 1.001f, qy + shiftF(sh, qy, qx, 0)));
                    fx = Math.max(0, Math.min(W2 - 1.001f, qx + shiftF(sh, qy, qx, 1)));
                }
                int sy = Math.min((int) fy, H2 - 2), sx = Math.min((int) fx, W2 - 2);
                float ay = fy - sy, ax = fx - sx;
                int[] ds = {fr, fg1, fg2, fb};
                float[] v = new float[4];
                for (int j = 0; j < 4; j++) {
                    int d = ds[j], yy = 2 * sy + d / 2, xx = 2 * sx + d % 2;
                    v[j] = ((1 - ay) * ((1 - ax) * r.get(yy, xx) + ax * r.get(yy, xx + 2))
                            + ay * ((1 - ax) * r.get(yy + 2, xx) + ax * r.get(yy + 2, xx + 2))) - black[d];
                }
                int i = qy * W2 + qx;
                out[0][i] = v[0] / scale;
                out[1][i] = (v[1] + v[2]) / 2 / scale;
                out[2][i] = v[3] / scale;
            }
        });
        return out;
    }

    /** Bench diagnostics: median normalised distance (noise only: ~0.9 when the noise model fits). */
    private double medianDist(float[][] rp, float[][] fp, double[] bias, double rS, double rO,
                              double kS, double kO, double RR, float rk) {
        int n = 0;
        float[] s = new float[W2 * H2 / 17 + 1];
        for (int i = 0; i < W2 * H2 && n < s.length; i += 17) {
            int x = i % W2, y = i / W2;
            double dsum = 0;
            for (int c = 0; c < 3; c++) {
                double mr = m3(rp[c], x, y), mf = m3(fp[c], x, y), d = mr - mf - bias[c];
                double vr = (rS * Math.max(0, mr) / RR + rO) * RR * RR;
                double vf = (kS * Math.max(0, mf) * rk / RR + kO) * RR * RR / (rk * rk);
                double vn = (vr + vf) / 9.0, vs = Math.max(0, v3(rp[c], x, y, mr) - vr);
                dsum += d * d / (vn + robStruct() * vs);
            }
            s[n++] = (float) Math.sqrt(dsum / 3);
        }
        Arrays.sort(s, 0, n);
        return s[n / 2];
    }

    /** 3x3 mean of a quad plane at (x, y), border pixels repeated (= box3). */
    private float m3(float[] p, int x, int y) {
        int xa = Math.max(0, x - 1), xb = Math.min(W2 - 1, x + 1);
        int ya = Math.max(0, y - 1) * W2, yc = y * W2, yb = Math.min(H2 - 1, y + 1) * W2;
        return (p[ya + xa] + p[ya + x] + p[ya + xb] + p[yc + xa] + p[yc + x] + p[yc + xb]
                + p[yb + xa] + p[yb + x] + p[yb + xb]) / 9f;
    }

    /** 3x3 variance (mean of squares - mean^2, >= 0) of a quad plane at (x, y), given its 3x3 mean. */
    private double v3(float[] p, int x, int y, double mean) {
        int xa = Math.max(0, x - 1), xb = Math.min(W2 - 1, x + 1);
        int ya = Math.max(0, y - 1) * W2, yc = y * W2, yb = Math.min(H2 - 1, y + 1) * W2;
        double s = sq(p[ya + xa]) + sq(p[ya + x]) + sq(p[ya + xb]) + sq(p[yc + xa]) + sq(p[yc + x]) + sq(p[yc + xb])
                + sq(p[yb + xa]) + sq(p[yb + x]) + sq(p[yb + xb]);
        return Math.max(0, s / 9 - mean * mean);
    }

    private static double sq(float v) {
        return (double) v * v;
    }

    static final float DEN_K = Float.parseFloat(System.getProperty("denK", "1.5"));   // range: x the remaining noise
    static final float DEN_SPACE = 1.6f;          // spatial sigma, in same-colour pixels (2 px steps)
    static final double DEN_MIN = Double.parseDouble(System.getProperty("denMin", "0"));

    /**
     * Noise reduction that follows the merge: the noise left in each pixel is the reference's noise
     * (camera noise model) / the merge's weight sum there (inverse-variance weights: var = var_ref /
     * wsum). An edge-preserving (bilateral) 5x5 same-colour filter whose range is DEN_K x that
     * noise: strong where the longer frames were rejected (only the short reference: its noise),
     * almost nothing where many frames were averaged - like Google's spatial denoise after the merge.
     * Returns the number of pixels changed.
     */
    private int adaptiveDenoise(float[] m, float[] wsum, double rS, double rO) {
        final float[] src = m.clone();
        final double R = white - (black[0] + black[1] + black[2] + black[3]) / 4;
        final float[] ws = new float[25];
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++)
                ws[(dy + 2) * 5 + dx + 2] = (float) Math.exp(-(dx * dx + dy * dy) / (2.0 * DEN_SPACE * DEN_SPACE));
        int[] cnt = new int[H];
        IntStream.range(4, H - 4).parallel().forEach(y -> {
            for (int x = 4; x < W - 4; x++) {
                int i = y * W + x;
                float bk = black[(y & 1) * 2 + (x & 1)];
                float v = src[i] - bk;
                double var = (rS * Math.max(0, v) / R + rO) * R * R / Math.max(1f, wsum[(y >> 1) * W2 + (x >> 1)]);
                double rng = DEN_K * DEN_K * var;
                if (rng < DEN_MIN) continue;                  // well merged: nothing to do
                double inv = 1.0 / (2 * rng), sw = 0, sv = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    int row = (y + 2 * dy) * W;
                    for (int dx = -2; dx <= 2; dx++) {
                        float u = src[row + x + 2 * dx] - bk;
                        double d = u - v;
                        double w = ws[(dy + 2) * 5 + dx + 2] * Math.exp(-d * d * inv);
                        sw += w;
                        sv += w * u;
                    }
                }
                m[i] = (float) (sv / sw) + bk;
                cnt[y]++;
            }
        });
        int s = 0;
        for (int c : cnt) s += c;
        return s;
    }

    /** 3x3 box mean (edges clamped). */
    private static float[] box3(float[] in, int w, int h) {
        float[] tmp = new float[in.length], out = new float[in.length];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int xa = Math.max(0, x - 1), xb = Math.min(w - 1, x + 1);
                tmp[y * w + x] = (in[y * w + xa] + in[y * w + x] + in[y * w + xb]) / 3;
            }
        });
        IntStream.range(0, h).parallel().forEach(y -> {
            int ya = Math.max(0, y - 1), yb = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++) out[y * w + x] = (tmp[ya * w + x] + tmp[y * w + x] + tmp[yb * w + x]) / 3;
        });
        return out;
    }

    /** Shift at a quad, bilinear between the tile centres (sub-pixel, no jumps at tile borders). */
    private float shiftF(int[][][] sh, int qy, int qx, int k) {
        int ty = sh.length, tx = sh[0].length;
        float fy = Math.max(0, Math.min(ty - 1.001f, (qy + 0.5f) / TILE - 0.5f));
        float fx = Math.max(0, Math.min(tx - 1.001f, (qx + 0.5f) / TILE - 0.5f));
        if (ty == 1 || tx == 1) return sh[Math.min(ty - 1, qy / TILE)][Math.min(tx - 1, qx / TILE)][k];
        int i = (int) fy, j = (int) fx;
        float a = fy - i, b = fx - j;
        return (sh[i][j][k] * (1 - b) + sh[i][j + 1][k] * b) * (1 - a) + (sh[i + 1][j][k] * (1 - b) + sh[i + 1][j + 1][k] * b) * a;
    }

    private int shiftOf(int[][][] sh, int qy, int qx, int k) {
        int i = Math.min(sh.length - 1, qy / TILE), j = Math.min(sh[0].length - 1, qx / TILE);
        return sh[i][j][k];
    }

    /**
     * Median of g / prev over pixels where prev is well above the noise (> 30 raw units) and both
     * are below the clip level; 0 if too few such pixels.
     */
    private float[] refGmCache, refGmSrc;

    /** 3x3 median of the reference green (the motion test of longer frames: no single outliers). */
    private float[] refGm() {
        return refGmCache;
    }

    private static float measuredRatio(float[] g, float[] prev, float clip) {
        int step = Math.max(1, g.length / 400_000);
        float[] r = new float[g.length / step + 1];
        int n = 0;
        for (int i = 0; i < g.length; i += step) {
            float p = prev[i], v = g[i];
            if (p > 30 && p < clip && v > 0 && v < clip) r[n++] = v / p;
        }
        if (n < 2000) return 0;
        Arrays.sort(r, 0, n);
        return r[n / 2];
    }

    private static float log2(float v) {
        return (float) (Math.log(v) / Math.log(2));
    }

    /** log2 with ~1e-4 error (exponent bits + ln(mantissa) polynomial / ln 2). */
    private static float fastLog2(float v) {
        int b = Float.floatToRawIntBits(v);
        float e = ((b >> 23) & 255) - 127;
        float m = Float.intBitsToFloat((b & 0x7FFFFF) | 0x3F800000);
        float ln = -1.7417939f + (2.8212026f + (-1.4699568f + (0.44717955f - 0.056570851f * m) * m) * m) * m;
        return e + ln * 1.4426950f;
    }

    /** 2^x with ~1e-4 relative error. */
    private static float fastExp2(float x) {
        if (x < -126) return 0;
        if (x > 127) x = 127;
        int i = (int) Math.floor(x);
        float f = x - i;
        float p = 1 + f * (0.6931472f + f * (0.2402265f + f * (0.0555041f + f * 0.0096181f)));
        return Float.intBitsToFloat((i + 127) << 23) * p;
    }

    private static int bin(float v, float lvMax, int nb) {
        int b = (int) (log2(Math.max(v, 1)) / lvMax * nb);
        return Math.min(nb - 1, Math.max(0, b));
    }

    private static void fillNaN(float[] v) {
        int first = -1;
        for (int i = 0; i < v.length; i++) if (!Float.isNaN(v[i])) {
            first = i;
            break;
        }
        if (first < 0) {
            Arrays.fill(v, 1f);
            return;
        }
        for (int i = 0; i < first; i++) v[i] = v[first];
        for (int i = first + 1; i < v.length; i++) if (Float.isNaN(v[i])) v[i] = v[i - 1];
    }

    /** A pixel far above all 8 same-colour neighbours (distance 2) gets their median. */
    private int removeHotPixels(float[] m) {
        int[] count = new int[H];
        float[] copy = m.clone();
        // (3-pixel border: the neighbour test below reads up to 3 rows / columns away - with 2 it
        // read past the last row: ArrayIndexOutOfBounds on the phone, 2026-11-17)
        IntStream.range(3, H - 3).parallel().forEach(y -> {
            float[] nb = new float[8];
            for (int x = 3; x < W - 3; x++) {
                float bk = black[(y % 2) * 2 + x % 2];
                float v = copy[y * W + x];
                int n = 0;
                float mx = -1;
                for (int dy = -2; dy <= 2; dy += 2) {
                    for (int dx = -2; dx <= 2; dx += 2) {
                        if (dy == 0 && dx == 0) continue;
                        float u = copy[(y + dy) * W + x + dx];
                        nb[n++] = u;
                        mx = Math.max(mx, u);
                    }
                }
                if (v <= mx + 12) continue;
                Arrays.sort(nb);
                float med = (nb[3] + nb[4]) / 2;
                // a tiny light / star covers its direct neighbours (other colours, 1 px) too - a hot
                // pixel does not (it destroyed the cores of distant lamps in the astro merge,
                // 2026-11-17): same test as fixWarm
                float excess = v - Math.max(mx, med);
                float raise = -1e9f;
                int i0 = y * W + x;
                for (int j : new int[]{i0 - 1, i0 + 1, i0 - W, i0 + W}) {
                    raise = Math.max(raise, copy[j] - (copy[j - 2] + copy[j + 2] + copy[j - 2 * W] + copy[j + 2 * W]) / 4);
                }
                if (!Boolean.getBoolean("oldHot") && raise > WARM_NEIGHBOUR * excess) continue;
                if (v - bk > 2 * (med - bk) + 12) {
                    m[y * W + x] = med;
                    count[y]++;
                }
            }
        });
        int s = 0;
        for (int c : count) s += c;
        return s;
    }

    private static float bilinear(float[] a, int w, int h, float fx, float fy) {
        fx = Math.max(0, Math.min(w - 1.001f, fx));
        fy = Math.max(0, Math.min(h - 1.001f, fy));
        int x0 = (int) fx, y0 = (int) fy;
        float ax = fx - x0, ay = fy - y0;
        return (a[y0 * w + x0] * (1 - ax) + a[y0 * w + x0 + 1] * ax) * (1 - ay)
                + (a[(y0 + 1) * w + x0] * (1 - ax) + a[(y0 + 1) * w + x0 + 1] * ax) * ay;
    }

    /** Bayer value with mirrored borders. */
    private float at(float[] m, int y, int x) {
        if (y < 0) y = -y;
        if (x < 0) x = -x;
        if (y >= H) y = 2 * H - 2 - y;
        if (x >= W) x = 2 * W - 2 - x;
        return m[y * W + x];
    }

    /** Malvar-He-Cutler demosaicing at (y, x): rgb[0..2]. */
    private void demosaic(float[] m, int y, int x, float[] rgb) {
        if (y >= 2 && x >= 2 && y < H - 2 && x < W - 2) {
            demosaicInner(m, y, x, rgb);
            return;
        }
        int p = (y % 2) * 2 + x % 2;
        int c = color[p];
        float C = at(m, y, x);
        if (c == 1) {
            // green site: the colour of the horizontal neighbours
            int ch = color[(y % 2) * 2 + (x + 1) % 2];
            float hz = (5 * C + 4 * (at(m, y, x - 1) + at(m, y, x + 1))
                    - (at(m, y - 1, x - 1) + at(m, y - 1, x + 1) + at(m, y + 1, x - 1) + at(m, y + 1, x + 1))
                    - (at(m, y, x - 2) + at(m, y, x + 2)) + 0.5f * (at(m, y - 2, x) + at(m, y + 2, x))) / 8;
            float vt = (5 * C + 4 * (at(m, y - 1, x) + at(m, y + 1, x))
                    - (at(m, y - 1, x - 1) + at(m, y - 1, x + 1) + at(m, y + 1, x - 1) + at(m, y + 1, x + 1))
                    - (at(m, y - 2, x) + at(m, y + 2, x)) + 0.5f * (at(m, y, x - 2) + at(m, y, x + 2))) / 8;
            rgb[1] = C;
            if (ch == 0) {
                rgb[0] = hz;
                rgb[2] = vt;
            } else {
                rgb[2] = hz;
                rgb[0] = vt;
            }
        } else {
            float g = (4 * C + 2 * (at(m, y - 1, x) + at(m, y + 1, x) + at(m, y, x - 1) + at(m, y, x + 1))
                    - (at(m, y - 2, x) + at(m, y + 2, x) + at(m, y, x - 2) + at(m, y, x + 2))) / 8;
            float o = (6 * C + 2 * (at(m, y - 1, x - 1) + at(m, y - 1, x + 1) + at(m, y + 1, x - 1) + at(m, y + 1, x + 1))
                    - 1.5f * (at(m, y - 2, x) + at(m, y + 2, x) + at(m, y, x - 2) + at(m, y, x + 2))) / 8;
            rgb[1] = g;
            if (c == 0) {
                rgb[0] = C;
                rgb[2] = o;
            } else {
                rgb[2] = C;
                rgb[0] = o;
            }
        }
    }

    /** demosaic() without border handling (2 <= y < H-2, 2 <= x < W-2). */
    static final boolean DEM_CLAMP = !Boolean.getBoolean("noDemClamp");

    private static float clamp2(float v, float a, float b) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), v));
    }

    private static float clamp4(float v, float a, float b, float c, float d) {
        float lo = Math.min(Math.min(a, b), Math.min(c, d)), hi = Math.max(Math.max(a, b), Math.max(c, d));
        return Math.max(lo, Math.min(hi, v));
    }

    private void demosaicInner(float[] m, int y, int x, float[] rgb) {
        final int w = W, i = y * w + x;
        int c = color[(y & 1) * 2 + (x & 1)];
        float C = m[i];
        if (c == 1) {
            float diag = m[i - w - 1] + m[i - w + 1] + m[i + w - 1] + m[i + w + 1];
            float hz = (5 * C + 4 * (m[i - 1] + m[i + 1]) - diag - (m[i - 2] + m[i + 2])
                    + 0.5f * (m[i - 2 * w] + m[i + 2 * w])) / 8;
            float vt = (5 * C + 4 * (m[i - w] + m[i + w]) - diag - (m[i - 2 * w] + m[i + 2 * w])
                    + 0.5f * (m[i - 2] + m[i + 2])) / 8;
            if (DEM_CLAMP) {
                // no overshoot beyond the neighbours that were interpolated (Malvar's gradient term
                // made dark dots in tiny bright lights and a dotted line at hard clip edges)
                hz = clamp2(hz, m[i - 1], m[i + 1]);
                vt = clamp2(vt, m[i - w], m[i + w]);
            }
            rgb[1] = C;
            if (color[(y & 1) * 2 + ((x + 1) & 1)] == 0) {
                rgb[0] = hz;
                rgb[2] = vt;
            } else {
                rgb[2] = hz;
                rgb[0] = vt;
            }
        } else {
            float cross2 = m[i - 2 * w] + m[i + 2 * w] + m[i - 2] + m[i + 2];
            float g = (4 * C + 2 * (m[i - w] + m[i + w] + m[i - 1] + m[i + 1]) - cross2) / 8;
            float o = (6 * C + 2 * (m[i - w - 1] + m[i - w + 1] + m[i + w - 1] + m[i + w + 1]) - 1.5f * cross2) / 8;
            if (DEM_CLAMP) {
                g = clamp4(g, m[i - w], m[i + w], m[i - 1], m[i + 1]);
                o = clamp4(o, m[i - w - 1], m[i - w + 1], m[i + w - 1], m[i + w + 1]);
            }
            rgb[1] = g;
            if (c == 0) {
                rgb[0] = C;
                rgb[2] = o;
            } else {
                rgb[2] = C;
                rgb[0] = o;
            }
        }
    }

    // sharpening (unsharp mask on the luminance only, as camera JPEGs do; the raw data have no
    // sharpening at all): detail = Y - blur(Y); changes below SHARP_THRESH levels (noise) are
    // ignored, the rest x SHARP_AMOUNT; less in the shadows (strongly brightened = noisy)
    static final float SHARP_SIGMA = 1.0f, SHARP_THRESH = 2f, SHARP_DARK = 48f;
    // user setting "Sharpening" (master, sent to all cameras): Off / Low / Normal / Strong
    static final String[] SHARP_NAMES = {"Off", "Low", "Normal", "Strong"};
    static final float[] SHARP_AMOUNTS = {0f, 0.4f, 0.7f, 1.0f};
    static final int SHARP_DEFAULT = 2;
    static volatile int sharpLevel = Integer.getInteger("sharp", SHARP_DEFAULT);   // bench: -Dsharp=0..3
    // user setting "Local contrast" (2026-11-17, like Google's clarity; edge-aware, no halos - bench
    // data/local_contrast_20261117): Off / Low / Normal / Strong, never in astro
    static final String[] CLARITY_NAMES = {"Off", "Low", "Normal", "Strong"};
    static final float[] CLARITY_AMOUNTS = {0f, 0.5f, 1.0f, 1.5f};
    static final int CLARITY_DEFAULT = 2;
    static volatile int clarityLevel = Integer.getInteger("clarityLevel", CLARITY_DEFAULT);
    static final float SHARP_MAX = 40f;           // levels: limits halos at hard edges
    static final float SHARP_MARGIN = 3f;         // levels beyond the neighbours' range allowed
    static final boolean OLD_SHARP = Boolean.getBoolean("oldSharp");   // bench switch

    private static void sharpen(int[] px, int w, int h, float amount) {
        float[] y = new float[w * h];
        IntStream.range(0, h).parallel().forEach(r -> {
            for (int x = 0; x < w; x++) {
                int c = px[r * w + x];
                y[r * w + x] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
            }
        });
        float[] b = gauss(y, w, h, SHARP_SIGMA);
        IntStream.range(0, h).parallel().forEach(r -> {
            for (int x = 0; x < w; x++) {
                int i = r * w + x;
                float d = y[i] - b[i], ad = Math.abs(d);
                if (ad <= SHARP_THRESH) continue;
                // soft threshold, shadows weaker, size limited
                float s = Math.signum(d) * Math.min(SHARP_MAX, (ad - SHARP_THRESH) * amount
                        * Math.min(1f, b[i] / SHARP_DARK));
                if (!OLD_SHARP && r > 0 && r < h - 1 && x > 0 && x < w - 1) {
                    // no over- / undershoot: the result stays within its 8 neighbours' range (+ a
                    // small margin) - edges still get steeper, but a pixel never becomes darker
                    // than everything around it (black dots in the astro lamp glows, 2026-11-17)
                    float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dy == 0 && dx == 0) continue;
                            float v = y[i + dy * w + dx];
                            mn = Math.min(mn, v);
                            mx = Math.max(mx, v);
                        }
                    }
                    float yi = y[i];
                    s = Math.max(s, Math.min(0f, mn - SHARP_MARGIN - yi));   // only limits, never inverts
                    s = Math.min(s, Math.max(0f, mx + SHARP_MARGIN - yi));
                    if (s == 0) continue;
                }
                int c = px[i];
                px[i] = 0xFF000000 | clamp8(((c >> 16) & 255) + s) << 16 | clamp8(((c >> 8) & 255) + s) << 8
                        | clamp8((c & 255) + s);
            }
        });
    }

    /**
     * Local contrast ("clarity", like Google): luminance detail at a large scale (radius
     * -DclarityR px, relative to a 4000 px wide image) is boosted by the amount. Base: "gauss" = a
     * plain large blur (halos at strong edges), "guided" = a self-guided filter (He et al. 2010) that
     * keeps strong edges in the base (no halos); -DclarityEps = its edge threshold (levels). The
     * change is limited to +-CL_MAX levels and weaker in the shadows (noise).
     */
    private static void localContrast(int[] px, int w, int h, float amount) {
        if (Boolean.getBoolean("clarityFull") || "gauss".equals(System.getProperty("clarityMode"))) {
            localContrastFull(px, w, h, amount);      // bench reference (needs ~10 full-size arrays)
            return;
        }
        // FAST guided filter (He & Sun 2015): a, b computed on a 4x smaller image, applied at full
        // resolution - a few MB instead of ~500 MB (the full version ran out of memory on the phone,
        // 2026-11-17: OutOfMemoryError, heap 512 MB)
        float rRel = Float.parseFloat(System.getProperty("clarityR", "24"));
        float eps = Float.parseFloat(System.getProperty("clarityEps", "12"));
        float clMax = Float.parseFloat(System.getProperty("clarityMax", "30"));
        final int S = 4;
        final int lw = (w + S - 1) / S, lh = (h + S - 1) / S;
        int r = Math.max(1, Math.round(rRel * Math.max(w, h) / 4000f / S));
        float[] m = new float[lw * lh], m2 = new float[lw * lh];
        IntStream.range(0, lh).parallel().forEach(ly -> {
            for (int lx = 0; lx < lw; lx++) {
                float s = 0, s2 = 0;
                int n = 0;
                for (int yy = ly * S; yy < Math.min(h, ly * S + S); yy++) {
                    for (int xx = lx * S; xx < Math.min(w, lx * S + S); xx++) {
                        int c = px[yy * w + xx];
                        float v = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
                        s += v;
                        s2 += v * v;
                        n++;
                    }
                }
                m[ly * lw + lx] = s / n;
                m2[ly * lw + lx] = s2 / n;
            }
        });
        float[] tmp = new float[lw * lh], mean = new float[lw * lh], mean2 = new float[lw * lh];
        boxH(m, tmp, lw, lh, r);
        boxV(tmp, mean, lw, lh, r);
        boxH(m2, tmp, lw, lh, r);
        boxV(tmp, mean2, lw, lh, r);
        float e2 = eps * eps;
        float[] a = m, b = m2;                    // (arrays reused: memory on the phone)
        for (int i = 0; i < a.length; i++) {
            float v = Math.max(0, mean2[i] - mean[i] * mean[i]);
            a[i] = v / (v + e2);
            b[i] = mean[i] - a[i] * mean[i];
        }
        final float[] ma = mean, mb = mean2;
        boxH(a, tmp, lw, lh, r);
        boxV(tmp, ma, lw, lh, r);
        boxH(b, tmp, lw, lh, r);
        boxV(tmp, mb, lw, lh, r);
        IntStream.range(0, h).parallel().forEach(y0 -> {
            float fy = Math.max(0, Math.min(lh - 1.001f, (y0 + 0.5f) / S - 0.5f));
            int iy = (int) fy;
            float ay = fy - iy;
            for (int x = 0; x < w; x++) {
                float fx = Math.max(0, Math.min(lw - 1.001f, (x + 0.5f) / S - 0.5f));
                int ix = (int) fx;
                float ax = fx - ix;
                int k = iy * lw + ix;
                float A = (ma[k] * (1 - ax) + ma[k + 1] * ax) * (1 - ay) + (ma[k + lw] * (1 - ax) + ma[k + lw + 1] * ax) * ay;
                float B = (mb[k] * (1 - ax) + mb[k + 1] * ax) * (1 - ay) + (mb[k + lw] * (1 - ax) + mb[k + lw + 1] * ax) * ay;
                int i = y0 * w + x;
                int c = px[i];
                float v = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
                float base = A * v + B;
                float s = amount * (v - base) * Math.min(1f, base / SHARP_DARK);
                s = Math.max(-clMax, Math.min(clMax, s));
                if (Math.abs(s) < 0.5f) continue;
                px[i] = 0xFF000000 | clamp8(((c >> 16) & 255) + s) << 16 | clamp8(((c >> 8) & 255) + s) << 8
                        | clamp8((c & 255) + s);
            }
        });
    }

    /** Full-resolution version (bench reference / gauss mode): ~10 full-size float arrays. */
    private static void localContrastFull(int[] px, int w, int h, float amount) {
        float rRel = Float.parseFloat(System.getProperty("clarityR", "24"));
        int r = Math.max(2, Math.round(rRel * Math.max(w, h) / 4000f));
        boolean guided = !"gauss".equals(System.getProperty("clarityMode", "guided"));
        float eps = Float.parseFloat(System.getProperty("clarityEps", "12"));
        float clMax = Float.parseFloat(System.getProperty("clarityMax", "30"));
        float[] y = new float[w * h];
        IntStream.range(0, h).parallel().forEach(r0 -> {
            for (int x = 0; x < w; x++) {
                int c = px[r0 * w + x];
                y[r0 * w + x] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
            }
        });
        float[] base;
        if (guided) {
            float[] tmp = new float[w * h], m = new float[w * h], m2 = new float[w * h], yy = new float[w * h];
            boxH(y, tmp, w, h, r);
            boxV(tmp, m, w, h, r);
            for (int i = 0; i < yy.length; i++) yy[i] = y[i] * y[i];
            boxH(yy, tmp, w, h, r);
            boxV(tmp, m2, w, h, r);
            float e2 = eps * eps;
            float[] a = new float[w * h], b = new float[w * h];
            for (int i = 0; i < a.length; i++) {
                float v = Math.max(0, m2[i] - m[i] * m[i]);
                a[i] = v / (v + e2);
                b[i] = m[i] - a[i] * m[i];
            }
            float[] ma = new float[w * h], mb = new float[w * h];
            boxH(a, tmp, w, h, r);
            boxV(tmp, ma, w, h, r);
            boxH(b, tmp, w, h, r);
            boxV(tmp, mb, w, h, r);
            base = new float[w * h];
            for (int i = 0; i < base.length; i++) base[i] = ma[i] * y[i] + mb[i];
        } else {
            base = gauss(y, w, h, r);
        }
        final float[] bs = base;
        IntStream.range(0, h).parallel().forEach(r0 -> {
            for (int x = 0; x < w; x++) {
                int i = r0 * w + x;
                float d = y[i] - bs[i];
                float s = amount * d * Math.min(1f, bs[i] / SHARP_DARK);
                s = Math.max(-clMax, Math.min(clMax, s));
                if (Math.abs(s) < 0.5f) continue;
                int c = px[i];
                px[i] = 0xFF000000 | clamp8(((c >> 16) & 255) + s) << 16 | clamp8(((c >> 8) & 255) + s) << 8
                        | clamp8((c & 255) + s);
            }
        });
    }

    /**
     * FALSE COLOUR OF TINY LIGHTS (item 7): a light of 1-3 px lands mostly on one colour of the Bayer
     * pattern, so its colour in the short reference is random from quad to quad ("confetti"); where only
     * that frame is used (merge weight ~1: the longer frames are clipped there) the colour is replaced
     * by its brightness-weighted mean over (2r+1)^2 quads (the light's real colour); brightness unchanged.
     */
    static final float PC_BRIGHT_LO = Float.parseFloat(System.getProperty("pcBrightLo", "0"));
    static final float PC_BRIGHT_HI = Float.parseFloat(System.getProperty("pcBrightHi", "120"));
    static final float PC_SAT = Float.parseFloat(System.getProperty("pcSat", "1"));

    private void pointColour(int[] px, int w, int h, float[] wsum, int cx, int cy, int r, float pcLo, float pcHi, float pcSat) {
        int w2 = w / 2, h2 = h / 2;
        float[] sy = new float[w2 * h2], sb = new float[w2 * h2], sr = new float[w2 * h2], mk = new float[w2 * h2];
        IntStream.range(0, h2).parallel().forEach(y -> {
            for (int x = 0; x < w2; x++) {
                float yy = 0, cb = 0, cr = 0;
                for (int d = 0; d < 4; d++) {
                    int c = px[(2 * y + d / 2) * w + 2 * x + d % 2];
                    int rr = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    float l = 0.299f * rr + 0.587f * g + 0.114f * b;
                    yy += l;
                    cb += b - l;
                    cr += rr - l;
                }
                int i = y * w2 + x;
                sy[i] = yy / 4;                      // weight: brightness (the light dominates its colour)
                sb[i] = cb / 4 * sy[i];
                sr[i] = cr / 4 * sy[i];
                int qy = Math.min(H2 - 1, (cy / 2) + y), qx = Math.min(W2 - 1, (cx / 2) + x);
                float ws = wsum[qy * W2 + qx];
                mk[i] = ws <= 2f ? 1f : ws >= 6f ? 0f : (6f - ws) / 4f;   // only the reference used there
                if (pcLo > 0) {                          // also lights + glows: bright parts of the image
                    float bm = (sy[i] - pcLo) / (pcHi - pcLo);
                    mk[i] = Math.max(mk[i], Math.min(1f, Math.max(0f, bm)));
                }
            }
        });
        float[] tmp = new float[w2 * h2], my = new float[w2 * h2], mb = new float[w2 * h2], mr = new float[w2 * h2];
        boxH(sy, tmp, w2, h2, r);
        boxV(tmp, my, w2, h2, r);
        boxH(sb, tmp, w2, h2, r);
        boxV(tmp, mb, w2, h2, r);
        boxH(sr, tmp, w2, h2, r);
        boxV(tmp, mr, w2, h2, r);
        float[] mm = new float[w2 * h2];
        boxH(mk, tmp, w2, h2, 2);
        boxV(tmp, mm, w2, h2, 2);
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i2 = Math.min(h2 - 1, y / 2) * w2 + Math.min(w2 - 1, x / 2);
                float a = Math.min(mm[i2], mk[i2] > 0 ? 1f : mm[i2]);
                if (a <= 0.01f || my[i2] < 1f) continue;
                int c = px[y * w + x];
                int rr = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                float l = 0.299f * rr + 0.587f * g + 0.114f * b;
                float cb0 = b - l, cr0 = rr - l;
                float cb1 = mb[i2] / my[i2], cr1 = mr[i2] / my[i2];
                float cb = cb0 + a * (cb1 - cb0), cr = cr0 + a * (cr1 - cr0);
                float sat = 1f - a * (1f - pcSat);              // a little less saturation on lights
                cb *= sat;
                cr *= sat;
                float nr = l + cr, nb = l + cb;
                float ng = (l - 0.299f * nr - 0.114f * nb) / 0.587f;
                px[y * w + x] = 0xFF000000 | clamp8(nr) << 16 | clamp8(ng) << 8 | clamp8(nb);
            }
        });
    }

    /** Colour noise: chroma (Cb, Cr) at half resolution, 3x3 median + 5x5 box, back to full res. */
    private static void chromaDenoise(int[] px, int w, int h) {
        int w2 = w / 2, h2 = h / 2;
        float[] cb = new float[w2 * h2], cr = new float[w2 * h2];
        IntStream.range(0, h2).parallel().forEach(y -> {
            for (int x = 0; x < w2; x++) {
                float sb = 0, sr = 0;
                for (int d = 0; d < 4; d++) {
                    int c = px[(2 * y + d / 2) * w + 2 * x + d % 2];
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    float yy = 0.299f * r + 0.587f * g + 0.114f * b;
                    sb += b - yy;
                    sr += r - yy;
                }
                cb[y * w2 + x] = sb / 4;
                cr[y * w2 + x] = sr / 4;
            }
        });
        float[] cbm = box5(median3(cb, w2, h2), w2, h2), crm = box5(median3(cr, w2, h2), w2, h2);
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                float yy = 0.299f * r + 0.587f * g + 0.114f * b;
                float fx = Math.min(w2 - 1.001f, Math.max(0, (x + 0.5f) / 2 - 0.5f));
                float fy = Math.min(h2 - 1.001f, Math.max(0, (y + 0.5f) / 2 - 0.5f));
                float ncb = bilinear(cbm, w2, h2, fx, fy), ncr = bilinear(crm, w2, h2, fx, fy);
                float nr = yy + ncr, nb = yy + ncb;
                float ng = (yy - 0.299f * nr - 0.114f * nb) / 0.587f;
                px[y * w + x] = 0xFF000000 | clamp8(nr) << 16 | clamp8(ng) << 8 | clamp8(nb);
            }
        });
    }

    private static int clamp8(float v) {
        return Math.min(255, Math.max(0, Math.round(v)));
    }

    private static float[] median3(float[] in, int w, int h) {
        float[] out = new float[in.length];
        IntStream.range(0, h).parallel().forEach(y -> {
            float[] v = new float[9];
            for (int x = 0; x < w; x++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy)), xx = Math.min(w - 1, Math.max(0, x + dx));
                    v[n++] = in[yy * w + xx];
                }
                Arrays.sort(v);
                out[y * w + x] = v[4];
            }
        });
        return out;
    }

    private static float[] box5(float[] in, int w, int h) {
        float[] tmp = new float[in.length], out = new float[in.length];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int d = -2; d <= 2; d++) s += in[y * w + Math.min(w - 1, Math.max(0, x + d))];
                tmp[y * w + x] = s / 5;
            }
        });
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int d = -2; d <= 2; d++) s += tmp[Math.min(h - 1, Math.max(0, y + d)) * w + x];
                out[y * w + x] = s / 5;
            }
        });
        return out;
    }

    /** Where fewer than 3 frames were merged (motion): blend towards a 3x3 median (per channel). */
    private void motionDenoise(int[] px, int w, int h, float[] wsum, int cx, int cy) {
        float[] wb = gauss(wsum, W2, H2, 2f);
        int[] src = px.clone();
        IntStream.range(1, h - 1).parallel().forEach(y -> {
            int[] cr = new int[9], cg = new int[9], cbl = new int[9];
            for (int x = 1; x < w - 1; x++) {
                int qy = Math.min(H2 - 1, (cy + y) / 2), qx = Math.min(W2 - 1, (cx + x) / 2);
                float a = Math.min(1f, Math.max(0f, (3f - wb[qy * W2 + qx]) / 2f));
                if (a <= 0) continue;
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int c = src[(y + dy) * w + x + dx];
                    cr[n] = (c >> 16) & 255;
                    cg[n] = (c >> 8) & 255;
                    cbl[n++] = c & 255;
                }
                Arrays.sort(cr);
                Arrays.sort(cg);
                Arrays.sort(cbl);
                int c = src[y * w + x];
                int r = Math.round(((c >> 16) & 255) * (1 - a) + cr[4] * a);
                int g = Math.round(((c >> 8) & 255) * (1 - a) + cg[4] * a);
                int b = Math.round((c & 255) * (1 - a) + cbl[4] * a);
                px[y * w + x] = 0xFF000000 | r << 16 | g << 8 | b;
            }
        });
    }

    private int shadeChannel(int p) {
        int c = color[p];
        if (c == 0) return 0;
        if (c == 2) return 3;
        // green on a red row = "even" green
        int other = color[(p / 2) * 2 + (1 - p % 2)];
        return other == 0 ? 1 : 2;
    }

    /** The map interpolated to image row y: float[col * 4 + ch], or null. */
    private float[] shadingAt(float[][] rows, int y) {
        if (rows == null) return null;
        float fy = (float) y / (H - 1) * (rows.length - 1);
        int j = Math.min(rows.length - 2, (int) fy);
        float a = fy - j;
        float[] out = new float[rows[0].length];
        for (int i = 0; i < out.length; i++) out[i] = rows[j][i] * (1 - a) + rows[j + 1][i] * a;
        return out;
    }

    private float shadeGain(float[] row, int p, int x) {
        int cols = row.length / 4;
        float fx = (float) x / (W - 1) * (cols - 1);
        int i = Math.min(cols - 2, (int) fx);
        float a = fx - i;
        int ch = shadeChannel(p);
        return row[i * 4 + ch] * (1 - a) + row[(i + 1) * 4 + ch] * a;
    }
}
