package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import java.util.Random;

/**
 * Particles drawn as soft pre-rendered light sprites (never hard shapes), in a
 * small palette taken from the picture. Every particle has a depth: far ones are
 * smaller, dimmer and slower, which gives the layer a sense of space.
 *
 * Positions are in image coordinates (0..1) so particles move with the picture;
 * sizes are in dp.
 */
final class ParticleSystem {
    static final int MAX = WallpaperSettings.MAX_SPARKLES;
    private static final int PALETTE_SIZE = 5;

    // Particle state.
    private final float[] u = new float[MAX];
    private final float[] v = new float[MAX];
    private final float[] vu = new float[MAX];
    private final float[] vv = new float[MAX];
    private final float[] life = new float[MAX];
    private final float[] maxLife = new float[MAX];
    private final float[] size = new float[MAX];
    private final float[] phase = new float[MAX];
    private final float[] depth = new float[MAX];
    private final float[] wait = new float[MAX];
    private final float[] length = new float[MAX];
    private final float[] pop = new float[MAX];
    private final int[] color = new int[MAX];
    private final boolean[] glints = new boolean[MAX];

    // Where sparkles appear (the picture's glowing spots).
    private final float[] spawnU;
    private final float[] spawnV;
    private final int spawnCount;

    // Sprites: per palette color, plus a few white/colored-in shapes.
    private final int paletteCount;
    private final Bitmap[] glow;
    private final Bitmap[] hotCore;
    private final Bitmap[] streak;
    private final Bitmap[] flare;
    private final Bitmap[] hotFlare;
    private final Bitmap whiteCore;
    private final Bitmap whiteStreak;
    private final Bitmap glint;
    private final Bitmap bubble;

    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix spriteMatrix = new Matrix();
    private final float[] point = new float[2];
    private final float[] matrixValues = new float[9];
    private int style = -1;
    private float time;
    private float lastBeat;

    ParticleSystem(float[] spawnU, float[] spawnV, int[] spawnColors, int spawnCount, float[] hues) {
        this.spawnU = spawnU;
        this.spawnV = spawnV;
        this.spawnCount = spawnCount;
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

        int[] palette = palette(spawnColors, spawnCount, hues);
        paletteCount = palette.length;
        glow = new Bitmap[paletteCount];
        hotCore = new Bitmap[paletteCount];
        streak = new Bitmap[paletteCount];
        flare = new Bitmap[paletteCount];
        hotFlare = new Bitmap[paletteCount];
        float[] glowAlpha = Sprites.glow(64);
        float[] coreAlpha = Sprites.core(64);
        float[] streakAlpha = Sprites.streak(256, 32);
        float[] flareAlpha = Sprites.flare(256, 32);
        for (int i = 0; i < paletteCount; i++) {
            int c = palette[i];
            int hot = mix(c, Color.WHITE, 0.55f);
            glow[i] = Sprites.tint(glowAlpha, 64, 64, c);
            hotCore[i] = Sprites.tint(coreAlpha, 64, 64, hot);
            streak[i] = Sprites.tint(streakAlpha, 256, 32, c);
            flare[i] = Sprites.tint(flareAlpha, 256, 32, c);
            hotFlare[i] = Sprites.tint(flareAlpha, 256, 32, mix(c, Color.WHITE, 0.65f));
        }
        whiteCore = Sprites.tint(coreAlpha, 64, 64, Color.WHITE);
        whiteStreak = Sprites.tint(streakAlpha, 256, 32, Color.WHITE);
        glint = Sprites.tint(Sprites.glint(64), 64, 64, Color.WHITE);
        bubble = Sprites.bubble(128);
    }

    void release() {
        for (Bitmap[] set : new Bitmap[][] {glow, hotCore, streak, flare, hotFlare}) {
            for (Bitmap b : set) {
                b.recycle();
            }
        }
        whiteCore.recycle();
        whiteStreak.recycle();
        glint.recycle();
        bubble.recycle();
    }

