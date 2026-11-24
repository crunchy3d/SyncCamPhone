package com.crunchy3d.synccamphone;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.util.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The lenses of this phone, found automatically from the Camera2 characteristics (no per-model
 * table): the main back camera; an ultra-wide / telephoto inside it (logical multi-camera: reached
 * with CONTROL_ZOOM_RATIO, e.g. Pixel 7a ultra-wide at 0.53) or as a separate back camera (some other
 * brands); the front camera. Each lens is checked for what the frame sync needs (manual sensor,
 * REALTIME timestamps, per-frame control); a lens without them is listed with the reason, not used.
 * Lens kinds are the same on all phones: the master sends the kind, every phone uses its own lens of
 * that kind (Pixel 7a and Pixel 4 tests 2026-11-20: main, ultra-wide (zoom 0.53) and front camera all
 * PASS the phase steering test).
 */
final class Lenses {
    private Lenses() {}

    static final int MAIN = 0, WIDE = 1, TELE = 2, FRONT = 3;
    static final String[] KIND_NAMES = {"Main", "Ultra-wide", "Telephoto", "Front"};

    static final class Lens {
        int kind;
        String cameraId;
        float zoom = 1f;          // CONTROL_ZOOM_RATIO on that camera (1 = none)
        float factor = 1f;        // field of view relative to the main lens (shown as "0.5x")
        boolean ok = true;
        String reason = "";
        // zoom lens inside a logical camera: the physical sensor that must deliver its frames (the
        // driver may refuse to switch, e.g. Pixel 7a ultra-wide with full-size photo streams)
        String expectPhysical;

        String label() {
            if (kind == FRONT) return "Front";
            return KIND_NAMES[kind] + " " + factorText(factor);
        }

        String shortLabel() {
            return kind == FRONT ? "Front" : factorText(factor);
        }
    }

    static String factorText(float f) {
        if (Math.abs(f - Math.round(f)) < 0.05f) return Math.round(f) + "×";
        return String.format(Locale.US, "%.1f×", Math.floor(f * 10 + 0.25) / 10);
    }

    private static float focal(CameraCharacteristics c) {
        float[] f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        return f != null && f.length > 0 ? f[0] : Float.NaN;
    }

    /** Focal length as 35 mm equivalent (sensor diagonal), for comparing different sensors. */
    private static float equiv(CameraCharacteristics c) {
        float f = focal(c);
        android.util.SizeF s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (Float.isNaN(f) || s == null) return f;
        double diag = Math.hypot(s.getWidth(), s.getHeight());
        return (float) (f * 43.27 / diag);
    }

    private static String syncProblem(CameraCharacteristics c) {
        List<String> p = new ArrayList<>();
        if (!CameraInfo.has(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) p.add("no manual sensor");
        Integer ts = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        if (ts == null || ts != CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) p.add("timestamps not REALTIME");
        Integer lat = c.get(CameraCharacteristics.SYNC_MAX_LATENCY);
        if (lat == null || lat == CameraMetadata.SYNC_MAX_LATENCY_UNKNOWN) p.add("frame timing latency unknown");
        return String.join(", ", p);
    }

    static List<Lens> detect(CameraManager cm) {
        List<Lens> out = new ArrayList<>();
        try {
            String mainId = null, frontId = null;
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (facing == CameraMetadata.LENS_FACING_BACK && mainId == null) mainId = id;
                if (facing == CameraMetadata.LENS_FACING_FRONT && frontId == null) frontId = id;
            }
            if (mainId != null) {
                CameraCharacteristics mc = cm.getCameraCharacteristics(mainId);
                Lens main = lens(MAIN, mainId, 1f, 1f, mc);
                out.add(main);
                float mainEq = equiv(mc);
                Range<Float> zr = android.os.Build.VERSION.SDK_INT >= 30
                        ? mc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
                boolean haveWide = false, haveTele = false;
                // lenses inside the main (logical) camera, reached with the zoom ratio
                if (zr != null && zr.getLower() < 0.95f) {
                    Lens w = lens(WIDE, mainId, zr.getLower(), zr.getLower(), mc);
                    if (android.os.Build.VERSION.SDK_INT >= 28) {      // the shortest focal length
                        float mf = Float.MAX_VALUE;
                        for (String pid : mc.getPhysicalCameraIds()) {
                            float f = focal(cm.getCameraCharacteristics(pid));
                            if (!Float.isNaN(f) && f < mf) {
                                mf = f;
                                w.expectPhysical = pid;
                            }
                        }
                    }
                    out.add(w);
                    haveWide = true;
                }
                if (android.os.Build.VERSION.SDK_INT >= 28 && zr != null) {
                    Set<String> phys = mc.getPhysicalCameraIds();
                    float best = 0;
                    String bestId = null;
                    float mainF = focal(mc);
                    for (String pid : phys) {
                        CameraCharacteristics pc = cm.getCameraCharacteristics(pid);
                        // a real telephoto has its own focal length; the same focal length with a
                        // smaller sensor area is the main sensor's crop mode (Pixel 7a camera 4: 2x)
                        if (Math.abs(focal(pc) - mainF) < 0.1f * mainF) continue;
                        float r = equiv(pc) / mainEq;                                  // 35 mm equivalent
                        if (!Float.isNaN(r) && r > 1.4f && r > best) {
                            best = r;
                            bestId = pid;
                        }
                    }
                    if (best > 0) {
                        float z = Math.min(zr.getUpper(), best);
                        Lens tl = lens(TELE, mainId, z, z, mc);
                        tl.expectPhysical = bestId;
                        out.add(tl);
                        haveTele = true;
                    }
                }
                // other brands: each lens a separate back camera
                for (String id : cm.getCameraIdList()) {
                    if (id.equals(mainId)) continue;
                    CameraCharacteristics c = cm.getCameraCharacteristics(id);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (facing == null || facing != CameraMetadata.LENS_FACING_BACK) continue;
                    float r = equiv(c) / mainEq;
                    if (Float.isNaN(r)) continue;
                    if (r < 0.8f && !haveWide) {
                        out.add(lens(WIDE, id, 1f, r, c));
                        haveWide = true;
                    } else if (r > 1.4f && !haveTele) {
                        out.add(lens(TELE, id, 1f, r, c));
                        haveTele = true;
                    }
                }
            }
            if (frontId != null) out.add(lens(FRONT, frontId, 1f, 1f, cm.getCameraCharacteristics(frontId)));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Lens lens(int kind, String id, float zoom, float factor, CameraCharacteristics c) {
        Lens l = new Lens();
        l.kind = kind;
        l.cameraId = id;
        l.zoom = zoom;
        l.factor = factor;
        l.reason = syncProblem(c);
        l.ok = l.reason.isEmpty();
        return l;
    }

    /** This phone's usable lens of that kind, or null. */
    static Lens find(List<Lens> list, int kind) {
        for (Lens l : list) if (l.kind == kind && l.ok) return l;
        return null;
    }

    /** The usable lenses (order: main, ultra-wide, telephoto, front). */
    static List<Lens> usable(List<Lens> list) {
        List<Lens> u = new ArrayList<>();
        for (int k = 0; k < KIND_NAMES.length; k++) {
            Lens l = find(list, k);
            if (l != null) u.add(l);
        }
        return u;
    }
}
