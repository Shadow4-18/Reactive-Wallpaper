package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * The picture's subjects (people, characters, objects) cut out so they can be
 * drawn in front of the effects. Built from a painted or detected subject
 * mask, any size (it is stretched over the image): a gray mask is one subject
 * (255 = subject); a colored one holds up to three, one in each of its red,
 * green and blue channels, each drawn as its own layer with its own depth.
 *
 * <p>Each part has a cutout (the image with that subject opaque and the rest
 * clear) and an aura (a small, very soft silhouette for a light behind it).
 * One plate, the image with every subject filled in from its surroundings,
 * is drawn behind when they move with their own depth, so no ghost shows.
 */
final class SubjectLayer {
    /** The long side the mask is worked on at; plenty for soft edges. */
    private static final int WORK_SIDE = 384;
    private static final int AURA_SIDE = 72;
    static final int MAX_PARTS = 3;

    static final class Part {
        final Bitmap cutout;
        /** White with the silhouette as alpha; tinted when drawn. */
        final Bitmap aura;
        /** White with the outline as alpha (a bright line with a soft halo); tinted when drawn. */
        final Bitmap rim;
        /** The subject's center, as a fraction of the image (for scaling it about itself). */
        final float centerX;
        final float centerY;
        /** How much of the depth effect this subject gets: nearer subjects move more. */
        float depth = 1f;

        Part(Bitmap cutout, Bitmap aura, Bitmap rim, float centerX, float centerY) {
            this.cutout = cutout;
            this.aura = aura;
            this.rim = rim;
            this.centerX = centerX;
            this.centerY = centerY;
        }
    }

    /** Back to front: draw in this order. */
    final Part[] parts;
    final Bitmap plate;

    private SubjectLayer(Part[] parts, Bitmap plate) {
        this.parts = parts;
        this.plate = plate;
    }

    /** Builds the layers, or returns null when the mask marks (almost) nothing. */
    static SubjectLayer build(Bitmap image, Bitmap subjectMask) {
        if (image == null || subjectMask == null || image.isRecycled() || subjectMask.isRecycled()) {
            return null;
        }
        float longSide = Math.max(image.getWidth(), image.getHeight());
        int mw = Math.max(1, Math.round(image.getWidth() * WORK_SIDE / longSide));
        int mh = Math.max(1, Math.round(image.getHeight() * WORK_SIDE / longSide));
        java.util.List<float[]> grids = readMasks(subjectMask, mw, mh);
        java.util.List<Part> parts = new java.util.ArrayList<>();
        java.util.List<Float> bottoms = new java.util.ArrayList<>();
        float[] union = new float[mw * mh];
        for (float[] m : grids) {
            // Where is it, and is there anything there at all?
            double sum = 0;
            double sx = 0;
            double sy = 0;
            int lowest = 0;
            for (int y = 0; y < mh; y++) {
                for (int x = 0; x < mw; x++) {
                    float v = m[y * mw + x];
                    sum += v;
                    sx += v * x;
                    sy += v * y;
                    if (v > 0.5f) {
                        lowest = y;
                    }
                }
            }
            if (sum < mw * mh * 0.002) {
                continue;
            }
            for (int i = 0; i < union.length; i++) {
                union[i] = Math.max(union[i], m[i]);
            }
            float cx = (float) (sx / sum + 0.5) / mw;
            float cy = (float) (sy / sum + 0.5) / mh;
            // A one-pixel feather keeps the cut edge from looking jagged once scaled up.
            float[] edge = blur(m, mw, mh, 1, 1);
            Bitmap cutout = image.copy(Bitmap.Config.ARGB_8888, true);
            multiplyAlpha(cutout, edge, mw, mh);
            float[] soft = blur(scale(m, mw, mh, AURA_SIDE), auraW(mw, mh), auraH(mw, mh), 3, 3);
            Bitmap aura = alphaBitmap(soft, auraW(mw, mh), auraH(mw, mh));
            Bitmap rim = alphaBitmap(rim(m, mw, mh), mw, mh);
            parts.add(new Part(cutout, aura, rim, cx, cy));
            bottoms.add(lowest / (float) mh);
        }
        if (parts.isEmpty()) {
            return null;
        }
        // Whoever stands lower in the picture is nearer: drawn last and moved most.
        Integer[] order = new Integer[parts.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Float.compare(bottoms.get(x), bottoms.get(y)));
        Part[] sorted = new Part[order.length];
        for (int rank = 0; rank < order.length; rank++) {
            Part p = parts.get(order[rank]);
            p.depth = order.length == 1 ? 1f : 0.35f + 0.65f * rank / (order.length - 1);
            sorted[rank] = p;
        }
        // The plate: fill every subject (grown a little, to swallow its outline) with
        // the surrounding colors, spread inward by a normalized blur.
        float[] grown = blur(dilate(union, mw, mh, 4), mw, mh, 2, 2);
        Bitmap plate = fillPlate(image, grown, mw, mh);
        return new SubjectLayer(sorted, plate);
    }

