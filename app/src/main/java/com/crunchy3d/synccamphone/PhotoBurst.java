package com.crunchy3d.synccamphone;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HDR burst with zero shutter lag (photo thread only, except the result map).
 *
 * The RAW stream runs all the time in burst mode; the last RING frames are kept open (ring). A
 * burst for master time T takes the frames whose master time lies in [T - BEFORE*P, T + AFTER*P]:
 * BEFORE of them come from the ring (taken before the shutter was touched), the rest arrive live.
 * All phones use the same master-time window, so frame k matches frame k on every phone.
 * HDR modes (the master's AE plan, the same on every phone): MODE_SINGLE = one frame (the one at
 * T), MODE_TWO = that frame + one longer frame, MODE_AUTO = that frame + the planned longer
 * frames (0..4: exposure first, then ISO). The frames before / after T are not merged (motion). The longer frames are requested at the
 * press, REPEAT times each; for plan entry j each phone keeps the matching frame (recognised by
 * exposure and ISO in its capture result) whose exposure ends nearest to slot T + (EXTRA_SLOT +
 * REPEAT * j) periods (the frame grid is the end of the exposure), so all phones use the same moments. The reference (moving parts, sync) is always the frame at T.
 * Saved: burst_NNNN_<phone>_k.dng (k = 0..7) + burst_NNNN_<phone>.txt (timing, WB gains, colour
 * matrix, black / white level) for tools/merge_burst.py.
 */
final class PhotoBurst {
    static final int FRAMES = 8, BEFORE = 3;
    static final int RING = 8;                // frames kept while waiting (~0.27 s at 29.8 fps)
    // plan entry j is requested REPEAT times in a row; each phone keeps the frame whose exposure ENDS
    // in slot T + (EXTRA_SLOT + REPEAT * j) periods. Requests landed 5..7 frames after the press
    // (measured, varies per burst): with 4 repeats slot 8 is covered for delays of 5..8 frames.
    static final int EXTRA_SLOT = 9, REPEAT = 4;
    static final double SUBMIT_AT = 3.5;      // the longer frames are requested at T + 3.5 periods (master time)
    static final int MODE_SINGLE = 0, MODE_TWO = 1, MODE_AUTO = 2, MODE_ASTRO = 3;
    // MODE_ASTRO: tripod series of long frames (+ short highlight frames), AstroCapture
    static final String[] MODES = {"1 frame", "2-frame HDR", "Auto HDR", "Astro"};

    /** Capture results of recent frames by sensor timestamp (camera thread writes). */
    static final class Results {
        private final Map<Long, TotalCaptureResult> map = new LinkedHashMap<Long, TotalCaptureResult>() {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, TotalCaptureResult> e) {
                return size() > 150;          // ~5 s

            }
        };

        synchronized void put(long ts, TotalCaptureResult r) {
            map.put(ts, r);
        }

