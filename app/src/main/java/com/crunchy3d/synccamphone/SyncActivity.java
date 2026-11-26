package com.crunchy3d.synccamphone;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Master / camera screen of the sync system. */
public class SyncActivity extends Activity {
    static final String EXTRA_ROLE = "role";
    static final String EXTRA_CAMERA = "camera";
    static final String EXTRA_SIDE = "side";      // Downloads.SIDE_* (asked at every start)
    static final int ROLE_MASTER_CAMERA = 0, ROLE_CAMERA = 1, ROLE_MASTER_ONLY = 2;

    private static final int GREEN = 0xFF66BB6A, YELLOW = 0xFFFFCA28, RED = 0xFFEF5350, GREY = 0xFF808080;

    private int role;
    private MasterNode master;
    private CameraNode camera;
    private SyncCamera syncCamera;            // local camera (master+camera and camera roles)
    private final PhaseAligner aligner = new PhaseAligner();
    private LedCapture led;                   // LED detection / capture on the local camera
    private int seenCaptureSeq = -1;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView header, table, logView, syncView;

    /** Sync display: green <= 50 us, yellow <= 1 ms, red above or unknown (< 0). */
    // qNs <= -2: MEASURING (#5, 2026-11-25) - the frame phase is paused after a correction step but the clock
    // is fine: yellow with the last measured value (-2 - qNs) instead of a red "…"
    private static void showSync(TextView v, long qNs, String detail) {
        boolean measuring = qNs <= -2;
        if (measuring) qNs = -2 - qNs;
        int color = qNs < 0 ? RED : measuring ? YELLOW : qNs <= 100_000 ? GREEN : qNs <= 1_000_000 ? YELLOW : RED;
        String q = qNs < 0 ? "…" : (qNs < 1_000_000 ? String.format(Locale.US, "±%d µs", Math.round(qNs / 1e3))
                : String.format(Locale.US, "±%.1f ms", qNs / 1e6)) + (measuring ? " (measuring)" : "");
        SpannableStringBuilder sb = new SpannableStringBuilder();
        dot(sb, color);
        int s = sb.length();
        sb.append("SYNC ").append(q);
        sb.setSpan(new ForegroundColorSpan(color), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!detail.isEmpty()) sb.append("  ").append(detail);
        v.setText(sb);
    }

    /** Sync quality of a camera: |phase error| + clock uncertainty (ns), or -1 if unknown. */
    private static long quality(boolean isRef, boolean phaseValid, double phaseUs, double clockErrUs) {
        if (clockErrUs < 0) return -1;
        if (isRef) return Math.round(clockErrUs * 1e3);
        if (!phaseValid) return -1;
        return Math.round((Math.abs(phaseUs) + clockErrUs) * 1e3);
    }

    // last known quality per camera: the phase is unknown for ~0.5 s after each correction step,
    // which only makes the sync better - keep showing the last value for up to 3 s
    private final java.util.Map<String, long[]> lastQuality = new java.util.HashMap<>();

    private long held(String key, long q) {
        return held(key, q, false);
    }

    /** As held(); clockOk: 3..15 s after the last value, -2 - last ("measuring", yellow) instead of -1. */
    private long held(String key, long q, boolean clockOk) {
        long now = SystemClock.elapsedRealtime();
        if (q >= 0) {
            lastQuality.put(key, new long[]{q, now});
            return q;
        }
        long[] l = lastQuality.get(key);
        if (l == null) return -1;
        if (now - l[1] < 3000) return l[0];
        return clockOk && now - l[1] < 15000 ? -2 - l[0] : -1;
    }

    /** The value of a held() result: >= 0, or -1 when unknown ("measuring" counts as its last value). */
    private static long qValue(long q) {
        return q <= -2 ? -2 - q : q;
    }
    private EditText fpsEdit, expEdit, isoEdit, capEdit, asymEdit, videoEdit, soundEdit, distEdit, bitrateEdit;
    private Button startButton, alignButton, stopButton, captureButton, recordButton, modeButton, photoButton;
    private Button wbButton, lockWbButton, focusButton;
    private int wbChoice;
    private EditText kelvinEdit, tintEdit;
    private TextView awbText;
    private android.hardware.camera2.CameraCharacteristics camChars;
    // "auto shared" (default): automatic white balance measured on all phones at AE, combined and
    // locked on all - both phones of a stereo pair get the same colours
    private static final String[] WB_NAMES = {"auto shared", "auto", "K", "daylight", "cloudy", "shade",
            "incandescent", "fluorescent", "copy"};
    private static final int[] WB_PRESETS = {0, 0, 0, android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_SHADE,
            android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, 0};

    private static float metresToDiopters(double m) {
        return m <= 0 || m > 1000 ? 0f : (float) (1 / m);
    }

    private static String focusText(float d) {
        return d > 0.001f ? String.format(Locale.US, "%.2f m", 1 / d) : "infinity";
    }

    private void applyWbChoice() {
        String n = WB_NAMES[wbChoice];
        if (n.equals("auto") || n.equals("auto shared")) {
            master.wbMode = SyncCamera.WB_AUTO;
        } else if (n.equals("K")) {
            applyKelvin();
            return;
        } else if (n.equals("copy")) {
            master.wbMode = SyncCamera.WB_COPY;     // values come with "Lock WB"
        } else {
            master.wbMode = SyncCamera.WB_PRESET;
            master.awbPreset = WB_PRESETS[wbChoice];
        }
        master.lookChanged("WB " + n + (n.equals("copy") && master.wbGains == null ? " (press Lock WB)" : ""));
    }

    private ColorTemp wbTable;                 // K calibration from the camera's presets (master)

    private double estimateK(android.hardware.camera2.params.RggbChannelVector g) {
        return wbTable == null ? Double.NaN : wbTable.estimate(g);
    }

    /** "Calibrate WB": the master camera applies its presets; the table is saved on this phone. */
    private void calibrateWb() {
        calibrateWb(null);
    }

    /** then: run after a successful calibration (the Kelvin slider calibrates by itself the first time). */
    private void calibrateWb(Runnable then) {
        if (syncCamera == null || !syncCamera.isStreaming()) {
            master.log.add("Calibrate WB: start the master camera first");
            showRedNote(syncCamera == null ? "White balance in K needs a camera on the CONTROL phone"
                    : "Calibrate WB: the camera is not running yet");
            return;
        }
        if (wbCalibrating) return;
        wbCalibrating = true;
        master.log.add("Calibrate WB: presets ... (about 3 s)");
        showNote("Calibrating white balance (about 3 s) ...");
        syncCamera.calibrateWb(ColorTemp.PRESETS, (g, x) -> ui.post(() -> {
            wbCalibrating = false;
            ColorTemp t = g == null ? null : ColorTemp.fromPresets(ColorTemp.PRESET_K, g, x);
            if (t == null) {
                master.log.add("Calibrate WB failed");
                showRedNote("White balance calibration failed - try again");
                return;
            }
            wbTable = t;
            getSharedPreferences("wb", MODE_PRIVATE).edit().putString("table", t.serialize()).apply();
            master.log.add("WB calibrated: " + t.describe());
            showNote("White balance calibrated");
            if (then != null) then.run();
        }));
    }

    private boolean wbCalibrating;

    /** White balance from the K and tint fields, from the preset calibration. */
    private void applyKelvin() {
        double k = Math.max(1800, Math.min(12000, parse(kelvinEdit, 5000)));
        double tint = Math.max(-150, Math.min(150, parse(tintEdit, 0)));
        if (wbTable == null) {
            // not calibrated yet (e.g. a new install): calibrate once by itself, then apply the K value
            // (user 2026-11-24: the slider changed nothing - only a line in the log said why)
            calibrateWb(this::applyKelvin);
            return;
        }
        ColorTemp.Result r = wbTable.compute(k, tint);
        if (r == null) {
            showRedNote("White balance " + Math.round(k) + " K: not possible with this calibration");
            return;
        }
        master.wbGains = r.gains;
        master.wbXform = r.xform;
        master.wbMode = SyncCamera.WB_COPY;            // sent like copied values
        master.lookChanged(String.format(Locale.US, "WB %.0f K tint %+.0f (gains R %.2f B %.2f)", k, tint,
                r.gains[0], r.gains[3]));
    }

    /** Copies the master camera's current automatic white balance to all cameras (fixed). */
    private void lockWb() {
        if (syncCamera == null || syncCamera.lastGains == null || syncCamera.lastXform == null) {
            master.log.add("Lock WB: the master camera must run with WB auto first");
            return;
        }
        android.hardware.camera2.params.RggbChannelVector g = syncCamera.lastGains;
        int[] x = new int[18];
        syncCamera.lastXform.copyElements(x, 0);
        master.wbGains = new float[]{g.getRed(), g.getGreenEven(), g.getGreenOdd(), g.getBlue()};
        master.wbXform = x;
        master.wbMode = SyncCamera.WB_COPY;
        wbChoice = WB_NAMES.length - 1;
        double k = estimateK(g);
        if (!Double.isNaN(k)) kelvinEdit.setText(String.format(Locale.US, "%.0f", k));
        master.lookChanged(String.format(Locale.US, "WB locked: gains R %.2f G %.2f B %.2f",
                g.getRed(), g.getGreenEven(), g.getBlue()));
    }
    private int startedRecordId = -1;
    private volatile int deviceDegrees;       // physical rotation (0/90/180/270) for the video flag
    private android.view.OrientationEventListener orientationListener;
    private GraphView graph;
    private boolean graphShowsPhase;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RemoteTrigger.appCtx = getApplicationContext();
        role = getIntent().getIntExtra(EXTRA_ROLE, ROLE_CAMERA);
        Downloads.side = Math.max(0, Math.min(2, getIntent().getIntExtra(EXTRA_SIDE, Downloads.SIDE_MONO)));
        lenses = Lenses.detect(getSystemService(CameraManager.class));
        curLens = Lenses.find(lenses, Lenses.MAIN);
        String cameraId = curLens != null ? curLens.cameraId : "0";
        try {
            camChars = getSystemService(CameraManager.class).getCameraCharacteristics(cameraId);
        } catch (Exception ignored) {
        }
        wbTable = ColorTemp.parse(getSharedPreferences("wb", MODE_PRIVATE).getString("table", null));

