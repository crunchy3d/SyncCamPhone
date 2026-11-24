package com.crunchy3d.synccamphone;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES31;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Locale;

/**
 * The HDR / night merge on the GPU (item 21, 2026-11-18): OpenGL ES 3.1 compute shaders, the same
 * maths as MergeCore (float instead of double). An off-screen EGL context on the merge thread,
 * created per merge (MergeCore.GpuFactory). Buffers live in GPU / driver memory, not in the Java
 * heap; uploads go straight from the Java arrays (no direct-buffer copies: those count against
 * the 512 MB heap).
 *
 * Per frame (step 2 + 3): PLANES (the frame at the aligned sub-pixel position: R, G, B per Bayer
 * quad + clip mask), SAMPLES (3x3 means at every 7th quad: the black-level bias, on the CPU), ROB
 * (robustness, + 2 near clipped parts), ERODE / BOX / MINP (erosion + blur of the weights), USE0 +
 * GREEN (longer frame: clip ramp, blurred green for the noise weights), ACC (accumulation). The
 * accumulator (W x H) and the weight sum stay on the GPU until the end of the merge.
 */
final class GpuRobust implements MergeCore.Gpu {
    static final MergeCore.GpuFactory FACTORY = GpuRobust::new;

    private static final int B_RAW = 0, B_SH = 1, B_FPL = 2, B_CLIP = 3, B_REF = 4, B_SMP = 5, B_OUT = 6,
            B_AUX = 7, B_ACC = 8, B_WS = 9, B_TMP = 10, B_CNT = 11, B_PX = 12, B_DEV = 13, B_SHD = 14, B_SRGB = 15,
            B_ALN = 16, NB = 17;
    // planes of the AUX buffer (W2 x H2 floats each)
    // (7..10: MergeCore.GP_REF, GP_G, GP_PREV, GP_CHAIN - the greens of the alignment)
    private static final int P_WE = 0, P_USE = 1, P_GM = 2, P_RV = 3, P_T1 = 4, P_T2 = 5, P_T3 = 6, NP = 11;
    private static final int LX = 16, LY = 16, L1 = 256;

    private final int W, H, W2, H2, N, M, tile;
    private final float[] black;
    private final int[] ds;
    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surf = EGL14.EGL_NO_SURFACE;
    private final int[] buf = new int[NB];
    private int pPlanes, pSamples, pRob, pErode, pBox, pMin, pUse0, pGreen, pAccInit, pAcc;
    private int pDiv, pHot, pDen, pRender, pC1, pMed3, pBoxG, pC4, pL1, pL2, pL3, pS1, pS2;
    private int pBelow;
    private int pWarm, pBmed, pAddB, pRatio, pCopyP, pDown8, pCoarse, pRefine, pTiles;
    private int shTy = -1, shTx = -1, ty, tx;
    private int usePlane = P_USE;
    private boolean smpMapped;
    private String renderer = "";
    private long maxBlock;
    private int maxCompBlocks;

    private GpuRobust(int W, int H, int tile, float[] black, int[] ds) {
        this.W = W;
        this.H = H;
        this.W2 = W / 2;
        this.H2 = H / 2;
        this.N = W2 * H2;
        this.M = (N + 6) / 7;
        this.tile = tile;
        this.black = black.clone();
        this.ds = ds.clone();
        boolean ok = false;
        try {
            long t0 = android.os.SystemClock.elapsedRealtime();
            initEgl();
            long t1 = android.os.SystemClock.elapsedRealtime();
            pPlanes = program(PLANES);
            pSamples = program(SAMPLES);
            pRob = program(ROB);
            pErode = program(ERODE);
            pBox = program(BOX);
            pMin = program(MINP);
            pUse0 = program(USE0);
            pGreen = program(GREEN);
            pAccInit = program(ACCINIT);
            pAcc = program(ACC);
            pDiv = program(DIV);
            pHot = program(HOT);
            pDen = program(DEN);
            pRender = program(RENDER);
            pC1 = program(C1);
            pMed3 = program(MED3);
            pBoxG = program(BOXG);
            pC4 = program(C4);
            pL1 = program(LC1);
            pL2 = program(L2S);
            pL3 = program(LC3);
            pS1 = program(S1);
            pS2 = program(S2);
            pWarm = program(WARM);
            pBmed = program(BMED);
            pBelow = program(BBELOW);
            pAddB = program(ADDB);
            pRatio = program(RATIO);
            pCopyP = program(COPYP);
            pDown8 = program(DOWN8);
            pCoarse = program(ACOARSE);
            pRefine = program(AREFINE);
            pTiles = program(ATILES);
            GLES31.glFinish();
            long t2 = android.os.SystemClock.elapsedRealtime();
            GLES31.glGenBuffers(NB, buf, 0);
            alloc(B_RAW, (long) W * H * 2);
            alloc(B_FPL, (long) N * 3 * 4);
            alloc(B_CLIP, (long) N * 4);
            alloc(B_REF, (long) N * 3 * 4);
            alloc(B_SMP, (long) M * 6 * 4);
            alloc(B_OUT, (long) N * 4);
            alloc(B_AUX, (long) N * NP * 4);
            alloc(B_ACC, (long) W * H * 4);
            alloc(B_WS, (long) N * 4);
            alloc(B_TMP, (long) W * H * 4);
            alloc(B_CNT, 16);
            alloc(B_ALN, 4L << 20);
            check("buffers");
            GLES31.glFinish();
            startTimes = String.format(Locale.US, "context %d, programs %d, buffers %d ms",
                    t1 - t0, t2 - t1, android.os.SystemClock.elapsedRealtime() - t2);
            ok = true;
        } finally {
            if (!ok) close();
        }
    }

    // ------------------------------------------------------------------ EGL / GL plumbing

