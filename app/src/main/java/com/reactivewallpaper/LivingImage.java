package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import java.util.Random;

/**
 * Makes the picture itself come alive: its glowing, vivid parts are pulled out
 * into two glow layers (one per dominant hue) that brighten and bloom with the
 * music, and sparkles in the image's own colors drift across it. With ambient
 * motion on, the glow breathes and the sparkles keep moving even in silence.
 */
final class LivingImage {
    private static final int MAX_SPAWN_POINTS = 4000;
    private static final int PARTICLE_COUNT = WallpaperSettings.MAX_SPARKLES;
    /** Glow can stack up to this many times a layer's own brightness. */
    private static final float MAX_LAYER_AMOUNT = 4f;

    /** Glow layers at reduced size: [hue group][0 = sharp, 1 = bloom]. */
    private final Bitmap[][] layers = new Bitmap[2][2];
    private final int imageWidth;
    private final int imageHeight;
    private final float coverage;

    // Where sparkles may appear (normalized image coords) and their colors.
    private final float[] spawnU;
    private final float[] spawnV;
    private final int[] spawnColor;
    private final int spawnCount;

    private final float[] pu = new float[PARTICLE_COUNT];
    private final float[] pv = new float[PARTICLE_COUNT];
    private final float[] pDrift = new float[PARTICLE_COUNT];
    private final float[] pLife = new float[PARTICLE_COUNT];
    private final float[] pMaxLife = new float[PARTICLE_COUNT];
    private final float[] pSize = new float[PARTICLE_COUNT];
    private final float[] pPhase = new float[PARTICLE_COUNT];
    private final int[] pColor = new int[PARTICLE_COUNT];

    private final Random random = new Random();
    private final Paint addPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix layerMatrix = new Matrix();
    private final float[] point = new float[2];
    private float time;
    private float breathePhase;

    /**
     * @param coverage 0 .. 1; higher lets dimmer, less saturated parts of the image glow too
     */
    LivingImage(Bitmap image, float[] hues, float coverage) {
        imageWidth = image.getWidth();
        imageHeight = image.getHeight();
        this.coverage = coverage;
        float lowEdge = 0.7f - 0.55f * coverage;
        float saturationFloor = 0.15f + 0.35f * coverage;
        addPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

        int w = Math.max(1, imageWidth / 2);
        int h = Math.max(1, imageHeight / 2);
        Bitmap half = Bitmap.createScaledBitmap(image, w, h, true);
        int[] pixels = new int[w * h];
        half.getPixels(pixels, 0, w, 0, 0, w, h);
        if (half != image) {
            half.recycle();
        }

        int[] groupA = new int[w * h];
        int[] groupB = new int[w * h];
        float[] hsv = new float[3];
        float[] tmpU = new float[MAX_SPAWN_POINTS];
        float[] tmpV = new float[MAX_SPAWN_POINTS];
        int[] tmpColor = new int[MAX_SPAWN_POINTS];
        int candidates = 0;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            toHsv(c, hsv);
            float weight = smoothstep(lowEdge, lowEdge + 0.45f, hsv[2])
                    * (saturationFloor + (1f - saturationFloor) * hsv[1]);
            if (weight < 0.02f) {
                continue;
            }
            float toA;
            if (hsv[1] < 0.25f) {
                toA = 0.5f;
            } else {
                float dA = hueDistance(hsv[0], hues[0]);
                float dB = hueDistance(hsv[0], hues[1]);
                toA = clamp((dB - dA) / 80f + 0.5f, 0f, 1f);
            }
            groupA[i] = scale(c, weight * toA);
            groupB[i] = scale(c, weight * (1f - toA));

            // Reservoir-sample bright vivid points as sparkle spawn spots.
            if (weight > 0.3f && hsv[1] > 0.3f) {
                int slot = candidates < MAX_SPAWN_POINTS ? candidates : random.nextInt(candidates + 1);
                if (slot < MAX_SPAWN_POINTS) {
                    tmpU[slot] = (i % w + 0.5f) / w;
                    tmpV[slot] = (i / w + 0.5f) / h;
                    tmpColor[slot] = c;
                }
                candidates++;
            }
        }
        spawnCount = Math.min(candidates, MAX_SPAWN_POINTS);
        spawnU = tmpU;
        spawnV = tmpV;
        spawnColor = tmpColor;

