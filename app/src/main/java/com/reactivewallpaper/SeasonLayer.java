package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Calendar;
import java.util.Random;

/**
 * Seasonal scenes over the wallpaper, in their own natural colors (never
 * tinted by the look): cherry blossom petals, autumn leaves, summer
 * fireflies, winter fairy lights with snow, and Halloween embers and bats.
 * The music nudges each: petals and leaves swirl with the bass, fireflies
 * and lights glow on the beat, bats flap faster.
 */
final class SeasonLayer {
    static final int PETALS = 10;
    static final int LEAVES = 11;
    static final int FIREFLIES = 12;
    static final int WINTER = 13;
    static final int HALLOWEEN = 14;

    /** Southern hemisphere (from the saved location), which flips the seasons for "by the date". */
    static volatile boolean southern;

    static boolean isSeason(int kind) {
        return kind >= PETALS && kind <= HALLOWEEN;
    }

    /** The scene for a date: spring petals, summer fireflies, autumn leaves (Halloween's last two weeks of October), winter lights. Southern hemisphere seasons are flipped. */
    static int forDate(Calendar c, boolean southern) {
        int month = c.get(Calendar.MONTH);
        int day = c.get(Calendar.DAY_OF_MONTH);
        if (month == Calendar.OCTOBER && day >= 15) {
            return HALLOWEEN;
        }
        if (southern) {
            month = (month + 6) % 12;
        }
        switch (month) {
            case Calendar.MARCH:
            case Calendar.APRIL:
            case Calendar.MAY:
                return PETALS;
            case Calendar.JUNE:
            case Calendar.JULY:
            case Calendar.AUGUST:
                return FIREFLIES;
            case Calendar.SEPTEMBER:
            case Calendar.OCTOBER:
            case Calendar.NOVEMBER:
                return LEAVES;
            default:
                return WINTER;
        }
    }

    private static final int MAX = 90;
    private static final int[] PETAL_COLORS = {0xFFFFC4DA, 0xFFFFB0CC, 0xFFF9D6E4, 0xFFFFE6EF};
    private static final int[] LEAF_COLORS = {0xFFE0662A, 0xFFC8402A, 0xFFE8A33A, 0xFF9E5A2C, 0xFFD9852B};
    private static final int[] LIGHT_COLORS = {0xFFFFD27A, 0xFFFF6B6B, 0xFF7BE0A8, 0xFF7AB8FF, 0xFFFFA8E0};

    private final float density;
    private final Random random = new Random(11);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sprite = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Path shape = new Path();
    private final float[] x = new float[MAX];
    private final float[] y = new float[MAX];
    private final float[] speed = new float[MAX];
    private final float[] size = new float[MAX];
    private final float[] spin = new float[MAX];
    private final float[] phase = new float[MAX];
    private final int[] color = new int[MAX];
    private Bitmap glow;
    private float time;
    private int lastKind = -1;
    // Bats: a few, crossing now and then.
    private final float[] batX = new float[4];
    private final float[] batY = new float[4];
    private final float[] batSpeed = new float[4];
    private final float[] batSize = new float[4];
    private float nextBat = 2f;

    SeasonLayer(float density) {
        this.density = density;
    }

    private void respawn(int kind, int i, boolean anywhere) {
        x[i] = random.nextFloat() * 1.2f - 0.1f;
        y[i] = anywhere ? random.nextFloat() : (kind == HALLOWEEN ? 1.05f : -0.06f);
        speed[i] = 0.6f + random.nextFloat() * 0.8f;
        float d = random.nextFloat();
        size[i] = 0.35f + 0.65f * d * d;
        spin[i] = random.nextFloat() * 6.28f;
        phase[i] = random.nextFloat() * 6.28f;
        int[] palette = kind == LEAVES ? LEAF_COLORS : kind == WINTER ? LIGHT_COLORS : PETAL_COLORS;
        color[i] = palette[random.nextInt(palette.length)];
        if (kind == WINTER) {
            // Fairy lights hang in the upper part of the picture and stay put.
            y[i] = 0.04f + random.nextFloat() * 0.45f;
        }
    }

    void draw(Canvas canvas, int w, int h, int kind, float strength, float wind, float bass, float beat, float dt) {
        time += dt;
        if (kind != lastKind) {
            lastKind = kind;
            for (int i = 0; i < MAX; i++) {
                respawn(kind, i, true);
            }
            for (int b = 0; b < batX.length; b++) {
                batX[b] = -1f;
            }
        }
        if (strength <= 0.01f) {
            return;
        }
        int count = Math.round(MAX * Math.min(1f, 0.25f + 0.75f * strength));
        switch (kind) {
            case PETALS:
            case LEAVES:
                drawFalling(canvas, w, h, kind, count, wind, bass, dt);
                break;
            case FIREFLIES:
                drawFireflies(canvas, w, h, count / 2, beat, dt);
                break;
            case WINTER:
                drawLights(canvas, w, h, count / 2, beat);
                break;
            case HALLOWEEN:
                drawEmbers(canvas, w, h, count, bass, dt);
                drawBats(canvas, w, h, beat, dt);
                break;
            default:
                break;
        }
    }

