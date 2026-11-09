package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.util.Locale;

/**
 * A simple skeleton for the character in a picture, so it moves with the
 * music: it breathes and sways, nods on the beat, its arms lift with the
 * bass, and its hair swings behind its head with a little physics.
 *
 * <p>The joints sit on the picture (0..1 each way); each part of the picture
 * follows the bones nearest it (smooth skinning over a mesh), so the cut-out
 * subject bends rather than splitting into pieces. Saved as a short text in
 * the look, next to the picture it belongs to.
 */
final class CharacterRig {
    static final int HIPS = 0;
    static final int CHEST = 1;
    static final int NECK = 2;
    static final int HEAD = 3;
    static final int L_SHOULDER = 4;
    static final int L_ELBOW = 5;
    static final int L_HAND = 6;
    static final int R_SHOULDER = 7;
    static final int R_ELBOW = 8;
    static final int R_HAND = 9;
    static final int L_HAIR1 = 10;
    static final int L_HAIR2 = 11;
    static final int L_HAIR3 = 12;
    static final int R_HAIR1 = 13;
    static final int R_HAIR2 = 14;
    static final int R_HAIR3 = 15;
    static final int COUNT = 16;

    static final int[] PARENT = {-1, HIPS, CHEST, NECK, CHEST, L_SHOULDER, L_ELBOW, CHEST, R_SHOULDER, R_ELBOW,
            HEAD, L_HAIR1, L_HAIR2, HEAD, R_HAIR1, R_HAIR2};
    static final String[] NAMES = {"Hips", "Chest", "Neck", "Head", "Left shoulder", "Left elbow", "Left hand",
            "Right shoulder", "Right elbow", "Right hand", "Hair (left)", "Hair (left)", "Hair tip (left)",
            "Hair (right)", "Hair (right)", "Hair tip (right)"};

    static boolean isArm(int j) {
        return j >= L_SHOULDER && j <= R_HAND;
    }

    static boolean isHair(int j) {
        return j >= L_HAIR1;
    }

    /** Joint positions on the picture, 0..1. */
    final float[] x = new float[COUNT];
    final float[] y = new float[COUNT];
    boolean arms = true;
    boolean hair = true;

    boolean used(int j) {
        return !(isArm(j) && !arms) && !(isHair(j) && !hair);
    }

    CharacterRig copy() {
        CharacterRig r = new CharacterRig();
        System.arraycopy(x, 0, r.x, 0, COUNT);
        System.arraycopy(y, 0, r.y, 0, COUNT);
        r.arms = arms;
        r.hair = hair;
        return r;
    }

    // ---- saving ----

    String encode() {
        StringBuilder b = new StringBuilder("r1;").append(arms ? 1 : 0).append(';').append(hair ? 1 : 0);
        for (int j = 0; j < COUNT; j++) {
            b.append(';').append(String.format(Locale.US, "%.4f,%.4f", x[j], y[j]));
        }
        return b.toString();
    }

