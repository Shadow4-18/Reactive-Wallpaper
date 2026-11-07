package com.reactivewallpaper;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The newer visualizer styles: mirror bars, an LED meter with falling peak
 * caps, a pulsing orb and layered neon lines. Each draws the spectrum
 * ({@code bands}, 0..1 from bass to treble) in the look's colors.
 */
final class VisualizerStyles {
    /** The look's color at a position along the spectrum, 0 (bass) .. 1 (treble). */
    interface Palette {
        int color(float position);
    }

    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    // LED meter: each column's peak cap, which holds then falls.
    private float[] caps = new float[0];
    private float[] capHold = new float[0];
    // Neon lines drift sideways slowly so they never look frozen.
    private float drift;
    private float orbSpin;
    private float[] orbShape = new float[0];

    VisualizerStyles(float density) {
        this.density = density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Bars that grow up and down from a center line, bass in the middle. */
    void drawMirror(Canvas canvas, int w, int h, float[] bands, WallpaperSettings s, Palette palette) {
        int n = bands.length;
        if (n == 0) {
            return;
        }
        int columns = n * 2;
        float margin = w * 0.04f;
        float slot = (w - 2 * margin) / columns;
        float barWidth = Math.max(1f, slot * 0.58f);
        float radius = barWidth / 2f;
        float centerY = h * 0.84f;
        float maxHalf = h * 0.11f * s.vizHeight;
        float minHalf = 2f * density;
        for (int c = 0; c < columns; c++) {
            // Bass at the middle, treble toward both edges.
            int band = c < n ? n - 1 - c : c - n;
            float half = minHalf + bands[band] * maxHalf;
            float x = margin + slot * c + (slot - barWidth) / 2f;
            fill.setColor(palette.color(band / (float) Math.max(1, n - 1)));
            fill.setAlpha((int) (70 * s.vizOpacity));
            rect.set(x - barWidth * 0.5f, centerY - half - barWidth * 0.5f,
                    x + barWidth * 1.5f, centerY + half + barWidth * 0.5f);
            canvas.drawRoundRect(rect, barWidth, barWidth, fill);
            fill.setAlpha((int) (255 * s.vizOpacity));
            rect.set(x, centerY - half, x + barWidth, centerY + half);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }
    }

    /** Columns of lit segments, like a hardware level meter, with peak caps. */
    void drawLed(Canvas canvas, int w, int h, float[] bands, WallpaperSettings s, Palette palette, float dt) {
        int n = bands.length;
        if (n == 0) {
            return;
        }
        if (caps.length != n) {
            caps = new float[n];
            capHold = new float[n];
        }
        int segments = 14;
        float margin = w * 0.05f;
        float slot = (w - 2 * margin) / n;
        float cellW = Math.max(2f, slot * 0.72f);
        float base = h * 0.9f;
        float maxHeight = h * 0.26f * s.vizHeight;
        float cellPitch = maxHeight / segments;
        float cellH = cellPitch * 0.7f;
        float corner = Math.min(cellW, cellH) * 0.3f;
        for (int i = 0; i < n; i++) {
            float value = bands[i];
            // Caps jump up with the level, wait a moment, then fall.
            if (value >= caps[i]) {
                caps[i] = value;
                capHold[i] = 0.35f;
            } else if (capHold[i] > 0f) {
                capHold[i] -= dt;
            } else {
                caps[i] = Math.max(value, caps[i] - dt * 0.9f);
            }
            float x = margin + slot * i + (slot - cellW) / 2f;
            int lit = Math.round(value * segments);
            int color = palette.color(i / (float) Math.max(1, n - 1));
            for (int k = 0; k < segments; k++) {
                float top = base - (k + 1) * cellPitch;
                rect.set(x, top, x + cellW, top + cellH);
                fill.setColor(color);
                // Unlit cells stay faintly visible, like a real meter.
                fill.setAlpha((int) ((k < lit ? 255 : 26) * s.vizOpacity));
                canvas.drawRoundRect(rect, corner, corner, fill);
            }
            int capCell = Math.min(segments - 1, Math.round(caps[i] * segments));
            if (capCell > lit) {
                float top = base - (capCell + 1) * cellPitch;
                rect.set(x, top, x + cellW, top + cellH);
                fill.setColor(0xFFFFFFFF);
                fill.setAlpha((int) (230 * s.vizOpacity));
                canvas.drawRoundRect(rect, corner, corner, fill);
            }
        }
    }

    /**
     * A glowing blob at the ring's position whose outline ripples with the
     * spectrum (mirrored, so it stays symmetric) and swells with the bass.
     */
    void drawOrb(Canvas canvas, int w, int h, float[] bands, WallpaperSettings s, Palette palette,
                 float bass, float dt) {
        int n = bands.length;
        if (n == 0) {
            return;
        }
        orbSpin += dt * (0.15f + 0.6f * bass);
        float cx = w * s.circleX;
        float cy = h * s.circleY;
        float radius = Math.min(w, h) * s.circleSize * (0.85f + 0.18f * bass * s.vizHeight);
        int points = 96;
        if (orbShape.length != points) {
            orbShape = new float[points];
        }
        // Mirror the spectrum around the circle, then smooth it around the loop
        // so the outline ripples instead of jagging where neighboring bands differ.
        for (int i = 0; i < points; i++) {
            float t = i / (float) points;
            float pos = t < 0.5f ? t * 2f : (1f - t) * 2f;
            float fi = pos * (n - 1);
            int a = (int) fi;
            int b = Math.min(n - 1, a + 1);
            orbShape[i] = bands[a] + (bands[b] - bands[a]) * (fi - a);
        }
        for (int pass = 0; pass < 3; pass++) {
            float first = orbShape[0];
            float previous = orbShape[points - 1];
            for (int i = 0; i < points; i++) {
                float next = i + 1 < points ? orbShape[i + 1] : first;
                float current = orbShape[i];
                orbShape[i] = 0.25f * previous + 0.5f * current + 0.25f * next;
                previous = current;
            }
        }
        path.reset();
        for (int i = 0; i <= points; i++) {
            float t = i / (float) points;
            float value = orbShape[i % points];
            double angle = t * 2 * Math.PI + orbSpin - Math.PI / 2;
            float r = radius * (1f + 0.24f * value * s.vizHeight);
            float x = cx + (float) Math.cos(angle) * r;
            float y = cy + (float) Math.sin(angle) * r;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        int inner = palette.color(0f);
        int outer = palette.color(1f);
        float reach = radius * 1.4f;
        fill.setShader(new RadialGradient(cx, cy, reach,
                new int[] {withAlpha(inner, 0), withAlpha(inner, 90), withAlpha(outer, 170)},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        fill.setAlpha((int) (255 * s.vizOpacity));
        canvas.drawPath(path, fill);
        fill.setShader(null);
        stroke.setShader(null);
        stroke.setColor(outer);
        stroke.setStrokeWidth(9f * density);
        stroke.setAlpha((int) (55 * s.vizOpacity));
        canvas.drawPath(path, stroke);
        stroke.setColor(palette.color(0.5f));
        stroke.setStrokeWidth(2.5f * density);
        stroke.setAlpha((int) (255 * s.vizOpacity));
        canvas.drawPath(path, stroke);
    }

    /** Three glowing lines (bass, mids, highs) rippling across the lower screen. */
    void drawLines(Canvas canvas, int w, int h, float[] bands, WallpaperSettings s, Palette palette, float dt) {
        int n = bands.length;
        if (n < 3) {
            return;
        }
        drift += dt;
        float base = h * 0.82f;
        float amp = h * 0.09f * s.vizHeight;
        int third = n / 3;
        for (int layer = 0; layer < 3; layer++) {
            int from = layer * third;
            int to = layer == 2 ? n : from + third;
            float level = 0f;
            for (int i = from; i < to; i++) {
                level += bands[i];
            }
            level /= Math.max(1, to - from);
            // Lower layers are slower, wider waves; higher ones are quick and fine.
            float waves = 1.2f + layer * 1.3f;
            float speed = 0.5f + layer * 0.7f;
            float y0 = base + (layer - 1) * 14f * density;
            path.reset();
            int steps = 64;
            for (int k = 0; k <= steps; k++) {
                float t = k / (float) steps;
                // Each line also follows its own bands across the screen.
                float fi = from + t * (to - from - 1);
                int a = (int) fi;
                int b = Math.min(to - 1, a + 1);
                float local = bands[a] + (bands[b] - bands[a]) * (fi - a);
                float wave = (float) Math.sin((t * waves + drift * speed) * 2 * Math.PI);
                float y = y0 - (0.35f * level + 0.65f * local) * amp * (0.6f + 0.4f * wave);
                if (k == 0) {
                    path.moveTo(0, y);
                } else {
                    path.lineTo(t * w, y);
                }
            }
            int color = palette.color(layer / 2f);
            stroke.setShader(null);
            stroke.setColor(color);
            stroke.setStrokeWidth((10f - layer * 2f) * density);
            stroke.setAlpha((int) (50 * s.vizOpacity));
            canvas.drawPath(path, stroke);
            stroke.setStrokeWidth((3f - layer * 0.6f) * density);
            stroke.setAlpha((int) (255 * s.vizOpacity));
            canvas.drawPath(path, stroke);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