        synchronized TotalCaptureResult get(long ts) {
            return map.get(ts);
        }
    }

    /** Ring of the newest RAW images (photo thread). */
    static final class Ring {
        final ArrayDeque<Image> q = new ArrayDeque<>();

        void add(Image img) {
            q.addLast(img);
            while (q.size() > RING) q.pollFirst().close();
        }

        void clear() {
            for (Image i : q) i.close();
            q.clear();
        }
    }

    final int seq;
    final long targetMaster, periodNs;
    final LedCapture.Clock clock;
    private final Context ctx;
    private final String name, info;
    private final CameraCharacteristics chars;
    private final Results results;
    private final int rotation, aspect;
    final int mode;
    final long[] planExp;                     // longer frames: exposure (ns) and ISO per plan entry
    final int[] planIso;
    final boolean natural;                    // longer frames near the frame time: no motion rejection
    /**
     * A burst frame: the camera's image, or (plan frames, as soon as they are kept) a copy in direct
     * memory with the camera buffer released - the RAW reader has only ~13 buffers, a night burst keeps
     * up to 18 frames (averaging frames, 2026-11-17).
     */
    static final class Frm {
        Image img;
        java.nio.ByteBuffer copy;
        final long ts;
        final int w, h;

        Frm(Image i) {
            img = i;
            ts = i.getTimestamp();
            w = i.getWidth();
            h = i.getHeight();
        }

        long getTimestamp() {
            return ts;
        }

        int getWidth() {
            return w;
        }

        int getHeight() {
            return h;
        }

        /**
         * Copies the RAW data (rows packed, 2 bytes per pixel) into a region of a memory-mapped cache
         * file and releases the camera buffer. (Not allocateDirect: on Android that counts against the
         * 512 MB Java heap - 15 frames ran out of memory, 2026-11-17; mapped file pages do not.)
         */
        void detach(FrameStore store) {
            if (img == null || copy != null) return;
            Image.Plane p = img.getPlanes()[0];
            java.nio.ByteBuffer src = p.getBuffer();
            int rs = p.getRowStride();
            copy = store.region((long) w * h * 2);
            if (copy == null) return;                    // no store: keep the camera image
            for (int y = 0; y < h; y++) {
                src.limit(y * rs + w * 2);
                src.position(y * rs);
                copy.put(src);
            }
            copy.rewind();
            img.close();
            img = null;
        }

        void close() {
            if (img != null) img.close();
            img = null;
            copy = null;
        }
    }

    /** Memory-mapped cache file for the copied frames of one burst (deleted at the end). */
    static final class FrameStore {
        private final java.io.File file;
        private java.io.RandomAccessFile raf;
        private long next;

        FrameStore(java.io.File f) {
            file = f;
        }

        synchronized java.nio.ByteBuffer region(long size) {
            try {
                if (raf == null) raf = new java.io.RandomAccessFile(file, "rw");
                java.nio.MappedByteBuffer mb = raf.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_WRITE, next, size);
                next += size;
                mb.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                return mb;
            } catch (Exception e) {
                return null;
            }
        }

        synchronized void delete() {
            try {
                if (raf != null) raf.close();
            } catch (Exception ignored) {
            }
            raf = null;
            file.delete();
        }
    }

    private final FrameStore store;
    private final Frm[] extra;                // best frame per plan entry
    private final long[] extraMaster;
    private final long[] tslot;               // target slot per plan entry (runs of identical frames: consecutive)
    private final List<Frm> pending = new ArrayList<>();   // result not known yet
    private final List<Frm> frames = new ArrayList<>();
    private final List<Long> masters = new ArrayList<>();
    final long startedMs = android.os.SystemClock.elapsedRealtime();
    volatile boolean finished;

    PhotoBurst(Context ctx, String name, String info, int seq, long targetMaster, long periodNs,
               LedCapture.Clock clock, CameraCharacteristics chars, Results results, int rotation, int aspect,
               int mode, long[] planExp, int[] planIso, boolean natural) {
        this.mode = mode;
        this.planExp = planExp;
        this.planIso = planIso;
        this.natural = natural;
        extra = new Frm[planExp.length];
        fin = new boolean[planExp.length];
        store = new FrameStore(new java.io.File(ctx.getCacheDir(), "burst_frames.raw"));
        extraMaster = new long[planExp.length];
        tslot = new long[planExp.length];
        long s = EXTRA_SLOT;
        for (int j = 0; j < planExp.length; j++) {
            boolean same = j > 0 && planExp[j] == planExp[j - 1] && planIso[j] == planIso[j - 1];
            if (j > 0) s += same ? 1 : REPEAT;
            tslot[j] = s;
        }
        this.ctx = ctx.getApplicationContext();
        GpuRobust.cacheDir = new java.io.File(this.ctx.getCacheDir(), "gpu");
        this.name = name;
        this.info = info;
        this.seq = seq;
        this.targetMaster = targetMaster;
        this.periodNs = periodNs;
        this.clock = clock;
        this.chars = chars;
        this.results = results;
        this.rotation = rotation;
        this.aspect = aspect;
    }

    /**
     * Only the frame at T (the user, 2026-11-15: the fast frames before / after the press doubled
     * text with slight motion - not used in any mode any more).
     */
    private int shortMax() {
        return 1;
    }

    private long lo() {
        return targetMaster - periodNs / 2;
    }

    private long hi() {
        return targetMaster + periodNs / 2;
    }

    private long end() {
        return planExp.length == 0 ? hi()
                : targetMaster + Math.round((tslot[planExp.length - 1] + REPEAT + 0.5) * periodNs);
    }

    long shortExpNs;                          // exposure of the base frames (slot of a frame = its exposure end)

    /** Frame slot (periods after T) of a frame of plan entry j: by the END of its exposure. */
    private long slotOf(long tm, int j) {
        return Math.round((tm + planExp[j] - shortExpNs - targetMaster) / (double) periodNs);
    }

    private long targetSlot(int j) {
        return tslot[j];
    }

    /** Requests for the plan entries: a run of identical frames (averaging) as one block of run + REPEAT - 1. */
    static int[][] requestRuns(long[] pe, int[] pi) {
        List<int[]> runs = new ArrayList<>();
        int j = 0;
        while (j < pe.length) {
            int k = j;
            while (k + 1 < pe.length && pe[k + 1] == pe[j] && pi[k + 1] == pi[j]) k++;
            runs.add(new int[]{j, k - j + 1 + REPEAT - 1});        // {first entry, number of requests}
            j = k + 1;
        }
        return runs.toArray(new int[0][]);
    }

    /** Plan entry this frame belongs to by its exposure + ISO: -1 none, -2 result not there yet. */
    private int planOf(long tsLocal) {
        if (planExp.length == 0) return -1;
        TotalCaptureResult r = results.get(tsLocal);
        if (r == null) return -2;
        Long e = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer s = r.get(CaptureResult.SENSOR_SENSITIVITY);
        if (e == null || s == null) return -1;
        for (int j = 0; j < planExp.length; j++) {
            if (Math.abs(e - planExp[j]) <= planExp[j] * 0.15 && Math.abs(s - planIso[j]) <= planIso[j] * 0.2) return j;
        }
        return -1;
    }

    /** Takes the matching frames from the ring (the rest of the ring is closed). */
    void takeFromRing(Ring ring) {
        while (!ring.q.isEmpty()) offer(ring.q.pollFirst());
    }

    private int shortsKept() {
        return frames.size();
    }

    // ---- MERGE DURING THE CAPTURE (item 21, 2026-11-18): with the GPU on, the merge starts when the
    // reference frame (at T) is there and takes each plan frame as soon as it is FINAL - no better-timed
    // candidate can come any more (a merged frame is never replaced). The burst ends as before; finish()
    // only waits for the merge. The frames stay open until the end (the single DNGs are written from
    // them as before); any problem: the normal merge after the capture.
    volatile boolean keepFrames;
    private Stream stream;
    private final boolean[] fin;              // plan entry handed to the merge

    private boolean streamWanted() {
        return planExp.length > 0 && MergeCore.gpuMode == 1 && !Boolean.getBoolean("noStream");
    }

    private static double lightOf(TotalCaptureResult r) {
        Long e = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer s = r.get(CaptureResult.SENSOR_SENSITIVITY);
        return (e == null ? 1 : e) * (double) (s == null ? 100 : s);
    }

    /** Plan entry j can not get a better-timed frame any more (frames arrive in time order). */
    private boolean entryFinal(int j) {
        if (!pending.isEmpty() || lastSeenMaster == Long.MIN_VALUE) return false;
        long s = slotOf(lastSeenMaster, j);
        if (extra[j] == null) return s >= tslot[j] + REPEAT + 1;      // none came: missing
        long bs = slotOf(extraMaster[j], j);
        // at / after the target: later frames are farther; before it: once a frame as far after it
        // has come
        return bs >= tslot[j] || s >= 2 * tslot[j] - bs;
    }

    private void streamCheck() {
        if (stream == null) return;
        for (int j = 0; j < planExp.length; j++) {
            if (fin[j] || !entryFinal(j)) continue;
            fin[j] = true;
            Frm f = extra[j];
            stream.deliver(j + 1, f, f == null ? null : results.get(f.getTimestamp()));
        }
    }

    private void streamFinishAll() {
        if (stream == null) return;
        for (int j = 0; j < planExp.length; j++) {
            if (fin[j]) continue;
            fin[j] = true;
            Frm f = extra[j];
            stream.deliver(j + 1, f, f == null ? null : results.get(f.getTimestamp()));
        }
    }

    private void streamStart(Frm ref) {
        if (stream != null || !streamWanted()) return;
        stream = new Stream();
        stream.deliver(0, ref, results.get(ref.getTimestamp()));
        stream.th = new Thread(stream::run, "burst-merge-" + seq);
        stream.th.start();
    }

    private final class Stream {
        final int n = 1 + planExp.length;
        final Frm[] fr = new Frm[n];
        final TotalCaptureResult[] rs = new TotalCaptureResult[n];
        final int[] state = new int[n];       // 0 waiting, 1 there, 2 missing
        final float[] expRatio = new float[n];
        final long t0 = android.os.SystemClock.elapsedRealtime();
        final java.util.Set<Long> used = new java.util.HashSet<>();   // timestamps of the merged frames
        Thread th;
        volatile BurstMerger mg;
        volatile float[] m;
        final float[][] ws = new float[1][];
        volatile Throwable err;
        volatile boolean done;
        volatile long doneMs;

        synchronized void deliver(int k, Frm f, TotalCaptureResult r) {
            fr[k] = f;
            rs[k] = r;
            state[k] = f == null ? 2 : 1;
            notifyAll();
        }

        synchronized boolean await(int k) {
            while (state[k] == 0) {
                long left = 20_000 - (android.os.SystemClock.elapsedRealtime() - t0);
                if (left <= 0) {
                    state[k] = 2;                 // (finish delivers everything: only a safety net)
                    break;
                }
                try {
                    wait(Math.min(left, 200));
                } catch (InterruptedException e) {
                    state[k] = 2;
                }
            }
            return state[k] == 1;
        }

        void run() {
            try {
                TotalCaptureResult rr = rs[0] != null ? rs[0] : resultFor(fr[0].getTimestamp());
                if (rr == null) throw new IllegalStateException("no capture result for the reference");
                rs[0] = rr;
                final double l0 = lightOf(rr);
                BurstMerger b = new BurstMerger(chars, rr, fr[0].getWidth(), fr[0].getHeight());
                b.core.brightness = brightness;
                b.initNoise(n, rr);
                final boolean[] nm = new boolean[n];
                b.core.noMotion = nm;
                expRatio[0] = 1f;
                for (int k = 1; k < n; k++) expRatio[k] = (float) (planExp[k - 1] * (double) planIso[k - 1] / l0);
                final MergeCore.Frames base = b.source(fr, false);
                MergeCore.Frames src = new MergeCore.Frames() {
                    @Override public boolean has(int k) {
                        if (k == 0) return true;
                        if (!await(k)) return false;
                        TotalCaptureResult r = rs[k] != null ? rs[k] : resultFor(fr[k].getTimestamp());
                        if (r == null) return false;
                        expRatio[k] = (float) (lightOf(r) / l0);
                        Long e = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        nm[k] = natural && expRatio[k] > 1.2f && e != null && e >= 0.9 * periodNs;
                        b.setNoise(k, r);
                        synchronized (used) {
                            used.add(fr[k].getTimestamp());
                        }
                        return true;
                    }

                    @Override public void read(int k, short[] buf) {
                        base.read(k, buf);
                    }

                    @Override public void release(int k) {
                    }

                    @Override public java.nio.ByteBuffer direct(int k) {
                        return base.direct(k);
                    }

                    @Override public int directStride(int k) {
                        return base.directStride(k);
                    }
                };
                synchronized (used) {
                    used.add(fr[0].getTimestamp());
                }
                mg = b;
                m = b.core.merge(src, n, 0, ws, expRatio);
            } catch (Throwable e) {
                err = e;
            } finally {
                doneMs = android.os.SystemClock.elapsedRealtime();
                done = true;
            }
        }
    }


    /** A plan frame: kept if it is nearer to its slot than the one kept so far (same settings: any free entry). */
    private final StringBuilder candidates = new StringBuilder();   // diagnostics: all plan frames seen

    private void offerExtra(Frm img, long tm, int j0) {
        long slot = slotOf(tm, j0);
        candidates.append(' ').append(j0).append('@').append(slot);
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int j = 0; j < planExp.length; j++) {
            if (planExp[j] != planExp[j0] || planIso[j] != planIso[j0]) continue;
            if (fin[j]) continue;                 // already merged: never replaced
            long d = Math.abs(slot - targetSlot(j));
            long cur = extra[j] == null ? Long.MAX_VALUE : Math.abs(slotOf(extraMaster[j], j) - targetSlot(j));
            if (d < cur && d < bestD) {
                best = j;
                bestD = d;
            }
        }
        if (best < 0) {
            img.close();
            return;
        }
        if (extra[best] != null) extra[best].close();
        img.detach(store);                    // copy now, camera buffer back to the stream
        extra[best] = img;
        extraMaster[best] = tm;
    }

    /** Classify frames whose capture result arrived late. */
    private void retryPending() {
        for (int i = pending.size() - 1; i >= 0; i--) {
            Frm img = pending.get(i);
            int j = planOf(img.getTimestamp());
            if (j == -2) continue;
            pending.remove(i);
            place(img, clock.toMaster(img.getTimestamp()), j);
        }
        streamCheck();
    }

    private void place(Frm img, long tm, int j) {
        // averaging (noise) frames have the reference's own settings (item 19): a frame in the
        // reference slot is the reference, not a plan frame (else no reference: "no frames", #55)
        boolean refLike = j >= 0 && shortExpNs > 0 && Math.abs(planExp[j] - shortExpNs) <= shortExpNs * 0.15;
        if (refLike && tm >= lo() && tm <= hi() && shortsKept() < shortMax()) {
            frames.add(img);
            masters.add(tm);
            streamStart(img);
        } else if (j >= 0) {
            offerExtra(img, tm, j);
        } else if (tm >= lo() && tm <= hi() && shortsKept() < shortMax()) {
            frames.add(img);
            masters.add(tm);
            streamStart(img);
        } else {
            img.close();
        }
    }

    /** A new RAW image; it is kept or closed here. */
    boolean offer(Image image) {
        Frm img = new Frm(image);
        long tm = clock.toMaster(img.getTimestamp());
        if (tm != Long.MIN_VALUE) lastSeenMaster = Math.max(lastSeenMaster, tm);
        retryPending();
        if (tm == Long.MIN_VALUE || finished || tm < lo() || tm > end()) {
            img.close();
            return true;
        }
        int j = planOf(img.getTimestamp());
        if (j == -2) {
            if (pending.size() < 3) pending.add(img);
            else img.close();
            return true;
        }
        place(img, tm, j);
        streamCheck();
        return true;
    }

    private long lastSeenMaster = Long.MIN_VALUE;

    /**
     * A frame after the last needed slot has arrived (images come ~0.1-0.2 s after their
     * timestamp, so the clock alone would end too early), or a safety timeout.
     */
    boolean complete(long nowMaster) {
        if (android.os.SystemClock.elapsedRealtime() - startedMs > 3000) return true;
        if (planExp.length == 0) return !frames.isEmpty() && lastSeenMaster > hi();
        return lastSeenMaster > end();
    }

    int brightness;                           // develop: 0 night look, 1 bright
    SyncCamera.ToneBus toneBus;               // stereo: the master's rendering for all phones
    int toneRole;                             // 0 alone, 1 publish (master), 2 wait for the master
    private long[] extraSlots = new long[0];

    private int planOfWait(long ts) {
        resultFor(ts);
        int j = planOf(ts);
        return j == -2 ? -1 : j;
    }

    /** The capture result may arrive after the image: wait up to 1 s for it (writer thread). */
    private TotalCaptureResult resultFor(long ts) {
        long until = android.os.SystemClock.elapsedRealtime() + 1000;
        TotalCaptureResult r;
        while ((r = results.get(ts)) == null && android.os.SystemClock.elapsedRealtime() < until) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
        return r;
    }

    /** Small upright thumbnail of the developed photo (set by finish), or null. */
    volatile android.graphics.Bitmap thumb;
    volatile android.net.Uri jpegUri;               // the saved JPEG (photo viewer)

    private int exifOrientation() {
        return rotation == 90 ? android.media.ExifInterface.ORIENTATION_ROTATE_90
                : rotation == 180 ? android.media.ExifInterface.ORIENTATION_ROTATE_180
                : rotation == 270 ? android.media.ExifInterface.ORIENTATION_ROTATE_270
                : android.media.ExifInterface.ORIENTATION_NORMAL;
    }

    /**
     * Merges the burst in the app and saves 3D_img_NNNN-l.jpg (developed, look, aspect crop) +
     * 3D_img_NNNN-l.dng (merged RAW) + Info/3D_img_NNNN_info-l.txt; with keepFrames also the single
     * frames as Frames/3D_img_NNNN_frameK-l.dng (names: Downloads; -r on the right camera,
     * Mono/2D_img_NNNN... on a mono phone). Writer thread.
     */
    // night mode of JPEG / RAW (item 19): -1 = HDR (JPEG + merged DNG), 0 JPEG only, 1 merged DNG only,
    // 2 both
    volatile int outFormat = -1;

    String finish(boolean keepFrames) {
        finished = true;
        String imgDng = Downloads.mainName("img", seq, "dng"), imgJpg = Downloads.mainName("img", seq, "jpg");
        String infoTxt = Downloads.infoName("img", seq, "info", "txt");
        if (frames.isEmpty()) return "burst #" + seq + ": no frames";
        StringBuilder meta = new StringBuilder();
        meta.append(String.format(Locale.US, "# " + App.TITLE + " HDR burst #%d, %s, %s%n", seq, name, info));
        if (outFormat >= 0) meta.append("# night mode of ").append(PhotoCapture.FORMATS[outFormat]).append('\n');
        final boolean saveJpg = outFormat != 1, saveDng = outFormat != 0;
        meta.append(String.format(Locale.US, "target %d%nperiod %d%nrotation %d%naspect %s%nframes %d%n",
                targetMaster, periodNs, rotation, PhotoCapture.ASPECTS[aspect], frames.size()));
        Integer white = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        meta.append("white_level ").append(white).append('\n');
        meta.append("# k,file,tsLocal,tsMaster,master-target_us,exposure_ns,iso,wb_gains(r;ge;go;b),ccm(9),black(4)\n");
        // frames whose result came late, then the plan frames
        for (Frm img : pending) place(img, clock.toMaster(img.getTimestamp()), planOfWait(img.getTimestamp()));
        pending.clear();
        streamFinishAll();                        // the merge during the capture: the rest (or missing)
        final long finishMs = android.os.SystemClock.elapsedRealtime();
        int nExtra = 0;
        extraSlots = new long[extra.length];
        for (int j = 0; j < extra.length; j++) {
            extraSlots[j] = extra[j] == null ? Long.MIN_VALUE : slotOf(extraMaster[j], j);
            if (extra[j] == null) continue;
            frames.add(extra[j]);
            masters.add(extraMaster[j]);
            extra[j] = null;
            nExtra++;
        }
        TotalCaptureResult[] res = new TotalCaptureResult[frames.size()];
        for (int k = 0; k < frames.size(); k++) res[k] = resultFor(frames.get(k).getTimestamp());
        List<Frm> fr = new ArrayList<>();
        List<Long> ms = new ArrayList<>();
        List<TotalCaptureResult> rs = new ArrayList<>();
        for (int k = 0; k < frames.size(); k++) {
            if (res[k] == null) {
                frames.get(k).close();
                continue;
            }
            fr.add(frames.get(k));
            ms.add(masters.get(k));
            rs.add(res[k]);
        }
        frames.clear();
        frames.addAll(fr);
        masters.clear();
        masters.addAll(ms);
        res = rs.toArray(new TotalCaptureResult[0]);
        if (frames.isEmpty()) return "burst #" + seq + ": no frames with capture results";
        // light of each frame (exposure x ISO); the reference: the base-light frame closest to T
        double[] light = new double[frames.size()];
        double minLight = Double.MAX_VALUE;
        for (int k = 0; k < frames.size(); k++) {
            Long e = res[k].get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer s = res[k].get(CaptureResult.SENSOR_SENSITIVITY);
            light[k] = (e == null ? 1 : e) * (double) (s == null ? 100 : s);
            minLight = Math.min(minLight, light[k]);
        }
        int ref = -1;
        for (int k = 0; k < frames.size(); k++) {
            if (light[k] > minLight * 1.3) continue;
            if (ref < 0 || Math.abs(masters.get(k) - targetMaster) < Math.abs(masters.get(ref) - targetMaster)) ref = k;
        }
        if (ref < 0) ref = 0;
        float[] ratio = new float[frames.size()];
        boolean[] noMotion = new boolean[frames.size()];
        for (int k = 0; k < frames.size(); k++) {
            ratio[k] = (float) (light[k] / light[ref]);
            Long e = res[k].get(CaptureResult.SENSOR_EXPOSURE_TIME);
            noMotion[k] = natural && ratio[k] > 1.2f && e != null && e >= 0.9 * periodNs;
        }
        String err = "";
        int singles = 0;
        for (int k = 0; k < frames.size(); k++) {
            String file = Downloads.frameName(seq, k);
            if (keepFrames) {
                try {
                    if (res[k] == null) throw new IllegalStateException("no capture result");
                    DngCreator dng = new DngCreator(chars, res[k]);
                    dng.setOrientation(exifOrientation());
                    Downloads.Out out = Downloads.open(ctx, file, "image/x-adobe-dng");
                    try (OutputStream os = out.stream) {
                        Frm f = frames.get(k);
                        if (f.img != null) {
                            dng.writeImage(os, f.img);
                        } else {
                            f.copy.rewind();
                            dng.writeByteBuffer(os, new android.util.Size(f.w, f.h), f.copy, 0);
                            f.copy.rewind();
                        }
                    }
                    dng.close();
                    singles++;
                } catch (Exception e) {
                    err = ", " + file + ": " + e.getMessage();
                }
            }
            long tm = masters.get(k);
            meta.append(k).append(',').append(keepFrames ? file : "-").append(',').append(frames.get(k).getTimestamp())
                    .append(',').append(tm).append(',').append(String.format(Locale.US, "%.1f", (tm - targetMaster) / 1e3));
            appendResult(meta, res[k]);
            meta.append('\n');
        }
        meta.append("reference ").append(ref).append('\n');
        meta.append("mode ").append(MODES[mode]).append('\n');
        StringBuilder pl = new StringBuilder();
        for (int j = 0; j < planExp.length; j++) pl.append(String.format(Locale.US, " %.3fms/ISO%d", planExp[j] / 1e6, planIso[j]));
        StringBuilder slots = new StringBuilder();
        for (int j = 0; j < planExp.length; j++) {
            slots.append(' ').append(j < extraSlots.length && extraSlots[j] != Long.MIN_VALUE ? String.valueOf(extraSlots[j]) : "-")
                    .append('/').append(targetSlot(j));
        }
        meta.append("plan_candidates (entry@slot)").append(candidates).append('\n');
        meta.append("plan_slots (used/target)").append(slots).append('\n');
        meta.append(String.format(Locale.US, "plan%s (%d of %d longer frames used)%n",
                pl.length() == 0 ? " none" : pl.toString(), nExtra, planExp.length));

        // ---- merge + develop in the app
        Frm[] arr = frames.toArray(new Frm[0]);
        frames.clear();
        TotalCaptureResult rr = res[ref];
        String merged = "";
        // merged DNG: written to the cache first, saved after the JPEG (embedded as its preview)
        java.io.File dtmp = new java.io.File(ctx.getCacheDir(), "burst.dng");
        String dngNote = null;                    // set when the DNG in the cache is complete
        byte[] previewJpeg = null;
        try {
            if (rr == null) throw new IllegalStateException("no capture result for the reference frame");
            int w = arr[ref].getWidth(), h = arr[ref].getHeight();
            float[][] ws = new float[1][];
            BurstMerger mg = null;
            float[] m = null;
            Stream st = stream;
            String streamNote = "";
            if (st != null) {
                try {
                    st.th.join(120_000);
                } catch (InterruptedException ignored) {
                }
                boolean same;
                synchronized (st.used) {
                    same = st.used.size() == arr.length;
                    for (Frm f : arr) same &= st.used.contains(f.getTimestamp());
                }
                if (st.done && st.err == null && st.m != null && ref == 0 && same) {
                    mg = st.mg;
                    m = st.m;
                    ws[0] = st.ws[0];
                    streamNote = String.format(Locale.US, ", merged during the capture (ready %d ms after it)",
                            Math.max(0, st.doneMs - finishMs));
                } else {
                    streamNote = ", merge during the capture not used (" + (st.err != null ? String.valueOf(st.err)
                            : !st.done ? "not finished" : ref != 0 ? "other reference" : !same ? "other frames" : "no result")
                            + ")";
                    if (st.mg != null) st.mg.core.releaseGpu();
                }
            }
            if (m == null) {
                mg = new BurstMerger(chars, rr, w, h);
                mg.core.noMotion = noMotion;
                mg.setNoise(java.util.Arrays.asList(res));
                mg.core.brightness = brightness;
                m = mg.merge(arr, ref, ws, ratio);       // closes every frame when used
            }
            if (toneBus != null && toneRole != 0) {
                final SyncCamera.ToneBus bus = toneBus;
                final int role = toneRole;
                mg.core.toneLink = new MergeCore.ToneLink() {
                    @Override public void publish(MergeCore.Tone t) {
                        if (role == 1) bus.publish(seq, t.encode());
                    }

                    @Override public MergeCore.Tone await() {
                        if (role != 2) return null;
                        String s = bus.await(seq, 8000);
                        return s == null ? null : MergeCore.Tone.decode(s);
                    }
                };
            }
            // merged RAW
            DngCreator dng = new DngCreator(chars, rr);
            dng.setOrientation(exifOrientation());
            dng.setDescription(String.format(Locale.US, App.TITLE + " HDR burst #%d (%d frames merged%s), %s",
                    seq, arr.length, nExtra > 0 ? ", " + nExtra + " longer" : "", name));
            // 16-bit: pixels x dngScale (the merge's extra precision); DngCreator writes the
            // sensor's black/white level, so they are scaled in the file afterwards
            // (a DNG problem must not cost the JPEG)
            try {
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(dtmp)) {
                    dng.writeByteBuffer(os, new android.util.Size(w, h), mg.toDngPixels(m), 0);
                }
                int dngScale = mg.core.dngScale();
                int patched = DngPatch.scaleLevels(dtmp.getAbsolutePath(), dngScale);
                if (patched < 2) throw new java.io.IOException("levels not found (" + patched + ")");
                dngNote = String.format(Locale.US, "16-bit, pixels and levels x%d", dngScale);
            } catch (Exception e) {
                err += ", merged DNG: " + e.getMessage();
            } finally {
                dng.close();
            }
            // developed JPEG (crop to the aspect, sensor orientation + EXIF orientation)
            double asp = PhotoCapture.ASPECT_VALUES[aspect];
            int cw = w, ch = (int) Math.round(w / asp);
            if (ch > h) {
                ch = h;
                cw = (int) Math.round(h * asp);
            }
            int cx = ((w - cw) / 2) & ~1, cy = ((h - ch) / 2) & ~1;
            int[] px = mg.develop(m, ws[0], cx, cy, cw, ch);
            m = null;
            android.graphics.Bitmap bmp = BurstMerger.toBitmap(px, cw, ch);
            px = null;
            java.io.File tmp = new java.io.File(ctx.getCacheDir(), "burst.jpg");
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, PhotoCapture.jpegQuality, os);
            }
            android.graphics.Matrix rot = new android.graphics.Matrix();
            rot.postRotate(rotation);
            float s = 480f / Math.max(cw, ch);
            rot.preScale(s, s);
            thumb = android.graphics.Bitmap.createBitmap(bmp, 0, 0, cw, ch, rot, true);
            bmp.recycle();
            writeExif(tmp, rr, arr.length);
            byte[] jb = java.nio.file.Files.readAllBytes(tmp.toPath());
            tmp.delete();
            previewJpeg = jb;
            if (saveJpg) {
                Downloads.Out jout = Downloads.open(ctx, imgJpg, "image/jpeg");
                try (OutputStream os = jout.stream) {
                    os.write(jb);
                }
                jpegUri = jout.uri;
                merged = ", merged -> " + jout.path;
            } else {
                merged = ", merged -> " + imgDng;
            }
            meta.append("# in-app merge: ").append(mg.log()).append(streamNote).append('\n');
        } catch (Throwable e) {
            merged = ", merge failed: " + e;
            // the reason also into the burst's text file and the log (the screen line is gone soon)
            StringBuilder st = new StringBuilder();
            for (StackTraceElement el : e.getStackTrace()) {
                if (st.length() > 600) break;
                String cn = el.getClassName();
                st.append(" | ").append(cn.substring(cn.lastIndexOf('.') + 1)).append('.')
                        .append(el.getMethodName()).append(':').append(el.getLineNumber());
            }
            Runtime rt = Runtime.getRuntime();
            meta.append("# in-app merge FAILED: ").append(e).append(String.format(Locale.US,
                    " (heap %d of %d MB)", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20))
                    .append(st).append('\n');
            android.util.Log.e(App.NAME, "burst merge failed", e);
        } finally {
            for (Frm i : arr) if (i != null) i.close();
            store.delete();
        }
        if (dngNote != null && saveDng) {
            // the app's JPEG as the DNG preview: viewers show our rendering, raw editors can
            // match it (RawTherapee auto-matched tone curve). Cropped to the aspect, in sensor
            // orientation like the raw.
            if (previewJpeg != null) {
                try {
                    DngPatch.addPreview(dtmp.getAbsolutePath(), previewJpeg);
                    dngNote += ", app JPEG embedded as preview";
                } catch (Exception e) {
                    err += ", DNG preview: " + e.getMessage();
                }
            }
            try {
                Downloads.Out dout = Downloads.open(ctx, imgDng, "image/x-adobe-dng");
                try (OutputStream os = dout.stream) {
                    java.nio.file.Files.copy(dtmp.toPath(), os);
                }
                meta.append("# merged DNG: ").append(dngNote).append('\n');
            } catch (Exception e) {
                err += ", merged DNG: " + e.getMessage();
            }
        }
        previewJpeg = null;
        dtmp.delete();
        try {
            Downloads.Out t = Downloads.open(ctx, infoTxt, "text/plain");
            try (OutputStream os = t.stream) {
                os.write(meta.toString().getBytes(StandardCharsets.US_ASCII));
            }
        } catch (Exception e) {
            err += ", txt: " + e.getMessage();
        }
        return String.format(Locale.US, "burst #%d: ref %+.0f us%s%s%s", seq,
                (masters.get(ref) - targetMaster) / 1e3, keepFrames ? ", " + singles + " single DNG" : "", merged, err);
    }

    private void writeExif(java.io.File f, TotalCaptureResult r, int n) throws java.io.IOException {
        android.media.ExifInterface ex = new android.media.ExifInterface(f.getAbsolutePath());
        ex.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation()));
        ex.setAttribute(android.media.ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER);
        ex.setAttribute(android.media.ExifInterface.TAG_MODEL, android.os.Build.MODEL);
        ex.setAttribute(android.media.ExifInterface.TAG_SOFTWARE, App.TITLE + " (HDR burst, " + n + " frames)");
        String now = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new java.util.Date());
        ex.setAttribute(android.media.ExifInterface.TAG_DATETIME, now);
        ex.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, now);
        ex.setAttribute(android.media.ExifInterface.TAG_IMAGE_DESCRIPTION, String.format(Locale.US,
                App.TITLE + " HDR burst #%d, %s, aspect %s", seq, name, PhotoCapture.ASPECTS[aspect]));
        Long et = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
        Float fn = r.get(CaptureResult.LENS_APERTURE);
        Float fl = r.get(CaptureResult.LENS_FOCAL_LENGTH);
        if (et != null) ex.setAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME, String.format(Locale.US, "%.6f", et / 1e9));
        if (iso != null) ex.setAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS, String.valueOf(iso));
        if (fn != null) ex.setAttribute(android.media.ExifInterface.TAG_F_NUMBER, String.format(Locale.US, "%.2f", fn));
        if (fl != null) ex.setAttribute(android.media.ExifInterface.TAG_FOCAL_LENGTH, Math.round(fl * 1000) + "/1000");
        ex.saveAttributes();
    }

    /** Master-time error of the reference frame (closest to T), or MIN_VALUE. */
    long refErrorNs() {
        long best = Long.MIN_VALUE;
        for (long tm : masters) {
            if (best == Long.MIN_VALUE || Math.abs(tm - targetMaster) < Math.abs(best)) best = tm - targetMaster;
        }
        return best;
    }

    static void appendResult(StringBuilder m, TotalCaptureResult r) {
        if (r == null) {
            m.append(",,,,,");
            return;
        }
        Long et = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
        android.hardware.camera2.params.RggbChannelVector g = r.get(CaptureResult.COLOR_CORRECTION_GAINS);
        android.hardware.camera2.params.ColorSpaceTransform t = r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        float[] black = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        m.append(',').append(et).append(',').append(iso).append(',');
        if (g != null) m.append(String.format(Locale.US, "%.4f;%.4f;%.4f;%.4f", g.getRed(), g.getGreenEven(),
                g.getGreenOdd(), g.getBlue()));
        m.append(',');
        if (t != null) {
            for (int i = 0; i < 9; i++) {
                if (i > 0) m.append(';');
                m.append(String.format(Locale.US, "%.4f", t.getElement(i % 3, i / 3).floatValue()));
            }
        }
        m.append(',');
        if (black != null) {
            for (int i = 0; i < black.length; i++) {
                if (i > 0) m.append(';');
                m.append(String.format(Locale.US, "%.1f", black[i]));
            }
        }
    }
}