    /** The rig saved with {@link #encode}, or null for none (or anything unreadable). */
    static CharacterRig decode(String text) {
        if (text == null || !text.startsWith("r1;")) {
            return null;
        }
        String[] parts = text.split(";");
        if (parts.length != 3 + COUNT) {
            return null;
        }
        try {
            CharacterRig r = new CharacterRig();
            r.arms = "1".equals(parts[1]);
            r.hair = "1".equals(parts[2]);
            for (int j = 0; j < COUNT; j++) {
                String[] xy = parts[3 + j].split(",");
                r.x[j] = clamp01(Float.parseFloat(xy[0]));
                r.y[j] = clamp01(Float.parseFloat(xy[1]));
            }
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ---- placing it from the subject's shape ----

    /**
     * A first guess from the subject mask's shape (w x h, 0..1): head at the
     * top, hips lower down (or at the bottom edge for a half-length picture),
     * shoulders at the widest point under the head, arms hanging, hair falling
     * either side of the head. Null if the mask is empty.
     */
    static CharacterRig guess(float[] mask, int w, int h) {
        int top = -1;
        int bottom = -1;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                if (mask[yy * w + xx] > 0.5f) {
                    if (top < 0) {
                        top = yy;
                    }
                    bottom = yy;
                    break;
                }
            }
        }
        if (top < 0 || bottom - top < 4) {
            return null;
        }
        float span = bottom - top;
        boolean halfLength = bottom >= h - 2;
        CharacterRig r = new CharacterRig();
        float headY = top + span * (halfLength ? 0.14f : 0.08f);
        float neckY = top + span * (halfLength ? 0.30f : 0.16f);
        float chestY = top + span * (halfLength ? 0.48f : 0.27f);
        float hipsY = top + span * (halfLength ? 0.95f : 0.52f);
        set(r, HEAD, middle(mask, w, (int) headY), headY, w, h);
        set(r, NECK, middle(mask, w, (int) neckY), neckY, w, h);
        set(r, CHEST, middle(mask, w, (int) chestY), chestY, w, h);
        set(r, HIPS, middle(mask, w, (int) Math.min(h - 1, hipsY)), hipsY, w, h);
        // Shoulders: a little in from the edges of the body just above the chest.
        float shoulderY = neckY + (chestY - neckY) * 0.45f;
        float[] edges = edges(mask, w, (int) shoulderY);
        float cx = r.x[CHEST] * w;
        float half = Math.max(2f, Math.min(cx - edges[0], edges[1] - cx) * 0.8f);
        set(r, L_SHOULDER, cx - half, shoulderY, w, h);
        set(r, R_SHOULDER, cx + half, shoulderY, w, h);
        float arm = span * (halfLength ? 0.28f : 0.17f);
        set(r, L_ELBOW, cx - half * 1.1f, shoulderY + arm, w, h);
        set(r, L_HAND, cx - half * 1.05f, shoulderY + arm * 1.9f, w, h);
        set(r, R_ELBOW, cx + half * 1.1f, shoulderY + arm, w, h);
        set(r, R_HAND, cx + half * 1.05f, shoulderY + arm * 1.9f, w, h);
        // Hair: from the sides of the head, falling down past the shoulders.
        float[] headEdges = edges(mask, w, (int) headY);
        float hx = r.x[HEAD] * w;
        float headHalf = Math.max(2f, Math.min(hx - headEdges[0], headEdges[1] - hx));
        float fall = (chestY - headY) * 0.75f;
        for (int side = 0; side < 2; side++) {
            float dir = side == 0 ? -1f : 1f;
            int first = side == 0 ? L_HAIR1 : R_HAIR1;
            for (int k = 0; k < 3; k++) {
                set(r, first + k, hx + dir * headHalf * (0.75f + 0.15f * k), headY + fall * (0.4f + 0.8f * k), w, h);
            }
        }
        return r;
    }

    private static void set(CharacterRig r, int j, float px, float py, int w, int h) {
        r.x[j] = clamp01(px / Math.max(1, w - 1));
        r.y[j] = clamp01(py / Math.max(1, h - 1));
    }

    /** The middle of the subject along a row (or of the picture if the row is empty). */
    private static float middle(float[] mask, int w, int row) {
        float[] e = edges(mask, w, row);
        return (e[0] + e[1]) / 2f;
    }

    private static float[] edges(float[] mask, int w, int row) {
        int h = mask.length / w;
        row = Math.max(0, Math.min(h - 1, row));
        int left = -1;
        int right = -1;
        for (int xx = 0; xx < w; xx++) {
            if (mask[row * w + xx] > 0.5f) {
                if (left < 0) {
                    left = xx;
                }
                right = xx;
            }
        }
        return left < 0 ? new float[] {0, w - 1} : new float[] {left, right};
    }

    // ---- moving it ----

    /** Joint angles this frame (radians, about each bone's start), and the whole body's bounce. */
    private final float[] angle = new float[COUNT];
    private final float[] hairVel = new float[COUNT];
    private float time;
    private float bounce;
    private float lastHead;

