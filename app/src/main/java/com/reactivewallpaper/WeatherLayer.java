package com.reactivewallpaper;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * Rain, snow, fog or a thunderstorm over the wallpaper. Rain falls faster and
 * snow glows brighter with the music; a storm's lightning strikes on big beats.
 */
final class WeatherLayer {
    private static final int MAX_DROPS = 260;
    private static final int FOG_BANKS = 4;

    private final float density;
    private final Random random = new Random(7);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Each drop or flake: position (0..1 of the screen), speed, size, phase.
    private final float[] x = new float[MAX_DROPS];
    private final float[] y = new float[MAX_DROPS];
    private final float[] speed = new float[MAX_DROPS];
    private final float[] size = new float[MAX_DROPS];
    private final float[] phase = new float[MAX_DROPS];
    private final float[] fogX = new float[FOG_BANKS];
    private float time;
    private float flash;
    private float flashCooldown;
    private float strikeX;
    private boolean secondFlash;
    private int lastKind = -1;

    WeatherLayer(float density) {
        this.density = density;
        for (int i = 0; i < MAX_DROPS; i++) {
            respawn(i, true);
        }
        for (int i = 0; i < FOG_BANKS; i++) {
            fogX[i] = random.nextFloat();
        }
    }

    private void respawn(int i, boolean anywhere) {
        x[i] = random.nextFloat() * 1.3f - 0.15f;
        y[i] = anywhere ? random.nextFloat() : -0.05f - random.nextFloat() * 0.1f;
        speed[i] = 0.6f + random.nextFloat() * 0.8f;
        // Far drops are small and slow; near ones big and fast.
        size[i] = 0.4f + random.nextFloat() * 0.6f;
        phase[i] = random.nextFloat() * 6.28f;
    }

    /**
     * @param kind     a {@link Weather} kind
     * @param strength 0..1, the weather's own intensity times the look's amount
     * @param wind     -1..1, how far rain and snow lean
     */
    void draw(Canvas canvas, int w, int h, int kind, float strength, float wind, int tint,
              float bass, float beat, float dt) {
        time += dt;
        if (kind != lastKind) {
            lastKind = kind;
            for (int i = 0; i < MAX_DROPS; i++) {
                respawn(i, true);
            }
        }
        if (strength <= 0.01f) {
            return;
        }
        switch (kind) {
            case Weather.RAIN:
                drawRain(canvas, w, h, strength, wind, tint, bass, dt);
                break;
            case Weather.STORM:
                drawRain(canvas, w, h, Math.max(0.6f, strength), wind * 1.3f, tint, bass, dt);
                drawLightning(canvas, w, h, strength, beat, dt);
                break;
            case Weather.SNOW:
                drawSnow(canvas, w, h, strength, wind, tint, beat, dt);
                break;
            case Weather.FOG:
            case Weather.CLOUDY:
                drawFog(canvas, w, h, kind == Weather.FOG ? strength : strength * 0.45f, bass, dt);
                break;
            default:
                break;
        }
    }

    private void drawRain(Canvas canvas, int w, int h, float strength, float wind, int tint, float bass,
                          float dt) {
        int count = Math.round(MAX_DROPS * Math.min(1f, 0.25f + 0.75f * strength));
        float lean = 0.12f + wind * 0.35f;
        // A bluish white, nudged toward the look's colors.
        int color = blend(0xFFCFE4FF, tint, 0.25f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setShader(null);
        float fall = 1.5f * (1f + 0.6f * bass);
        for (int i = 0; i < count; i++) {
            float v = speed[i] * size[i] * fall;
            y[i] += v * dt;
            x[i] += v * lean * dt * h / w;
            if (y[i] > 1.05f) {
                respawn(i, false);
            }
            float length = (10f + 26f * size[i]) * density * (1f + 0.3f * bass);
            float px = x[i] * w;
            float py = y[i] * h;
            paint.setColor(color);
            paint.setAlpha((int) (40 + 110 * size[i] * Math.min(1f, 0.5f + strength)));
            paint.setStrokeWidth((0.8f + 1.4f * size[i]) * density);
            canvas.drawLine(px, py, px - lean * length, py - length, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSnow(Canvas canvas, int w, int h, float strength, float wind, int tint, float beat,
                          float dt) {
        int count = Math.round(MAX_DROPS * 0.7f * Math.min(1f, 0.25f + 0.75f * strength));
        int color = blend(Color.WHITE, tint, 0.15f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        for (int i = 0; i < count; i++) {
            float v = 0.05f + 0.09f * speed[i] * size[i];
            y[i] += v * dt;
            x[i] += (wind * 0.06f + (float) Math.sin(time * 0.9f + phase[i]) * 0.025f) * size[i] * dt;
            if (y[i] > 1.03f) {
                respawn(i, false);
            }
            float r = (1.2f + 2.8f * size[i]) * density * (1f + 0.35f * beat);
            paint.setColor(color);
            paint.setAlpha((int) Math.min(255, (90 + 140 * size[i]) * (0.75f + 0.5f * beat)));
            canvas.drawCircle(x[i] * w, y[i] * h, r, paint);
        }
    }

    private void drawFog(Canvas canvas, int w, int h, float strength, float bass, float dt) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < FOG_BANKS; i++) {
            fogX[i] += dt * (0.008f + 0.004f * i) * (i % 2 == 0 ? 1 : -1);
            if (fogX[i] > 1.4f) {
                fogX[i] = -0.4f;
            } else if (fogX[i] < -0.4f) {
                fogX[i] = 1.4f;
            }
            float cx = fogX[i] * w;
            float cy = h * (0.45f + 0.15f * i);
            float r = Math.max(w, h) * (0.45f + 0.1f * i);
            int alpha = (int) (Math.min(1f, strength) * (70 + 45 * bass));
            paint.setShader(new RadialGradient(cx, cy, r, new int[] {Color.argb(alpha, 210, 215, 230),
                    Color.argb(alpha / 3, 200, 205, 225), Color.TRANSPARENT}, new float[] {0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.save();
            // Flattened into a low bank of mist.
            canvas.scale(1f, 0.4f, cx, cy);
            canvas.drawCircle(cx, cy, r, paint);
            canvas.restore();
        }
        paint.setShader(null);
    }

    private void drawLightning(Canvas canvas, int w, int h, float strength, float beat, float dt) {
        flashCooldown -= dt;
        if (flashCooldown <= 0f && beat > 0.85f && random.nextFloat() < 0.35f) {
            flash = 1f;
            secondFlash = false;
            strikeX = 0.15f + random.nextFloat() * 0.7f;
            flashCooldown = 2.5f + random.nextFloat() * 4f;
        }
        if (flash <= 0.01f) {
            return;
        }
        // The sky lights up, brightest near the strike.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(strikeX * w, 0, Math.max(w, h) * 0.9f,
                new int[] {Color.argb((int) (150 * flash), 230, 235, 255), Color.argb((int) (40 * flash), 200, 210, 255),
                        Color.TRANSPARENT}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        // Flickers: drops fast, with a second smaller flash.
        float before = flash;
        flash *= (float) Math.pow(0.02, dt);
        if (!secondFlash && before > 0.5f && flash <= 0.5f) {
            secondFlash = true;
            flash = 0.7f;
        }
        if (flash <= 0.01f) {
            secondFlash = false;
        }
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }
}
