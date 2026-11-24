package com.crunchy3d.synccamphone;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Dark-frame test for the astro mode: with the lens covered, RAW frames at several ISOs, short
 * (read noise: two frames per ISO, their difference removes the fixed pattern) and 16 s (dark
 * current, warm / hot pixels). Camera processing that would hide the sensor's behaviour is off
 * (noise reduction, hot-pixel correction). Saved as Download/CamSyncProbe/Dark/dark_*.dng plus
 * a report of requested vs. delivered exposure / ISO. Evaluated on the PC (tools/dark_eval.py).
 */
final class DarkTest {
    interface Listener {
        void onProgress(String message);

        void onDone(String report);

        void onError(String message);
    }

    private static final class Item {
        final long expNs;
        final int iso, k;

        Item(long expNs, int iso, int k) {
            this.expNs = expNs;
            this.iso = iso;
            this.k = k;
        }
    }

    private final Context ctx;
    private final CameraManager cm;
    private final String cameraId;
    private final SurfaceTexture previewTexture;
    private final Listener listener;
    private final int countdownS;
    private final List<Item> items = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    private CameraCharacteristics chars;
    private volatile boolean cancelled;
    private int idx;
    private Image pendingImage;
    private TotalCaptureResult pendingResult;
    private final String model = android.os.Build.MODEL.replaceAll("[^A-Za-z0-9]+", "-");

    DarkTest(Context ctx, CameraManager cm, String cameraId, SurfaceTexture previewTexture, int countdownS,
             Listener listener) {
        this.ctx = ctx;
        this.cm = cm;
        this.cameraId = cameraId;
        this.previewTexture = previewTexture;
        this.countdownS = countdownS;
        this.listener = listener;
    }