    /**
     * Advances the motion.
     *
     * @param amount 0..1, how much it moves
     */
    void animate(float dt, float bass, float beat, float amount) {
        time += dt;
        float a = amount;
        // A quick dip on each beat that settles.
        bounce += (beat - bounce) * Math.min(1f, dt * 14f);
        angle[HIPS] = a * 0.018f * (float) Math.sin(time * 0.6f);
        angle[CHEST] = a * (0.03f * (float) Math.sin(time * 0.9f) + 0.02f * bass);
        angle[NECK] = a * 0.02f * (float) Math.sin(time * 0.7f + 1f);
        angle[HEAD] = a * (0.11f * bounce + 0.035f * (float) Math.sin(time * 1.3f));
        float lift = 0.12f * bass;
        angle[L_SHOULDER] = a * (0.06f * (float) Math.sin(time * 1.1f) + lift);
        angle[R_SHOULDER] = -a * (0.06f * (float) Math.sin(time * 1.1f + 0.7f) + lift);
        angle[L_ELBOW] = a * 0.1f * (float) Math.sin(time * 1.6f);
        angle[R_ELBOW] = -a * 0.1f * (float) Math.sin(time * 1.6f + 1.2f);
        angle[L_HAND] = a * 0.08f * (float) Math.sin(time * 2.1f);
        angle[R_HAND] = -a * 0.08f * (float) Math.sin(time * 2.1f + 0.5f);
        // Hair lags behind the head, swings, and flutters with the bass.
        float headTurn = angle[HEAD] + angle[NECK] + angle[CHEST];
        float headSpeed = dt > 0f ? (headTurn - lastHead) / dt : 0f;
        lastHead = headTurn;
        for (int j = L_HAIR1; j <= R_HAIR3; j++) {
            int k = (j - L_HAIR1) % 3;
            float target = a * (0.06f * (1f + 1.5f * bass) * (float) Math.sin(time * 1.8f + j)) - 0.08f * headSpeed;
            float accel = 30f * (target - angle[j]) - 5f * hairVel[j];
            hairVel[j] += accel * dt;
            angle[j] = Math.max(-0.5f, Math.min(0.5f, angle[j] + hairVel[j] * dt * (1f + 0.3f * k)));
        }
    }

    // ---- skinning ----

    static final int COLS = 40;
    static final int ROWS = 64;
    private static final int INFLUENCES = 3;

    private int weightsW;
    private int weightsH;
    private int[] boneOf;
    private float[] weightOf;
    private final float[] points = new float[(COLS + 1) * (ROWS + 1) * 2];
    private final float[] verts = new float[(COLS + 1) * (ROWS + 1) * 2];
    /** Each bone's 2x3 transform this frame, in picture pixels. */
    private final float[][] transform = new float[COUNT][6];

    /** Which bones move each mesh point, and how much (worked out once per picture size). */
    private void weigh(int pw, int ph) {
        if (boneOf != null && weightsW == pw && weightsH == ph) {
            return;
        }
        weightsW = pw;
        weightsH = ph;
        int n = (COLS + 1) * (ROWS + 1);
        boneOf = new int[n * INFLUENCES];
        weightOf = new float[n * INFLUENCES];
        float soft = 0.035f * (float) Math.hypot(pw, ph);
        float[] d = new float[COUNT];
        int k = 0;
        for (int j = 0; j <= ROWS; j++) {
            for (int i = 0; i <= COLS; i++, k++) {
                float px = pw * i / (float) COLS;
                float py = ph * j / (float) ROWS;
                for (int b = 0; b < COUNT; b++) {
                    if (!used(b)) {
                        d[b] = Float.MAX_VALUE;
                        continue;
                    }
                    float ax;
                    float ay;
                    float bx = x[b] * pw;
                    float by = y[b] * ph;
                    if (b == HIPS) {
                        // The root holds everything below the hips.
                        ax = bx;
                        ay = by;
                        by = ph * 2f;
                    } else {
                        ax = x[PARENT[b]] * pw;
                        ay = y[PARENT[b]] * ph;
                    }
                    d[b] = segmentDistance(px, py, ax, ay, bx, by);
                }
                float total = 0f;
                for (int slot = 0; slot < INFLUENCES; slot++) {
                    int best = -1;
                    for (int b = 0; b < COUNT; b++) {
                        if (d[b] < Float.MAX_VALUE && (best < 0 || d[b] < d[best])) {
                            best = b;
                        }
                    }
                    if (best < 0) {
                        best = HIPS;
                    }
                    float dd = d[best] == Float.MAX_VALUE ? 1e6f : d[best];
                    float w = 1f / (float) Math.pow(dd * dd + soft * soft, 2);
                    boneOf[k * INFLUENCES + slot] = best;
                    weightOf[k * INFLUENCES + slot] = w;
                    total += w;
                    d[best] = Float.MAX_VALUE;
                }
                for (int slot = 0; slot < INFLUENCES; slot++) {
                    weightOf[k * INFLUENCES + slot] /= total;
                }
            }
        }
    }