    /**
     * @param count particles to show (before per-style scaling)
     * @param offsetX extra screen offset, for parallax depth
     */
    void draw(Canvas canvas, Matrix imageMatrix, int imageWidth, int imageHeight, int count, float dt,
              float density, AudioAnalyzer audio, WallpaperSettings s, float offsetX, float offsetY,
              ColorFilter filter) {
        if (s.particleStyle != style) {
            style = s.particleStyle;
            for (int i = 0; i < MAX; i++) {
                respawn(i);
                // Start mid-life so the screen isn't empty after switching style.
                life[i] = random.nextFloat() * maxLife[i];
                wait[i] *= random.nextFloat();
            }
        }
        time += dt;
        float level = audio.level();
        float beat = audio.beat();
        float bass = audio.bass();
        float react = s.sparkleReact;
        boolean beatHit = beat > 0.7f && lastBeat <= 0.7f;
        lastBeat = beat;

        switch (style) {
            case WallpaperSettings.PARTICLE_STARS:
                count = Math.max(1, count / 4);
                break;
            case WallpaperSettings.PARTICLE_BUBBLES:
                count = Math.max(1, count / 2);
                break;
            case WallpaperSettings.PARTICLE_STREAKS:
                count = Math.max(1, count / 3);
                break;
            default:
                break;
        }
        count = Math.min(count, MAX);
        if (beatHit && react > 0f) {
            onBeat(count);
        }

        paint.setColorFilter(filter);
        imageMatrix.getValues(matrixValues);
        float imageScale = matrixValues[Matrix.MSCALE_X];
        float energy = react * (1.2f * level + 0.8f * beat);
        for (int i = 0; i < count; i++) {
            float z = depth[i];
            float speed = s.sparkleSpeed * (0.5f + 0.8f * z) * (1f + energy);
            if (!step(i, dt, speed)) {
                continue;
            }
            point[0] = u[i] * imageWidth;
            point[1] = v[i] * imageHeight;
            imageMatrix.mapPoints(point);
            // Nearer particles shift further with parallax.
            float x = point[0] + offsetX * (0.4f + 0.8f * z);
            float y = point[1] + offsetY * (0.4f + 0.8f * z);
            float bright = s.sparkleBrightness * (0.35f + 0.65f * z)
                    * (0.6f + react * (0.8f * level + 0.6f * beat));
            float scale = s.sparkleSize * density * (0.45f + 0.9f * z);
            switch (style) {
                case WallpaperSettings.PARTICLE_EMBERS:
                    drawEmber(canvas, i, x, y, scale, bright);
                    break;
                case WallpaperSettings.PARTICLE_STARS:
                    drawStar(canvas, i, x, y, scale, bright, imageWidth * imageScale, imageHeight * imageScale);
                    break;
                case WallpaperSettings.PARTICLE_BUBBLES:
                    drawBubble(canvas, i, x, y, scale, bright, bass * react);
                    break;
                case WallpaperSettings.PARTICLE_STREAKS:
                    drawStreak(canvas, i, x, y, scale, bright, beat * react, imageWidth * imageScale);
                    break;
                default:
                    drawSparkle(canvas, i, x, y, scale, bright);
                    break;
            }
        }
        paint.setColorFilter(null);
    }

    /** Moves particle i; returns false if it isn't on screen yet (or anymore) this frame. */
    private boolean step(int i, float dt, float speed) {
        if (wait[i] > 0f) {
            wait[i] -= dt;
            return false;
        }
        if (pop[i] >= 0f) {
            pop[i] += dt;
            if (pop[i] > 0.3f) {
                respawn(i);
                return false;
            }
            return true;
        }
        float t = time;
        switch (style) {
            case WallpaperSettings.PARTICLE_EMBERS:
                // Rising with a lazy swirl, like heat carries them.
                float swirl = 0.03f * (float) Math.sin(t * 1.7f + phase[i])
                        + 0.018f * (float) Math.sin(t * 3.3f + phase[i] * 2.1f);
                u[i] += (vu[i] + swirl) * dt * speed;
                v[i] += vv[i] * dt * speed;
                break;
            case WallpaperSettings.PARTICLE_BUBBLES:
                u[i] += 0.012f * (float) Math.sin(t * 1.1f + phase[i]) * dt * speed;
                v[i] += vv[i] * dt * speed;
                break;
            case WallpaperSettings.PARTICLE_STARS:
                // Shooting stars keep their own pace; music makes more of them instead.
                u[i] += vu[i] * dt;
                v[i] += vv[i] * dt;
                break;
            case WallpaperSettings.PARTICLE_STREAKS:
                u[i] += vu[i] * dt * speed;
                break;
            default:
                u[i] += 0.006f * (float) Math.sin(t * 0.7f + phase[i]) * dt * speed;
                v[i] += vv[i] * dt * speed;
                break;
        }
        life[i] += dt;
        if (life[i] >= maxLife[i]) {
            respawn(i);
            return false;
        }
        return true;
    }

