package com.crunchy3d.synccamphone;

import android.content.Context;
import android.media.Image;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One synchronized photo: the full-resolution JPEG stream is switched on for a short window
 * around the master time T; this keeps the frame whose master time is closest to T and saves it
 * as img_NNNN_<phone>.jpg (+ .txt with the timing). All phones keep the same frame slot.
 */
final class PhotoCapture {
    final int seq;
    final long targetMaster;          // T
    final LedCapture.Clock clock;
    final long startedNs = android.os.SystemClock.elapsedRealtimeNanos();
    private final Context ctx;
    private final String name, info;

    private byte[] best;
    private long bestLocal, bestMaster, bestErr = Long.MAX_VALUE;
    private int seen;
    volatile boolean finished;

    // RAW: the best RAW image is kept open until it is written as DNG (needs its capture result)
    static final String[] FORMATS = {"JPEG", "RAW", "RAW+JPEG", "HDR burst"};
    // JPEG quality of every JPEG the app saves (camera, in-app and HDR burst); the master's
    // setting, the same on all phones
    static final int[] JPEG_QUALITIES = {90, 95, 100};
    static volatile int jpegQuality = 95;
    private android.media.Image bestRaw;
    private long bestRawMaster, bestRawErr = Long.MAX_VALUE;
    private final java.util.Map<Long, android.hardware.camera2.TotalCaptureResult> results = new java.util.HashMap<>();
    private android.hardware.camera2.CameraCharacteristics chars;
    private int rotation;

    void setRaw(android.hardware.camera2.CameraCharacteristics chars, int rotation) {
        this.chars = chars;
        this.rotation = rotation;
    }

    /** Capture result of a frame in the window (camera thread). */
    synchronized void onResult(long ts, android.hardware.camera2.TotalCaptureResult r) {
        if (results.size() < 64) results.put(ts, r);
    }

    /** A RAW image of the window; returns true if it is kept (then it must not be closed). */
    synchronized boolean onRaw(android.media.Image img) {
        long tm = clock.toMaster(img.getTimestamp());
        if (tm == Long.MIN_VALUE || finished) return false;
        long err = Math.abs(tm - targetMaster);
        if (err >= bestRawErr) return false;
        if (bestRaw != null) bestRaw.close();
        bestRaw = img;
        bestRawMaster = tm;
        bestRawErr = err;
        return true;
    }

    private String writeDng(String file) {
        if (bestRaw == null) return "";
        try {
            android.hardware.camera2.TotalCaptureResult r = results.get(bestRaw.getTimestamp());
            if (r == null || chars == null) return ", RAW: no capture result";
            android.hardware.camera2.DngCreator dng = new android.hardware.camera2.DngCreator(chars, r);
            int o = rotation == 90 ? android.media.ExifInterface.ORIENTATION_ROTATE_90
                    : rotation == 180 ? android.media.ExifInterface.ORIENTATION_ROTATE_180
                    : rotation == 270 ? android.media.ExifInterface.ORIENTATION_ROTATE_270
                    : android.media.ExifInterface.ORIENTATION_NORMAL;
            dng.setOrientation(o);
            dng.setDescription(String.format(Locale.US, App.TITLE + " photo #%d, %s, aspect %s, frame - target %+.1f us",
                    seq, name, ASPECTS[aspect], (bestRawMaster - targetMaster) / 1e3));
            Downloads.Out out = Downloads.open(ctx, file, "image/x-adobe-dng");
            try (OutputStream os = out.stream) {
                dng.writeImage(os, bestRaw);
            }
            dng.close();
            return ", DNG";
        } catch (Exception e) {
            return ", RAW failed: " + e.getMessage();
        } finally {
            bestRaw.close();
            bestRaw = null;
        }
    }

    /** Photo aspect ratios: 4:3 = whole sensor, the others are centred crops of it. */
    static final String[] ASPECTS = {"4:3", "3:2", "16:9", "1:1"};
    static final double[] ASPECT_VALUES = {4.0 / 3, 3.0 / 2, 16.0 / 9, 1.0};
    private final int aspect;

    PhotoCapture(Context ctx, String name, String info, int seq, long targetMaster, LedCapture.Clock clock,
                 int aspect) {
        this.ctx = ctx.getApplicationContext();
        this.name = name;
        this.info = info;
        this.seq = seq;
        this.targetMaster = targetMaster;
        this.clock = clock;
        this.aspect = Math.max(0, Math.min(ASPECTS.length - 1, aspect));
    }