    void release() {
        for (Part p : parts) {
            p.cutout.recycle();
            p.aura.recycle();
            p.rim.recycle();
        }
        plate.recycle();
    }

    // ---- mask helpers (plain float grids, 0..1) ----

    /**
     * The outline of a subject: a thin bright line where the mask crosses its
     * middle, plus a soft halo around it, so it reads as neon light on the edge.
     */
    static float[] rim(float[] m, int w, int h) {
        float[] soft = blur(m, w, h, 1, 2);
        float[] line = new float[soft.length];
        for (int i = 0; i < soft.length; i++) {
            // Peaks at 1 where the (softened) mask is one half: right on the edge.
            float v = 4f * soft[i] * (1f - soft[i]);
            line[i] = v * v;
        }
        float[] halo = blur(line, w, h, 3, 3);
        float[] out = new float[line.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.min(1f, line[i] + 1.6f * halo[i]);
        }
        return out;
    }

    /**
     * The subjects in a mask: one grid for a gray mask, else one per color
     * channel (red, green, blue). A transparent pixel counts as background.
     */
    static java.util.List<float[]> readMasks(Bitmap mask, int w, int h) {
        Bitmap small = Bitmap.createScaledBitmap(mask, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != mask) {
            small.recycle();
        }
        boolean gray = true;
        for (int p : px) {
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            if (Math.abs(r - g) > 3 || Math.abs(g - b) > 3) {
                gray = false;
                break;
            }
        }
        java.util.List<float[]> grids = new java.util.ArrayList<>();
        int channels = gray ? 1 : MAX_PARTS;
        for (int c = 0; c < channels; c++) {
            int shift = 16 - 8 * c;
            float[] out = new float[w * h];
            for (int i = 0; i < px.length; i++) {
                int a = px[i] >>> 24;
                out[i] = ((px[i] >> shift) & 0xFF) * a / (255f * 255f);
            }
            grids.add(out);
        }
        return grids;
    }

    private static int auraW(int w, int h) {
        return Math.max(1, Math.round(w * AURA_SIDE / (float) Math.max(w, h)));
    }

    private static int auraH(int w, int h) {
        return Math.max(1, Math.round(h * AURA_SIDE / (float) Math.max(w, h)));
    }

