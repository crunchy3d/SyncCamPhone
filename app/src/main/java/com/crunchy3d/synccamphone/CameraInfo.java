package com.crunchy3d.synccamphone;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Range;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Dumps the camera characteristics that matter for frame-phase synchronisation. */
final class CameraInfo {
    private CameraInfo() {}

    static String deviceHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("Device : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(", API ").append(Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 31) {
            sb.append(", SoC ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL);
        }
        sb.append('\n');
        sb.append("Build  : ").append(Build.FINGERPRINT).append('\n');
        return sb.toString();
    }

    static String dump(CameraManager cm) throws CameraAccessException {
        StringBuilder sb = new StringBuilder("=== " + App.TITLE + ": camera info ===\n");
        sb.append(deviceHeader()).append('\n');
        String[] ids = cm.getCameraIdList();
        sb.append("Camera IDs: ").append(Arrays.toString(ids)).append("\n\n");
        Set<String> done = new HashSet<>(Arrays.asList(ids));
        for (String id : ids) {
            dumpOne(sb, cm, id, null);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            for (String id : ids) {
                for (String phys : cm.getCameraCharacteristics(id).getPhysicalCameraIds()) {
                    if (done.add(phys)) dumpOne(sb, cm, phys, id);
                }
            }
        }
        return sb.toString();
    }

    static String facingName(Integer f) {
        if (f == null) return "?";
        switch (f) {
            case CameraCharacteristics.LENS_FACING_FRONT: return "front";
            case CameraCharacteristics.LENS_FACING_BACK: return "back";
            case CameraCharacteristics.LENS_FACING_EXTERNAL: return "external";
            default: return "facing " + f;
        }
    }

    private static String levelName(Integer l) {
        if (l == null) return "?";
        switch (l) {
            case 0: return "LIMITED";
            case 1: return "FULL";
            case 2: return "LEGACY";
            case 3: return "LEVEL_3";
            case 4: return "EXTERNAL";
            default: return "level " + l;
        }
    }

    private static final String[] CAP_NAMES = {
            "BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "MANUAL_POST_PROCESSING", "RAW",
            "PRIVATE_REPROCESSING", "READ_SENSOR_SETTINGS", "BURST_CAPTURE", "YUV_REPROCESSING",
            "DEPTH_OUTPUT", "CONSTRAINED_HIGH_SPEED_VIDEO", "MOTION_TRACKING", "LOGICAL_MULTI_CAMERA",
            "MONOCHROME", "SECURE_IMAGE_DATA", "SYSTEM_CAMERA", "OFFLINE_PROCESSING",
            "ULTRA_HIGH_RESOLUTION_SENSOR", "REMOSAIC_REPROCESSING", "DYNAMIC_RANGE_TEN_BIT",
            "STREAM_USE_CASE", "COLOR_SPACE_PROFILES"};

    private static String capName(int c) {
        return c >= 0 && c < CAP_NAMES.length ? CAP_NAMES[c] : "CAP_" + c;
    }

    private static final String[] AE_NAMES = {"OFF", "ON", "ON_AUTO_FLASH", "ON_ALWAYS_FLASH",
            "ON_AUTO_FLASH_REDEYE", "ON_EXTERNAL_FLASH", "ON_LOW_LIGHT_BOOST"};

    static boolean has(int[] arr, int v) {
        if (arr == null) return false;
        for (int a : arr) if (a == v) return true;
        return false;
    }

    private static String ms(long ns) {
        return String.format(Locale.US, "%.3f ms", ns / 1e6);
    }

    private static void line(StringBuilder sb, String key, Object value) {
        sb.append(String.format(Locale.US, "  %-26s %s%n", key + ":", value));
    }

    private static void dumpOne(StringBuilder sb, CameraManager cm, String id, String logicalParent) {
        CameraCharacteristics c;
        try {
            c = cm.getCameraCharacteristics(id);
        } catch (Exception e) {
            sb.append("Camera ").append(id).append(": error ").append(e).append("\n\n");
            return;
        }
        sb.append("----- Camera ").append(id);
        if (logicalParent != null) sb.append(" (physical camera of logical ").append(logicalParent).append(')');
        sb.append(" -----\n");

        line(sb, "Facing", facingName(c.get(CameraCharacteristics.LENS_FACING)));
        float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        line(sb, "Focal length(s)", focal == null ? "?" : Arrays.toString(focal) + " mm");
        Size pix = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        line(sb, "Pixel array", pix);
        line(sb, "Hardware level", levelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));

        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        List<String> capList = new ArrayList<>();
        if (caps != null) for (int cap : caps) capList.add(capName(cap));
        line(sb, "Capabilities", String.join(", ", capList));

        Integer tsSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        line(sb, "Timestamp source", tsSource == null ? "?" :
                tsSource == 1 ? "REALTIME (elapsedRealtime base)" : "UNKNOWN (not tied to system clock)");

        Integer sync = c.get(CameraCharacteristics.SYNC_MAX_LATENCY);
        String syncText;
        if (sync == null) syncText = "?";
        else if (sync == 0) syncText = "PER_FRAME_CONTROL (0)";
        else if (sync == -1) syncText = "UNKNOWN (-1)";
        else syncText = sync + " frames";
        line(sb, "Sync max latency", syncText);

