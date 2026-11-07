package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SubjectSplitTest {
    private static final int W = 100;
    private static final int H = 200;

    private static void blob(int[] px, int x0, int y0, int x1, int y1, int v) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                px[y * W + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
    }

    private static int[] blank() {
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, 0xFF000000);
        return px;
    }

    @Test
    public void oneShapeStaysOneSubject() {
        int[] px = blank();
        blob(px, 30, 60, 70, 190, 255);
        assertNull(SubjectDetector.separate(px, W, H));
    }

    @Test
    public void separateShapesGetTheirOwnChannels() {
        int[] px = blank();
        blob(px, 50, 60, 90, 190, 255);   // big: red
        blob(px, 5, 100, 30, 170, 255);   // smaller: green
        blob(px, 4, 99, 31, 100, 30);     // its faint top edge
        blob(px, 2, 2, 4, 4, 255);        // a speck: joins the largest
        int[] out = SubjectDetector.separate(px, W, H);
        assertNotNull(out);
        assertEquals(255, (out[100 * W + 70] >> 16) & 0xFF);
        assertEquals(0, (out[100 * W + 70] >> 8) & 0xFF);
        assertEquals(255, (out[120 * W + 10] >> 8) & 0xFF);
        assertEquals(0, (out[120 * W + 10] >> 16) & 0xFF);
        // The faint edge belongs with the green subject, not the red one.
        assertEquals(30, (out[99 * W + 10] >> 8) & 0xFF);
        assertEquals(0, (out[99 * W + 10] >> 16) & 0xFF);
        assertEquals(255, (out[3 * W + 3] >> 16) & 0xFF);
    }

    @Test
    public void atMostThreeSubjects() {
        int[] px = blank();
        blob(px, 0, 0, 20, 40, 255);
        blob(px, 30, 0, 50, 40, 255);
        blob(px, 60, 0, 80, 40, 255);
        blob(px, 0, 100, 20, 140, 255);
        int[] out = SubjectDetector.separate(px, W, H);
        assertNotNull(out);
        // Four equal shapes: three get channels, the fourth joins the first.
        int marked = 0;
        for (int c = 0; c < 3; c++) {
            boolean any = false;
            for (int p : out) {
                any |= ((p >> (16 - 8 * c)) & 0xFF) > 0;
            }
            marked += any ? 1 : 0;
        }
        assertEquals(3, marked);
    }
}
