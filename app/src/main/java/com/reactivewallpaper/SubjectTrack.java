package com.reactivewallpaper;

import android.graphics.Bitmap;

/**
 * Where the subject is through a video or GIF: small subject masks taken at
 * even steps through it (found once, in the editor), blended between steps
 * while it plays, so the outline, the light behind the subject and "subject
 * in front" follow it as it moves.
 *
 * <p>Saved as a PNG in the background's subject file, so it travels with the
 * background like a still picture's subject mask: the first row holds a
 * header (a marker, the number of steps, the step length and the mask
 * height), then the masks follow one under the other, gray (255 = subject).
 */
final class SubjectTrack {
    static final int WIDTH = 96;
    private static final int MAGIC = 0x5EC7A0;

    final int count;
    final int intervalMs;
    /** The whole loop's real length (steps are spread evenly over it). */
    private final long loopMs;
    final int width;
    final int height;
    /** Per step: the mask, its soft glow and its outline, 0..255. */
    private final byte[][] masks;
    private final byte[][] glows;
    private final byte[][] rims;

    private SubjectTrack(int count, int intervalMs, long loopMs, int width, int height, byte[][] masks) {
        this.count = count;
        this.intervalMs = intervalMs;
        this.loopMs = loopMs > 0 ? loopMs : (long) count * intervalMs;
        this.width = width;
        this.height = height;
        this.masks = masks;
        glows = new byte[count][];
        rims = new byte[count][];
        for (int i = 0; i < count; i++) {
            float[] m = toFloats(masks[i]);
            glows[i] = toBytes(SubjectLayer.blur(m, width, height, 3, 3));
            rims[i] = toBytes(SubjectLayer.rim(m, width, height));
        }
    }

    long durationMs() {
        return loopMs;
    }

    /** Reads a saved track from a background's subject file, or null if it holds none. */
    static SubjectTrack read(java.io.File file) {
        if (file == null || !file.isFile() || file.length() == 0) {
            return null;
        }
        Bitmap sheet = android.graphics.BitmapFactory.decodeFile(file.getPath());
        if (sheet == null) {
            return null;
        }
        try {
            return load(sheet);
        } finally {
            sheet.recycle();
        }
    }

    /** True if the bitmap is a saved track (rather than a still picture's subject mask). */
    static boolean isTrack(Bitmap sheet) {
        return sheet != null && sheet.getWidth() == WIDTH && sheet.getHeight() > 1
                && (sheet.getPixel(0, 0) & 0xFFFFFF) == MAGIC;
    }

    /** Reads a saved track, or null if the bitmap isn't one. */
    static SubjectTrack load(Bitmap sheet) {
        if (!isTrack(sheet)) {
            return null;
        }
        int count = sheet.getPixel(1, 0) & 0xFFFFFF;
        int interval = sheet.getPixel(2, 0) & 0xFFFFFF;
        int h = sheet.getPixel(3, 0) & 0xFFFFFF;
        long loop = sheet.getPixel(4, 0) & 0xFFFFFF;
        if (count <= 0 || interval <= 0 || h <= 0 || 1 + count * h > sheet.getHeight()) {
            return null;
        }
        byte[][] masks = new byte[count][];
        int[] row = new int[WIDTH * h];
        for (int i = 0; i < count; i++) {
            sheet.getPixels(row, 0, WIDTH, 0, 1 + i * h, WIDTH, h);
            byte[] m = new byte[WIDTH * h];
            for (int k = 0; k < row.length; k++) {
                m[k] = (byte) (row[k] & 0xFF);
            }
            masks[i] = m;
        }
        return new SubjectTrack(count, interval, loop, WIDTH, h, masks);
    }

    /** Packs masks (each WIDTH x height, 0..255) into a sheet to save as PNG. */
    static Bitmap save(java.util.List<byte[]> masks, int intervalMs, long loopMs, int height) {
        int count = masks.size();
        Bitmap sheet = Bitmap.createBitmap(WIDTH, 1 + count * height, Bitmap.Config.ARGB_8888);
        int[] header = new int[WIDTH];
        header[0] = 0xFF000000 | MAGIC;
        header[1] = 0xFF000000 | count;
        header[2] = 0xFF000000 | intervalMs;
        header[3] = 0xFF000000 | height;
        header[4] = 0xFF000000 | (int) Math.min(0xFFFFFF, loopMs);
        for (int i = 5; i < WIDTH; i++) {
            header[i] = 0xFF000000;
        }
        sheet.setPixels(header, 0, WIDTH, 0, 0, WIDTH, 1);
        int[] row = new int[WIDTH * height];
        for (int i = 0; i < count; i++) {
            byte[] m = masks.get(i);
            for (int k = 0; k < row.length; k++) {
                int v = m[k] & 0xFF;
                row[k] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            sheet.setPixels(row, 0, WIDTH, 0, 1 + i * height, WIDTH, height);
        }
        return sheet;
    }

    static final int MASK = 0;
    static final int GLOW = 1;
    static final int RIM = 2;

    /**
     * Fills {@code out} (WIDTH x height ARGB, white with the value as alpha) with
     * the mask, glow or outline at {@code timeMs} into the loop, blended between steps.
     */
    void fill(int which, long timeMs, int[] out) {
        byte[][] set = which == GLOW ? glows : which == RIM ? rims : masks;
        long t = Math.floorMod(timeMs, durationMs());
        float step = t * count / (float) durationMs();
        int a = Math.min(count - 1, (int) step);
        int b = (a + 1) % count;
        float f = step - a;
        byte[] ma = set[a];
        byte[] mb = set[b];
        for (int k = 0; k < out.length; k++) {
            float v = (ma[k] & 0xFF) * (1 - f) + (mb[k] & 0xFF) * f;
            out[k] = ((int) v << 24) | 0xFFFFFF;
        }
    }

    private static float[] toFloats(byte[] b) {
        float[] f = new float[b.length];
        for (int i = 0; i < b.length; i++) {
            f[i] = (b[i] & 0xFF) / 255f;
        }
        return f;
    }

    private static byte[] toBytes(float[] f) {
        byte[] b = new byte[f.length];
        for (int i = 0; i < f.length; i++) {
            b[i] = (byte) Math.round(Math.max(0f, Math.min(1f, f[i])) * 255f);
        }
        return b;
    }
}
