package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Turns a square album cover into a full-screen background. */
final class AlbumArt {
    private AlbumArt() {}

    /**
     * @param style {@link WallpaperSettings#ART_FILL} or {@link WallpaperSettings#ART_CARD}
     * @return a new bitmap about the shape of the {@code w x h} screen
     */
    static Bitmap compose(Bitmap art, int w, int h, int style) {
        // Covers are small (often 512-1000 px), so a smaller canvas loses nothing.
        float shrink = Math.min(1f, 1400f / Math.max(w, h));
        int cw = Math.max(1, Math.round(w * shrink));
        int ch = Math.max(1, Math.round(h * shrink));
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (style == WallpaperSettings.ART_FILL) {
            canvas.drawBitmap(art, null, cover(art, cw, ch), paint);
            return out;
        }

        // Backdrop: the cover blown up very soft (shrunk tiny, then scaled back up) and dimmed.
        Bitmap tiny = Bitmap.createScaledBitmap(art, 12, 12, true);
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.55f, 0.55f, 0.55f, 1f);
        Paint backdrop = new Paint(Paint.FILTER_BITMAP_FLAG);
        backdrop.setColorFilter(new ColorMatrixColorFilter(dim));
        canvas.drawBitmap(tiny, null, cover(tiny, cw, ch), backdrop);
        tiny.recycle();

        // The cover itself as a rounded card a little above center.
        float size = cw * 0.78f;
        float left = (cw - size) / 2f;
        float top = ch * 0.40f - size / 2f;
        RectF card = new RectF(left, top, left + size, top + size);
        float radius = size * 0.045f;
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x66000000);
        canvas.drawRoundRect(new RectF(card.left, card.top + size * 0.02f, card.right, card.bottom + size * 0.03f),
                radius, radius, shadow);
        Path clip = new Path();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.drawBitmap(art, null, card, paint);
        canvas.restore();
        return out;
    }

    /** Destination rect that fills {@code w x h} with the bitmap, cropping the overflow. */
    private static RectF cover(Bitmap b, int w, int h) {
        float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        float dw = b.getWidth() * scale;
        float dh = b.getHeight() * scale;
        return new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
    }
}