    private void initEgl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new IllegalStateException("eglInitialize");
        int[] attr = {EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0) || n[0] < 1) {
            throw new IllegalStateException("no ES 3 config");
        }
        ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("no ES 3 context");
        surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE}, 0);
        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw new IllegalStateException("eglMakeCurrent");
        String ver = GLES31.glGetString(GLES31.GL_VERSION);
        renderer = GLES31.glGetString(GLES31.GL_RENDERER);
        driverKey = renderer + "|" + ver;
        if (ver == null || !(ver.contains("ES 3.1") || ver.contains("ES 3.2"))) {
            throw new IllegalStateException("needs OpenGL ES 3.1, have " + ver);
        }
        long[] mb = new long[1];
        GLES31.glGetInteger64v(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE, mb, 0);
        maxBlock = mb[0];
        if (maxBlock > 0 && maxBlock < Math.max((long) W * H * 4, (long) N * NP * 4)) {
            throw new IllegalStateException("storage block limit " + (maxBlock >> 20) + " MB");
        }
        int[] iv = new int[1];
        GLES31.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS, iv, 0);
        if (iv[0] < NB) throw new IllegalStateException("only " + iv[0] + " storage bindings");
        GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS, iv, 0);
        maxCompBlocks = iv[0];
        if (maxCompBlocks < 5) throw new IllegalStateException("only " + maxCompBlocks + " compute storage blocks");
    }

    // PROGRAM CACHE (2026-11-18): compiling the 32 programs took 0.2 - 1.6 s per merge (1.6 s while the
    // camera was busy); the driver's binaries are saved in the app cache, keyed by the source and the
    // driver (renderer + version), and loaded next time (a stale / refused binary: compiled again)
    static volatile java.io.File cacheDir;        // set by the app (PhotoBurst / AstroCapture)
    private static String driverKey = "";
    private int cachedN, compiledN;
    private String startTimes = "";

    private static String keyOf(String src) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest((driverKey + "|" + src).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format(Locale.US, "%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((driverKey + src).hashCode());
        }
    }

    private int program(String src) {
        java.io.File dir = cacheDir;
        java.io.File f = dir == null ? null : new java.io.File(dir, keyOf(src) + ".bin");
        if (f != null && f.isFile()) {
            try {
                byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
                int fmt = bb.getInt();
                ByteBuffer bin = ByteBuffer.allocateDirect(all.length - 4).order(ByteOrder.nativeOrder());
                bin.put(all, 4, all.length - 4).rewind();
                int p = GLES31.glCreateProgram();
                android.opengl.GLES30.glProgramBinary(p, fmt, bin, all.length - 4);
                int[] st = new int[1];
                GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, st, 0);
                if (st[0] != 0) {
                    cachedN++;
                    return p;
                }
                GLES31.glDeleteProgram(p);
            } catch (Throwable ignored) {
            }
            f.delete();                               // refused (driver update): compile again
            GLES31.glGetError();
        }
        int p = compile(src);
        compiledN++;
        if (f != null) {
            try {
                int[] len = new int[1];
                GLES31.glGetProgramiv(p, android.opengl.GLES30.GL_PROGRAM_BINARY_LENGTH, len, 0);
                if (len[0] > 0) {
                    ByteBuffer bin = ByteBuffer.allocateDirect(len[0]).order(ByteOrder.nativeOrder());
                    int[] got = new int[1], fmt = new int[1];
                    android.opengl.GLES30.glGetProgramBinary(p, len[0], got, 0, fmt, 0, bin);
                    if (got[0] > 0) {
                        byte[] out = new byte[4 + got[0]];
                        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(fmt[0]);
                        bin.rewind();
                        bin.get(out, 4, got[0]);
                        dir.mkdirs();
                        java.io.File tmp = new java.io.File(dir, f.getName() + ".tmp");
                        java.nio.file.Files.write(tmp.toPath(), out);
                        if (!tmp.renameTo(f)) tmp.delete();
                    }
                }
            } catch (Throwable ignored) {
            }
            GLES31.glGetError();
        }
        return p;
    }

    private static int compile(String src) {
        int s = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);
        GLES31.glShaderSource(s, src);
        GLES31.glCompileShader(s);
        int[] st = new int[1];
        GLES31.glGetShaderiv(s, GLES31.GL_COMPILE_STATUS, st, 0);
        if (st[0] == 0) {
            String log = GLES31.glGetShaderInfoLog(s);
            GLES31.glDeleteShader(s);
            throw new IllegalStateException("shader: " + log);
        }
        int p = GLES31.glCreateProgram();
        GLES31.glAttachShader(p, s);
        android.opengl.GLES30.glProgramParameteri(p, android.opengl.GLES30.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GLES31.GL_TRUE);
        GLES31.glLinkProgram(p);
        GLES31.glDeleteShader(s);
        GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, st, 0);
        if (st[0] == 0) throw new IllegalStateException("link: " + GLES31.glGetProgramInfoLog(p));
        return p;
    }

    private void alloc(int b, long bytes) {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[b]);
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, (int) bytes, null, GLES31.GL_DYNAMIC_COPY);
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, b, buf[b]);
    }

    private static void check(String what) {
        int e = GLES31.glGetError();
        if (e != GLES31.GL_NO_ERROR) throw new IllegalStateException(String.format(Locale.US, "GL error 0x%x at %s", e, what));
    }

    private static final int CHUNK = 1 << 22;     // elements per upload call

    private void upload(int b, long byteOff, short[] a) {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[b]);
        for (int o = 0; o < a.length; o += CHUNK) {
            int n = Math.min(CHUNK, a.length - o);
            GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, (int) (byteOff + 2L * o), 2 * n, ShortBuffer.wrap(a, o, n));
        }
    }

    private void upload(int b, long byteOff, float[] a) {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[b]);
        for (int o = 0; o < a.length; o += CHUNK) {
            int n = Math.min(CHUNK, a.length - o);
            GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, (int) (byteOff + 4L * o), 4 * n, FloatBuffer.wrap(a, o, n));
        }
    }

    private static int groups(int n, int l) {
        return (n + l - 1) / l;
    }

    private static void barrier() {
        GLES31.glMemoryBarrier(GLES31.GL_ALL_BARRIER_BITS);
    }

    private void dispatch2() {
        GLES31.glDispatchCompute(groups(W2, LX), groups(H2, LY), 1);
        barrier();
    }

    private static void dispatch(int w, int h) {
        GLES31.glDispatchCompute(groups(w, LX), groups(h, LY), 1);
        barrier();
    }

    /** Maps (read) a range of buffer b on the given target; unmap with unmap(target). */
    private FloatBuffer map(int target, int b, long off, long bytes) {
        GLES31.glBindBuffer(target, buf[b]);
        ByteBuffer bb = (ByteBuffer) GLES31.glMapBufferRange(target, (int) off, (int) bytes, GLES31.GL_MAP_READ_BIT);
        if (bb == null) throw new IllegalStateException("map failed (" + GLES31.glGetError() + ")");
        return bb.order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static void unmap(int target) {
        GLES31.glUnmapBuffer(target);
    }

    private static void u(int p, String name, int v) {
        GLES31.glUniform1i(GLES31.glGetUniformLocation(p, name), v);
    }

    private static void u(int p, String name, float v) {
        GLES31.glUniform1f(GLES31.glGetUniformLocation(p, name), v);
    }

    private void use(int p) {
        GLES31.glUseProgram(p);
        u(p, "W", W);
        u(p, "W2", W2);
        u(p, "H2", H2);
    }

    private void shiftUniforms(int p) {
        u(p, "TILE", tile);
        u(p, "TY", ty);
        u(p, "TX", tx);
    }

    private void endSamples() {
        if (smpMapped) {
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[B_SMP]);
            unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
            smpMapped = false;
        }
    }

    // ------------------------------------------------------------------ small passes on AUX planes

    private void erode(int src, int dst, boolean fromRob, boolean oldErode) {
        use(pErode);
        u(pErode, "src", src);
        u(pErode, "dst", dst);
        u(pErode, "mode", fromRob ? 1 : 0);
        u(pErode, "oldErode", oldErode ? 1 : 0);
        dispatch2();
    }

    private void box(int src, int dst, int dir, int r) {
        use(pBox);
        u(pBox, "src", src);
        u(pBox, "dst", dst);
        u(pBox, "dir", dir);
        u(pBox, "r", r);
        dispatch2();
    }

    /** MergeCore.gauss: three box passes per direction, x then y. Ends in y (x, y = temporaries). */
    private void gauss(int src, int x, int y, int r) {
        box(src, x, 0, r);
        box(x, y, 1, r);
        box(y, x, 0, r);
        box(x, y, 1, r);
        box(y, x, 0, r);
        box(x, y, 1, r);
    }

    private void minp(int a, int b, boolean nearOnly) {
        use(pMin);
        u(pMin, "a", a);
        u(pMin, "b", b);
        u(pMin, "nearOnly", nearOnly ? 1 : 0);
        dispatch2();
    }

    // ------------------------------------------------------------------ MergeCore.Gpu

    @Override public void sync() {
        GLES31.glFinish();
    }

    @Override public void makeCurrent() {
        if (ctx.equals(EGL14.eglGetCurrentContext())) return;
        if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
            throw new IllegalStateException("eglMakeCurrent: 0x" + Integer.toHexString(EGL14.eglGetError()));
        }
    }

    @Override public void releaseCurrent() {
        endSamples();
        GLES31.glFinish();
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
    }

    @Override public String info() {
        return renderer + (maxBlock > 0 ? ", block " + (maxBlock >> 20) + " MB" : "") + ", " + maxCompBlocks + " blocks, "
                + cachedN + " programs cached, " + compiledN + " compiled (" + startTimes + ")";
    }

    @Override public void setReference(float[][] refPl, short[] refRaw, float[] refVar) {
        for (int c = 0; c < 3; c++) upload(B_REF, 4L * N * c, refPl[c]);
        upload(B_AUX, 4L * N * P_RV, refVar);
        upload(B_RAW, 0, refRaw);
        use(pAccInit);
        dispatch2();
        green(MergeCore.GP_REF);
        check("reference");
        GLES31.glFinish();
    }


    // ------------------------------------------------------------------ pre-steps (item 21: read + align)

    @Override public void uploadRaw(short[] raw) {
        endSamples();
        upload(B_RAW, 0, raw);
        check("upload");
    }

    @Override public void uploadRaw(ByteBuffer bb, int rowStride) {
        endSamples();
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[B_RAW]);
        ByteBuffer b = bb.duplicate();
        int row = W * 2;
        if (rowStride == row) {
            int total = row * H, step = 1 << 23;
            for (int o = 0; o < total; o += step) {
                int n = Math.min(step, total - o);
                b.position(0);
                b.limit(o + n);
                b.position(o);
                GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, o, n, b);
            }
        } else {
            for (int y = 0; y < H; y++) {
                b.position(0);
                b.limit(y * rowStride + row);
                b.position(y * rowStride);
                GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, y * row, row, b);
            }
        }
        check("upload direct");
    }

    private void zeroCounters() {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[B_CNT]);
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 16, IntBuffer.wrap(new int[4]));
    }

    private int counter(int i) {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[B_CNT]);
        ByteBuffer bb = (ByteBuffer) GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 16, GLES31.GL_MAP_READ_BIT);
        if (bb == null) throw new IllegalStateException("map counters failed");
        int v = bb.order(ByteOrder.nativeOrder()).asIntBuffer().get(i);
        unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
        return v;
    }

    private float[] readAln(int n) {
        FloatBuffer m = map(GLES31.GL_SHADER_STORAGE_BUFFER, B_ALN, 0, 4L * n);
        float[] out = new float[n];
        m.get(out);
        unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
        return out;
    }

    @Override public int fixWarm(float abs, float rel, float neighbour) {
        zeroCounters();
        GLES31.glBindBuffer(GLES31.GL_COPY_READ_BUFFER, buf[B_RAW]);
        GLES31.glBindBuffer(GLES31.GL_COPY_WRITE_BUFFER, buf[B_TMP]);
        GLES31.glCopyBufferSubData(GLES31.GL_COPY_READ_BUFFER, GLES31.GL_COPY_WRITE_BUFFER, 0, 0, 2 * W * H);
        barrier();
        use(pWarm);
        u(pWarm, "H", H);
        GLES31.glUniform4f(GLES31.glGetUniformLocation(pWarm, "blk"), black[0], black[1], black[2], black[3]);
        u(pWarm, "wabs", abs);
        u(pWarm, "wrel", rel);
        u(pWarm, "wnb", neighbour);
        GLES31.glDispatchCompute(groups(W * H / 2, L1), 1, 1);
        barrier();
        check("warm");
        return counter(2);
    }

    @Override public float[] blockMedians(int bw, int bh) {
        use(pBmed);
        u(pBmed, "bw", bw);
        u(pBmed, "bh", bh);
        GLES31.glDispatchCompute(bw, 4 * bh, 1);          // one work group (16 x 16) per block
        barrier();
        check("block medians");
        return readAln(4 * bw * bh);
    }

    @Override public float[] blockBelow(int bw, int bh, float[] below) {
        use(pBelow);
        u(pBelow, "bw", bw);
        u(pBelow, "bh", bh);
        GLES31.glUniform4f(GLES31.glGetUniformLocation(pBelow, "lv"), below[0], below[1], below[2], below[3]);
        dispatch(bw, 4 * bh);
        check("block below");
        return readAln(4 * bw * bh);
    }

    @Override public void addBlack(int[] add, int white) {
        use(pAddB);
        GLES31.glUniform4i(GLES31.glGetUniformLocation(pAddB, "add"), add[0], add[1], add[2], add[3]);
        u(pAddB, "white", white);
        GLES31.glDispatchCompute(groups(W * H / 2, L1), 1, 1);
        barrier();
        check("black");
    }

    @Override public void green(int p) {
        use(pGreen);
        u(pGreen, "ga", ds[1]);
        u(pGreen, "gb", ds[2]);
        u(pGreen, "gbk", (black[ds[1]] + black[ds[2]]) / 2);
        u(pGreen, "rk", 1f);
        u(pGreen, "dst", p);
        dispatch2();
        check("green");
    }

    @Override public float[] ratioSamples(int gP, int prevP, float clip, int step, int count) {
        use(pRatio);
        u(pRatio, "gP", gP);
        u(pRatio, "pP", prevP);
        u(pRatio, "clip", clip);
        u(pRatio, "stp", step);
        u(pRatio, "count", count);
        GLES31.glDispatchCompute(groups(count, L1), 1, 1);
        barrier();
        check("ratio");
        return readAln(count);
    }

    @Override public void copyPlane(int src, int dst) {
        use(pCopyP);
        u(pCopyP, "src", src);
        u(pCopyP, "dst", dst);
        u(pCopyP, "dv", 1f);
        dispatch2();
    }

    @Override public void divPlane(int p, float d) {
        use(pCopyP);
        u(pCopyP, "src", p);
        u(pCopyP, "dst", p);
        u(pCopyP, "dv", d);
        dispatch2();
    }

    @Override public float[] alignCoarse(int refP, int gP, float clamp, int rows) {
        int ws = W2 / 8, hs = H2 / 8;
        use(pDown8);
        u(pDown8, "clampv", clamp);
        u(pDown8, "src", refP);
        u(pDown8, "dst", P_T1);
        dispatch(ws, hs);
        u(pDown8, "src", gP);
        u(pDown8, "dst", P_T2);
        dispatch(ws, hs);
        use(pCoarse);
        u(pCoarse, "rows", rows);
        dispatch(169, rows);
        check("align coarse");
        return readAln(169 * rows);
    }

    @Override public float[] alignRefine(int refP, int gP, float clamp, int cy, int cx, int R, int step, int rows) {
        int nc = (2 * R + 1) * (2 * R + 1);
        use(pRefine);
        u(pRefine, "refP", refP);
        u(pRefine, "gP", gP);
        u(pRefine, "clampv", clamp);
        u(pRefine, "cy", cy);
        u(pRefine, "cx", cx);
        u(pRefine, "R", R);
        u(pRefine, "st", step);
        u(pRefine, "rows", rows);
        dispatch(nc, rows);
        check("align refine");
        return readAln(nc * rows * 2);
    }

    @Override public float[] alignTiles(int refP, int gP, float clamp, int gy, int gx, int search) {
        int ty = H2 / tile, tx = W2 / tile, ns = (2 * search + 1) * (2 * search + 1);
        use(pTiles);
        u(pTiles, "refP", refP);
        u(pTiles, "gP", gP);
        u(pTiles, "clampv", clamp);
        u(pTiles, "gy", gy);
        u(pTiles, "gx", gx);
        u(pTiles, "S", search);
        u(pTiles, "TILE", tile);
        u(pTiles, "TXN", tx);
        u(pTiles, "T", ty * tx);
        dispatch(ns + 1, ty * tx);
        check("align tiles");
        return readAln(ty * tx * (ns + 1));
    }

    @Override public void setShifts(int[][][] sh) {
        endSamples();
        ty = sh.length;
        tx = sh[0].length;
        int[] s = new int[ty * tx * 2];
        for (int i = 0; i < ty; i++)
            for (int j = 0; j < tx; j++) {
                s[(i * tx + j) * 2] = sh[i][j][0];
                s[(i * tx + j) * 2 + 1] = sh[i][j][1];
            }
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[B_SH]);
        if (ty != shTy || tx != shTx) {
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 4 * s.length, IntBuffer.wrap(s), GLES31.GL_DYNAMIC_DRAW);
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, B_SH, buf[B_SH]);
            shTy = ty;
            shTx = tx;
        } else {
            GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4 * s.length, IntBuffer.wrap(s));
        }
        check("shifts");
    }

    @Override public FloatBuffer samples(float rk, float clipG, boolean doNear) {
        use(pPlanes);
        shiftUniforms(pPlanes);
        GLES31.glUniform4i(GLES31.glGetUniformLocation(pPlanes, "ds"), ds[0], ds[1], ds[2], ds[3]);
        GLES31.glUniform4f(GLES31.glGetUniformLocation(pPlanes, "blk"), black[0], black[1], black[2], black[3]);
        u(pPlanes, "scale", rk);
        u(pPlanes, "clipG", clipG);
        u(pPlanes, "doNear", doNear ? 1 : 0);
        dispatch2();
        use(pSamples);
        u(pSamples, "M", M);
        GLES31.glDispatchCompute(groups(M, L1), 1, 1);
        barrier();
        check("planes / samples");
        FloatBuffer m = map(GLES31.GL_SHADER_STORAGE_BUFFER, B_SMP, 0, (long) M * 6 * 4);
        smpMapped = true;                         // unmapped by weights() / the next frame
        return m;
    }

    @Override public double weights(double[] bias, boolean doNear, boolean doClipQ, double rS, double rO,
                                    double kS2, double kO2, double RR, float rk, boolean oldErode) {
        endSamples();
        use(pRob);
        u(pRob, "doNear", doNear ? 1 : 0);
        u(pRob, "doClipQ", doClipQ ? 1 : 0);
        GLES31.glUniform3f(GLES31.glGetUniformLocation(pRob, "bias"), (float) bias[0], (float) bias[1], (float) bias[2]);
        u(pRob, "rS", (float) rS);
        u(pRob, "rO", (float) rO);
        u(pRob, "kS2", (float) kS2);
        u(pRob, "kO2", (float) kO2);
        u(pRob, "RR", (float) RR);
        u(pRob, "rk", rk);
        u(pRob, "ROB_STRUCT", (float) MergeCore.robStruct());
        u(pRob, "ROB_LO", (float) MergeCore.robLo());
        u(pRob, "ROB_HI", (float) MergeCore.robHi());
        dispatch2();
        // robustPost: erosion (near clipped parts: the quad's own value), blur, near clipped parts
        // capped by the eroded value
        erode(0, P_T1, true, oldErode);
        gauss(P_T1, P_T2, P_WE, 1);               // MergeCore: gauss(er, 0.7) -> radius 1
        minp(P_WE, P_T1, true);
        check("weights");
        FloatBuffer m = map(GLES31.GL_SHADER_STORAGE_BUFFER, B_AUX, 4L * N * P_WE, 4L * N);
        double s = 0;
        for (int i = 0; i < N; i += 31) s += m.get(i);
        unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
        return s / ((N + 30) / 31);
    }

    @Override public double[] compareWeights(float[] cpu) {
        FloatBuffer m = map(GLES31.GL_SHADER_STORAGE_BUFFER, B_AUX, 4L * N * P_WE, 4L * N);
        double mx = 0, sum = 0, bad = 0;
        for (int i = 0; i < N; i++) {
            double d = Math.abs(cpu[i] - m.get(i));
            if (!(d <= 1)) d = 1;                     // NaN
            sum += d;
            if (d > mx) mx = d;
            if (d > 0.02) bad++;
        }
        unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
        return new double[]{mx, sum, bad};
    }

    @Override public void longMaps(float rk, float lo, float hi, int grow, int featherR, boolean featherCap,
                                   boolean clipPixel, int greenR, float greenBlack) {
        // use map: the clip ramp of the aligned frame (u0) ...
        use(pUse0);
        shiftUniforms(pUse0);
        u(pUse0, "lo", lo);
        u(pUse0, "hi", hi);
        u(pUse0, "dst", P_USE);
        dispatch2();
        usePlane = P_USE;
        if (!clipPixel) {
            // ... eroded CLIP_GROW times, feathered, capped by u0
            int cur = P_USE;
            for (int g = 0; g < grow; g++) {
                int dst = cur == P_T1 ? P_T2 : P_T1;
                erode(cur, dst, false, false);
                cur = dst;
            }
            int tmp = cur == P_T1 ? P_T2 : P_T1;
            gauss(cur, tmp, P_T3, featherR);
            if (featherCap) minp(P_T3, P_USE, false);
            usePlane = P_T3;
        }
        // blurred green of the longer frame (reference units) for its noise-model weight
        use(pGreen);
        u(pGreen, "ga", ds[1]);
        u(pGreen, "gb", ds[2]);
        u(pGreen, "gbk", greenBlack);
        u(pGreen, "rk", rk);
        u(pGreen, "dst", P_T1);
        dispatch2();
        gauss(P_T1, P_T2, P_GM, greenR);
        check("long maps");
    }

    @Override public void accumulate(boolean isLong, boolean natK, float rk, double kS, double kO, double kR) {
        use(pAcc);
        shiftUniforms(pAcc);
        GLES31.glUniform4f(GLES31.glGetUniformLocation(pAcc, "blk"), black[0], black[1], black[2], black[3]);
        u(pAcc, "isLong", isLong ? 1 : 0);
        u(pAcc, "natK", natK ? 1 : 0);
        u(pAcc, "rk", rk);
        u(pAcc, "kS", (float) kS);
        u(pAcc, "kO", (float) kO);
        u(pAcc, "kR", (float) kR);
        u(pAcc, "usePl", usePlane);
        dispatch2();
        check("accumulate");
    }

    @Override public void readAccumulator(float[] acc, float[] wsum) {
        endSamples();
        FloatBuffer a = map(GLES31.GL_SHADER_STORAGE_BUFFER, B_ACC, 0, 4L * W * H);
        a.get(acc);
        unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
        FloatBuffer w = map(GLES31.GL_SHADER_STORAGE_BUFFER, B_WS, 0, 4L * N);
        w.get(wsum);
        unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
    }


    // ------------------------------------------------------------------ end of the merge (item 21, part 1)

    @Override public int[] finishMerge(boolean hot, boolean newHot, float warmNb, boolean den, double rS, double rO,
                                       float denK, float denSpace, double denMin, double R) {
        endSamples();
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[B_CNT]);
        GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 16, IntBuffer.wrap(new int[4]));
        use(pDiv);                                // tmp = acc / wsum
        dispatch2();
        use(pHot);                                // acc = tmp without hot pixels
        u(pHot, "H", H);
        GLES31.glUniform4f(GLES31.glGetUniformLocation(pHot, "blk"), black[0], black[1], black[2], black[3]);
        u(pHot, "doHot", hot ? 1 : 0);
        u(pHot, "newHot", newHot ? 1 : 0);
        u(pHot, "warmNb", warmNb);
        dispatch(W, H);
        if (den) {
            GLES31.glBindBuffer(GLES31.GL_COPY_READ_BUFFER, buf[B_ACC]);
            GLES31.glBindBuffer(GLES31.GL_COPY_WRITE_BUFFER, buf[B_TMP]);
            GLES31.glCopyBufferSubData(GLES31.GL_COPY_READ_BUFFER, GLES31.GL_COPY_WRITE_BUFFER, 0, 0, 4 * W * H);
            barrier();
            use(pDen);
            u(pDen, "H", H);
            GLES31.glUniform4f(GLES31.glGetUniformLocation(pDen, "blk"), black[0], black[1], black[2], black[3]);
            u(pDen, "rS", (float) rS);
            u(pDen, "rO", (float) rO);
            u(pDen, "R", (float) R);
            u(pDen, "denK2", denK * denK);
            u(pDen, "invDs", 1f / (2f * denSpace * denSpace));
            u(pDen, "denMin", (float) denMin);
            dispatch(W, H);
        }
        check("merge end");
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[B_CNT]);
        ByteBuffer bb = (ByteBuffer) GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 16, GLES31.GL_MAP_READ_BIT);
        if (bb == null) throw new IllegalStateException("map counters failed");
        IntBuffer ib = bb.order(ByteOrder.nativeOrder()).asIntBuffer();
        int[] c = {ib.get(0), ib.get(1)};
        unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
        return c;
    }

    @Override public double[] compareFinal(float[] acc) {
        FloatBuffer a = map(GLES31.GL_SHADER_STORAGE_BUFFER, B_ACC, 0, 4L * W * H);
        double mx = 0, sum = 0, bad = 0;
        for (int j = 0; j < W * H; j++) {
            double d = Math.abs(acc[j] - a.get(j));
            if (!(d < 1e9)) d = 1e9;                  // NaN / inf
            sum += d;
            if (d > mx) mx = d;
            if (d > 0.5) bad++;
        }
        unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
        return new double[]{mx, sum, bad, (double) W * H};
    }

    // ------------------------------------------------------------------ develop (item 21, part 2)

    private int cw, ch;

    @Override public void beginDevelop(int cw, int ch) {
        endSamples();
        this.cw = cw;
        this.ch = ch;
        // the merge-only buffers are freed (GPU memory), the accumulator (-> merged data) and the
        // AUX planes are reused
        for (int b : new int[]{B_RAW, B_FPL, B_CLIP, B_REF, B_SMP, B_OUT, B_TMP}) {
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[b]);
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 4, null, GLES31.GL_DYNAMIC_COPY);
        }
        alloc(B_PX, 4L * cw * ch);
        alloc(B_DEV, 4L * 3 * cw * ch);
        float[] t = new float[4097];
        for (int i = 0; i <= 4096; i++) {
            double l = i / 4096.0;
            t[i] = (float) (l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1 / 2.4) - 0.055);
        }
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[B_SRGB]);
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 4 * t.length, FloatBuffer.wrap(t), GLES31.GL_STATIC_DRAW);
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, B_SRGB, buf[B_SRGB]);
        check("develop buffers");
    }

    @Override public void render(float[] m, float[] clipQ, float[] gmap, float[] lift, float[] toneRatio, int lw4,
                                 int lh4, float[][] shading, int shCh, float[] ccm, float minGain, float lk, int cx,
                                 int cy, int[] color) {
        upload(B_ACC, 0, m);
        upload(B_AUX, 4L * N * P_WE, clipQ);
        upload(B_AUX, 4L * N * P_USE, gmap);
        if (lift != null) upload(B_AUX, 4L * N * P_GM, lift);
        if (toneRatio != null) upload(B_AUX, 4L * N * P_T1, toneRatio);
        int nR = 0, rowLen = 4;
        float[] sh = new float[8];
        if (shading != null && shading.length >= 2) {
            nR = shading.length;
            rowLen = shading[0].length;
            sh = new float[nR * rowLen];
            for (int r = 0; r < nR; r++) System.arraycopy(shading[r], 0, sh, r * rowLen, rowLen);
        }
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[B_SHD]);
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 4 * sh.length, FloatBuffer.wrap(sh), GLES31.GL_STATIC_DRAW);
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, B_SHD, buf[B_SHD]);
        use(pRender);
        u(pRender, "H", H);
        u(pRender, "cx", cx);
        u(pRender, "cy", cy);
        u(pRender, "cw", cw);
        u(pRender, "ch", ch);
        u(pRender, "hasLift", lift != null ? 1 : 0);
        u(pRender, "lw4", lw4);
        u(pRender, "lh4", lh4);
        u(pRender, "hasShade", nR >= 2 ? 1 : 0);
        u(pRender, "shCh", shCh);
        u(pRender, "nR", nR);
        u(pRender, "rowLen", rowLen);
        u(pRender, "demClamp", MergeCore.DEM_CLAMP ? 1 : 0);
        u(pRender, "oldShoulder", MergeCore.OLD_SHOULDER ? 1 : 0);
        GLES31.glUniform4i(GLES31.glGetUniformLocation(pRender, "colorv"), color[0], color[1], color[2], color[3]);
        GLES31.glUniform1fv(GLES31.glGetUniformLocation(pRender, "ccm"), 9, ccm, 0);
        u(pRender, "minGain", minGain);
        u(pRender, "lk", lk);
        u(pRender, "DEFR", MergeCore.DEFRINGE_FROM);
        u(pRender, "CDESAT", MergeCore.CLIP_DESAT);
        u(pRender, "SAT", MergeCore.SATURATION);
        u(pRender, "SHOULDER", MergeCore.SHOULDER);
        u(pRender, "CONTRAST", MergeCore.CONTRAST);
        u(pRender, "TONE_MAX", toneRatio != null ? MergeCore.TONE_MAX : 0f);
        dispatch(cw, ch);
        check("render");
    }

    private void boxg(int src, int dst, int pw, int ph, int dir, int r) {
        use(pBoxG);
        u(pBoxG, "src", src);
        u(pBoxG, "dst", dst);
        u(pBoxG, "pw", pw);
        u(pBoxG, "ph", ph);
        u(pBoxG, "dir", dir);
        u(pBoxG, "r", r);
        dispatch(pw, ph);
    }

    private void dev2(int p) {
        use(p);
        u(p, "cw", cw);
        u(p, "ch", ch);
    }

    @Override public void chroma() {
        int w2 = cw / 2, h2 = ch / 2, q = w2 * h2;
        dev2(pC1);                                // cb @0, cr @q
        dispatch(w2, h2);
        use(pMed3);
        u(pMed3, "pw", w2);
        u(pMed3, "ph", h2);
        u(pMed3, "src", 0);
        u(pMed3, "dst", 2 * q);
        dispatch(w2, h2);
        u(pMed3, "src", q);
        u(pMed3, "dst", 3 * q);
        dispatch(w2, h2);
        boxg(2 * q, 0, w2, h2, 0, 2);             // box5: cb -> @4q, cr -> @5q
        boxg(0, 4 * q, w2, h2, 1, 2);
        boxg(3 * q, q, w2, h2, 0, 2);
        boxg(q, 5 * q, w2, h2, 1, 2);
        dev2(pC4);
        u(pC4, "q", q);
        dispatch(cw, ch);
        check("chroma");
    }

    @Override public void localContrast(float amount, int r, float eps, float clMax) {
        int lw = (cw + 3) / 4, lh = (ch + 3) / 4, L = lw * lh;
        dev2(pL1);                                // m @0, m2 @L
        u(pL1, "lw", lw);
        u(pL1, "lh", lh);
        dispatch(lw, lh);
        boxg(0, 2 * L, lw, lh, 0, r);
        boxg(2 * L, 3 * L, lw, lh, 1, r);         // mean @3L
        boxg(L, 2 * L, lw, lh, 0, r);
        boxg(2 * L, 4 * L, lw, lh, 1, r);         // mean2 @4L
        use(pL2);                                 // a @0, b @L
        u(pL2, "L", L);
        u(pL2, "pw", lw);
        u(pL2, "ph", lh);
        u(pL2, "e2", eps * eps);
        dispatch(lw, lh);
        boxg(0, 2 * L, lw, lh, 0, r);
        boxg(2 * L, 3 * L, lw, lh, 1, r);         // ma @3L
        boxg(L, 2 * L, lw, lh, 0, r);
        boxg(2 * L, 4 * L, lw, lh, 1, r);         // mb @4L
        dev2(pL3);
        u(pL3, "lw", lw);
        u(pL3, "lh", lh);
        u(pL3, "L", L);
        u(pL3, "amount", amount);
        u(pL3, "clMax", clMax);
        u(pL3, "SDARK", MergeCore.SHARP_DARK);
        dispatch(cw, ch);
        check("local contrast");
    }

    @Override public void sharpen(float amount) {
        int P = cw * ch;
        dev2(pS1);                                // y @0
        dispatch(cw, ch);
        int r = Math.max(1, Math.round(MergeCore.SHARP_SIGMA * 0.8f));
        boxg(0, P, cw, ch, 0, r);                 // gauss: three box passes, blur @2P
        boxg(P, 2 * P, cw, ch, 1, r);
        boxg(2 * P, P, cw, ch, 0, r);
        boxg(P, 2 * P, cw, ch, 1, r);
        boxg(2 * P, P, cw, ch, 0, r);
        boxg(P, 2 * P, cw, ch, 1, r);
        dev2(pS2);
        u(pS2, "amount", amount);
        u(pS2, "TH", MergeCore.SHARP_THRESH);
        u(pS2, "SMAX", MergeCore.SHARP_MAX);
        u(pS2, "SDARK", MergeCore.SHARP_DARK);
        u(pS2, "MARGIN", MergeCore.SHARP_MARGIN);
        u(pS2, "oldSharp", MergeCore.OLD_SHARP ? 1 : 0);
        dispatch(cw, ch);
        check("sharpen");
    }

    private IntBuffer mapPx() {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buf[B_PX]);
        ByteBuffer bb = (ByteBuffer) GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4 * cw * ch,
                GLES31.GL_MAP_READ_BIT);
        if (bb == null) throw new IllegalStateException("map pixels failed");
        return bb.order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    @Override public void readPixels(int[] out) {
        IntBuffer p = mapPx();
        p.get(out);
        unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
    }

    @Override public double[] comparePixels(int[] cpu) {
        IntBuffer p = mapPx();
        double mx = 0, sum = 0, bad = 0;
        for (int i = 0; i < cw * ch; i++) {
            int a = cpu[i], b = p.get(i);
            int d = Math.max(Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)), Math.max(
                    Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)), Math.abs((a & 255) - (b & 255))));
            sum += d;
            if (d > mx) mx = d;
            if (d > 1) bad++;
        }
        unmap(GLES31.GL_SHADER_STORAGE_BUFFER);
        return new double[]{mx, sum, bad, (double) cw * ch};
    }

    @Override public void close() {
        if (dpy != EGL14.EGL_NO_DISPLAY && ctx != EGL14.EGL_NO_CONTEXT && !ctx.equals(EGL14.eglGetCurrentContext())) {
            try {                                     // released by another thread: take it here
                EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
            } catch (Throwable ignored) {
            }
        }
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            // GL objects only from the thread where this context is current (a stale GPU closed by
            // another merge: destroying the context frees them)
            if (ctx != EGL14.EGL_NO_CONTEXT && ctx.equals(EGL14.eglGetCurrentContext())) {
                try {
                    endSamples();
                    GLES31.glDeleteBuffers(NB, buf, 0);
                    for (int p : new int[]{pPlanes, pSamples, pRob, pErode, pBox, pMin, pUse0, pGreen, pAccInit, pAcc,
                            pDiv, pHot, pDen, pRender, pC1, pMed3, pBoxG, pC4, pL1, pL2, pL3, pS1, pS2,
                            pBelow, pWarm, pBmed, pAddB, pRatio, pCopyP, pDown8, pCoarse, pRefine, pTiles}) {
                        if (p != 0) GLES31.glDeleteProgram(p);
                    }
                } catch (Throwable ignored) {
                }
            }
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglReleaseThread();
        }
        dpy = EGL14.EGL_NO_DISPLAY;
        ctx = EGL14.EGL_NO_CONTEXT;
        surf = EGL14.EGL_NO_SURFACE;
    }

    // ------------------------------------------------------------------ shaders

    private static final String HEAD = "#version 310 es\n"
            + "precision highp float;\nprecision highp int;\n"
            + "uniform int W, W2, H2;\n";

    private static final String L2 = "layout(local_size_x = 16, local_size_y = 16) in;\n";

    private static final String MAIN2 = "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y);\n"
            + "  if (x >= W2 || y >= H2) return;\n"
            + "  int n = W2 * H2, i = y * W2 + x;\n";

    /** Raw access + the smooth tile shift (MergeCore.shiftF). */
    private static final String SHIFT = ""
            + "layout(std430, binding = 0) readonly buffer B0 { uint raw[]; };\n"
            + "layout(std430, binding = 1) readonly buffer B1 { int sh[]; };\n"
            + "uniform int TILE, TY, TX;\n"
            + "float rget(int y, int x) {\n"
            + "  int i = y * W + x; uint v = raw[i >> 1];\n"
            + "  return float((i & 1) == 0 ? (v & 0xFFFFu) : (v >> 16));\n"
            + "}\n"
            + "float shv(int i, int j, int k) { return float(sh[(i * TX + j) * 2 + k]); }\n"
            + "float shiftF(int qy, int qx, int k) {\n"
            + "  if (TY == 1 || TX == 1) return shv(min(TY - 1, qy / TILE), min(TX - 1, qx / TILE), k);\n"
            + "  float fy = max(0.0, min(float(TY) - 1.001, (float(qy) + 0.5) / float(TILE) - 0.5));\n"
            + "  float fx = max(0.0, min(float(TX) - 1.001, (float(qx) + 0.5) / float(TILE) - 0.5));\n"
            + "  int i = int(fy), j = int(fx); float a = fy - float(i), b = fx - float(j);\n"
            + "  return (shv(i, j, k) * (1.0 - b) + shv(i, j + 1, k) * b) * (1.0 - a)\n"
            + "       + (shv(i + 1, j, k) * (1.0 - b) + shv(i + 1, j + 1, k) * b) * a;\n"
            + "}\n";

    /** The frame at the aligned position (MergeCore.quadPlanes with sh) + clip mask (robustWeights). */
    private static final String PLANES = HEAD + L2 + SHIFT
            + "layout(std430, binding = 2) writeonly buffer B2 { float fpl[]; };\n"
            + "layout(std430, binding = 3) writeonly buffer B3 { uint clipm[]; };\n"
            + "uniform int doNear;\n"
            + "uniform ivec4 ds;\nuniform vec4 blk;\nuniform float scale, clipG;\n"
            + MAIN2
            + "  float fy = max(0.0, min(float(H2) - 1.001, float(y) + shiftF(y, x, 0)));\n"
            + "  float fx = max(0.0, min(float(W2) - 1.001, float(x) + shiftF(y, x, 1)));\n"
            + "  int sy = min(int(fy), H2 - 2), sx = min(int(fx), W2 - 2);\n"
            + "  float ay = fy - float(sy), ax = fx - float(sx);\n"
            + "  float v[4];\n"
            + "  for (int j = 0; j < 4; j++) {\n"
            + "    int d = ds[j], yy = 2 * sy + d / 2, xx = 2 * sx + d % 2;\n"
            + "    v[j] = ((1.0 - ay) * ((1.0 - ax) * rget(yy, xx) + ax * rget(yy, xx + 2))\n"
            + "          + ay * ((1.0 - ax) * rget(yy + 2, xx) + ax * rget(yy + 2, xx + 2))) - blk[d];\n"
            + "  }\n"
            + "  float r = v[0] / scale, g = (v[1] + v[2]) / 2.0 / scale, b = v[3] / scale;\n"
            + "  fpl[i] = r; fpl[n + i] = g; fpl[2 * n + i] = b;\n"
            + "  clipm[i] = (doNear == 1 && max(r, max(g, b)) >= clipG) ? 1u : 0u;\n"
            + "}\n";

    private static final String M3 = ""
            + "float m3(bool f, int c, int x, int y) {\n"
            + "  int n = W2 * H2, o = c * n;\n"
            + "  int xa = max(0, x - 1), xb = min(W2 - 1, x + 1);\n"
            + "  int ya = max(0, y - 1) * W2, yc = y * W2, yb = min(H2 - 1, y + 1) * W2;\n"
            + "  if (f) return (fpl[o + ya + xa] + fpl[o + ya + x] + fpl[o + ya + xb] + fpl[o + yc + xa] + fpl[o + yc + x]\n"
            + "      + fpl[o + yc + xb] + fpl[o + yb + xa] + fpl[o + yb + x] + fpl[o + yb + xb]) / 9.0;\n"
            + "  return (rpl[o + ya + xa] + rpl[o + ya + x] + rpl[o + ya + xb] + rpl[o + yc + xa] + rpl[o + yc + x]\n"
            + "      + rpl[o + yc + xb] + rpl[o + yb + xa] + rpl[o + yb + x] + rpl[o + yb + xb]) / 9.0;\n"
            + "}\n";

    /** 3x3 means at every 7th quad (the bias, MergeCore.robustWeightsCpu). */
    private static final String SAMPLES = HEAD
            + "layout(local_size_x = 256) in;\n"
            + "layout(std430, binding = 2) readonly buffer B2 { float fpl[]; };\n"
            + "layout(std430, binding = 4) readonly buffer B4 { float rpl[]; };\n"
            + "layout(std430, binding = 5) writeonly buffer B5 { float smp[]; };\n"
            + "uniform int M;\n"
            + M3
            + "void main() {\n"
            + "  int j = int(gl_GlobalInvocationID.x);\n"
            + "  if (j >= M) return;\n"
            + "  int i = 7 * j, qx = i % W2, qy = i / W2;\n"
            + "  for (int c = 0; c < 3; c++) {\n"
            + "    smp[j * 6 + 2 * c] = m3(false, c, qx, qy);\n"
            + "    smp[j * 6 + 2 * c + 1] = m3(true, c, qx, qy);\n"
            + "  }\n"
            + "}\n";

    /** The per-quad robustness (MergeCore.robustWeightsCpu), + 2 near clipped parts. */
    private static final String ROB = HEAD + L2
            + "layout(std430, binding = 2) readonly buffer B2 { float fpl[]; };\n"
            + "layout(std430, binding = 3) readonly buffer B3 { uint clipm[]; };\n"
            + "layout(std430, binding = 4) readonly buffer B4 { float rpl[]; };\n"
            + "layout(std430, binding = 6) writeonly buffer B6 { float rob[]; };\n"
            + "uniform int doNear, doClipQ;\n"
            + "uniform vec3 bias;\n"
            + "uniform float rS, rO, kS2, kO2, RR, rk, ROB_STRUCT, ROB_LO, ROB_HI;\n"
            + M3
            + "float v3(int c, int x, int y, float mean) {\n"
            + "  int o = c * W2 * H2; float s = 0.0;\n"
            + "  for (int yy = y - 1; yy <= y + 1; yy++) {\n"
            + "    int ry = clamp(yy, 0, H2 - 1) * W2;\n"
            + "    for (int xx = x - 1; xx <= x + 1; xx++) {\n"
            + "      float d = rpl[o + ry + clamp(xx, 0, W2 - 1)] - mean; s += d * d;\n"
            + "    }\n"
            + "  }\n"
            + "  return s / 9.0;\n"
            + "}\n"
            + MAIN2
            + "  bool near = false;\n"
            + "  if (doNear == 1) {\n"
            + "    for (int yy = max(0, y - 2); yy <= min(H2 - 1, y + 2) && !near; yy++)\n"
            + "      for (int xx = max(0, x - 2); xx <= min(W2 - 1, x + 2); xx++)\n"
            + "        if (clipm[yy * W2 + xx] != 0u) { near = true; break; }\n"
            + "  }\n"
            + "  float add = near ? 2.0 : 0.0;\n"
            + "  if (doClipQ == 1 && clipm[i] != 0u) { rob[i] = 1.0 + add; return; }\n"
            + "  float st = near ? 0.0 : ROB_STRUCT;\n"
            + "  float dsum = 0.0;\n"
            + "  for (int c = 0; c < 3; c++) {\n"
            + "    float mr = m3(false, c, x, y), mf = m3(true, c, x, y);\n"
            + "    float d = mr - mf - bias[c];\n"
            + "    float vr = (rS * max(0.0, mr) / RR + rO) * RR * RR;\n"
            + "    float own = max(0.0, mf) * rk;\n"
            + "    float vf = (kS2 * own / RR + kO2) * RR * RR / (rk * rk);\n"
            + "    float vn = (vr + vf) / 9.0;\n"
            + "    float vs = max(0.0, v3(c, x, y, mr) - vr);\n"
            + "    dsum += d * d / (vn + st * vs);\n"
            + "  }\n"
            + "  float dd = sqrt(dsum / 3.0);\n"
            + "  if (near) {\n"
            + "    float qsum = 0.0;\n"
            + "    for (int c = 0; c < 3; c++) {\n"
            + "      float pr = rpl[c * n + i], pf = fpl[c * n + i];\n"
            + "      float d = pr - pf - bias[c];\n"
            + "      float vr = (rS * max(0.0, pr) / RR + rO) * RR * RR;\n"
            + "      float vf = (kS2 * max(0.0, pf) * rk / RR + kO2) * RR * RR / (rk * rk);\n"
            + "      qsum += d * d / (vr + vf);\n"
            + "    }\n"
            + "    dd = max(dd, sqrt(qsum / 3.0));\n"
            + "  }\n"
            + "  rob[i] = clamp((ROB_HI - dd) / (ROB_HI - ROB_LO), 0.0, 1.0) + add;\n"
            + "}\n";

    private static final String AUXB = "layout(std430, binding = 7) buffer B7 { float aux[]; };\n";
    private static final String ROBB = "layout(std430, binding = 6) readonly buffer B6 { float rob[]; };\n";

    /** MergeCore.erode3 (mode 1: of the robustness, near clipped parts the quad's own value). */
    private static final String ERODE = HEAD + L2 + ROBB + AUXB
            + "uniform int src, dst, mode, oldErode;\n"
            + "float dec(float v) { return v >= 1.5 ? v - 2.0 : v; }\n"
            + MAIN2
            + "  float m = 3.0e38;\n"
            + "  for (int dy = -1; dy <= 1; dy++) {\n"
            + "    int yy = clamp(y + dy, 0, H2 - 1);\n"
            + "    for (int dx = -1; dx <= 1; dx++) {\n"
            + "      int j = yy * W2 + clamp(x + dx, 0, W2 - 1);\n"
            + "      float rv = rob[j], av = aux[src * n + j];\n"
            + "      m = min(m, mode == 1 ? dec(rv) : av);\n"
            + "    }\n"
            + "  }\n"
            + "  float ri = rob[i];\n"
            + "  if (mode == 1 && oldErode == 0 && ri >= 1.5) m = dec(ri);\n"
            + "  aux[dst * n + i] = m;\n"
            + "}\n";

    /** One box pass of MergeCore.gauss (dir 0: x, 1: y; borders repeated). */
    private static final String BOX = HEAD + L2 + AUXB
            + "uniform int src, dst, dir, r;\n"
            + MAIN2
            + "  float s = 0.0;\n"
            + "  for (int t = -r; t <= r; t++) {\n"
            + "    int j = dir == 0 ? y * W2 + clamp(x + t, 0, W2 - 1) : clamp(y + t, 0, H2 - 1) * W2 + x;\n"
            + "    s += aux[src * n + j];\n"
            + "  }\n"
            + "  aux[dst * n + i] = s / float(2 * r + 1);\n"
            + "}\n";

    /** a = min(a, b) (nearOnly: only near clipped parts, from the robustness flags). */
    private static final String MINP = HEAD + L2 + ROBB + AUXB
            + "uniform int a, b, nearOnly;\n"
            + MAIN2
            + "  if (nearOnly == 1 && rob[i] < 1.5) return;\n"
            + "  aux[a * n + i] = min(aux[a * n + i], aux[b * n + i]);\n"
            + "}\n";

    /** Longer frame: the clip ramp at the aligned position (MergeCore merge loop, u0). */
    private static final String USE0 = HEAD + L2 + SHIFT + AUXB
            + "uniform float lo, hi;\nuniform int dst;\n"
            + MAIN2
            + "  float fy = max(0.0, min(float(H2) - 1.001, float(y) + shiftF(y, x, 0)));\n"
            + "  float fx = max(0.0, min(float(W2) - 1.001, float(x) + shiftF(y, x, 1)));\n"
            + "  int sy = int(fy), sx = int(fx);\n"
            + "  float mx = 0.0;\n"
            + "  for (int d = 0; d < 4; d++) {\n"
            + "    int yy = 2 * sy + d / 2, xx = 2 * sx + d % 2;\n"
            + "    mx = max(mx, max(max(rget(yy, xx), rget(yy, xx + 2)), max(rget(yy + 2, xx), rget(yy + 2, xx + 2))));\n"
            + "  }\n"
            + "  aux[dst * n + i] = clamp((hi - mx) / (hi - lo), 0.0, 1.0);\n"
            + "}\n";

    /** Half-res green of the frame, black subtracted, / rk (MergeCore.greenHalf + the scaling). */
    private static final String GREEN = HEAD + L2 + SHIFT + AUXB
            + "uniform int ga, gb, dst;\nuniform float gbk, rk;\n"
            + MAIN2
            + "  float g = (rget(2 * y + ga / 2, 2 * x + ga % 2) + rget(2 * y + gb / 2, 2 * x + gb % 2)) / 2.0 - gbk;\n"
            + "  aux[dst * n + i] = g / rk;\n"
            + "}\n";

    private static final String ACCB = ""
            + "layout(std430, binding = 8) buffer B8 { float acc[]; };\n"
            + "layout(std430, binding = 9) buffer B9 { float ws[]; };\n";

    /** Accumulator = the reference, weight sum 1. */
    private static final String ACCINIT = HEAD + L2
            + "layout(std430, binding = 0) readonly buffer B0 { uint raw[]; };\n"
            + ACCB
            + "float rget(int y, int x) {\n"
            + "  int i = y * W + x; uint v = raw[i >> 1];\n"
            + "  return float((i & 1) == 0 ? (v & 0xFFFFu) : (v >> 16));\n"
            + "}\n"
            + MAIN2
            + "  for (int d = 0; d < 4; d++) {\n"
            + "    int yy = 2 * y + d / 2, xx = 2 * x + d % 2;\n"
            + "    acc[yy * W + xx] = rget(yy, xx);\n"
            + "  }\n"
            + "  ws[i] = 1.0;\n"
            + "}\n";

    /** The accumulation of one frame (MergeCore merge loop, the same formula). */
    private static final String ACC = HEAD + L2 + SHIFT + AUXB + ACCB
            + "uniform int isLong, natK, usePl;\nuniform float rk, kS, kO, kR;\nuniform vec4 blk;\n"
            + MAIN2
            + "  float wq = natK == 1 ? 1.0 : aux[" + P_WE + " * n + i];\n"
            + "  if (wq <= 0.0) return;\n"
            + "  float fy = max(0.0, min(float(H2) - 1.001, float(y) + shiftF(y, x, 0)));\n"
            + "  float fx = max(0.0, min(float(W2) - 1.001, float(x) + shiftF(y, x, 1)));\n"
            + "  int sy = int(fy), sx = int(fx);\n"
            + "  float ay = fy - float(sy), ax = fx - float(sx);\n"
            + "  float w00 = (1.0 - ay) * (1.0 - ax), w01 = (1.0 - ay) * ax, w10 = ay * (1.0 - ax), w11 = ay * ax;\n"
            + "  float wk = rk;\n"
            + "  if (isLong == 1) {\n"
            + "    wq *= aux[usePl * n + i];\n"
            + "    if (wq <= 1e-4) return;\n"
            + "    float own = max(0.0, aux[" + P_GM + " * n + sy * W2 + sx]) * rk;\n"
            + "    float vk = (kS * own / kR + kO) * kR * kR;\n"
            + "    wk = min(rk * rk, max(0.01 * rk, rk * rk * aux[" + P_RV + " * n + i] / vk));\n"
            + "  }\n"
            + "  for (int d = 0; d < 4; d++) {\n"
            + "    int yy = 2 * sy + d / 2, xx = 2 * sx + d % 2;\n"
            + "    float v = w00 * rget(yy, xx) + w01 * rget(yy, xx + 2) + w10 * rget(yy + 2, xx) + w11 * rget(yy + 2, xx + 2);\n"
            + "    if (isLong == 1) { float bk = blk[d]; v = wk * (bk + (v - bk) / rk); }\n"
            + "    acc[(2 * y + d / 2) * W + 2 * x + d % 2] += wq * v;\n"
            + "  }\n"
            + "  ws[i] += isLong == 1 ? wq * wk : wq;\n"
            + "}\n";

    // ------------------------------------------------------------------ shaders: end of the merge

    private static final String L2F = "layout(local_size_x = 16, local_size_y = 16) in;\n";
    private static final String TMPB = "layout(std430, binding = 10) buffer B10 { float tmp[]; };\n";
    private static final String CNTB = "layout(std430, binding = 11) buffer B11 { uint cnt[]; };\n";

    /** tmp = acc / wsum (per Bayer quad). */
    private static final String DIV = HEAD + L2 + ACCB + TMPB
            + MAIN2
            + "  float w = ws[i];\n"
            + "  for (int d = 0; d < 4; d++) {\n"
            + "    int j = (2 * y + d / 2) * W + 2 * x + d % 2;\n"
            + "    float a = acc[j];\n"
            + "    tmp[j] = a / w;\n"
            + "  }\n"
            + "}\n";

    /** acc = tmp without hot pixels (MergeCore.removeHotPixels). */
    private static final String HOT = HEAD + L2F + ACCB + TMPB + CNTB
            + "uniform int H, doHot, newHot;\nuniform vec4 blk;\nuniform float warmNb;\n"
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y);\n"
            + "  if (x >= W || y >= H) return;\n"
            + "  int i = y * W + x;\n"
            + "  float v = tmp[i];\n"
            + "  if (doHot == 0 || y < 3 || y >= H - 3 || x < 3 || x >= W - 3) { acc[i] = v; return; }\n"
            + "  float bk = blk[(y % 2) * 2 + x % 2];\n"
            + "  float nb[8]; int n = 0; float mx = -1.0;\n"
            + "  for (int dy = -2; dy <= 2; dy += 2) {\n"
            + "    for (int dx = -2; dx <= 2; dx += 2) {\n"
            + "      if (dy == 0 && dx == 0) continue;\n"
            + "      float u = tmp[(y + dy) * W + x + dx];\n"
            + "      nb[n] = u; n++; mx = max(mx, u);\n"
            + "    }\n"
            + "  }\n"
            + "  if (v <= mx + 12.0) { acc[i] = v; return; }\n"
            + "  for (int a = 1; a < 8; a++) {\n"
            + "    float k = nb[a]; int b = a - 1;\n"
            + "    while (b >= 0 && nb[b] > k) { nb[b + 1] = nb[b]; b--; }\n"
            + "    nb[b + 1] = k;\n"
            + "  }\n"
            + "  float med = (nb[3] + nb[4]) / 2.0;\n"
            + "  float excess = v - max(mx, med);\n"
            + "  float raise = -1e9;\n"
            + "  int js[4] = int[4](i - 1, i + 1, i - W, i + W);\n"
            + "  for (int k = 0; k < 4; k++) {\n"
            + "    int j = js[k];\n"
            + "    float c0 = tmp[j], c1 = tmp[j - 2], c2 = tmp[j + 2], c3 = tmp[j - 2 * W], c4 = tmp[j + 2 * W];\n"
            + "    float e = c0 - (c1 + c2 + c3 + c4) / 4.0;\n"
            + "    raise = max(raise, e);\n"
            + "  }\n"
            + "  if (newHot == 1 && raise > warmNb * excess) { acc[i] = v; return; }\n"
            + "  if (v - bk > 2.0 * (med - bk) + 12.0) { acc[i] = med; atomicAdd(cnt[0], 1u); }\n"
            + "  else acc[i] = v;\n"
            + "}\n";

    /** acc = adaptive bilateral denoise of tmp (MergeCore.adaptiveDenoise). */
    private static final String DEN = HEAD + L2F + ACCB + TMPB + CNTB
            + "uniform int H;\nuniform vec4 blk;\nuniform float rS, rO, R, denK2, invDs, denMin;\n"
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y);\n"
            + "  if (x < 4 || y < 4 || x >= W - 4 || y >= H - 4) return;\n"
            + "  int i = y * W + x;\n"
            + "  float bk = blk[(y & 1) * 2 + (x & 1)];\n"
            + "  float s0 = tmp[i];\n"
            + "  float v = s0 - bk;\n"
            + "  float wq = ws[(y >> 1) * W2 + (x >> 1)];\n"
            + "  float var = (rS * max(0.0, v) / R + rO) * R * R / max(1.0, wq);\n"
            + "  float rng = denK2 * var;\n"
            + "  if (rng < denMin) return;\n"
            + "  float inv = 1.0 / (2.0 * rng), sw = 0.0, sv = 0.0;\n"
            + "  for (int dy = -2; dy <= 2; dy++) {\n"
            + "    int row = (y + 2 * dy) * W;\n"
            + "    for (int dx = -2; dx <= 2; dx++) {\n"
            + "      float u0 = tmp[row + x + 2 * dx];\n"
            + "      float u = u0 - bk, d = u - v;\n"
            + "      float w = exp(-float(dx * dx + dy * dy) * invDs) * exp(-d * d * inv);\n"
            + "      sw += w; sv += w * u;\n"
            + "    }\n"
            + "  }\n"
            + "  acc[i] = sv / sw + bk;\n"
            + "  atomicAdd(cnt[1], 1u);\n"
            + "}\n";

    // ------------------------------------------------------------------ shaders: develop

    private static final String PXB = "layout(std430, binding = 12) buffer B12 { uint px[]; };\n";
    private static final String DEVB = "layout(std430, binding = 13) buffer B13 { float dev[]; };\n";
    private static final String DEVU = "uniform int cw, ch;\n";
    private static final String PIX = ""
            + "int clamp8(float v) { return clamp(int(floor(v + 0.5)), 0, 255); }\n"
            + "float luma(uint c) { return 0.299 * float((c >> 16) & 255u) + 0.587 * float((c >> 8) & 255u) + 0.114 * float(c & 255u); }\n"
            + "uint addAll(uint c, float s) {\n"
            + "  float r = float((c >> 16) & 255u), g = float((c >> 8) & 255u), b = float(c & 255u);\n"
            + "  return 0xFF000000u | uint(clamp8(r + s)) << 16 | uint(clamp8(g + s)) << 8 | uint(clamp8(b + s));\n"
            + "}\n";

    /** develop() step 5: demosaic, colour, gain, defringe, shoulder, gamut, sRGB + S-curve. */
    private static final String RENDER = HEAD + L2F
            + "layout(std430, binding = 8) readonly buffer B8 { float m[]; };\n"
            + "layout(std430, binding = 7) readonly buffer B7 { float aux[]; };\n"
            + "layout(std430, binding = 14) readonly buffer B14 { float shd[]; };\n"
            + "layout(std430, binding = 15) readonly buffer B15 { float srgbT[]; };\n"
            + PXB
            + "uniform int H, cx, cy, cw, ch, hasLift, lw4, lh4, hasShade, shCh, nR, rowLen, demClamp, oldShoulder;\n"
            + "uniform ivec4 colorv;\nuniform float ccm[9];\nuniform float minGain, lk, DEFR, CDESAT, SAT, SHOULDER, CONTRAST, TONE_MAX;\n"
            + "float at(int y, int x) {\n"
            + "  if (y < 0) y = -y; if (x < 0) x = -x;\n"
            + "  if (y >= H) y = 2 * H - 2 - y; if (x >= W) x = 2 * W - 2 - x;\n"
            + "  return m[y * W + x];\n"
            + "}\n"
            + "float c2(float v, float a, float b) { return max(min(a, b), min(max(a, b), v)); }\n"
            + "float c4(float v, float a, float b, float c, float d) {\n"
            + "  float lo = min(min(a, b), min(c, d)), hi = max(max(a, b), max(c, d));\n"
            + "  return max(lo, min(hi, v));\n"
            + "}\n"
            + "vec3 dem(int y, int x) {\n"
            + "  int c = colorv[(y & 1) * 2 + (x & 1)];\n"
            + "  vec3 rgb;\n"
            + "  if (y >= 2 && x >= 2 && y < H - 2 && x < W - 2) {\n"
            + "    int i = y * W + x; float C = m[i];\n"
            + "    if (c == 1) {\n"
            + "      float diag = m[i - W - 1] + m[i - W + 1] + m[i + W - 1] + m[i + W + 1];\n"
            + "      float hz = (5.0 * C + 4.0 * (m[i - 1] + m[i + 1]) - diag - (m[i - 2] + m[i + 2])\n"
            + "          + 0.5 * (m[i - 2 * W] + m[i + 2 * W])) / 8.0;\n"
            + "      float vt = (5.0 * C + 4.0 * (m[i - W] + m[i + W]) - diag - (m[i - 2 * W] + m[i + 2 * W])\n"
            + "          + 0.5 * (m[i - 2] + m[i + 2])) / 8.0;\n"
            + "      if (demClamp == 1) {\n"
            + "        float a = m[i - 1], b = m[i + 1]; hz = c2(hz, a, b);\n"
            + "        a = m[i - W]; b = m[i + W]; vt = c2(vt, a, b);\n"
            + "      }\n"
            + "      rgb.g = C;\n"
            + "      if (colorv[(y & 1) * 2 + ((x + 1) & 1)] == 0) { rgb.r = hz; rgb.b = vt; } else { rgb.b = hz; rgb.r = vt; }\n"
            + "    } else {\n"
            + "      float cross2 = m[i - 2 * W] + m[i + 2 * W] + m[i - 2] + m[i + 2];\n"
            + "      float g = (4.0 * C + 2.0 * (m[i - W] + m[i + W] + m[i - 1] + m[i + 1]) - cross2) / 8.0;\n"
            + "      float o = (6.0 * C + 2.0 * (m[i - W - 1] + m[i - W + 1] + m[i + W - 1] + m[i + W + 1]) - 1.5 * cross2) / 8.0;\n"
            + "      if (demClamp == 1) {\n"
            + "        float a = m[i - W], b = m[i + W], e = m[i - 1], f = m[i + 1]; g = c4(g, a, b, e, f);\n"
            + "        a = m[i - W - 1]; b = m[i - W + 1]; e = m[i + W - 1]; f = m[i + W + 1]; o = c4(o, a, b, e, f);\n"
            + "      }\n"
            + "      rgb.g = g;\n"
            + "      if (c == 0) { rgb.r = C; rgb.b = o; } else { rgb.b = C; rgb.r = o; }\n"
            + "    }\n"
            + "    return rgb;\n"
            + "  }\n"
            + "  float C = at(y, x);\n"
            + "  if (c == 1) {\n"
            + "    int chh = colorv[(y % 2) * 2 + (x + 1) % 2];\n"
            + "    float hz = (5.0 * C + 4.0 * (at(y, x - 1) + at(y, x + 1))\n"
            + "        - (at(y - 1, x - 1) + at(y - 1, x + 1) + at(y + 1, x - 1) + at(y + 1, x + 1))\n"
            + "        - (at(y, x - 2) + at(y, x + 2)) + 0.5 * (at(y - 2, x) + at(y + 2, x))) / 8.0;\n"
            + "    float vt = (5.0 * C + 4.0 * (at(y - 1, x) + at(y + 1, x))\n"
            + "        - (at(y - 1, x - 1) + at(y - 1, x + 1) + at(y + 1, x - 1) + at(y + 1, x + 1))\n"
            + "        - (at(y - 2, x) + at(y + 2, x)) + 0.5 * (at(y, x - 2) + at(y, x + 2))) / 8.0;\n"
            + "    rgb.g = C;\n"
            + "    if (chh == 0) { rgb.r = hz; rgb.b = vt; } else { rgb.b = hz; rgb.r = vt; }\n"
            + "  } else {\n"
            + "    float g = (4.0 * C + 2.0 * (at(y - 1, x) + at(y + 1, x) + at(y, x - 1) + at(y, x + 1))\n"
            + "        - (at(y - 2, x) + at(y + 2, x) + at(y, x - 2) + at(y, x + 2))) / 8.0;\n"
            + "    float o = (6.0 * C + 2.0 * (at(y - 1, x - 1) + at(y - 1, x + 1) + at(y + 1, x - 1) + at(y + 1, x + 1))\n"
            + "        - 1.5 * (at(y - 2, x) + at(y + 2, x) + at(y, x - 2) + at(y, x + 2))) / 8.0;\n"
            + "    rgb.g = g;\n"
            + "    if (c == 0) { rgb.r = C; rgb.b = o; } else { rgb.b = C; rgb.r = o; }\n"
            + "  }\n"
            + "  return rgb;\n"
            + "}\n"
            + "float bil(int off, int w, int h, float fx, float fy) {\n"
            + "  fx = max(0.0, min(float(w) - 1.001, fx)); fy = max(0.0, min(float(h) - 1.001, fy));\n"
            + "  int x0 = int(fx), y0 = int(fy); float ax = fx - float(x0), ay = fy - float(y0);\n"
            + "  float a00 = aux[off + y0 * w + x0], a01 = aux[off + y0 * w + x0 + 1];\n"
            + "  float a10 = aux[off + (y0 + 1) * w + x0], a11 = aux[off + (y0 + 1) * w + x0 + 1];\n"
            + "  return (a00 * (1.0 - ax) + a01 * ax) * (1.0 - ay) + (a10 * (1.0 - ax) + a11 * ax) * ay;\n"
            + "}\n"
            + "float fastExp2(float x) {\n"
            + "  if (x < -126.0) return 0.0;\n"
            + "  if (x > 127.0) x = 127.0;\n"
            + "  float fl = floor(x); int i = int(fl); float f = x - fl;\n"
            + "  float p = 1.0 + f * (0.6931472 + f * (0.2402265 + f * (0.0555041 + f * 0.0096181)));\n"
            + "  return intBitsToFloat((i + 127) << 23) * p;\n"
            + "}\n"
            + "float shadeAt(int y, int x, int chn) {\n"
            + "  float fy = float(y) / float(H - 1) * float(nR - 1); int j = min(nR - 2, int(fy)); float a = fy - float(j);\n"
            + "  int cols = rowLen / 4; float fx = float(x) / float(W - 1) * float(cols - 1);\n"
            + "  int i = min(cols - 2, int(fx)); float b = fx - float(i);\n"
            + "  float s00 = shd[j * rowLen + i * 4 + chn], s10 = shd[(j + 1) * rowLen + i * 4 + chn];\n"
            + "  float s01 = shd[j * rowLen + (i + 1) * 4 + chn], s11 = shd[(j + 1) * rowLen + (i + 1) * 4 + chn];\n"
            + "  float r0 = s00 * (1.0 - a) + s10 * a, r1 = s01 * (1.0 - a) + s11 * a;\n"
            + "  return r0 * (1.0 - b) + r1 * b;\n"
            + "}\n"
            + "float srgb(float l) { int k = int(min(1.0, max(0.0, l)) * 4096.0); return srgbT[k]; }\n"
            + "uint curve(float v) {\n"
            + "  float s = v + CONTRAST * (v * v * (3.0 - 2.0 * v) - v);\n"
            + "  return uint(clamp(int(floor(s * 255.0 + 0.5)), 0, 255));\n"
            + "}\n"
            + "void main() {\n"
            + "  int ox = int(gl_GlobalInvocationID.x), oy = int(gl_GlobalInvocationID.y);\n"
            + "  if (ox >= cw || oy >= ch) return;\n"
            + "  int x = cx + ox, y = cy + oy, n = W2 * H2;\n"
            + "  vec3 c = dem(y, x);\n"
            + "  float r = max(0.0, ccm[0] * c.r + ccm[1] * c.g + ccm[2] * c.b);\n"
            + "  float g = max(0.0, ccm[3] * c.r + ccm[4] * c.g + ccm[5] * c.b);\n"
            + "  float b = max(0.0, ccm[6] * c.r + ccm[7] * c.g + ccm[8] * c.b);\n"
            + "  float cl = aux[" + P_WE + " * n + min(H2 - 1, y / 2) * W2 + min(W2 - 1, x / 2)];\n"
            + "  if (cl > DEFR) {\n"
            + "    float mag = min(r, b) - g;\n"
            + "    if (mag > 0.0) { float s2 = min(1.0, (cl - DEFR) / 0.25); r -= mag * s2; b -= mag * s2; }\n"
            + "  }\n"
            + "  if (cl > CDESAT) {\n"
            + "    float s = min(1.0, (cl - CDESAT) / (0.95 - CDESAT)), mxc = max(r, max(g, b));\n"
            + "    s = s * s * (3.0 - 2.0 * s);\n"
            + "    r += (mxc - r) * s; g += (mxc - g) * s; b += (mxc - b) * s;\n"
            + "  }\n"
            + "  float gn = bil(" + P_USE + " * n, W2, H2, (float(x) + 0.5) / 2.0 - 0.5, (float(y) + 0.5) / 2.0 - 0.5);\n"
            + "  float trv = TONE_MAX > 0.0 ? bil(" + P_T1 + " * n, W2, H2, (float(x) + 0.5) / 2.0 - 0.5, (float(y) + 0.5) / 2.0 - 0.5) : 1.0;\n"
            + "  float l = (0.2126 * r + 0.7152 * g + 0.0722 * b) * (1.0 + TONE_MAX * (trv - 1.0)) * gn;\n"
            + "  gn *= fastExp2(lk * (1.0 - sqrt(min(1.0, max(0.0, l)))));\n"
            + "  if (hasLift == 1) gn *= bil(" + P_GM + " * n, lw4, lh4, ((float(x) + 0.5) / 2.0 - 0.5) / 4.0 - 0.375,\n"
            + "      ((float(y) + 0.5) / 2.0 - 0.5) / 4.0 - 0.375);\n"
            + "  r *= gn; g *= gn; b *= gn;\n"
            + "  l = 0.2126 * r + 0.7152 * g + 0.0722 * b;\n"
            + "  r = max(0.0, l + (r - l) * SAT); g = max(0.0, l + (g - l) * SAT); b = max(0.0, l + (b - l) * SAT);\n"
            + "  float mx = max(r, max(g, b));\n"
            + "  if (mx > SHOULDER && oldShoulder == 1) {\n"
            + "    float k = (0.8 + 0.2 * tanh((mx - 0.8) / 0.2)) / mx;\n"
            + "    r *= k; g *= k; b *= k;\n"
            + "  } else if (mx > SHOULDER) {\n"
            + "    float wp = gn * minGain * (hasShade == 1 ? shadeAt(y, x, shCh) : 1.0);\n"
            + "    float t = (mx - SHOULDER) / (1.0 - SHOULDER), tw = (wp - SHOULDER) / (1.0 - SHOULDER);\n"
            + "    float yv = tw > 1.0 ? t * (1.0 + t / (tw * tw)) / (1.0 + t) : t;\n"
            + "    float k = (SHOULDER + (1.0 - SHOULDER) * yv) / mx;\n"
            + "    r *= k; g *= k; b *= k;\n"
            + "  }\n"
            + "  float mx2 = max(r, max(g, b));\n"
            + "  if (mx2 > 1.0 && oldShoulder == 0) {\n"
            + "    float lu = 0.2126 * r + 0.7152 * g + 0.0722 * b;\n"
            + "    if (lu >= 1.0) { r = 1.0; g = 1.0; b = 1.0; }\n"
            + "    else { float sd = (1.0 - lu) / (mx2 - lu); r = lu + (r - lu) * sd; g = lu + (g - lu) * sd; b = lu + (b - lu) * sd; }\n"
            + "  }\n"
            + "  px[oy * cw + ox] = 0xFF000000u | curve(srgb(r)) << 16 | curve(srgb(g)) << 8 | curve(srgb(b));\n"
            + "}\n";

    /** chromaDenoise: cb, cr per 2x2 block of the image (@0, @q). */
    private static final String C1 = HEAD + L2F + PXB + DEVB + DEVU
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y), w2 = cw / 2, h2 = ch / 2;\n"
            + "  if (x >= w2 || y >= h2) return;\n"
            + "  float sb = 0.0, sr = 0.0;\n"
            + "  for (int d = 0; d < 4; d++) {\n"
            + "    uint c = px[(2 * y + d / 2) * cw + 2 * x + d % 2];\n"
            + "    float r = float((c >> 16) & 255u), g = float((c >> 8) & 255u), b = float(c & 255u);\n"
            + "    float yy = 0.299 * r + 0.587 * g + 0.114 * b;\n"
            + "    sb += b - yy; sr += r - yy;\n"
            + "  }\n"
            + "  int i = y * w2 + x;\n"
            + "  dev[i] = sb / 4.0; dev[w2 * h2 + i] = sr / 4.0;\n"
            + "}\n";

    /** 3x3 median of a dev plane (MergeCore.median3). */
    private static final String MED3 = HEAD + L2F + DEVB
            + "uniform int src, dst, pw, ph;\n"
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y);\n"
            + "  if (x >= pw || y >= ph) return;\n"
            + "  float v[9]; int n = 0;\n"
            + "  for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {\n"
            + "    int yy = clamp(y + dy, 0, ph - 1), xx = clamp(x + dx, 0, pw - 1);\n"
            + "    float t = dev[src + yy * pw + xx]; v[n] = t; n++;\n"
            + "  }\n"
            + "  for (int a = 1; a < 9; a++) {\n"
            + "    float k = v[a]; int b = a - 1;\n"
            + "    while (b >= 0 && v[b] > k) { v[b + 1] = v[b]; b--; }\n"
            + "    v[b + 1] = k;\n"
            + "  }\n"
            + "  dev[dst + y * pw + x] = v[4];\n"
            + "}\n";

    /** Box mean along x (dir 0) or y (1) of a dev plane, borders repeated (boxH / boxV / box5). */
    private static final String BOXG = HEAD + L2F + DEVB
            + "uniform int src, dst, pw, ph, dir, r;\n"
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y);\n"
            + "  if (x >= pw || y >= ph) return;\n"
            + "  float s = 0.0;\n"
            + "  for (int t = -r; t <= r; t++) {\n"
            + "    int j = dir == 0 ? y * pw + clamp(x + t, 0, pw - 1) : clamp(y + t, 0, ph - 1) * pw + x;\n"
            + "    float v = dev[src + j]; s += v;\n"
            + "  }\n"
            + "  dev[dst + y * pw + x] = s / float(2 * r + 1);\n"
            + "}\n";

    /** chromaDenoise: the smoothed colour (@4q, @5q) with each pixel's own luminance. */
    private static final String C4 = HEAD + L2F + PXB + DEVB + DEVU + PIX
            + "uniform int q;\n"
            + "float bilD(int off, int w, int h, float fx, float fy) {\n"
            + "  fx = max(0.0, min(float(w) - 1.001, fx)); fy = max(0.0, min(float(h) - 1.001, fy));\n"
            + "  int x0 = int(fx), y0 = int(fy); float ax = fx - float(x0), ay = fy - float(y0);\n"
            + "  float a00 = dev[off + y0 * w + x0], a01 = dev[off + y0 * w + x0 + 1];\n"
            + "  float a10 = dev[off + (y0 + 1) * w + x0], a11 = dev[off + (y0 + 1) * w + x0 + 1];\n"
            + "  return (a00 * (1.0 - ax) + a01 * ax) * (1.0 - ay) + (a10 * (1.0 - ax) + a11 * ax) * ay;\n"
            + "}\n"
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y), w2 = cw / 2, h2 = ch / 2;\n"
            + "  if (x >= cw || y >= ch) return;\n"
            + "  uint c = px[y * cw + x];\n"
            + "  float yy = luma(c);\n"
            + "  float fx = min(float(w2) - 1.001, max(0.0, (float(x) + 0.5) / 2.0 - 0.5));\n"
            + "  float fy = min(float(h2) - 1.001, max(0.0, (float(y) + 0.5) / 2.0 - 0.5));\n"
            + "  float ncb = bilD(4 * q, w2, h2, fx, fy), ncr = bilD(5 * q, w2, h2, fx, fy);\n"
            + "  float nr = yy + ncr, nb = yy + ncb;\n"
            + "  float ng = (yy - 0.299 * nr - 0.114 * nb) / 0.587;\n"
            + "  px[y * cw + x] = 0xFF000000u | uint(clamp8(nr)) << 16 | uint(clamp8(ng)) << 8 | uint(clamp8(nb));\n"
            + "}\n";

    /** localContrast: mean and mean of squares of the luminance per 4x4 block (@0, @L). */
    private static final String LC1 = HEAD + L2F + PXB + DEVB + DEVU + PIX
            + "uniform int lw, lh;\n"
            + "void main() {\n"
            + "  int lx = int(gl_GlobalInvocationID.x), ly = int(gl_GlobalInvocationID.y);\n"
            + "  if (lx >= lw || ly >= lh) return;\n"
            + "  float s = 0.0, s2 = 0.0; int n = 0;\n"
            + "  for (int yy = ly * 4; yy < min(ch, ly * 4 + 4); yy++)\n"
            + "    for (int xx = lx * 4; xx < min(cw, lx * 4 + 4); xx++) {\n"
            + "      uint c = px[yy * cw + xx];\n"
            + "      float v = luma(c); s += v; s2 += v * v; n++;\n"
            + "    }\n"
            + "  int i = ly * lw + lx;\n"
            + "  dev[i] = s / float(n); dev[lw * lh + i] = s2 / float(n);\n"
            + "}\n";

    /** localContrast: a, b of the guided filter (@0, @L) from mean @3L, mean2 @4L. */
    private static final String L2S = HEAD + L2F + DEVB
            + "uniform int L, pw, ph;\nuniform float e2;\n"
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y);\n"
            + "  if (x >= pw || y >= ph) return;\n"
            + "  int i = y * pw + x;\n"
            + "  float mn = dev[3 * L + i], m2 = dev[4 * L + i];\n"
            + "  float v = max(0.0, m2 - mn * mn);\n"
            + "  float a = v / (v + e2);\n"
            + "  dev[i] = a; dev[L + i] = mn - a * mn;\n"
            + "}\n";

    /** localContrast: applied at full resolution (ma @3L, mb @4L). */
    private static final String LC3 = HEAD + L2F + PXB + DEVB + DEVU + PIX
            + "uniform int lw, lh, L;\nuniform float amount, clMax, SDARK;\n"
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y0 = int(gl_GlobalInvocationID.y);\n"
            + "  if (x >= cw || y0 >= ch) return;\n"
            + "  float fy = max(0.0, min(float(lh) - 1.001, (float(y0) + 0.5) / 4.0 - 0.5));\n"
            + "  int iy = int(fy); float ay = fy - float(iy);\n"
            + "  float fx = max(0.0, min(float(lw) - 1.001, (float(x) + 0.5) / 4.0 - 0.5));\n"
            + "  int ix = int(fx); float ax = fx - float(ix);\n"
            + "  int k = iy * lw + ix;\n"
            + "  float a0 = dev[3 * L + k], a1 = dev[3 * L + k + 1], a2 = dev[3 * L + k + lw], a3 = dev[3 * L + k + lw + 1];\n"
            + "  float b0 = dev[4 * L + k], b1 = dev[4 * L + k + 1], b2 = dev[4 * L + k + lw], b3 = dev[4 * L + k + lw + 1];\n"
            + "  float A = (a0 * (1.0 - ax) + a1 * ax) * (1.0 - ay) + (a2 * (1.0 - ax) + a3 * ax) * ay;\n"
            + "  float B = (b0 * (1.0 - ax) + b1 * ax) * (1.0 - ay) + (b2 * (1.0 - ax) + b3 * ax) * ay;\n"
            + "  int i = y0 * cw + x;\n"
            + "  uint c = px[i];\n"
            + "  float v = luma(c);\n"
            + "  float base = A * v + B;\n"
            + "  float s = amount * (v - base) * min(1.0, base / SDARK);\n"
            + "  s = max(-clMax, min(clMax, s));\n"
            + "  if (abs(s) < 0.5) return;\n"
            + "  px[i] = addAll(c, s);\n"
            + "}\n";

    /** sharpen: the luminance (@0). */
    private static final String S1 = HEAD + L2F + PXB + DEVB + DEVU + PIX
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y);\n"
            + "  if (x >= cw || y >= ch) return;\n"
            + "  uint c = px[y * cw + x];\n"
            + "  dev[y * cw + x] = luma(c);\n"
            + "}\n";

    /** sharpen: unsharp mask with the soft threshold and the neighbour limit (blur @2P). */
    private static final String S2 = HEAD + L2F + PXB + DEVB + DEVU + PIX
            + "uniform float amount, TH, SMAX, SDARK, MARGIN;\nuniform int oldSharp;\n"
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), r = int(gl_GlobalInvocationID.y);\n"
            + "  if (x >= cw || r >= ch) return;\n"
            + "  int P = cw * ch, i = r * cw + x;\n"
            + "  float yi = dev[i], bi = dev[2 * P + i];\n"
            + "  float d = yi - bi, ad = abs(d);\n"
            + "  if (ad <= TH) return;\n"
            + "  float s = sign(d) * min(SMAX, (ad - TH) * amount * min(1.0, bi / SDARK));\n"
            + "  if (oldSharp == 0 && r > 0 && r < ch - 1 && x > 0 && x < cw - 1) {\n"
            + "    float mn = 3.0e38, mx = -3.0e38;\n"
            + "    for (int dy = -1; dy <= 1; dy++)\n"
            + "      for (int dx = -1; dx <= 1; dx++) {\n"
            + "        if (dy == 0 && dx == 0) continue;\n"
            + "        float v = dev[i + dy * cw + dx];\n"
            + "        mn = min(mn, v); mx = max(mx, v);\n"
            + "      }\n"
            + "    s = max(s, min(0.0, mn - MARGIN - yi));\n"
            + "    s = min(s, max(0.0, mx + MARGIN - yi));\n"
            + "    if (s == 0.0) return;\n"
            + "  }\n"
            + "  uint c = px[i];\n"
            + "  px[i] = addAll(c, s);\n"
            + "}\n";

    // ------------------------------------------------------------------ shaders: pre-steps

    private static final String ALNB = "layout(std430, binding = 16) buffer B16 { float aln[]; };\n";
    private static final String L1D = "layout(local_size_x = 256) in;\n";
    private static final String RAWU = ""
            + "float rawAt(int i) { uint v = raw[i >> 1]; return float((i & 1) == 0 ? (v & 0xFFFFu) : (v >> 16)); }\n";

    /** fixWarm (long frames): per 2 pixels (one uint), from the copy in TMP. */
    private static final String WARM = HEAD + L1D
            + "layout(std430, binding = 0) buffer B0 { uint raw[]; };\n"
            + "layout(std430, binding = 10) readonly buffer B10 { uint srcw[]; };\n"
            + CNTB
            + "uniform int H;\nuniform vec4 blk;\nuniform float wabs, wrel, wnb;\n"
            + "float sget(int i) { uint v = srcw[i >> 1]; return float((i & 1) == 0 ? (v & 0xFFFFu) : (v >> 16)); }\n"
            + "uint fixPix(int y, int x, uint orig) {\n"
            + "  if (y < 2 || y >= H - 2 || x < 2 || x >= W - 2) return orig;\n"
            + "  int i = y * W + x;\n"
            + "  float bk = blk[(y & 1) * 2 + (x & 1)];\n"
            + "  float v = sget(i) - bk;\n"
            + "  if (v < wabs) return orig;\n"
            + "  float nb[8]; int n = 0; float mx = -1e9;\n"
            + "  for (int dy = -2; dy <= 2; dy += 2)\n"
            + "    for (int dx = -2; dx <= 2; dx += 2) {\n"
            + "      if (dx == 0 && dy == 0) continue;\n"
            + "      float u = sget(i + dy * W + dx) - bk;\n"
            + "      nb[n] = u; n++; if (u > mx) mx = u;\n"
            + "    }\n"
            + "  for (int a = 1; a < 8; a++) {\n"
            + "    float k = nb[a]; int b = a - 1;\n"
            + "    while (b >= 0 && nb[b] > k) { nb[b + 1] = nb[b]; b--; }\n"
            + "    nb[b + 1] = k;\n"
            + "  }\n"
            + "  float med = (nb[3] + nb[4]) / 2.0;\n"
            + "  float excess = v - max(mx, med);\n"
            + "  if (excess <= wabs + wrel * max(0.0, med)) return orig;\n"
            + "  float raise = 0.0;\n"
            + "  int js[4] = int[4](i - 1, i + 1, i - W, i + W);\n"
            + "  for (int t = 0; t < 4; t++) {\n"
            + "    int j = js[t], yy = j / W, xx = j % W;\n"
            + "    float bj = blk[(yy & 1) * 2 + (xx & 1)];\n"
            + "    float vj = sget(j) - bj;\n"
            + "    if (xx >= 2 && xx < W - 2 && yy >= 2 && yy < H - 2) {\n"
            + "      float s = sget(j - 2) + sget(j + 2) + sget(j - 2 * W) + sget(j + 2 * W);\n"
            + "      raise = max(raise, vj - (s / 4.0 - bj));\n"
            + "    }\n"
            + "  }\n"
            + "  if (raise > wnb * excess) return orig;\n"
            + "  atomicAdd(cnt[2], 1u);\n"
            + "  return uint(floor(med + bk + 0.5)) & 0xFFFFu;\n"
            + "}\n"
            + "void main() {\n"
            + "  int j = int(gl_GlobalInvocationID.x);\n"
            + "  if (j >= W * H / 2) return;\n"
            + "  int i0 = 2 * j, y = i0 / W, x0 = i0 % W;\n"
            + "  uint v = raw[j];\n"
            + "  uint lo = fixPix(y, x0, v & 0xFFFFu), hi = fixPix(y, x0 + 1, v >> 16);\n"
            + "  raw[j] = lo | (hi << 16);\n"
            + "}\n";

    /**
     * fixBlack: median of each 16x16 block per CFA channel - one work group per block, the 256 values
     * sorted in shared memory (bitonic, 36 compare-exchange steps written out: OpenGL ES allows no
     * barrier() inside loops); exact: (128th + 129th) / 2 like the CPU. (The value search took ~140 ms
     * per frame, a shared histogram ~75 ms on the 7a / ~200 ms on the Pixel 4: all dark pixels hit the
     * same counters - item 25, 2026-11-19.)
     */
    private static final String BMED = HEAD + L2F
            + "layout(std430, binding = 0) readonly buffer B0 { uint raw[]; };\n"
            + ALNB + RAWU
            + "uniform int bw, bh;\n"
            + "shared uint s[256];\n"
            + "void main() {\n"
            + "  int bx = int(gl_WorkGroupID.x), g = int(gl_WorkGroupID.y);\n"
            + "  int d = g / bh, by = g % bh, dy = d / 2, dx = d % 2;\n"
            + "  int lx = int(gl_LocalInvocationID.x), ly = int(gl_LocalInvocationID.y);\n"
            + "  uint ix = uint(ly * 16 + lx);\n"
            + "  float v0 = rawAt((2 * (by * 16 + ly) + dy) * W + 2 * (bx * 16 + lx) + dx);\n"
            + "  s[ix] = uint(v0);\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 1u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 2u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 2u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 4u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 1u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 4u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 4u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 8u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 2u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 8u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 1u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 8u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 8u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 16u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 4u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 16u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 2u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 16u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 1u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 16u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 16u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 32u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 8u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 32u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 4u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 32u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 2u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 32u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 1u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 32u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 32u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 64u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 16u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 64u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 8u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 64u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 4u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 64u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 2u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 64u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 1u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 64u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 64u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 128u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 32u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 128u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 16u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 128u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 8u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 128u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 4u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 128u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 2u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 128u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 1u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 128u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 128u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 256u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 64u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 256u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 32u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 256u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 16u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 256u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 8u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 256u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 4u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 256u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 2u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 256u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  { uint p = ix ^ 1u; if (p > ix) { uint a = s[ix], c = s[p]; if ((a > c) == ((ix & 256u) == 0u)) { s[ix] = c; s[p] = a; } } }\n"
            + "  memoryBarrierShared();\n"
            + "  barrier();\n"
            + "  if (ix == 0u) aln[d * bw * bh + by * bw + bx] = (float(s[127]) + float(s[128])) / 2.0;\n"
            + "}\n";

    /** fixBlack pre-test: per 16x16 block the number of values below lv[d] (one thread per block, one pass). */
    private static final String BBELOW = HEAD + L2F
            + "layout(std430, binding = 0) readonly buffer B0 { uint raw[]; };\n"
            + ALNB + RAWU
            + "uniform int bw, bh;\nuniform vec4 lv;\n"
            + "void main() {\n"
            + "  int bx = int(gl_GlobalInvocationID.x), g = int(gl_GlobalInvocationID.y);\n"
            + "  if (bx >= bw || g >= 4 * bh) return;\n"
            + "  int d = g / bh, by = g % bh, dy = d / 2, dx = d % 2;\n"
            + "  float L = lv[d], c = 0.0;\n"
            + "  for (int yy = 0; yy < 16; yy++) {\n"
            + "    int row = (2 * (by * 16 + yy) + dy) * W + 2 * bx * 16 + dx;\n"
            + "    for (int xx = 0; xx < 16; xx++) { float v = rawAt(row + 2 * xx); if (v < L) c += 1.0; }\n"
            + "  }\n"
            + "  aln[d * bw * bh + by * bw + bx] = c;\n"
            + "}\n";

    /** fixBlack: + add per CFA channel, clipped at white (per uint = 2 pixels). */
    private static final String ADDB = HEAD + L1D
            + "layout(std430, binding = 0) buffer B0 { uint raw[]; };\n"
            + "uniform ivec4 add;\nuniform int white;\n"
            + "void main() {\n"
            + "  int j = int(gl_GlobalInvocationID.x), H = 2 * H2;\n"
            + "  if (j >= W * H / 2) return;\n"
            + "  int i0 = 2 * j, y = i0 / W, x0 = i0 % W;\n"
            + "  uint v = raw[j], lo = v & 0xFFFFu, hi = v >> 16;\n"
            + "  int a0 = add[(y & 1) * 2 + (x0 & 1)], a1 = add[(y & 1) * 2 + ((x0 + 1) & 1)];\n"
            + "  if (a0 != 0) lo = uint(min(white, int(lo) + a0));\n"
            + "  if (a1 != 0) hi = uint(min(white, int(hi) + a1));\n"
            + "  raw[j] = lo | (hi << 16);\n"
            + "}\n";

    /** measuredRatio samples: g / prev where both are valid, else -1. */
    private static final String RATIO = HEAD + L1D + AUXB + ALNB
            + "uniform int gP, pP, stp, count;\nuniform float clip;\n"
            + "void main() {\n"
            + "  int j = int(gl_GlobalInvocationID.x);\n"
            + "  if (j >= count) return;\n"
            + "  int n = W2 * H2, i = j * stp;\n"
            + "  float p = aux[pP * n + i], v = aux[gP * n + i];\n"
            + "  aln[j] = (p > 30.0 && p < clip && v > 0.0 && v < clip) ? v / p : -1.0;\n"
            + "}\n";

    /** dst = src / dv (dv 1: copy). */
    private static final String COPYP = HEAD + L2 + AUXB
            + "uniform int src, dst;\nuniform float dv;\n"
            + MAIN2
            + "  float v = aux[src * n + i];\n"
            + "  aux[dst * n + i] = dv == 1.0 ? v : v / dv;\n"
            + "}\n";

    /** align: 1/8 size mean of a plane (clamped), compact at P_T1 / P_T2. */
    private static final String DOWN8 = HEAD + L2F + AUXB
            + "uniform int src, dst;\nuniform float clampv;\n"
            + "void main() {\n"
            + "  int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y), ws = W2 / 8, hs = H2 / 8;\n"
            + "  if (x >= ws || y >= hs) return;\n"
            + "  int n = W2 * H2; float s = 0.0;\n"
            + "  for (int dy = 0; dy < 8; dy++)\n"
            + "    for (int dx = 0; dx < 8; dx++) { float v = aux[src * n + (y * 8 + dy) * W2 + x * 8 + dx]; s += min(v, clampv); }\n"
            + "  aux[dst * n + y * ws + x] = s / 64.0;\n"
            + "}\n";

    /** align: coarse error per shift (13 x 13) and row. */
    private static final String ACOARSE = HEAD + L2F + AUXB + ALNB
            + "uniform int rows;\n"
            + "void main() {\n"
            + "  int c = int(gl_GlobalInvocationID.x), r = int(gl_GlobalInvocationID.y), ws = W2 / 8;\n"
            + "  if (c >= 169 || r >= rows) return;\n"
            + "  int n = W2 * H2, sy = c / 13 - 6, sx = c % 13 - 6, y = r + 8;\n"
            + "  float e = 0.0;\n"
            + "  for (int x = 8; x < ws - 8; x++) {\n"
            + "    float a = aux[" + P_T1 + " * n + y * ws + x], b = aux[" + P_T2 + " * n + (y + sy) * ws + x + sx];\n"
            + "    e += abs(a - b);\n"
            + "  }\n"
            + "  aln[c * rows + r] = e;\n"
            + "}\n";

    /** align: refine around the coarse shift, per shift and sampled row {sum, count}. */
    private static final String AREFINE = HEAD + L2F + AUXB + ALNB
            + "uniform int refP, gP, cy, cx, R, st, rows;\nuniform float clampv;\n"
            + "void main() {\n"
            + "  int c = int(gl_GlobalInvocationID.x), ri = int(gl_GlobalInvocationID.y), nc = (2 * R + 1) * (2 * R + 1);\n"
            + "  if (c >= nc || ri >= rows) return;\n"
            + "  int n = W2 * H2, sy = cy + c / (2 * R + 1) - R, sx = cx + c % (2 * R + 1) - R;\n"
            + "  int y = 64 + ri * st, yy = y + sy;\n"
            + "  float e = 0.0, cnt = 0.0;\n"
            + "  if (yy >= 0 && yy < H2) {\n"
            + "    for (int x = 64; x < W2 - 64; x += st) {\n"
            + "      int xx = x + sx;\n"
            + "      if (xx < 0 || xx >= W2) continue;\n"
            + "      float a = aux[refP * n + y * W2 + x], b = aux[gP * n + yy * W2 + xx];\n"
            + "      e += abs(min(a, clampv) - min(b, clampv)); cnt += 1.0;\n"
            + "    }\n"
            + "  }\n"
            + "  aln[(c * rows + ri) * 2] = e; aln[(c * rows + ri) * 2 + 1] = cnt;\n"
            + "}\n";

    /** align: per tile and shift the error (every 2nd pixel), the last column = the tile's reference sum. */
    private static final String ATILES = HEAD + L2F + AUXB + ALNB
            + "uniform int refP, gP, gy, gx, S, TILE, TXN, T;\nuniform float clampv;\n"
            + "void main() {\n"
            + "  int s = int(gl_GlobalInvocationID.x), t = int(gl_GlobalInvocationID.y), ns = (2 * S + 1) * (2 * S + 1);\n"
            + "  if (s > ns || t >= T) return;\n"
            + "  int n = W2 * H2, i = t / TXN, j = t % TXN;\n"
            + "  if (s == ns) {\n"
            + "    float sum = 0.0;\n"
            + "    for (int y = 0; y < TILE; y += 2) {\n"
            + "      int ry = i * TILE + y;\n"
            + "      for (int x = 0; x < TILE; x += 2) { float a = aux[refP * n + ry * W2 + j * TILE + x]; sum += min(a, clampv); }\n"
            + "    }\n"
            + "    aln[T * ns + t] = sum;\n"
            + "    return;\n"
            + "  }\n"
            + "  int sy = gy - S + s / (2 * S + 1), sx = gx - S + s % (2 * S + 1);\n"
            + "  float e = 0.0;\n"
            + "  for (int y = 0; y < TILE; y += 2) {\n"
            + "    int ry = i * TILE + y, yy = clamp(ry + sy, 0, H2 - 1);\n"
            + "    for (int x = 0; x < TILE; x += 2) {\n"
            + "      int rx = j * TILE + x, xx = clamp(rx + sx, 0, W2 - 1);\n"
            + "      float a = aux[refP * n + ry * W2 + rx], b = aux[gP * n + yy * W2 + xx];\n"
            + "      e += abs(min(a, clampv) - min(b, clampv));\n"
            + "    }\n"
            + "  }\n"
            + "  aln[t * ns + s] = e;\n"
            + "}\n";
}
