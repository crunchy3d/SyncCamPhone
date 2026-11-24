package com.crunchy3d.synccamphone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * Full-screen view of the last photo: pinch = zoom, drag = move, double tap = fit / 100 %.
 * Shows the thumbnail at once and replaces it with the saved JPEG when that is decoded.
 * The semi-transparent X (or Back) closes it.
 */
final class PhotoViewer extends FrameLayout {
    private final ZoomView zoom;
    private final Runnable onClose;
    private volatile boolean closed;

    PhotoViewer(Context ctx, Bitmap thumb, Uri jpeg, Runnable onClose) {
        super(ctx);
        this.onClose = onClose;
        setBackgroundColor(Color.BLACK);
        setClickable(true);                                  // nothing reaches the views below
        zoom = new ZoomView(ctx);
        addView(zoom, new LayoutParams(-1, -1));
        if (thumb != null) zoom.setBitmap(thumb);

        float dp = getResources().getDisplayMetrics().density;
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

        if (jpeg != null) {
            Handler ui = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                Bitmap full = decode(ctx, jpeg);
                if (full != null) ui.post(() -> {
                    if (closed) {
                        full.recycle();
                        return;
                    }
                    zoom.setBitmap(full);
                });
            }, "photo-viewer").start();
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        zoom.release();
        onClose.run();
    }

    /** The saved JPEG, upright (EXIF orientation), at full size if memory allows, else halved. */
    private static Bitmap decode(Context ctx, Uri uri) {
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
            o.inSampleSize = need * 3 < free ? 1 : 2;           // room for the rotated copy too
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
        } catch (Throwable e) {                              // incl. OutOfMemoryError: keep the thumbnail
            return null;
        }
    }

    /** Bitmap with pinch zoom, pan and double-tap fit / 100 %. */
    private static final class ZoomView extends View {
        private Bitmap bmp;
        private final Matrix m = new Matrix();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final ScaleGestureDetector scaler;
        private final GestureDetector gestures;

        ZoomView(Context ctx) {
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
         * Shows b. Replacing the thumbnail by the full image keeps the zoom and position if the
         * user already zoomed and both have the same shape; otherwise the image is fitted.
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