    private static float segmentDistance(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax;
        float vy = by - ay;
        float len = vx * vx + vy * vy;
        float t = len > 0f ? Math.max(0f, Math.min(1f, ((px - ax) * vx + (py - ay) * vy) / len)) : 0f;
        float dx = px - (ax + vx * t);
        float dy = py - (ay + vy * t);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Forward kinematics: each bone turns about its start, on top of its parent's motion. */
    private void pose(int pw, int ph) {
        for (int j = 0; j < COUNT; j++) {
            float[] t = transform[j];
            float px;
            float py;
            if (j == HIPS) {
                px = x[HIPS] * pw;
                py = y[HIPS] * ph;
            } else {
                px = x[PARENT[j]] * pw;
                py = y[PARENT[j]] * ph;
            }
            float c = (float) Math.cos(angle[j]);
            float s = (float) Math.sin(angle[j]);
            // Rotation about (px, py).
            float[] own = {c, -s, px - c * px + s * py, s, c, py - s * px - c * py};
            if (j == HIPS) {
                // The whole body dips a little on the beat.
                own[5] += bounce * 0.008f * ph;
                System.arraycopy(own, 0, t, 0, 6);
            } else {
                multiply(transform[PARENT[j]], own, t);
            }
        }
    }

    private static void multiply(float[] a, float[] b, float[] out) {
        float o0 = a[0] * b[0] + a[1] * b[3];
        float o1 = a[0] * b[1] + a[1] * b[4];
        float o2 = a[0] * b[2] + a[1] * b[5] + a[2];
        float o3 = a[3] * b[0] + a[4] * b[3];
        float o4 = a[3] * b[1] + a[4] * b[4];
        float o5 = a[3] * b[2] + a[4] * b[5] + a[5];
        out[0] = o0;
        out[1] = o1;
        out[2] = o2;
        out[3] = o3;
        out[4] = o4;
        out[5] = o5;
    }

    /** Call once per frame (after {@link #animate}) before drawing, with the picture's size. */
    void prepare(int pictureWidth, int pictureHeight) {
        weigh(pictureWidth, pictureHeight);
        pose(pictureWidth, pictureHeight);
    }

    /**
     * Draws {@code bitmap} (covering the whole picture, at any size) bent by the
     * skeleton, placed by {@code matrix} (bitmap pixels to the screen).
     */
    void draw(Canvas canvas, Bitmap bitmap, Matrix matrix, Paint paint) {
        int pw = weightsW;
        int ph = weightsH;
        float sx = bitmap.getWidth() / (float) pw;
        float sy = bitmap.getHeight() / (float) ph;
        int k = 0;
        for (int j = 0; j <= ROWS; j++) {
            for (int i = 0; i <= COLS; i++, k++) {
                float px = pw * i / (float) COLS;
                float py = ph * j / (float) ROWS;
                float ox = 0f;
                float oy = 0f;
                for (int slot = 0; slot < INFLUENCES; slot++) {
                    float w = weightOf[k * INFLUENCES + slot];
                    float[] t = transform[boneOf[k * INFLUENCES + slot]];
                    ox += w * (t[0] * px + t[1] * py + t[2]);
                    oy += w * (t[3] * px + t[4] * py + t[5]);
                }
                points[k * 2] = ox * sx;
                points[k * 2 + 1] = oy * sy;
            }
        }
        matrix.mapPoints(verts, points);
        canvas.drawBitmapMesh(bitmap, COLS, ROWS, verts, 0, null, 0, paint);
    }

    /** Where a joint is drawn this frame, in picture pixels (for tests and the editor). */
    float[] posed(int j, int pw, int ph) {
        float[] t = transform[j];
        float px = x[j] * pw;
        float py = y[j] * ph;
        return new float[] {t[0] * px + t[1] * py + t[2], t[3] * px + t[4] * py + t[5]};
    }
}
