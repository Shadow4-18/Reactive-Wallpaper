package com.reactivewallpaper;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SubjectRimTest {
    @Test
    public void outlineIsBrightOnTheEdgeAndDarkInsideAndFarOutside() {
        int w = 80;
        int h = 80;
        float[] m = new float[w * h];
        // A 40 x 40 square subject in the middle.
        for (int y = 20; y < 60; y++) {
            for (int x = 20; x < 60; x++) {
                m[y * w + x] = 1f;
            }
        }
        float[] rim = SubjectLayer.rim(m, w, h);
        float edge = rim[40 * w + 20];
        float inside = rim[40 * w + 40];
        float outside = rim[40 * w + 2];
        assertTrue("edge " + edge, edge > 0.8f);
        assertTrue("inside " + inside, inside < 0.05f);
        assertTrue("outside " + outside, outside < 0.05f);
    }
}