    /** On a strong beat: launch a waiting shooting star, or pop a big bubble. */
    private void onBeat(int count) {
        if (style == WallpaperSettings.PARTICLE_STARS) {
            for (int i = 0; i < count; i++) {
                if (wait[i] > 0f) {
                    wait[i] = 0f;
                    return;
                }
            }
        } else if (style == WallpaperSettings.PARTICLE_BUBBLES) {
            int start = random.nextInt(count);
            for (int k = 0; k < count; k++) {
                int i = (start + k) % count;
                if (pop[i] < 0f && depth[i] > 0.55f && life[i] > maxLife[i] * 0.2f) {
                    pop[i] = 0f;
                    return;
                }
            }
        }
    }

    // ---- Drawing each style ----------------------------------------------------

    private void drawEmber(Canvas canvas, int i, float x, float y, float scale, float bright) {
        float t = time;
        float env = envelope(life[i] / maxLife[i], 0.12f, 0.35f);
        float flicker = (0.7f + 0.3f * (float) Math.sin(t * 9f + phase[i] * 3f))
                * (0.85f + 0.15f * (float) Math.sin(t * 23f + phase[i]));
        float heat = 1f - life[i] / maxLife[i];
        float drift = vu[i] + 0.03f * (float) Math.sin(t * 1.7f + phase[i]);
        float angle = (float) Math.toDegrees(Math.atan2(vv[i], drift));
        float stretch = 1f + 3f * (float) Math.hypot(vu[i], vv[i]);
        float s = size[i] * scale * (0.85f + 0.15f * (float) Math.sin(t * 13f + phase[i]));
        int c = color[i];
        // A colored glow, stretched along the direction of travel, with a hot core that cools.
        sprite(canvas, glow[c], x, y, s * 9f * stretch, s * 9f, angle, env * flicker * bright * 1.2f);
        sprite(canvas, hotCore[c], x, y, s * 5f * stretch, s * 5f, angle,
                env * flicker * bright * (0.5f + 0.5f * heat) * 1.6f);
    }

    private void drawStar(Canvas canvas, int i, float x, float y, float scale, float bright,
                          float screenImageWidth, float screenImageHeight) {
        float t = life[i] / maxLife[i];
        float env = envelope(t, 0.08f, 0.3f);
        float dx = vu[i] * screenImageWidth;
        float dy = vv[i] * screenImageHeight;
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        float speed = (float) Math.hypot(dx, dy);
        // The tail grows as the star travels and shrinks as it burns out.
        float tail = Math.min(speed * life[i], speed * 0.45f) * (1f - Math.max(0f, (t - 0.6f) / 0.4f));
        tail = Math.max(tail, 4f);
        float s = size[i] * scale;
        double rad = Math.toRadians(angle);
        float cx = x - (float) Math.cos(rad) * tail / 2f;
        float cy = y - (float) Math.sin(rad) * tail / 2f;
        int c = color[i];
        sprite(canvas, streak[c], cx, cy, tail, s * 5f, angle, env * bright * 1.1f);
        sprite(canvas, whiteStreak, cx, cy, tail, s * 1.6f, angle, env * bright * 0.9f);
        sprite(canvas, glow[c], x, y, s * 9f, s * 9f, 0f, env * bright);
        float peak = (float) Math.exp(-Math.pow((t - 0.35f) / 0.12f, 2));
        sprite(canvas, glint, x, y, s * 14f, s * 14f, 15f, env * bright * peak);
    }

