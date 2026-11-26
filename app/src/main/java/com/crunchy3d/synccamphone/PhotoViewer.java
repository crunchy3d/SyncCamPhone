package com.crunchy3d.synccamphone;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full-screen viewer of this phone's photos and videos (user 2026-11-26): opens on the last one; SWIPE right = older,
 * left = newer (the files of this phone's folder, Download/SyncCamPhone or .../Mono, in time order). Photos: pinch =
 * zoom, drag = move, double tap = fit / 100 %; a swipe changes the photo only while it is fitted. Videos: first frame
 * with a play button, tap = play / pause, a position bar, sound. RAW-only photos (DNG): a placeholder with the name.
 * Only the shown photo is decoded at full size. The X (or Back) closes it.
 */
final class PhotoViewer extends FrameLayout {
    private final ZoomView zoom;
    private final Runnable onClose;
    private volatile boolean closed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView label, placeholder, playButton;
    private final FrameLayout videoBox;
    private final VideoView video;
    private final SeekBar seek;
    private final View videoTouch;
    private final List<Item> items = new ArrayList<>();
    private int index = -1;
    private int loadSeq;                      // decode generation (a newer item cancels an older decode)

    /** One numbered capture: the photo's JPEG (or only a DNG) or the video's MP4. */
    static final class Item {
        final String kind, name;              // "img" / "vid", display name of the shown file
        final int number;
        final Uri uri;
        final boolean rawOnly;
        final long time;

        Item(String kind, int number, String name, Uri uri, boolean rawOnly, long time) {
            this.kind = kind;
            this.number = number;
            this.name = name;
            this.uri = uri;
            this.rawOnly = rawOnly;
            this.time = time;
        }
    }

    PhotoViewer(Context ctx, Bitmap thumb, Uri start, Runnable onClose) {
        super(ctx);
        this.onClose = onClose;
        setBackgroundColor(Color.BLACK);
        setClickable(true);                                  // nothing reaches the views below
        float dp = getResources().getDisplayMetrics().density;
        zoom = new ZoomView(ctx, this::step);
        addView(zoom, new LayoutParams(-1, -1));
        if (thumb != null) zoom.setBitmap(thumb);

        // video: VideoView, a touch layer (tap = play / pause, swipe = next), play symbol, position bar
        videoBox = new FrameLayout(ctx);
        video = new VideoView(ctx);
        videoBox.addView(video, new LayoutParams(-1, -1, Gravity.CENTER));
        playButton = new TextView(ctx);
        playButton.setText("▶");
        playButton.setTextSize(64);
        playButton.setTextColor(0xDDFFFFFF);
        playButton.setGravity(Gravity.CENTER);
        videoBox.addView(playButton, new LayoutParams(-1, -1));
        videoTouch = new View(ctx);
        GestureDetector vg = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                togglePlay();
                return true;
            }

            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > 800) {
                    step(vx > 0 ? -1 : 1);
                    return true;
                }
                return false;
            }
        });
        videoTouch.setOnTouchListener((v, e) -> vg.onTouchEvent(e));
        videoBox.addView(videoTouch, new LayoutParams(-1, -1));
        seek = new SeekBar(ctx);
        LayoutParams sl = new LayoutParams(-1, (int) (48 * dp), Gravity.BOTTOM);
        sl.setMargins((int) (24 * dp), 0, (int) (24 * dp), (int) (16 * dp));
        videoBox.addView(seek, sl);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) video.seekTo(p);
            }

            @Override public void onStartTrackingTouch(SeekBar s) {
            }

            @Override public void onStopTrackingTouch(SeekBar s) {
            }
        });
        video.setOnPreparedListener(mp -> {
            seek.setMax(video.getDuration());
            video.seekTo(1);                                  // shows the first frame
        });
        video.setOnCompletionListener(mp -> playButton.setVisibility(VISIBLE));
        videoBox.setVisibility(GONE);
        addView(videoBox, new LayoutParams(-1, -1));

        placeholder = new TextView(ctx);
        placeholder.setTextColor(Color.LTGRAY);
        placeholder.setTextSize(18);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setVisibility(GONE);
        addView(placeholder, new LayoutParams(-1, -1));

        label = new TextView(ctx);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        label.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
        GradientDrawable lb = new GradientDrawable();
        lb.setCornerRadius(8 * dp);
        lb.setColor(0x88000000);
        label.setBackground(lb);
        LayoutParams ll = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ll.setMargins(0, (int) (20 * dp), 0, 0);
        addView(label, ll);

        TextView x = new TextView(ctx);
        x.setText("✕");
        x.setTextSize(26);
        x.setTextColor(Color.WHITE);
        x.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x66000000);
        bg.setStroke((int) (1.5f * dp), 0x88FFFFFF);
        x.setBackground(bg);
        LayoutParams xl = new LayoutParams((int) (56 * dp), (int) (56 * dp), Gravity.TOP | Gravity.END);
        xl.setMargins(0, (int) (16 * dp), (int) (16 * dp), 0);
        addView(x, xl);
        x.setOnClickListener(v -> close());

        // the list of this phone's captures (in the background), then the start item
        new Thread(() -> {
            List<Item> list = listItems(ctx);
            ui.post(() -> {
                if (closed) return;
                items.addAll(list);
                int s = items.size() - 1;
                if (start != null) {
                    for (int i = 0; i < items.size(); i++) {
                        if (sameId(items.get(i).uri, start)) s = i;   // downloads/ vs file/ URIs
                    }
                }
                if (s >= 0) show(s, thumb);
                else if (start != null) showUri(start);          // not in the list (yet): just this photo
                else label.setText("no photos or videos yet");
            });
        }, "viewer-list").start();
        ui.postDelayed(progress, 250);
    }

    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (videoBox.getVisibility() == VISIBLE && video.isPlaying()) seek.setProgress(video.getCurrentPosition());
            ui.postDelayed(this, 250);
        }
    };

    private void togglePlay() {
        if (video.isPlaying()) {
            video.pause();
            playButton.setVisibility(VISIBLE);
        } else {
            video.start();
            playButton.setVisibility(GONE);
        }
    }

    /** d = -1 older, +1 newer. */
    private void step(int d) {
        int n = index + d;
        if (n < 0 || n >= items.size()) return;
        show(n, null);
    }

    private void show(int i, Bitmap quick) {
        index = i;
        Item it = items.get(i);
        int seq = ++loadSeq;
        label.setText(String.format(Locale.US, "%s #%04d  ·  %d of %d", it.kind.equals("vid") ? "video" : "photo",
                it.number, i + 1, items.size()));
        if (video.isPlaying()) video.stopPlayback();
        if (it.kind.equals("vid")) {
            zoom.setVisibility(GONE);
            placeholder.setVisibility(GONE);
            videoBox.setVisibility(VISIBLE);
            playButton.setVisibility(VISIBLE);
            seek.setProgress(0);
            video.setVideoURI(it.uri);
            return;
        }
        videoBox.setVisibility(GONE);
        if (it.rawOnly) {
            zoom.setVisibility(GONE);
            zoom.release();
            placeholder.setText(it.name + "\n\n(RAW only - no preview)");
            placeholder.setVisibility(VISIBLE);
            return;
        }
        placeholder.setVisibility(GONE);
        zoom.setVisibility(VISIBLE);
        if (quick != null) zoom.setBitmap(quick);
        final Context ctx = getContext();
        new Thread(() -> {
            if (quick == null) {                                 // a small version at once, then the full one
                Bitmap small = decode(ctx, it.uri, 8);
                if (small != null) ui.post(() -> {
                    if (!closed && seq == loadSeq) zoom.setBitmap(small);
                });
            }
            Bitmap full = decode(ctx, it.uri, 0);
            if (full != null) ui.post(() -> {
                if (closed || seq != loadSeq) {
                    full.recycle();
                    return;
                }
                zoom.setBitmap(full);
            });
        }, "photo-viewer").start();
    }

    /** A photo that is not in the list (e.g. still being indexed): shown alone. */
    private void showUri(Uri u) {
        final Context ctx = getContext();
        new Thread(() -> {
            Bitmap full = decode(ctx, u, 0);
            if (full != null) ui.post(() -> {
                if (!closed) zoom.setBitmap(full);
            });
        }, "photo-viewer").start();
    }

    private static boolean sameId(Uri a, Uri b) {
        try {
            return ContentUris.parseId(a) == ContentUris.parseId(b);
        } catch (Exception e) {
            return a.equals(b);
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
        }
        zoom.release();
        onClose.run();
    }

    private static final Pattern NUM = Pattern.compile("^[23]D_(img|vid)_(\\d+)(-[lr])?\\.(jpg|dng|mp4)$");

    /**
     * This phone's photos and videos in its folder (Download/SyncCamPhone/ or .../Mono/), one item per number: the
     * JPEG of a photo (a DNG only when there is no JPEG), the MP4 of a video; oldest first.
     */
    static List<Item> listItems(Context ctx) {
        List<Item> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 29) return out;
        String rel = Environment.DIRECTORY_DOWNLOADS + "/" + App.FOLDER + "/"
                + (Downloads.side == Downloads.SIDE_MONO ? "Mono/" : "");
        Map<String, Item> byKey = new LinkedHashMap<>();
        Uri files = MediaStore.Files.getContentUri("external");
        try (Cursor c = ctx.getContentResolver().query(files,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_MODIFIED},
                MediaStore.MediaColumns.RELATIVE_PATH + " = ?", new String[]{rel}, null)) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(1);
                if (name == null) continue;
                Matcher m = NUM.matcher(name);
                if (!m.matches()) continue;
                String kind = m.group(1), ext = m.group(4);
                int n;
                try {
                    n = Integer.parseInt(m.group(2));
                } catch (NumberFormatException e) {
                    continue;
                }
                Uri u = ContentUris.withAppendedId(files, c.getLong(0));
                String key = kind + n;
                Item old = byKey.get(key);
                boolean dng = ext.equals("dng");
                if (old == null || (old.rawOnly && !dng)) {
                    byKey.put(key, new Item(kind, n, name, u, dng, c.getLong(2)));
                }
            }
        } catch (Exception ignored) {
            // no access: an empty list (the viewer still shows the last photo)
        }
        out.addAll(byKey.values());
        out.sort((a, b) -> a.time != b.time ? Long.compare(a.time, b.time)
                : a.kind.equals(b.kind) ? Integer.compare(a.number, b.number) : a.kind.compareTo(b.kind));
        return out;
    }

    /** First frame of the newest video of this phone (for the thumbnail under the shutter), or null. */
    static Bitmap lastVideoFrame(Context ctx) {
        List<Item> l = listItems(ctx);
        for (int i = l.size() - 1; i >= 0; i--) {
            if (!l.get(i).kind.equals("vid")) continue;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(ctx, l.get(i).uri);
                Bitmap b = r.getFrameAtTime(0);
                if (b == null) return null;
                int w = 320, h = Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth()));
                Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
                if (s != b) b.recycle();
                return s;
            } catch (Exception e) {
                return null;
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /** The JPEG, upright (EXIF orientation); sample 0 = full size if memory allows (else halved), else 1/sample. */
    private static Bitmap decode(Context ctx, Uri uri, int sample) {
        try {
            int orient = android.media.ExifInterface.ORIENTATION_NORMAL;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in != null) orient = new android.media.ExifInterface(in).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            Runtime rt = Runtime.getRuntime();
            long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
            long need = (long) o.outWidth * o.outHeight * 4;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample > 0 ? sample : need * 3 < free ? 1 : 2;    // room for the rotated copy too
            Bitmap b;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            if (b == null) return null;
            int deg = orient == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : orient == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : orient == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg == 0) return b;
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            b.recycle();
            return r;
        } catch (Throwable e) {                              // incl. OutOfMemoryError: keep what is shown
            return null;
        }
    }

    interface Stepper {
        void step(int d);
    }

    /** Bitmap with pinch zoom, pan, double-tap fit / 100 %, and a swipe to the next / previous item while fitted. */
    private static final class ZoomView extends View {
        private Bitmap bmp;
        private final Matrix m = new Matrix();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final ScaleGestureDetector scaler;
        private final GestureDetector gestures;

        ZoomView(Context ctx, Stepper stepper) {
            super(ctx);
            scaler = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float s = d.getScaleFactor(), cur = scale();
                    float min = fitScale() * 0.8f, max = Math.max(fitScale(), 1f) * 4f;
                    s = Math.max(min / cur, Math.min(max / cur, s));
                    m.postScale(s, s, d.getFocusX(), d.getFocusY());
                    keepInside();
                    invalidate();
                    return true;
                }
            });
            gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    m.postTranslate(-dx, -dy);
                    keepInside();
                    invalidate();
                    return true;
                }

                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    // only while the photo is fitted (zoomed in, a swipe moves within the photo)
                    if (scale() <= fitScale() * 1.05f && Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > 800) {
                        stepper.step(vx > 0 ? -1 : 1);
                        return true;
                    }
                    return false;
                }

                @Override public boolean onDoubleTap(MotionEvent e) {
                    float cur = scale(), fit = fitScale();
                    if (cur > fit * 1.05f) {
                        fit();
                    } else {                                 // 100 % (one image pixel = one screen pixel)
                        float s = Math.max(1f, fit * 2f) / cur;
                        m.postScale(s, s, e.getX(), e.getY());
                        keepInside();
                    }
                    invalidate();
                    return true;
                }
            });
        }

        /**
         * Shows b. Replacing a small version by the full image keeps the zoom and position if the user already
         * zoomed and both have the same shape; otherwise the image is fitted.
         */
        void setBitmap(Bitmap b) {
            Bitmap old = bmp;
            bmp = b;
            boolean sameShape = old != null && Math.abs((float) old.getWidth() / old.getHeight()
                    - (float) b.getWidth() / b.getHeight()) < 0.02f;
            if (old != null && sameShape && scale() > fitScaleOf(old) * 1.05f) {
                float r = (float) old.getWidth() / b.getWidth();
                m.preScale(r, r);
                keepInside();
            } else {
                fit();
            }
            invalidate();
        }

        private float fitScaleOf(Bitmap b) {
            if (getWidth() == 0) return 1f;
            return Math.min((float) getWidth() / b.getWidth(), (float) getHeight() / b.getHeight());
        }

        void release() {
            bmp = null;
            invalidate();
        }

        private float scale() {
            float[] v = new float[9];
            m.getValues(v);
            return v[Matrix.MSCALE_X];
        }

        private float fitScale() {
            if (bmp == null || getWidth() == 0) return 1f;
            return Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
        }

        private void fit() {
            if (bmp == null || getWidth() == 0) return;
            float s = fitScale();
            m.reset();
            m.postScale(s, s);
            m.postTranslate((getWidth() - bmp.getWidth() * s) / 2, (getHeight() - bmp.getHeight() * s) / 2);
        }

        /** No empty space where the image could fill the view; small images centred. */
        private void keepInside() {
            if (bmp == null) return;
            float[] v = new float[9];
            m.getValues(v);
            float s = v[Matrix.MSCALE_X], w = bmp.getWidth() * s, h = bmp.getHeight() * s;
            float tx = v[Matrix.MTRANS_X], ty = v[Matrix.MTRANS_Y];
            float nx = w <= getWidth() ? (getWidth() - w) / 2 : Math.min(0, Math.max(getWidth() - w, tx));
            float ny = h <= getHeight() ? (getHeight() - h) / 2 : Math.min(0, Math.max(getHeight() - h, ty));
            m.postTranslate(nx - tx, ny - ty);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            fit();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            if (!scaler.isInProgress()) gestures.onTouchEvent(e);
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            if (bmp != null && !bmp.isRecycled()) c.drawBitmap(bmp, m, paint);
        }
    }
}
