package com.crunchy3d.synccamphone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Item 20 (experiment): the accessibility service that presses another camera app's shutter. It looks
 * for the shutter button in the accessibility tree (by its label / view id) and clicks it; when not
 * found, it taps the last known shutter position (a gesture). Enabled once by the user (Settings >
 * Accessibility; sideloaded apps first: App info > Allow restricted settings).
 */
public class TapService extends AccessibilityService {
    static volatile TapService instance;
    private static volatile AccessibilityNodeInfo cached;
    // matched on the elements' DESCRIPTION and view ID only - not on the visible text: Blackmagic Camera
    // shows a "SHUTTER" label (the shutter-angle setting) in its top bar, which was clicked instead of the
    // record button (2026-11-20); video apps: "Record" / "Start recording"
    private static final Pattern SHUTTER = Pattern.compile(
            "(?i).*\\b(shutter|take photo|take picture|capture|record|start recording|auslöser|okidalo"
                    + "|sprožilec|fotografiraj|snemaj)\\b.*");
    private static final Pattern NOT_SHUTTER = Pattern.compile("(?i).*(setting|menu|mode|option|format).*");
    private static volatile String cachedPkg;   // the app the shutter was looked up in

    @Override protected void onServiceConnected() {
        instance = this;
        RemoteTrigger.appCtx = getApplicationContext();
        RemoteTrigger.log("accessibility service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
    }

    @Override public void onInterrupt() {
    }

    @Override public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    static boolean enabled() {
        return instance != null;
    }

    /** Looks up the shutter before T (the search takes a few ms). */
    static void prepare() {
        TapService s = instance;
        cachedPkg = null;
        cached = s == null ? null : s.findShutter(true);
    }

    /** Clicks the shutter now; returns how. */
    static String tap() {
        TapService s = instance;
        if (s == null) return "NO accessibility service (enable " + App.NAME + " in Settings > Accessibility)";
        AccessibilityNodeInfo n = cached;
        cached = null;
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "click on " + describe(n);
        }
        // not a clickable element (Blackmagic's "Record" is a drawn image): a tap at its centre,
        // remembered per app
        SharedPreferences p = s.getSharedPreferences("remote", MODE_PRIVATE);
        String pk = cachedPkg != null ? cachedPkg : "?";
        float x = p.getFloat("x." + pk, -1), y = p.getFloat("y." + pk, -1);
        if (x < 0) return "shutter NOT found (no saved position) - see remote_nodes.txt";
        Path path = new Path();
        path.moveTo(x, y);
        boolean ok = s.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 40)).build(), null, null);
        return String.format(Locale.US, "tap gesture at %.0f, %.0f (%s)", x, y, ok ? "sent" : "refused");
    }

    private static String describe(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        String id = n.getViewIdResourceName();
        return (d != null ? "\"" + d + "\"" : "") + (id != null ? " " + id : "");
    }

    /** The shutter of the camera app in front (clickable node whose label / id says shutter). */
    private AccessibilityNodeInfo findShutter(boolean dumpIfMissing) {
        StringBuilder dump = dumpIfMissing ? new StringBuilder() : null;
        List<AccessibilityWindowInfo> ws = getWindows();
        for (AccessibilityWindowInfo w : ws) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence pkg = root.getPackageName();
            if (pkg == null || pkg.toString().equals(getPackageName())) continue;
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            while (!q.isEmpty()) {
                AccessibilityNodeInfo n = q.poll();
                CharSequence d = n.getContentDescription(), tx = n.getText();
                String id = n.getViewIdResourceName();
                String key = (d != null ? d : "") + " " + (tx != null ? tx : "") + " " + (id != null ? id : "");
                String named = (d != null ? d : "") + " " + (id != null ? id : "");     // no visible text
                if (dump != null && (n.isClickable() || d != null || id != null)) {
                    Rect b = new Rect();
                    n.getBoundsInScreen(b);
                    dump.append(pkg).append(" | ").append(key.trim()).append(" | clickable ").append(n.isClickable())
                            .append(" | ").append(b.toShortString()).append('\n');
                }
                if (SHUTTER.matcher(named).matches() && !NOT_SHUTTER.matcher(named).matches()) {
                    Rect nb = new Rect();
                    n.getBoundsInScreen(nb);
                    cachedPkg = pkg.toString();
                    getSharedPreferences("remote", MODE_PRIVATE).edit()
                            .putFloat("x." + cachedPkg, nb.exactCenterX()).putFloat("y." + cachedPkg, nb.exactCenterY()).apply();
                    // the element itself, or a clickable parent of about its size (not a big container)
                    AccessibilityNodeInfo c = n;
                    long area = Math.max(1L, (long) nb.width() * nb.height());
                    while (c != null && !c.isClickable()) {
                        c = c.getParent();
                        if (c != null) {
                            Rect cb = new Rect();
                            c.getBoundsInScreen(cb);
                            if ((long) cb.width() * cb.height() > 4 * area) c = null;
                        }
                    }
                    return c;                                // null: tap() taps the saved centre
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo ch = n.getChild(i);
                    if (ch != null) q.add(ch);
                }
            }
        }
        if (dump != null) {
            try {
                java.io.File f = new java.io.File(getExternalFilesDir(null), "remote_nodes.txt");
                java.nio.file.Files.write(f.toPath(), dump.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
