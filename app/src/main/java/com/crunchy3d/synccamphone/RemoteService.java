package com.crunchy3d.synccamphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Item 20 (experiment): keeps CamSyncProbe alive while another camera app is in front (foreground
 * service: the Wi-Fi clock sync goes on) and, on the master, shows the floating shutter button over
 * that app (draggable; a tap = remote photo on all phones).
 */
public class RemoteService extends Service {
    static final String EXTRA_BUTTON = "button";
    static volatile RemoteService instance;
    private TextView button;
    private GradientDrawable buttonBg;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * The clock sync of the camera phones on the button (master): green all good, yellow some, red none;
     * "good/total" under 3D.
     */
    static void setSync(int good, int total) {
        RemoteService s = instance;
        if (s == null) return;
        s.main.post(() -> {
            if (s.button == null) return;
            int col = total > 0 && good == total ? 0xDD2E7D32 : good > 0 ? 0xDDF9A825 : 0xDDD32F2F;
            s.buttonBg.setColor(col);
            s.button.setText("3D\n" + good + "/" + total);
        });
    }

    @Override public IBinder onBind(Intent i) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("remote", "Remote trigger", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "remote")
                .setContentTitle(App.NAME + " remote trigger")
                .setContentText("Clock sync running - open the camera app; stop in " + App.NAME + " > Settings")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        startForeground(20, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (intent != null && intent.getBooleanExtra(EXTRA_BUTTON, false) && button == null) showButton();
        return START_NOT_STICKY;
    }

    private void showButton() {
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        WindowManager wm = getSystemService(WindowManager.class);
        float d = getResources().getDisplayMetrics().density;
        TextView b = new TextView(this);
        b.setText("3D\n?");
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCCD32F2F);
        bg.setStroke((int) (3 * d), Color.WHITE);
        b.setBackground(bg);
        buttonBg = bg;
        int size = (int) (72 * d);
        SharedPreferences p = getSharedPreferences("remote", MODE_PRIVATE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = p.getInt("bx", (int) (24 * d));
        lp.y = p.getInt("by", (int) (200 * d));
        b.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = lp.x;
                        oy = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - sx) + Math.abs(e.getRawY() - sy) > 12 * d) moved = true;
                        if (moved) {
                            lp.x = ox + (int) (e.getRawX() - sx);
                            lp.y = oy + (int) (e.getRawY() - sy);
                            wm.updateViewLayout(b, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            p.edit().putInt("bx", lp.x).putInt("by", lp.y).apply();
                        } else {
                            Runnable r = RemoteTrigger.onFloatTap;
                            if (r != null) r.run();
                            b.animate().scaleX(0.8f).scaleY(0.8f).setDuration(80)
                                    .withEndAction(() -> b.animate().scaleX(1).scaleY(1).setDuration(80)).start();
                        }
                        return true;
                }
                return false;
            }
        });
        wm.addView(b, lp);
        button = b;
    }

    @Override public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (button != null) {
            getSystemService(WindowManager.class).removeView(button);
            button = null;
        }
    }
}
