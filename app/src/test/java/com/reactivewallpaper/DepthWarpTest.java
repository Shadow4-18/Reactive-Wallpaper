package com.reactivewallpaper;

import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class DepthWarpTest {
    @Test
    public void subjectIsNearestAndDetailedGroundNearerThanPlainSky() {
        int w = 64;
        int h = 128;
        int[] px = new int[w * h];
        Random random = new Random(3);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v;
                if (y < h / 2) {
                    // A smooth, plain sky.
                    v = 60 + y / 4;
                } else {
                    // Busy, detailed ground.
                    v = 40 + random.nextInt(160);
                }
                px[y * w + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        float[] subject = new float[w * h];
        for (int y = 40; y < 80; y++) {
            for (int x = 24; x < 40; x++) {
                subject[y * w + x] = 1f;
            }
        }
        float[] d = DepthWarp.estimate(px, subject, w, h);
        float sky = d[10 * w + 5];
        float ground = d[115 * w + 5];
        float person = d[60 * w + 32];
        assertTrue("sky " + sky + " ground " + ground, ground > sky + 0.3f);
        assertTrue("person " + person, person > 0.9f);
        for (float v : d) {
            assertTrue(v >= 0f && v <= 1f);
        }
    }
}