    /** Centre crop of a 4:3 JPEG to the chosen aspect (EXIF kept); returns the JPEG unchanged for 4:3. */
    private byte[] crop(byte[] jpeg) throws Exception {
        if (aspect == 0) return jpeg;
        android.graphics.BitmapRegionDecoder dec =
                android.graphics.BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length, false);
        int w = dec.getWidth(), h = dec.getHeight();
        double a = ASPECT_VALUES[aspect];
        int cw = w, ch = (int) Math.round(w / a);
        if (ch > h) {
            ch = h;
            cw = (int) Math.round(h * a);
        }
        android.graphics.Rect r = new android.graphics.Rect((w - cw) / 2, (h - ch) / 2, (w - cw) / 2 + cw,
                (h - ch) / 2 + ch);
        android.graphics.Bitmap bmp = dec.decodeRegion(r, null);
        dec.recycle();
        java.io.File tmp = new java.io.File(ctx.getCacheDir(), "crop.jpg");
        java.io.File src = new java.io.File(ctx.getCacheDir(), "src.jpg");
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, os);
        }
        bmp.recycle();
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(src)) {
            os.write(jpeg);
        }
        // copy the camera's EXIF (orientation, exposure, ISO, time, ...) to the cropped file
        android.media.ExifInterface in = new android.media.ExifInterface(src.getAbsolutePath());
        android.media.ExifInterface out = new android.media.ExifInterface(tmp.getAbsolutePath());
        String[] tags = {android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.TAG_DATETIME,
                android.media.ExifInterface.TAG_DATETIME_ORIGINAL, android.media.ExifInterface.TAG_MAKE,
                android.media.ExifInterface.TAG_MODEL, android.media.ExifInterface.TAG_EXPOSURE_TIME,
                android.media.ExifInterface.TAG_ISO_SPEED_RATINGS, android.media.ExifInterface.TAG_F_NUMBER,
                android.media.ExifInterface.TAG_FOCAL_LENGTH, android.media.ExifInterface.TAG_WHITE_BALANCE,
                android.media.ExifInterface.TAG_SUBSEC_TIME_ORIGINAL};
        for (String t : tags) {
            String v = in.getAttribute(t);
            if (v != null) out.setAttribute(t, v);
        }
        out.saveAttributes();
        byte[] res = java.nio.file.Files.readAllBytes(tmp.toPath());
        tmp.delete();
        src.delete();
        return res;
    }

    /** A JPEG of the window arrived (photo thread). */
    synchronized void onJpeg(Image img) {
        long ts = img.getTimestamp();
        long tm = clock.toMaster(ts);
        if (tm == Long.MIN_VALUE) return;
        seen++;
        long err = Math.abs(tm - targetMaster);
        if (err >= bestErr) return;
        ByteBuffer b = img.getPlanes()[0].getBuffer();
        byte[] data = new byte[b.remaining()];
        b.get(data);
        best = data;
        bestLocal = ts;
        bestMaster = tm;
        bestErr = err;
    }

    // in-app JPEG: the full-resolution YUV image closest to T is kept open until finish()
    private Image bestYuv;
    private boolean cropped;          // best is already cropped to the aspect

    /** A full-res YUV image of the window; returns true if it is kept (then it must not be closed). */
    synchronized boolean onYuv(Image img) {
        long ts = img.getTimestamp();
        long tm = clock.toMaster(ts);
        if (tm == Long.MIN_VALUE || finished) return false;
        seen++;
        long err = Math.abs(tm - targetMaster);
        if (err >= bestErr) return false;
        if (bestYuv != null) bestYuv.close();
        bestYuv = img;
        bestLocal = ts;
        bestMaster = tm;
        bestErr = err;
        return true;
    }

    private static int exifOrientation(int rotation) {
        return rotation == 90 ? android.media.ExifInterface.ORIENTATION_ROTATE_90
                : rotation == 180 ? android.media.ExifInterface.ORIENTATION_ROTATE_180
                : rotation == 270 ? android.media.ExifInterface.ORIENTATION_ROTATE_270
                : android.media.ExifInterface.ORIENTATION_NORMAL;
    }

    /** YUV_420_888 -> NV21 -> JPEG (quality jpegQuality), cropped to the aspect, with EXIF from the capture result. */
    private byte[] encodeYuv(Image img) throws Exception {
        int w = img.getWidth(), h = img.getHeight();
        byte[] nv21 = new byte[w * h * 3 / 2];
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer();
        int yrs = p[0].getRowStride();
        for (int row = 0; row < h; row++) {
            y.position(row * yrs);
            y.get(nv21, row * w, w);
        }
        ByteBuffer u = p[1].getBuffer(), v = p[2].getBuffer();
        int crs = p[1].getRowStride(), cps = p[1].getPixelStride();
        byte[] ur = new byte[crs], vr = new byte[crs];
        int off = w * h, cw = w / 2;
        for (int row = 0; row < h / 2; row++) {
            int pos = row * crs;
            int lu = Math.min(crs, u.limit() - pos), lv = Math.min(crs, v.limit() - pos);
            u.position(pos);
            u.get(ur, 0, lu);
            v.position(pos);
            v.get(vr, 0, lv);
            for (int c = 0; c < cw; c++) {
                nv21[off++] = vr[c * cps];
                nv21[off++] = ur[c * cps];
            }
        }
        double a = ASPECT_VALUES[aspect];
        int ow = w, oh = (int) Math.round(w / a);
        if (oh > h) {
            oh = h;
            ow = (int) Math.round(h * a);
        }
        ow &= ~1;
        oh &= ~1;
        int left = ((w - ow) / 2) & ~1, top = ((h - oh) / 2) & ~1;
        android.graphics.YuvImage yi = new android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21,
                w, h, null);
        java.io.File tmp = new java.io.File(ctx.getCacheDir(), "yuv.jpg");
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(tmp)) {
            yi.compressToJpeg(new android.graphics.Rect(left, top, left + ow, top + oh), jpegQuality, os);
        }
        nv21 = null;
        // EXIF: orientation, camera, time, exposure from the capture result
        android.media.ExifInterface ex = new android.media.ExifInterface(tmp.getAbsolutePath());
        ex.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation(rotation)));
        ex.setAttribute(android.media.ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER);
        ex.setAttribute(android.media.ExifInterface.TAG_MODEL, android.os.Build.MODEL);
        ex.setAttribute(android.media.ExifInterface.TAG_SOFTWARE, App.TITLE + " (in-app JPEG from YUV)");
        String now = new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new java.util.Date());
        ex.setAttribute(android.media.ExifInterface.TAG_DATETIME, now);
        ex.setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, now);
        ex.setAttribute(android.media.ExifInterface.TAG_IMAGE_DESCRIPTION, String.format(Locale.US,
                App.TITLE + " photo #%d, %s, aspect %s, frame - target %+.1f us",
                seq, name, ASPECTS[aspect], (bestMaster - targetMaster) / 1e3));
        android.hardware.camera2.TotalCaptureResult r = results.get(img.getTimestamp());
        if (r != null) {
            Long et = r.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = r.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY);
            Float fn = r.get(android.hardware.camera2.CaptureResult.LENS_APERTURE);
            Float fl = r.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH);
            if (et != null) ex.setAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME,
                    String.format(Locale.US, "%.6f", et / 1e9));
            if (iso != null) ex.setAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS, String.valueOf(iso));
            if (fn != null) ex.setAttribute(android.media.ExifInterface.TAG_F_NUMBER,
                    String.format(Locale.US, "%.2f", fn));
            if (fl != null) ex.setAttribute(android.media.ExifInterface.TAG_FOCAL_LENGTH,
                    Math.round(fl * 1000) + "/1000");
        }
        ex.saveAttributes();
        byte[] res = java.nio.file.Files.readAllBytes(tmp.toPath());
        tmp.delete();
        return res;
    }

    /** Saves the best frame; returns a status line. */
    synchronized String finish() {
        finished = true;
        String dngName = Downloads.mainName("img", seq, "dng");
        if (best == null && bestYuv != null) {
            try {
                best = encodeYuv(bestYuv);
                cropped = true;
            } catch (Exception e) {
                return "photo #" + seq + ": in-app JPEG failed: " + e.getMessage();
            } finally {
                bestYuv.close();
                bestYuv = null;
            }
        }
        if (best == null && bestRaw == null) return "photo #" + seq + ": no frame";
        if (best == null) {                                // RAW only: timing from the RAW frame
            bestMaster = bestRawMaster;
            bestLocal = bestRaw.getTimestamp();
        }
        String raw = writeDng(dngName);
        try {
            String path = dngName;
            if (best != null) {
                byte[] data = cropped ? best : crop(best);
                Downloads.Out o = Downloads.open(ctx, Downloads.mainName("img", seq, "jpg"), "image/jpeg");
                try (OutputStream os = o.stream) {
                    os.write(data);
                }
                path = o.path;
                jpegUri = o.uri;
                thumb = makeThumb(data);
            }
            Downloads.Out t = Downloads.open(ctx, Downloads.infoName("img", seq, "info", "txt"), "text/plain");
            try (OutputStream os = t.stream) {
                os.write(String.format(Locale.US,
                        App.TITLE + " photo #%d, %s, %s, aspect " + ASPECTS[aspect]
                                + "%ntarget master time %d ns%nframe tsLocal %d ns%n"
                                + "frame tsMaster %d ns%nframe - target %+.1f us%nframes in window %d%n",
                        seq, name, info, targetMaster, bestLocal, bestMaster,
                        (bestMaster - targetMaster) / 1e3, seen).getBytes(StandardCharsets.US_ASCII));
            }
            return String.format(Locale.US, "photo #%d: %+.0f us%s -> %s", seq, (bestMaster - targetMaster) / 1e3,
                    raw, path);
        } catch (Exception e) {
            return "photo #" + seq + " failed: " + e.getMessage();
        }
    }

    /** Small upright image of the saved JPEG (for the last-shot thumbnail), or null. */
    volatile android.graphics.Bitmap thumb;
    volatile android.net.Uri jpegUri;               // the saved JPEG (photo viewer)

    private android.graphics.Bitmap makeThumb(byte[] jpeg) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 480) s *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = s;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
            if (b == null || rotation == 0) return b;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(rotation);               // EXIF orientation: the pixels are not turned
            return android.graphics.Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Frame time minus target (ns) of the saved frame. */
    synchronized long errorNs() {
        return best == null && bestMaster == 0 ? Long.MIN_VALUE : bestMaster - targetMaster;
    }
}
