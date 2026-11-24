package com.crunchy3d.synccamphone;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Astro series: the planned frames (long frames of up to 16 s, then short highlight frames when
 * lights would clip) are requested back to back from a common master time. Each frame, as it
 * arrives: saved as Frames/3D_img_NNNN_frameK-l.dng, its raw data also copied to a cache file,
 * the camera buffer released at once (a 2-min series has up to 14 frames - more than the camera's
 * buffers). After the last frame the cache files are merged with the HDR-burst merge (MergeCore,
 * the same code as the PC bench): the shortest frame is the reference (highlights), the long
 * frames chained onto it; per-frame warm-pixel and black-level fixes; astro brightness. Output
 * like an HDR burst: 3D_img_NNNN-l.jpg + 16-bit 3D_img_NNNN-l.dng (JPEG embedded as preview) +
 * Info/3D_img_NNNN_info-l.txt (burst format, tools/bench_prep.py reads it).
 */
final class AstroCapture {
    final int seq;
    final long startMaster;
    volatile long endMaster;                  // expected end of the last frame (master time): countdown
    /** A frame of the series started: the countdown follows the real timing (the camera can insert pauses). */
    void frameStarted(int j, long tMaster) {
        if (j < 0 || j >= exp.length || tMaster == Long.MIN_VALUE) return;
        long rest = 0;
        for (int i = j; i < exp.length; i++) rest += exp[i] + 30_000_000L;
        endMaster = Math.max(endMaster, tMaster + rest);
    }
    final long[] exp;
    final int[] iso;
    final LedCapture.Clock clock;
    private final Context ctx;
    private final String name, info;
    private final CameraCharacteristics chars;
    private final PhotoBurst.Results results;
    private final int rotation, aspect, brightness;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final StringBuilder lines = new StringBuilder();
    private final List<TotalCaptureResult> res = new ArrayList<>();
    private final List<File> raws = new ArrayList<>();
    private int written, failed, dropped, w, h;
    private volatile int accepted;
    private String err = "";
    volatile String syncNote = "";            // SyncCamera: how the start was synchronised
    volatile boolean syncOk;                  // the spacer was used (start synchronised)
    volatile long a0StartMaster = Long.MIN_VALUE, a0ExposureNs;   // first frame: start (master time), exposure
    // item 8: the lead-in / spacer frames (start in master time, requested and real exposure / duration)
    private final StringBuffer leadLog = new StringBuffer();

