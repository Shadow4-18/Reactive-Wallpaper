package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EyeFinderTest {
    private static final int W = 200;
    private static final int H = 400;
    private static final int SKIN = 0xFFF0DCD8;
    private static final int HAIR = 0xFF14101A;
    private static final int BACK = 0xFF808080;

    private static void ellipse(int[] px, float cx, float cy, float rx, float ry, int color) {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float dx = (x - cx) / rx;
                float dy = (y - cy) / ry;
                if (dx * dx + dy * dy <= 1f) {
                    px[y * W + x] = color;
                }
            }
        }
    }

    @Test
    public void findsBothEyesUnderBangsWithAStrandBetweenThem() {
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, BACK);
        // A face, its bangs, a strand of hair hanging between the eyes, and the eyes.
        ellipse(px, 100, 80, 34, 42, SKIN);
        for (int y = 30; y < 62; y++) {
            for (int x = 60; x < 140; x++) {
                px[y * W + x] = HAIR;
            }
        }
        for (int y = 62; y < 84; y++) {
            for (int x = 97; x < 103; x++) {
                px[y * W + x] = HAIR;
            }
        }
        ellipse(px, 84, 76, 9, 5, 0xFF28202C);
        ellipse(px, 116, 76, 9, 5, 0xFF28202C);

        CharacterRig rig = new CharacterRig();
        rig.x[CharacterRig.HEAD] = 100f / W;
        rig.y[CharacterRig.HEAD] = 80f / H;
        rig.x[CharacterRig.NECK] = 100f / W;
        rig.y[CharacterRig.NECK] = 115f / H;
        assertTrue(EyeFinder.find(px, W, H, rig));
        assertEquals(84f, rig.eyeX[0] * W, 2f);
        assertEquals(116f, rig.eyeX[1] * W, 2f);
        assertEquals(76f, rig.eyeY[0] * H, 2f);
        assertEquals(76f, rig.eyeY[1] * H, 2f);
        // About the eyes' size (width 19 px).
        assertEquals(19f * 1.15f, rig.eyeW[0] * W, 4f);
    }

    @Test
    public void givesUpWithoutAFace() {
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, BACK);
        CharacterRig rig = new CharacterRig();
        rig.x[CharacterRig.HEAD] = 0.5f;
        rig.y[CharacterRig.HEAD] = 0.2f;
        rig.x[CharacterRig.NECK] = 0.5f;
        rig.y[CharacterRig.NECK] = 0.3f;
        assertTrue(!EyeFinder.find(px, W, H, rig));
    }
}
