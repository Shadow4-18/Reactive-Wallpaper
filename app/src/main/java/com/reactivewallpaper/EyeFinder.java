package com.reactivewallpaper;

/**
 * Finds a face's eyes in the picture, near the skeleton's head dot: the skin
 * color is read from the cheeks, the face is the skin around it, and the eyes
 * are the two shapes inside the face's upper part that aren't skin, side by
 * side. Works on drawn and photographed faces when the face is fairly
 * frontal; when it can't find a clear pair, the rig keeps its guess.
 */
final class EyeFinder {
    private EyeFinder() {}

    /**
     * @param px the picture's pixels (ARGB), {@code w} x {@code h}
     * @return true if the rig's eyes were moved onto the eyes found
     */
    static boolean find(int[] px, int w, int h, CharacterRig rig) {
        float head = 2f * Math.abs(rig.y[CharacterRig.NECK] - rig.y[CharacterRig.HEAD]) * h;
        if (head < 12f) {
            return false;
        }
        int hx = Math.round(rig.x[CharacterRig.HEAD] * w);
        int hy = Math.round(rig.y[CharacterRig.HEAD] * h);
        int x0 = clamp(Math.round(hx - 0.75f * head), w);
        int x1 = clamp(Math.round(hx + 0.75f * head), w);
        int y0 = clamp(Math.round(hy - 0.7f * head), h);
        int y1 = clamp(Math.round(hy + 0.6f * head), h);
        int rw = x1 - x0 + 1;
        int rh = y1 - y0 + 1;
        if (rw < 8 || rh < 8) {
            return false;
        }

        // The skin: the typical color just below the eyes (cheeks and nose).
        int[] skin = null;
        int seedY = 0;
        for (float drop : new float[] {0.22f, 0.32f, 0.12f}) {
            seedY = clamp(Math.round(hy + drop * head), h);
            skin = median(px, w, h, hx, seedY, Math.max(2, Math.round(0.15f * head)),
                    Math.max(1, Math.round(0.06f * head)));
            if (skin != null && luma(skin) > 90) {
                break;
            }
            skin = null;
        }
        if (skin == null) {
            return false;
        }

        // The face, row by row: where there's skin near the head's middle line. (Rows, not one
        // connected patch, so a strand of hair across the face doesn't cut it in two.)
        boolean[] isSkin = new boolean[rw * rh];
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                isSkin[y * rw + x] = near(px[(y0 + y) * w + x0 + x], skin);
            }
        }
        int half = Math.max(2, Math.round(0.45f * head));
        int[] left = new int[rh];
        int[] right = new int[rh];
        boolean[] faceRow = new boolean[rh];
        for (int y = 0; y < rh; y++) {
            left[y] = -1;
            right[y] = -1;
            int count = 0;
            for (int x = Math.max(0, hx - x0 - half); x <= Math.min(rw - 1, hx - x0 + half); x++) {
                if (isSkin[y * rw + x]) {
                    if (left[y] < 0) {
                        left[y] = x;
                    }
                    right[y] = x;
                    count++;
                }
            }
            faceRow[y] = count >= 0.12f * (2 * half + 1);
        }
        int seedRow = seedY - y0;
        if (seedRow < 0 || seedRow >= rh || !faceRow[seedRow]) {
            return false;
        }
        // The face's rows: the run of face rows through the cheeks, allowing small gaps (eyes, lines).
        int top = seedRow;
        int gap = 0;
        for (int y = seedRow - 1; y >= 0; y--) {
            if (faceRow[y]) {
                top = y;
                gap = 0;
            } else if (++gap > 0.12f * head) {
                break;
            }
        }
        int bottom = seedRow;
        gap = 0;
        for (int y = seedRow + 1; y < rh; y++) {
            if (faceRow[y]) {
                bottom = y;
                gap = 0;
            } else if (++gap > 0.12f * head) {
                break;
            }
        }
        int faceHeight = bottom - top + 1;
        if (faceHeight < 0.2f * head) {
            return false;
        }

        // Eye candidates: not skin, with skin on both sides along the row, in the face's upper part.
        boolean[] cand = new boolean[rw * rh];
        int lastRow = top + Math.round(0.7f * faceHeight);
        for (int y = top; y <= Math.min(rh - 1, lastRow); y++) {
            if (left[y] < 0) {
                continue;
            }
            for (int x = left[y] + 1; x < right[y]; x++) {
                cand[y * rw + x] = !isSkin[y * rw + x];
            }
        }
        int[] queue = new int[rw * rh];
        int head0;
        int tail;
        // Its shapes.
        int[] label = new int[rw * rh];
        java.util.Arrays.fill(label, -1);
        java.util.List<int[]> boxes = new java.util.ArrayList<>(); // minX, minY, maxX, maxY, area
        for (int s = 0; s < cand.length; s++) {
            if (!cand[s] || label[s] >= 0) {
                continue;
            }
            int id = boxes.size();
            int[] box = {rw, rh, -1, -1, 0};
            head0 = 0;
            tail = 0;
            queue[tail++] = s;
            label[s] = id;
            while (head0 < tail) {
                int i = queue[head0++];
                int x = i % rw;
                int y = i / rw;
                box[0] = Math.min(box[0], x);
                box[1] = Math.min(box[1], y);
                box[2] = Math.max(box[2], x);
                box[3] = Math.max(box[3], y);
                box[4]++;
                int[] next = {x > 0 ? i - 1 : -1, x < rw - 1 ? i + 1 : -1, y > 0 ? i - rw : -1,
                        y < rh - 1 ? i + rw : -1};
                for (int j : next) {
                    if (j >= 0 && label[j] < 0 && cand[j]) {
                        label[j] = id;
                        queue[tail++] = j;
                    }
                }
            }
            boxes.add(box);
        }

        // The best pair: similar height, side by side at a face's eye spacing, not hair-thin.
        float minArea = 0.0015f * head * head;
        float maxArea = 0.05f * head * head;
        int best0 = -1;
        int best1 = -1;
        float bestScore = 0f;
        for (int a = 0; a < boxes.size(); a++) {
            int[] A = boxes.get(a);
            if (!eyeShaped(A, minArea, maxArea) || A[1] <= top) {
                continue;
            }
            for (int b = 0; b < boxes.size(); b++) {
                int[] B = boxes.get(b);
                // Shapes touching the face's top edge are hair hanging over it (bangs), not eyes.
                if (a == b || !eyeShaped(B, minArea, maxArea) || B[1] <= top) {
                    continue;
                }
                float ax = (A[0] + A[2]) / 2f;
                float bx = (B[0] + B[2]) / 2f;
                if (ax >= bx) {
                    continue;
                }
                float ay = (A[1] + A[3]) / 2f;
                float by = (B[1] + B[3]) / 2f;
                float dx = bx - ax;
                if (Math.abs(ay - by) > 0.15f * head || dx < 0.18f * head || dx > 0.95f * head) {
                    continue;
                }
                float size = Math.min(A[4], B[4]) / (float) Math.max(A[4], B[4]);
                float score = (A[4] + B[4]) * size;
                if (score > bestScore) {
                    bestScore = score;
                    best0 = a;
                    best1 = b;
                }
            }
        }
        if (best0 < 0) {
            return false;
        }
        int[][] eyes = {boxes.get(best0).clone(), boxes.get(best1).clone()};
        // Eyes are about the same size: one that ran into hair beside it is trimmed from its outer side.
        int w0 = eyes[0][2] - eyes[0][0] + 1;
        int w1 = eyes[1][2] - eyes[1][0] + 1;
        if (w0 > 1.4f * w1) {
            eyes[0][0] = eyes[0][2] - w1 + 1;
        } else if (w1 > 1.4f * w0) {
            eyes[1][2] = eyes[1][0] + w0 - 1;
        }
        for (int side = 0; side < 2; side++) {
            int[] e = eyes[side];
            float cx = x0 + (e[0] + e[2]) / 2f + 0.5f;
            float cy = y0 + (e[1] + e[3]) / 2f + 0.5f;
            rig.eyeX[side] = cx / w;
            rig.eyeY[side] = cy / h;
            rig.eyeW[side] = CharacterRig.clampEye((e[2] - e[0] + 1) * 1.15f / w);
            rig.eyeH[side] = CharacterRig.clampEye((e[3] - e[1] + 1) * 1.4f / w);
        }
        // Lashes and lids make both eyes about as tall as the taller one looks.
        float tall = Math.max(rig.eyeH[0], rig.eyeH[1]);
        rig.eyeH[0] = tall;
        rig.eyeH[1] = tall;
        return true;
    }

    private static boolean eyeShaped(int[] box, float minArea, float maxArea) {
        int bw = box[2] - box[0] + 1;
        int bh = box[3] - box[1] + 1;
        // Solid enough (not a thin line or strand), and not taller than it is wide by much.
        return box[4] >= minArea && box[4] <= maxArea && box[4] >= 0.3f * bw * bh && bh <= bw * 1.6f && bw <= bh * 5f;
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    private static int luma(int[] c) {
        return (299 * c[0] + 587 * c[1] + 114 * c[2]) / 1000;
    }

    /** Close enough to the skin color: by hue and saturation, allowing for light and shade. */
    private static boolean near(int c, int[] skin) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int l = (299 * r + 587 * g + 114 * b) / 1000;
        int sl = luma(skin);
        if (l < sl * 0.62f || l > Math.min(255, sl * 1.3f + 12)) {
            return false;
        }
        float sum = r + g + b + 1f;
        float ssum = skin[0] + skin[1] + skin[2] + 1f;
        float dr = r / sum - skin[0] / ssum;
        float dg = g / sum - skin[1] / ssum;
        return dr * dr + dg * dg < 0.0016f;
    }

    /** The median color of a box (null if the box is outside the picture). */
    private static int[] median(int[] px, int w, int h, int cx, int cy, int halfW, int halfH) {
        java.util.List<Integer> r = new java.util.ArrayList<>();
        java.util.List<Integer> g = new java.util.ArrayList<>();
        java.util.List<Integer> b = new java.util.ArrayList<>();
        for (int y = Math.max(0, cy - halfH); y <= Math.min(h - 1, cy + halfH); y++) {
            for (int x = Math.max(0, cx - halfW); x <= Math.min(w - 1, cx + halfW); x++) {
                int c = px[y * w + x];
                r.add((c >> 16) & 0xFF);
                g.add((c >> 8) & 0xFF);
                b.add(c & 0xFF);
            }
        }
        if (r.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(r);
        java.util.Collections.sort(g);
        java.util.Collections.sort(b);
        int m = r.size() / 2;
        return new int[] {r.get(m), g.get(m), b.get(m)};
    }
}
