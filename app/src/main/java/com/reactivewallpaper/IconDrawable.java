package com.reactivewallpaper;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Small line icons drawn in code, so they look the same on every phone
 * (symbol characters depend on each phone's fonts, which size and place them
 * differently). Always centered in their bounds.
 */
final class IconDrawable extends Drawable {
    static final int FULL_SCREEN = 0;
    static final int CLOSE = 1;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    /** @param stroke line width in pixels */
    IconDrawable(int kind, int color, float stroke) {
        this.kind = kind;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        // The icon fills 40% of the button, centered.
        float half = Math.min(b.width(), b.height()) * 0.2f;
        path.reset();
        if (kind == FULL_SCREEN) {
            // Four corner brackets.
            float arm = half * 0.75f;
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    float x = cx + sx * half;
                    float y = cy + sy * half;
                    path.moveTo(x - sx * arm, y);
                    path.lineTo(x, y);
                    path.lineTo(x, y - sy * arm);
                }
            }
        } else {
            float r = half * 0.85f;
            path.moveTo(cx - r, cy - r);
            path.lineTo(cx + r, cy + r);
            path.moveTo(cx + r, cy - r);
            path.lineTo(cx - r, cy + r);
        }
        canvas.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
