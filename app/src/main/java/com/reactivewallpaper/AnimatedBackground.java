package com.reactivewallpaper;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.IOException;

/** An animated GIF or WebP, drawn into a reusable frame bitmap. */
@TargetApi(28)
final class AnimatedBackground implements MediaBackground {
    /** Large animations are drawn smaller; the wallpaper scales them up. */
    private static final int MAX_SIDE = 1280;

    private final AnimatedImageDrawable drawable;
    private final Bitmap frame;
    private final Canvas canvas;

    private AnimatedBackground(AnimatedImageDrawable drawable) {
        this.drawable = drawable;
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(w, h));
        int fw = Math.max(1, Math.round(w * scale));
        int fh = Math.max(1, Math.round(h * scale));
        drawable.setBounds(0, 0, fw, fh);
        frame = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(frame);
        drawable.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
        drawable.start();
    }

    /** Null if the file is a still image (then the plain image path is used). */
    static AnimatedBackground open(File file) throws IOException {
        Drawable d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file));
        return d instanceof AnimatedImageDrawable ? new AnimatedBackground((AnimatedImageDrawable) d) : null;
    }

    /** True if the file is an animated GIF/WebP this phone can play. */
    static boolean isAnimated(File file) {
        try {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) instanceof AnimatedImageDrawable;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public Bitmap frame() {
        frame.eraseColor(0);
        drawable.draw(canvas);
        return frame;
    }

    @Override
    public int width() {
        return frame.getWidth();
    }

    @Override
    public int height() {
        return frame.getHeight();
    }

    @Override
    public void setPlaying(boolean playing) {
        if (playing) {
            drawable.start();
        } else {
            drawable.stop();
        }
    }

    @Override
    public void release() {
        drawable.stop();
        frame.recycle();
    }
}
