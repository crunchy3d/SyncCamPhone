package com.crunchy3d.synccamphone;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ProbeActivity extends Activity implements TimingTest.Listener {
    private static final int REQ_CAMERA = 1;

    private CameraManager cameraManager;
    private String[] cameraIds = new String[0];

    private Spinner cameraSpinner;
    private EditText fpsEdit, exposureEdit, isoEdit, stepEdit, zoomEdit;
    private Button infoButton, runButton, shareButton, darkButton;
    private DarkTest dark;
    private android.content.res.ColorStateList outColors;
    private TextureView preview;
    private TextView output;
    private ScrollView outputScroll;

    private String lastReport = "";
    private TimingTest test;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cameraManager = getSystemService(CameraManager.class);
        buildUi();
        loadCameraList();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
        setReport(App.NAME + " - step 1 of the stereo phone sync project.\n\n"
                + "1. 'Camera info' lists sync-relevant properties of all cameras.\n"
                + "2. 'Timing test' streams the selected camera with manual exposure, measures\n"
                + "   the frame period and checks whether a one-frame change of the frame\n"
                + "   duration shifts the phase of all later frames.\n\n"
                + "Reports are saved to " + "Download/" + App.FOLDER + " and can be shared.\n");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (test != null) {
            test.cancel();
            test = null;
        }
        if (dark != null) {
            dark.cancel();
            dark = null;
            setBusy(false);
            dimScreen(false);
        }
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private EditText numberField(LinearLayout row, String label, String value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setSingleLine();
        e.setSelectAllOnFocus(true);
        col.addView(t);
        col.addView(e);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(4));
        row.addView(col, lp);
        return e;
    }

    private Button button(LinearLayout row, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setFitsSystemWindows(true);

        cameraSpinner = new Spinner(this);
        root.addView(cameraSpinner);

        LinearLayout params = new LinearLayout(this);
        fpsEdit = numberField(params, "fps", "30");
        exposureEdit = numberField(params, "exposure ms", "10");
        isoEdit = numberField(params, "ISO", "400");
        stepEdit = numberField(params, "step ms", "5");
        zoomEdit = numberField(params, "zoom", "1");
        root.addView(params);

        LinearLayout buttons = new LinearLayout(this);
        infoButton = button(buttons, "Camera info");
        runButton = button(buttons, "Timing test");
        shareButton = button(buttons, "Share");
        root.addView(buttons);
        LinearLayout buttons2 = new LinearLayout(this);
        darkButton = button(buttons2, "Dark frames (ISO noise test, ~2.5 min)");
        root.addView(buttons2);

        preview = new TextureView(this);
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));

        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(10);
        output.setTextIsSelectable(true);
        outputScroll = new ScrollView(this);
        outputScroll.addView(output);
        root.addView(outputScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        infoButton.setOnClickListener(v -> showInfo());
        runButton.setOnClickListener(v -> runTest());
        shareButton.setOnClickListener(v -> share());
        darkButton.setOnClickListener(v -> runDark());
    }

    private void loadCameraList() {
        List<String> labels = new ArrayList<>();
        try {
            cameraIds = cameraManager.getCameraIdList();
            for (String id : cameraIds) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                labels.add("Camera " + id + " (" + CameraInfo.facingName(c.get(CameraCharacteristics.LENS_FACING)) + ")");
            }
        } catch (Exception e) {
            labels.add("Error: " + e.getMessage());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        cameraSpinner.setAdapter(adapter);
    }

    private void setReport(String text) {
        lastReport = text;
        output.setText(text);
        outputScroll.post(() -> outputScroll.scrollTo(0, 0));
    }

    private void setBusy(boolean busy) {
        infoButton.setEnabled(!busy);
        runButton.setEnabled(!busy);
        shareButton.setEnabled(!busy);
        if (darkButton != null) darkButton.setEnabled(!busy);
        cameraSpinner.setEnabled(!busy);
    }

    private static double parse(EditText e, double fallback) {
        try {
            return Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ actions

    private void showInfo() {
        try {
            String report = CameraInfo.dump(cameraManager);
            String where = saveText("camera_info", "txt", report);
            setReport(report + "Saved: " + where + "\n");
        } catch (Exception e) {
            setReport("Error reading camera info: " + e);
        }
    }

    private void runTest() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (cameraIds.length == 0) return;
        if (!preview.isAvailable()) {
            Toast.makeText(this, "Preview not ready yet, try again.", Toast.LENGTH_SHORT).show();
            return;
        }
        TimingTest.Params p = new TimingTest.Params();
        p.cameraId = cameraIds[Math.max(0, cameraSpinner.getSelectedItemPosition())];
        p.fps = Math.max(1, parse(fpsEdit, 30));
        p.exposureMs = Math.max(0.01, parse(exposureEdit, 10));
        p.iso = (int) Math.max(50, parse(isoEdit, 400));
        p.stepMs = Math.max(0.1, parse(stepEdit, 5));
        p.zoom = (float) Math.max(0.1, parse(zoomEdit, 1));
        setBusy(true);
        setReport("Starting timing test...\n");
        test = new TimingTest(cameraManager, p, preview.getSurfaceTexture(), this);
        test.start();
    }

    /** Dark-frame test: 15 s to cover the camera, then RAW frames at several ISOs (DarkTest). */
    private void runDark() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (cameraIds.length == 0) return;
        if (!preview.isAvailable()) {
            Toast.makeText(this, "Preview not ready yet, try again.", Toast.LENGTH_SHORT).show();
            return;
        }
        String id = cameraIds[Math.max(0, cameraSpinner.getSelectedItemPosition())];
        setBusy(true);
        dimScreen(true);
        setReport("Dark-frame test, camera " + id + ": cover the camera completely (dark cloth / box).\n");
        dark = new DarkTest(this, cameraManager, id, preview.getSurfaceTexture(), 15, new DarkTest.Listener() {
            @Override public void onProgress(String message) {
                runOnUiThread(() -> output.setText(message));
            }

            @Override public void onDone(String report) {
                runOnUiThread(() -> {
                    dimScreen(false);
                    setReport("Dark-frame test finished.\n\n" + report);
                    setBusy(false);
                    dark = null;
                    buzz2();
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    dimScreen(false);
                    setReport("Dark-frame test stopped: " + message + "\n");
                    setBusy(false);
                    dark = null;
                });
            }
        });
        dark.start();
    }

    /**
     * During the dark-frame test the screen stays on (the test stops when the activity pauses) but
     * at minimum brightness and black, with dim text: no light reaching the camera from it.
     */
    private void dimScreen(boolean on) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = on ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        android.view.View root = getWindow().getDecorView();
        root.setBackgroundColor(on ? android.graphics.Color.BLACK : android.graphics.Color.TRANSPARENT);
        preview.setVisibility(on ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
        if (on) {
            if (outColors == null) outColors = output.getTextColors();
            output.setTextColor(0xFF404040);
        } else if (outColors != null) {
            output.setTextColor(outColors);
        }
    }

    /** Two vibrations: the dark-frame test is finished. */
    private void buzz2() {
        try {
            android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
            if (v != null) v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 300, 250, 300}, -1));
        } catch (Exception ignored) {
            // no vibrator
        }
    }

    private void share() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, App.TITLE + " report - " + Build.MANUFACTURER + " " + Build.MODEL);
        send.putExtra(Intent.EXTRA_TEXT, lastReport);
        startActivity(Intent.createChooser(send, "Share report"));
    }

    // ------------------------------------------------------------------ TimingTest.Listener

    @Override
    public void onProgress(String message) {
        runOnUiThread(() -> output.setText(message));
    }

    @Override
    public void onDone(String cameraId, String report, String csv) {
        runOnUiThread(() -> {
            String base = "timing_cam" + cameraId;
            String r1 = saveText(base, "txt", report);
            String r2 = saveText(base, "csv", csv);
            setReport(report + "\nSaved: " + r1 + "\n       " + r2 + "\n");
            setBusy(false);
            test = null;
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            setReport("Timing test stopped: " + message + "\n");
            setBusy(false);
            test = null;
        });
    }

    // ------------------------------------------------------------------ saving

    private String saveText(String baseName, String ext, String content) {
        try {
            Downloads.Out out = Downloads.open(this, Downloads.fileName(baseName, ext), Downloads.mimeFor(ext));
            try (OutputStream os = out.stream) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return out.path;
        } catch (IOException e) {
            return "(save failed: " + e.getMessage() + ")";
        }
    }
}
