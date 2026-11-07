package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;

/**
 * One-off moments: an intro when the phone is unlocked (or the lock screen
 * wakes) and a wave of light when a charger is plugged in. The renderer asks
 * for the zoom, glow boost and darkness to apply, then draws the overlay.
 */
final class MomentEffects {
    static final float INTRO_SECONDS = 1.3f;
    static final float CHARGE_SECONDS = 2.2f;

    private static final PorterDuffXfermode ADD = new PorterDuffXfermode(PorterDuff.Mode.ADD);

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap glow = Sprites.tint(Sprites.glow(64), 64, 64, Color.WHITE);
    private final android.graphics.Matrix spriteMatrix = new android.graphics.Matrix();
    private final Paint sprite = new Paint(Paint.FILTER_BITMAP_FLAG);

    private int intro = WallpaperSettings.INTRO_OFF;
    private float introAge = -1f;
    private float chargeAge = -1f;
    private float pulseAge = -1f;
    private int pulseColor;
    static final float PULSE_SECONDS = 1.8f;
    private int chargePercent = -1;

    MomentEffects(float density) {
        this.density = density;
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        sprite.setXfermode(ADD);
    }

    void startIntro(int style) {
        if (style != WallpaperSettings.INTRO_OFF) {
            intro = style;
            introAge = 0f;
        }
    }

    /** @param percent the battery level to show, or -1 for none */
    void startCharge(int percent) {
        chargeAge = 0f;
        chargePercent = percent;
    }

    /** A glow around the screen's edges that breathes twice, in {@code color}. */
    void startPulse(int color) {
        pulseAge = 0f;
        pulseColor = color;
    }

    boolean isActive() {
        return introAge >= 0f || chargeAge >= 0f || pulseAge >= 0f;
    }

    void advance(float dt) {
        if (introAge >= 0f) {
            introAge += dt;
            if (introAge > INTRO_SECONDS) {
                introAge = -1f;
            }
        }
        if (chargeAge >= 0f) {
            chargeAge += dt;
            if (chargeAge > CHARGE_SECONDS) {
                chargeAge = -1f;
            }
        }
        if (pulseAge >= 0f) {
            pulseAge += dt;
            if (pulseAge > PULSE_SECONDS) {
                pulseAge = -1f;
            }
        }
    }

    /** 0..1: two soft breaths over the pulse's length. */
    private float pulseLevel() {
        if (pulseAge < 0f) {
            return 0f;
        }
        float t = pulseAge / PULSE_SECONDS;
        float breath = (float) Math.sin(Math.PI * Math.min(1f, (t * 2f) % 1f));
        return breath * (t < 0.5f ? 1f : 0.65f);
    }

    private float introT() {
        return introAge < 0f ? 1f : Math.min(1f, introAge / INTRO_SECONDS);
    }

