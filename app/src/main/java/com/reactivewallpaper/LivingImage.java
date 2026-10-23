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
    private final float[] pVu = new float[PARTICLE_COUNT];
    private final float[] pVv = new float[PARTICLE_COUNT];
    private final int[] pColor = new int[PARTICLE_COUNT];
    private int particleStyle = -1;

    private final Random random = new Random();
    private final Paint addPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint addStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix layerMatrix = new Matrix();
    private final float[] point = new float[2];
    private float time;
    private float breathePhase;

    /** Mask value meaning "let the app decide" (painted masks store 0 = no glow, 255 = glow). */
    static final int MASK_AUTO = 128;

    /** Receives points that are good places for sparkles to appear. */
    private interface SpawnSink {
        void add(int index, int color);
    }

    /**
     * @param coverage 0 .. 1; higher lets dimmer, less saturated parts of the image glow too
     * @param mask painted glow mask (any size, gray: 0 = never glow, 128 = auto, 255 = always), or null
     */
    LivingImage(Bitmap image, Bitmap mask, float[] hues, float coverage) {
        imageWidth = image.getWidth();
        imageHeight = image.getHeight();
        this.coverage = coverage;
        addPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        addStroke.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        addStroke.setStyle(Paint.Style.STROKE);
        addStroke.setStrokeCap(Paint.Cap.ROUND);

        int w = Math.max(1, imageWidth / 2);
        int h = Math.max(1, imageHeight / 2);
        int[] pixels = pixelsAt(image, w, h);
        int[] maskPixels = mask != null ? maskAt(mask, w, h) : null;

        int[] groupA = new int[w * h];
        int[] groupB = new int[w * h];
        float[] tmpU = new float[MAX_SPAWN_POINTS];
        float[] tmpV = new float[MAX_SPAWN_POINTS];
        int[] tmpColor = new int[MAX_SPAWN_POINTS];
        int[] candidates = {0};
        // Reservoir-sample the candidate points so they cover the whole image evenly.
        computeGlow(pixels, maskPixels, hues, coverage, groupA, groupB, (i, c) -> {
            int n = candidates[0];
            int slot = n < MAX_SPAWN_POINTS ? n : random.nextInt(n + 1);
            if (slot < MAX_SPAWN_POINTS) {
                tmpU[slot] = (i % w + 0.5f) / w;
                tmpV[slot] = (i / w + 0.5f) / h;
                tmpColor[slot] = c;
            }
            candidates[0] = n + 1;
        });
        int candidateCount = candidates[0];
        spawnCount = Math.min(candidateCount, MAX_SPAWN_POINTS);
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

    }

    float coverage() {
        return coverage;
    }

    private static int[] pixelsAt(Bitmap source, int w, int h) {
        Bitmap scaled = Bitmap.createScaledBitmap(source, w, h, true);
        int[] out = new int[w * h];
        scaled.getPixels(out, 0, w, 0, 0, w, h);
        if (scaled != source) {
            scaled.recycle();
        }
        return out;
    }

    /** The painted mask at {@code w x h}, with feathered edges so glow fades in softly. */
    private static int[] maskAt(Bitmap mask, int w, int h) {
        int[] px = pixelsAt(mask, w, h);
        int radius = Math.max(1, Math.max(w, h) / 300);
        boxBlur(px, w, h, radius);
        boxBlur(px, w, h, radius);
        return px;
    }

    /**
     * Glow for an image as the painter shows it: both color groups added
     * together, at {@code w x h}. Call off the main thread.
     */
    static Bitmap glowPreview(Bitmap image, Bitmap mask, float[] hues, float coverage, int w, int h) {
        int[] pixels = pixelsAt(image, w, h);
        int[] maskPixels = mask != null ? maskAt(mask, w, h) : null;
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        computeGlow(pixels, maskPixels, hues, coverage, a, b, null);
        for (int i = 0; i < a.length; i++) {
            a[i] = Color.rgb(Math.min(255, Color.red(a[i]) + Color.red(b[i])),
                    Math.min(255, Color.green(a[i]) + Color.green(b[i])),
                    Math.min(255, Color.blue(a[i]) + Color.blue(b[i])));
        }
        return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888);
    }

    /**
     * Decides how strongly each pixel glows and splits it between the two hue
     * groups. A painted mask can force glow on (in the pixel's own hue, even if
     * it is dim) or off.
     */
    private static void computeGlow(int[] pixels, int[] mask, float[] hues, float coverage,
                                    int[] groupA, int[] groupB, SpawnSink spawns) {
        float lowEdge = 0.7f - 0.55f * coverage;
        float saturationFloor = 0.15f + 0.35f * coverage;
        float[] hsv = new float[3];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            toHsv(c, hsv);
            float weight = smoothstep(lowEdge, lowEdge + 0.45f, hsv[2])
                    * (saturationFloor + (1f - saturationFloor) * hsv[1]);
            float r = Color.red(c) * weight;
            float g = Color.green(c) * weight;
            float b = Color.blue(c) * weight;

            float painted = mask != null ? (mask[i] & 0xFF) / 255f : 0.5f;
            if (painted > 0.52f) {
                // Painted "glow": the pixel brightened (up to 3x) in its own color, so
                // dim areas glow but keep their texture. Pure black has nothing to glow.
                float force = Math.min(1f, (painted - 0.5f) * 2f);
                float gain = Math.min(3f, 0.7f / Math.max(hsv[2], 0.001f));
                r += (Color.red(c) * gain - r) * force;
                g += (Color.green(c) * gain - g) * force;
                b += (Color.blue(c) * gain - b) * force;
                r = Math.min(255f, r);
                g = Math.min(255f, g);
                b = Math.min(255f, b);
                weight = Math.max(weight, force * Math.min(1f, hsv[2] * gain));
            } else if (painted < 0.48f) {
                // Painted "no glow".
                float keep = Math.max(0f, painted * 2f);
                r *= keep;
                g *= keep;
                b *= keep;
                weight *= keep;
            }
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
            groupA[i] = Color.rgb((int) (r * toA), (int) (g * toA), (int) (b * toA));
            groupB[i] = Color.rgb((int) (r * (1f - toA)), (int) (g * (1f - toA)), (int) (b * (1f - toA)));

            if (spawns != null && weight > 0.3f && (hsv[1] > 0.3f || painted > 0.75f)) {
                spawns.add(i, c);
            }
        }
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
     * @param particleDx extra screen offset for particles, which sit "in front" for parallax
     * @param colorFilter recolors the glow and particles, or null
     * @param particleScale fraction of the particle count to draw (lower to save battery)
     */
    void draw(Canvas canvas, Matrix imageMatrix, AudioAnalyzer audio, WallpaperSettings s,
              float dt, float density, float particleDx, float particleDy,
              ColorFilter colorFilter, float particleScale) {
        time += dt;
        breathePhase += dt * s.breatheSpeed;
        float bass = audio.bass();
        float beat = audio.beat();
        float high = audio.high();
        addPaint.setColorFilter(colorFilter);
        addStroke.setColorFilter(colorFilter);

        float idleA = s.breathe * (0.3f + 0.22f * (float) Math.sin(breathePhase * 1.1f));
        float idleB = s.breathe * (0.3f + 0.22f * (float) Math.sin(breathePhase * 0.9f + 2.1f));
        float bassAmount = idleA + s.bassGlow * bass * 1.3f + s.beatFlare * beat;
        float highAmount = idleB + s.highGlow * high * 1.6f + s.beatFlare * beat * 0.35f;
        int bassGroup = s.swapGlowColors ? 1 : 0;
        drawGroup(canvas, imageMatrix, bassGroup, bassAmount, s.bloom);
        drawGroup(canvas, imageMatrix, 1 - bassGroup, highAmount, s.bloom);

        int particles = Math.round(s.sparkleAmount * PARTICLE_COUNT * particleScale);
        if (particles > 0 && spawnCount > 0) {
            if (s.particleStyle != particleStyle) {
                particleStyle = s.particleStyle;
                for (int i = 0; i < PARTICLE_COUNT; i++) {
                    respawn(i);
                    pLife[i] = random.nextFloat() * pMaxLife[i];
                }
            }
            drawParticles(canvas, imageMatrix, particles, dt, density, bass, beat, audio.level(), s,
                    particleDx, particleDy);
        }
        addPaint.setColorFilter(null);
        addStroke.setColorFilter(null);
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
                               float bass, float beat, float level, WallpaperSettings s,
                               float offsetX, float offsetY) {
        float react = s.sparkleReact;
        float energy = react * (4f * level + 2f * beat);
        float speed = s.sparkleSpeed * (1f + energy);
        if (particleStyle == WallpaperSettings.PARTICLE_STARS) {
            // Streaking stars are big and fast; fewer at once reads better.
            count = Math.max(1, count / 4);
        }
        for (int i = 0; i < count; i++) {
            pLife[i] += dt * s.sparkleSpeed * (1f + react * level);
            if (pLife[i] >= pMaxLife[i]) {
                respawn(i);
            }
            float wobble = (float) Math.sin(time * 0.7f + pPhase[i]) * pDrift[i];
            switch (particleStyle) {
                case WallpaperSettings.PARTICLE_EMBERS:
                    pu[i] += dt * (pVu[i] + 0.02f * wobble) * speed;
                    break;
                case WallpaperSettings.PARTICLE_BUBBLES:
                    pu[i] += dt * 0.01f * (float) Math.sin(time * 2f + pPhase[i]) * speed;
                    break;
                case WallpaperSettings.PARTICLE_SPARKLES:
                    pu[i] += dt * 0.006f * wobble * speed;
                    break;
                default:
                    pu[i] += dt * pVu[i] * speed;
                    break;
            }
            pv[i] += dt * pVv[i] * speed;

            float life = pLife[i] / pMaxLife[i];
            float fade = (float) Math.sin(Math.PI * life);
            float flicker;
            if (particleStyle == WallpaperSettings.PARTICLE_EMBERS) {
                flicker = 0.55f + 0.45f * (float) Math.sin(time * 11f + pPhase[i] * 7f);
            } else if (particleStyle == WallpaperSettings.PARTICLE_SPARKLES) {
                flicker = 0.6f + 0.4f * (float) Math.sin(time * 3f + pPhase[i] * 5f);
            } else {
                flicker = 1f;
            }
            float alpha = Math.min(1f, fade * flicker * s.sparkleBrightness
                    * (0.6f + react * (0.8f * level + 0.6f * beat)));
            if (alpha <= 0.01f) {
                continue;
            }
            point[0] = pu[i] * imageWidth;
            point[1] = pv[i] * imageHeight;
            imageMatrix.mapPoints(point);
            float x = point[0] + offsetX;
            float y = point[1] + offsetY;
            float size = pSize[i] * s.sparkleSize * density * (1f + react * (0.8f * bass + 0.6f * beat));
            addPaint.setColor(pColor[i]);
            addStroke.setColor(pColor[i]);

            switch (particleStyle) {
                case WallpaperSettings.PARTICLE_STARS:
                case WallpaperSettings.PARTICLE_STREAKS: {
                    // A line trailing behind the direction of travel, brightest at the head.
                    float trail = (particleStyle == WallpaperSettings.PARTICLE_STARS ? 0.5f : 0.9f) * speed;
                    float tx = x - pVu[i] * imageWidth * trail * scaleOf(imageMatrix);
                    float ty = y - pVv[i] * imageHeight * trail * scaleOf(imageMatrix);
                    addStroke.setStrokeWidth(size * 2.4f);
                    addStroke.setAlpha((int) (alpha * 50));
                    canvas.drawLine(tx, ty, x, y, addStroke);
                    addStroke.setStrokeWidth(size * 0.8f);
                    addStroke.setAlpha((int) (alpha * 220));
                    canvas.drawLine(tx, ty, x, y, addStroke);
                    addPaint.setAlpha((int) (alpha * 255));
                    canvas.drawCircle(x, y, size, addPaint);
                    break;
                }
                case WallpaperSettings.PARTICLE_BUBBLES: {
                    float radius = size * 4f;
                    addStroke.setStrokeWidth(Math.max(1f, size * 0.6f));
                    addStroke.setAlpha((int) (alpha * 170));
                    canvas.drawCircle(x, y, radius, addStroke);
                    addPaint.setAlpha((int) (alpha * 200));
                    canvas.drawCircle(x - radius * 0.35f, y - radius * 0.35f, size * 0.7f, addPaint);
                    break;
                }
                default: {
                    addPaint.setAlpha((int) (alpha * 70));
                    canvas.drawCircle(x, y, size * 3.5f, addPaint);
                    addPaint.setAlpha((int) (alpha * 255));
                    canvas.drawCircle(x, y, size, addPaint);
                    break;
                }
            }
        }
    }

    private final float[] matrixValues = new float[9];

    private float scaleOf(Matrix m) {
        m.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }

    /** Starts particle i again, with position and motion suited to the current style. */
    private void respawn(int i) {
        int k = random.nextInt(spawnCount);
        pColor[i] = brighten(spawnColor[k]);
        pDrift[i] = 0.5f + random.nextFloat();
        pLife[i] = 0f;
        pPhase[i] = random.nextFloat() * 6.283f;
        switch (particleStyle) {
            case WallpaperSettings.PARTICLE_EMBERS:
                // Rise from the bottom, a bit faster than sparkles.
                pu[i] = random.nextFloat();
                pv[i] = 0.75f + random.nextFloat() * 0.3f;
                pVu[i] = (random.nextFloat() - 0.5f) * 0.01f;
                pVv[i] = -(0.035f + random.nextFloat() * 0.03f);
                pMaxLife[i] = 3f + random.nextFloat() * 3f;
                pSize[i] = 0.6f + random.nextFloat() * 1.0f;
                break;
            case WallpaperSettings.PARTICLE_STARS:
                // Shoot diagonally down across the upper part of the picture.
                pu[i] = random.nextFloat() * 1.1f - 0.1f;
                pv[i] = random.nextFloat() * 0.55f - 0.05f;
                pVu[i] = 0.12f + random.nextFloat() * 0.08f;
                pVv[i] = 0.06f + random.nextFloat() * 0.05f;
                pMaxLife[i] = 0.7f + random.nextFloat() * 0.9f;
                pSize[i] = 0.9f + random.nextFloat() * 1.0f;
                pColor[i] = whiter(pColor[i]);
                break;
            case WallpaperSettings.PARTICLE_BUBBLES:
                pu[i] = random.nextFloat();
                pv[i] = 0.85f + random.nextFloat() * 0.25f;
                pVu[i] = 0f;
                pVv[i] = -(0.015f + random.nextFloat() * 0.02f);
                pMaxLife[i] = 6f + random.nextFloat() * 5f;
                pSize[i] = 0.8f + random.nextFloat() * 2.2f;
                break;
            case WallpaperSettings.PARTICLE_STREAKS:
                // Fast horizontal light streaks, either direction.
                pu[i] = random.nextFloat();
                pv[i] = 0.1f + random.nextFloat() * 0.8f;
                pVu[i] = (random.nextBoolean() ? 1f : -1f) * (0.1f + random.nextFloat() * 0.12f);
                pVv[i] = 0f;
                pMaxLife[i] = 1f + random.nextFloat() * 1.2f;
                pSize[i] = 0.5f + random.nextFloat() * 0.7f;
                break;
            default:
                // Sparkles appear on the image's own glowing spots and drift up.
                pu[i] = spawnU[k];
                pv[i] = spawnV[k];
                pVu[i] = 0f;
                pVv[i] = -0.012f;
                pMaxLife[i] = 2.5f + random.nextFloat() * 4f;
                pSize[i] = 0.8f + random.nextFloat() * 1.6f;
                break;
        }
    }

    private static int whiter(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(hsv[1], 0.3f);
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
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