        TextureView preview = role == ROLE_CAMERA ? buildCameraUi() : buildMasterUi();
        if (preview != null) {
            syncCamera = new SyncCamera(getSystemService(CameraManager.class), cameraId, preview, this::onFrame);
        }
        try {
            if (role == ROLE_CAMERA) {
                camera = new CameraNode(this);
            // a new photo is started at once (zero-lag burst: the ring holds only ~0.27 s)
            camera.onNewPhoto = () -> ui.post(() -> checkPhoto(camera.photoSeq, camera.photoTime, camera.running));
                camera.aligner = aligner;
                camera.start();
            } else {
                master = new MasterNode(SyncNet.deviceName(this), role == ROLE_MASTER_CAMERA);
                loadPhotoPrefs();
                master.start();
            }
        } catch (Exception e) {
            header.setText("Network error: " + e.getMessage());
        }
        // highest 3D_/2D_ file numbers on this phone: the master continues after the highest of all
        final MasterNode mn0 = master;
        final CameraNode cn0 = camera;
        new Thread(() -> {
            int[] mx = Downloads.maxNumbers(this);
            if (mn0 != null) {
                mn0.ownMaxImg = mx[0];
                mn0.ownMaxVid = mx[1];
            }
            if (cn0 != null) {
                cn0.maxImg = mx[0];
                cn0.maxVid = mx[1];
            }
        }, "scan-numbers").start();
        if (syncCamera != null) {
            LedCapture.Clock clock = camera != null ? camera::toMaster : t -> t;
            led = new LedCapture(this, SyncNet.deviceName(this), clock);
            syncCamera.analyzer = led;
            if (camera != null) camera.led = led;
        }
        orientationListener = new android.view.OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int deg) {
                if (deg == ORIENTATION_UNKNOWN) return;
                deviceDegrees = ((deg + 45) / 90 * 90) % 360;
            }
        };
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        if (master != null) {
            seenCaptureSeq = master.captureSeq;
            seenPhotoSeq = master.photoSeq;
            ui.postDelayed(() -> sendState(true, true), 300);   // cameras start by themselves, Align on
        }
        ui.post(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumedOnce && syncCamera != null) syncCamera.onResume();
        resumedOnce = true;
    }

    private boolean resumedOnce;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
        if (led != null) led.stop();
        if (orientationListener != null) orientationListener.disable();
        if (syncCamera != null) syncCamera.release();
        if (master != null) master.stop();
        if (camera != null) camera.stop();
    }

    // ------------------------------------------------------------------ local camera

    /** Called on the camera thread for every frame. */
    private void onFrame(long timestampNs, String tag) {
        if (master != null) {
            // master's own camera: master time = local time; it is the reference, never steers
            aligner.onFrame(timestampNs, tag, null, false, syncCamera);
        } else if (camera != null) {
            boolean correct = camera.running && camera.align && !camera.isReference();
            aligner.onFrame(camera.toMaster(timestampNs), tag, camera.ref, correct, syncCamera);
        }
    }

    // ------------------------------------------------------------------ lenses (2026-11-20)
    private List<Lenses.Lens> lenses = new ArrayList<>();
    private Lenses.Lens curLens;                     // the lens in use on this phone
    private int ownLens = -1;                        // camera phone: own choice (-1 = follow the master)
    private int seenMasterLens = -1;                 // camera phone: last lens received from the master
    private String lensSig = "";                     // what the mismatch note was last checked for
    // lenses the camera driver did not really switch to, per mode ("1/photo"): Main is used there
    private final java.util.Set<String> lensBlocked = new java.util.HashSet<>();
    private boolean lensPhoto;                       // the mode the lens is chosen for

    /** This phone's lens of that kind, else the main lens (also when it does not work in this mode). */
    private Lenses.Lens lensFor(int kind) {
        Lenses.Lens l = Lenses.find(lenses, kind);
        if (l != null && lensBlocked.contains(kind + (lensPhoto ? "/photo" : "/video"))) l = null;
        return l != null ? l : Lenses.find(lenses, Lenses.MAIN);
    }

    /**
     * Lens check (any phone, no table): ~1 s after a zoom lens started, the frames must come from its
     * own sensor. Pixel 7a: the ultra-wide works in video, but with the full-size photo streams the
     * driver stays on the main sensor (2026-11-20) -> Main is used in that mode, with a note.
     */
    private void checkLensInUse() {
        Lenses.Lens l = curLens;
        if (l == null || l.expectPhysical == null || syncCamera == null || !syncCamera.isStreaming()) return;
        if (syncCamera.lookFrames < 30) return;
        String a = syncCamera.activePhysicalId;
        if (a == null || a.equals(l.expectPhysical)) return;
        String mode = lensPhoto ? "photo" : "video";
        if (lensBlocked.add(l.kind + "/" + mode)) {
            showRedNote(Lenses.KIND_NAMES[l.kind] + " not available in " + mode + " mode on this phone"
                    + " - Main used (" + (lensPhoto ? "video" : "photo") + " mode: try there)");
            if (master != null) master.log.add(Lenses.KIND_NAMES[l.kind] + ": the driver stays on sensor " + a
                    + " in " + mode + " mode - Main used");
        }
    }

    private String lensName(int kind) {
        return kind >= 0 && kind < Lenses.KIND_NAMES.length ? Lenses.KIND_NAMES[kind] : "?";
    }

    /** The lens this phone should use now: master = its choice; camera = own choice or the master's. */
    private Lenses.Lens wantedLens() {
        if (master != null) return lensFor(master.lens);
        if (camera == null) return curLens;
        if (camera.lens != seenMasterLens) {             // the master set a lens: copied, own choice reset
            seenMasterLens = camera.lens;
            ownLens = -1;
        }
        return lensFor(ownLens >= 0 ? ownLens : camera.lens);
    }

    /** Next usable lens of this phone after the given kind. */
    private int nextLens(int kind) {
        List<Lenses.Lens> u = Lenses.usable(lenses);
        if (u.isEmpty()) return Lenses.MAIN;
        for (int i = 0; i < u.size(); i++) if (u.get(i).kind == kind) return u.get((i + 1) % u.size()).kind;
        return u.get(0).kind;
    }

    private void setMasterLens(int kind) {
        if (master == null || kind == master.lens) return;
        master.lens = kind;
        master.lookChanged("lens " + lensFor(kind).label() + " (copied to all cameras)");
    }

    /** Red note for a few seconds after a lens change when a camera's lens differs from the master's. */
    private void checkLensMismatch() {
        if (master != null) {
            int ml = lensFor(master.lens).kind;          // the lens really used (Main where it does not work)
            StringBuilder sig = new StringBuilder().append(ml);
            StringBuilder diff = new StringBuilder();
            for (MasterNode.ClientInfo c : master.clients.values()) {
                if (c.lost || c.lens < 0) continue;
                sig.append(',').append(c.id).append('=').append(c.lens);
                if (c.lens != ml) {
                    diff.append(diff.length() > 0 ? "\n" : "").append(c.name.replace("Pixel 7a ", "")).append(": ")
                            .append(lensName(c.lens)).append(" (CONTROL: ").append(lensName(ml)).append(')');
                }
            }
            String s = sig.toString();
            if (!s.equals(lensSig)) {
                lensSig = s;
                if (diff.length() > 0) showRedNote("Different lens\n" + diff);
            }
        } else if (camera != null && curLens != null) {
            int ml = lensFor(camera.lens).kind;          // the master's lens as it works on this phone
            String s = ml + "/" + curLens.kind;
            if (!s.equals(lensSig)) {
                lensSig = s;
                if (curLens.kind != ml) {
                    showRedNote("Lens " + lensName(curLens.kind) + " - the CONTROL phone uses " + lensName(camera.lens)
                            + (Lenses.find(lenses, camera.lens) == null ? " (not on this phone)" : ""));
                }
            }
        }
    }

    /** A yellow note for 6 s (e.g. not enough light after AE). */
    private void showWarnNote(String msg) {
        showNote(msg);
        if (noteView == null) return;
        noteView.setBackground(rounded(0xE0B07800, 0));
        ui.removeCallbacks(hideNote);
        ui.postDelayed(hideNote, 6000);
    }

    private void showRedNote(String msg) {
        showNote(msg);
        if (noteView == null) return;
        noteView.setBackground(rounded(0xE0C62828, 0));
        ui.removeCallbacks(hideNote);
        ui.postDelayed(hideNote, 4000);
    }

    /** Starts/stops the local camera according to the desired state. */
    private void applyCameraState(boolean running, long periodNs, long exposureNs, int iso, int videoHeight,
                                  boolean photoMode, int photoFormat, boolean appJpeg, int tone) {
        if (syncCamera == null) {
            checkLensMismatch();                         // (master only: no camera of its own)
            return;
        }
        lensPhoto = photoMode;
        checkLensInUse();
        Lenses.Lens lens = wantedLens();
        if (lens != null && lens != curLens) {
            curLens = lens;
            try {
                camChars = getSystemService(CameraManager.class).getCameraCharacteristics(lens.cameraId);
            } catch (Exception ignored) {
            }
        }
        if (camera != null) camera.lensInUse = curLens != null ? curLens.kind : -1;
        checkLensMismatch();
        if (running) {
            SyncCamera.Settings want = new SyncCamera.Settings(periodNs, exposureNs, iso, videoHeight, photoMode,
                    photoFormat, appJpeg, tone);
            if (curLens != null) {
                want.cameraId = curLens.cameraId;
                want.zoom = curLens.zoom;
            }
            want.analysis = ledPending || (led != null && led.isCapturing());
            if (!want.sameSession(syncCamera.activeSettings())) aligner.reset();
            syncCamera.start(want);
        } else if (syncCamera.isStreaming() || !"idle".equals(syncCamera.status())) {
            syncCamera.stop();
            aligner.reset();
        }
    }

    // ------------------------------------------------------------------ UI building

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView mono(float size) {
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(size);
        return t;
    }

    private EditText field(LinearLayout row, String label, String value) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        row.addView(t);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setSingleLine();
        e.setTextSize(14);
        // Enter = done: keyboard closed, editing ends, value saved (not: jump to the next field)
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        e.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                    || (ev != null && ev.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && ev.getAction() == android.view.KeyEvent.ACTION_DOWN);
            if (!enter) return false;
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            // focus to the row itself (a plain clearFocus would hand it to the first field)
            android.view.View host = (android.view.View) v.getParent();
            host.setFocusableInTouchMode(true);
            host.requestFocus();
            savePhotoPrefs();
            applyTyped();
            return true;
        });
        e.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                savePhotoPrefs();
                applyTyped();
            }
        });
        row.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return e;
    }

    /** A typed value was finished (Enter / left the field): the master sends it to all phones. */
    private void applyTyped() {
        if (master != null && master.running && !standby) sendState(true, master.align);
    }

    private Button button(LinearLayout row, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    // ------------------------------------------------------------------ main screen (landscape)
    //
    //  [ preview area: image always fully visible, aspect of the output   ][ right column ]
    //    video: SYNC bar on top + chips at the bottom, over the image          mode, shutter, gear
    //    photo: image at the right, SYNC + chips in the left black column      (camera: info)
    //  The gear opens the settings panel (old controls, grouped in sections).

    private android.widget.FrameLayout rootFrame, previewArea;
    private TextureView previewView;
    private MaskView mask;
    private LinearLayout topBar, leftCol, rightCol, settingsContent;
    private ViewGroup bottomBar;
    private android.widget.ScrollView settingsPanel;
    private TextView recView, shutterLabel, rightInfo, leftSync, videoTab, photoTab;
    private ShutterView shutter;
    private final List<TextView> bottomChips = new ArrayList<>(), leftChips = new ArrayList<>();
    private final java.util.Map<String, android.view.View> sections = new java.util.HashMap<>();
    private String layoutKey = "";

    /** Big round shutter: 0 = record (red dot), 1 = recording (red square), 2 = photo (white). */
    private static final class ShutterView extends android.view.View {
        int mode;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ShutterView(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.92f;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.08f);
            p.setColor(Color.WHITE);
            c.drawCircle(cx, cy, r, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            if (mode == 1) {
                p.setColor(RED);
                float s = r * 0.42f;
                c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * 0.2f, s * 0.2f, p);
            } else {
                p.setColor(mode == 2 ? Color.WHITE : RED);
                c.drawCircle(cx, cy, r * 0.8f, p);
            }
        }
    }

    /** Black bars over the parts of the 4:3 preview that are cropped away (photo aspect). */
    private static final class MaskView extends android.view.View {
        double aspect;                      // 0 = no mask
        private final android.graphics.Paint p = new android.graphics.Paint();

        MaskView(android.content.Context c) {
            super(c);
            p.setColor(Color.BLACK);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            if (aspect <= 0) return;
            float w = getWidth(), h = getHeight();
            if (aspect > w / h) {
                float ch = (float) (w / aspect), m = (h - ch) / 2;
                c.drawRect(0, 0, w, m, p);
                c.drawRect(0, h - m, w, h, p);
            } else {
                float cw = (float) (h * aspect), m = (w - cw) / 2;
                c.drawRect(0, 0, m, h, p);
                c.drawRect(w - m, 0, w, h, p);
            }
        }
    }

    private android.graphics.drawable.GradientDrawable rounded(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);                           // item 23 (2026-11-19): larger, taller chips
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setBackground(rounded(0x66000000, 0xCCFFFFFF));
        return t;
    }

    private void section(LinearLayout root, String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, dp(18), 0, dp(6));
        root.addView(t);
        sections.put(name, t);
    }

    private void openSettings(String sectionName) {
        settingsPanel.setVisibility(android.view.View.VISIBLE);
        if (master != null) {                       // master: the tab that fits ("Photo" / "Video" chip)
            selectTab(sectionName.equals("Video") ? TAB_VIDEO : sectionName.equals("Info") ? TAB_SYSTEM
                    : sectionName.equals("Photo") ? TAB_PHOTO : tabSel);
            return;
        }
        android.view.View s = sections.get(sectionName);
        if (s != null) settingsPanel.post(() -> settingsPanel.scrollTo(0, s.getTop()));
    }

    private int displayRotationDeg() {
        return getWindowManager().getDefaultDisplay().getRotation() * 90;
    }

    /** The frame of both roles: preview area, overlays, right column, settings panel. */
    private void buildFrame() {
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        rootFrame = new android.widget.FrameLayout(this);
        rootFrame.setBackgroundColor(Color.BLACK);
        setContentView(rootFrame);
        row = new LinearLayout(this);
        rootFrame.addView(row, new android.widget.FrameLayout.LayoutParams(-1, -1));

        previewArea = new android.widget.FrameLayout(this);
        previewArea.setOnClickListener(v -> {           // tap beside / on the image: close the menu
            if (settingsPanel != null) settingsPanel.setVisibility(android.view.View.GONE);
        });
        row.addView(previewArea, new LinearLayout.LayoutParams(0, -1, 1f));
        rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.CENTER_HORIZONTAL);
        rightCol.setBackgroundColor(0xFF121212);
        rightCol.setPadding(dp(8), dp(12), dp(8), dp(12));
        row.addView(rightCol, new LinearLayout.LayoutParams(dp(170), -1));

        previewView = new TextureView(this);
        previewArea.addView(previewView, new android.widget.FrameLayout.LayoutParams(1, 1));
        mask = new MaskView(this);
        previewArea.addView(mask, new android.widget.FrameLayout.LayoutParams(1, 1));

        leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setPadding(dp(10), dp(10), dp(10), dp(10));
        leftCol.setVisibility(android.view.View.GONE);
        previewArea.addView(leftCol, new android.widget.FrameLayout.LayoutParams(1, -1));
        leftSync = new TextView(this);
        leftSync.setTextSize(18);
        leftSync.setTypeface(Typeface.DEFAULT_BOLD);
        leftCol.addView(leftSync);

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        topBar.setBackground(rounded(0x99000000, 0));
        android.widget.FrameLayout.LayoutParams tl = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tl.setMargins(dp(8), dp(8), dp(8), 0);
        previewArea.addView(topBar, tl);
        syncView = new TextView(this);
        syncView.setTextSize(20);
        syncView.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(syncView, new LinearLayout.LayoutParams(0, -2, 1f));
        recView = new TextView(this);
        recView.setTextSize(20);
        recView.setTypeface(Typeface.DEFAULT_BOLD);
        recView.setTextColor(RED);
        topBar.addView(recView);

        bottomBar = new FlowLayout(this);
        android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.START);
        bl.setMargins(dp(8), 0, dp(8), dp(8));
        previewArea.addView(bottomBar, bl);

        settingsPanel = new android.widget.ScrollView(this);
        settingsPanel.setBackgroundColor(0xFC121212);
        settingsPanel.setVisibility(android.view.View.GONE);
        android.widget.FrameLayout.LayoutParams sl = new android.widget.FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.62), -1, Gravity.END);
        rootFrame.addView(settingsPanel, sl);
        settingsContent = new LinearLayout(this);
        settingsContent.setOrientation(LinearLayout.VERTICAL);
        settingsContent.setPadding(dp(16), dp(8), dp(16), dp(24));
        settingsPanel.addView(settingsContent);
        LinearLayout closeRow = new LinearLayout(this);
        closeRow.setGravity(Gravity.END);
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(26);
        close.setTextColor(Color.WHITE);
        close.setPadding(dp(16), dp(4), dp(8), dp(4));
        close.setOnClickListener(v -> settingsPanel.setVisibility(android.view.View.GONE));
        closeRow.addView(close);
        settingsContent.addView(closeRow);

        // only when the area's size changed: relayout() sets child layout params, which runs another
        // layout pass -> with an unconditional call this looped endlessly (CPU / battery)
        previewArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            if (r - l != orr - ol || b - t != ob - ot) v.post(() -> relayout(true));
        });
    }

    // ------------------------------------------------------------------ orientation (landscape / portrait)

    private LinearLayout row;
    private LinearLayout tabs;
    private android.view.View gearView;
    private final List<android.view.View> landscapeViews = new ArrayList<>();
    private final List<ViewGroup.LayoutParams> landscapeParams = new ArrayList<>();
    private boolean portrait;
    private LinearLayout shutterBox;                 // portrait: shutter + label

    /** Simple wrapping row: children left to right, continued on the next line when full. */
    private static final class FlowLayout extends ViewGroup {
        FlowLayout(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int maxW = MeasureSpec.getSize(ws), x = 0, y = 0, lineH = 0, used = 0;
            for (int i = 0; i < getChildCount(); i++) {
                android.view.View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), hs);
                int cw = c.getMeasuredWidth() + 12, ch = c.getMeasuredHeight() + 12;
                if (x + cw > maxW && x > 0) {
                    x = 0;
                    y += lineH;
                    lineH = 0;
                }
                x += cw;
                used = Math.max(used, x);
                lineH = Math.max(lineH, ch);
            }
            setMeasuredDimension(MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY ? maxW : used, y + lineH);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l, x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                android.view.View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                int cw = c.getMeasuredWidth() + 12, ch = c.getMeasuredHeight() + 12;
                if (x + cw > maxW && x > 0) {
                    x = 0;
                    y += lineH;
                    lineH = 0;
                }
                c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
                x += cw;
                lineH = Math.max(lineH, ch);
            }
        }
    }

    private void rememberLandscapeColumn() {
        landscapeViews.clear();
        landscapeParams.clear();
        for (int i = 0; i < rightCol.getChildCount(); i++) {
            landscapeViews.add(rightCol.getChildAt(i));
            landscapeParams.add(rightCol.getChildAt(i).getLayoutParams());
        }
        arrange(getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        arrange(c.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT);
    }

    /**
     * Landscape: controls in a column at the right. Portrait: the same controls in a bar at the
     * bottom (mode switch, shutter, last shot, gear) - only the views are moved, nothing restarts.
     */
    private void arrange(boolean p) {
        portrait = p;
        rightCol.removeAllViews();
        if (shutterBox != null) shutterBox.removeAllViews();
        if (shutterLabel != null) shutterLabel.setTextSize(p ? 13 : 16);
        row.setOrientation(p ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        previewArea.setLayoutParams(p ? new LinearLayout.LayoutParams(-1, 0, 1f) : new LinearLayout.LayoutParams(0, -1, 1f));
        rightCol.setLayoutParams(p ? new LinearLayout.LayoutParams(-1, dp(130)) : new LinearLayout.LayoutParams(dp(170), -1));
        rightCol.setOrientation(p ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        rightCol.setGravity(p ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        if (!p) {
            for (int i = 0; i < landscapeViews.size(); i++) rightCol.addView(landscapeViews.get(i), landscapeParams.get(i));
        } else if (role != ROLE_CAMERA) {
            // the mode switch takes the width that is left: fixed widths (404 dp) pushed the gear off
            // narrower screens (Beam Pro 360 dp, 2026-11-24); shutter / thumb / gear keep their size
            rightCol.addView(tabs, new LinearLayout.LayoutParams(0, -2, 1f));
            // the shutter with its label below ("PHOTO #0143"; it was missing in portrait)
            if (shutterBox == null) {
                shutterBox = new LinearLayout(this);
                shutterBox.setOrientation(LinearLayout.VERTICAL);
                shutterBox.setGravity(Gravity.CENTER_HORIZONTAL);
            }
            shutterBox.addView(shutter, new LinearLayout.LayoutParams(dp(84), dp(84)));
            if (shutterLabel != null) shutterBox.addView(shutterLabel, new LinearLayout.LayoutParams(-2, -2));
            rightCol.addView(shutterBox, new LinearLayout.LayoutParams(dp(100), -2));
            rightCol.addView(thumb, new LinearLayout.LayoutParams(dp(64), dp(48)));
            rightCol.addView(gearView, new LinearLayout.LayoutParams(dp(56), -2));
        } else {
            rightCol.addView(rightInfo, new LinearLayout.LayoutParams(0, -2, 1f));
            rightCol.addView(thumb, new LinearLayout.LayoutParams(dp(90), dp(68)));
            rightCol.addView(gearView, new LinearLayout.LayoutParams(dp(64), -2));
        }
        android.widget.FrameLayout.LayoutParams sl = (android.widget.FrameLayout.LayoutParams) settingsPanel.getLayoutParams();
        sl.width = (int) (getResources().getDisplayMetrics().widthPixels * (p ? 0.96 : 0.62));
        settingsPanel.setLayoutParams(sl);
        relayout(true);
    }

    private TextView rightLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(GREY);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        rightCol.addView(t);
        return t;
    }

    private TextView gearButton() {
        TextView g = new TextView(this);
        g.setText("⚙");
        g.setTextSize(40);
        g.setTextColor(Color.WHITE);
        g.setGravity(Gravity.CENTER);
        g.setOnClickListener(v -> {
            boolean open = settingsPanel.getVisibility() != android.view.View.VISIBLE;
            settingsPanel.setVisibility(open ? android.view.View.VISIBLE : android.view.View.GONE);
            if (open) refreshSettings();
        });
        rightCol.addView(g);
        gearView = g;
        return g;
    }

    // STANDBY button left of the image (master only; user 2026-11-17): all cameras off at once
    private TextView standbyBtn;

    private void addStandbyButton() {
        standbyBtn = new TextView(this);
        standbyBtn.setGravity(Gravity.CENTER);
        standbyBtn.setTextColor(0xFFE6E6E6);
        standbyBtn.setBackground(rounded(0x66000000, 0xFFE6E6E6));
        standbyBtn.setOnClickListener(v -> {
            boolean busy = master.record || aeRunning || master.photoBusy()
                    || (syncCamera != null && syncCamera.burstBusy()) || ledPending || (led != null && led.isCapturing());
            if (busy) {
                showNote("Busy (recording / photo / AE) - standby later");
                return;
            }
            enterStandby();
        });
        previewArea.addView(standbyBtn, new android.widget.FrameLayout.LayoutParams(1, 1));
    }

    /** Places the STANDBY button in the strip left of the image (icon only when the strip is narrow). */
    private void placeStandbyButton(int imgX, int imgY, int imgH) {
        if (standbyBtn == null) return;
        boolean wide = imgX >= dp(100);
        int w = wide ? Math.min(imgX - dp(20), dp(110)) : dp(52), h = wide ? dp(86) : dp(52);
        standbyBtn.setText(wide ? "\u263E\nSTANDBY" : "\u263E");
        standbyBtn.setTextSize(wide ? 15 : 22);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(w, h);
        lp.leftMargin = wide ? (imgX - w) / 2 : dp(8);
        lp.topMargin = imgY + (imgH - h) / 2;
        standbyBtn.setLayoutParams(lp);
        standbyBtn.setVisibility(standby || master == null || !master.running ? android.view.View.GONE
                : android.view.View.VISIBLE);
        standbyBtn.bringToFront();
    }

    private TextureView buildMasterUi() {
        buildFrame();
        addStandbyButton();
        // right column: mode switch, shutter, gear
        rightLabel("CONTROL");
        tabs = new LinearLayout(this);
        tabs.setBackground(rounded(0xFF3C3C3C, 0));
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.setMargins(0, dp(10), 0, dp(10));
        rightCol.addView(tabs, tlp);
        videoTab = tab(tabs, "VIDEO");
        photoTab = tab(tabs, "PHOTO");
        videoTab.setOnClickListener(v -> {
            master.setPhotoMode(false);
            savePhotoPrefs();
        });
        photoTab.setOnClickListener(v -> {
            master.setPhotoMode(true);
            savePhotoPrefs();
        });
        rightCol.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        shutter = new ShutterView(this);
        rightCol.addView(shutter, new LinearLayout.LayoutParams(dp(110), dp(110)));
        shutter.setOnClickListener(v -> onShutter());
        // the photo moment of an HDR burst is the touch-down: the frames before it have no touch shake
        shutter.setOnTouchListener((v, e) -> {
            boolean burstMode = master != null && master.photoMode && master.effFormat() == 3;
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                long ageNs = (android.os.SystemClock.uptimeMillis() - e.getEventTime()) * 1_000_000L;
                shutterDownNs = android.os.SystemClock.elapsedRealtimeNanos() - Math.max(0, ageNs);
                // HDR burst fires at once on touch-down (the ring holds only ~0.27 s of frames)
                if (burstMode) onShutter();
            }
            return burstMode;                                 // burst: no second shot on release
        });
        shutterLabel = rightLabel("REC");
        shutterLabel.setTextColor(Color.WHITE);
        shutterLabel.setTextSize(16);
        thumb = new android.widget.ImageView(this);
        thumb.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        thumb.setOnClickListener(v -> openViewer());
        LinearLayout.LayoutParams thl = new LinearLayout.LayoutParams(dp(120), dp(68));
        thl.setMargins(0, dp(10), 0, 0);
        rightCol.addView(thumb, thl);
        rightCol.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        gearButton();

        buildMasterSettings(settingsContent);
        rememberLandscapeColumn();

        if (role == ROLE_MASTER_CAMERA) {
            // tap to focus: the master focuses there and sends its focus distance to all cameras
            previewView.setOnTouchListener((v, e) -> {
                if (settingsPanel.getVisibility() == android.view.View.VISIBLE) {   // tap on the image: close the menu
                    if (e.getAction() == android.view.MotionEvent.ACTION_UP) settingsPanel.setVisibility(android.view.View.GONE);
                    return true;
                }
                if (e.getAction() != android.view.MotionEvent.ACTION_UP || syncCamera == null) return true;
                float u = e.getX() / previewView.getWidth(), w = e.getY() / previewView.getHeight();
                master.log.add(String.format(Locale.US, "tap focus at %.2f, %.2f ...", u, w));
                showFocusSquare(e.getX(), e.getY(), 0);
                syncCamera.tapFocus(u, w, displayRotationDeg(), (d, sharp) -> ui.post(() -> {
                    buzz(3);                                  // focus set: short click
                    showFocusSquare(Float.NaN, Float.NaN, sharp ? 1 : 2);
                    master.focusManual = true;
                    master.focusDiopters = d;
                    distEdit.setText(d > 0.001f ? String.format(Locale.US, "%.2f", 1 / d) : "inf");
                    master.lookChanged(String.format(Locale.US, "tap focus: %s%s", focusText(d),
                            sharp ? "" : " (not sharp?)"));
                }));
                return true;
            });
            return previewView;
        }
        // CONTROL only (no camera): there is no image here - say where to tap (item 38)
        previewView.setOnTouchListener((v, e) -> {
            if (e.getAction() != android.view.MotionEvent.ACTION_UP) return true;
            if (settingsPanel.getVisibility() == android.view.View.VISIBLE) {
                settingsPanel.setVisibility(android.view.View.GONE);
                return true;
            }
            showNote("Focus: tap the image on a camera phone - its distance is used by all phones "
                    + "(or the Focus chip here: slider / auto).");
            return true;
        });
        return null;
    }

    // FOCUS SQUARE: where the tap focused (the camera's focus area: 10 % of the image width), white
    // while focusing, green when sharp, yellow when not sure; gone ~1 s after (at most 4 s)
    private android.view.View focusSquare;
    private final Runnable hideFocusSquare = () -> {
        if (focusSquare != null) focusSquare.setVisibility(android.view.View.GONE);
    };

    /** state 0 = focusing (x, y = tap in the preview view), 1 = sharp, 2 = not sharp (x, y unused). */
    private void showFocusSquare(float x, float y, int state) {
        if (previewArea == null || previewView == null) return;
        if (focusSquare == null) {
            focusSquare = new android.view.View(this);
            previewArea.addView(focusSquare, new android.widget.FrameLayout.LayoutParams(1, 1));
        }
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(4));
        g.setStroke(dp(2), state == 1 ? 0xFF40E040 : state == 2 ? 0xFFFFC000 : Color.WHITE);
        focusSquare.setBackground(g);
        if (state == 0) {
            int side = Math.max(dp(48), Math.round(0.10f * Math.max(previewView.getWidth(), previewView.getHeight())));
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(side, side);
            lp.leftMargin = Math.round(previewView.getLeft() + x - side / 2f);
            lp.topMargin = Math.round(previewView.getTop() + y - side / 2f);
            focusSquare.setLayoutParams(lp);
        }
        focusSquare.setVisibility(android.view.View.VISIBLE);
        focusSquare.bringToFront();
        ui.removeCallbacks(hideFocusSquare);
        ui.postDelayed(hideFocusSquare, state == 0 ? 4000 : 1000);
    }

    private TextView tab(LinearLayout tabs, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        tabs.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        return t;
    }

    // astro series running (master only role: estimated end of the series)
    private long astroUntilMs;

    private boolean astroRunning() {
        if (syncCamera != null) return syncCamera.astroBusy();
        return SystemClock.elapsedRealtime() < astroUntilMs;
    }

    private boolean astroSelected() {
        return master.photoMode && master.photoFormat == 3 && master.hdrMode == PhotoBurst.MODE_ASTRO;
    }

    private void onShutter() {
        if (master.photoMode && tlOn && !astroSelected()) {   // time-lapse: start / stop
            if (tlRunning) stopTimelapse("stopped by the user");
            else startTimelapse();
            return;
        }
        if (master.photoMode && astroRunning()) {             // STOP ASTRO
            master.astroStopSeq++;
            astroUntilMs = 0;
            if (syncCamera != null) syncCamera.stopAstro();
            master.log.add("astro series stopped by the user");
            return;
        }
        if (master.photoMode && astroSelected() && syncCamera == null) {
            astroUntilMs = SystemClock.elapsedRealtime() + 2000 + 2500 + astroSeconds * 1000L + 30_000;
        }
        if (master.photoMode) {
            if (master.running) {
                // HDR burst: every phone must have saved the previous one (else phones skip
                // different bursts and the pairs do not match)
                if (!photoReady()) {
                    android.widget.Toast.makeText(this, "Saving the previous burst - wait a moment",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                takeOnePhoto(shutterDownNs > 0 ? shutterDownNs : android.os.SystemClock.elapsedRealtimeNanos());
            }
            return;
        }
        if (master.record) grabThumb();                       // last image of the recording
        if (!master.running && !master.record) {
            master.log.add("record: the cameras are not running");
            return;
        }
        master.soundOffsetNs = parseAuto(soundEdit, 1e6);
        master.setRecord(!master.record);
    }

    /** An HDR burst needs every phone to have saved the previous one (else the pairs do not match). */
    private boolean photoReady() {
        return !(master.effFormat() == 3 && (master.photoBusy() || (syncCamera != null && syncCamera.burstBusy())));
    }

    /** One synchronised photo on all phones (shutter, time-lapse). */
    private void takeOnePhoto(long touchNs) {
        if (master.effFormat() == 3) {
            master.hdrPlan = computeHdrPlan();
            applyHdr(master.effHdrMode(), master.brightness, master.natural, master.hdrPlan);
        }
        master.takePhoto(touchNs);
        checkPhoto(master.photoSeq, master.photoTime, master.running);   // own camera: at once
    }

    // ------------------------------------------------------------------ time-lapse (2026-11-21)
    // Interval + number of frames (0 = until stopped); focus and white balance locked at the start on all
    // phones; AE locked at the start, or before each shot (~3 s ahead) with at most 1/3 EV change per shot
    // (sunrise without frame-to-frame flicker). Every sequence in its own folder Timelapse_NNNN.
    private EditText tlIntervalEdit, tlFramesEdit;
    private boolean tlOn;                            // setting: the shutter starts a time-lapse
    private int tlAeMode;                            // 0 = AE locked at the start, 1 = before each shot
    private boolean tlRunning, tlAeStarted, tlAeShot, tlFocusWas;
    private int tlCount, tlFrames;
    private long tlNextMs, tlIntervalMs;
    private double tlLight;                          // exposure ms x ISO of the last shot
    static final double TL_MAX_STEP = Math.pow(2, 1 / 3.0);

    private void buildTimelapseUi(LinearLayout photo) {
        subhead(photo, "Time-lapse");
        seg(photo, "Time-lapse", new String[]{"Off", "On"}, () -> tlOn ? 1 : 0, k -> {
            tlOn = k == 1;
            if (!tlOn && tlRunning) stopTimelapse("switched off");
            savePhotoPrefs();
        });
        LinearLayout r = fieldRow(photo);
        tlIntervalEdit = field(r, "Interval s", "10");
        tlFramesEdit = field(r, " frames (0 = until stopped)", "100");
        seg(photo, "Time-lapse AE", new String[]{"locked at start", "each shot (smoothed)"}, () -> tlAeMode, k -> {
            tlAeMode = k;
            savePhotoPrefs();
        });
        hint(photo, "On: the shutter starts / stops the series (same format as single photos, not Astro). Focus and "
                + "white balance are locked at the start. Each shot: AE ~3 s before every photo, at most 1/3 EV "
                + "change per shot (e.g. a sunrise without flicker). Each series in its own folder Timelapse_NNNN.");
    }

    private void startTimelapse() {
        if (tlClearAtSeq > 0) {
            showNote("Time-lapse: the last photos of the previous series are still being saved");
            return;
        }
        if (!master.running) {
            showNote("Time-lapse: the cameras are not running");
            return;
        }
        tlIntervalMs = Math.round(Math.max(1, parse(tlIntervalEdit, 10)) * 1000);
        tlFrames = (int) Math.max(0, parse(tlFramesEdit, 100));
        long minMs = master.effFormat() == 3 ? 8000 : 2000;
        String warn = tlIntervalMs < minMs ? String.format(Locale.US, "\ninterval short for %s: shots may be skipped (>= %d s)",
                master.effFormat() == 3 ? "HDR" : "this format", minMs / 1000) : "";
        // focus locked: the master's measured distance on all phones (the same focus in both pictures)
        tlFocusWas = master.focusManual;
        if (!master.focusManual && syncCamera != null && syncCamera.lastFocusDiopters >= 0) {
            master.focusManual = true;
            master.focusDiopters = syncCamera.lastFocusDiopters;
        }
        // white balance locked (shared WB is locked after AE already; presets / K stay as they are)
        if (WB_NAMES[wbChoice].equals("auto")) lockWb();
        tlClearAtSeq = 0;
        master.timelapse = master.nextPhotoNumber();
        Downloads.sub = String.format(Locale.US, "Timelapse_%04d/", master.timelapse);
        tlRunning = true;
        tlCount = 0;
        tlNextMs = SystemClock.elapsedRealtime() + 1000;
        tlAeStarted = false;
        tlLight = master.exposureNs / 1e6 * master.iso;
        master.lookChanged(String.format(Locale.US, "time-lapse started: every %.1f s, %s frames, AE %s, folder %s",
                tlIntervalMs / 1000.0, tlFrames == 0 ? "until stopped" : String.valueOf(tlFrames),
                tlAeMode == 1 ? "each shot" : "locked", Downloads.sub));
        showNote("Time-lapse started" + warn);
    }

    private int tlClearAtSeq;                        // folder kept until every phone saved this photo
    private long tlClearMs;

    private void stopTimelapse(String why) {
        if (!tlRunning) return;
        tlRunning = false;
        master.focusManual = tlFocusWas;
        // the folder stays until every phone has saved the last photo (else it lands outside: 2026-11-21)
        tlClearAtSeq = tlCount > 0 ? master.photoSeq : 0;
        tlClearMs = SystemClock.elapsedRealtime();
        if (tlClearAtSeq == 0) clearTimelapseFolder();
        master.lookChanged("time-lapse " + why + " after " + tlCount + " photos");
        showNote("Time-lapse " + why + ": " + tlCount + " photos");
    }

    private void clearTimelapseFolder() {
        tlClearAtSeq = 0;
        master.timelapse = 0;
        Downloads.sub = "";
        master.lookChanged("time-lapse folder closed");
    }

    /** Master update loop: AE ahead of the shot, then the shot when the phones are ready. */
    private void timelapseTick() {
        // (the master's own camera too: lastPhotoSeq is set after its files are written - the Beam Pro's
        // in-app JPEG took long enough that its last photo landed outside the folder, 2026-11-21)
        if (!tlRunning && tlClearAtSeq > 0 && ((master.photoSavedEverywhere(tlClearAtSeq)
                && (syncCamera == null || syncCamera.lastPhotoSeq >= tlClearAtSeq))
                || SystemClock.elapsedRealtime() - tlClearMs > 60_000)) {
            clearTimelapseFolder();
        }
        if (!tlRunning) return;
        if (!master.running || !master.photoMode || astroSelected()) {
            stopTimelapse("stopped (cameras / mode changed)");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        lastTouchMs = now;                               // no standby during a time-lapse
        if (tlAeMode == 1 && tlCount > 0 && !tlAeStarted && now >= tlNextMs - 3000 && !aeRunning) {
            tlAeStarted = true;
            tlAeShot = true;
            autoExposure();
        }
        if (now < tlNextMs || aeRunning || !photoReady()) return;
        takeOnePhoto(SystemClock.elapsedRealtimeNanos());
        tlCount++;
        tlAeStarted = false;
        // the next shot on the planned grid (a busy phone delays a shot, it does not shift the series)
        while (tlNextMs <= now) tlNextMs += tlIntervalMs;
        if (tlFrames > 0 && tlCount >= tlFrames) stopTimelapse("finished");
    }

    /**
     * Measured defaults of this phone model: {Wi-Fi asym us, sound offset ms} (Pico LED + buzzer
     * tests, 2026-11-14). Pixel 7a: 310 / -85, Pixel 4: 340 / -62; other models: the 7a values.
     */
    /** Empty field = MasterNode.AUTO (each camera its own model value), else value * scale. */
    private static long parseAuto(EditText e, double scale) {
        String s = e.getText().toString().trim();
        if (s.isEmpty()) return MasterNode.AUTO;
        try {
            return Math.round(Double.parseDouble(s) * scale);
        } catch (NumberFormatException x) {
            return MasterNode.AUTO;
        }
    }

    /** The sound offset to use on this phone (AUTO -> this model's measured value). */
    private static long soundNs(long v) {
        return v == MasterNode.AUTO ? modelDefaults()[1] * 1_000_000L : v;
    }

    static int[] modelDefaults() {
        String m = android.os.Build.MODEL;
        if (m != null && m.matches("Pixel 4( XL)?")) return new int[]{340, -62};
        return new int[]{310, -85};
    }

    // ------------------------------------------------------------------ settings panel (master): tabs
    // (clean-up 2026-11-17: one long list with tap-to-cycle buttons -> tabs, every option visible as a
    // segmented row, the selected one highlighted; values and their meaning unchanged)
    private static final String[] TABS = {"PHOTO", "HDR & ASTRO", "VIDEO", "WB · TONE · FOCUS", "SYSTEM"};
    private static final int TAB_PHOTO = 0, TAB_VIDEO = 2, TAB_SYSTEM = 4;
    private final LinearLayout[] tabPages = new LinearLayout[TABS.length];
    private final TextView[] tabHeads = new TextView[TABS.length];
    private int tabSel;
    private final List<Runnable> segRefresh = new ArrayList<>();
    private LinearLayout hdrModeRow, singleRow, kelvinRow;

    private void selectTab(int t) {
        tabSel = t;
        for (int i = 0; i < TABS.length; i++) {
            if (tabPages[i] != null) tabPages[i].setVisibility(i == t ? android.view.View.VISIBLE : android.view.View.GONE);
            if (tabHeads[i] != null) {
                tabHeads[i].setBackground(rounded(i == t ? 0xFFE6E6E6 : 0x00000000, i == t ? 0 : 0xFF606060));
                tabHeads[i].setTextColor(i == t ? 0xFF141414 : Color.WHITE);
            }
        }
        if (settingsPanel != null) settingsPanel.scrollTo(0, 0);
        refreshSettings();
    }

    /** All segmented rows + the rows that only apply to some choices (master). */
    private void refreshSettings() {
        if (master == null) return;
        for (Runnable r : segRefresh) r.run();
        if (hdrModeRow != null) hdrModeRow.setVisibility(formatTile() == 3 ? android.view.View.VISIBLE : android.view.View.GONE);
        if (singleRow != null) singleRow.setVisibility(formatTile() < 3 ? android.view.View.VISIBLE : android.view.View.GONE);
        if (kelvinRow != null) kelvinRow.setVisibility(WB_NAMES[wbChoice].equals("K") ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /**
     * A row "label   [opt] [opt] ..." - all options visible, the selected one (sel) highlighted;
     * a tap calls pick(index) and refreshes all rows.
     */
    private LinearLayout seg(LinearLayout page, String label, String[] opts, java.util.function.IntSupplier sel,
                             java.util.function.IntConsumer pick) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(0xFFD0D0D0);
        row.addView(l, new LinearLayout.LayoutParams(dp(128), -2));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout opt = new LinearLayout(this);
        hs.addView(opt);
        row.addView(hs, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView[] b = new TextView[opts.length];
        for (int i = 0; i < opts.length; i++) {
            TextView t = new TextView(this);
            t.setText(opts[i]);
            t.setTextSize(13);
            t.setSingleLine();
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(12), dp(7), dp(12), dp(7));
            final int k = i;
            t.setOnClickListener(v -> {
                if (master == null) return;
                pick.accept(k);
                refreshSettings();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(6), 0);
            opt.addView(t, lp);
            b[i] = t;
        }
        page.addView(row);
        segRefresh.add(() -> {
            int s = sel.getAsInt();
            for (int i = 0; i < b.length; i++) {
                b[i].setBackground(rounded(i == s ? 0xFFE6E6E6 : 0xFF2C2C2C, 0));
                b[i].setTextColor(i == s ? 0xFF141414 : Color.WHITE);
            }
        });
        return row;
    }

    /** One short grey explanation line. */
    private void hint(LinearLayout page, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(0xFF9A9A9A);
        t.setPadding(dp(128), 0, 0, dp(6));
        page.addView(t);
    }

    private void subhead(LinearLayout page, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, dp(14), 0, dp(4));
        page.addView(t);
    }

    private LinearLayout fieldRow(LinearLayout page) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(r);
        return r;
    }

    /**
     * Edit-field rows like the segmented rows: the first label in the label column, labels in the
     * same size / colour, the fields and buttons only as wide as needed (not across the panel).
     */
    private void styleFieldRows(LinearLayout page) {
        for (int i = 0; i < page.getChildCount(); i++) {
            if (!(page.getChildAt(i) instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) page.getChildAt(i);
            boolean hasField = false;
            for (int j = 0; j < row.getChildCount(); j++) hasField |= row.getChildAt(j) instanceof EditText;
            if (!hasField) continue;
            row.setPadding(0, dp(2), 0, dp(2));
            for (int j = 0; j < row.getChildCount(); j++) {
                android.view.View c = row.getChildAt(j);
                if (c instanceof EditText) {
                    c.setLayoutParams(new LinearLayout.LayoutParams(dp(96), -2));
                } else if (c instanceof Button) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.setMargins(dp(12), 0, 0, 0);
                    c.setLayoutParams(lp);
                } else if (c instanceof TextView) {
                    TextView l = (TextView) c;
                    l.setTextSize(13);
                    l.setTextColor(0xFFD0D0D0);
                    if (j == 0) {
                        l.setLayoutParams(new LinearLayout.LayoutParams(dp(128), -2));
                    } else {
                        l.setPadding(dp(16), 0, dp(6), 0);
                    }
                }
            }
        }
    }

    /** The master's settings panel: tabs PHOTO / HDR & ASTRO / VIDEO / WB · TONE · FOCUS / SYSTEM. */
    private void buildMasterSettings(LinearLayout root) {
        // tab bar in the top row, left of the close button
        LinearLayout top = (LinearLayout) root.getChildAt(0);
        top.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.HorizontalScrollView ths = new android.widget.HorizontalScrollView(this);
        ths.setHorizontalScrollBarEnabled(false);
        LinearLayout tabBar = new LinearLayout(this);
        ths.addView(tabBar);
        top.addView(ths, 0, new LinearLayout.LayoutParams(0, -2, 1f));
        for (int i = 0; i < TABS.length; i++) {
            TextView t = new TextView(this);
            t.setText(TABS[i]);
            t.setTextSize(12);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setSingleLine();
            t.setPadding(dp(8), dp(7), dp(8), dp(7));
            final int k = i;
            t.setOnClickListener(v -> selectTab(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(4), 0);
            tabBar.addView(t, lp);
            tabHeads[i] = t;
            LinearLayout p = new LinearLayout(this);
            p.setOrientation(LinearLayout.VERTICAL);
            p.setPadding(0, dp(8), 0, 0);
            root.addView(p);
            tabPages[i] = p;
        }
        LinearLayout photo = tabPages[0], hdr = tabPages[1], video = tabPages[2], look = tabPages[3], sys = tabPages[4];

        // ---------------- PHOTO
        List<Lenses.Lens> ul = Lenses.usable(lenses);
        String[] lensNames = new String[ul.size()];
        for (int i = 0; i < ul.size(); i++) lensNames[i] = ul.get(i).label();
        seg(photo, "Lens", lensNames, () -> {
            for (int i = 0; i < ul.size(); i++) if (ul.get(i).kind == master.lens) return i;
            return 0;
        }, k -> setMasterLens(ul.get(k).kind));
        StringBuilder lensHint = new StringBuilder("Photo and video, copied to all cameras (a camera phone can then "
                + "choose another lens with its lens chip). Main at every start.");
        for (Lenses.Lens l : lenses) if (!l.ok) lensHint.append(" ").append(l.label()).append(": not usable (")
                .append(l.reason).append(").");
        hint(photo, lensHint.toString());
        seg(photo, "Aspect", PhotoCapture.ASPECTS, () -> master.photoAspect, this::setAspect);
        seg(photo, "Format", FORMAT_TILES, this::formatTile, this::setFormatTile);
        hdrModeRow = new LinearLayout(this);
        hdrModeRow.setOrientation(LinearLayout.VERTICAL);
        photo.addView(hdrModeRow);
        seg(hdrModeRow, "Extra frames (up to)", new String[]{"1", "2", "3"},
                () -> hdrMaxFrames - 1, k -> {
            hdrMaxFrames = k + 1;
            master.hdrMode = PhotoBurst.MODE_AUTO;
            master.lookChanged("HDR: up to " + hdrMaxFrames + " extra frame" + (hdrMaxFrames > 1 ? "s" : "")
                    + " (at most " + 3 * hdrMaxFrames + " EV)");
            savePhotoPrefs();
        });
        hint(hdrModeRow, "Longer frames for the dark parts, as many as the scene needs, steps of at most 3 EV."
                + " 1 = mild (shadows at most 3 EV brighter), 3 = full range (up to 9 EV).");
        // JPEG / RAW (V0.102, faces smeared in #134-138: Night at 1/31 s ISO 437): a shutter limit for
        // sharp people (ISO raised instead, up to 3200) and Night only in really dark scenes, or never
        singleRow = new LinearLayout(this);
        singleRow.setOrientation(LinearLayout.VERTICAL);
        photo.addView(singleRow);
        seg(singleRow, "Slowest shutter", SINGLE_SLOW_NAMES, () -> singleSlowest, k -> {
            singleSlowest = k;
            master.lookChanged("JPEG / RAW: slowest shutter " + SINGLE_SLOW_NAMES[k] + " - press AE");
            savePhotoPrefs();
        });
        singleIsoEdit = field(fieldRow(singleRow), "Max ISO", "800");
        hint(singleRow, "Limits of AE for JPEG / RAW. Shorter shutter = people stay sharp. When the light is "
                + "short, AE says so and the exposure chip shows -x EV: brighten with the brightness or ISO slider "
                + "(up to ISO " + SINGLE_ISO_MANUAL + ").");
        seg(singleRow, "Night", new String[]{"off", "on"}, () -> nightOn ? 1 : 0, k -> {
            nightOn = k == 1;
            updateNight();
            if (master.effFormat() == 3) master.hdrPlan = computeHdrPlan();
            savePhotoPrefs();
            refreshPhotoButtons();
        });
        hint(singleRow, "On: when the light is short, the app takes several frames and averages them (for still "
                + "scenes; faces may look smeared). Off: always the camera's own single photo.");
        int[] jq = PhotoCapture.JPEG_QUALITIES;
        String[] jqs = new String[jq.length];
        for (int i = 0; i < jq.length; i++) jqs[i] = String.valueOf(jq[i]);
        seg(photo, "JPEG quality", jqs, () -> {
            for (int i = 0; i < jq.length; i++) if (jq[i] == master.jpegQuality) return i;
            return -1;
        }, k -> {
            master.jpegQuality = jq[k];
            master.lookChanged("JPEG quality " + jq[k] + (jq[k] == 100 ? " (about 2x larger files)" : ""));
            savePhotoPrefs();
        });
        seg(photo, "Sharpening", MergeCore.SHARP_NAMES, () -> master.sharpLevel, k -> {
            master.sharpLevel = k;
            MergeCore.sharpLevel = k;
            master.lookChanged("Sharpening " + MergeCore.SHARP_NAMES[k]
                    + (k == 0 ? " (also the camera's own sharpening off)" : "") + " - not in astro");
            savePhotoPrefs();
        });
        hint(photo, "Never in astro. Off also switches off the camera's own sharpening.");
        seg(photo, "Local contrast", MergeCore.CLARITY_NAMES, () -> master.clarityLevel, k -> {
            master.clarityLevel = k;
            MergeCore.clarityLevel = k;
            master.lookChanged("Local contrast " + MergeCore.CLARITY_NAMES[k] + " - HDR / app processing, not in astro");
            savePhotoPrefs();
        });
        hint(photo, "Crisper text and texture (like Google), edge-aware: no halos. HDR only, never in astro.");
        aeClipEdit = field(fieldRow(photo), "AE: max clipped %", "0.1");
        hint(photo, "0.1 = almost nothing clips (a bare bulb makes the rest darker), 1 = small lights may clip.");
        buildTimelapseUi(photo);

        // ---------------- HDR & ASTRO
        subhead(hdr, "HDR - longer frames");
        longIsoEdit = field(fieldRow(hdr), "Max ISO", "800");
        seg(hdr, "Slowest shutter", new String[]{"longest", "1/60 s", "1/125 s"}, () -> slowest, k -> {
            slowest = k;
            savePhotoPrefs();
        });
        seg(hdr, "Movement", new String[]{"sharp", "natural blur"}, () -> master.natural ? 1 : 0, k -> {
            master.natural = k == 1;
            master.lookChanged(master.natural ? "movement: natural blur (longer frames at ~1/31 s)" : "movement: sharp");
            savePhotoPrefs();
        });
        // segments: night look / natural / bright = MergeCore.BRIGHT_NIGHT / _NATURAL / _BRIGHT
        final int[] brMap = {MergeCore.BRIGHT_NIGHT, MergeCore.BRIGHT_NATURAL, MergeCore.BRIGHT_BRIGHT};
        seg(hdr, "Brightness", new String[]{"night look", "natural", "bright"}, () -> {
            for (int i = 0; i < brMap.length; i++) if (brMap[i] == master.brightness) return i;
            return 1;
        }, k -> {
            master.brightness = brMap[k];
            master.lookChanged("brightness: " + new String[]{"night look", "natural (median -4 EV)", "bright"}[k]);
            savePhotoPrefs();
        });
        hint(hdr, "Exposure first up to the slowest shutter, then ISO. Natural blur only at ~1/31 s."
                + " Brightness: night look = dark like Google night photos, natural = middle tones at most"
                + " 4 EV below white, bright = 3 EV.");
        subhead(hdr, "Astro - tripod");
        seg(hdr, "Duration", new String[]{"1 min", "2 min"}, () -> astroSeconds >= 120 ? 1 : 0, k -> {
            astroSeconds = k == 1 ? 120 : 60;
            master.lookChanged("astro series: " + astroSeconds / 60 + " min of long frames (tripod)");
            savePhotoPrefs();
        });
        seg(hdr, "Light frame steps", ASTRO_STEP_NAMES, () -> astroStepIdx, k -> {
            astroStepIdx = k;
            master.lookChanged("astro light frames: steps of at most " + ASTRO_STEP_NAMES[k] + " (press AE)");
            savePhotoPrefs();
        });
        hint(hdr, "Frames between the short one and the 16 s frames, for lamps. 1.5 EV: more frames, smoother glows.");
        seg(hdr, "Astro WB", ASTRO_WB, () -> astroWb, k -> {
            astroWb = k;
            if (astroPresetWb()) applyAstroWb();
            else master.lookChanged("astro WB: " + ASTRO_WB[astroWb]);
            savePhotoPrefs();
        });

        // ---------------- VIDEO
        LinearLayout hidden = new LinearLayout(this);        // exposure / ISO: the main-screen sliders
        hidden.setVisibility(android.view.View.GONE);
        video.addView(hidden);
        expEdit = field(hidden, "exp ms", "10");
        isoEdit = field(hidden, "ISO", "400");
        videoEdit = field(hidden, "video p", "2160");
        seg(video, "Resolution", new String[]{"1080p", "4K"}, () -> parse(videoEdit, 1080) >= 2000 ? 1 : 0, k -> {
            videoEdit.setText(k == 1 ? "2160" : "1080");
            if (master.running) sendState(true, master.align);
        });
        fpsEdit = field(fieldRow(video), "fps", "29.8");
        hint(video, "4K and photo mode: at most 29.9 fps.");
        bitrateEdit = field(fieldRow(video), String.format(Locale.US, "video Mbit/s (0 = auto, max %d)",
                maxBr() / 1_000_000), "0");
        seg(video, "Bitrate mode", new String[]{"VBR", "CBR"}, () -> master.cbr ? 1 : 0, k -> {
            master.cbr = k == 1;
            master.lookChanged("video bitrate mode " + (master.cbr ? "CBR (constant)" : "VBR (variable)"));
            savePhotoPrefs();
        });
        hint(video, "VBR: an average target - the encoder uses less in easy scenes and may use more in noisy "
                + "ones (Beam Pro at 100: 52 Mbit/s sunny, 124 Mbit/s dim indoors). CBR: the bitrate is held (most "
                + "detail in busy scenes). A phone without CBR uses VBR (noted in the video's frame list).");
        seg(video, "Fill lost frames", new String[]{"off", "on"}, () -> master.fillLost ? 1 : 0, k -> {
            master.fillLost = k == 1;
            master.lookChanged("fill lost video frames " + (master.fillLost ? "on" : "off"));
            savePhotoPrefs();
        });
        hint(video, "On: if the camera loses a frame, the previous one is repeated - both eyes always have the same "
                + "number of frames; but a real frame that comes late (> 50 ms) is then discarded and replaced by the "
                + "copy. Off: every camera frame as it is; a lost frame leaves a gap (a note says where).");
        seg(video, "Preview", PREVIEW_NAMES, () -> previewIdx(master.previewW), k -> {
            master.previewW = PREVIEW_W[k];
            master.lookChanged("preview up to " + PREVIEW_NAMES[k] + " pixels wide");
            savePhotoPrefs();
        });
        hint(video, "Size of the live picture on the screens (the recorded video is not changed). Smaller = less load "
                + "on the camera: fewer lost frames at 4K on some phones (Xreal Beam Pro).");
        seg(video, "Codec", new String[]{"H.264", "HEVC"}, () -> master.hevc ? 1 : 0, k -> {
            master.hevc = k == 1;
            updateBitrateLabel();
            double mb = parse(bitrateEdit, 0);
            master.videoBitrate = mb <= 0 ? 0 : (int) Math.min(maxBr(), mb * 1e6);
            master.lookChanged("video codec " + (master.hevc ? "HEVC (H.265)" : "H.264"));
        });
        seg(video, "Conform to 30 fps", new String[]{"on", "off (real timing)"}, () -> master.conformFps > 0 ? 0 : 1, k -> {
            master.conformFps = k == 0 ? 30 : 0;
            master.lookChanged(master.conformFps > 0 ? "video conformed to 30 fps" : "video in real timing");
        });
        soundEdit = field(fieldRow(video), "Sound offset ms", "");
        soundEdit.setHint("auto");             // empty: every camera its own model's value

        // ---------------- WB · TONE · FOCUS
        seg(look, "White balance", WB_NAMES, () -> wbChoice, k -> {
            wbChoice = k;
            applyWbChoice();
        });
        LinearLayout wbRow = fieldRow(look);
        lockWbButton = button(wbRow, "Lock WB");
        lockWbButton.setOnClickListener(v -> {
            if (WB_NAMES[wbChoice].equals("K")) applyKelvin();
            else lockWb();
        });
        Button calButton = button(wbRow, "Calibrate WB");
        calButton.setOnClickListener(v -> calibrateWb());
        kelvinRow = fieldRow(look);
        kelvinEdit = field(kelvinRow, "WB K", "5000");
        tintEdit = field(kelvinRow, " tint", "0");
        awbText = new TextView(this);
        awbText.setTextSize(12);
        look.addView(awbText);
        hint(look, "auto shared: measured on all phones at AE, the same on all. Lock WB: keep the current values.");
        seg(look, "Tone", SyncCamera.TONES, () -> master.tone, k -> {
            master.tone = k;
            master.lookChanged("tone curve " + SyncCamera.TONES[k]
                    + (k > 0 ? " (highlight roll-off; press AE again)" : " (camera default)"));
        });
        seg(look, "Focus", new String[]{"auto", "manual"}, () -> master.focusManual ? 1 : 0, k -> {
            master.focusManual = k == 1;
            if (master.focusManual) master.focusDiopters = metresToDiopters(parse(distEdit, 1.5));
            master.lookChanged(master.focusManual ? String.format(Locale.US, "focus manual %s",
                    focusText(master.focusDiopters)) : "focus auto");
        });
        distEdit = field(fieldRow(look), "Manual focus m", "1.5");

        // ---------------- SYSTEM
        // (item 13, 2026-11-17: no "Cameras" row - the cameras start by themselves, typed values apply
        // when editing ends, standby has its button on the main screen, alignment is in the tests)
        standbyEdit = field(fieldRow(sys), "Standby after s (min 10)", "120");
        hint(sys, "Or now: the STANDBY button left of the image. A tap wakes the cameras.");
        seg(sys, "Photo JPEG", new String[]{"camera", "app (from YUV)"}, () -> master.appJpeg ? 1 : 0, k -> {
            master.appJpeg = k == 1;
            savePhotoPrefs();
            master.lookChanged(master.appJpeg ? "photo JPEG made in the app from full-res YUV (restarts photo mode)"
                    : "photo JPEG from the camera");
        });
        hint(sys, "app: only for phones whose camera JPEG stalls the frame stream (e.g. Pixel 4).");
        seg(sys, "Burst single DNGs", new String[]{"off", "save (~300 MB)"}, () -> master.keepBurstFrames ? 1 : 0, k -> {
            master.keepBurstFrames = k == 1;
            master.lookChanged(master.keepBurstFrames ? "HDR burst also saves the 8 single DNGs (~300 MB)"
                    : "HDR burst saves only the merged photo");
            savePhotoPrefs();
        });
        seg(sys, "GPU merge", MergeCore.GPU_NAMES, () -> master.gpuMode, k -> {
            master.gpuMode = k;
            MergeCore.gpuMode = k;
            master.lookChanged("GPU merge " + MergeCore.GPU_NAMES[k] + (k == 2 ? " (GPU + CPU, difference in the info file)" : ""));
            savePhotoPrefs();
        });
        hint(sys, "Robustness weights of the HDR / night / astro merge on the graphics chip (faster). "
                + "Check: both, the CPU result is used, the difference goes to the info file (slower).");
        seg(sys, "Anti-flicker", FLICKER_NAMES, () -> flicker, k -> {
            flicker = k;
            savePhotoPrefs();
            // the current exposure at once on a whole step (the next AE keeps to the steps)
            double[] fr = flickerRound(master.exposureNs / 1e6, master.iso, aeMaxExposureMs(), 50,
                    Math.max(master.iso, aeIsoCap()));
            if (expEdit != null && isoEdit != null) {
                expEdit.setText(String.format(Locale.US, "%.3f", fr[0]));
                isoEdit.setText(String.valueOf(Math.round(fr[1])));
            }
            if (master.effFormat() == 3) master.hdrPlan = computeHdrPlan();
            master.lookChanged("anti-flicker " + FLICKER_NAMES[k]);
            sendState(true, master.align);
        });
        hint(sys, "Lamps on 50 Hz / 60 Hz mains flicker; short exposures then show stripes. Photo and video "
                + "exposures of 10 ms (60 Hz: 8.3 ms) or longer become whole steps (10, 20, 30 ms ...); the ISO "
                + "keeps the brightness. Shorter exposures and astro stay as they are.");
        subhead(sys, "Remote trigger (experiment)");
        LinearLayout rr = fieldRow(sys);
        remoteBtn = button(rr, "Start remote trigger (Google Camera)");
        remoteBtn.setOnClickListener(v -> {
            if (master.remoteMode == 1) stopRemote();
            else startRemote();
        });
        hint(sys, "Our cameras off, the clock sync keeps running; a floating 3D button over Google Camera presses "
                + "its shutter on all phones at the same moment. Needs: display over other apps (CONTROL phone), "
                + App.NAME + " enabled in Settings > Accessibility (all phones; first App info > Allow restricted settings).");
        subhead(sys, "Sync test (LED)");
        seg(sys, "Frame alignment", new String[]{"On", "Off (tests only)"}, () -> master.align ? 0 : 1,
                k -> sendState(master.running, k == 0));
        LinearLayout ledRow = fieldRow(sys);
        asymEdit = field(ledRow, "Wi-Fi asym us", "");
        asymEdit.setHint("auto");              // empty: every camera its own model's value
        capEdit = field(ledRow, " LED s", "90");
        captureButton = button(ledRow, "LED capture");
        captureButton.setOnClickListener(v -> {
            if (!master.running) {
                master.log.add("LED capture: start the cameras first");
                return;
            }
            master.asymNs = parseAuto(asymEdit, 1e3);
            master.startCapture((int) Math.max(5, Math.min(600, parse(capEdit, 90))));
        });
        subhead(sys, "Info");
        sections.put("Info", sys);
        header = mono(13);
        header.setTextColor(Color.WHITE);
        sys.addView(header);
        table = mono(12);
        table.setTextColor(0xFFD0D0D0);
        table.setPadding(0, dp(6), 0, dp(6));
        sys.addView(table);
        graph = new GraphView(this);
        graph.setTitle("Clock sync residual, last 60 s (us)");
        sys.addView(graph, new LinearLayout.LayoutParams(-1, dp(150)));
        logView = mono(11);
        logView.setTextColor(0xFFB0B0B0);
        sys.addView(logView);
        for (LinearLayout pg : tabPages) styleFieldRows(pg);
        selectTab(TAB_PHOTO);
    }

    // ------------------------------------------------------------------ quick sliders, AE, thumbnail

    private static final java.util.Set<String> SLIDERS =
            new java.util.HashSet<>(java.util.Arrays.asList("exposure", "iso", "wb", "focus"));
    private android.widget.ImageView thumb;
    private boolean aeRunning;
    private boolean ledPending;                      // LED test requested, analysis stream not yet there

    // STANDBY (power, measured 2026-11-17: the running camera pipeline costs ~2.5 W per phone):
    // after STANDBY_MS without a touch on the master all cameras stop (not while recording, AE,
    // a burst / astro series, the LED test); a tap on the master wakes them (that tap only wakes)
    // user setting "Standby after s" (Advanced): default 30 s, at least 10 s
    static final double STANDBY_DEFAULT_S = 120, STANDBY_MIN_S = 10;   // 120 s since V0.103 (user)
    private EditText standbyEdit;

    private long standbyMs() {
        double s = standbyEdit == null ? STANDBY_DEFAULT_S : parse(standbyEdit, STANDBY_DEFAULT_S);
        return Math.round(Math.max(STANDBY_MIN_S, s) * 1000);
    }
    private long lastTouchMs = SystemClock.elapsedRealtime();
    private boolean standby;
    private boolean standbyAlign;                    // Align state before standby (stop clears it)
    private TextView standbyView;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        lastTouchMs = SystemClock.elapsedRealtime();
        if (standby && master != null) {
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) wakeFromStandby();
            return true;                             // the waking tap does nothing else
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Standby overlay over the preview (master: tap to wake; camera: wake on the master). */
    private void showStandbyOverlay(boolean on, String text) {
        if (previewArea == null) return;
        if (on && standbyView == null) {
            standbyView = new TextView(this);
            standbyView.setTextSize(20);
            standbyView.setTextColor(Color.WHITE);
            standbyView.setGravity(Gravity.CENTER);
            standbyView.setBackgroundColor(0xE0000000);
            previewArea.addView(standbyView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        if (standbyView == null) return;
        if (on) {
            standbyView.setText(text);
            standbyView.setVisibility(android.view.View.VISIBLE);
            standbyView.bringToFront();
        } else {
            standbyView.setVisibility(android.view.View.GONE);
        }
    }

    private void checkStandby() {
        if (master == null || standby || !master.running) return;
        boolean busy = master.record || aeRunning || master.photoBusy() || tlRunning
                || (syncCamera != null && syncCamera.burstBusy()) || ledPending || (led != null && led.isCapturing());
        if (busy) {
            lastTouchMs = Math.max(lastTouchMs, SystemClock.elapsedRealtime() - standbyMs() / 2);
            return;
        }
        if (SystemClock.elapsedRealtime() - lastTouchMs < standbyMs()) return;
        enterStandby();
    }

    /** All cameras off (power); a tap on the master wakes them. Also the STANDBY button. */
    private void enterStandby() {
        if (master == null || standby || !master.running) return;
        standby = true;
        standbyAlign = master.align;                 // a stop clears "Align" (MasterNode): restored on wake
        master.standby = true;                       // the camera phones show a note
        master.log.add("standby: cameras stopped (tap to wake)");
        sendState(false, master.align);
        showStandbyOverlay(true, "Standby\n(cameras off to save battery)\n\ntap to wake");
    }

    private void wakeFromStandby() {
        standby = false;
        startAeDone = false;                          // the light may have changed: AE once more (like at start)
        streamSinceMs = 0;
        lastTouchMs = SystemClock.elapsedRealtime();
        showStandbyOverlay(false, null);
        master.log.add("wake: cameras starting");
        master.standby = false;
        sendState(true, standbyAlign);
    }

    /** Longest exposure that fits the frame (period minus the readout margin; 29.8 fps: 1/31 s). */
    private double maxExposureMs() {
        return (master.periodNs - SyncCamera.EXPOSURE_MARGIN_NS) / 1e6;
    }

    // JPEG / RAW single photos (V0.102, faces smeared in #134-138 by the automatic Night at ISO 437):
    // the AE limits slowest shutter (longest = the frame time ~1/31 s, 1/60 s, 1/125 s) and Max ISO (800);
    // when the light is short there, a note + "-x EV" on the exposure chip - the user brightens with the
    // brightness / ISO slider (they may go beyond the limits, up to ISO 3200). Night only when the user
    // switches it on (and then only when the light is short) - no automatic Night until item 34.
    static final String[] SINGLE_SLOW_NAMES = {"1/31 s", "1/60 s", "1/125 s"};
    static final int SINGLE_ISO_MANUAL = 3200;      // sliders' limit for single photos
    private int singleSlowest = 0;
    private boolean nightOn = false;                 // Night of JPEG / RAW: user's choice, default off
    private EditText singleIsoEdit;
    private boolean aeShort;                         // the last AE reached the limits with too little light

    /** A single JPEG / RAW photo (not HDR / Astro, not Night). */
    private boolean singleFrame() {
        return master != null && master.photoMode && master.photoFormat < 3 && master.nightOut < 0;
    }

    /** The shutter limit of single photos (ms). */
    private double singleMaxExposureMs() {
        double m = maxExposureMs();
        return singleSlowest == 0 ? m : Math.min(m, singleSlowest == 1 ? 1000.0 / 60 : 1000.0 / 125);
    }

    private int singleIsoMax() {
        return (int) Math.max(50, Math.min(SINGLE_ISO_MANUAL, parse(singleIsoEdit, 800)));
    }

    /** Longest exposure the AE uses: the shutter limit for single photos, else the frame time
     *  (with anti-flicker: down to a whole flicker step, e.g. 32.3 -> 30 ms). */
    private double aeMaxExposureMs() {
        return flickerFloor(singleFrame() ? singleMaxExposureMs() : maxExposureMs());
    }

    // ANTI-FLICKER (item 32, V0.105): lamps on 50 / 60 Hz mains flicker at 100 / 120 Hz; our manual
    // exposure switches the camera's own antibanding off. Exposures of at least one flicker step
    // (10 / 8.33 ms) become whole steps (rows of the rolling shutter then catch whole cycles: no
    // stripes), the ISO keeps the brightness. Shorter exposures (daylight, highlight frames) and astro
    // stay as they are. Only the CONTROL phone rounds: the camera phones get its values.
    static final String[] FLICKER_NAMES = {"Off", "50 Hz", "60 Hz"};
    private int flicker = 0;

    private double flickerStepMs() {
        return flicker == 1 ? 10.0 : flicker == 2 ? 1000.0 / 120 : 0;
    }

    /** A limit (ms) down to a whole flicker step (unchanged when off or below one step). */
    private double flickerFloor(double maxMs) {
        double st = flickerStepMs();
        if (st <= 0 || maxMs < st) return maxMs;
        return Math.floor(maxMs / st + 1e-6) * st;
    }

    /** {exposure ms, ISO} with the exposure on the NEAREST flicker step (same light exposure x ISO):
     *  up only when it fits maxMs and the ISO stays >= isoMin, down only when the ISO stays <= isoMax. */
    private double[] flickerRound(double exp, double iso, double maxMs, double isoMin, double isoMax) {
        double st = flickerStepMs();
        if (st <= 0 || exp < st * 0.999) return new double[]{exp, iso};
        double ei = exp * iso;
        double dn = Math.max(st, Math.floor(exp / st + 1e-6) * st), up = dn + st;
        if (Math.abs(exp - dn) < 1e-3) return new double[]{dn, iso};
        boolean upOk = up <= maxMs + 1e-6 && ei / up >= isoMin - 0.5;
        boolean dnOk = ei / dn <= isoMax + 0.5;
        double e = upOk && (!dnOk || up / exp < exp / dn) ? up : dn;
        return new double[]{e, Math.max(isoMin, Math.min(Math.max(isoMax, isoMin), ei / e))};
    }

    /** How much light (EV) a single photo misses at the AE limits (the last AE), 0 = enough. */
    private double singleShortEv() {
        if (aeIsoNeed <= 0) return 0;
        double need = aeIsoNeed * maxExposureMs(), have = singleMaxExposureMs() * singleIsoMax();
        return Math.max(0, Math.log(need / have) / Math.log(2));
    }

    /** "-1.3 EV" while the current exposure is darker than the scene needs (after a short AE). */
    private String shortText() {
        if (!aeShort || !singleFrame() || aeIsoNeed <= 0) return "";
        double ev = Math.log(aeIsoNeed * maxExposureMs() / (master.exposureNs / 1e6 * master.iso)) / Math.log(2);
        return ev >= 0.1 ? String.format(Locale.US, "  −%.1f EV", ev) : "";
    }

    /** ISO limit of the sliders: single photos up to 3200 (beyond the AE's Max ISO), else the AE limit. */
    private int manualIsoCap() {
        return singleFrame() ? SINGLE_ISO_MANUAL : aeIsoCap();
    }

    /**
     * Small slider popup next to a chip. The value is applied when the slider is released (and by
     * the optional extra button, e.g. "auto").
     */
    /** Manual exposure slider with anti-flicker: from one step up only whole steps (ms). */
    private double flickerSnap(double v) {
        double st = flickerStepMs();
        if (st <= 0 || v < st * 0.95) return v;
        return Math.max(st, Math.min(flickerFloor(maxExposureMs()), Math.round(v / st) * st));
    }

    private void showSlider(String what, android.view.View anchor) {
        final double min, max, start;
        final boolean log;
        final java.util.function.DoubleFunction<String> fmt;
        final java.util.function.DoubleConsumer apply;
        String extra = null;
        Runnable extraAction = null;
        switch (what) {
            case "exposure":
                min = 1000.0 / 8000;
                max = maxExposureMs();
                start = master.exposureNs / 1e6;
                log = true;
                fmt = v -> exposureText(Math.round(flickerSnap(v) * 1e6));
                apply = v -> {
                    expEdit.setText(String.format(Locale.US, "%.3f", flickerSnap(v)));
                    sendState(true, master.align);
                };
                break;
            case "iso":
                min = 50;
                max = manualIsoCap();
                start = master.iso;
                log = true;
                fmt = v -> "ISO " + Math.round(v);
                apply = v -> {
                    isoEdit.setText(String.valueOf(Math.round(v)));
                    sendState(true, master.align);
                };
                break;
            case "wb":
                min = 2000;
                max = 10000;
                start = Math.max(2000, Math.min(10000, parse(kelvinEdit, 5000)));
                log = false;
                fmt = v -> Math.round(v / 50) * 50 + " K";
                apply = v -> {
                    kelvinEdit.setText(String.valueOf(Math.round(v / 50) * 50));
                    wbChoice = java.util.Arrays.asList(WB_NAMES).indexOf("K");
                    applyKelvin();
                };
                extra = "auto";
                extraAction = () -> {
                    wbChoice = 0;
                    applyWbChoice();
                };
                break;
            default:                                               // focus, in diopters (0 = infinity)
                min = 0;
                max = 10;
                start = master.focusManual ? master.focusDiopters : metresToDiopters(parse(distEdit, 1.5));
                log = false;
                fmt = v -> "Focus " + focusText((float) v);
                apply = v -> {
                    master.focusManual = true;
                    master.focusDiopters = (float) v;
                    distEdit.setText(v > 0.001 ? String.format(Locale.US, "%.2f", 1 / v) : "inf");
                    master.lookChanged("focus " + focusText((float) v));
                };
                extra = "auto";
                extraAction = () -> {
                    master.focusManual = false;
                    master.lookChanged("focus auto");
                };
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(rounded(0xF0202020, GREY));
        TextView val = new TextView(this);
        val.setTextColor(Color.WHITE);
        val.setTextSize(18);
        val.setText(fmt.apply(start));
        box.addView(val);
        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(1000);
        final java.util.function.DoubleUnaryOperator toValue = p -> log ? min * Math.pow(max / min, p / 1000.0)
                : min + (max - min) * p / 1000.0;
        double p0 = log ? Math.log(start / min) / Math.log(max / min) * 1000 : (start - min) / (max - min) * 1000;
        sb.setProgress((int) Math.round(Math.max(0, Math.min(1000, p0))));
        sb.setMinimumHeight(dp(48));                                // tall touch strip, easy to grab
        sb.setPadding(dp(16), dp(14), dp(16), dp(14));
        sb.setScaleY(1.3f);                                         // bigger knob and line
        box.addView(sb, new LinearLayout.LayoutParams(dp(300), dp(48)));
        android.widget.PopupWindow pw = new android.widget.PopupWindow(box, -2, -2, true);
        pw.setOutsideTouchable(true);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                val.setText(fmt.apply(toValue.applyAsDouble(p)));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {
            }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {
                apply.accept(toValue.applyAsDouble(s.getProgress()));
            }
        });
        if (extra != null) {
            Button b = new Button(this);
            b.setText(extra);
            b.setAllCaps(false);
            final Runnable ea = extraAction;
            b.setOnClickListener(v -> {
                ea.run();
                pw.dismiss();
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.topMargin = dp(12);                                 // gap: a finger on the slider misses it
            blp.gravity = Gravity.END;
            box.addView(b, blp);
        }
        box.measure(0, 0);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        boolean photo = master.photoMode;
        int x = photo ? loc[0] + anchor.getWidth() + dp(8) : loc[0];
        int y = photo ? loc[1] : loc[1] - box.getMeasuredHeight() - dp(8);
        pw.showAtLocation(rootFrame, Gravity.TOP | Gravity.START, x, Math.max(0, y));
    }

    /**
     * "AE" (highlight priority): measures the master preview (with the tone curve applied) and sets
     * exposure / ISO so that the brightest areas just stay below white: at most ~0.5 % of the
     * pixels clipped (any channel >= 250) and the 99.5 % point near 235; a mean far too dark is
     * lifted only as far as the highlights allow. A few steps, then the values stay fixed (locked)
     * and are used by all cameras. Android's own AE is not used: it changes the frame timing.
     */
    private boolean aeByUser;                        // this AE was started by the user (AE button / panel)

    private void autoExposure() {
        if (previewView == null || !master.running || aeRunning) {
            aeByUser = false;
            return;
        }
        aeRunning = true;
        aeWaitStep = -1;
        if (aePanel != null) aePanel.dismiss();      // a new AE: the old brightness panel goes
        savePhotoPrefs();                            // keeps the AE clip setting
        aeStartMs = SystemClock.elapsedRealtime();
        master.aeAllow = aeClipFraction();
        master.aeActive = true;                      // the cameras measure their views too
        if (astroPresetWb()) {
            applyAstroWb();                          // astro: the chosen preset on all phones
        } else if (sharedWb() && !tlRunning) {
            // shared white balance: all phones back to their automatic white balance while AE
            // runs; at the end the values are combined and locked on all phones
            master.wbMode = SyncCamera.WB_AUTO;
            master.wbGains = null;
            master.lookChanged("WB auto shared: measuring");
        }
        sendState(true, master.align);
        // the cameras need a moment for their first measurement (and the AWB to settle)
        ui.postDelayed(() -> aeStep(0, 1.0), 700);
    }

    private static final double AE_HI_TARGET = 235, AE_MEAN_MIN = 70;

    /**
     * ISO limit for AE and the ISO slider: in photo mode the user's Max ISO (user 2026-11-18: "no image,
     * even the first one, should exceed the user ISO setting" - AE had put the preview at ISO 1848)
     */
    private int aeIsoCap() {
        if (master == null || !master.photoMode) return 3200;
        if (singleFrame()) return singleIsoMax();
        return (int) Math.max(50, Math.min(3200, parse(longIsoEdit, 800)));
    }

    private double aeIsoNeed = 0;                // ISO the scene needs at the longest exposure (last AE)

    /**
     * One AE measurement of THIS phone (its preview + RAW statistics): {wanted exposure factor,
     * clipped fraction, preview mean}, or null without a preview image. Used by the master for
     * itself and by every camera phone for the joint AE (the master combines them).
     */
    private double[] aeMeasure(boolean photoMode, int photoFormat, double allow) {
        android.graphics.Bitmap bmp = previewView == null ? null : previewView.getBitmap(240, 135);
        if (bmp == null) return null;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] hist = new int[256];
        double sum = 0;
        int clipped = 0;
        for (int c : px) {
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            int mx = Math.max(r, Math.max(g, b));
            hist[mx]++;
            if (mx >= 250) clipped++;
            sum += 0.299 * r + 0.587 * g + 0.114 * b;
        }
        int n = px.length;
        double clipFrac = clipped / (double) n, mean = sum / n;
        // the user's "AE: max clipped %" (it was ignored below 0.5 %: a fixed floor, user report
        // 2026-11-16). At least 2 preview pixels (240x135: a smaller fraction is just noise).
        double lim = Math.max(allow, 2.0 / n);
        int acc = 0, pHi = 255;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc >= n * (1 - lim)) {
                pHi = v;
                break;
            }
        }
        // correction in linear light (the preview is display-encoded: ~gamma 2.2)
        // JPEG rule (preview): more than the allowed fraction blown -> darker; else the point
        // above which only that fraction lies goes to ~235
        double jpegRatio = clipFrac > lim
                ? (clipFrac > 0.05 ? 0.35 : clipFrac > Math.max(0.02, 10 * lim) ? 0.5 : 0.7)   // blown: darker
                : Math.pow(AE_HI_TARGET / Math.max(8.0, pHi), 2.2);
        double ratio;
        float[] rs = photoMode && photoFormat == 3 && syncCamera != null ? syncCamera.rawStats : null;
        if (rs != null) {
            // HDR burst: expose for the RAW data (the preview hides sensor clipping): at most the
            // set fraction clipped (Settings > Photo, "AE: max clipped %"); otherwise the point
            // that may clip goes to 90 % of the sensor range. Never brighter than the JPEG rule
            // would expose (the burst must not clip more than an ordinary photo; tested
            // 2026-11-15: raw rule alone 1/107 s vs JPEG 1/125 s, white spot on a shelf). The
            // JPEG rule's "keep a dim scene" does not limit it: only the highlights count.
            float[] rawHist = syncCamera.rawHist;
            double q = rawHist != null ? histPoint(rawHist, 1 - allow) : rs[0];
            double rawRatio = rs[1] > Math.max(allow, 0.00002) ? (rs[1] > 5 * allow + 0.002 ? 0.35 : 0.6)
                    : 0.9 / Math.max(0.02, q);
            ratio = Math.min(rawRatio, jpegRatio);
            clipFrac = rs[1];
        } else {
            ratio = jpegRatio;
            if (clipFrac <= lim
                    && mean * Math.pow(ratio, 1 / 2.2) < AE_MEAN_MIN && ratio < 1) ratio = 1;  // keep a dim scene
        }
        return new double[]{ratio, clipFrac, mean};
    }

    private long aeStartMs;                          // master: start of the running AE

    /** Cameras whose AE measurement is from the running AE (the joint decision uses them). */
    private java.util.List<MasterNode.ClientInfo> aeClients() {
        java.util.List<MasterNode.ClientInfo> l = new java.util.ArrayList<>();
        for (MasterNode.ClientInfo c : master.clients.values()) {
            if (!c.lost && c.aeMs >= aeStartMs && !Double.isNaN(c.aeRatio) && c.aeLight > 0) l.add(c);
        }
        return l;
    }

    /** total = product of all corrections so far (for the result message). */
    private void aeStep(int i, double total) {
        // CONTROL only (no camera of its own): its preview is black - only the camera phones measure
        // (2026-11-22, Beam Pro as CONTROL: the black preview counted, AE said -3.0 EV instead of -1.3)
        boolean ownCam = syncCamera != null;
        double[] me = ownCam ? aeMeasure(master.photoMode, master.photoFormat, aeClipFraction())
                : new double[]{Double.POSITIVE_INFINITY, 0, 0};
        if (me == null) {
            aeDone("AE: no preview image", 0, 0);
            return;
        }
        double ratio = me[0], clipFrac = me[1], mean = me[2];
        // JOINT AE: every camera measured its own view; converted to the master's current exposure
        // (exposure x ISO it measured at / the master's), the most cautious phone decides - no
        // phone clips more than allowed (a lamp seen by one phone only limits both)
        double lightM = master.exposureNs * (double) master.iso;
        // only measurements made AT the current exposure (2026-11-22, Beam Pro as CONTROL only: a camera's
        // reading at the dark start exposure, converted, asked for 2.4 EV too much - the preview's dark end
        // is not linear); wait for them up to 2.5 s per step, then the older ones count as before
        long nowMs = SystemClock.elapsedRealtime();
        if (aeWaitStep != i) {
            aeWaitStep = i;
            aeWaitSinceMs = nowMs;
        }
        int nJoint = 0, stale = 0;
        for (MasterNode.ClientInfo c : aeClients()) if (Math.abs(c.aeLight / lightM - 1) > 0.03) stale++;
        boolean waitFresh = stale > 0 && nowMs - aeWaitSinceMs < 2500;
        if (waitFresh || (!ownCam && aeClients().isEmpty() && nowMs - aeWaitSinceMs < 5000)) {
            ui.postDelayed(() -> aeStep(i, total), 250);
            return;
        }
        for (MasterNode.ClientInfo c : aeClients()) {
            double rc = c.aeRatio * c.aeLight / lightM;
            if (rc < ratio) ratio = rc;
            clipFrac = Math.max(clipFrac, c.aeClip);
            nJoint++;
        }
        aeJoint = nJoint;
        if (!ownCam && nJoint == 0) {
            aeDone("AE: no camera phone measured", 0, 0);
            return;
        }
        android.util.Log.i("SyncCamAE", String.format(Locale.US, "step %d own %.2f joint %.2f (%d cams) light %.0f",
                i, me[0], ratio, nJoint, master.exposureNs / 1e6 * master.iso));
        ratio = Math.max(0.125, Math.min(8, ratio));
        double ei = master.exposureNs / 1e6 * master.iso;
        boolean converged = Math.abs(ratio - 1) < 0.12;
        final int isoCap = aeIsoCap();
        boolean atLimit = (ratio > 1 && master.iso >= isoCap - 1 && master.exposureNs / 1e6 >= aeMaxExposureMs() - 0.01)
                || (ratio < 1 && master.iso <= 50 && master.exposureNs / 1e6 <= 0.126);
        // beyond the AE limits (the brightness / ISO slider went above them): AE returns to the limits
        // first - it must not stop at the slider's ISO 3200 just because the brightness is right
        boolean within = master.iso <= isoCap + 1 && master.exposureNs / 1e6 <= aeMaxExposureMs() + 0.01;
        if (!within) {
            converged = false;
            atLimit = false;
        }
        if ((converged && (i >= 1 || !sharedWb())) || atLimit || i >= 6) {
            // how much ISO the scene really needs at the longest exposure (beyond the cap: the night
            // frames make up for it by averaging)
            aeIsoNeed = master.exposureNs / 1e6 * master.iso * ratio / maxExposureMs();
            aeDone(String.format(Locale.US, "AE: %s ISO %d  (%+.1f EV, %.1f %% clipped%s)",
                    exposureText(master.exposureNs), master.iso, Math.log(total) / Math.log(2), clipFrac * 100,
                    atLimit ? ", limit reached" : ""), clipFrac, mean);
            return;
        }
        ei *= ratio;
        // lowest noise: ISO 50 with a longer exposure first (up to the frame limit), then ISO
        double exp = ei / 50, iso = 50;
        if (exp > aeMaxExposureMs()) {
            exp = aeMaxExposureMs();
            iso = Math.min(isoCap, ei / exp);
        } else if (exp < 0.125) {
            exp = 0.125;
        }
        double[] fr = flickerRound(exp, iso, aeMaxExposureMs(), 50, isoCap);
        exp = fr[0];
        iso = fr[1];
        expEdit.setText(String.format(Locale.US, "%.3f", exp));
        isoEdit.setText(String.valueOf(Math.round(iso)));
        sendState(true, master.align);
        final double t = total * ratio;
        ui.postDelayed(() -> aeStep(i + 1, t), 500);      // the new exposure needs a few frames
    }

    private boolean sharedWb() {
        return master != null && WB_NAMES[wbChoice].equals("auto shared");
    }

    private static String modelOf(String name) {
        int k = name == null ? -1 : name.indexOf(" #");
        return k < 0 ? String.valueOf(name) : name.substring(0, k);
    }

    /**
     * Shared white balance: the automatic white balance of the master and of every camera of the
     * SAME model (different models: their gains are not comparable - the master's alone), averaged
     * (geometric mean of R/G and B/G), with the master's colour matrix, locked on all phones.
     */
    private String lockSharedWb() {
        if (syncCamera == null) return lockSharedWbCameras();
        if (syncCamera.lastGains == null || syncCamera.lastXform == null) {
            return "WB shared: no automatic white balance on the CONTROL phone yet";
        }
        android.hardware.camera2.params.RggbChannelVector g = syncCamera.lastGains;
        double lr = Math.log(g.getRed() / g.getGreenEven()), lb = Math.log(g.getBlue() / g.getGreenEven());
        int n = 1, other = 0;
        String myModel = modelOf(SyncNet.deviceName(this));
        for (MasterNode.ClientInfo c : aeClients()) {
            if (c.awb == null || c.awb.length < 4) continue;
            if (!modelOf(c.name).equals(myModel)) {
                other++;
                continue;
            }
            lr += Math.log(c.awb[0] / c.awb[1]);
            lb += Math.log(c.awb[3] / c.awb[1]);
            n++;
        }
        float r = (float) Math.exp(lr / n), b = (float) Math.exp(lb / n);
        int[] x = new int[18];
        syncCamera.lastXform.copyElements(x, 0);
        master.wbGains = new float[]{r, 1f, 1f, b};
        master.wbXform = x;
        master.wbMode = SyncCamera.WB_COPY;
        master.lookChanged(String.format(Locale.US, "WB shared: R %.2f B %.2f from %d phone%s", r, b, n, n > 1 ? "s" : ""));
        return String.format(Locale.US, "WB shared from %d phone%s%s", n, n > 1 ? "s" : "",
                other > 0 ? " (" + other + " other model: CONTROL's)" : "");
    }

    /**
     * CONTROL only (no camera of its own, 2026-11-22): the automatic white balance of the camera phones of
     * the first camera's model, averaged; no colour matrix is sent - every camera keeps its own (same model).
     */
    private String lockSharedWbCameras() {
        double lr = 0, lb = 0;
        int n = 0, other = 0;
        String model = null;
        for (MasterNode.ClientInfo c : aeClients()) {
            if (c.awb == null || c.awb.length < 4) continue;
            if (model == null) model = modelOf(c.name);
            if (!modelOf(c.name).equals(model)) {
                other++;
                continue;
            }
            lr += Math.log(c.awb[0] / c.awb[1]);
            lb += Math.log(c.awb[3] / c.awb[1]);
            n++;
        }
        if (n == 0) return "WB shared: no white balance from the camera phones yet";
        float r = (float) Math.exp(lr / n), b = (float) Math.exp(lb / n);
        master.wbGains = new float[]{r, 1f, 1f, b};
        master.wbXform = null;
        master.wbMode = SyncCamera.WB_COPY;
        master.lookChanged(String.format(Locale.US, "WB shared: R %.2f B %.2f from %d camera%s", r, b, n, n > 1 ? "s" : ""));
        return String.format(Locale.US, "WB shared from %d camera%s%s", n, n > 1 ? "s" : "",
                other > 0 ? " (" + other + " other model: not used)" : "");
    }

    /** Darkest-half level for the HDR plan: the darkest phone (converted to the master's exposure). */
    private float jointRawDark() {
        float d = syncCamera != null ? syncCamera.rawDark : Float.NaN;   // CONTROL only: the camera phones'
        double lightM = master.exposureNs * (double) master.iso;
        for (MasterNode.ClientInfo c : aeClients()) {
            if (c.rawDark < 0 || Float.isNaN(c.rawDark)) continue;
            float dc = (float) (c.rawDark * lightM / c.aeLight);
            if (Float.isNaN(d) || dc < d) d = dc;
        }
        return d;
    }

    /** Median level for the astro plan: the brightest phone (converted to the master's exposure). */
    private float jointRawMedian() {
        float m = syncCamera != null ? syncCamera.rawMedian : Float.NaN;
        double lightM = master.exposureNs * (double) master.iso;
        for (MasterNode.ClientInfo c : aeClients()) {
            if (c.rawMedian < 0 || Float.isNaN(c.rawMedian)) continue;
            float mc = (float) (c.rawMedian * lightM / c.aeLight);
            if (Float.isNaN(m) || mc > m) m = mc;
        }
        return m;
    }

    /** Level (0..1 of the sensor range) below which the fraction p of the sampled RAW values lie. */
    private static double histPoint(float[] hist, double p) {
        double n = 0;
        for (float h : hist) n += h;
        double acc = 0;
        for (int b = 0; b < hist.length; b++) {
            acc += hist[b];
            if (acc >= p * n) return b / (double) (hist.length - 1);
        }
        return 1;
    }

    private int aeJoint;                             // cameras used in the last AE step
    private int aeWaitStep = -1;                     // AE step waiting for fresh camera measurements
    private long aeWaitSinceMs;

    private void aeDone(String msg, double clipFrac, double mean) {
        aeRunning = false;
        if (aeJoint > 0) msg += " (joint, " + (aeJoint + (syncCamera != null ? 1 : 0)) + " phones)";
        if (astroPresetWb()) msg += "\nastro WB: " + ASTRO_WB[astroWb];
        else if (sharedWb() && !tlRunning) msg += "\n" + lockSharedWb();
        master.aeActive = false;
        if (tlAeShot) {                                  // time-lapse: at most 1/3 EV per shot
            tlAeShot = false;
            double want = master.exposureNs / 1e6 * master.iso;
            double lim = Math.max(tlLight / TL_MAX_STEP, Math.min(tlLight * TL_MAX_STEP, want));
            if (Math.abs(lim / want - 1) > 0.01) {
                setLight(lim);
                msg += String.format(Locale.US, "\ntime-lapse: smoothed to %+.2f EV", Math.log(lim / want) / Math.log(2));
            }
            tlLight = lim;
        }
        updateNight();
        if (master.effFormat() == 3) {
            master.hdrPlan = computeHdrPlan();
            msg += "\n" + (master.nightOut >= 0 ? PhotoCapture.FORMATS[master.nightOut] + " Night"
                    : PhotoBurst.MODES[master.hdrMode]) + planText;
        }
        // single JPEG / RAW photo at its limits with too little light: say so (V0.102)
        aeShort = singleFrame() && singleShortEv() >= 0.1;
        if (aeShort) {
            msg = String.format(Locale.US, "Not enough light: −%.1f EV at %s ISO %d.\nBrighten with the brightness "
                    + "slider, or raise Max ISO / Slowest shutter (Settings > PHOTO).", singleShortEv(),
                    exposureText(master.exposureNs), master.iso) + "\n" + msg;
        }
        master.log.add(msg + String.format(Locale.US, ", mean %.0f", mean));
        if (aeShort) showWarnNote(msg);
        else showNote(msg);
        // item 22: the brightness panel right after AE (photo mode, not astro) - ONLY when the user
        // pressed AE (not after the automatic AE at the start / after standby, user 2026-11-20)
        aeBaseExpMs = master.exposureNs / 1e6;
        aeBaseIso = master.iso;
        boolean byUser = aeByUser;
        aeByUser = false;
        if (byUser && !(master.photoMode && astroSelected())) showAePanel();     // photo and video (2026-11-21)
    }

    // ------------------------------------------------------------------ item 22: brightness after AE
    // A panel after AE (and on a long press on the AE chip): the reference exposure by SHUTTER (log, +-3
    // EV around the AE result, ISO only above the frame-time limit) or by the CLIPPED % (0 and a log
    // scale 0.01 .. 10 %; releasing re-runs AE with that limit). Closes after 8 s without a touch.
    private TextView aeChip;
    private double aeBaseExpMs = -1;
    private int aeBaseIso;
    private boolean aePanelClip;                     // false: shutter, true: clipped %
    private android.widget.PopupWindow aePanel;
    static final double[] CLIP_STEPS = {0, 0.01, 0.03, 0.1, 0.3, 1, 3, 10};   // %
    private final Runnable aePanelClose = () -> {
        if (aePanel != null) aePanel.dismiss();
    };

    private void aePanelTouched() {
        ui.removeCallbacks(aePanelClose);
        ui.postDelayed(aePanelClose, 8000);
    }

    /** Exposure / ISO for a light level (exposure ms x ISO): ISO 50 with a longer exposure first (like AE). */
    private void setLight(double ei) {
        setLight(ei, false);
    }

    /** beyond: the brightness slider - above the AE limits the ISO rises further (single photos: up to
     *  3200), then the shutter (up to the frame time). */
    private void setLight(double ei, boolean beyond) {
        double[] ex = lightToExposure(ei, beyond);
        expEdit.setText(String.format(Locale.US, "%.3f", ex[0]));
        isoEdit.setText(String.valueOf(Math.round(ex[1])));
        sendState(true, master.align);
    }

    /** {exposure ms, ISO} for a light level: ISO 50 with a longer exposure first, then ISO (AE limits). */
    private double[] lightToExposure(double ei, boolean beyond) {
        double exp = ei / 50, iso = 50;
        if (exp > aeMaxExposureMs()) {
            exp = aeMaxExposureMs();
            iso = Math.min(aeIsoCap(), ei / exp);
            if (beyond && ei / exp > iso) {
                iso = Math.min(manualIsoCap(), ei / exp);
                exp = Math.min(maxExposureMs(), Math.max(exp, ei / iso));
            }
        } else if (exp < 0.125) {
            exp = 0.125;
            iso = Math.max(50, ei / exp);
        }
        return flickerRound(exp, iso, beyond ? flickerFloor(maxExposureMs()) : aeMaxExposureMs(), 50,
                Math.max(iso, beyond ? manualIsoCap() : aeIsoCap()));
    }

    private void showAePanel() {
        if (master == null) return;
        if (aePanel != null) aePanel.dismiss();
        if (aeBaseExpMs <= 0) {
            aeBaseExpMs = master.exposureNs / 1e6;
            aeBaseIso = master.iso;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(rounded(0xF0202020, GREY));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(master.effFormat() == 3 ? "Reference brightness" : "Brightness");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button bShut = new Button(this), bClip = new Button(this);
        bShut.setText("Shutter");
        bClip.setText("Clipped %");
        bShut.setAllCaps(false);
        bClip.setAllCaps(false);
        head.addView(bShut);
        head.addView(bClip);
        box.addView(head);
        TextView val = new TextView(this);
        val.setTextColor(Color.WHITE);
        val.setTextSize(18);
        box.addView(val);
        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        box.addView(sb, new LinearLayout.LayoutParams(dp(340), -2));
        TextView ends = new TextView(this);
        ends.setTextColor(0xFFBBBBBB);
        ends.setTextSize(13);
        box.addView(ends);
        final double baseEi = aeBaseExpMs * aeBaseIso;
        Runnable setup = () -> {
            bShut.setTextColor(aePanelClip ? 0xFF888888 : Color.WHITE);
            bClip.setTextColor(aePanelClip ? Color.WHITE : 0xFF888888);
            if (!aePanelClip) {
                sb.setMax(600);                          // -3 .. +3 EV in 0.01 EV
                double ev = Math.log(master.exposureNs / 1e6 * master.iso / baseEi) / Math.log(2);
                sb.setProgress((int) Math.round(Math.max(0, Math.min(600, (ev + 3) * 100))));
                ends.setText(String.format(Locale.US, "-3 EV  ...  AE %s ISO %d  ...  +3 EV",
                        exposureText(Math.round(aeBaseExpMs * 1e6)), aeBaseIso));
            } else {
                sb.setMax(CLIP_STEPS.length - 1);
                double cur = aeClipFraction() * 100;
                int k = 0;
                for (int i = 0; i < CLIP_STEPS.length; i++) if (Math.abs(CLIP_STEPS[i] - cur) < Math.abs(CLIP_STEPS[k] - cur)) k = i;
                sb.setProgress(k);
                ends.setText("0 %  ...  0.01  0.03  0.1  0.3  1  3  ...  10 %   (release: AE again)");
            }
        };
        java.util.function.IntFunction<String> text = pr -> {
            if (!aePanelClip) {
                double ev = pr / 100.0 - 3;
                double ei = baseEi * Math.pow(2, ev);
                double[] ex = lightToExposure(ei, true);
                double exp = ex[0], iso = ex[1];
                return String.format(Locale.US, "%s  ISO %d   (%+.1f EV)", exposureText(Math.round(exp * 1e6)),
                        Math.round(iso), ev);
            }
            double c = CLIP_STEPS[pr];
            return c == 0 ? "max clipped 0 % (nothing clips)" : String.format(Locale.US, "max clipped %s %%",
                    c < 0.1 ? String.format(Locale.US, "%.2f", c) : c < 1 ? String.format(Locale.US, "%.1f", c)
                            : String.format(Locale.US, "%.0f", c));
        };
        setup.run();
        val.setText(text.apply(sb.getProgress()));
        bShut.setOnClickListener(v -> {
            aePanelClip = false;
            setup.run();
            val.setText(text.apply(sb.getProgress()));
            aePanelTouched();
        });
        bClip.setOnClickListener(v -> {
            aePanelClip = true;
            setup.run();
            val.setText(text.apply(sb.getProgress()));
            aePanelTouched();
        });
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int pr, boolean user) {
                val.setText(text.apply(pr));
                aePanelTouched();
                if (user && !aePanelClip) setLight(baseEi * Math.pow(2, pr / 100.0 - 3), true);     // live
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {
                aePanelTouched();
            }

            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {
                aePanelTouched();
                if (!aePanelClip) {
                    // the HDR / night plan for the new reference
                    updateNight();
                    if (master.effFormat() == 3) {
                        master.hdrPlan = computeHdrPlan();
                        master.lookChanged("brightness " + val.getText() + planText);
                    }
                } else {
                    double c = CLIP_STEPS[s.getProgress()];
                    if (aeClipEdit != null) aeClipEdit.setText(c == 0 ? "0" : String.valueOf(c));
                    savePhotoPrefs();
                    aeByUser = true;
                    autoExposure();                       // AE again with this limit (the panel reopens after it)
                }
            }
        });
        // not focusable, no outside-touch dismiss: a tap beside the panel goes through (user 2026-11-22:
        // AE pressed while the panel was open only closed it - the ISO stayed at the slider's 3200);
        // it closes after 8 s, with a new AE, or with Back (onBackPressed)
        aePanel = new android.widget.PopupWindow(box, -2, -2, false);
        aePanel.setOutsideTouchable(false);
        box.measure(0, 0);
        android.view.View anchor = aeChip != null ? aeChip : rootFrame;
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0] + anchor.getWidth() + dp(8);
        int y = Math.max(0, loc[1] + anchor.getHeight() - box.getMeasuredHeight());
        aePanel.showAtLocation(rootFrame, Gravity.TOP | Gravity.START, x, y);
        aePanelTouched();
    }

    private TextView noteView;
    private final Runnable hideNote = () -> {
        if (noteView != null) noteView.setVisibility(android.view.View.GONE);
    };

    /** Short message over the upper part of the image (below the sync bar), gone after 2.5 s. */

    /**
     * Two cameras with the same side (both LEFT or both RIGHT): their files would not form stereo
     * pairs. Returns the warning, or null.
     */
    private String sideConflict() {
        if (master == null) return null;
        List<String> left = new ArrayList<>(), right = new ArrayList<>();
        String own = SyncNet.deviceName(this);
        if (role == ROLE_MASTER_CAMERA) {
            if (Downloads.side == Downloads.SIDE_LEFT) left.add(own);
            if (Downloads.side == Downloads.SIDE_RIGHT) right.add(own);
        }
        for (MasterNode.ClientInfo c : master.clients.values()) {
            if (c.lost) continue;
            if (c.side == Downloads.SIDE_LEFT) left.add(c.name);
            if (c.side == Downloads.SIDE_RIGHT) right.add(c.name);
        }
        java.util.Collections.sort(left);
        java.util.Collections.sort(right);
        StringBuilder sb = new StringBuilder();
        if (left.size() > 1) sb.append("Both LEFT: ").append(String.join(", ", left));
        if (right.size() > 1) sb.append(sb.length() > 0 ? "\n" : "").append("Both RIGHT: ").append(String.join(", ", right));
        return sb.length() == 0 ? null : sb + "\n(the file names will not pair)";
    }

    // same-side warning only when it arises (user 2026-11-22): red note on the CONTROL phone, and the text
    // to all cameras (STATE 55); a camera shows it when it is named
    private String sideWarnSeen = "-";

    private void checkSideWarning() {
        if (master != null) {
            String sc = sideConflict();
            String w = sc == null ? "-" : sc;
            if (!w.equals(master.sideWarn)) {
                master.sideWarn = w;
                master.lookChanged(sc == null ? "sides ok" : "same side: " + sc.replace("\n", " "));
                if (sc != null) showRedNote(sc);
            }
        } else if (camera != null) {
            String w = camera.sideWarn;
            if (!w.equals(sideWarnSeen)) {
                sideWarnSeen = w;
                if (!"-".equals(w) && w.contains(SyncNet.deviceName(this))) showRedNote(w);
            }
        }
    }

    private void showNote(String msg) {
        if (previewArea == null) return;
        if (noteView == null) {
            noteView = new TextView(this);
            noteView.setTextSize(15);
            noteView.setTextColor(Color.WHITE);
            noteView.setPadding(dp(12), dp(6), dp(12), dp(6));
            noteView.setBackground(rounded(0xB0000000, 0));
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-2, -2,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(64);
            previewArea.addView(noteView, lp);
        }
        noteView.setText(msg);
        noteView.setBackground(rounded(0xB0000000, 0));
        noteView.setVisibility(android.view.View.VISIBLE);
        noteView.bringToFront();
        ui.removeCallbacks(hideNote);
        ui.postDelayed(hideNote, 2500);
    }

    /** Thumbnail of the last photo / recording, from the preview. */
    private void grabThumb() {
        if (previewView == null || thumb == null) return;
        // getBitmap() ignores the view transform: the image comes in the phone's natural
        // (portrait) orientation - turn it like the preview
        int rot = displayRotationDeg();
        int w = previewView.getWidth() / 8, h = previewView.getHeight() / 8;
        boolean side = rot == 90 || rot == 270;
        android.graphics.Bitmap raw = previewView.getBitmap(side ? h : w, side ? w : h);
        if (raw == null) return;
        android.graphics.Matrix m = new android.graphics.Matrix();
        if (side) m.postRotate(rot - 180);
        else if (rot == 180) m.postRotate(180);
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
        // photo: only the part that is saved (the same crop as the black bars of the mask)
        boolean photoNow = master != null ? master.photoMode : camera != null && camera.photoMode;
        if (photoNow && mask.aspect > 0) {
            int bw = bm.getWidth(), bh = bm.getHeight();
            int cw = bw, ch = (int) Math.round(bw / mask.aspect);
            if (ch > bh) {
                ch = bh;
                cw = (int) Math.round(bh * mask.aspect);
            }
            bm = android.graphics.Bitmap.createBitmap(bm, (bw - cw) / 2, (bh - ch) / 2, Math.max(1, cw), Math.max(1, ch));
        }
        shownJpeg = null;                     // only the preview grab until the file is saved
        thumb.setImageBitmap(bm);
    }

    // ---- full-screen photo viewer (tap on the thumbnail)

    private PhotoViewer viewer;
    private Button[] fmtButtons;
    private Button keepButton, appJpegButton, jpegQButton, sharpButton;
    private EditText aeClipEdit;
    private android.net.Uri shownJpeg;        // the saved JPEG behind the shown thumbnail, or null

    private void openViewer() {
        if (viewer != null || thumb == null) return;
        android.graphics.drawable.Drawable d = thumb.getDrawable();
        android.graphics.Bitmap bm = d instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) d).getBitmap() : null;
        viewer = new PhotoViewer(this, shownJpeg != null ? bm : null, shownJpeg, () -> {
            if (viewer != null) rootFrame.removeView(viewer);
            viewer = null;
        });
        rootFrame.addView(viewer, new android.widget.FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    public void onBackPressed() {
        if (viewer != null) viewer.close();
        else if (aePanel != null && aePanel.isShowing()) aePanel.dismiss();
        else super.onBackPressed();
    }

    /** Photo chip label: megapixels of this phone's photo stream (e.g. "12 MP"), or "photo". */
    private String photoMp() {
        android.util.Size z = syncCamera != null ? syncCamera.photoStreamSize() : null;
        return z == null ? "photo" : Math.round(z.getWidth() * (double) z.getHeight() / 1e6) + " MP";
    }

    /** Highest video bitrate of the selected codec (encoder limit, at most 200 Mbit/s). */
    private int maxBr() {
        return VideoRecorder.maxBitrate(master != null && master.hevc ? android.media.MediaFormat.MIMETYPE_VIDEO_HEVC
                : android.media.MediaFormat.MIMETYPE_VIDEO_AVC);
    }

    private void updateBitrateLabel() {
        TextView label = (TextView) ((LinearLayout) bitrateEdit.getParent()).getChildAt(0);
        label.setText(String.format(Locale.US, "video Mbit/s (0 = auto, max %d)", maxBr() / 1_000_000));
    }

    // Format tiles (2026-11-17): JPEG / RAW / RAW+JPEG / HDR / Astro. HDR and Astro are both the burst
    // format (3); HDR = Auto or 2-frame (hdrMode), Astro = MODE_ASTRO. "1 frame" removed (Auto with
    // no longer frames needed is exactly that).
    static final String[] FORMAT_TILES = {"JPEG", "RAW", "RAW+JPEG", "HDR", "Astro"};

    private int formatTile() {
        if (master == null || master.photoFormat < 3) return master == null ? 0 : master.photoFormat;
        return master.hdrMode == PhotoBurst.MODE_ASTRO ? 4 : 3;
    }

    private void setFormatTile(int k) {
        if (k < 3) {
            setFormat(k);
            return;
        }
        if (k == 4) master.hdrMode = PhotoBurst.MODE_ASTRO;
        else master.hdrMode = PhotoBurst.MODE_AUTO;
        setFormat(3);
    }

    private void setFormat(int k) {
        master.photoFormat = k;
        updateNight();
        if (master.effFormat() == 3) master.hdrPlan = computeHdrPlan();
        master.lookChanged("photo format " + (k == 3 ? burstLabel() : PhotoCapture.FORMATS[k]));
        savePhotoPrefs();
        refreshPhotoButtons();
    }

    /** Photo / video mode, format, aspect and bracketing survive app restarts and updates. */
    private void savePhotoPrefs() {
        if (master == null) return;
        getSharedPreferences("master", MODE_PRIVATE).edit()
                .putBoolean("cbr", master.cbr)
                .putInt("previewW", master.previewW)
                .putBoolean("fillLost", master.fillLost)
                .putBoolean("tlOn", tlOn)
                .putInt("tlAe", tlAeMode)
                .putString("tlInterval", tlIntervalEdit != null ? tlIntervalEdit.getText().toString() : "10")
                .putString("tlFrames", tlFramesEdit != null ? tlFramesEdit.getText().toString() : "100")
                .putBoolean("photoMode", master.photoMode)
                .putInt("photoFormat", master.photoFormat)
                .putInt("photoAspect", master.photoAspect)
                .putInt("hdrMode", master.hdrMode)
                .putInt("hdrMax", hdrMaxFrames)
                .putInt("brightness", master.brightness)
                .putBoolean("brightV2", true)
                .putBoolean("natural", master.natural)
                .putInt("slowest", slowest)
                .putInt("singleSlowest", singleSlowest)
                .putString("singleIso", singleIsoEdit != null ? singleIsoEdit.getText().toString() : "800")
                .putBoolean("nightOn", nightOn)
                .putString("longIso", longIsoEdit != null ? longIsoEdit.getText().toString() : "800")
                .putBoolean("keepBurstFrames", master.keepBurstFrames)
                .putBoolean("appJpeg", master.appJpeg)
                .putInt("jpegQuality", master.jpegQuality)
                .putInt("sharpLevel", master.sharpLevel)
                .putInt("clarityLevel", master.clarityLevel)
                .putInt("gpuMode", master.gpuMode)
                .putInt("flicker", flicker)
                .putInt("astroTime", astroSeconds)
                .putInt("astroWb", astroWb)
                .putInt("astroStep", astroStepIdx)
                .putString("aeClip", aeClipEdit != null ? aeClipEdit.getText().toString() : "0.1")
                .putString("standbyS", standbyEdit != null ? standbyEdit.getText().toString() : "120")
                .putBoolean("standbyV2", true)
                .apply();
    }

    /** Labels of the photo setting tiles (the selected format is marked). */
    private void refreshPhotoButtons() {
        if (master == null) return;
        if (fmtButtons != null) {
            for (int i = 0; i < fmtButtons.length; i++) {
                String name = i == 3 ? burstLabel() : PhotoCapture.FORMATS[i];
                fmtButtons[i].setText((i == master.photoFormat ? "\u2713 " : "") + name);
            }
        }
        if (keepButton != null) keepButton.setText("Save burst single DNGs: " + (master.keepBurstFrames ? "ON" : "OFF"));
        if (slowButton != null) slowButton.setText("Slowest shutter: " + slowestText());
        if (motionButton != null) motionButton.setText("Movement: " + (master.natural ? "natural blur" : "sharp"));
        if (brightButton != null) brightButton.setText("Brightness: " + (master.brightness == 1 ? "bright" : "night look"));
        if (jpegQButton != null) jpegQButton.setText("JPEG quality: " + master.jpegQuality);
        if (sharpButton != null) sharpButton.setText("Sharpening: " + MergeCore.SHARP_NAMES[master.sharpLevel]);
        if (astroButton != null) astroButton.setText("Astro: " + astroSeconds / 60 + " min");
        if (astroWbButton != null) astroWbButton.setText("Astro WB: " + ASTRO_WB[astroWb]);
        if (appJpegButton != null) appJpegButton.setText(master.appJpeg ? "Photo JPEG: app (from full-res YUV)" : "Photo JPEG: camera");
        refreshSettings();
    }

    /** The HDR burst tile / chip name with its long-frame mode. */
    String burstLabel() {
        if (master == null) return "HDR";
        if (master.hdrMode == PhotoBurst.MODE_ASTRO) return "Astro";
        return "HDR ≤" + hdrMaxFrames;
    }

    // ---- HDR plan (master): longer frames for the dark parts, from the fast frame's RAW statistics

    static final double DARK_TARGET = 0.04;   // dark-half level in the longest frame (lamp test: 1/31 s ISO 201 good)
    // HDR: at most 3 EV between the exposures (2026-11-17, was 4 EV: the noise jumps x4.2 where a longer
    // frame reaches its clip limit, x2.9 with 3 EV); the user limits the number of longer frames
    // (hdrMaxFrames 1..3 = at most 3 / 6 / 9 EV) - "up to 1" replaces the old 2-frame mode (5 EV)
    static final double MAX_STEP = 8;
    static final int NIGHT_MAX = Integer.getInteger("nightMax", 15);   // averaging frames in dark scenes
    // NOISE RULE (item 19, 2026-11-18): the ISO the scene needs at the longest exposure (last AE) / this
    // = the number of frames to average (noise of N frames at ISO x ~ one frame at ISO x / N); a flat
    // dim room at ISO 1848 (#54) gets ~9 frames, ISO 400 two, daylight one
    static final double NIGHT_ISO = 200;

    private int noiseFrames() {
        if (aeIsoNeed <= 0) return 1;
        return (int) Math.max(1, Math.min(NIGHT_MAX, Math.ceil(aeIsoNeed / NIGHT_ISO - 0.3)));
    }

    /** HDR mode used for the burst: the night mode of JPEG / RAW uses Auto. */
    private int effHdrMode() {
        return master.effHdrMode();
    }

    /** After AE / a format change: JPEG and RAW switch to the night mode (averaging burst) when too dark. */
    private void updateNight() {
        int was = master.nightOut;
        boolean wasSingle = singleFrame();
        double eiWas = master.exposureNs / 1e6 * master.iso;
        // Night only when the user switched it on, and then only when the light is short at the limits
        master.nightOut = master.photoMode && master.photoFormat < 3 && nightOn && singleShortEv() > 0.1
                && noiseFrames() >= 2 ? master.photoFormat : -1;
        if (was != master.nightOut) {
            master.lookChanged(master.nightOut >= 0
                    ? String.format(Locale.US, "too dark for one frame (ISO %.0f needed): %s Night - %d frames averaged",
                    aeIsoNeed, PhotoCapture.FORMATS[master.photoFormat], noiseFrames())
                    : "normal " + PhotoCapture.FORMATS[master.photoFormat]);
        }
        // the exposure limits changed (single photo: shutter limit + ISO 3200; Night / HDR: frame time +
        // Max ISO): the same light with the new limits - a single photo gets all the light the scene needs
        boolean single = singleFrame();
        if (single != wasSingle && !aeRunning && master.exposureNs > 0) {
            setLight(single && aeIsoNeed > 0 ? aeIsoNeed * maxExposureMs() : eiWas);
        }
    }
    // astro light frames (between the short reference and the long frames): steps of at most 4 / 3 /
    // 1.5 EV (setting; item 7 test 2026-11-17: the lamp glows were stitched from frames 8x apart)
    static final double[] ASTRO_STEPS = {16, 8, 2.83};
    static final String[] ASTRO_STEP_NAMES = {"4 EV", "3 EV", "1.5 EV"};
    private int astroStepIdx = 1;
    private int hdrMaxFrames = 3;
    private int slowest;                      // 0 longest (frame time - margin), 1 = 1/60 s, 2 = 1/125 s
    private Button slowButton, motionButton, brightButton, astroButton;
    private int astroSeconds = 60;                 // astro series: 60 or 120 s of long frames
    static final int ASTRO_MAX_FRAMES = 16;
    // astro white balance (user choice, 2026-11-16: "neutral sky" default): the camera's own presets,
    // so every phone uses its correct values for that light (also different models); "auto" = shared
    static final String[] ASTRO_WB = {"neutral sky", "warm", "daylight", "auto (shared)"};
    static final int[] ASTRO_WB_PRESET = {android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, 0};
    private int astroWb = 0;
    private Button astroWbButton;

    /** Astro with a fixed white balance preset (not "auto"). */
    private boolean astroPresetWb() {
        return master != null && master.photoFormat == 3 && master.hdrMode == PhotoBurst.MODE_ASTRO
                && ASTRO_WB_PRESET[astroWb] != 0;
    }

    private void applyAstroWb() {
        master.wbMode = SyncCamera.WB_PRESET;
        master.awbPreset = ASTRO_WB_PRESET[astroWb];
        master.lookChanged("astro WB: " + ASTRO_WB[astroWb]);
    }
    private EditText longIsoEdit;
    private String planText = "";

    private long slowestNs() {
        long frameMax = master.periodNs - SyncCamera.EXPOSURE_MARGIN_NS;
        return slowest == 1 ? Math.min(frameMax, 16_666_667L) : slowest == 2 ? Math.min(frameMax, 8_000_000L) : frameMax;
    }

    private String slowestText() {
        if (master == null) return "longest";
        long ns = slowestNs();
        return (slowest == 0 ? "longest " : "") + String.format(Locale.US, "1/%.0f s", 1e9 / ns);
    }

    /**
     * The plan of longer frames for the current scene ("exp:iso;..." or "-"), also sets planText.
     * Light needed: the dark half of the fast frame (8x8 block means) up to DARK_TARGET; per frame
     * exposure first (up to the slowest shutter), then ISO (up to the max). Auto: steps of at most
     * 4 EV (one frame per step); the longest frame twice when its ISO is high (less noise).
     */
    private String computeHdrPlan() {
        planText = "";
        int hm = effHdrMode();
        // CONTROL only (no camera of its own, 2026-11-24: Night / HDR plan was always empty): the plan from the
        // camera phones' measurements; astro still needs the own camera's histogram
        if (master.effFormat() != 3 || hm == PhotoBurst.MODE_SINGLE || (syncCamera == null && hm == PhotoBurst.MODE_ASTRO)) {
            return "-";
        }
        if (hm == PhotoBurst.MODE_ASTRO) return computeAstroPlan();
        float dark = jointRawDark();                    // joint: the darkest phone
        long e0 = Math.min(master.exposureNs, master.periodNs - SyncCamera.EXPOSURE_MARGIN_NS);
        int i0 = master.iso;
        // (no RAW statistics yet, e.g. right after the night switch: no contrast frames, the noise
        // rule still applies)
        double need = Float.isNaN(dark) || master.nightOut >= 0 ? 1 : DARK_TARGET / Math.max(dark, 1e-5);
        int nNoise = noiseFrames();
        if (need < 2) {
            if (nNoise >= 2) {
                // NOISE RULE (item 19): a dim but flat scene - N frames like the reference, averaged
                StringBuilder pl = new StringBuilder();
                for (int k = 1; k < nNoise; k++) {
                    if (pl.length() > 0) pl.append(';');
                    pl.append(e0).append(':').append(i0);
                }
                planText = String.format(Locale.US, " (x%d averaged: noise, ISO %.0f needed)", nNoise, aeIsoNeed);
                return pl.toString();
            }
            planText = " (no longer frames needed)";
            return "-";
        }
        // (the old "longest frame twice" now counts against the user's limit of longer frames)
        int isoMax = (int) Math.max(i0, parse(longIsoEdit, 800));
        if (camChars != null) {
            android.util.Range<Integer> r = camChars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (r != null) isoMax = Math.min(isoMax, r.getUpper());
        }
        long eMax = Math.max(e0, Math.round(flickerFloor(slowestNs() / 1e6) * 1e6));   // anti-flicker: whole steps
        java.util.List<Double> ratios = new java.util.ArrayList<>();
        // at most maxN longer frames, steps of at most MAX_STEP: beyond maxN steps the range is
        // limited (the shadows stay darker - the user's choice), never a bigger step
        int maxN = master.hdrMode == PhotoBurst.MODE_TWO ? 1 : Math.max(1, Math.min(3, hdrMaxFrames));
        double reach = Math.min(need, Math.pow(MAX_STEP, maxN));
        int n = reach <= MAX_STEP * 1.0001 ? 1 : (int) Math.ceil(Math.log(reach) / Math.log(MAX_STEP) - 1e-6);
        n = Math.min(maxN, n);
        for (int k = 1; k <= n; k++) ratios.add(Math.pow(reach, k / (double) n));
        StringBuilder plan = new StringBuilder(), txt = new StringBuilder();
        long lastE = -1;
        int lastI = -1;
        for (double r : ratios) {
            double light = e0 * (double) i0 * r;
            long e = Math.min(eMax, Math.max(e0, Math.round(light / i0)));
            int iso = (int) Math.max(i0, Math.min(isoMax, Math.round(light / e)));
            if (flicker > 0) {                                   // item 32: longer frames on whole steps
                double[] fr = flickerRound(e / 1e6, iso, eMax / 1e6, 50, isoMax);
                e = Math.min(eMax, Math.round(fr[0] * 1e6));
                iso = (int) Math.round(fr[1]);
            }
            if (e == lastE && iso == lastI) continue;           // limits reached: same frame again
            lastE = e;
            lastI = iso;
            if (plan.length() > 0) plan.append(';');
            plan.append(e).append(':').append(iso);
            txt.append(String.format(Locale.US, " + 1/%.0f s ISO %d", 1e9 / e, iso));
        }
        int planned = plan.length() == 0 ? 0 : plan.toString().split(";").length;
        // NIGHT FRAMES (like Google, 2026-11-17): the longest frame at its limits (frame time, max ISO)
        // and still too little light -> N frames of it, aligned and averaged (noise / sqrt(N)); N = the
        // missing light, 2..NIGHT_MAX; not counted as "extra frames" (those are for the dynamic range)
        double got = lastE > 0 ? lastE * (double) lastI / (e0 * (double) i0) : 1;
        double missing = need / Math.max(1, got);
        int nNight = 1;
        String why = "";
        if (hm == PhotoBurst.MODE_AUTO && lastE >= eMax && lastI >= isoMax && missing > 1.3) {
            nNight = (int) Math.max(2, Math.min(NIGHT_MAX, Math.ceil(missing)));
            why = "too dark";
        }
        if (hm == PhotoBurst.MODE_AUTO && nNoise > nNight) {           // item 19: the noise decides
            nNight = nNoise;
            why = "noise";
        }
        if (nNight >= 2 && lastE > 0) {
            for (int k = 1; k < nNight; k++) plan.append(';').append(lastE).append(':').append(lastI);
            txt.append(String.format(Locale.US, " (x%d averaged: %s)", nNight, why));
            planText = txt.toString();
            return plan.toString();
        }
        if (hm == PhotoBurst.MODE_AUTO && lastI > 400 && planned < maxN) {
            plan.append(';').append(lastE).append(':').append(lastI);   // a second long frame: less noise
            txt.append(String.format(Locale.US, " + 1/%.0f s ISO %d", 1e9 / lastE, lastI));
        }
        planText = txt.toString();
        return plan.length() == 0 ? "-" : plan.toString();
    }

    static final double ASTRO_SKY_MAX = 0.12;      // median of a long frame at most this (moon, city light)

    /**
     * Astro series (tripod): long frames of up to 16 s (each phone clamps to its own maximum) for
     * the set time (1 or 2 min), at the ISO where the sensor's read noise reaches its floor (dark-frame
     * test 2026-11-16: Pixel 7a 800, Pixel 4 400; at most the max-ISO setting), lowered when the
     * long frame's median would exceed ASTRO_SKY_MAX. When the long frames would clip more than the
     * allowed fraction (lights), short highlight frames follow: the current (AE) exposure and
     * steps of at most MAX_STEP up towards the long frame. Measured on the current preview RAW
     * (press AE first).
     */
    private String computeAstroPlan() {
        float med = jointRawMedian();                   // joint: the brightest sky of all phones
        float[] hist = syncCamera.rawHist;
        if (Float.isNaN(med) || hist == null) return "-";
        long e0 = Math.min(master.exposureNs, master.periodNs - SyncCamera.EXPOSURE_MARGIN_NS);
        int i0 = master.iso;
        long eL = 16_000_000_000L;
        int isoMin = 50;
        if (camChars != null) {
            android.util.Range<Long> er = camChars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            if (er != null) eL = Math.min(eL, er.getUpper());
            android.util.Range<Integer> ir = camChars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (ir != null) isoMin = ir.getLower();
        }
        int iso = android.os.Build.MODEL.contains("Pixel 4") ? 400 : 800;
        iso = (int) Math.max(isoMin, Math.min(iso, parse(longIsoEdit, 800)));
        double scale = eL * (double) iso / (e0 * (double) i0);
        if (med * scale > ASTRO_SKY_MAX) {                  // bright sky: lower ISO first, then exposure
            double f = ASTRO_SKY_MAX / (med * scale);
            int iso2 = (int) Math.max(isoMin, Math.round(iso * f));
            f *= iso / (double) iso2;
            iso = iso2;
            if (f < 1) eL = Math.max(1_000_000_000L, Math.round(eL * f));
            scale = eL * (double) iso / (e0 * (double) i0);
        }
        // at most ASTRO_MAX_FRAMES: a camera whose longest exposure is short (Xreal Beam Pro 0.385 s, front
        // cameras 1 s) would otherwise get 60-155 frames of ~25 MB each (storage, merge time)
        int n = (int) Math.max(1, Math.min(ASTRO_MAX_FRAMES, Math.round(astroSeconds * 1e9 / eL)));
        StringBuilder plan = new StringBuilder();
        for (int k = 0; k < n; k++) {
            if (k > 0) plan.append(';');
            plan.append(eL).append(':').append(iso);
        }
        // lights: the fraction of the preview samples that would clip in a long frame
        double tot = 0, clip = 0;
        for (int b = 0; b < hist.length; b++) {
            tot += hist[b];
            if (b / (double) (hist.length - 1) * scale >= 0.98) clip += hist[b];
        }
        int shorts = 0;
        if (tot > 0 && clip / tot > aeClipFraction() && scale > 1.5) {
            int m = (int) Math.ceil(Math.log(scale) / Math.log(ASTRO_STEPS[astroStepIdx]) - 1e-6);
            for (int j = 0; j < m; j++) {
                double light = e0 * (double) i0 * Math.pow(scale, j / (double) m);
                long e = Math.max(100_000L, Math.round(light / iso));
                plan.append(';').append(e).append(':').append(iso);
                shorts++;
            }
        }
        planText = String.format(Locale.US, ": %d x %.1f s ISO %d%s", n, eL / 1e9, iso,
                shorts > 0 ? " + " + shorts + " short frame" + (shorts > 1 ? "s" : "") + " for lights" : "");
        return plan.toString();
    }

    /** AE: allowed clipped fraction of the image (setting in %, default 0.1 %). */
    private double aeClipFraction() {
        return aeClipEdit == null ? 0.001 : Math.max(0.0, Math.min(10.0, parse(aeClipEdit, 0.1))) / 100.0;
    }

    private void loadPhotoPrefs() {
        android.content.SharedPreferences sp = getSharedPreferences("master", MODE_PRIVATE);
        master.cbr = sp.getBoolean("cbr", false);
        master.previewW = PREVIEW_W[previewIdx(sp.getInt("previewW", 1920))];
        master.fillLost = sp.getBoolean("fillLost", false);
        tlOn = sp.getBoolean("tlOn", false);
        tlAeMode = Math.max(0, Math.min(1, sp.getInt("tlAe", 0)));
        if (tlIntervalEdit != null) tlIntervalEdit.setText(sp.getString("tlInterval", "10"));
        if (tlFramesEdit != null) tlFramesEdit.setText(sp.getString("tlFrames", "100"));
        master.photoMode = sp.getBoolean("photoMode", false);
        master.photoFormat = Math.max(0, Math.min(PhotoCapture.FORMATS.length - 1, sp.getInt("photoFormat", 0)));
        master.photoAspect = Math.max(0, Math.min(PhotoCapture.ASPECTS.length - 1, sp.getInt("photoAspect", 0)));
        master.hdrMode = Math.max(0, Math.min(PhotoBurst.MODES.length - 1, sp.getInt("hdrMode", PhotoBurst.MODE_AUTO)));
        hdrMaxFrames = Math.max(1, Math.min(3, sp.getInt("hdrMax", 3)));
        if (master.hdrMode == PhotoBurst.MODE_SINGLE) master.hdrMode = PhotoBurst.MODE_AUTO;   // "1 frame" removed
        // ASTRO is never remembered (user 2026-11-17: shots taken by mistake in astro): start in HDR
        if (master.hdrMode == PhotoBurst.MODE_ASTRO) master.hdrMode = PhotoBurst.MODE_AUTO;
        if (master.hdrMode == PhotoBurst.MODE_TWO) {                                         // -> "up to 1"
            master.hdrMode = PhotoBurst.MODE_AUTO;
            if (!sp.contains("hdrMax")) hdrMaxFrames = 1;
        }
        // "natural" is the default since 2026-11-17 (also replaces the old saved default "night look" once)
        int br = sp.getBoolean("brightV2", false) ? sp.getInt("brightness", MergeCore.BRIGHT_NATURAL)
                : (sp.getInt("brightness", 0) == MergeCore.BRIGHT_BRIGHT ? MergeCore.BRIGHT_BRIGHT : MergeCore.BRIGHT_NATURAL);
        master.brightness = br >= 0 && br <= 2 ? br : MergeCore.BRIGHT_NATURAL;
        master.natural = sp.getBoolean("natural", false);
        slowest = Math.max(0, Math.min(2, sp.getInt("slowest", 0)));
        singleSlowest = Math.max(0, Math.min(2, sp.getInt("singleSlowest", 0)));
        if (singleIsoEdit != null) singleIsoEdit.setText(sp.getString("singleIso", "800"));
        nightOn = sp.getBoolean("nightOn", false);
        if (longIsoEdit != null) longIsoEdit.setText(sp.getString("longIso", "800"));
        master.keepBurstFrames = sp.getBoolean("keepBurstFrames", false);
        master.appJpeg = sp.getBoolean("appJpeg", false);
        astroSeconds = sp.getInt("astroTime", 60) >= 120 ? 120 : 60;
        astroWb = Math.max(0, Math.min(ASTRO_WB.length - 1, sp.getInt("astroWb", 0)));
        astroStepIdx = Math.max(0, Math.min(ASTRO_STEPS.length - 1, sp.getInt("astroStep", 1)));
        int jq = sp.getInt("jpegQuality", 95);
        master.jpegQuality = jq == 90 || jq == 100 ? jq : 95;
        master.sharpLevel = Math.max(0, Math.min(MergeCore.SHARP_NAMES.length - 1,
                sp.getInt("sharpLevel", MergeCore.SHARP_DEFAULT)));
        MergeCore.sharpLevel = master.sharpLevel;
        master.clarityLevel = Math.max(0, Math.min(MergeCore.CLARITY_NAMES.length - 1,
                sp.getInt("clarityLevel", MergeCore.CLARITY_DEFAULT)));
        MergeCore.clarityLevel = master.clarityLevel;
        master.gpuMode = Math.max(0, Math.min(MergeCore.GPU_NAMES.length - 1, sp.getInt("gpuMode", 1)));
        MergeCore.gpuMode = master.gpuMode;
        flicker = Math.max(0, Math.min(FLICKER_NAMES.length - 1, sp.getInt("flicker", 0)));
        if (aeClipEdit != null) aeClipEdit.setText(sp.getString("aeClip", "0.1"));
        // the old default 30 s becomes 120 s once (V0.103); a value the user typed later is kept
        String sb = sp.getString("standbyS", "120");
        if (!sp.getBoolean("standbyV2", false) && sb.trim().equals("30")) sb = "120";
        if (standbyEdit != null) standbyEdit.setText(sb);
        refreshPhotoButtons();
    }

    /** Aspect label in the current orientation: landscape 4:3, 3:2, 16:9, 1:1 - portrait 3:4, 2:3, 9:16, 1:1. */
    private String aspectLabel(int k) {
        String a = PhotoCapture.ASPECTS[Math.max(0, Math.min(PhotoCapture.ASPECTS.length - 1, k))];
        if (!portrait || a.equals("1:1")) return a;
        String[] s = a.split(":");
        return s[1] + ":" + s[0];
    }

    private void setAspect(int k) {
        master.photoAspect = k;
        master.lookChanged("photo aspect " + PhotoCapture.ASPECTS[k]);
        savePhotoPrefs();
    }

    private TextureView buildCameraUi() {
        buildFrame();
        rightLabel("CAMERA");
        rightInfo = mono(13);
        rightInfo.setTextColor(Color.WHITE);
        rightInfo.setPadding(0, dp(12), 0, 0);
        rightCol.addView(rightInfo);
        thumb = new android.widget.ImageView(this);
        thumb.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        thumb.setOnClickListener(v -> openViewer());
        LinearLayout.LayoutParams thl = new LinearLayout.LayoutParams(dp(120), dp(68));
        thl.setMargins(0, dp(10), 0, 0);
        rightCol.addView(thumb, thl);
        rightCol.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        TextView ctl = rightLabel("controlled by CONTROL");
        ctl.setTextSize(12);
        gearButton();
        rememberLandscapeColumn();
        section(settingsContent, "Info");
        header = mono(13);
        header.setTextColor(Color.WHITE);
        settingsContent.addView(header);
        table = mono(13);
        table.setTextColor(0xFFD0D0D0);
        settingsContent.addView(table);
        // item 38: tap to focus here too; the distance goes to the CONTROL phone, which sets it for all
        previewView.setOnTouchListener((v, e) -> {
            if (settingsPanel.getVisibility() == android.view.View.VISIBLE) {
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) settingsPanel.setVisibility(android.view.View.GONE);
                return true;
            }
            if (e.getAction() != android.view.MotionEvent.ACTION_UP || syncCamera == null || camera == null) return true;
            float u = e.getX() / previewView.getWidth(), w = e.getY() / previewView.getHeight();
            showFocusSquare(e.getX(), e.getY(), 0);
            syncCamera.tapFocus(u, w, displayRotationDeg(), (d, sharp) -> ui.post(() -> {
                buzz(3);
                showFocusSquare(Float.NaN, Float.NaN, sharp ? 1 : 2);
                camera.focusTapD = d;
                camera.focusTapSharp = sharp ? 1 : 0;
                camera.focusSeq = (int) (SystemClock.elapsedRealtime() & 0x3fffffff) | 1;
            }));
            return true;
        });
        return previewView;
    }

    /**
     * Sizes the preview to the aspect of the stream (whole image visible), positions the overlays
     * and rotates the camera image to the display. force = also when nothing seems to have changed.
     */
    private void relayout(boolean force) {
        if (previewArea == null) return;
        int aw = previewArea.getWidth(), ah = previewArea.getHeight();
        if (aw == 0 || ah == 0) return;
        android.util.Size ps = syncCamera != null ? syncCamera.previewSize() : null;
        boolean photo = master != null ? master.photoMode : camera != null && camera.photoMode;
        int asp = master != null ? master.photoAspect : camera != null ? camera.photoAspect : 0;
        int rot = displayRotationDeg();
        // height of the chip rows (they may wrap to another row: the layout is checked again)
        bottomBar.measure(android.view.View.MeasureSpec.makeMeasureSpec(Math.max(1, aw - dp(16)),
                android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(0,
                android.view.View.MeasureSpec.UNSPECIFIED));
        int barH = bottomBar.getMeasuredHeight();
        String key = aw + "x" + ah + " " + ps + " " + photo + " " + asp + " " + rot + " " + portrait + " " + barH;
        if (!force && key.equals(layoutKey)) return;
        layoutKey = key;
        // aspect of the image on the screen (portrait: the sensor image turned upright)
        double sensorAspect = ps != null ? ps.getWidth() / (double) ps.getHeight() : photo ? 4.0 / 3 : 16.0 / 9;
        double aspect = portrait ? 1 / sensorAspect : sensorAspect;
        int vh = ah, vw = (int) Math.round(ah * aspect);
        if (vw > aw) {
            vw = aw;
            vh = (int) Math.round(aw / aspect);
        }
        int x, y;
        if (portrait) {
            // full width; video at the bottom (SYNC in the strip above), photo below the SYNC strip
            x = (aw - vw) / 2;
            int strip = dp(64);
            y = photo ? Math.min(strip, ah - vh) : ah - vh;
        } else {
            x = (aw - vw) / 2;
            y = (ah - vh) / 2;
        }
        for (android.view.View v : new android.view.View[]{previewView, mask}) {
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(vw, vh);
            lp.leftMargin = x;
            lp.topMargin = y;
            v.setLayoutParams(lp);
        }
        double cropAspect = PhotoCapture.ASPECT_VALUES[Math.max(0, Math.min(3, asp))];
        mask.aspect = photo ? (portrait ? 1 / cropAspect : cropAspect) : 0;
        mask.invalidate();
        // photo and video share one layout: status on top, chips over the lower edge of the image
        // (the old left column with small vertical chips was hard to hit)
        leftCol.setVisibility(android.view.View.GONE);
        topBar.setVisibility(android.view.View.VISIBLE);
        bottomBar.setVisibility(android.view.View.VISIBLE);
        android.widget.FrameLayout.LayoutParams bl;
        // portrait photo: chips below the image - only when they fit there (CONTROL only, Beam Pro
        // 2026-11-22: the last row was hidden behind the shutter bar); else over the image's lower edge
        boolean below = y + vh + dp(8) + barH <= ah;
        if (portrait && photo && below) {
            bl = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.START);
            bl.setMargins(dp(8), y + vh + dp(8), dp(8), 0);
        } else {                                      // video: over the lower edge of the image
            bl = new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
            bl.setMargins(dp(8), 0, dp(8), dp(8));
        }
        bottomBar.setLayoutParams(bl);
        placeStandbyButton(x, y, vh);
        if (ps != null) configureTransform(vw, vh, ps, rot);
    }

    /** Standard Camera2 TextureView transform: sensor image upright in a landscape view. */
    private void configureTransform(int vw, int vh, android.util.Size ps, int rotDeg) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        android.graphics.RectF view = new android.graphics.RectF(0, 0, vw, vh);
        android.graphics.RectF buf = new android.graphics.RectF(0, 0, ps.getHeight(), ps.getWidth());
        float cx = view.centerX(), cy = view.centerY();
        if (rotDeg == 90 || rotDeg == 270) {
            buf.offset(cx - buf.centerX(), cy - buf.centerY());
            m.setRectToRect(view, buf, android.graphics.Matrix.ScaleToFit.FILL);
            float s = Math.max((float) vh / ps.getHeight(), (float) vw / ps.getWidth());
            m.postScale(s, s, cx, cy);
            m.postRotate(rotDeg - 180, cx, cy);
        } else if (rotDeg == 180) {
            m.postRotate(180, cx, cy);
        }
        previewView.setTransform(m);
    }

    /** Chips: quick view of the settings; tap = open the matching settings section. */
    private void setChips(ViewGroup parent, List<TextView> chips, List<String[]> items, boolean vertical) {
        if (chips.size() != items.size()) {
            for (TextView t : chips) parent.removeView(t);
            chips.clear();
            for (String[] it : items) {
                TextView t = chip(it[0]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(vertical ? -1 : -2, -2);
                lp.setMargins(vertical ? 0 : dp(4), vertical ? dp(8) : 0, dp(4), 0);
                parent.addView(t, lp);
                chips.add(t);
            }
        }
        for (int i = 0; i < items.size(); i++) {
            final String[] it = items.get(i);
            TextView t = chips.get(i);
            t.setText(it[0]);
            if ("ae".equals(it[1])) {
                aeChip = t;
                t.setOnLongClickListener(v -> {           // item 22: the brightness panel without a new AE
                    showAePanel();
                    return true;
                });
            } else {
                t.setOnLongClickListener(null);
            }
            t.setOnClickListener(it[1] == null ? null : v -> {
                if (it[1].equals("aspect")) setAspect((master.photoAspect + 1) % PhotoCapture.ASPECTS.length);
                else if (SLIDERS.contains(it[1])) showSlider(it[1], v);
                else if (it[1].equals("ae")) {
                    aeByUser = true;
                    autoExposure();
                }
                else if (it[1].equals("format")) setFormatTile((formatTile() + 1) % FORMAT_TILES.length);
                else if (it[1].equals("lens")) setMasterLens(nextLens(master.lens));
                else if (it[1].equals("lensOwn")) {
                    int k = nextLens(curLens != null ? curLens.kind : Lenses.MAIN);
                    ownLens = camera != null && k == camera.lens ? -1 : k;
                }
                else openSettings(it[1]);
            });
        }
    }

    private String wbChipText() {
        if (astroPresetWb() && master.wbMode == SyncCamera.WB_PRESET) return "WB astro " + ASTRO_WB[astroWb];
        if (sharedWb()) return master.wbMode == SyncCamera.WB_COPY ? "WB shared ✓" : "WB shared (AE)";
        if (master.wbMode == SyncCamera.WB_AUTO) return "WB auto";
        if (master.wbMode == SyncCamera.WB_PRESET) return "WB " + WB_NAMES[wbChoice];
        if (WB_NAMES[wbChoice].equals("K")) return "WB " + kelvinEdit.getText() + " K";
        return master.wbGains != null ? "WB copy ✓" : "WB copy ?";
    }

    /**
     * Astro plan "exposureNs:iso;..." -> {"16 s ×4 + 2", "ISO 400"} (the longest frames x count + the
     * short frames for lights), or {"Astro", "press AE"} without a plan.
     */
    static String[] astroChips(String plan) {
        if (plan == null || plan.isEmpty() || plan.equals("-")) return new String[]{"Astro", "press AE"};
        long maxE = 0;
        int nLong = 0, n = 0, isoL = 0;
        String[] fr = plan.split(";");
        for (String f : fr) {
            String[] p = f.split(":");
            if (p.length < 2) continue;
            long e = Long.parseLong(p[0].trim());
            n++;
            if (e > maxE) {
                maxE = e;
                nLong = 1;
                isoL = Integer.parseInt(p[1].trim());
            } else if (e == maxE) {
                nLong++;
            }
        }
        if (n == 0) return new String[]{"Astro", "press AE"};
        String e = exposureText(maxE) + " ×" + nLong + (n > nLong ? " + " + (n - nLong) : "");
        return new String[]{e, "ISO " + isoL};
    }

    private static String exposureText(long ns) {
        double s = ns / 1e9;
        return s >= 0.25 ? String.format(Locale.US, "%.1f s", s) : String.format(Locale.US, "1/%.0f s", 1 / s);
    }

    /** Main screen of the master (every tick). */
    private void updateMasterMain(long worst, String detail) {
        boolean photo = master.photoMode;
        relayout(false);
        videoTab.setBackground(photo ? null : rounded(0xFFE6E6E6, 0));
        videoTab.setTextColor(photo ? Color.WHITE : 0xFF141414);
        photoTab.setBackground(photo ? rounded(0xFFE6E6E6, 0) : null);
        photoTab.setTextColor(photo ? 0xFF141414 : Color.WHITE);
        boolean astroRun = photo && astroRunning(), astroSel = photo && astroSelected();
        shutter.mode = astroRun ? 1 : photo ? 2 : master.record ? 1 : 0;
        shutter.invalidate();
        boolean tl = photo && tlOn && !astroSel;
        shutterLabel.setText(astroRun ? "STOP ASTRO" : tl ? (tlRunning ? String.format(Locale.US, "STOP %d%s",
                tlCount, tlFrames > 0 ? "/" + tlFrames : "") : "TIME-LAPSE") : photo ? String.format(Locale.US, "%s #%04d",
                astroSel ? "ASTRO" : "PHOTO", master.nextPhotoNumber()) : master.record ? "STOP" : "REC");
        shutterLabel.setTextColor(astroRun || astroSel ? 0xFFFFD040 : tl ? 0xFF64B5F6 : Color.WHITE);
        showSync(syncView, worst, detail);
        showSync(leftSync, worst, "");
        if (Downloads.side == Downloads.SIDE_MONO && role == ROLE_MASTER_CAMERA) {
            syncView.setText("\u25CF MONO");                // single phone: no sync to show
            syncView.setTextColor(0xFFB0B0B0);
            leftSync.setText("");
        }
        if (standbyBtn != null) {
            standbyBtn.setVisibility(standby || !master.running ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        if (master.record) {
            long s = Math.max(0, (SystemClock.elapsedRealtimeNanos() - master.recStart) / 1_000_000_000L);
            recView.setText(String.format(Locale.US, "● REC %02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60));
        } else {
            recView.setText("");
        }
        double fps = 1e9 / master.periodNs;
        String focus = master.focusManual ? "Focus " + focusText(master.focusDiopters) : "Focus auto";
        List<String[]> items = new ArrayList<>();
        if (photo) {
            // like the video chip: what is set; tap = the Photo settings (aspect, format)
            items.add(new String[]{aspectLabel(master.photoAspect) + "  ·  " + photoMp() + " "
                    + (master.photoFormat == 3 ? burstLabel() : PhotoCapture.FORMATS[master.photoFormat]
                    + (master.nightOut >= 0 ? "  ·  Night" : "")), "Photo"});
        } else {
            items.add(new String[]{(master.videoHeight == 2160 ? "4K" : "1080p")
                    + String.format(Locale.US, "  %.1f%s fps  ·  %s", fps, master.conformFps > 0
                    ? String.format(Locale.US, " → %d", master.conformFps) : "", master.videoBitrate > 0
                    ? (master.hevc ? "HEVC " : "") + master.videoBitrate / 1_000_000 + " Mb/s"
                    : String.format(Locale.US, (master.hevc ? "HEVC " : "") + "auto %.0f Mb/s",
                    Math.min(maxBr(), (master.videoHeight == 2160 ? 3840.0 * 2160 : 1920.0 * 1080)
                            * Math.round(fps) * 0.32) / 1e6)), "Video"});
        }
        if (Lenses.usable(lenses).size() > 1) items.add(new String[]{lensFor(master.lens).shortLabel(), "lens"});
        String[] ast = photo && master.photoFormat == 3 && master.hdrMode == PhotoBurst.MODE_ASTRO
                ? astroChips(master.hdrPlan) : null;
        // astro: the frames of the series (the preview's exposure / ISO are only for the screen)
        items.add(new String[]{ast != null ? ast[0] : exposureText(master.exposureNs) + (photo ? shortText() : ""), "exposure"});
        items.add(new String[]{ast != null ? ast[1] : "ISO " + master.iso, "iso"});
        items.add(new String[]{wbChipText(), "wb"});
        items.add(new String[]{focus, "focus"});
        items.add(new String[]{aeRunning ? "AE …" : "AE", "ae"});
        setChips(bottomBar, bottomChips, items, false);
        String photoStatus = syncCamera != null ? syncCamera.photoStatus : camerasPhotoStatus();
        if (showAstroCountdown()) return;
        if (photo && !photoStatus.isEmpty()) {                 // short: "photo #1: +12 us" (no path)
            int cut = photoStatus.indexOf(" -> ");
            syncView.append("     " + (cut > 0 ? photoStatus.substring(0, cut) : photoStatus).replace(" us", " µs"));
        }
    }

    /**
     * CONTROL only (no camera of its own, 2026-11-22): the last photo as the camera phones report it,
     * "photo #142: L -14 us, R -11 us" ("saving" until all have it); "" before the first photo.
     */
    private String camerasPhotoStatus() {
        int seq = master.photoSeq;
        if (seq <= 0) return "";
        StringBuilder sb = new StringBuilder("photo #" + seq + ":");
        int done = 0, all = 0;
        List<MasterNode.ClientInfo> cs = new ArrayList<>(master.clients.values());
        cs.sort((p, q) -> Integer.compare(p.side, q.side));
        for (MasterNode.ClientInfo c : cs) {
            if (c.lost) continue;
            all++;
            if (c.photoSeq < seq) continue;
            done++;
            String sd = c.side == Downloads.SIDE_LEFT ? "L" : c.side == Downloads.SIDE_RIGHT ? "R" : modelOf(c.name);
            sb.append(done > 1 ? ", " : " ").append(sd).append(String.format(Locale.US, " %+.0f us", c.photoErrUs));
        }
        if (all == 0) return "";
        // "saving" only shortly after the photo (a restarted camera no longer reports it)
        boolean recent = SystemClock.elapsedRealtime() - master.lastPhotoMs < 20_000;
        if (done < all && !recent && done == 0) return "";
        if (done < all && recent) sb.append(done == 0 ? " saving" : " ...");
        return sb.toString();
    }

    /**
     * ASTRO series running (or just finished): one countdown instead of the sync values (the frame
     * stream is stopped, they mean nothing then) - same on the master and the cameras.
     */
    private boolean showAstroCountdown() {
        String cd = syncCamera != null ? syncCamera.astroCountdown() : null;
        String head = "\u2605 ASTRO  ";
        if (cd == null) {                                  // HDR burst: hold still / merging / saved
            cd = syncCamera != null ? syncCamera.burstNotice() : null;
            head = "\u25cf HDR  ";
        }
        if (cd == null) return false;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(head);
        int s = sb.length();
        sb.append(cd);
        sb.setSpan(new ForegroundColorSpan(0xFFFFD040), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        syncView.setText(sb);
        leftSync.setText("");
        if (rightInfo != null && camera != null) rightInfo.setText("");
        return true;
    }

    /** Main screen of a camera phone (every tick). */
    private void updateCameraMain(long q, String detail) {
        relayout(false);
        showSync(syncView, q, detail);
        showSync(leftSync, q, "");
        recView.setText(camera.record && camera.running ? "● REC" : "");
        android.os.BatteryManager bm = getSystemService(android.os.BatteryManager.class);
        int batt = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        long free = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath())
                .getAvailableBytes();
        VideoRecorder r = syncCamera.recorder();
        double mbPerMin = r != null ? r.bitrate * 60.0 / 8e6 : 600;
        long cerr = camera.clockErrNs;
        if (!showAstroCountdown()) rightInfo.setText(String.format(Locale.US, "Battery %d %%%nFree %.0f GB%n≈ %.0f min video%n%n"
                        + "Phase %s%nClock %s%nRTT   %s", batt, free / 1e9, free / 1e6 / mbPerMin,
                aligner.phaseValid ? String.format(Locale.US, "%+.0f µs", aligner.phaseNs / 1e3) : "-",
                cerr < 0 ? "-" : String.format(Locale.US, "±%d µs", cerr / 1000),
                String.format(Locale.US, "%.1f ms", camera.estimator.compute(SystemClock.elapsedRealtimeNanos())
                        .minRttNs / 1e6)));
        List<String[]> items = new ArrayList<>();
        items.add(new String[]{(camera.photoMode ? "PHOTO " + aspectLabel(camera.photoAspect)
                : (camera.videoHeight == 2160 ? "4K" : "1080p")) + String.format(Locale.US, "  %.1f fps",
                camera.periodNs > 0 ? 1e9 / camera.periodNs : 0), null});
        String[] astC = camera.photoMode && camera.photoFormat == 3 && camera.hdrMode == PhotoBurst.MODE_ASTRO
                ? astroChips(camera.hdrPlan) : null;
        items.add(new String[]{astC != null ? astC[0] + "  " + astC[1]
                : exposureText(Math.max(1, camera.exposureNs)) + "  ISO " + camera.iso, null});
        items.add(new String[]{lookText(syncCamera), null});
        if (curLens != null && Lenses.usable(lenses).size() > 1) {
            items.add(new String[]{"Lens " + curLens.shortLabel() + (ownLens >= 0 ? " (own)" : ""), "lensOwn"});
        }
        setChips(bottomBar, bottomChips, items, false);
    }

    private static double parse(EditText e, double fallback) {
        try {
            return Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void sendState(boolean running, boolean align) {
        double fps = Math.max(1, Math.min(60, parse(fpsEdit, 30)));
        double exp = Math.max(0.01, parse(expEdit, 10));
        // never above the user's Max ISO (item 19, user: "no image, even the first one")
        int iso = (int) Math.max(50, Math.min(manualIsoCap(), parse(isoEdit, 400)));
        if (running != master.running) {
            graph.clear();
            graphShowsPhase = running;
            graph.setTitle(running ? "Phase error vs reference, last 60 s (us)"
                    : "Clock sync residual, last 60 s (us)");
        }
        master.asymNs = parseAuto(asymEdit, 1e3);
        master.videoHeight = parse(videoEdit, 1080) >= 2000 ? 2160 : 1080;
        double mbps = Math.max(0, parse(bitrateEdit, 0));
        master.videoBitrate = mbps <= 0 ? 0 : (int) Math.min(maxBr(), Math.max(1, mbps) * 1e6);
        if (mbps * 1e6 > maxBr()) {
            bitrateEdit.setText(String.valueOf(maxBr() / 1_000_000));
            master.log.add("video bitrate limited to " + maxBr() / 1_000_000 + " Mbit/s");
        }
        if (running && (master.videoHeight == 2160 || master.photoMode) && fps > 29.9) {
            master.log.add((master.photoMode ? "photo mode" : "4K")
                    + ": frames cannot be shorter than 33.333 ms - use fps <= 29.9");
        }
        master.setState(running, align, Math.round(1e9 / fps), Math.round(exp * 1e6), iso);
    }

    // ------------------------------------------------------------------ periodic update

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (master != null) updateMaster();
            if (camera != null) updateCamera();
            ui.postDelayed(this, 250);
        }
    };

    private static int colorFor(double us, boolean lost) {
        if (lost) return GREY;
        us = Math.abs(us);
        if (us < 100) return GREEN;
        if (us < 1000) return YELLOW;
        return RED;
    }

    private static void dot(SpannableStringBuilder sb, int color) {
        int start = sb.length();
        sb.append("● ");
        sb.setSpan(new ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String phaseText(double us) {
        return Math.abs(us) < 10000 ? String.format(Locale.US, "%+.0f", us)
                : String.format(Locale.US, "%+.1fm", us / 1000);
    }

    private static String ledLine(String who, int y, int peak, int progressS, int durationS) {
        String cap = progressS >= 0 ? String.format(Locale.US, "capturing %d/%d s", progressS, durationS) : "";
        return String.format(Locale.US, "%-14s y=%-4s pk=%-4d %s%n", who, y >= 0 ? Integer.toString(y) : "-",
                peak, cap);
    }

    /** Starts / stops the local LED capture when the master's capture number changes. */
    private void checkCapture(int seq, int seconds, boolean running, String info) {
        if (led == null) return;
        if (!running) {
            if (led.isCapturing()) led.stop();
            seenCaptureSeq = seq;
            return;
        }
        if (seenCaptureSeq < 0) {           // first state after start: do not replay an old capture
            seenCaptureSeq = seq;
            return;
        }
        if (seq != seenCaptureSeq) {
            // the analysis stream is only set up for the LED test: the session restarts with it
            // first, then the capture starts
            ledPending = true;
            if (syncCamera.isStreaming() && syncCamera.hasAnalysis()) {
                seenCaptureSeq = seq;
                ledPending = false;
                led.start(seconds, info);
            }
        }
    }

    private int seenPhotoSeq = -1;

    /** Takes the synchronized photo when the master's photo number changes. */
    private android.graphics.Bitmap shownThumb;

    /** Shows the thumbnail of the last saved photo (replaces the quick preview grab). */
    private void refreshThumb() {
        android.graphics.Bitmap b = syncCamera != null ? syncCamera.lastThumb : null;
        if (b != null && b != shownThumb && thumb != null) {
            shownThumb = b;
            shownJpeg = syncCamera.lastJpeg;
            thumb.setImageBitmap(b);
        }
    }

    /** Short vibration: 1 = photo taken, 2 = recording start / stop, 3 = focus set. */
    private void buzz(int kind) {
        try {
            android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
            if (v == null || !v.hasVibrator()) return;
            if (kind == 3) {                                  // focus: a light click
                v.vibrate(android.os.Build.VERSION.SDK_INT >= 29
                        ? android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
                        : android.os.VibrationEffect.createOneShot(20, 150));
                return;
            }
            v.vibrate(kind == 1 ? android.os.VibrationEffect.createOneShot(40, 200)
                    : android.os.VibrationEffect.createWaveform(new long[]{0, 35, 90, 35}, -1));
        } catch (Exception ignored) {
        }
    }

    /** ms from now until master time t (on this phone's clock); 0 if unknown. */
    private long msUntilMaster(long t) {
        long now = android.os.SystemClock.elapsedRealtimeNanos();
        long nowMaster = camera != null ? camera.toMaster(now) : now;
        if (nowMaster == Long.MIN_VALUE || t <= 0) return 0;
        return Math.max(0, (t - nowMaster) / 1_000_000L);
    }

    /** HDR settings + plan of the master -> this phone's camera (before a photo is taken). */
    private void applyHdr(int mode, int bright, boolean nat, String plan) {
        if (master != null) PhotoCapture.jpegQuality = master.jpegQuality;
        else if (camera != null) PhotoCapture.jpegQuality = camera.jpegQuality;
        if (master != null) MergeCore.sharpLevel = master.sharpLevel;
        else if (camera != null) MergeCore.sharpLevel = camera.sharpLevel;
        if (master != null) MergeCore.clarityLevel = master.clarityLevel;
        else if (camera != null) MergeCore.clarityLevel = camera.clarityLevel;
        if (master != null) MergeCore.gpuMode = master.gpuMode;
        else if (camera != null) MergeCore.gpuMode = camera.gpuMode;
        if (syncCamera == null) return;
        // stereo: the master publishes its rendering, cameras develop with it (same look)
        if (master != null) {
            master.toneShare = role == ROLE_MASTER_CAMERA;
            final MasterNode mn = master;
            if (syncCamera.toneBus == null) {
                syncCamera.toneBus = new SyncCamera.ToneBus() {
                    @Override public void publish(int seq, String tone) {
                        mn.sendTone(seq, tone);
                    }

                    @Override public String await(int seq, long timeoutMs) {
                        return null;
                    }
                };
            }
            syncCamera.toneRole = 1;
        } else if (camera != null) {
            final CameraNode cn = camera;
            if (syncCamera.toneBus == null) {
                syncCamera.toneBus = new SyncCamera.ToneBus() {
                    @Override public void publish(int seq, String tone) {
                    }

                    @Override public String await(int seq, long timeoutMs) {
                        long until = android.os.SystemClock.elapsedRealtime() + timeoutMs;
                        while (android.os.SystemClock.elapsedRealtime() < until) {
                            if (cn.toneSeq == seq && cn.toneText != null) return cn.toneText;
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                return null;
                            }
                        }
                        return null;
                    }
                };
            }
            syncCamera.toneRole = camera.toneShare ? 2 : 0;
        }
        syncCamera.hdrMode = mode;
        syncCamera.brightness = bright;
        syncCamera.natural = nat;
        try {
            syncCamera.setPlan(plan);
        } catch (Exception e) {
            syncCamera.setPlan("-");
        }
    }

    // ------------------------------------------------------------------ item 20: remote trigger (experiment)
    private Button remoteBtn;
    private boolean remoteSvc;                       // RemoteService started on this phone
    private int remoteSeenSeq = -1;
    private boolean remoteAlign = true;

    private boolean remoteOn() {
        return master != null ? master.remoteMode == 1 : camera != null && camera.remoteMode == 1;
    }

    /** Master: checks the permissions, then cameras off + foreground service + floating button. */
    private void startRemote() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            showNote("Allow \"display over other apps\" for " + App.NAME + ", then start again");
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        if (!TapService.enabled()) {
            showNote("Enable " + App.NAME + " in Settings > Accessibility (if greyed out: App info > menu > Allow restricted settings)");
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 20);
        }
        remoteSeenSeq = master.photoSeq;
        remoteAlign = master.align;                      // a stop clears "Align" (MasterNode): restored on stop
        master.remoteMode = 1;
        sendState(false, master.align);                  // all cameras off (the clock sync goes on)
        RemoteTrigger.onFloatTap = () -> {
            long t = SystemClock.elapsedRealtimeNanos() + RemoteTrigger.LEAD_NS;   // master time = this clock
            master.remoteShot(t);
            remoteSeenSeq = master.photoSeq;
            StringBuilder d = new StringBuilder();
            int[] st = remoteSyncState(d);
            RemoteTrigger.log(String.format(Locale.US, "master #%d: clock sync %d of %d phones good:%s", master.photoSeq,
                    st[0], st[1], d));
            RemoteTrigger.tapAt(t, "master #" + master.photoSeq);
        };
        startService(new android.content.Intent(this, RemoteService.class).putExtra(RemoteService.EXTRA_BUTTON, true));
        remoteSvc = true;
        remoteBtn.setText("Stop remote trigger");
        master.log.add("remote trigger started");
        showNote("Remote trigger on: open Google Camera on all phones, then tap the floating 3D button");
    }

    private void stopRemote() {
        if (master != null) {
            master.remoteMode = 0;
            RemoteTrigger.onFloatTap = null;
            sendState(true, remoteAlign);                // cameras on, frame alignment as before (bug: was off)
            if (remoteBtn != null) remoteBtn.setText("Start remote trigger (Google Camera)");
            master.log.add("remote trigger stopped");
        }
        stopService(new android.content.Intent(this, RemoteService.class));
        remoteSvc = false;
    }

    /** Camera phones: follow the master's remote mode; a new photo number = press the shutter at its time. */
    private void remoteCamera() {
        if (camera == null) return;
        if (camera.remoteMode == 1 && !remoteSvc) {
            remoteSvc = true;
            remoteSeenSeq = camera.photoSeq;
            startService(new android.content.Intent(this, RemoteService.class));
            if (!TapService.enabled()) showNote("Remote trigger: enable " + App.NAME + " in Settings > Accessibility on this phone");
        } else if (camera.remoteMode != 1 && remoteSvc) {
            stopRemote();
        }
    }

    private void remoteShot(int seq, long photoTime) {
        if (seq == remoteSeenSeq) return;
        remoteSeenSeq = seq;
        if (master != null) return;                      // (the master taps in its own button handler)
        long now = SystemClock.elapsedRealtimeNanos();
        long nowMaster = camera.toMaster(now);
        if (nowMaster == Long.MIN_VALUE) {
            RemoteTrigger.log("camera #" + seq + ": no clock sync - not pressed");
            return;
        }
        RemoteTrigger.tapAt(now + (photoTime - nowMaster), "camera #" + seq);
    }

    private void checkPhoto(int seq, long photoTime, boolean running) {
        if (remoteOn()) {                                // item 20: the other app's shutter, not ours
            remoteShot(seq, photoTime);
            return;
        }
        refreshThumb();
        if (syncCamera == null) return;
        if (master != null) applyHdr(master.hdrMode, master.brightness, master.natural, master.hdrPlan);
        else if (camera != null) applyHdr(camera.hdrMode, camera.brightness, camera.natural, camera.hdrPlan);
        if (seenPhotoSeq < 0 && master == null) {     // first state: do not replay an old photo
            seenPhotoSeq = seq;
            return;
        }
        if (seq == seenPhotoSeq) return;
        seenPhotoSeq = seq;
        if (!running) return;
        LedCapture.Clock clock = camera != null ? camera::toMaster : t -> t;
        SyncCamera.Settings a = syncCamera.activeSettings();
        String info = a == null ? "" : String.format(Locale.US, "exposure %d ns, ISO %d, period %d ns",
                a.exposureNs, a.iso, a.periodNs);
        syncCamera.takePhoto(this, SyncNet.deviceName(this), info, seq, photoTime, clock, deviceDegrees,
                master != null ? master.photoAspect : camera.photoAspect);
        // buzz only after the frame (T + exposure + a frame): no shake in the photo
        boolean burstFmt = (master != null ? master.effFormat() : camera.photoFormat) == 3;
        ui.postDelayed(() -> buzz(1), msUntilMaster(photoTime) + (burstFmt ? 300 : 150));
        // quick thumbnail from the preview on every phone at the same moment; the saved / merged
        // photo replaces it when it is ready
        ui.postDelayed(this::grabThumb, msUntilMaster(photoTime) + 100);
    }

    private int stopBuzzId = -1;
    private long shutterDownNs;                               // elapsedRealtime of the last touch-down

    /** Starts / stops local video recording to follow the master's record state. */
    private void checkRecord(boolean want, int id, long startMaster, long stopMaster, long soundOffsetNs,
                             int conformFps, int bitrate, boolean hevc, String info) {
        if (syncCamera == null) return;
        if (want && id != startedRecordId && syncCamera.isStreaming() && !syncCamera.isRecording()) {
            startedRecordId = id;
            VideoRecorder.nextNumber = id;                // 3D_vid_NNNN-l.mp4: the master's number
            if (camera != null) camera.maxVid = Math.max(camera.maxVid, id);
            LedCapture.Clock clock = camera != null ? camera::toMaster : t -> t;
            syncCamera.startRecording(this, SyncNet.deviceName(this), "record #" + id + ", " + info, clock,
                    deviceDegrees, startMaster, soundOffsetNs, conformFps, bitrate, hevc);
            buzz(2);                                    // the first frame is ~1.5 s later: no shake
        } else if (!want && syncCamera.isRecording()) {
            syncCamera.setRecordingStop(stopMaster);   // ends by itself after the stop time
            if (stopBuzzId != id) {
                stopBuzzId = id;
                ui.postDelayed(() -> buzz(2), msUntilMaster(stopMaster) + 300);   // after the last frame
            }
        }
        if (camera != null) {
            VideoRecorder r = syncCamera.recorder();
            camera.recFrames = r != null && r.isRecording() ? r.frames : -1;
        }
    }

    static final int[] PREVIEW_W = {1920, 1280, 960, 720};
    static final String[] PREVIEW_NAMES = {"1920", "1280", "960", "720"};

    private static int previewIdx(int w) {
        for (int i = 0; i < PREVIEW_W.length; i++) if (PREVIEW_W[i] == w) return i;
        return 0;
    }

    private String videoThumbFor = "";

    /** After a recording on this phone: its first frame becomes the thumbnail (tap = viewer, starts at it). */
    private void checkVideoThumb() {
        VideoRecorder r = syncCamera != null ? syncCamera.recorder() : null;
        if (r == null || r.isRecording() || r.result.isEmpty() || r.result.equals(videoThumbFor) || thumb == null) return;
        videoThumbFor = r.result;
        new Thread(() -> {
            android.graphics.Bitmap b = PhotoViewer.lastVideoFrame(this);
            if (b != null) ui.post(() -> {
                thumb.setImageBitmap(b);
                shownJpeg = null;                    // the viewer then opens at the newest item (this video)
            });
        }, "video-thumb").start();
    }

    private String ownMissingShown = "-";
    private final java.util.Set<String> missingShownNo = new java.util.HashSet<>();   // "who number" already shown

    /** "#29: 1 frame ..." -> "29" (the video number of a missing-frame key). */
    private static String videoNo(String key) {
        int c = key.indexOf(':');
        return c > 1 && key.startsWith("#") ? key.substring(1, c) : key;
    }

    /** "#5: 1 frame missing at 0:07.2" after a recording with missing frames, else "-" (item 41). */
    private static String missingKey(VideoRecorder r) {
        // the snapshot taken when the recording ended: at the next start the new number was briefly paired
        // with the old counts, which showed the previous note again (user 2026-11-26)
        return r == null ? "-" : r.doneKey;
    }

    private static String recText(VideoRecorder r) {
        if (r == null) return "no encoder";
        if (r.isRecording()) {
            return r.isStarted() ? String.format(Locale.US, "REC %d frames%s", r.frames,
                    r.audioFrames > 0 ? " + sound" : r.frames > 45 ? "  NO SOUND (microphone?)" : "") : "starting...";
        }
        return r.result.isEmpty() ? "ready" : "saved " + r.result;
    }

    private String captureInfo(long periodNs, long exposureNs, int iso) {
        return String.format(Locale.US, "role %s, period %d ns, exposure %d ns, ISO %d, asym %d ns",
                master != null ? "control" : camera.isReference() ? "camera (ref)" : "camera",
                periodNs, exposureNs, iso, master != null ? 0 : camera.effAsymNs());
    }

    // AE AT START (user 2026-11-18): the cameras start at the fixed 10 ms / ISO 400 - in a dim room an
    // almost black screen until AE was pressed. Once, ~0.7 s after the master's preview runs.
    private boolean startAeDone;
    private long streamSinceMs;

    private void startAe() {
        if (startAeDone || !master.running || standby) return;
        long now = SystemClock.elapsedRealtime();
        // CONTROL only (no camera of its own): when a camera phone streams (it measures for the AE)
        boolean streaming = syncCamera != null ? syncCamera.isStreaming() : anyCameraStreaming();
        if (!streaming) {
            streamSinceMs = 0;
            return;
        }
        if (streamSinceMs == 0) {
            streamSinceMs = now;
            return;
        }
        if (now - streamSinceMs < (syncCamera != null ? 700 : 3000) || aeRunning) return;
        startAeDone = true;
        master.log.add("AE at start / after standby");
        autoExposure();
    }

    private boolean anyCameraStreaming() {
        for (MasterNode.ClientInfo c : master.clients.values()) if (!c.lost && c.streaming) return true;
        return false;
    }

    /** Item 20: camera phones with a good clock fit (connected, uncertainty < 1 ms, >= 8 points). */
    private int[] remoteSyncState(StringBuilder detail) {
        int good = 0, total = 0;
        long now = SystemClock.elapsedRealtime();
        for (MasterNode.ClientInfo c : master.clients.values()) {
            if (c.lost) continue;
            total++;
            boolean ok = now - c.lastSeenMs < 3000 && c.used >= 8 && c.offsetErrUs < 1000;
            if (ok) good++;
            if (detail != null) detail.append(String.format(Locale.US, " %s: %s (+-%.0f us, %d points, %.1f s ago)",
                    c.name, ok ? "ok" : "NOT ok", c.offsetErrUs, c.used, (now - c.lastSeenMs) / 1000.0));
        }
        return new int[]{good, total};
    }

    private void updateMaster() {
        timelapseTick();
        checkSideWarning();
        VideoRecorder.cbrWanted = master.cbr;
        VideoRecorder.fillWanted = master.fillLost;
        if (syncCamera != null) syncCamera.setPreviewMax(master.previewW);
        if (master.remoteMode == 1) {
            int[] st = remoteSyncState(null);
            RemoteService.setSync(st[0], st[1]);
        }
        checkStandby();
        startAe();
        if (syncCamera != null) syncCamera.statsWanted = aeRunning;
        applyCameraState(master.running, master.periodNs, master.exposureNs, master.iso, master.videoHeight,
                master.photoMode, master.effFormat(), master.appJpeg, master.tone);
        checkPhoto(master.photoSeq, master.photoTime, master.running);
        if (syncCamera != null) {
            syncCamera.keepBurstFrames = master.keepBurstFrames;
            syncCamera.nightOut = master.photoMode ? master.nightOut : -1;
        }
        applyHdr(master.effHdrMode(), master.brightness, master.natural, master.hdrPlan);
        if (syncCamera != null) syncCamera.setLook(master.look());
        lockWbButton.setText(WB_NAMES[wbChoice].equals("K") ? "Set K" : "Lock WB" + (master.wbMode == SyncCamera.WB_COPY
                ? (master.wbGains != null ? " ✓" : " ?") : ""));
        if (syncCamera != null) {
            double awbK = estimateK(syncCamera.lastGains);
            double nowK = estimateK(syncCamera.appliedGains);
            awbText.setText(String.format(Locale.US, "  AWB ≈ %s, now ≈ %s",
                    Double.isNaN(awbK) ? "-" : String.format(Locale.US, "%.0f K", awbK),
                    Double.isNaN(nowK) ? "-" : String.format(Locale.US, "%.0f K", nowK)));
        }
        checkCapture(master.captureSeq, master.captureSeconds, master.running,
                captureInfo(master.periodNs, master.exposureNs, master.iso));
        checkRecord(master.record && master.running, master.recordId, master.recStart, master.recStop,
                soundNs(master.soundOffsetNs), master.conformFps, master.videoBitrate, master.hevc, captureInfo(master.periodNs, master.exposureNs, master.iso));
        syncCameraToNode();
        master.updateReference(role == ROLE_MASTER_CAMERA ? aligner : null);
        master.checkLost();
        checkVideoThumb();
        // item 41: missing video frames (own camera and camera phones) -> a note on the CONTROL phone
        // each video's note only ONCE (by its number): the key went "-" while recording and back, which showed
        // the previous video's note again at the next start (user 2026-11-26)
        if (syncCamera != null) {
            String k = missingKey(syncCamera.recorder());
            if (!k.equals("-") && !k.equals(ownMissingShown)) {
                if (missingShownNo.add("self " + videoNo(k))) showWarnNote("This phone, video " + k);
                ownMissingShown = k;
            }
        }
        for (MasterNode.ClientInfo c : master.clients.values()) {
            if (!c.vidMissingNew) continue;
            c.vidMissingNew = false;
            if (missingShownNo.add(c.id + " " + videoNo(c.vidMissing))) showWarnNote(c.name + ", video " + c.vidMissing);
        }
        // item 38: a tap focus on a camera phone -> that distance for all phones (like the CONTROL's own tap)
        for (MasterNode.ClientInfo c : master.clients.values()) {
            if (!c.focusNew) continue;
            c.focusNew = false;
            float d = c.focusD;
            master.focusManual = true;
            master.focusDiopters = d;
            if (distEdit != null) distEdit.setText(d > 0.001f ? String.format(Locale.US, "%.2f", 1 / d) : "inf");
            master.lookChanged(String.format(Locale.US, "tap focus on %s: %s%s", c.name, focusText(d),
                    c.focusSharp ? "" : " (not sharp?)"));
        }

        String conflict = sideConflict();
        header.setText("CONTROL" + (role == ROLE_MASTER_CAMERA ? " + " + Downloads.SIDE_NAMES[Downloads.side] + " camera" : "")
                + "  " + SyncNet.localAddresses() + (conflict != null ? "   !! " + conflict : ""));
        // (the red same-side warning: checkSideWarning; the header keeps the "!!" marker)


        // sync display: the worst camera
        long worst = 0;
        boolean measuringAny = false;
        int nCams = 0;
        StringBuilder det = new StringBuilder();
        for (MasterNode.ClientInfo c : master.clients.values()) {
            if (c.lost) continue;
            nCams++;
            long q = held(c.id, quality(c.id.equals(master.refId), c.phaseValid && master.running, c.phaseUs,
                    c.offsetErrUs), c.offsetErrUs >= 0);
            if (q <= -2) measuringAny = true;
            worst = q == -1 || worst < 0 ? -1 : Math.max(worst, qValue(q));
            det.append(trim(c.name.replace("Pixel 7a ", ""), 8)).append(c.side == Downloads.SIDE_LEFT ? " L"
                    : c.side == Downloads.SIDE_RIGHT ? " R" : c.side == Downloads.SIDE_MONO ? " M" : "")
                    .append(q == -1 ? " … " : String.format(Locale.US,
                    q <= -2 ? " %d? " : " %d ", Math.round(qValue(q) / 1e3)));
        }
        if (nCams == 0 || !master.running) worst = -1;
        long shown = worst >= 0 && measuringAny ? -2 - worst : worst;
        updateMasterMain(shown, !master.running ? "(cameras stopped)" : nCams == 0 ? "(no camera)"
                : det.toString().trim());
        if (syncCamera != null && syncCamera.recorder() != null) syncCamera.recorder().noteSync(worst);

        SpannableStringBuilder sb = new SpannableStringBuilder();
        String fmt = "%-14s %5s %5s %5s %6s %7s%n";
        sb.append("  ").append(String.format(Locale.US, fmt, "Camera", "±us", "scat", "RTTms", "ppm", "phaseus"));
        if (role == ROLE_MASTER_CAMERA) {
            dot(sb, syncCamera.isStreaming() ? GREEN : GREY);
            sb.append(String.format(Locale.US, fmt, "self (ref)", "0", "-", "-", "-",
                    syncCamera.isStreaming() ? "ref" : "-"));
        }
        List<MasterNode.ClientInfo> list = new ArrayList<>(master.clients.values());
        list.sort((a, b) -> a.name.compareTo(b.name));
        for (MasterNode.ClientInfo c : list) {
            boolean isRef = c.id.equals(master.refId);
            String phase = isRef ? "ref" : c.phaseValid
                    ? phaseText(c.phaseUs) + (c.aligned || !master.align ? "" : "*") : "-";
            double colorValue = master.running && c.phaseValid && !isRef ? c.phaseUs : c.offsetErrUs;
            dot(sb, colorFor(colorValue, c.lost));
            sb.append(String.format(Locale.US, fmt, trim(c.name, 14),
                    String.format(Locale.US, "%.0f", c.offsetErrUs),
                    String.format(Locale.US, "%.0f", c.scatterUs),
                    String.format(Locale.US, "%.1f", c.minRttMs),
                    String.format(Locale.US, "%+.2f", c.driftPpm), phase));
        }
        if (list.isEmpty()) sb.append("  waiting for cameras...\n");
        else sb.append("  ± = clock fit error, scat = per ping, * = aligning\n");
        if (role == ROLE_MASTER_CAMERA) {
            sb.append("  own camera: ").append(syncCamera.status()).append('\n');
            sb.append("  WB / focus: ").append(lookText(syncCamera)).append('\n');
        }

        // LED test: position / brightness of the brightest spot and capture progress
        sb.append("  LED test (y = row of brightest spot, pk = peak 0-255)\n");
        if (led != null) {
            sb.append("  ").append(ledLine("self", led.ledY, led.peak, led.progressS(), (int) led.durationS()));
        }
        for (MasterNode.ClientInfo c : list) {
            sb.append("  ").append(ledLine(trim(c.name, 14), c.ledY, c.ledPeak, c.capProgressS,
                    master.captureSeconds));
        }
        if (led != null && !led.result.isEmpty()) sb.append("  ").append(led.result).append('\n');

        if (master.photoMode) {
            sb.append("  Photo (frame - photo time)\n");
            if (syncCamera != null) sb.append("  self           ").append(syncCamera.photoStatus).append('\n');
            for (MasterNode.ClientInfo c : list) {
                sb.append(String.format(Locale.US, "  %-14s %s%n", trim(c.name, 14), c.photoSeq > 0
                        ? String.format(Locale.US, "#%d %+.0f us", c.photoSeq, c.photoErrUs) : "-"));
            }
        }
        sb.append("  Video\n");
        if (syncCamera != null) sb.append("  self           ").append(recText(syncCamera.recorder())).append('\n');
        for (MasterNode.ClientInfo c : list) {
            sb.append(String.format(Locale.US, "  %-14s %s%n", trim(c.name, 14),
                    c.recFrames >= 0 ? "REC " + c.recFrames + " frames" : "-"));
        }
        table.setText(sb);

        MasterNode.Point p;
        while ((p = master.points.poll()) != null) graph.add(p.key, p.timeMs, p.value);
        graph.invalidate();

        String line;
        while ((line = master.log.poll()) != null) {
            logLines.addLast(String.format(Locale.US, "%tT %s", System.currentTimeMillis(), line));
            while (logLines.size() > 8) logLines.pollFirst();
        }
        logView.setText(String.join("\n", logLines));
    }

    private long camAeLastMs, camLightSinceMs;
    private double camLastLight = -1;

    /**
     * Camera phone: its values for the master's joint AE / white balance. While the master's AE
     * runs, a measurement every ~0.4 s once the exposure has been stable for 0.4 s (the preview
     * must show the current exposure); its AWB gains and dark / median levels always.
     */
    private void cameraAeReport() {
        if (camera == null || syncCamera == null) return;
        syncCamera.statsWanted = camera.aeActive;    // RAW statistics often only while the master's AE runs
        android.hardware.camera2.params.RggbChannelVector g = syncCamera.lastGains;
        if (g != null) camera.awb = new float[]{g.getRed(), g.getGreenEven(), g.getGreenOdd(), g.getBlue()};
        long now = SystemClock.elapsedRealtime();
        double light = syncCamera.activeLight();
        if (light != camLastLight) {
            camLastLight = light;
            camLightSinceMs = now;
        }
        if (!camera.aeActive || light <= 0 || now - camLightSinceMs < 400 || now - camAeLastMs < 400) return;
        double[] m = aeMeasure(camera.photoMode, camera.userFormat(), camera.aeAllow);
        if (m == null) return;
        camAeLastMs = now;
        camera.aeRatio = m[0];
        camera.aeClip = m[1];
        camera.aeLight = light;
        camera.rawDark = syncCamera.rawDark;
        camera.rawMedian = syncCamera.rawMedian;
        camera.aeSeq++;
    }

    /** Tells the camera node whether the local camera is streaming (for its status). */
    private void syncCameraToNode() {
        if (camera != null && syncCamera != null) camera.streaming = syncCamera.isStreaming();
    }

    private int seenAstroStop = -1;

    private void updateCamera() {
        remoteCamera();
        checkVideoThumb();
        if (syncCamera != null) camera.vidMissing = missingKey(syncCamera.recorder());   // item 41
        VideoRecorder.cbrWanted = camera.cbr;
        VideoRecorder.fillWanted = camera.fillLost;
        if (syncCamera != null) syncCamera.setPreviewMax(camera.previewW);
        checkSideWarning();
        Downloads.sub = camera.timelapse > 0 ? String.format(Locale.US, "Timelapse_%04d/", camera.timelapse) : "";
        if (camera.astroStopSeq >= 0) {                     // the master stopped the astro series
            if (seenAstroStop >= 0 && camera.astroStopSeq != seenAstroStop && syncCamera != null) syncCamera.stopAstro();
            seenAstroStop = camera.astroStopSeq;
        }
        showStandbyOverlay(camera.standby && SystemClock.elapsedRealtime() - camera.lastStateMs < 5000,
                "Standby\n(cameras off to save battery)\n\ntap the CONTROL phone to wake");
        boolean stateFresh = SystemClock.elapsedRealtime() - camera.lastStateMs < 5000;
        applyCameraState(stateFresh && camera.running, camera.periodNs, camera.exposureNs, camera.iso,
                camera.videoHeight, camera.photoMode, camera.photoFormat, camera.appJpeg, camera.tone);
        if (camera.photoSeq >= 0) checkPhoto(camera.photoSeq, camera.photoTime, stateFresh && camera.running);
        syncCamera.keepBurstFrames = camera.keepBurstFrames;
        syncCamera.nightOut = camera.nightOut;
        applyHdr(camera.hdrMode, camera.brightness, camera.natural, camera.hdrPlan);
        SyncCamera.Look lk = camera.look;
        if (lk != null) syncCamera.setLook(lk);
        camera.photoSeqDone = syncCamera.lastPhotoSeq;
        cameraAeReport();
        camera.photoErrNs = syncCamera.lastPhotoErrNs;
        syncCameraToNode();
        if (camera.captureSeq >= 0) {
            checkCapture(camera.captureSeq, camera.captureSeconds, stateFresh && camera.running,
                    captureInfo(camera.periodNs, camera.exposureNs, camera.iso));
            checkRecord(stateFresh && camera.running && camera.record, camera.recordId, camera.recStart,
                    stateFresh ? camera.recStop : 0, soundNs(camera.soundOffsetNs), camera.conformFps, camera.videoBitrate, camera.hevc,
                    captureInfo(camera.periodNs, camera.exposureNs, camera.iso));
        }

        boolean connected = SystemClock.elapsedRealtime() - camera.lastPongMs < 2000;
        SpannableStringBuilder h = new SpannableStringBuilder("CAMERA  " + camera.name() + "  ");
        dot(h, connected ? GREEN : RED);
        h.append(connected ? "connected" : "searching CONTROL phone...");
        h.append("   ").append(Downloads.SIDE_NAMES[Downloads.side]).append(" camera");
        header.setText(h);

        long cerr = camera.clockErrNs;
        long q = !connected || !syncCamera.isStreaming() ? -1
                : held("self", quality(camera.isReference(), aligner.phaseValid, aligner.phaseNs / 1e3,
                cerr < 0 ? -1 : cerr / 1e3), cerr >= 0);
        updateCameraMain(q, camera.name().replace("Pixel 7a ", "") + " · clock " + camera.clockSource
                + (aligner.aligned ? " · aligned" : camera.align && !camera.isReference() ? " · aligning" : ""));
        if (syncCamera.recorder() != null) syncCamera.recorder().noteSync(qValue(q));

        ClockEstimator.Result r = camera.estimator.compute(SystemClock.elapsedRealtimeNanos());
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String m = camera.master == null ? "-" : camera.master.getHostAddress();
        sb.append(String.format(Locale.US, "Control       %s (%s)%n", m, camera.masterSource));
        if (r.valid) {
            sb.append(String.format(Locale.US, "Clock         ±%.0fus fit,%.0fus/ping,%+.1fppm%n",
                    r.offsetErrNs / 1e3, r.scatterNs / 1e3, r.driftPpm));
            sb.append(String.format(Locale.US, "RTT min/med   %.1f / %.1f ms (%.0f s)%n",
                    r.minRttNs / 1e6, r.medRttNs / 1e6, r.windowS));
        } else {
            sb.append("Clock         -\n");
        }
        sb.append("Camera        ").append(syncCamera.status()).append('\n');
        if (camera.isReference()) {
            sb.append("Phase         this camera is the reference\n");
        } else if (aligner.phaseValid) {
            sb.append("Phase error   ");
            dot(sb, colorFor(aligner.phaseNs / 1e3, false));
            sb.append(String.format(Locale.US, "%+.0f us  %s%n", aligner.phaseNs / 1e3,
                    !camera.align ? "(align off)" : aligner.aligned ? "aligned" : "aligning..."));
        } else {
            sb.append("Phase error   -\n");
        }
        sb.append(String.format(Locale.US, "Steps         %d (last %+.0f us)%n", aligner.steps,
                aligner.lastStepNs / 1e3));
        sb.append("Re-attached   ").append(String.valueOf(camera.reattachCount)).append("x\n");
        int prog = led.progressS();
        sb.append(String.format(Locale.US, "LED           y=%s pk=%d %s%n",
                led.ledY >= 0 ? Integer.toString(led.ledY) : "-", led.peak,
                prog >= 0 ? String.format(Locale.US, "capturing %d/%d s", prog, led.durationS()) : ""));
        if (!led.result.isEmpty()) sb.append("     ").append(led.result).append('\n');
        sb.append("Video         ").append(recText(syncCamera.recorder())).append('\n');
        sb.append("WB / focus    ").append(lookText(syncCamera)).append('\n');
        double nowK = estimateK(syncCamera.appliedGains);
        if (!Double.isNaN(nowK)) sb.append(String.format(Locale.US, "WB now        ≈ %.0f K%n", nowK));
        if (!syncCamera.photoStatus.isEmpty()) sb.append("Photo         ").append(syncCamera.photoStatus).append('\n');
        sb.append("Log  ").append(camera.logPath).append('\n');
        table.setText(sb);
    }

    /** Current white balance gains and focus distance measured by this camera. */
    private static String lookText(SyncCamera c) {
        android.hardware.camera2.params.RggbChannelVector g = c.appliedGains;
        float d = c.lastFocusDiopters;
        return (g == null ? "WB -" : String.format(Locale.US, "R %.3f G %.3f B %.3f%s", g.getRed(),
                g.getGreenEven(), g.getBlue(), c.appliedAwbOff ? " fixed" : "")) + ", focus "
                + (d < 0 ? "-" : focusText(d));
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