    /** Petals and leaves: fall, sway and tumble (they flip as they turn), swirling more with the bass. */
    private void drawFalling(Canvas canvas, int w, int h, int kind, int count, float wind, float bass, float dt) {
        boolean leaf = kind == LEAVES;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        for (int i = 0; i < count; i++) {
            float fall = (leaf ? 0.09f : 0.06f) * speed[i] * (0.6f + 0.6f * size[i]);
            y[i] += fall * dt;
            float sway = (float) Math.sin(time * (0.8f + 0.4f * speed[i]) + phase[i]);
            x[i] += ((wind + 0.15f) * 0.04f + sway * (0.03f + 0.03f * bass)) * dt;
            spin[i] += dt * (1.2f + 1.5f * speed[i]) * (1f + bass);
            if (y[i] > 1.06f) {
                respawn(kind, i, false);
            }
            if (x[i] < -0.1f) {
                x[i] += 1.2f;
            } else if (x[i] > 1.1f) {
                x[i] -= 1.2f;
            }
            float r = (leaf ? 9f : 6f) * density * size[i];
            canvas.save();
            canvas.translate(x[i] * w, y[i] * h);
            canvas.rotate((float) Math.toDegrees(spin[i] * 0.6f));
            // Tumbling: the shape narrows as it turns edge-on.
            canvas.scale(0.25f + 0.75f * Math.abs((float) Math.cos(spin[i])), 1f);
            paint.setColor(color[i]);
            paint.setAlpha((int) (150 + 100 * size[i]));
            if (leaf) {
                leafShape(r);
                canvas.drawPath(shape, paint);
                // A darker vein.
                paint.setColor(0x55000000);
                paint.setStrokeWidth(0.8f * density);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(0, -r, 0, r, paint);
                paint.setStyle(Paint.Style.FILL);
            } else {
                petalShape(r);
                canvas.drawPath(shape, paint);
            }
            canvas.restore();
        }
    }

    /** A cherry petal: rounded, with the little notch at its tip. */
    private void petalShape(float r) {
        shape.reset();
        shape.moveTo(0, r);
        shape.cubicTo(-r * 0.9f, r * 0.4f, -r * 0.8f, -r * 0.8f, -r * 0.18f, -r);
        shape.lineTo(0, -r * 0.72f);
        shape.lineTo(r * 0.18f, -r);
        shape.cubicTo(r * 0.8f, -r * 0.8f, r * 0.9f, r * 0.4f, 0, r);
        shape.close();
    }

    /** A simple pointed leaf. */
    private void leafShape(float r) {
        shape.reset();
        shape.moveTo(0, -r);
        shape.cubicTo(r * 0.8f, -r * 0.5f, r * 0.7f, r * 0.5f, 0, r);
        shape.cubicTo(-r * 0.7f, r * 0.5f, -r * 0.8f, -r * 0.5f, 0, -r);
        shape.close();
    }

    private Bitmap glow() {
        if (glow == null) {
            int s = 64;
            glow = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                    new int[] {0xFFFFFFFF, 0xAAFFFFFF, 0x33FFFFFF, 0x00FFFFFF}, new float[] {0f, 0.15f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            new Canvas(glow).drawCircle(s / 2f, s / 2f, s / 2f, p);
        }
        return glow;
    }

    private final java.util.Map<Integer, android.graphics.ColorFilter> tints = new java.util.HashMap<>();

    private void drawGlow(Canvas canvas, float px, float py, float r, int rgb, float alpha) {
        android.graphics.ColorFilter tint = tints.get(rgb);
        if (tint == null) {
            tint = new android.graphics.PorterDuffColorFilter(rgb | 0xFF000000, android.graphics.PorterDuff.Mode.MULTIPLY);
            tints.put(rgb, tint);
        }
        sprite.setColorFilter(tint);
        sprite.setAlpha((int) (255 * Math.max(0f, Math.min(1f, alpha))));
        dst.set(px - r, py - r, px + r, py + r);
        canvas.drawBitmap(glow(), null, dst, sprite);
    }

    /** Fireflies wander slowly in the lower part of the picture, each blinking on its own, all brighter on the beat. */
    private void drawFireflies(Canvas canvas, int w, int h, int count, float beat, float dt) {
        for (int i = 0; i < count; i++) {
            float px = (float) (0.5f + 0.48f * Math.sin(time * 0.05f * speed[i] + phase[i] * 3f)
                    + 0.04f * Math.sin(time * 0.7f + phase[i])) * w;
            float py = (float) (0.55f + 0.38f * Math.sin(time * 0.04f * speed[i] + phase[i] * 2f)
                    + 0.03f * Math.cos(time * 0.9f + phase[i])) * h;
            float blink = (float) Math.max(0, Math.sin(time * (0.7f + 0.5f * speed[i]) + phase[i] * 5f));
            float glowAmount = blink * blink * (0.7f + 0.5f * beat);
            float r = (7f + 9f * size[i]) * density * (0.8f + 0.4f * glowAmount);
            drawGlow(canvas, px, py, r, 0xFFEFFF8A, glowAmount);
        }
    }

    /** Fairy lights hang still and twinkle, brighter on the beat; a light snow falls past them. */
    private void drawLights(Canvas canvas, int w, int h, int count, float beat) {
        for (int i = 0; i < count; i++) {
            float twinkle = 0.55f + 0.45f * (float) Math.sin(time * (1.1f + speed[i]) + phase[i]);
            float r = (6f + 14f * size[i]) * density;
            drawGlow(canvas, x[i] * w, y[i] * h, r, color[i], twinkle * (0.55f + 0.6f * beat));
        }
        // Snow: small soft flakes, drifting down.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        for (int i = count; i < Math.min(MAX, count * 2); i++) {
            float fy = (float) ((time * 0.04f * speed[i] + phase[i]) % 1.1f) - 0.05f;
            float fx = x[i] + 0.02f * (float) Math.sin(time * 0.8f + phase[i]);
            paint.setColor(Color.WHITE);
            paint.setAlpha((int) (120 + 100 * size[i]));
            canvas.drawCircle(fx * w, fy * h, (1.2f + 2.2f * size[i]) * density, paint);
        }
    }

