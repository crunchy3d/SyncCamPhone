package com.crunchy3d.synccamphone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Start screen: choose the role of this phone. Follows the phone's orientation (landscape: the
 * buttons in two columns). The lens is no longer chosen here: the master chooses it for all phones
 * (Lenses, 2026-11-20); the lenses found on this phone are listed.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(land ? 16 : 24);
        inner.setPadding(pad, pad, pad, pad);
        scroll.addView(inner);

        TextView title = new TextView(this);
        title.setText(App.TITLE);
        title.setTextSize(24);
        title.setPadding(0, 0, 0, dp(4));
        inner.addView(title);
        TextView name = new TextView(this);
        name.setText(SyncNet.deviceName(this));
        name.setPadding(0, 0, 0, dp(land ? 12 : 24));
        inner.addView(name);

        LinearLayout colA = inner, colB = inner;
        if (land) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            colA = new LinearLayout(this);
            colA.setOrientation(LinearLayout.VERTICAL);
            colB = new LinearLayout(this);
            colB.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            la.setMargins(0, 0, dp(8), 0);
            LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lb.setMargins(dp(8), 0, 0, 0);
            row.addView(colA, la);
            row.addView(colB, lb);
            inner.addView(row);
        }

        addButton(colA, "CONTROL + CAMERA", SyncActivity.ROLE_MASTER_CAMERA, land);
        addButton(colA, "CAMERA", SyncActivity.ROLE_CAMERA, land);
        addButton(colA, "CONTROL only", SyncActivity.ROLE_MASTER_ONLY, land);
        // a single phone (no stereo pair): master + camera with MONO file names, no position question
        Button mono = new Button(this);
        mono.setText("MONO camera");
        mono.setAllCaps(false);
        mono.setOnClickListener(v -> {
            Intent i = new Intent(this, SyncActivity.class);
            i.putExtra(SyncActivity.EXTRA_ROLE, SyncActivity.ROLE_MASTER_CAMERA);
            i.putExtra(SyncActivity.EXTRA_SIDE, Downloads.SIDE_MONO);
            startActivity(i);
        });
        colB.addView(mono, buttonParams(land));
        Button probe = new Button(this);
        probe.setText("Probe (camera tests)");
        probe.setAllCaps(false);
        probe.setOnClickListener(v -> startActivity(new Intent(this, ProbeActivity.class)));
        colB.addView(probe, buttonParams(land));

        TextView hint = new TextView(this);
        hint.setText(lensLine() + "\n\nCONTROL phone: turn on the Wi-Fi hotspot in Android settings.\n"
                + "Camera phones: connect to that hotspot.");
        hint.setTextSize(13);
        hint.setPadding(0, dp(land ? 8 : 20), 0, 0);
        colB.addView(hint);

        setContentView(scroll);
        // camera, sound, and reading photos / videos in Download (the file numbering continues
        // after the highest number there, also for files of an earlier install)
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.CAMERA);
        need.add(Manifest.permission.RECORD_AUDIO);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.READ_MEDIA_IMAGES);
            need.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        List<String> ask = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), 1);
    }

    /** "Lenses: Main 1x, Ultra-wide 0.5x, Front" (+ the ones the sync cannot use). */
    private String lensLine() {
        StringBuilder sb = new StringBuilder("Lenses: ");
        List<Lenses.Lens> all = Lenses.detect(getSystemService(CameraManager.class));
        boolean first = true;
        for (Lenses.Lens l : all) {
            if (!l.ok) continue;
            sb.append(first ? "" : ", ").append(l.label());
            first = false;
        }
        if (first) sb.append("none found");
        for (Lenses.Lens l : all) if (!l.ok) sb.append("\n").append(l.label()).append(": not usable (").append(l.reason).append(')');
        return sb.toString();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private LinearLayout.LayoutParams buttonParams(boolean land) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(land ? 56 : 64));
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private void addButton(LinearLayout parent, String text, int role, boolean land) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            Intent i = new Intent(this, SyncActivity.class);
            i.putExtra(SyncActivity.EXTRA_ROLE, role);
            if (role == SyncActivity.ROLE_MASTER_ONLY) {
                startActivity(i);
                return;
            }
            // every start: the phones may have changed places (file names end with -l / -r)
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Position of this camera")
                    .setItems(new String[]{"LEFT camera (files -l)", "RIGHT camera (files -r)"}, (d, which) -> {
                        i.putExtra(SyncActivity.EXTRA_SIDE, which);
                        startActivity(i);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        parent.addView(b, buttonParams(land));
    }
}