    private void drawBubble(Canvas canvas, int i, float x, float y, float scale, float bright, float pulse) {
        float r = size[i] * scale * 2f * (1f + 0.12f * pulse);
        if (pop[i] >= 0f) {
            // Popping: the rim flashes outward and fades.
            float p = pop[i] / 0.3f;
            sprite(canvas, bubble, x, y, r * (1f + 0.6f * p), r * (1f + 0.6f * p), 0f, (1f - p) * 1.2f);
            sprite(canvas, glow[color[i]], x, y, r * 1.8f, r * 1.8f, 0f, (1f - p) * 0.5f * bright);
            return;
        }
        float env = envelope(life[i] / maxLife[i], 0.12f, 0.2f);
        float wobble = 0.06f * (float) Math.sin(time * 5f + phase[i]);
        sprite(canvas, bubble, x, y, r * (1f + wobble), r * (1f - wobble), 0f, env * (0.55f + 0.45f * depth[i]));
        sprite(canvas, glow[color[i]], x, y, r * 1.4f, r * 1.4f, 0f, env * 0.12f * bright);
    }

    private void drawStreak(Canvas canvas, int i, float x, float y, float scale, float bright, float beat,
                            float screenImageWidth) {
        float env = envelope(life[i] / maxLife[i], 0.15f, 0.25f);
        float z = depth[i];
        float len = length[i] * screenImageWidth * (0.8f + 0.4f * beat) * (0.6f + 0.6f * z);
        // Streak thickness uses its own depth curve rather than the shared one.
        float s = size[i] * scale / (0.45f + 0.9f * z) * (0.6f + 0.8f * z);
        int c = color[i];
        // A wide haze, a colored line and a near-white core, with a bright center like a lens flare.
        sprite(canvas, flare[c], x, y, len * 1.1f, s * 22f, 0f, env * bright * 0.45f);
        sprite(canvas, flare[c], x, y, len, s * 5f, 0f, env * bright);
        sprite(canvas, hotFlare[c], x, y, len * 0.75f, s * 1.6f, 0f, env * bright * 1.4f);
        sprite(canvas, glow[c], x, y, s * 16f, s * 16f, 0f, env * bright * 0.9f);
        sprite(canvas, whiteCore, x, y, s * 7f, s * 7f, 0f, env * bright * 0.9f);
    }

    private void drawSparkle(Canvas canvas, int i, float x, float y, float scale, float bright) {
        float env = envelope(life[i] / maxLife[i], 0.2f, 0.4f);
        float twinkle = 0.6f + 0.4f * (float) Math.sin(time * 3f + phase[i] * 5f);
        float s = size[i] * scale;
        sprite(canvas, glow[color[i]], x, y, s * 6f, s * 6f, 0f, env * twinkle * bright);
        if (glints[i]) {
            sprite(canvas, glint, x, y, s * 12f, s * 12f, (time * 20f) % 90f, env * twinkle * bright * 0.8f);
        }
    }

    /** Draws a sprite centered at (x, y), stretched to w x h and turned by angle degrees. */
    private void sprite(Canvas canvas, Bitmap b, float x, float y, float w, float h, float angle, float alpha) {
        if (alpha <= 0.004f || w < 0.5f || h < 0.5f) {
            return;
        }
        spriteMatrix.setScale(w / b.getWidth(), h / b.getHeight());
        spriteMatrix.postTranslate(-w / 2f, -h / 2f);
        if (angle != 0f) {
            spriteMatrix.postRotate(angle);
        }
        spriteMatrix.postTranslate(x, y);
        paint.setAlpha((int) (Math.min(1f, alpha) * 255));
        canvas.drawBitmap(b, spriteMatrix, paint);
    }

    /** Fades in over {@code in} and out over {@code out} (fractions of the life). */
    private static float envelope(float t, float in, float out) {
        if (t < 0f || t > 1f) {
            return 0f;
        }
        return Math.min(1f, t / in) * Math.min(1f, (1f - t) / out);
    }

    // ---- Spawning ---------------------------------------------------------------

