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
    /** Eye centers on the picture (left, right), 0..1, and their size as a fraction of its width. */
    final float[] eyeX = new float[2];
    final float[] eyeY = new float[2];
    float eyeSize = 0.03f;
    boolean blink = true;

    boolean used(int j) {
        return !(isArm(j) && !arms) && !(isHair(j) && !hair);
    }

    CharacterRig copy() {
        CharacterRig r = new CharacterRig();
        System.arraycopy(x, 0, r.x, 0, COUNT);
        System.arraycopy(y, 0, r.y, 0, COUNT);
        r.arms = arms;
        r.hair = hair;
        System.arraycopy(eyeX, 0, r.eyeX, 0, 2);
        System.arraycopy(eyeY, 0, r.eyeY, 0, 2);
        r.eyeSize = eyeSize;
        r.blink = blink;
        return r;
    }

    /** Eyes where they usually are on a face: level with the head dot, either side. */
    void guessEyes(int pictureWidth, int pictureHeight) {
        float head = Math.abs(y[NECK] - y[HEAD]) * pictureHeight;
        // (Proportions of a drawn face, which has big eyes; the editor's dots fix any picture.)
        float ex = 0.36f * head / pictureWidth;
        eyeSize = Math.max(0.01f, Math.min(0.08f, 0.36f * head / pictureWidth));
        for (int side = 0; side < 2; side++) {
            eyeX[side] = clamp01(x[HEAD] + (side == 0 ? -ex : ex));
            eyeY[side] = clamp01(y[HEAD]);
        }
    }

    // ---- saving ----

    String encode() {
        StringBuilder b = new StringBuilder("r2;").append(arms ? 1 : 0).append(';').append(hair ? 1 : 0)
                .append(';').append(blink ? 1 : 0).append(';').append(String.format(Locale.US, "%.4f", eyeSize));
        for (int j = 0; j < COUNT; j++) {
            b.append(';').append(String.format(Locale.US, "%.4f,%.4f", x[j], y[j]));
        }
        for (int side = 0; side < 2; side++) {
            b.append(';').append(String.format(Locale.US, "%.4f,%.4f", eyeX[side], eyeY[side]));
        }
        return b.toString();
    }

    /** The rig saved with {@link #encode}, or null for none (or anything unreadable). */
    static CharacterRig decode(String text) {
        if (text == null) {
            return null;
        }
        boolean v2 = text.startsWith("r2;");
        if (!v2 && !text.startsWith("r1;")) {
            return null;
        }
        String[] parts = text.split(";");
        int first = v2 ? 5 : 3;
        if (parts.length != first + COUNT + (v2 ? 2 : 0)) {
            return null;
        }
        try {
            CharacterRig r = new CharacterRig();
            r.arms = "1".equals(parts[1]);
            r.hair = "1".equals(parts[2]);
            for (int j = 0; j < COUNT; j++) {
                String[] xy = parts[first + j].split(",");
                r.x[j] = clamp01(Float.parseFloat(xy[0]));
                r.y[j] = clamp01(Float.parseFloat(xy[1]));
            }
            if (v2) {
                r.blink = "1".equals(parts[3]);
                r.eyeSize = Math.max(0.005f, Math.min(0.1f, Float.parseFloat(parts[4])));
                for (int side = 0; side < 2; side++) {
                    String[] xy = parts[first + COUNT + side].split(",");
                    r.eyeX[side] = clamp01(Float.parseFloat(xy[0]));
                    r.eyeY[side] = clamp01(Float.parseFloat(xy[1]));
                }
            } else {
                // Saved before eyes existed: guess them, but don't start blinking unasked.
                r.guessEyes(1000, 1000);
                r.blink = false;
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
     * A first guess from the subject mask's shape (w x h, 0..1), by ordinary
     * figure proportions: the figure's height in head-heights tells where
     * neck, shoulders, chest, hips, elbows and hands fall; a picture cut off at
     * the bottom is taken as half-length. Hair falls either side of the head,
     * on the subject. Null if the mask is empty.
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
        // A whole figure is about 7 heads tall; a picture cut at the waist or hips shows about 3.3.
        float head = span / (halfLength ? 3.3f : 7.2f);
        CharacterRig r = new CharacterRig();
        float headY = top + 0.5f * head;
        float cx = middle(mask, w, Math.round(headY + 0.6f * head));
        set(r, HEAD, middle(mask, w, Math.round(headY)), headY, w, h);
        set(r, NECK, cx, top + 1.0f * head, w, h);
        set(r, CHEST, cx, top + 1.8f * head, w, h);
        set(r, HIPS, cx, Math.min(h - 1f, top + 3.0f * head), w, h);
        float[][] side = {
                {L_SHOULDER, 1.3f, 0.55f}, {L_ELBOW, 2.4f, 0.75f}, {L_HAND, 3.5f, 0.95f},
                {R_SHOULDER, 1.3f, 0.55f}, {R_ELBOW, 2.4f, 0.75f}, {R_HAND, 3.5f, 0.95f},
                {L_HAIR1, 1.0f, 0.7f}, {L_HAIR2, 1.8f, 0.85f}, {L_HAIR3, 2.7f, 0.9f},
                {R_HAIR1, 1.0f, 0.7f}, {R_HAIR2, 1.8f, 0.85f}, {R_HAIR3, 2.7f, 0.9f},
        };
        for (float[] s : side) {
            int j = (int) s[0];
            boolean left = j == L_SHOULDER || j == L_ELBOW || j == L_HAND || (j >= L_HAIR1 && j <= L_HAIR3);
            float py = Math.min(h - 1f, top + s[1] * head);
            float px = cx + (left ? -1f : 1f) * s[2] * head;
            set(r, j, onSubject(mask, w, h, px, py, cx), py, w, h);
        }
        return r;
    }

    /** Moves a point toward the middle until it's on the subject (so dots don't start in empty space). */
    private static float onSubject(float[] mask, int w, int h, float px, float py, float cx) {
        int row = Math.max(0, Math.min(h - 1, Math.round(py)));
        float x = px;
        for (int step = 0; step < w; step++) {
            int col = Math.max(0, Math.min(w - 1, Math.round(x)));
            if (mask[row * w + col] > 0.5f || Math.abs(x - cx) < 1f) {
                return x;
            }
            x += x < cx ? 1f : -1f;
        }
        return px;
    }

    /** The middle of the subject along a row (or of the picture if the row is empty). */
    private static float middle(float[] mask, int w, int row) {
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
        return left < 0 ? (w - 1) / 2f : (left + right) / 2f;
    }

    private static void set(CharacterRig r, int j, float px, float py, int w, int h) {
        r.x[j] = clamp01(px / Math.max(1, w - 1));
        r.y[j] = clamp01(py / Math.max(1, h - 1));
    }

    // ---- moving it ----

    /** Joint angles this frame (radians, about each bone's start), and the whole body's bounce. */
    private final float[] angle = new float[COUNT];
    private final float[] hairVel = new float[COUNT];
    private float time;
    private float bounce;
    private float groove;
    private float lastHead;
    private float blinkIn = 2.5f;
    private float blinkAge = -1f;
    private boolean doubleBlink;
    /** 0 = eyes open .. 1 = shut. */
    private float closed;
    private final java.util.Random random = new java.util.Random();

    /**
     * Advances the motion: a gentle, whole-body sway that never bends far at
     * any one joint (so nothing stretches), a smooth bob in time with the song
     * (on the learned tempo when there is one), hair that follows the head,
     * and now and then a blink.
     *
     * @param phase  0..1 through the current beat of the learned tempo, or -1 if it isn't known
     * @param amount 0..1, how much it moves
     */
    void animate(float dt, float bass, float beat, float phase, float amount) {
        time += dt;
        // At full amount this is still a light move: big angles stretch a flat picture.
        float a = 0.65f * amount;
        // The groove: smoothed bass (rises quickly, falls slowly).
        groove += (bass - groove) * Math.min(1f, dt * (bass > groove ? 8f : 2f));
        float target;
        if (phase >= 0f) {
            // On the tempo: down on the beat, up between, as a smooth wave.
            target = 0.5f + 0.5f * (float) Math.cos(2 * Math.PI * phase);
            target *= Math.min(1f, 0.35f + groove);
        } else {
            target = beat;
        }
        bounce += (target - bounce) * Math.min(1f, dt * 9f);
        angle[HIPS] = 0f;
        angle[CHEST] = a * (0.018f * (float) Math.sin(time * 0.55f) + 0.012f * groove);
        angle[NECK] = a * 0.012f * (float) Math.sin(time * 0.43f + 1f);
        angle[HEAD] = a * (0.03f * (float) Math.sin(time * 0.7f + 0.4f) + 0.035f * bounce);
        angle[L_SHOULDER] = a * 0.025f * (float) Math.sin(time * 0.8f);
        angle[R_SHOULDER] = -a * 0.025f * (float) Math.sin(time * 0.8f + 0.9f);
        angle[L_ELBOW] = a * 0.03f * (float) Math.sin(time * 1.1f + 0.3f);
        angle[R_ELBOW] = -a * 0.03f * (float) Math.sin(time * 1.1f + 1.5f);
        angle[L_HAND] = 0f;
        angle[R_HAND] = 0f;
        // Hair lags behind the head and swings a little on its own.
        float headTurn = angle[HEAD] + angle[NECK] + angle[CHEST];
        float headSpeed = dt > 0f ? (headTurn - lastHead) / dt : 0f;
        lastHead = headTurn;
        for (int j = L_HAIR1; j <= R_HAIR3; j++) {
            float target2 = a * 0.045f * (1f + 0.6f * groove) * (float) Math.sin(time * 1.2f + j) - 0.12f * headSpeed;
            float accel = 22f * (target2 - angle[j]) - 4.5f * hairVel[j];
            hairVel[j] += accel * dt;
            angle[j] = Math.max(-0.25f, Math.min(0.25f, angle[j] + hairVel[j] * dt));
        }
        animateBlink(dt);
    }

    /** Blinks every 2 to 6 seconds, sometimes twice: shuts fast, opens a little slower. */
    private void animateBlink(float dt) {
        if (!blink) {
            closed = 0f;
            return;
        }
        if (blinkAge < 0f) {
            blinkIn -= dt;
            if (blinkIn <= 0f) {
                blinkAge = 0f;
                doubleBlink = random.nextFloat() < 0.2f;
            }
            closed = 0f;
            return;
        }
        blinkAge += dt;
        float shut = 0.07f;
        float open = 0.13f;
        if (blinkAge < shut) {
            closed = blinkAge / shut;
        } else if (blinkAge < shut + 0.04f) {
            closed = 1f;
        } else if (blinkAge < shut + 0.04f + open) {
            closed = 1f - (blinkAge - shut - 0.04f) / open;
        } else {
            closed = 0f;
            blinkAge = -1f;
            if (doubleBlink) {
                doubleBlink = false;
                blinkIn = 0.12f;
            } else {
                blinkIn = 2f + random.nextFloat() * 4f;
            }
        }
    }

    /** How shut the eyes are now, 0..1 (for tests). */
    float eyesClosed() {
        return closed;
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
        // Wide, soft blending between bones, so a bend spreads out instead of kinking.
        float soft = 0.06f * (float) Math.hypot(pw, ph);
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
                // The whole body dips a little with the bob.
                own[5] += bounce * 0.004f * ph;
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

    private static final int EYE_MESH = 12;
    private final java.util.WeakHashMap<Bitmap, Bitmap[]> eyePatches = new java.util.WeakHashMap<>();
    private final android.graphics.Rect[] eyeRects = {new android.graphics.Rect(), new android.graphics.Rect()};
    private final float[] eyePoints = new float[(EYE_MESH + 1) * (EYE_MESH + 1) * 2];
    private final float[] eyeVerts = new float[(EYE_MESH + 1) * (EYE_MESH + 1) * 2];
    private final int[] near = new int[INFLUENCES];
    private final float[] nearW = new float[INFLUENCES];
    private final float[] dist = new float[COUNT];

    /**
     * Draws the blink over {@code bitmap} as drawn by {@link #draw}: around each
     * eye a small patch of the picture is squeezed toward the lid line, so the
     * eyelid slides down over the eye and the lashes close to a line. Nothing
     * is drawn while the eyes are open.
     */
    void drawEyes(Canvas canvas, Bitmap bitmap, Matrix matrix, Paint paint) {
        if (!blink || closed <= 0.01f || weightOf == null) {
            return;
        }
        int pw = weightsW;
        int ph = weightsH;
        float sx = bitmap.getWidth() / (float) pw;
        float sy = bitmap.getHeight() / (float) ph;
        Bitmap[] patches = eyePatches.get(bitmap);
        if (patches == null) {
            patches = new Bitmap[2];
            for (int side = 0; side < 2; side++) {
                float hw = eyeSize * pw / 2f;
                float rx = hw * 1.7f;
                float ry = hw * 1.5f;
                android.graphics.Rect r = eyeRects[side];
                r.set(Math.max(0, Math.round((eyeX[side] * pw - rx) * sx)),
                        Math.max(0, Math.round((eyeY[side] * ph - ry) * sy)),
                        Math.min(bitmap.getWidth(), Math.round((eyeX[side] * pw + rx) * sx)),
                        Math.min(bitmap.getHeight(), Math.round((eyeY[side] * ph + ry) * sy)));
                if (r.width() > 2 && r.height() > 2) {
                    patches[side] = Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height());
                }
            }
            eyePatches.put(bitmap, patches);
        }
        for (int side = 0; side < 2; side++) {
            Bitmap patch = patches[side];
            if (patch == null) {
                continue;
            }
            android.graphics.Rect r = eyeRects[side];
            float ex = eyeX[side] * pw;
            float ey = eyeY[side] * ph;
            float hh = eyeSize * pw / 2f * 0.7f;
            // The lids meet a little below the eye's middle, as upper lids do most of the work.
            float lid = ey + 0.3f * hh;
            float left = r.left / sx;
            float top = r.top / sy;
            float right = r.right / sx;
            float bottom = r.bottom / sy;
            int k = 0;
            for (int j = 0; j <= EYE_MESH; j++) {
                for (int i = 0; i <= EYE_MESH; i++, k++) {
                    float px = left + (right - left) * i / EYE_MESH;
                    float py = top + (bottom - top) * j / EYE_MESH;
                    // 1 at the eye's middle, 0 at the patch's edges (which stay put).
                    float u = (px - ex) / ((right - left) / 2f);
                    float v = (py - ey) / ((bottom - top) / 2f);
                    float f = Math.max(0f, 1f - u * u) * Math.max(0f, 1f - v * v);
                    float qy = lid + (py - lid) * (1f - closed * f);
                    skinAt(px, py, pw, ph);
                    float ox = 0f;
                    float oy = 0f;
                    for (int slot = 0; slot < INFLUENCES; slot++) {
                        float[] tr = transform[near[slot]];
                        ox += nearW[slot] * (tr[0] * px + tr[1] * qy + tr[2]);
                        oy += nearW[slot] * (tr[3] * px + tr[4] * qy + tr[5]);
                    }
                    eyePoints[k * 2] = ox * sx;
                    eyePoints[k * 2 + 1] = oy * sy;
                }
            }
            matrix.mapPoints(eyeVerts, eyePoints);
            canvas.drawBitmapMesh(patch, EYE_MESH, EYE_MESH, eyeVerts, 0, null, 0, paint);
        }
    }

    /** The bones moving a point, as for the mesh (into {@link #near} and {@link #nearW}). */
    private void skinAt(float px, float py, int pw, int ph) {
        float soft = 0.06f * (float) Math.hypot(pw, ph);
        for (int b = 0; b < COUNT; b++) {
            if (!used(b)) {
                dist[b] = Float.MAX_VALUE;
                continue;
            }
            float bx = x[b] * pw;
            float by = y[b] * ph;
            float ax;
            float ay;
            if (b == HIPS) {
                ax = bx;
                ay = by;
                by = ph * 2f;
            } else {
                ax = x[PARENT[b]] * pw;
                ay = y[PARENT[b]] * ph;
            }
            dist[b] = segmentDistance(px, py, ax, ay, bx, by);
        }
        float total = 0f;
        for (int slot = 0; slot < INFLUENCES; slot++) {
            int best = HIPS;
            float bestD = Float.MAX_VALUE;
            for (int b = 0; b < COUNT; b++) {
                if (dist[b] < bestD) {
                    bestD = dist[b];
                    best = b;
                }
            }
            float dd = bestD == Float.MAX_VALUE ? 1e6f : bestD;
            near[slot] = best;
            nearW[slot] = 1f / (float) Math.pow(dd * dd + soft * soft, 2);
            total += nearW[slot];
            dist[best] = Float.MAX_VALUE;
        }
        for (int slot = 0; slot < INFLUENCES; slot++) {
            nearW[slot] /= total;
        }
    }

    /** Where a joint is drawn this frame, in picture pixels (for tests and the editor). */
    float[] posed(int j, int pw, int ph) {
        float[] t = transform[j];
        float px = x[j] * pw;
        float py = y[j] * ph;
        return new float[] {t[0] * px + t[1] * py + t[2], t[3] * px + t[4] * py + t[5]};
    }
}
