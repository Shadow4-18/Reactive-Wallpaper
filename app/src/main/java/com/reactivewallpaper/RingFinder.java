package com.reactivewallpaper;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a ring or circle in a picture (a neon ring, a moon, a round frame),
 * so the ring visualizer can sit on it.
 *
 * <p>Plain Java on ARGB pixels, so it can be tested without a phone. Edges
 * vote for the centers they point at; each likely center is then checked for
 * a radius at which edges run most of the way around. Concentric rings resolve
 * to the outermost, so the visualizer's spokes reach out past the artwork.
 */
final class RingFinder {
    /** A circle in the picture's pixels. */
    static final class Ring {
        final float x;
        final float y;
        final float radius;
        /** 0..1: how much of the way around the circle edges were found. */
        final float coverage;

        Ring(float x, float y, float radius, float coverage) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.coverage = coverage;
        }
    }

    private static final int ANGLE_BINS = 72;

    /**
     * Puts the ring visualizer on {@code ring} (found in a {@code bw} x {@code bh}
     * copy of the picture) where it lands on a {@code w} x {@code h} screen with
     * the look's fit, zoom and position: center, size (from its width on screen)
     * and shape (an oval where the fit stretches the picture).
     */
    static void place(Ring ring, int bw, int bh, int w, int h, WallpaperSettings s) {
        float[] fit = WallpaperRenderer.fit(bw, bh, w, h, s, true);
        float x = (w - bw * fit[0]) / 2f + fit[2] + ring.x * fit[0];
        float y = (h - bh * fit[1]) / 2f + fit[3] + ring.y * fit[1];
        s.circleX = clamp(x / w, 0.1f, 0.9f);
        s.circleY = clamp(y / h, 0.1f, 0.9f);
        s.circleSize = clamp(ring.radius * fit[0] / Math.min(w, h), 0.08f, 0.5f);
        s.circleStretch = clamp(fit[1] / fit[0], 0.5f, 2f);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
    private static final float MIN_COVERAGE = 0.55f;

    private RingFinder() {}

    /** The best ring in a {@code w} x {@code h} picture, or null when there is none. */
    static Ring find(int[] argb, int w, int h) {
        int n = w * h;
        float[] lum = new float[n];
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            // Brightness, with saturated colors counting extra: neon lines stand out.
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            lum[i] = (0.3f * r + 0.59f * g + 0.11f * b + 0.5f * (max - min)) / 255f;
        }
        float[] gx = new float[n];
        float[] gy = new float[n];
        float[] mag = new float[n];
        float maxMag = 0f;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float sx = lum[i - w + 1] + 2 * lum[i + 1] + lum[i + w + 1]
                        - lum[i - w - 1] - 2 * lum[i - 1] - lum[i + w - 1];
                float sy = lum[i + w - 1] + 2 * lum[i + w] + lum[i + w + 1]
                        - lum[i - w - 1] - 2 * lum[i - w] - lum[i - w + 1];
                float m = (float) Math.sqrt(sx * sx + sy * sy);
                gx[i] = sx;
                gy[i] = sy;
                mag[i] = m;
                maxMag = Math.max(maxMag, m);
            }
        }
        if (maxMag <= 0f) {
            return null;
        }
        // Keep the strongest edges: above a fraction of the strongest, and the top ~10%.
        float threshold = Math.max(0.15f * maxMag, percentile(mag, 0.9f));
        int side = Math.min(w, h);
        int rMin = Math.max(4, Math.round(side * 0.08f));
        int rMax = Math.round(side * 0.5f);

        List<Integer> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (mag[i] >= threshold) {
                edges.add(i);
            }
        }
        if (edges.size() < 20) {
            return null;
        }

        // Each edge votes along its gradient (both ways: light ring on dark or dark on light).
        float[] votes = new float[n];
        for (int i : edges) {
            int ex = i % w;
            int ey = i / w;
            float dx = gx[i] / mag[i];
            float dy = gy[i] / mag[i];
            for (int r = rMin; r <= rMax; r++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    int cx = Math.round(ex + sign * dx * r);
                    int cy = Math.round(ey + sign * dy * r);
                    if (cx >= 0 && cy >= 0 && cx < w && cy < h) {
                        votes[cy * w + cx] += 1f / r;
                    }
                }
            }
        }
        votes = SubjectLayer.blur(votes, w, h, 1, 1);

        // The few strongest centers, kept apart from each other.
        List<int[]> centers = new ArrayList<>();
        float[] remaining = votes.clone();
        for (int k = 0; k < 6; k++) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (best < 0 || remaining[i] > remaining[best]) {
                    best = i;
                }
            }
            if (best < 0 || remaining[best] <= 0f) {
                break;
            }
            int bx = best % w;
            int by = best / w;
            centers.add(new int[] {bx, by});
            int clear = Math.max(2, rMin / 2);
            for (int y = Math.max(0, by - clear); y <= Math.min(h - 1, by + clear); y++) {
                for (int x = Math.max(0, bx - clear); x <= Math.min(w - 1, bx + clear); x++) {
                    remaining[y * w + x] = 0f;
                }
            }
        }

        List<Ring> rings = new ArrayList<>();
        for (int[] c : centers) {
            rings.addAll(ringsAround(c[0], c[1], edges, gx, gy, mag, w, rMin, rMax));
        }
        Ring best = null;
        for (Ring r : rings) {
            if (best == null || r.coverage > best.coverage) {
                best = r;
            }
        }
        if (best == null) {
            return null;
        }
        // Of rings nearly as complete around (about) the same center, take the outermost.
        Ring chosen = best;
        float near = side * 0.06f;
        for (Ring r : rings) {
            if (r.coverage >= best.coverage - 0.12f && Math.hypot(r.x - best.x, r.y - best.y) <= near
                    && r.radius > chosen.radius) {
                chosen = r;
            }
        }
        // The edges only give the ring roughly (to a pixel, and a glow's outer edge
        // can stand in for the line): pin it down on the line itself.
        return refine(lum, w, h, chosen);
    }

    /**
     * Puts the ring exactly on its line: along rays from the center, finds the
     * brightest point near the ring (to a fraction of a pixel), then fits the
     * circle through those points; twice, re-centering in between.
     */
    static Ring refine(float[] lum, int w, int h, Ring ring) {
        double cx = ring.x;
        double cy = ring.y;
        double r = ring.radius;
        for (int pass = 0; pass < 3; pass++) {
            int rays = 120;
            double[] px = new double[rays];
            double[] py = new double[rays];
            int n = 0;
            for (int k = 0; k < rays; k++) {
                double a = 2 * Math.PI * k / rays;
                double cos = Math.cos(a);
                double sin = Math.sin(a);
                double from = r * 0.82;
                double to = r * 1.18;
                int steps = (int) Math.ceil((to - from) / 0.5);
                double best = -1;
                double low = Double.MAX_VALUE;
                int bestStep = -1;
                double[] values = new double[steps + 1];
                boolean inside = true;
                for (int s = 0; s <= steps; s++) {
                    double d = from + s * 0.5;
                    double v = sample(lum, w, h, cx + cos * d, cy + sin * d);
                    if (Double.isNaN(v)) {
                        inside = false;
                        break;
                    }
                    values[s] = v;
                    if (v > best) {
                        best = v;
                        bestStep = s;
                    }
                    low = Math.min(low, v);
                }
                // Skip rays that leave the picture, cross nothing, or peak at the window's edge.
                if (!inside || best - low < 0.08 || bestStep <= 0 || bestStep >= steps) {
                    continue;
                }
                // The peak between samples, from the parabola through its neighbors.
                double l = values[bestStep - 1];
                double m = values[bestStep];
                double rr = values[bestStep + 1];
                double denom = l - 2 * m + rr;
                double shift = denom < 0 ? 0.5 * (l - rr) / denom : 0;
                double d = from + (bestStep + Math.max(-0.5, Math.min(0.5, shift))) * 0.5;
                px[n] = cx + cos * d;
                py[n] = cy + sin * d;
                n++;
            }
            if (n < rays / 4) {
                break;
            }
            double[] fit = fitCircle(px, py, n);
            if (fit == null || fit[2] <= 0 || Math.hypot(fit[0] - cx, fit[1] - cy) > r * 0.2) {
                break;
            }
            cx = fit[0];
            cy = fit[1];
            r = fit[2];
        }
        return new Ring((float) cx, (float) cy, (float) r, ring.coverage);
    }

    /** Least-squares circle through points (Kasa's method): {x, y, radius}, or null. */
    static double[] fitCircle(double[] x, double[] y, int n) {
        // Solve x^2 + y^2 + a x + b y + c = 0 for a, b, c.
        double sxx = 0, sxy = 0, syy = 0, sx = 0, sy = 0, sxz = 0, syz = 0, sz = 0;
        for (int i = 0; i < n; i++) {
            double z = x[i] * x[i] + y[i] * y[i];
            sxx += x[i] * x[i];
            sxy += x[i] * y[i];
            syy += y[i] * y[i];
            sx += x[i];
            sy += y[i];
            sxz += x[i] * z;
            syz += y[i] * z;
            sz += z;
        }
        double[][] m = {{sxx, sxy, sx}, {sxy, syy, sy}, {sx, sy, n}};
        double[] v = {-sxz, -syz, -sz};
        double det = det3(m);
        if (Math.abs(det) < 1e-9) {
            return null;
        }
        double[] abc = new double[3];
        for (int c = 0; c < 3; c++) {
            double[][] mc = {m[0].clone(), m[1].clone(), m[2].clone()};
            for (int row = 0; row < 3; row++) {
                mc[row][c] = v[row];
            }
            abc[c] = det3(mc) / det;
        }
        double cx = -abc[0] / 2;
        double cy = -abc[1] / 2;
        double r2 = cx * cx + cy * cy - abc[2];
        return r2 > 0 ? new double[] {cx, cy, Math.sqrt(r2)} : null;
    }

    private static double det3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    /** Brightness between pixels (bilinear), or NaN outside the picture. */
    private static double sample(float[] lum, int w, int h, double x, double y) {
        if (x < 0 || y < 0 || x > w - 1 || y > h - 1) {
            return Double.NaN;
        }
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        double fx = x - x0;
        double fy = y - y0;
        double top = lum[y0 * w + x0] * (1 - fx) + lum[y0 * w + x1] * fx;
        double bottom = lum[y1 * w + x0] * (1 - fx) + lum[y1 * w + x1] * fx;
        return top * (1 - fy) + bottom * fy;
    }

    /** Radii around one center at which aligned edges run most of the way around. */
    private static List<Ring> ringsAround(int cx, int cy, List<Integer> edges, float[] gx, float[] gy,
                                          float[] mag, int w, int rMin, int rMax) {
        float[] hist = new float[rMax + 2];
        for (int i : edges) {
            float dx = i % w - cx;
            float dy = i / w - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < rMin || d > rMax) {
                continue;
            }
            float align = Math.abs((gx[i] * dx + gy[i] * dy) / (mag[i] * d));
            if (align > 0.85f) {
                hist[Math.round(d)] += 1f;
            }
        }
        List<Ring> found = new ArrayList<>();
        for (int r = rMin; r <= rMax; r++) {
            float v = hist[r] + 0.5f * (hist[r - 1] + hist[r + 1]);
            boolean peak = v > 0f && hist[r] >= hist[r - 1] && hist[r] >= hist[r + 1];
            if (!peak) {
                continue;
            }
            float coverage = coverage(cx, cy, r, edges, gx, gy, mag, w);
            if (coverage >= MIN_COVERAGE) {
                found.add(new Ring(cx, cy, r, coverage));
            }
        }
        // Merge the inner and outer edge of one drawn ring into its middle.
        List<Ring> merged = new ArrayList<>();
        for (Ring r : found) {
            Ring last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && r.radius - last.radius <= Math.max(3f, last.radius * 0.08f)) {
                merged.set(merged.size() - 1, new Ring(cx, cy, (last.radius + r.radius) / 2f,
                        Math.max(last.coverage, r.coverage)));
            } else {
                merged.add(r);
            }
        }
        return merged;
    }

    private static float coverage(int cx, int cy, int r, List<Integer> edges, float[] gx, float[] gy,
                                  float[] mag, int w) {
        boolean[] seen = new boolean[ANGLE_BINS];
        for (int i : edges) {
            float dx = i % w - cx;
            float dy = i / w - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (Math.abs(d - r) > 2f || d == 0f) {
                continue;
            }
            float align = Math.abs((gx[i] * dx + gy[i] * dy) / (mag[i] * d));
            if (align > 0.85f) {
                double angle = Math.atan2(dy, dx) + Math.PI;
                seen[Math.min(ANGLE_BINS - 1, (int) (angle / (2 * Math.PI) * ANGLE_BINS))] = true;
            }
        }
        int count = 0;
        for (boolean b : seen) {
            if (b) {
                count++;
            }
        }
        return count / (float) ANGLE_BINS;
    }

    private static float percentile(float[] values, float q) {
        // A coarse histogram is plenty for a threshold.
        float max = 0f;
        for (float v : values) {
            max = Math.max(max, v);
        }
        int[] hist = new int[256];
        for (float v : values) {
            hist[Math.min(255, (int) (v / max * 255))]++;
        }
        int target = Math.round(values.length * q);
        int sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += hist[i];
            if (sum >= target) {
                return i / 255f * max;
            }
        }
        return max;
    }
}
