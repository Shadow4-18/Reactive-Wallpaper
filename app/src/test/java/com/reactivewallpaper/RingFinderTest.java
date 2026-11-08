package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Random;

import org.junit.Test;

public class RingFinderTest {
    private static int[] sky(int w, int h, long seed) {
        int[] px = new int[w * h];
        Random r = new Random(seed);
        for (int i = 0; i < px.length; i++) {
            int y = i / w;
            int base = 10 + 20 * y / h;
            px[i] = 0xFF000000 | (base << 16) | (base / 2 << 8) | (base + 20);
            if (r.nextFloat() < 0.004f) {
                px[i] = 0xFFC0E0FF; // stars
            }
        }
        return px;
    }

    /** A soft-edged neon ring. */
    private static void ring(int[] px, int w, int h, float cx, float cy, float radius, float width, int rgb) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float d = Math.abs((float) Math.hypot(x - cx, y - cy) - radius);
                float a = Math.max(0f, 1f - d / width);
                if (a > 0f) {
                    px[y * w + x] = blend(px[y * w + x], rgb, a);
                }
            }
        }
    }

    private static void rect(int[] px, int w, int x0, int y0, int x1, int y1, int argb) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                px[y * w + x] = argb;
            }
        }
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    @Test
    public void findsANeonRing() {
        int w = 200;
        int h = 300;
        int[] px = sky(w, h, 1);
        ring(px, w, h, 92, 170, 58, 3f, 0xFF3DCB);
        RingFinder.Ring r = RingFinder.find(px, w, h);
        assertNotNull(r);
        assertEquals(92, r.x, 3);
        assertEquals(170, r.y, 3);
        assertEquals(58, r.radius, 3);
    }

    @Test
    public void picksTheOuterOfConcentricRings() {
        int w = 200;
        int h = 300;
        int[] px = sky(w, h, 2);
        ring(px, w, h, 100, 160, 72, 3f, 0xFF3DCB);
        ring(px, w, h, 100, 160, 52, 3f, 0x22E6FF);
        RingFinder.Ring r = RingFinder.find(px, w, h);
        assertNotNull(r);
        assertEquals(72, r.radius, 3);
    }

    @Test
    public void findsARingPartlyHiddenBehindAFigure() {
        int w = 200;
        int h = 300;
        int[] px = sky(w, h, 3);
        ring(px, w, h, 100, 150, 60, 3f, 0x22E6FF);
        // A dark figure standing in front of the lower part.
        rect(px, w, 70, 150, 130, 300, 0xFF0A0710);
        RingFinder.Ring r = RingFinder.find(px, w, h);
        assertNotNull(r);
        assertEquals(100, r.x, 3);
        assertEquals(150, r.y, 3);
        assertEquals(60, r.radius, 3);
    }

    @Test
    public void noRingInBoxes() {
        int w = 200;
        int h = 300;
        int[] px = sky(w, h, 4);
        rect(px, w, 20, 40, 90, 120, 0xFFFF3DCB);
        rect(px, w, 110, 160, 180, 260, 0xFF22E6FF);
        rect(px, w, 40, 200, 70, 280, 0xFFFFFFFF);
        assertNull(RingFinder.find(px, w, h));
    }

    /** A neon ring as drawn in art: a thin bright line inside a wide soft glow. */
    private static void glowingRing(int[] px, int w, int h, float cx, float cy, float radius) {
        ring(px, w, h, cx, cy, radius, 9f, 0x5A1E50);
        ring(px, w, h, cx, cy, radius, 2.2f, 0xFF6EE6);
    }

    @Test
    public void landsOnTheLineToAFractionOfAPixel() {
        int w = 360;
        int h = 640;
        int[] px = sky(w, h, 7);
        // Off the pixel grid on purpose, and with a wide glow around the line.
        glowingRing(px, w, h, 181.4f, 352.7f, 96.3f);
        RingFinder.Ring r = RingFinder.find(px, w, h);
        assertNotNull(r);
        assertEquals(181.4f, r.x, 0.6f);
        assertEquals(352.7f, r.y, 0.6f);
        assertEquals("on the line, not the glow's edge", 96.3f, r.radius, 0.8f);
    }
}
