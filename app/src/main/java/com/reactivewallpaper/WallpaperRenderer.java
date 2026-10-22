package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Draws the image plus the audio-reactive effects. Shared by the wallpaper and the in-app preview. */
final class WallpaperRenderer {
    private static final float[] DEFAULT_HUES = {185f, 300f};

    private final float density;
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint();
    private final Matrix matrix = new Matrix();
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] hsv = new float[3];

    private Bitmap image;
    private float[] accentHues = DEFAULT_HUES;
    private float time;
    private int gradientHeight = -1;

    WallpaperRenderer(float density) {
        this.density = density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Takes ownership of the bitmap; the previous one is recycled. */
    void setImage(Bitmap bitmap) {
        if (image != null && image != bitmap) {
            image.recycle();
        }
        image = bitmap;
        accentHues = bitmap != null ? ImageStore.accentHues(bitmap) : DEFAULT_HUES;
    }

    void release() {
        setImage(null);
    }

    void draw(Canvas canvas, int w, int h, AudioAnalyzer audio, WallpaperSettings s, float dt) {
        time += dt;
        float intensity = s.intensity;
        float bass = audio.bass();
        float beat = audio.beat();

        drawBackground(canvas, w, h, s, bass, beat, intensity);

        if (s.flash && beat > 0.01f) {
            fillPaint.setShader(null);
            fillPaint.setColor(blend(Color.WHITE, colorAt(s, 0.5f), 0.5f));
            fillPaint.setAlpha((int) (255 * Math.min(0.45f, beat * 0.22f * intensity)));
            canvas.drawRect(0, 0, w, h, fillPaint);
        }

        switch (s.style) {
            case WallpaperSettings.STYLE_BARS:
                drawBars(canvas, w, h, audio.bands(), s);
                break;
            case WallpaperSettings.STYLE_CIRCLE:
                drawCircle(canvas, w, h, audio.bands(), s, bass);
                break;
            case WallpaperSettings.STYLE_WAVE:
                drawWave(canvas, w, h, audio.bands(), s);
                break;
            default:
                break;
        }
    }

    private void drawBackground(Canvas canvas, int w, int h, WallpaperSettings s,
                                float bass, float beat, float intensity) {
        float dx = 0f;
        float dy = 0f;
        float zoom = 1f;
        if (s.pulse) {
            zoom += 0.08f * intensity * bass + 0.03f * intensity * beat;
        }
        if (s.shake) {
            float amount = 9f * density * intensity * beat;
            dx = (float) Math.sin(time * 61f) * amount;
            dy = (float) Math.cos(time * 47f) * amount;
            // Overscan so shaking never reveals the edges.
            zoom += 0.05f * intensity;
        }

        if (image == null) {
            if (gradientHeight != h) {
                backgroundPaint.setShader(new LinearGradient(0, 0, 0, h,
                        0xFF1A1033, 0xFF05060F, Shader.TileMode.CLAMP));
                gradientHeight = h;
            }
            canvas.drawRect(0, 0, w, h, backgroundPaint);
            return;
        }

        canvas.drawColor(Color.BLACK);
        int bw = image.getWidth();
        int bh = image.getHeight();
        float scale = Math.max(w / (float) bw, h / (float) bh);
        matrix.setScale(scale, scale);
        matrix.postTranslate((w - bw * scale) / 2f + dx, (h - bh * scale) / 2f + dy);
        // Zoom around the ring when there is one, so artwork under it stays lined up.
        float pivotY = s.style == WallpaperSettings.STYLE_CIRCLE ? h * s.circleY : h / 2f;
        matrix.postScale(zoom, zoom, w / 2f, pivotY);
        canvas.drawBitmap(image, matrix, imagePaint);
    }

    private void drawBars(Canvas canvas, int w, int h, float[] bands, WallpaperSettings s) {
        int n = bands.length;
        if (n == 0) {
            return;
        }
        float margin = w * 0.05f;
        float slot = (w - 2 * margin) / n;
        float barWidth = Math.max(1f, slot * 0.62f);
        float radius = barWidth / 2f;
        float base = h * 0.9f;
        float maxHeight = h * 0.24f * s.intensity;
        float minHeight = 3f * density;
        fillPaint.setShader(null);
        for (int i = 0; i < n; i++) {
            float x = margin + slot * i + (slot - barWidth) / 2f;
            float barHeight = minHeight + bands[i] * maxHeight;
            int color = colorAt(s, i / (float) (n - 1));

            fillPaint.setColor(color);
            fillPaint.setAlpha(230);
            rect.set(x, base - barHeight, x + barWidth, base);
            canvas.drawRoundRect(rect, radius, radius, fillPaint);

            // Soft reflection under the bar.
            fillPaint.setAlpha(60);
            rect.set(x, base + 2f * density, x + barWidth, base + 2f * density + barHeight * 0.3f);
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
        }
    }

    private void drawCircle(Canvas canvas, int w, int h, float[] bands, WallpaperSettings s, float bass) {
        int n = bands.length;
        if (n == 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h * s.circleY;
        float radius = Math.min(w, h) * s.circleSize * (1f + 0.1f * bass * s.intensity);
        int spokes = n * 2;
        double step = 2 * Math.PI / spokes;
        float spokeWidth = Math.max(2f, (float) (radius * step * 0.55));
        strokePaint.setShader(null);
        for (int i = 0; i < spokes; i++) {
            // Mirror the spectrum so the ring is symmetric left/right.
            int band = i < n ? i : spokes - 1 - i;
            float value = bands[band];
            double angle = i * step - Math.PI / 2;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float length = 4f * density + value * radius * 0.75f * s.intensity;
            float x0 = cx + cos * radius;
            float y0 = cy + sin * radius;
            float x1 = cx + cos * (radius + length);
            float y1 = cy + sin * (radius + length);
            strokePaint.setColor(colorAt(s, band / (float) (n - 1)));
            // Wide faint stroke underneath for a neon glow.
            strokePaint.setStrokeWidth(spokeWidth * 2.6f);
            strokePaint.setAlpha(55);
            canvas.drawLine(x0, y0, x1, y1, strokePaint);
            strokePaint.setStrokeWidth(spokeWidth);
            strokePaint.setAlpha(235);
            canvas.drawLine(x0, y0, x1, y1, strokePaint);
        }
        strokePaint.setStrokeWidth(2f * density);
        strokePaint.setColor(colorAt(s, 0f));
        strokePaint.setAlpha(140);
        canvas.drawCircle(cx, cy, radius - 4f * density, strokePaint);
    }

    private void drawWave(Canvas canvas, int w, int h, float[] bands, WallpaperSettings s) {
        int n = bands.length;
        if (n < 2) {
            return;
        }
        float base = h * 0.86f;
        float maxHeight = h * 0.2f * s.intensity;
        float step = w / (float) (n - 1);

        path.reset();
        path.moveTo(0, base);
        float prevX = 0f;
        float prevY = base - bands[0] * maxHeight;
        path.lineTo(prevX, prevY);
        for (int i = 1; i < n; i++) {
            float x = i * step;
            float y = base - bands[i] * maxHeight;
            path.quadTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f);
            prevX = x;
            prevY = y;
        }
        path.lineTo(prevX, prevY);
        path.lineTo(w, base);
        path.close();

        int left = colorAt(s, 0f);
        int right = colorAt(s, 1f);
        fillPaint.setShader(new LinearGradient(0, 0, w, 0, left, right, Shader.TileMode.CLAMP));
        fillPaint.setAlpha(120);
        canvas.drawPath(path, fillPaint);
        strokePaint.setShader(fillPaint.getShader());
        strokePaint.setStrokeWidth(3f * density);
        strokePaint.setAlpha(255);
        canvas.drawPath(path, strokePaint);

        // Mirrored reflection below the baseline.
        canvas.save();
        canvas.scale(1f, -0.45f, 0, base);
        fillPaint.setAlpha(50);
        canvas.drawPath(path, fillPaint);
        canvas.restore();

        fillPaint.setShader(null);
        strokePaint.setShader(null);
    }

    /** Color for a position 0..1 across the spectrum. */
    private int colorAt(WallpaperSettings s, float position) {
        switch (s.colorMode) {
            case WallpaperSettings.COLOR_RAINBOW:
                hsv[0] = (position * 300f + time * 40f) % 360f;
                hsv[1] = 0.8f;
                hsv[2] = 1f;
                return Color.HSVToColor(hsv);
            case WallpaperSettings.COLOR_CUSTOM:
                hsv[0] = (s.hue + position * 40f) % 360f;
                hsv[1] = 0.8f;
                hsv[2] = 1f;
                return Color.HSVToColor(hsv);
            default:
                // Blend between the image's two main hues along the shorter way round.
                float from = accentHues[0];
                float delta = accentHues[1] - from;
                if (delta > 180f) {
                    delta -= 360f;
                }
                hsv[0] = ((from + delta * position) % 360f + 360f) % 360f;
                hsv[1] = 0.75f;
                hsv[2] = 1f;
                return Color.HSVToColor(hsv);
        }
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