    void start() {
        thread = new HandlerThread("dark-test");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::open);
    }

    void cancel() {
        cancelled = true;
        if (handler != null) handler.post(this::close);
    }

    // ------------------------------------------------------------------ setup

    private void open() {
        try {
            chars = cm.getCameraCharacteristics(cameraId);
            Range<Integer> isoR = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> expR = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            int isoMin = isoR != null ? isoR.getLower() : 50, isoMax = isoR != null ? isoR.getUpper() : 3200;
            long expMax = expR != null ? expR.getUpper() : 1_000_000_000L;
            // read noise: 1/100 s, two frames per ISO
            for (int iso : new int[]{50, 100, 200, 400, 800, 1600, 3200}) {
                int is = Math.max(isoMin, Math.min(isoMax, iso));
                for (int k = 0; k < 2; k++) items.add(new Item(10_000_000L, is, k));
            }
            // dark current / hot pixels: the longest exposure up to 16 s
            long longNs = Math.min(expMax, 16_000_000_000L);
            for (int iso : new int[]{400, 1600}) {
                int is = Math.max(isoMin, Math.min(isoMax, iso));
                for (int k = 0; k < 2; k++) items.add(new Item(longNs, is, k));
            }
            report.append(String.format(Locale.US, "# " + App.TITLE + " dark-frame test, %s, camera %s%n"
                            + "# ISO range %d..%d, exposure max %.3f s, white level %s, black pattern %s%n"
                            + "# idx,req_exp_ns,req_iso,exp_ns,iso,frame_ns,file%n",
                    android.os.Build.MODEL, cameraId, isoMin, isoMax, expMax / 1e9,
                    chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
                    chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)));
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] raws = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            if (raws == null || raws.length == 0) throw new IllegalStateException("no RAW output");
            Size raw = raws[0];
            for (Size s : raws) if ((long) s.getWidth() * s.getHeight() > (long) raw.getWidth() * raw.getHeight()) raw = s;
            reader = ImageReader.newInstance(raw.getWidth(), raw.getHeight(), ImageFormat.RAW_SENSOR, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireNextImage();
                if (img == null) return;
                if (pendingImage != null) pendingImage.close();
                pendingImage = img;
                tryWrite();
            }, handler);
            previewTexture.setDefaultBufferSize(640, 480);
            previewSurface = new Surface(previewTexture);
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    device = d;
                    createSession();
                }

                @Override public void onDisconnected(CameraDevice d) {
                    fail("camera disconnected");
                }

                @Override public void onError(CameraDevice d, int error) {
                    fail("camera error " + error);
                }
            }, handler);
        } catch (SecurityException e) {
            fail("no camera permission");
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    @SuppressWarnings("deprecation")
    private void createSession() {
        try {
            device.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            countdown(countdownS);
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            fail("session configuration failed");
                        }
                    }, handler);
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    private void countdown(int s) {
        if (cancelled) return;
        if (s > 0) {
            listener.onProgress("Cover the camera now - start in " + s + " s");
            handler.postDelayed(() -> countdown(s - 1), 1000);
            return;
        }
        idx = 0;
        next();
    }

    // ------------------------------------------------------------------ capture loop

    private void next() {
        if (cancelled) return;
        if (idx >= items.size()) {
            finish();
            return;
        }
        Item it = items.get(idx);
        listener.onProgress(String.format(Locale.US, "Dark frame %d of %d: %s, ISO %d - keep the camera covered",
                idx + 1, items.size(), it.expNs >= 1_000_000_000L
                        ? String.format(Locale.US, "%.0f s", it.expNs / 1e9) : "1/100 s", it.iso));
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL);
            b.addTarget(previewSurface);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it.expNs);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, it.iso);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, it.expNs + 5_000_000L);
            // the Pixel HAL clamps the frame duration to the AE fps range even with AE off:
            // the lowest range offered
            Range<Integer>[] fr = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fr != null && fr.length > 0) {
                Range<Integer> lo = fr[0];
                for (Range<Integer> r : fr) if (r.getLower() < lo.getLower()) lo = r;
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, lo);
            }
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
            b.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF);
            b.set(CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE, true);
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            pendingResult = null;
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    pendingResult = res;
                    tryWrite();
                }

                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r,
                                                      android.hardware.camera2.CaptureFailure f) {
                    report.append("# frame ").append(idx).append(" failed, reason ").append(f.getReason()).append('\n');
                    idx++;
                    next();
                }
            }, handler);
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    private void tryWrite() {
        if (pendingImage == null || pendingResult == null || idx >= items.size()) return;
        Item it = items.get(idx);
        Image img = pendingImage;
        TotalCaptureResult res = pendingResult;
        pendingImage = null;
        pendingResult = null;
        Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME), fd = res.get(CaptureResult.SENSOR_FRAME_DURATION);
        Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
        String name = String.format(Locale.US, "Dark/dark_%s_iso%d_%s_%d.dng", model, it.iso,
                it.expNs >= 1_000_000_000L ? String.format(Locale.US, "%ds", it.expNs / 1_000_000_000L) : "10ms", it.k);
        String path;
        try (DngCreator dng = new DngCreator(chars, res)) {
            Downloads.Out out = Downloads.open(ctx, name, "image/x-adobe-dng");
            try (OutputStream os = out.stream) {
                dng.writeImage(os, img);
            }
            path = out.path;
        } catch (Exception ex) {
            path = "write failed: " + ex.getMessage();
        } finally {
            img.close();
        }
        report.append(String.format(Locale.US, "%d,%d,%d,%d,%d,%d,%s%n", idx, it.expNs, it.iso,
                e != null ? e : -1, iso != null ? iso : -1, fd != null ? fd : -1, path));
        idx++;
        handler.post(this::next);
    }

    private void finish() {
        String text = report.toString();
        try {
            Downloads.Out out = Downloads.open(ctx, "Dark/" + Downloads.fileName("dark_report", "txt"), "text/plain");
            try (OutputStream os = out.stream) {
                os.write(text.getBytes(StandardCharsets.US_ASCII));
            }
        } catch (Exception ignored) {
            // the report is also shown on the screen
        }
        close();
        listener.onDone(text);
    }

    private void fail(String msg) {
        close();
        listener.onError(msg);
    }

    private void close() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
            // closing anyway
        }
        session = null;
        if (device != null) device.close();
        device = null;
        if (reader != null) reader.close();
        reader = null;
        if (pendingImage != null) pendingImage.close();
        pendingImage = null;
        if (thread != null) thread.quitSafely();
    }
}