    private static float easeOut(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Extra zoom on the picture: the zoom intro settles from close in. */
    float zoom() {
        if (introAge < 0f || intro != WallpaperSettings.INTRO_ZOOM) {
            return 0f;
        }
        return 0.14f * (1f - easeOut(introT()));
    }

    /** Multiplier on the picture's glow: a bloom that fades as the intro settles. */
    float glowBoost() {
        float boost = 0f;
        if (introAge >= 0f && intro != WallpaperSettings.INTRO_FADE) {
            float t = introT();
            // Rises fast, then falls away.
            boost += 3.5f * (float) Math.sin(Math.PI * Math.min(1f, t * 1.4f)) * (1f - t);
        }
        if (chargeAge >= 0f) {
            float t = chargeAge / CHARGE_SECONDS;
            boost += 1.5f * (float) Math.sin(Math.PI * t);
        }
        boost += 0.8f * pulseLevel();
        return boost;
    }

    /** Draws over the finished frame: darkness lifting, light waves, sparks, the battery level. */
    void draw(Canvas canvas, int w, int h, int color, int accent) {
        if (introAge >= 0f) {
            float t = introT();
            if (intro == WallpaperSettings.INTRO_FADE || intro == WallpaperSettings.INTRO_ZOOM) {
                // Starts black and lifts; the fade intro lifts from the bottom up.
                float dark = 1f - easeOut(t);
                if (intro == WallpaperSettings.INTRO_FADE) {
                    float edge = h * (1.15f - 1.3f * easeOut(t));
                    paint.setXfermode(null);
                    paint.setShader(new LinearGradient(0, edge - h * 0.3f, 0, edge,
                            Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP));
                    paint.setAlpha((int) (255 * Math.min(1f, dark * 1.6f)));
                    canvas.drawRect(0, 0, w, h, paint);
                    paint.setShader(null);
                } else {
                    paint.setShader(null);
                    paint.setXfermode(null);
                    paint.setColor(Color.BLACK);
                    paint.setAlpha((int) (255 * dark));
                    canvas.drawRect(0, 0, w, h, paint);
                }
            } else if (intro == WallpaperSettings.INTRO_WAVE) {
                // Rings of light spread from the middle across the whole screen.
                float reach = (float) Math.hypot(w, h) * 0.6f;
                for (int k = 0; k < 3; k++) {
                    float tk = (t - k * 0.12f) / 0.75f;
                    if (tk <= 0f || tk >= 1f) {
                        continue;
                    }
                    float r = reach * easeOut(tk);
                    float fade = 1f - tk;
                    paint.setShader(null);
                    paint.setXfermode(ADD);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(k == 1 ? accent : color);
                    paint.setStrokeWidth((26f - k * 6f) * density);
                    paint.setAlpha((int) (70 * fade));
                    canvas.drawCircle(w / 2f, h / 2f, r, paint);
                    paint.setStrokeWidth((5f - k) * density);
                    paint.setAlpha((int) (230 * fade));
                    canvas.drawCircle(w / 2f, h / 2f, r, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                paint.setXfermode(null);
            }
        }
        if (chargeAge >= 0f) {
            drawCharge(canvas, w, h, color, accent);
        }
        if (pulseAge >= 0f) {
            drawPulse(canvas, w, h, pulseColor != 0 ? (pulseColor | 0xFF000000) : accent);
        }
    }

    /** Light seeping in from all four edges, like the screen glowing from behind. */
    private void drawPulse(Canvas canvas, int w, int h, int color) {
        float level = pulseLevel();
        if (level <= 0.01f) {
            return;
        }
        float depth = Math.min(w, h) * 0.16f;
        int edge = (color & 0x00FFFFFF) | ((int) (200 * level) << 24);
        int clear = color & 0x00FFFFFF;
        paint.setStyle(Paint.Style.FILL);
        paint.setXfermode(ADD);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, 0, 0, depth, edge, clear, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, depth, paint);
        paint.setShader(new LinearGradient(0, h, 0, h - depth, edge, clear, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h - depth, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, depth, 0, edge, clear, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, depth, h, paint);
        paint.setShader(new LinearGradient(w, 0, w - depth, 0, edge, clear, Shader.TileMode.CLAMP));
        canvas.drawRect(w - depth, 0, w, h, paint);
        paint.setShader(null);
        paint.setXfermode(null);
    }

    /** A band of light rises from the bottom edge, shedding sparks, with the battery level. */
    private void drawCharge(Canvas canvas, int w, int h, int color, int accent) {
        float t = chargeAge / CHARGE_SECONDS;
        float rise = easeOut(Math.min(1f, t / 0.75f));
        float y = h * (1.02f - 0.85f * rise);
        float fade = t < 0.75f ? 1f : 1f - (t - 0.75f) / 0.25f;
        float band = h * 0.12f;
        paint.setStyle(Paint.Style.FILL);
        paint.setXfermode(ADD);
        paint.setShader(new LinearGradient(0, y - band, 0, y + band * 0.5f,
                new int[] {Color.TRANSPARENT, color, Color.TRANSPARENT}, new float[] {0f, 0.66f, 1f},
                Shader.TileMode.CLAMP));
        paint.setAlpha((int) (170 * fade));
        canvas.drawRect(0, y - band, w, y + band * 0.5f, paint);
        paint.setShader(null);
        // A bright line along the top of the band.
        paint.setColor(Color.WHITE);
        paint.setAlpha((int) (200 * fade));
        canvas.drawRect(0, y - 1.2f * density, w, y + 1.2f * density, paint);
        paint.setXfermode(null);

        // Sparks drift up off the line; placed by a fixed pattern so no state is kept.
        sprite.setColorFilter(new android.graphics.PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        for (int i = 0; i < 26; i++) {
            float seed = (i * 0.618034f) % 1f;
            float life = (t * 2.2f + seed) % 1f;
            float sx = w * ((i * 0.381966f + 0.07f) % 1f) + (float) Math.sin(life * 6 + i) * 12f * density;
            float sy = y - life * h * 0.16f;
            float size = (14f + 18f * seed) * density * (1f - life);
            spriteMatrix.setScale(size / glow.getWidth(), size / glow.getHeight());
            spriteMatrix.postTranslate(sx - size / 2f, sy - size / 2f);
            sprite.setAlpha((int) (220 * fade * (1f - life)));
            canvas.drawBitmap(glow, spriteMatrix, sprite);
        }

        if (chargePercent >= 0) {
            float textFade = Math.min(1f, t / 0.2f) * (t < 0.7f ? 1f : Math.max(0f, 1f - (t - 0.7f) / 0.3f));
            textPaint.setTextSize(44f * density);
            textPaint.setColor(Color.WHITE);
            textPaint.setShadowLayer(14f * density, 0, 0, color);
            textPaint.setAlpha((int) (255 * textFade));
            canvas.drawText("⚡ " + chargePercent + "%", w / 2f, h * 0.2f, textPaint);
        }
    }

    void release() {
        glow.recycle();
    }
}
