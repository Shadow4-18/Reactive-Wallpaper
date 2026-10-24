package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

/** Draws the image plus the audio-reactive effects. Shared by the wallpaper and the in-app preview. */
final class WallpaperRenderer {
    private static final float[] DEFAULT_HUES = {185f, 300f};
    private static final PorterDuffXfermode ADD = new PorterDuffXfermode(PorterDuff.Mode.ADD);

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
    private Bitmap mask;
    private MediaBackground media;
    private Bitmap mediaFrame;
    private final Matrix mediaMatrix = new Matrix();
    private final Matrix bloomMatrix = new Matrix();
    private final Paint mediaGlowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float mediaBreathePhase;
    private float glowFilterCoverage = Float.NaN;
    private float glowFilterHue = Float.NaN;
    private ColorMatrixColorFilter mediaGlowFilter;
    private LivingImage living;
    private float[] accentHues = DEFAULT_HUES;
    private float time;
    private float swayPhase;
    private int gradientHeight = -1;

    // Parallax inputs (-1..1) and their smoothed values.
    private float tiltX;
    private float tiltY;
    private float swipe;
    private float smoothTiltX;
    private float smoothTiltY;
    private float smoothSwipe;
    private float parallaxX;
    private float parallaxY;

    private float cyclePhase;
    private float appliedHue = Float.NaN;
    private ColorMatrixColorFilter hueFilter;
    private boolean saving;