        int[][] groups = {groupA, groupB};
        for (int g = 0; g < 2; g++) {
            Bitmap sharp = Bitmap.createBitmap(groups[g], w, h, Bitmap.Config.ARGB_8888);
            int bw = Math.max(1, w / 5);
            int bh = Math.max(1, h / 5);
            Bitmap small = Bitmap.createScaledBitmap(sharp, bw, bh, true);
            int[] bloom = new int[bw * bh];
            small.getPixels(bloom, 0, bw, 0, 0, bw, bh);
            small.recycle();
            boxBlur(bloom, bw, bh, 4);
            boxBlur(bloom, bw, bh, 4);
            layers[g][0] = sharp;
            layers[g][1] = Bitmap.createBitmap(bloom, bw, bh, Bitmap.Config.ARGB_8888);
        }

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            respawn(i);
            pLife[i] = random.nextFloat() * pMaxLife[i];
        }
    }

    float coverage() {
        return coverage;
    }

    void release() {
        for (Bitmap[] group : layers) {
            for (Bitmap b : group) {
                if (b != null) {
                    b.recycle();
                }
            }
        }
    }

    /**
     * @param imageMatrix maps full image pixels to the screen, as used for the background
     */
    void draw(Canvas canvas, Matrix imageMatrix, AudioAnalyzer audio, WallpaperSettings s,
              float dt, float density) {
        time += dt;
        breathePhase += dt * s.breatheSpeed;
        float bass = audio.bass();
        float beat = audio.beat();
        float high = audio.high();

        float idleA = s.breathe * (0.3f + 0.22f * (float) Math.sin(breathePhase * 1.1f));
        float idleB = s.breathe * (0.3f + 0.22f * (float) Math.sin(breathePhase * 0.9f + 2.1f));
        float bassAmount = idleA + s.bassGlow * bass * 1.3f + s.beatFlare * beat;
        float highAmount = idleB + s.highGlow * high * 1.6f + s.beatFlare * beat * 0.35f;
        int bassGroup = s.swapGlowColors ? 1 : 0;
        drawGroup(canvas, imageMatrix, bassGroup, bassAmount, s.bloom);
        drawGroup(canvas, imageMatrix, 1 - bassGroup, highAmount, s.bloom);

        int sparkles = Math.round(s.sparkleAmount * PARTICLE_COUNT);
        if (sparkles > 0 && spawnCount > 0) {
            drawParticles(canvas, imageMatrix, sparkles, dt, density, bass, beat, audio.level(), s);
        }
    }

    private void drawGroup(Canvas canvas, Matrix imageMatrix, int group, float amount, float bloom) {
        drawLayer(canvas, imageMatrix, layers[group][0], amount * 0.8f);
        drawLayer(canvas, imageMatrix, layers[group][1], amount * bloom);
    }

    /** Adds the layer on top `amount` times its brightness, stacking draws past 1. */
    private void drawLayer(Canvas canvas, Matrix imageMatrix, Bitmap b, float amount) {
        amount = Math.min(amount, MAX_LAYER_AMOUNT);
        if (amount <= 0.01f) {
            return;
        }
        layerMatrix.set(imageMatrix);
        layerMatrix.preScale(imageWidth / (float) b.getWidth(), imageHeight / (float) b.getHeight());
        while (amount > 0.01f) {
            addPaint.setAlpha((int) (Math.min(1f, amount) * 255));
            canvas.drawBitmap(b, layerMatrix, addPaint);
            amount -= 1f;
        }
    }

    private void drawParticles(Canvas canvas, Matrix imageMatrix, int count, float dt, float density,
                               float bass, float beat, float level, WallpaperSettings s) {
        float react = s.sparkleReact;
        float speed = s.sparkleSpeed * (1f + react * (4f * level + 2f * beat));
        for (int i = 0; i < count; i++) {
            pLife[i] += dt * s.sparkleSpeed * (1f + react * level);
            if (pLife[i] >= pMaxLife[i]) {
                respawn(i);
            }
            // Drift upward with a gentle sideways wobble.
            pv[i] -= dt * 0.012f * speed;
            pu[i] += dt * 0.006f * (float) Math.sin(time * 0.7f + pPhase[i]) * pDrift[i] * speed;

            float life = pLife[i] / pMaxLife[i];
            float fade = (float) Math.sin(Math.PI * life);
            float twinkle = 0.6f + 0.4f * (float) Math.sin(time * 3f + pPhase[i] * 5f);
            float alpha = Math.min(1f, fade * twinkle * s.sparkleBrightness
                    * (0.6f + react * (0.8f * level + 0.6f * beat)));
            if (alpha <= 0.01f) {
                continue;
            }
            point[0] = pu[i] * imageWidth;
            point[1] = pv[i] * imageHeight;
            imageMatrix.mapPoints(point);
            float size = pSize[i] * s.sparkleSize * density * (1f + react * (0.8f * bass + 0.6f * beat));

            addPaint.setColor(pColor[i]);
            addPaint.setAlpha((int) (alpha * 70));
            canvas.drawCircle(point[0], point[1], size * 3.5f, addPaint);
            addPaint.setAlpha((int) (alpha * 255));
            canvas.drawCircle(point[0], point[1], size, addPaint);
        }
    }

    private void respawn(int i) {
        int k = random.nextInt(spawnCount);
        pu[i] = spawnU[k];
        pv[i] = spawnV[k];
        pColor[i] = brighten(spawnColor[k]);
        pDrift[i] = 0.5f + random.nextFloat();
        pLife[i] = 0f;
        pMaxLife[i] = 2.5f + random.nextFloat() * 4f;
        pSize[i] = 0.8f + random.nextFloat() * 1.6f;
        pPhase[i] = random.nextFloat() * 6.283f;
    }

    /** Same result as Color.colorToHSV, without a native call per pixel. */
    private static void toHsv(int c, float[] hsv) {
        float r = ((c >> 16) & 0xFF) / 255f;
        float g = ((c >> 8) & 0xFF) / 255f;
        float b = (c & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h = 0f;
        if (delta > 0f) {
            if (max == r) {
                h = 60f * (((g - b) / delta) % 6f);
            } else if (max == g) {
                h = 60f * ((b - r) / delta + 2f);
            } else {
                h = 60f * ((r - g) / delta + 4f);
            }
            if (h < 0f) {
                h += 360f;
            }
        }
        hsv[0] = h;
        hsv[1] = max > 0f ? delta / max : 0f;
        hsv[2] = max;
    }

    private static int brighten(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(hsv[1], 0.7f);
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    private static int scale(int c, float f) {
        if (f <= 0f) {
            return 0xFF000000;
        }
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    private static float hueDistance(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Separable box blur of opaque ARGB pixels, in place. */
    private static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        blurPass(px, tmp, w, h, r, true);
        blurPass(tmp, px, w, h, r, false);
    }

    private static void blurPass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int rs = 0;
            int gs = 0;
            int bs = 0;
            for (int k = -r; k <= r; k++) {
                int c = src[index(line, clampInt(k, 0, len - 1), w, horizontal)];
                rs += (c >> 16) & 0xFF;
                gs += (c >> 8) & 0xFF;
                bs += c & 0xFF;
            }
            for (int k = 0; k < len; k++) {
                dst[index(line, k, w, horizontal)] = 0xFF000000 | ((rs / div) << 16) | ((gs / div) << 8) | (bs / div);
                int out = src[index(line, clampInt(k - r, 0, len - 1), w, horizontal)];
                int in = src[index(line, clampInt(k + r + 1, 0, len - 1), w, horizontal)];
                rs += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                gs += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                bs += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int index(int line, int k, int w, boolean horizontal) {
        return horizontal ? line * w + k : k * w + line;
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
