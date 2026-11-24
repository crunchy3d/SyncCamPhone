package com.crunchy3d.synccamphone;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes files to Download/CamSyncProbe (API 29+) or the app's external files dir. A name may
 * start with a sub-folder ("Frames/...", "Info/...", "Mono/...").
 *
 * Stereo naming: every camera phone is told at start whether it is the LEFT or RIGHT camera of a
 * stereo pair (or MONO). Images / videos: 3D_img_0001-l.jpg / .dng, 3D_vid_0001-r.mp4 (stereo
 * software pairs them by the -l / -r ending); single burst frames Frames/3D_img_0001_frame0-l.dng;
 * information Info/3D_img_0001_info-l.txt; mono phones Mono/2D_img_0001.jpg (and Mono/Frames,
 * Mono/Info). The number is the master's photo / record number: it continues after the highest
 * number found on any phone (deleting all files starts at 0001 again).
 */
final class Downloads {
    private Downloads() {}

    static final int SIDE_LEFT = 0, SIDE_RIGHT = 1, SIDE_MONO = 2;
    static final String[] SIDE_NAMES = {"LEFT", "RIGHT", "MONO"};
    static volatile int side = SIDE_MONO;

    private static String stem(String kind, int n) {
        return String.format(Locale.US, "%s_%s_%04d", side == SIDE_MONO ? "2D" : "3D", kind, n);
    }

    private static String suffix() {
        return side == SIDE_LEFT ? "-l" : side == SIDE_RIGHT ? "-r" : "";
    }

    // time-lapse (2026-11-21): every sequence in its own folder "Timelapse_NNNN/" (NNNN = its first photo)
    static volatile String sub = "";

    private static String dir() {
        return (side == SIDE_MONO ? "Mono/" : "") + sub;
    }

    /** Image / video: "3D_img_0001-l.jpg" (kind "img" or "vid"). */
    static String mainName(String kind, int n, String ext) {
        return dir() + stem(kind, n) + suffix() + "." + ext;
    }

    /** Single burst frame k: "Frames/3D_img_0001_frame0-l.dng". */
    static String frameName(int n, int k) {
        return dir() + "Frames/" + stem("img", n) + "_frame" + k + suffix() + ".dng";
    }

    /** Side file: "Info/3D_img_0001_info-l.txt" (what = "info", "frames", ...). */
    static String infoName(String kind, int n, String what, String ext) {
        return dir() + "Info/" + stem(kind, n) + "_" + what + suffix() + "." + ext;
    }

    private static final java.util.regex.Pattern NUMBERED =
            java.util.regex.Pattern.compile("^[23]D_(img|vid)_(\\d+)");

    /**
     * Highest image and video number in Download/CamSyncProbe and its sub-folders: {img, vid}
     * (0 = none). Files of an earlier install are visible only with the photo / video permission.
     */
    static int[] maxNumbers(Context ctx) {
        int[] mx = {0, 0};
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri u = MediaStore.Files.getContentUri("external");
                try (android.database.Cursor c = ctx.getContentResolver().query(u,
                        new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR " + MediaStore.MediaColumns.RELATIVE_PATH
                                + " LIKE ?",
                        new String[]{Environment.DIRECTORY_DOWNLOADS + "/" + App.FOLDER + "%",
                                Environment.DIRECTORY_DOWNLOADS + "/" + App.OLD_FOLDER + "%"}, null)) {
                    while (c != null && c.moveToNext()) note(c.getString(0), mx);
                }
            } else {
                scanDir(ctx.getExternalFilesDir(null), mx);
            }
        } catch (Exception ignored) {
            // no permission / no files: numbering from the session only
        }
        return mx;
    }

    private static void scanDir(File d, int[] mx) {
        File[] fs = d == null ? null : d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) scanDir(f, mx);
            else note(f.getName(), mx);
        }
    }

    private static void note(String name, int[] mx) {
        if (name == null) return;
        java.util.regex.Matcher m = NUMBERED.matcher(name);
        if (!m.find()) return;
        try {
            int n = Integer.parseInt(m.group(2));
            int k = m.group(1).equals("img") ? 0 : 1;
            mx[k] = Math.max(mx[k], n);
        } catch (NumberFormatException ignored) {
            // more digits than an int: ignored
        }
    }

    /** "Frames/x.dng" -> {"/Frames", "x.dng"}; "x.dng" -> {"", "x.dng"}. */
    private static String[] split(String name) {
        int s = name.lastIndexOf('/');
        return s < 0 ? new String[]{"", name} : new String[]{"/" + name.substring(0, s), name.substring(s + 1)};
    }

    static final class Out {
        final OutputStream stream;
        final String path;
        final Uri uri;                     // for reading the file back (photo viewer)

        Out(OutputStream stream, String path, Uri uri) {
            this.stream = stream;
            this.path = path;
            this.uri = uri;
        }
    }

    /** e.g. "timing_cam0_Pixel-7a_20261114_001500.txt" */
    static String fileName(String base, String ext) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String model = Build.MODEL.replaceAll("[^A-Za-z0-9]+", "-");
        return base + "_" + model + "_" + stamp + "." + ext;
    }

    static Out open(Context ctx, String name, String mime) throws IOException {
        String[] sn = split(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, sn[1]);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + App.FOLDER + sn[0]);
            Uri uri = insert(ctx, v, sn[1]);
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("cannot open output");
            return new Out(os, "Download/" + App.FOLDER + sn[0] + "/"
                    + v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME), uri);
        }
        File f = local(ctx, name);
        return new Out(new FileOutputStream(f), f.getAbsolutePath(), Uri.fromFile(f));
    }

    private static File local(Context ctx, String name) {
        File f = new File(ctx.getExternalFilesDir(null), name);
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        return f;
    }

    /**
     * MediaStore insert; if Android refuses the name (it numbers duplicates "(1)" .. "(31)" and
     * then throws IllegalStateException), the name gets a time stamp instead.
     */
    private static Uri insert(Context ctx, ContentValues v, String name) throws IOException {
        Uri uri;
        try {
            uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        } catch (IllegalStateException e) {
            int dot = name.lastIndexOf('.');
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String alt = dot > 0 ? name.substring(0, dot) + "_" + stamp + name.substring(dot) : name + "_" + stamp;
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, alt);
            uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        }
        if (uri == null) throw new IOException("MediaStore insert failed");
        return uri;
    }

    static final class Fd {
        final ParcelFileDescriptor pfd;
        final String path;

        Fd(ParcelFileDescriptor pfd, String path) {
            this.pfd = pfd;
            this.path = path;
        }
    }

    /** Read/write file descriptor (MediaMuxer needs to seek). */
    static Fd openFd(Context ctx, String name, String mime) throws IOException {
        String[] sn = split(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, sn[1]);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + App.FOLDER + sn[0]);
            Uri uri = insert(ctx, v, sn[1]);
            ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "rw");
            if (pfd == null) throw new IOException("cannot open output");
            return new Fd(pfd, "Download/" + App.FOLDER + sn[0] + "/"
                    + v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        }
        File f = local(ctx, name);
        return new Fd(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE), f.getAbsolutePath());
    }

    static String mimeFor(String ext) {
        return ext.equals("csv") ? "text/csv" : "text/plain";
    }
}
