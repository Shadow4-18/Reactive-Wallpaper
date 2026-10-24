package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Soft light shapes for particles, generated once. Shapes are alpha maps
 * (0..1, row by row) that {@link #tint} turns into colored bitmaps.
 * Coordinates below run -1..1 across each sprite.
 */
final class Sprites {
    private Sprites() {}

    /** A soft round glow: a wide halo with a tighter core. */
    static float[] glow(int n) {
        float[] a = new float[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float x = coord(i, n);
                float y = coord(j, n);
                float r2 = x * x + y * y;
                float value = (float) (Math.exp(-r2 * 5.0) * 0.75 + Math.exp(-r2 * 28.0) * 0.6);
                a[j * n + i] = clamp(value * (1f - smooth(0.85f, 1f, (float) Math.sqrt(r2))));
            }
        }
        return a;
    }

    /** A small, bright point. */
    static float[] core(int n) {
        float[] a = new float[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float x = coord(i, n);
                float y = coord(j, n);
                a[j * n + i] = clamp((float) Math.exp(-(x * x + y * y) * 60.0));
            }
        }
        return a;
    }

    /** A trail: thin and soft across, brightest at the head (right end), fading to the tail. */
    static float[] streak(int w, int h) {
        float[] a = new float[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float x = coord(i, w);
                float y = coord(j, h);
                float along = (x + 1f) / 2f;
                float taper = (float) Math.pow(along, 2.2) * smooth(1f, 0.93f, along);
                a[j * w + i] = clamp((float) Math.exp(-y * y * 14.0) * taper);
            }
        }
        return a;
    }

    /** A lens-flare line: brightest in the middle, fading to both ends. */
    static float[] flare(int w, int h) {
        float[] a = new float[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                float x = coord(i, w);
                float y = coord(j, h);
                float along = (float) Math.pow(Math.max(0f, 1f - Math.abs(x)), 1.6);
                a[j * w + i] = clamp((float) Math.exp(-y * y * 18.0) * along);
            }
        }
        return a;
    }

    /** A four-pointed star glint. */
    static float[] glint(int n) {
        float[] a = new float[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float x = coord(i, n);
                float y = coord(j, n);
                double rays = Math.exp(-y * y * 400.0) * Math.pow(Math.max(0f, 1f - Math.abs(x)), 3)
                        + Math.exp(-x * x * 400.0) * Math.pow(Math.max(0f, 1f - Math.abs(y)), 3);
                a[j * n + i] = clamp((float) (rays + Math.exp(-(x * x + y * y) * 30.0) * 0.5));
            }
        }
        return a;
    }

    /**
     * A soap bubble for additive drawing: a thin rainbow-sheened rim, an almost
     * clear middle and a soft highlight.
     */
    static Bitmap bubble(int n) {
        int[] px = new int[n * n];
        float[] hsv = new float[3];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float x = coord(i, n);
                float y = coord(j, n);
                float r = (float) Math.sqrt(x * x + y * y);
                double angle = Math.atan2(y, x);
                float rim = smooth(0.78f, 0.96f, r) * (1f - smooth(0.96f, 1f, r));
                float alpha = rim * 0.85f + (r < 0.96f ? 0.04f : 0f);
                hsv[0] = (float) (((angle / (2 * Math.PI)) + 0.55 + 0.15 * Math.sin(angle * 3)) % 1.0 + 1.0) % 1f * 360f;
                hsv[1] = 0.45f;
                hsv[2] = 1f;
                int sheen = Color.HSVToColor(hsv);
                float hl = (float) (Math.exp(-(Math.pow((x + 0.38) / 0.22, 2) + Math.pow((y + 0.42) / 0.14, 2))) * 0.95
                        + Math.exp(-(Math.pow((x - 0.45) / 0.12, 2) + Math.pow((y - 0.5) / 0.08, 2))) * 0.35);
                float red = clamp(Color.red(sheen) / 255f * alpha + hl);
                float green = clamp(Color.green(sheen) / 255f * alpha + hl);
                float blue = clamp(Color.blue(sheen) / 255f * alpha + hl);
                px[j * n + i] = premultipliedToColor(red, green, blue);
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    /** Colors an alpha map. */
    static Bitmap tint(float[] alpha, int w, int h, int color) {
        int[] px = new int[w * h];
        int rgb = color & 0x00FFFFFF;
        for (int i = 0; i < px.length; i++) {
            px[i] = (Math.round(alpha[i] * 255f) << 24) | rgb;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Turns light amounts (as additive, premultiplied values) into an unpremultiplied color. */
    private static int premultipliedToColor(float r, float g, float b) {
        float a = Math.max(r, Math.max(g, b));
        if (a <= 0f) {
            return 0;
        }
        return Color.argb(Math.round(a * 255f), Math.round(r / a * 255f),
                Math.round(g / a * 255f), Math.round(b / a * 255f));
    }

    private static float coord(int i, int n) {
        return (i + 0.5f) / n * 2f - 1f;
    }

    private static float smooth(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