    /** The user stopped the series: nothing is merged or saved, the cache files are deleted. */
    void discard() {
        finished = true;
        writer.shutdown();
        new Thread(() -> {
            try {
                writer.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            synchronized (res) {
                for (File f : raws) f.delete();
                raws.clear();
            }
        }, "astro-discard").start();
    }

    void leadStarted(String tag, long tMaster) {
        leadLog.append(String.format(Locale.US, "%n# lead %s start %d (master-target_us %.1f)", tag, tMaster,
                (tMaster - startMaster) / 1e3));
    }

    void leadResult(String tag, long expNs, long durNs, Long reqExp, Long reqDur) {
        leadLog.append(String.format(Locale.US, "%n# lead %s exposure %d (req %d) frame %d (req %d)", tag, expNs,
                reqExp == null ? -1 : reqExp, durNs, reqDur == null ? -1 : reqDur));
    }
    /** Called (writer thread) once every planned frame was written or failed. */
    volatile Runnable onComplete;
    volatile boolean finished;
    final long deadlineMs;                 // elapsedRealtime: give up waiting for frames after this
    volatile android.graphics.Bitmap thumb;
    volatile android.net.Uri jpegUri;
    // stereo: the master publishes its rendering (white balance, colour matrix, fusion, brightness),
    // the cameras develop with it - each phone's own AWB differed strongly at night (balcony test
    // 2026-11-16: R/B gains 1.28/2.43 vs 1.59/2.09, a cyan vs a yellow sky)
    volatile SyncCamera.ToneBus toneBus;
    volatile int toneRole;                    // 0 alone, 1 publish (master), 2 wait for the master
    static final long TONE_WAIT_MS = 45_000;  // the master's merge may finish later than a camera's

    AstroCapture(Context ctx, String name, String info, int seq, long startMaster, long[] exp, int[] iso,
                 LedCapture.Clock clock, CameraCharacteristics chars, PhotoBurst.Results results, int rotation,
                 int aspect, int brightness) {
        this.ctx = ctx;
        GpuRobust.cacheDir = new java.io.File(ctx.getCacheDir(), "gpu");
        this.name = name;
        this.info = info;
        this.seq = seq;
        this.startMaster = startMaster;
        this.exp = exp;
        this.iso = iso;
        this.clock = clock;
        this.chars = chars;
        this.results = results;
        this.rotation = rotation;
        this.aspect = aspect;
        this.brightness = brightness;
        long sum = 0;
        for (long e : exp) sum += e + 30_000_000L;        // + the HAL's gap after each frame (~30 ms)
        this.endMaster = startMaster + sum;
        long total = 0;
        for (long e : exp) total += e + 100_000_000L;
        // + one hidden long frame after the first (Pixel HAL) + margin
        deadlineMs = android.os.SystemClock.elapsedRealtime() + (total + (exp.length > 0 ? exp[0] : 0)) / 1_000_000L
                + 30_000L;
    }

    /**
     * Photo thread: a RAW frame after the series was queued. Only frames of the astro requests
     * (tag "A<j>") count: preview frames still in the camera pipeline came first (test 2026-11-16:
     * they were taken for astro frames, the short frames were lost, a Pixel 4 got only those).
     */
    void offer(Image img) {
        writer.execute(() -> write(img));
    }

    /** Camera thread: an astro request failed. */
    void frameFailed(int j) {
        writer.execute(() -> {
            failed++;
            err += ", frame " + j + " failed in the camera";
            checkDone();
        });
    }

    private void checkDone() {
        Runnable c = onComplete;
        if (written + failed >= exp.length && c != null) {
            onComplete = null;
            c.run();
        }
    }

    private int exifOrientation() {
        return rotation == 90 ? android.media.ExifInterface.ORIENTATION_ROTATE_90
                : rotation == 180 ? android.media.ExifInterface.ORIENTATION_ROTATE_180
                : rotation == 270 ? android.media.ExifInterface.ORIENTATION_ROTATE_270
                : android.media.ExifInterface.ORIENTATION_NORMAL;
    }

    private void write(Image img) {
        long ts = img.getTimestamp();
        TotalCaptureResult r = null;
        try {
            // the capture result may come a little after the image
            for (int i = 0; i < 150 && (r = results.get(ts)) == null; i++) Thread.sleep(20);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        Object tag = r == null ? null : r.getRequest().getTag();
        if (tag == null || !tag.toString().startsWith("A") || accepted >= exp.length) {
            dropped++;                        // a preview frame (or no result): not part of the series
            img.close();
            return;
        }
        int k = accepted++;
        if ("A0".equals(tag.toString())) {           // item 8: start of the first (shortest) frame
            a0StartMaster = clock.toMaster(ts);
            Long e0 = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            a0ExposureNs = e0 == null ? 0 : e0;
        }
        String file = Downloads.frameName(seq, k);
        try {
            try (DngCreator dng = new DngCreator(chars, r)) {
                dng.setOrientation(exifOrientation());
                dng.setDescription(String.format(Locale.US, App.TITLE + " astro #%d frame %d, %s", seq, k, name));
                Downloads.Out out = Downloads.open(ctx, file, "image/x-adobe-dng");
                try (OutputStream os = out.stream) {
                    dng.writeImage(os, img);
                }
            }
            // the raw data for the merge (cache file, row by row)
            w = img.getWidth();
            h = img.getHeight();
            File cf = new File(ctx.getCacheDir(), "astro_" + k + ".raw");
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer src = p.getBuffer();
            int stride = p.getRowStride();
            try (FileChannel ch = new RandomAccessFile(cf, "rw").getChannel()) {
                ch.truncate(0);
                for (int y = 0; y < h; y++) {
                    ByteBuffer row = src.duplicate();
                    row.position(y * stride);
                    row.limit(y * stride + w * 2);
                    while (row.hasRemaining()) ch.write(row);
                }
            }
            synchronized (res) {
                res.add(r);
                raws.add(cf);
            }
            written++;
        } catch (Exception e) {
            err += ", frame " + k + ": " + e.getMessage();
            file = "-";
            failed++;
        } finally {
            img.close();
        }
        long tm = clock.toMaster(ts);
        synchronized (lines) {
            lines.append(k).append(',').append(file).append(',').append(ts).append(',').append(tm).append(',')
                    .append(String.format(Locale.US, "%.1f", (tm - startMaster) / 1e3));
            PhotoBurst.appendResult(lines, r);
            lines.append(',').append(tag);
            lines.append('\n');
        }
        checkDone();
    }

    /**
     * After the series (all frames written or the deadline passed): merge + develop, write the
     * JPEG, the merged DNG and the info file; returns a status line. Own thread.
     */
    String finish() {
        finished = true;
        writer.shutdown();
        try {
            writer.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        List<TotalCaptureResult> rs;
        List<File> fs;
        synchronized (res) {
            rs = new ArrayList<>(res);
            fs = new ArrayList<>(raws);
        }
        int n = rs.size();
        // light of each frame; the reference = the shortest (holds the highlights)
        double[] light = new double[n];
        long[] expNs = new long[n];
        int ref = 0;
        for (int k = 0; k < n; k++) {
            Long e = rs.get(k).get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer s = rs.get(k).get(CaptureResult.SENSOR_SENSITIVITY);
            expNs[k] = e == null ? 0 : e;
            light[k] = (e == null ? 1 : e) * (double) (s == null ? 100 : s);
            if (light[k] < light[ref]) ref = k;
        }
        // item 1: only the longest frames are merged, the reference is the first of them
        boolean[] skip = new boolean[n];
        if (!MergeCore.ASTRO_ALL) ref = MergeCore.astroLongest(light, skip);
        StringBuilder m = new StringBuilder();
        m.append(String.format(Locale.US, "# " + App.TITLE + " ASTRO #%d, %s, %s%n", seq, name, info));
        m.append(String.format(Locale.US, "target %d%nperiod 0%nrotation %d%naspect %s%nframes %d%n",
                startMaster, rotation, PhotoCapture.ASPECTS[aspect], written));
        m.append("white_level ").append(chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)).append('\n');
        m.append("# k,file,tsLocal,tsMaster,master-target_us,exposure_ns,iso,wb_gains(r;ge;go;b),ccm(9),black(4)\n");
        synchronized (lines) {
            m.append(lines);
        }
        m.append("reference ").append(ref).append("\nmode Astro\n");
        m.append("# ").append(syncNote).append(leadLog).append('\n');
        m.append("plan");
        for (int j = 0; j < exp.length; j++) m.append(String.format(Locale.US, " %.3fs/ISO%d", exp[j] / 1e9, iso[j]));
        m.append(String.format(Locale.US, " (%d of %d frames, %d preview frames skipped)%n", written,
                exp.length, dropped));
        String merged = "";
        if (n >= 1) merged = mergeAndSave(rs, fs, light, expNs, ref, skip, m);
        for (File f : fs) f.delete();
        try {
            Downloads.Out t = Downloads.open(ctx, Downloads.infoName("img", seq, "info", "txt"), "text/plain");
            try (OutputStream os = t.stream) {
                os.write(m.toString().getBytes(StandardCharsets.US_ASCII));
            }
        } catch (Exception e) {
            err += ", txt: " + e.getMessage();
        }
        return String.format(Locale.US, "astro #%d: %d of %d frames%s%s", seq, written, exp.length, merged, err);
    }

    private String mergeAndSave(List<TotalCaptureResult> rs, List<File> fs, double[] light, long[] expNs, int ref,
                                boolean[] skip, StringBuilder meta) {
        int n = rs.size();
        TotalCaptureResult rr = rs.get(ref);
        File dtmp = new File(ctx.getCacheDir(), "astro.dng");
        String dngNote = null, merged;
        byte[] previewJpeg = null;
        try {
            BurstMerger mg = new BurstMerger(chars, rr, w, h);
            mg.core.frameExposureNs = expNs;
            mg.core.astro = true;
            mg.core.skip = skip;
            mg.setNoise(rs);
            mg.core.brightness = brightness;
            final SyncCamera.ToneBus bus = toneBus;
            final int role = toneRole;
            if (bus != null && role != 0) {
                mg.core.toneLink = new MergeCore.ToneLink() {
                    @Override public void publish(MergeCore.Tone t) {
                        if (role == 1) bus.publish(seq, t.encode());
                    }

                    @Override public MergeCore.Tone await() {
                        if (role != 2) return null;
                        String s = bus.await(seq, TONE_WAIT_MS);
                        return s == null ? null : MergeCore.Tone.decode(s);
                    }
                };
            }
            float[] ratio = new float[n];
            for (int k = 0; k < n; k++) ratio[k] = (float) (light[k] / light[ref]);
            final int W = w, H = h;
            MergeCore.Frames frames = new MergeCore.Frames() {
                @Override public void read(int k, short[] buf) {
                    try (FileChannel ch = new RandomAccessFile(fs.get(k), "r").getChannel()) {
                        ByteBuffer bb = ch.map(FileChannel.MapMode.READ_ONLY, 0, (long) W * H * 2)
                                .order(ByteOrder.LITTLE_ENDIAN);
                        bb.asShortBuffer().get(buf, 0, W * H);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override public void release(int k) {
                    fs.get(k).delete();
                }
            };
            float[][] ws = new float[1][];
            float[] mm = mg.core.merge(frames, n, ref, ws, ratio);
            // merged RAW, 16 bit (see PhotoBurst)
            try (DngCreator dng = new DngCreator(chars, rr)) {
                dng.setOrientation(exifOrientation());
                dng.setDescription(String.format(Locale.US, App.TITLE + " astro #%d (%d frames merged), %s", seq, n, name));
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(dtmp)) {
                    dng.writeByteBuffer(os, new android.util.Size(w, h), mg.toDngPixels(mm), 0);
                }
                int sc = mg.core.dngScale();
                if (DngPatch.scaleLevels(dtmp.getAbsolutePath(), sc) >= 2) {
                    dngNote = String.format(Locale.US, "16-bit, pixels and levels x%d", sc);
                }
            } catch (Exception e) {
                err += ", merged DNG: " + e.getMessage();
            }
            // developed JPEG (aspect crop, sensor orientation + EXIF orientation)
            double asp = PhotoCapture.ASPECT_VALUES[aspect];
            int cw = w, ch = (int) Math.round(w / asp);
            if (ch > h) {
                ch = h;
                cw = (int) Math.round(h * asp);
            }
            int cx = ((w - cw) / 2) & ~1, cy = ((h - ch) / 2) & ~1;
            int[] px = mg.develop(mm, ws[0], cx, cy, cw, ch);
            mm = null;
            android.graphics.Bitmap bmp = BurstMerger.toBitmap(px, cw, ch);
            px = null;
            File tmp = new File(ctx.getCacheDir(), "astro.jpg");
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, PhotoCapture.jpegQuality, os);
            }
            android.graphics.Matrix rot = new android.graphics.Matrix();
            rot.postRotate(rotation);
            float s = 480f / Math.max(cw, ch);
            rot.preScale(s, s);
            thumb = android.graphics.Bitmap.createBitmap(bmp, 0, 0, cw, ch, rot, true);
            bmp.recycle();
            android.media.ExifInterface ex = new android.media.ExifInterface(tmp.getAbsolutePath());
            ex.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation()));
            ex.setAttribute(android.media.ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER);
            ex.setAttribute(android.media.ExifInterface.TAG_MODEL, android.os.Build.MODEL);
            ex.setAttribute(android.media.ExifInterface.TAG_SOFTWARE, App.TITLE + " (astro, " + n + " frames)");
            String now = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new java.util.Date());
            ex.setAttribute(android.media.ExifInterface.TAG_DATETIME, now);
            ex.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, now);
            long total = 0;
            for (long e : expNs) total += e;
            ex.setAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME, String.format(Locale.US, "%.3f", total / 1e9));
            Integer is = rs.get(0).get(CaptureResult.SENSOR_SENSITIVITY);
            if (is != null) ex.setAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS, String.valueOf(is));
            ex.saveAttributes();
            Downloads.Out jout = Downloads.open(ctx, Downloads.mainName("img", seq, "jpg"), "image/jpeg");
            byte[] jb = java.nio.file.Files.readAllBytes(tmp.toPath());
            try (OutputStream os = jout.stream) {
                os.write(jb);
            }
            tmp.delete();
            previewJpeg = jb;
            jpegUri = jout.uri;
            merged = ", merged -> " + jout.path;
            meta.append("# in-app merge: ").append(mg.log()).append('\n');
        } catch (Throwable e) {
            merged = ", merge failed: " + e;
            Runtime rt = Runtime.getRuntime();
            meta.append("# in-app merge FAILED: ").append(e).append(String.format(Locale.US, " (heap %d of %d MB)",
                    (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20)).append('\n');
            android.util.Log.e(App.NAME, "astro merge failed", e);
        }
        if (dngNote != null) {
            if (previewJpeg != null) {
                try {
                    DngPatch.addPreview(dtmp.getAbsolutePath(), previewJpeg);
                    dngNote += ", app JPEG embedded as preview";
                } catch (Exception e) {
                    err += ", DNG preview: " + e.getMessage();
                }
            }
            try {
                Downloads.Out dout = Downloads.open(ctx, Downloads.mainName("img", seq, "dng"), "image/x-adobe-dng");
                try (OutputStream os = dout.stream) {
                    java.nio.file.Files.copy(dtmp.toPath(), os);
                }
                meta.append("# merged DNG: ").append(dngNote).append('\n');
            } catch (Exception e) {
                err += ", merged DNG: " + e.getMessage();
            }
        }
        dtmp.delete();
        return merged;
    }
}