    WallpaperRenderer(float density) {
        this.density = density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * Takes ownership of the image and its painted glow mask (may be null);
     * the previous ones are recycled.
     */
    void setImage(Bitmap bitmap, Bitmap glowMask) {
        if (image != null && image != bitmap) {
            image.recycle();
        }
        if (mask != null && mask != glowMask) {
            mask.recycle();
        }
        if (living != null) {
            living.release();
            living = null;
        }
        image = bitmap;
        mask = glowMask;
        accentHues = bitmap != null ? ImageStore.accentHues(bitmap) : DEFAULT_HUES;
        if (bitmap != null) {
            living = new LivingImage(bitmap, mask, accentHues, WallpaperSettings.DEFAULT_COVERAGE);
        }
    }

    void release() {
        setImage(null, null);
        setMedia(null);
    }

    /**
     * A moving background drawn instead of the still image (which stays as its
     * stand-in for sizing, colors and particles). Takes ownership; null for none.
     */
    void setMedia(MediaBackground next) {
        if (media != null && media != next) {
            media.release();
        }
        media = next;
        mediaFrame = null;
    }

    void setMediaPlaying(boolean playing) {
        if (media != null) {
            media.setPlaying(playing);
        }
    }

    /** Phone tilt from {@link TiltSensor}, -1..1 on each axis. */
    void setTilt(float x, float y) {
        tiltX = x;
        tiltY = y;
    }

    /** Home-screen position, -1 (first page) .. 1 (last page). */
    void setSwipe(float position) {
        swipe = position;
    }

    /** Battery saving: draws fewer particles. */
    void setSaving(boolean saving) {
        this.saving = saving;
    }

    void draw(Canvas canvas, int w, int h, AudioAnalyzer audio, WallpaperSettings s, float dt) {
        time += dt;
        swayPhase += dt * s.swaySpeed;
        cyclePhase = (cyclePhase + dt * s.colorCycle * 30f) % 360f;
        float bass = audio.bass();
        float beat = audio.beat();
        updateParallax(w, h, s, dt);
        ColorMatrixColorFilter filter = hueFilter((s.hueShift + cyclePhase) % 360f);
        imagePaint.setColorFilter(s.recolorImage ? filter : null);
        // Hardware bitmaps (video frames) can only be drawn on a hardware canvas.
        mediaFrame = media != null && canvas.isHardwareAccelerated() ? media.frame() : null;

        drawBackground(canvas, w, h, s, bass, beat);
        if (living != null) {
            if (Math.abs(living.coverage() - s.glowCoverage) > 0.001f) {
                living.release();
                living = new LivingImage(image, mask, accentHues, s.glowCoverage);
            }
            // Particles sit "in front" of the picture, so they move further with parallax.
            living.draw(canvas, matrix, audio, s, dt, density, parallaxX * 1.6f, parallaxY * 1.6f,
                    filter, saving ? 0.5f : 1f, mediaFrame == null);
        }
        if (mediaFrame != null) {
            drawMediaGlow(canvas, w, h, audio, s, dt);
        }

        if (s.flash > 0f && beat > 0.01f) {
            fillPaint.setShader(null);
            fillPaint.setColor(blend(Color.WHITE, colorAt(s, 0.5f), 0.5f));
            fillPaint.setAlpha((int) (255 * Math.min(0.6f, beat * 0.3f * s.flash)));
            canvas.drawRect(0, 0, w, h, fillPaint);
        }

        switch (s.style) {
            case WallpaperSettings.STYLE_BARS:
                drawBars(canvas, w, h, audio.bands(), s);
                break;
            case WallpaperSettings.STYLE_CIRCLE:
                // The ring is lined up with artwork, so it moves with the picture.
                canvas.save();
                canvas.translate(parallaxX, parallaxY);
                drawCircle(canvas, w, h, audio.bands(), s, bass);
                canvas.restore();
                break;
            case WallpaperSettings.STYLE_WAVE:
                drawWave(canvas, w, h, audio.bands(), s);
                break;
            default:
                break;
        }
    }

    private void drawBackground(Canvas canvas, int w, int h, WallpaperSettings s, float bass, float beat) {
        float dx = 0f;
        float dy = 0f;
        float zoom = 1f;
        if (s.pulse > 0f) {
            zoom += s.pulse * (0.08f * bass + 0.03f * beat);
        }
        if (s.sway > 0f) {
            // Slow sway so the picture never sits completely still.
            zoom += s.sway * (0.03f + 0.012f * (float) Math.sin(swayPhase * 0.21f));
            dx += (float) Math.sin(swayPhase * 0.13f) * 5f * density * s.sway;
            dy += (float) Math.cos(swayPhase * 0.17f) * 5f * density * s.sway;
        }
        if (s.tiltParallax > 0f || s.swipeParallax > 0f) {
            dx += parallaxX;
            dy += parallaxY;
            // Overscan so shifting never reveals the edges.
            zoom += 0.05f * s.tiltParallax + 0.08f * s.swipeParallax;
        }
        if (s.shake > 0f) {
            float amount = 9f * density * s.shake * beat;
            dx += (float) Math.sin(time * 61f) * amount;
            dy += (float) Math.cos(time * 47f) * amount;
            // Overscan so shaking never reveals the edges.
            zoom += 0.05f * s.shake;
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
        if (mediaFrame != null) {
            updateMediaMatrix();
            canvas.drawBitmap(mediaFrame, mediaMatrix, imagePaint);
        } else {
            canvas.drawBitmap(image, matrix, imagePaint);
        }
    }

    /** Maps the current media frame onto the still image's place on screen, turning it upright. */
    private void updateMediaMatrix() {
        int fw = mediaFrame.getWidth();
        int fh = mediaFrame.getHeight();
        mediaMatrix.reset();
        switch (media.rotation()) {
            case 90:
                mediaMatrix.postRotate(90);
                mediaMatrix.postTranslate(fh, 0);
                break;
            case 180:
                mediaMatrix.postRotate(180);
                mediaMatrix.postTranslate(fw, fh);
                break;
            case 270:
                mediaMatrix.postRotate(270);
                mediaMatrix.postTranslate(0, fw);
                break;
            default:
                break;
        }
        int uw = media.rotation() % 180 == 0 ? fw : fh;
        int uh = media.rotation() % 180 == 0 ? fh : fw;
        mediaMatrix.postScale(image.getWidth() / (float) uw, image.getHeight() / (float) uh);
        mediaMatrix.postConcat(matrix);
    }

    /**
     * Glow for moving backgrounds: the frame's bright parts (picked out on the
     * GPU by a threshold color filter) added back on top, brighter with the music.
     */
    private void drawMediaGlow(Canvas canvas, int w, int h, AudioAnalyzer audio, WallpaperSettings s, float dt) {
        mediaBreathePhase += dt * s.breatheSpeed;
        float idle = s.breathe * (0.3f + 0.22f * (float) Math.sin(mediaBreathePhase * 1.1f));
        float amount = idle + s.bassGlow * audio.bass() * 1.1f + s.highGlow * audio.high() * 0.9f
                + s.beatFlare * audio.beat();
        amount = Math.min(amount, 4f);
        if (amount <= 0.01f) {
            return;
        }
        mediaGlowPaint.setXfermode(ADD);
        mediaGlowPaint.setColorFilter(mediaGlowFilter(s.glowCoverage, (s.hueShift + cyclePhase) % 360f));
        for (float left = amount * 0.8f; left > 0.01f; left -= 1f) {
            mediaGlowPaint.setAlpha((int) (Math.min(1f, left) * 255));
            canvas.drawBitmap(mediaFrame, mediaMatrix, mediaGlowPaint);
        }
        // A soft halo: the same highlights drawn slightly enlarged and faint.
        for (float spread : new float[] {1.025f, 1.055f}) {
            float alpha = Math.min(1f, amount * s.bloom * 0.3f);
            if (alpha <= 0.01f) {
                break;
            }
            bloomMatrix.set(mediaMatrix);
            bloomMatrix.postScale(spread, spread, w / 2f, h / 2f);
            mediaGlowPaint.setAlpha((int) (alpha * 255));
            canvas.drawBitmap(mediaFrame, bloomMatrix, mediaGlowPaint);
        }
    }

    /** Keeps only colors brighter than the coverage threshold, stretched back to full range. */
    private ColorMatrixColorFilter mediaGlowFilter(float coverage, float hue) {
        if (coverage == glowFilterCoverage && Math.abs(hue - glowFilterHue) < 0.5f && mediaGlowFilter != null) {
            return mediaGlowFilter;
        }
        float t = 0.7f - 0.55f * coverage;
        float k = 1f / (1f - t);
        float offset = -t * k * 255f;
        ColorMatrix m = new ColorMatrix(new float[] {
                k, 0, 0, 0, offset,
                0, k, 0, 0, offset,
                0, 0, k, 0, offset,
                0, 0, 0, 1, 0,
        });
        if (hue >= 0.5f && hue <= 359.5f) {
            ColorMatrix rotate = new ColorMatrix();
            rotate.set(hueMatrix(hue));
            m.postConcat(rotate);
        }
        mediaGlowFilter = new ColorMatrixColorFilter(m);
        glowFilterCoverage = coverage;
        glowFilterHue = hue;
        return mediaGlowFilter;
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
        float maxHeight = h * 0.24f * s.vizHeight;
        float minHeight = 3f * density;
        fillPaint.setShader(null);
        for (int i = 0; i < n; i++) {
            float x = margin + slot * i + (slot - barWidth) / 2f;
            float barHeight = minHeight + bands[i] * maxHeight;
            int color = colorAt(s, i / (float) (n - 1));

            fillPaint.setColor(color);
            fillPaint.setAlpha((int) (255 * s.vizOpacity));
            rect.set(x, base - barHeight, x + barWidth, base);
            canvas.drawRoundRect(rect, radius, radius, fillPaint);

            // Soft reflection under the bar.
            fillPaint.setAlpha((int) (65 * s.vizOpacity));
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
        float radius = Math.min(w, h) * s.circleSize * (1f + 0.1f * bass * s.vizHeight);
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
            float length = 4f * density + value * radius * 0.75f * s.vizHeight;
            float x0 = cx + cos * radius;
            float y0 = cy + sin * radius;
            float x1 = cx + cos * (radius + length);
            float y1 = cy + sin * (radius + length);
            strokePaint.setColor(colorAt(s, band / (float) (n - 1)));
            // Wide faint stroke underneath for a neon glow.
            strokePaint.setStrokeWidth(spokeWidth * 2.6f);
            strokePaint.setAlpha((int) (60 * s.vizOpacity));
            canvas.drawLine(x0, y0, x1, y1, strokePaint);
            strokePaint.setStrokeWidth(spokeWidth);
            strokePaint.setAlpha((int) (255 * s.vizOpacity));
            canvas.drawLine(x0, y0, x1, y1, strokePaint);
        }
        strokePaint.setStrokeWidth(2f * density);
        strokePaint.setColor(colorAt(s, 0f));
        strokePaint.setAlpha((int) (150 * s.vizOpacity));
        canvas.drawCircle(cx, cy, radius - 4f * density, strokePaint);
    }

    private void drawWave(Canvas canvas, int w, int h, float[] bands, WallpaperSettings s) {
        int n = bands.length;
        if (n < 2) {
            return;
        }
        float base = h * 0.86f;
        float maxHeight = h * 0.2f * s.vizHeight;
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
        fillPaint.setAlpha((int) (130 * s.vizOpacity));
        canvas.drawPath(path, fillPaint);
        strokePaint.setShader(fillPaint.getShader());
        strokePaint.setStrokeWidth(3f * density);
        strokePaint.setAlpha((int) (255 * s.vizOpacity));
        canvas.drawPath(path, strokePaint);

        // Mirrored reflection below the baseline.
        canvas.save();
        canvas.scale(1f, -0.45f, 0, base);
        fillPaint.setAlpha((int) (55 * s.vizOpacity));
        canvas.drawPath(path, fillPaint);
        canvas.restore();

        fillPaint.setShader(null);
        strokePaint.setShader(null);
    }

    private void updateParallax(int w, int h, WallpaperSettings s, float dt) {
        float follow = Math.min(1f, dt * 8f);
        smoothTiltX += (tiltX - smoothTiltX) * follow;
        smoothTiltY += (tiltY - smoothTiltY) * follow;
        smoothSwipe += (swipe - smoothSwipe) * Math.min(1f, dt * 12f);
        // The picture moves against the tilt, like looking through a window.
        parallaxX = -(smoothTiltX * s.tiltParallax * 0.025f + smoothSwipe * s.swipeParallax * 0.04f) * w;
        parallaxY = -smoothTiltY * s.tiltParallax * 0.025f * h;
    }

    /** A brightness-preserving hue rotation, cached while the angle holds still. */
    private ColorMatrixColorFilter hueFilter(float degrees) {
        if (degrees < 0.5f || degrees > 359.5f) {
            appliedHue = Float.NaN;
            hueFilter = null;
            return null;
        }
        if (Math.abs(degrees - appliedHue) < 0.5f) {
            return hueFilter;
        }
        hueFilter = new ColorMatrixColorFilter(new ColorMatrix(hueMatrix(degrees)));
        appliedHue = degrees;
        return hueFilter;
    }

    private static float[] hueMatrix(float degrees) {
        double rad = Math.toRadians(degrees);
        float c = (float) Math.cos(rad);
        float n = (float) Math.sin(rad);
        return new float[] {
                0.213f + c * 0.787f - n * 0.213f, 0.715f - c * 0.715f - n * 0.715f, 0.072f - c * 0.072f + n * 0.928f, 0, 0,
                0.213f - c * 0.213f + n * 0.143f, 0.715f + c * 0.285f + n * 0.140f, 0.072f - c * 0.072f - n * 0.283f, 0, 0,
                0.213f - c * 0.213f - n * 0.787f, 0.715f - c * 0.715f + n * 0.715f, 0.072f + c * 0.928f + n * 0.072f, 0, 0,
                0, 0, 0, 1, 0,
        };
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
                hsv[0] = ((from + delta * position + s.hueShift + cyclePhase) % 360f + 360f) % 360f;
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