    /** Box-downsamples to a grid whose long side is {@code side}. */
    private static float[] scale(float[] m, int w, int h, int side) {
        int ow = auraW(w, h);
        int oh = auraH(w, h);
        float[] out = new float[ow * oh];
        for (int y = 0; y < oh; y++) {
            int y0 = y * h / oh;
            int y1 = Math.max(y0 + 1, (y + 1) * h / oh);
            for (int x = 0; x < ow; x++) {
                int x0 = x * w / ow;
                int x1 = Math.max(x0 + 1, (x + 1) * w / ow);
                float s = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        s += m[yy * w + xx];
                    }
                }
                out[y * ow + x] = s / ((x1 - x0) * (y1 - y0));
            }
        }
        return out;
    }

    /** Separable box blur, {@code passes} times (three passes look Gaussian). */
    static float[] blur(float[] src, int w, int h, int radius, int passes) {
        float[] a = src.clone();
        float[] b = new float[a.length];
        for (int p = 0; p < passes; p++) {
            boxH(a, b, w, h, radius);
            boxV(b, a, w, h, radius);
        }
        return a;
    }

    private static void boxH(float[] in, float[] out, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            float s = 0;
            int n = 0;
            for (int x = -r; x <= r; x++) {
                if (x >= 0 && x < w) {
                    s += in[row + x];
                    n++;
                }
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = s / n;
                int add = x + r + 1;
                int drop = x - r;
                if (add < w) {
                    s += in[row + add];
                    n++;
                }
                if (drop >= 0) {
                    s -= in[row + drop];
                    n--;
                }
            }
        }
    }

    private static void boxV(float[] in, float[] out, int w, int h, int r) {
        for (int x = 0; x < w; x++) {
            float s = 0;
            int n = 0;
            for (int y = -r; y <= r; y++) {
                if (y >= 0 && y < h) {
                    s += in[y * w + x];
                    n++;
                }
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = s / n;
                int add = y + r + 1;
                int drop = y - r;
                if (add < h) {
                    s += in[add * w + x];
                    n++;
                }
                if (drop >= 0) {
                    s -= in[drop * w + x];
                    n--;
                }
            }
        }
    }

    /** Grows the marked area by {@code r} cells (separable max filter). */
    static float[] dilate(float[] src, int w, int h, int r) {
        float[] a = new float[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = 0;
                for (int k = Math.max(0, x - r); k <= Math.min(w - 1, x + r); k++) {
                    v = Math.max(v, src[y * w + k]);
                }
                a[y * w + x] = v;
            }
        }
        float[] out = new float[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = 0;
                for (int k = Math.max(0, y - r); k <= Math.min(h - 1, y + r); k++) {
                    v = Math.max(v, a[k * w + x]);
                }
                out[y * w + x] = v;
            }
        }
        return out;
    }

    /** White, with the grid as alpha. */
    private static Bitmap alphaBitmap(float[] m, int w, int h) {
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int a = Math.round(Math.max(0f, Math.min(1f, m[i])) * 255f);
            px[i] = (a << 24) | 0xFFFFFF;
        }
        Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        b.setHasAlpha(true);
        return b;
    }

    /**
     * Multiplies {@code target}'s alpha by the grid, stretched smoothly over it.
     * Done on the pixels directly: blend modes into a bitmap that started out
     * opaque don't reliably make it transparent.
     */
    private static void multiplyAlpha(Bitmap target, float[] m, int w, int h) {
        int tw = target.getWidth();
        int th = target.getHeight();
        Bitmap small = alphaBitmap(m, w, h);
        Bitmap alpha = Bitmap.createScaledBitmap(small, tw, th, true);
        if (alpha != small) {
            small.recycle();
        }
        int[] row = new int[tw];
        int[] arow = new int[tw];
        target.setHasAlpha(true);
        for (int y = 0; y < th; y++) {
            target.getPixels(row, 0, tw, 0, y, tw, 1);
            alpha.getPixels(arow, 0, tw, 0, y, tw, 1);
            for (int x = 0; x < tw; x++) {
                int a = ((row[x] >>> 24) * (arow[x] >>> 24) + 127) / 255;
                row[x] = (a << 24) | (row[x] & 0xFFFFFF);
            }
            target.setPixels(row, 0, tw, 0, y, tw, 1);
        }
        alpha.recycle();
    }

    /**
     * The image with the (grown) subject area replaced by colors pulled in from
     * around it: a blur of the background alone, divided by a blur of where
     * background is, at a few sizes so large holes fill too.
     */
    private static Bitmap fillPlate(Bitmap image, float[] grown, int w, int h) {
        Bitmap small = Bitmap.createScaledBitmap(image, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != image) {
            small.recycle();
        }
        float[] r = new float[w * h];
        float[] g = new float[w * h];
        float[] b = new float[w * h];
        float[] known = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            float k = grown[i] > 0.02f ? 0f : 1f;
            known[i] = k;
            r[i] = ((px[i] >> 16) & 0xFF) * k;
            g[i] = ((px[i] >> 8) & 0xFF) * k;
            b[i] = (px[i] & 0xFF) * k;
        }
        int[] fill = new int[w * h];
        boolean[] done = new boolean[w * h];
        int remaining = 0;
        for (int i = 0; i < known.length; i++) {
            if (known[i] > 0f) {
                fill[i] = px[i];
                done[i] = true;
            } else {
                remaining++;
            }
        }
        // Small radius first for nearby detail, then wider until every hole is filled.
        for (int radius = 4; remaining > 0 && radius <= Math.max(w, h); radius *= 2) {
            float[] br = blur(r, w, h, radius, 2);
            float[] bg = blur(g, w, h, radius, 2);
            float[] bb = blur(b, w, h, radius, 2);
            float[] bk = blur(known, w, h, radius, 2);
            for (int i = 0; i < fill.length; i++) {
                if (!done[i] && bk[i] > 0.01f) {
                    fill[i] = 0xFF000000
                            | (clamp(br[i] / bk[i]) << 16) | (clamp(bg[i] / bk[i]) << 8) | clamp(bb[i] / bk[i]);
                    done[i] = true;
                    remaining--;
                }
            }
        }
        Bitmap fillBitmap = Bitmap.createBitmap(fill, w, h, Bitmap.Config.ARGB_8888);
        // Keep the filled colors only inside the grown subject area, then lay them over the image.
        Bitmap masked = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(masked);
        Rect full = new Rect(0, 0, image.getWidth(), image.getHeight());
        c.drawBitmap(fillBitmap, null, full, new Paint(Paint.FILTER_BITMAP_FLAG));
        fillBitmap.recycle();
        multiplyAlpha(masked, grown, w, h);
        Bitmap plate = image.copy(Bitmap.Config.ARGB_8888, true);
        new Canvas(plate).drawBitmap(masked, 0, 0, null);
        masked.recycle();
        return plate;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