    /** Embers rise and flicker out, more with the bass. */
    private void drawEmbers(Canvas canvas, int w, int h, int count, float bass, float dt) {
        for (int i = 0; i < count; i++) {
            y[i] -= (0.05f + 0.06f * speed[i]) * (1f + 0.8f * bass) * dt;
            x[i] += 0.015f * (float) Math.sin(time * 1.3f + phase[i]) * dt;
            if (y[i] < -0.05f) {
                respawn(HALLOWEEN, i, false);
            }
            float flicker = 0.6f + 0.4f * (float) Math.sin(time * 9f + phase[i] * 7f);
            float fade = Math.min(1f, y[i] * 1.6f);
            float r = (2.5f + 5f * size[i]) * density;
            drawGlow(canvas, x[i] * w, y[i] * h, r, i % 3 == 0 ? 0xFFFFC35A : 0xFFFF7A2E, flicker * fade);
        }
    }

    /** Now and then a bat or a few flap across, their wings beating faster with the music. */
    private void drawBats(Canvas canvas, int w, int h, float beat, float dt) {
        nextBat -= dt;
        if (nextBat <= 0f) {
            int flock = 1 + random.nextInt(3);
            float startY = 0.1f + random.nextFloat() * 0.35f;
            for (int b = 0, made = 0; b < batX.length && made < flock; b++) {
                if (batX[b] < 0f) {
                    batX[b] = -0.1f - made * 0.08f;
                    batY[b] = startY + made * 0.04f;
                    batSpeed[b] = 0.12f + random.nextFloat() * 0.08f;
                    batSize[b] = 0.7f + random.nextFloat() * 0.6f;
                    made++;
                }
            }
            nextBat = 4f + random.nextFloat() * 7f;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0xEE120A18);
        for (int b = 0; b < batX.length; b++) {
            if (batX[b] < -0.5f) {
                continue;
            }
            batX[b] += batSpeed[b] * dt;
            if (batX[b] > 1.15f) {
                batX[b] = -1f;
                continue;
            }
            float bx = batX[b] * w;
            float by = (batY[b] + 0.03f * (float) Math.sin(time * 2.5f + b)) * h;
            float flap = (float) Math.sin(time * (12f + 10f * beat) + b * 1.7f);
            float s = 11f * density * batSize[b];
            shape.reset();
            // Body, then each wing as a scalloped curve that rises and falls with the flap.
            shape.addOval(bx - s * 0.18f, by - s * 0.3f, bx + s * 0.18f, by + s * 0.3f, Path.Direction.CW);
            for (int side = -1; side <= 1; side += 2) {
                float tipY = by - s * 0.6f * flap;
                shape.moveTo(bx, by - s * 0.1f);
                shape.quadTo(bx + side * s * 0.6f, tipY - s * 0.4f, bx + side * s * 1.3f, tipY);
                shape.quadTo(bx + side * s * 0.95f, tipY + s * 0.15f, bx + side * s * 0.8f, tipY + s * 0.4f);
                shape.quadTo(bx + side * s * 0.55f, tipY + s * 0.2f, bx + side * s * 0.4f, by + s * 0.25f);
                shape.close();
            }
            canvas.drawPath(shape, paint);
        }
    }

    void release() {
        if (glow != null) {
            glow.recycle();
            glow = null;
        }
    }
}