    private void respawn(int i) {
        depth[i] = (float) Math.pow(random.nextFloat(), 1.4);
        color[i] = random.nextInt(paletteCount);
        phase[i] = random.nextFloat() * 6.283f;
        life[i] = 0f;
        wait[i] = 0f;
        pop[i] = -1f;
        switch (style) {
            case WallpaperSettings.PARTICLE_EMBERS:
                u[i] = random.nextFloat();
                v[i] = 0.4f + random.nextFloat() * 0.65f;
                vu[i] = (random.nextFloat() - 0.5f) * 0.03f;
                vv[i] = -(0.08f + 0.08f * random.nextFloat());
                maxLife[i] = 3f + 3f * random.nextFloat();
                size[i] = 2.8f + 2.6f * random.nextFloat();
                break;
            case WallpaperSettings.PARTICLE_STARS: {
                // Mostly falling left to right, a few the other way, spaced out in time.
                wait[i] = random.nextFloat() * 2.5f;
                u[i] = random.nextFloat() - 0.1f;
                v[i] = random.nextFloat() * 0.35f - 0.05f;
                double angle = Math.toRadians(18 + random.nextFloat() * 22);
                float speed = 0.35f + 0.25f * random.nextFloat();
                float direction = random.nextFloat() < 0.75f ? 1f : -1f;
                if (direction < 0f) {
                    u[i] += 0.2f;
                }
                vu[i] = (float) Math.cos(angle) * speed * direction;
                // Image height is about twice its width on a phone; keep the angle true on screen.
                vv[i] = (float) Math.sin(angle) * speed * 0.45f;
                maxLife[i] = 0.8f + 0.6f * random.nextFloat();
                size[i] = 2f + 1.2f * random.nextFloat();
                break;
            }
            case WallpaperSettings.PARTICLE_BUBBLES:
                u[i] = random.nextFloat();
                v[i] = 1.05f + random.nextFloat() * 0.1f;
                vu[i] = 0f;
                vv[i] = -(0.03f + 0.035f * random.nextFloat());
                maxLife[i] = 8f + 6f * random.nextFloat();
                size[i] = 8f + 14f * random.nextFloat();
                break;
            case WallpaperSettings.PARTICLE_STREAKS: {
                float direction = random.nextBoolean() ? 1f : -1f;
                v[i] = 0.05f + 0.9f * random.nextFloat();
                u[i] = direction > 0f ? -0.3f : 1.3f;
                vu[i] = direction * (0.12f + 0.25f * random.nextFloat());
                vv[i] = 0f;
                maxLife[i] = 1.6f / Math.abs(vu[i]);
                size[i] = 1f + 1.4f * random.nextFloat();
                length[i] = 0.18f + 0.3f * random.nextFloat();
                break;
            }
            default:
                // Sparkles appear on the picture's glowing spots and drift up.
                if (spawnCount > 0) {
                    int k = random.nextInt(spawnCount);
                    u[i] = spawnU[k];
                    v[i] = spawnV[k];
                } else {
                    u[i] = random.nextFloat();
                    v[i] = 0.3f + random.nextFloat() * 0.6f;
                }
                vu[i] = 0f;
                vv[i] = -0.012f;
                maxLife[i] = 2.5f + 4f * random.nextFloat();
                size[i] = 1.8f + 2.4f * random.nextFloat();
                glints[i] = random.nextFloat() < 0.3f;
                break;
        }
    }

    /** Up to five vivid colors from the picture, most common first. */
    private static int[] palette(int[] colors, int count, float[] hues) {
        float[] weight = new float[12];
        float[] hueSum = new float[12];
        float[] hsv = new float[3];
        for (int i = 0; i < count; i++) {
            Color.colorToHSV(colors[i], hsv);
            if (hsv[1] < 0.2f) {
                continue;
            }
            int bin = (int) (hsv[0] / 30f) % 12;
            float w = hsv[1] * hsv[2];
            weight[bin] += w;
            hueSum[bin] += hsv[0] * w;
        }
        float best = 0f;
        for (float w : weight) {
            best = Math.max(best, w);
        }
        int[] chosen = new int[PALETTE_SIZE];
        int n = 0;
        while (n < PALETTE_SIZE && best > 0f) {
            int top = -1;
            for (int b = 0; b < 12; b++) {
                if (weight[b] > 0f && (top < 0 || weight[b] > weight[top])) {
                    top = b;
                }
            }
            if (top < 0 || weight[top] < best * 0.1f) {
                break;
            }
            chosen[n++] = Color.HSVToColor(new float[] {hueSum[top] / weight[top], 0.7f, 1f});
            weight[top] = 0f;
        }
        if (n == 0) {
            // A grey or dark picture: use its accent hues.
            chosen[n++] = Color.HSVToColor(new float[] {hues[0], 0.6f, 1f});
            chosen[n++] = Color.HSVToColor(new float[] {hues[1], 0.6f, 1f});
        }
        int[] out = new int[n];
        System.arraycopy(chosen, 0, out, 0, n);
        return out;
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
