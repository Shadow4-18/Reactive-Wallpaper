package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** What a touch on the home screen does: a ring of light, a burst of sparks, or a glow flare. */
final class TapEffects {
    private static final float RIPPLE_SECONDS = 1.2f;
    private static final float BURST_SECONDS = 1.1f;
    private static final float FLARE_SECONDS = 0.8f;
    private static final int SPARKS = 28;
    private static final int MAX_SPARKS = 240;

    private static final class Ripple {
        float x;
        float y;
        float age;
        int color;
    }

    private static final class Spark {
        float x;
        float y;
        float vx;
        float vy;
        float age;
        float life;
        float size;
        PorterDuffColorFilter tint;
    }

    private final List<Ripple> ripples = new ArrayList<>();
    private final List<Spark> sparks = new ArrayList<>();
    private final Random random = new Random();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sprite = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix spriteMatrix = new Matrix();
    private final Bitmap glow = Sprites.tint(Sprites.glow(64), 64, 64, Color.WHITE);
    private float flare;
    private float lastTrailX = Float.NaN;
    private float lastTrailY = Float.NaN;

    TapEffects() {
        ring.setStyle(Paint.Style.STROKE);
        ring.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        sprite.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    /** A finger went down (or is dragging, when {@code moving}) at x, y. */
    void touch(int kind, float x, float y, boolean moving, int[] colors, float density) {
        if (kind == WallpaperSettings.TAP_RIPPLE && !moving) {
            Ripple r = new Ripple();
            r.x = x;
            r.y = y;
            r.color = colors[random.nextInt(colors.length)];
            ripples.add(r);
            if (ripples.size() > 6) {
                ripples.remove(0);
            }
        } else if (kind == WallpaperSettings.TAP_BURST) {
            if (!moving) {
                burst(x, y, SPARKS, 1f, colors, density);
                lastTrailX = x;
                lastTrailY = y;
            } else if (Float.isNaN(lastTrailX) || Math.hypot(x - lastTrailX, y - lastTrailY) > 18 * density) {
                // A trail of sparks while dragging.
                burst(x, y, 3, 0.35f, colors, density);
                lastTrailX = x;
                lastTrailY = y;
            }
        } else if (kind == WallpaperSettings.TAP_FLARE && !moving) {
            flare = 1f;
        }
    }

    private void burst(float x, float y, int count, float strength, int[] colors, float density) {
        for (int i = 0; i < count && sparks.size() < MAX_SPARKS; i++) {
            Spark s = new Spark();
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = (120f + random.nextFloat() * 380f) * density * strength;
            s.x = x;
            s.y = y;
            s.vx = (float) Math.cos(angle) * speed;
            s.vy = (float) Math.sin(angle) * speed;
            s.life = BURST_SECONDS * (0.6f + random.nextFloat() * 0.6f);
            s.size = (1.5f + random.nextFloat() * 2.5f) * density;
            s.tint = new PorterDuffColorFilter(colors[random.nextInt(colors.length)], PorterDuff.Mode.SRC_IN);
            sparks.add(s);
        }
    }

    /** How much extra glow the image gets from a recent flare tap (0 .. 1). */
    float flare() {
        return flare;
    }

    boolean isActive() {
        return !ripples.isEmpty() || !sparks.isEmpty() || flare > 0.01f;
    }

    void draw(Canvas canvas, float dt, float density) {
        flare = Math.max(0f, flare - dt / FLARE_SECONDS);
        for (Iterator<Ripple> it = ripples.iterator(); it.hasNext(); ) {
            Ripple r = it.next();
            r.age += dt;
            float t = r.age / RIPPLE_SECONDS;
            if (t >= 1f) {
                it.remove();
                continue;
            }
            // Two rings racing outward, the first ahead and brighter; eased so they slow down.
            for (int k = 0; k < 2; k++) {
                float tk = Math.max(0f, t - k * 0.18f);
                float eased = 1f - (1f - tk) * (1f - tk);
                float radius = (12f + 160f * eased) * density;
                float alpha = (1f - tk) * (k == 0 ? 1f : 0.6f);
                ring.setColor(r.color);
                ring.setStrokeWidth(10f * density * (1f - tk) + 2f);
                ring.setAlpha((int) (alpha * 70));
                canvas.drawCircle(r.x, r.y, radius, ring);
                ring.setStrokeWidth(2.5f * density);
                ring.setAlpha((int) (alpha * 230));
                canvas.drawCircle(r.x, r.y, radius, ring);
            }
        }
        for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) {
            Spark s = it.next();
            s.age += dt;
            if (s.age >= s.life) {
                it.remove();
                continue;
            }
            float drag = (float) Math.pow(0.12, dt);
            s.vx *= drag;
            s.vy *= drag;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            float fade = 1f - s.age / s.life;
            float size = s.size * (0.6f + 0.4f * fade) * 6f;
            spriteMatrix.setScale(size / glow.getWidth(), size / glow.getHeight());
            spriteMatrix.postTranslate(s.x - size / 2f, s.y - size / 2f);
            sprite.setColorFilter(s.tint);
            sprite.setAlpha((int) (fade * 255));
            canvas.drawBitmap(glow, spriteMatrix, sprite);
        }
    }

    void release() {
        glow.recycle();
    }
}
