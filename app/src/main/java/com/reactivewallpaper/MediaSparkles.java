package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.Random;

/**
 * Finds the glowing spots in a frame of a video or GIF, so sparkles appear on
 * (and follow) what's bright in the moving picture, not just its first frame.
 * Works on a small copy; call off the drawing thread.
 */
final class MediaSparkles {
    /** The width the frame is looked at; plenty to place sparkles. */
    static final int SAMPLE_WIDTH = 96;
    private static final int MAX_POINTS = 800;

    private MediaSparkles() {}

    /**
     * The glowing spots in {@code frame} as {u[], v[], {count}}, in the upright
     * picture's coordinates (0..1), or null if there are none.
     *
     * @param rotation degrees the frame is turned clockwise to be upright
     * @param coverage how much of the picture counts as glowing (as for still pictures)
     */
    static float[][] spots(Bitmap frame, int rotation, float coverage) {
        Bitmap soft = frame.getConfig() == Bitmap.Config.HARDWARE ? frame.copy(Bitmap.Config.ARGB_8888, false) : frame;
        if (soft == null) {
            return null;
        }
        int w = Math.min(SAMPLE_WIDTH, soft.getWidth());
        int h = Math.max(1, Math.round(soft.getHeight() * w / (float) soft.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(soft, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != soft) {
            small.recycle();
        }
        if (soft != frame) {
            soft.recycle();
        }
        return spots(px, w, h, rotation, coverage);
    }

    /** As above, on pixels already read. */
    static float[][] spots(int[] px, int w, int h, int rotation, float coverage) {
        float lowEdge = 0.7f - 0.55f * coverage;
        float saturationFloor = 0.15f + 0.35f * coverage;
        float[] u = new float[MAX_POINTS];
        float[] v = new float[MAX_POINTS];
        float[] hsv = new float[3];
        Random random = new Random(1);
        int seen = 0;
        for (int i = 0; i < px.length; i++) {
            Color.colorToHSV(px[i], hsv);
            float t = Math.max(0f, Math.min(1f, (hsv[2] - lowEdge) / 0.45f));
            float weight = t * t * (3f - 2f * t) * (saturationFloor + (1f - saturationFloor) * hsv[1]);
            // The same test still pictures use for where sparkles may appear.
            if (weight <= 0.3f || hsv[1] <= 0.3f) {
                continue;
            }
            int slot = seen < MAX_POINTS ? seen : random.nextInt(seen + 1);
            seen++;
            if (slot >= MAX_POINTS) {
                continue;
            }
            float x = (i % w + 0.5f) / w;
            float y = (i / w + 0.5f) / h;
            switch (((rotation % 360) + 360) % 360) {
                case 90:
                    u[slot] = 1f - y;
                    v[slot] = x;
                    break;
                case 180:
                    u[slot] = 1f - x;
                    v[slot] = 1f - y;
                    break;
                case 270:
                    u[slot] = y;
                    v[slot] = 1f - x;
                    break;
                default:
                    u[slot] = x;
                    v[slot] = y;
                    break;
            }
        }
        int count = Math.min(seen, MAX_POINTS);
        return count == 0 ? null : new float[][] {u, v, {count}};
    }
}