        Byte depth = c.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH);
        line(sb, "Pipeline max depth", depth == null ? "?" : depth.toString());

        Long maxFd = c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        line(sb, "Max frame duration", maxFd == null ? "?" : ms(maxFd));
        Range<Long> exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        line(sb, "Exposure range", exp == null ? "?" : ms(exp.getLower()) + " .. " + ms(exp.getUpper()));
        Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        line(sb, "ISO range", iso == null ? "?" : iso.getLower() + " .. " + iso.getUpper());

        int[] ae = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        List<String> aeList = new ArrayList<>();
        if (ae != null) for (int m : ae) aeList.add(m >= 0 && m < AE_NAMES.length ? AE_NAMES[m] : "AE_" + m);
        line(sb, "AE modes", String.join(", ", aeList));
        Range<Integer>[] fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        line(sb, "AE target fps ranges", fps == null ? "?" : Arrays.toString(fps));

        int[] vstab = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        line(sb, "Video stabilization", vstab == null ? "?" : Arrays.toString(vstab) + " (0=off,1=on,2=preview)");
        int[] ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        line(sb, "Optical stabilization", ois == null ? "?" : Arrays.toString(ois) + " (0=off,1=on)");

        if (Build.VERSION.SDK_INT >= 28 && has(caps, 11)) {
            Integer st = c.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE);
            line(sb, "Logical sensor sync", st == null ? "?" : st == 1 ? "CALIBRATED" : "APPROXIMATE");
            line(sb, "Physical camera IDs", c.getPhysicalCameraIds());
        }
        if (Build.VERSION.SDK_INT >= 34) {
            Integer ro = c.get(CameraCharacteristics.SENSOR_READOUT_TIMESTAMP);
            line(sb, "Readout timestamp", ro == null ? "?" : ro == 1 ? "HARDWARE" : "NOT_SUPPORTED");
        }

        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            sb.append("  Preview sizes (SurfaceTexture) @ max fps:\n");
            appendSizes(sb, map, map.getOutputSizes(SurfaceTexture.class), SurfaceTexture.class, -1);
            sb.append("  Video sizes (MediaRecorder) @ max fps:\n");
            appendSizes(sb, map, map.getOutputSizes(MediaRecorder.class), MediaRecorder.class, -1);
            sb.append("  YUV_420_888 sizes @ max fps:\n");
            appendSizes(sb, map, map.getOutputSizes(ImageFormat.YUV_420_888), null, ImageFormat.YUV_420_888);
            Size[] jpeg = map.getOutputSizes(ImageFormat.JPEG);
            if (jpeg != null && jpeg.length > 0) {
                Size big = largest(jpeg);
                line(sb, "Largest JPEG", big + ", stall " + ms(map.getOutputStallDuration(ImageFormat.JPEG, big)));
            }
            if (has(caps, 9)) {
                sb.append("  High-speed video:\n");
                for (Size s : map.getHighSpeedVideoSizes()) {
                    sb.append("    ").append(s).append(' ')
                            .append(Arrays.toString(map.getHighSpeedVideoFpsRangesFor(s))).append('\n');
                }
            }
        }

        // Verdict
        boolean manual = has(caps, 1);
        boolean realtime = tsSource != null && tsSource == 1;
        boolean aeOff = has(ae, 0);
        List<String> missing = new ArrayList<>();
        if (!manual) missing.add("no MANUAL_SENSOR");
        if (!realtime) missing.add("timestamps not REALTIME");
        if (!aeOff) missing.add("AE cannot be switched off");
        sb.append("  >> Phase-steering verdict: ");
        if (missing.isEmpty()) {
            sb.append("PROMISING (manual frame duration + REALTIME timestamps).\n");
            sb.append("     Run the timing test to confirm one-frame duration changes are honoured.\n");
        } else {
            sb.append("PROBLEM - ").append(String.join("; ", missing)).append(".\n");
        }
        sb.append('\n');
    }

    static Size largest(Size[] sizes) {
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    private static void appendSizes(StringBuilder sb, StreamConfigurationMap map, Size[] sizes,
                                    Class<?> klass, int format) {
        if (sizes == null || sizes.length == 0) {
            sb.append("    (none)\n");
            return;
        }
        Size[] sorted = sizes.clone();
        Arrays.sort(sorted, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(),
                (long) a.getWidth() * a.getHeight()));
        StringBuilder row = new StringBuilder("    ");
        int count = 0;
        for (Size s : sorted) {
            long fd = klass != null ? map.getOutputMinFrameDuration(klass, s)
                    : map.getOutputMinFrameDuration(format, s);
            String fps = fd > 0 ? String.format(Locale.US, "%.1f", 1e9 / fd) : "?";
            row.append(String.format(Locale.US, "%-16s", s + "@" + fps));
            if (++count % 4 == 0) {
                sb.append(row).append('\n');
                row = new StringBuilder("    ");
            }
        }
        if (count % 4 != 0) sb.append(row).append('\n');
    }
}
