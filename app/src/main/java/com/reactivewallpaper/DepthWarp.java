package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

/**
 * 3D depth from a single photo. A depth map is guessed from the picture
 * itself: the marked subject is nearest, the lower part of a scene is
 * usually nearer than the top, and fine sharp detail usually nearer than
 * soft, hazy areas. The picture (and its glow) is then drawn as a mesh whose
 * points shift by how near they are, so tilting the phone, swiping or the
 * slow look-around reveal real-looking depth instead of one flat slide.
 */
final class DepthWarp {
    private static final int GRID = 64;
    static final int COLS = 36;
    static final int ROWS = 64;

    /** 0 = far .. 1 = near, GRID wide and {@link #gh} tall. */
    private final float[] depth;
    private final int gw;
    private final int gh;
    private final float[] points = new float[(COLS + 1) * (ROWS + 1) * 2];
    private final float[] verts = new float[(COLS + 1) * (ROWS + 1) * 2];
    private float offsetX;
    private float offsetY;

    private DepthWarp(float[] depth, int gw, int gh) {
        this.depth = depth;
        this.gw = gw;
        this.gh = gh;
    }

    /** How far the nearest points move past the farthest this frame, in screen pixels. */
    void setOffset(float x, float y) {
        offsetX = x;
        offsetY = y;
    }

    boolean moving() {
        return Math.abs(offsetX) > 0.2f || Math.abs(offsetY) > 0.2f;
    }

    /**
     * Draws {@code bitmap} (which covers the whole picture, at any size) as
     * {@code matrix} places it, each part shifted by its depth.
     */
    void draw(Canvas canvas, Bitmap bitmap, Matrix matrix, Paint paint) {
        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        int k = 0;
        for (int j = 0; j <= ROWS; j++) {
            for (int i = 0; i <= COLS; i++) {
                points[k++] = bw * i / (float) COLS;
                points[k++] = bh * j / (float) ROWS;
            }
        }
        matrix.mapPoints(verts, points);
        k = 0;
        for (int j = 0; j <= ROWS; j++) {
            for (int i = 0; i <= COLS; i++) {
                // Around the middle depth: near parts move with the offset, far ones against it.
                float d = sample(i / (float) COLS, j / (float) ROWS) - 0.5f;
                verts[k++] += d * offsetX;
                verts[k++] += d * offsetY;
            }
        }
        canvas.drawBitmapMesh(bitmap, COLS, ROWS, verts, 0, null, 0, paint);
    }

    /** Depth at a point of the picture (0..1 each way), smoothly in between grid cells. */
    float sample(float u, float v) {
        float x = Math.max(0f, Math.min(gw - 1f, u * (gw - 1)));
        float y = Math.max(0f, Math.min(gh - 1f, v * (gh - 1)));
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(gw - 1, x0 + 1);
        int y1 = Math.min(gh - 1, y0 + 1);
        float fx = x - x0;
        float fy = y - y0;
        float top = depth[y0 * gw + x0] * (1 - fx) + depth[y0 * gw + x1] * fx;
        float bottom = depth[y1 * gw + x0] * (1 - fx) + depth[y1 * gw + x1] * fx;
        return top * (1 - fy) + bottom * fy;
    }

    /** Guesses the picture's depth; {@code subject} (may be null) is the marked subject layer. */
    static DepthWarp estimate(Bitmap image, SubjectLayer subject) {
        int gw = GRID;
        int gh = Math.max(8, Math.min(160, Math.round(GRID * image.getHeight() / (float) image.getWidth())));
        Bitmap small = Bitmap.createScaledBitmap(image, gw, gh, true);
        int[] px = new int[gw * gh];
        small.getPixels(px, 0, gw, 0, 0, gw, gh);
        if (small != image) {
            small.recycle();
        }
        float[] subjectMask = null;
        if (subject != null) {
            subjectMask = new float[gw * gh];
            for (SubjectLayer.Part part : subject.parts) {
                Bitmap cut = Bitmap.createScaledBitmap(part.cutout, gw, gh, true);
                int[] a = new int[gw * gh];
                cut.getPixels(a, 0, gw, 0, 0, gw, gh);
                cut.recycle();
                for (int i = 0; i < a.length; i++) {
                    subjectMask[i] = Math.max(subjectMask[i], (a[i] >>> 24) / 255f);
                }
            }
        }
        return new DepthWarp(estimate(px, subjectMask, gw, gh), gw, gh);
    }

    /** The guess itself, on plain pixels (ARGB) and an optional subject mask (0..1). */
    static float[] estimate(int[] px, float[] subjectMask, int gw, int gh) {
        int n = gw * gh;
        float[] lum = new float[n];
        for (int i = 0; i < n; i++) {
            int c = px[i];
            lum[i] = (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
        }
        // Fine detail: how much each spot differs from its blurred surroundings.
        float[] soft = SubjectLayer.blur(lum, gw, gh, 2, 2);
        float[] detail = new float[n];
        for (int i = 0; i < n; i++) {
            detail[i] = Math.abs(lum[i] - soft[i]);
        }
        detail = SubjectLayer.blur(detail, gw, gh, 3, 2);
        float top = percentile(detail, 0.9f);
        float[] d = new float[n];
        for (int y = 0; y < gh; y++) {
            float low = (float) Math.pow(y / (float) (gh - 1), 1.2);
            for (int x = 0; x < gw; x++) {
                int i = y * gw + x;
                float sharp = top > 1e-4f ? Math.min(1f, detail[i] / top) : 0f;
                d[i] = 0.6f * low + 0.4f * sharp;
            }
        }
        d = SubjectLayer.blur(d, gw, gh, 4, 3);
        // Stretch to the full range, then put the subject in front of everything.
        float lo = percentile(d, 0.03f);
        float hi = percentile(d, 0.97f);
        for (int i = 0; i < n; i++) {
            d[i] = hi > lo ? Math.max(0f, Math.min(1f, (d[i] - lo) / (hi - lo))) * 0.8f : 0.4f;
        }
        if (subjectMask != null) {
            float[] m = SubjectLayer.blur(subjectMask, gw, gh, 1, 2);
            for (int i = 0; i < n; i++) {
                d[i] = d[i] * (1f - m[i]) + 1f * m[i];
            }
        }
        return d;
    }

    private static float percentile(float[] values, float p) {
        float[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, Math.max(0, Math.round(p * (sorted.length - 1))))];
    }
}
