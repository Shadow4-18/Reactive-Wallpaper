package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CharacterRigTest {
    /** A standing figure: a round head on a body, in a 96 x 192 mask. */
    private static float[] figure(int w, int h) {
        float[] m = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean head = Math.hypot(x - 48, y - 30) < 12;
                boolean body = y >= 42 && y < 180 && Math.abs(x - 48) < 22;
                m[y * w + x] = head || body ? 1f : 0f;
            }
        }
        return m;
    }

    @Test
    public void guessesAPlausibleSkeletonFromTheShape() {
        CharacterRig r = CharacterRig.guess(figure(96, 192), 96, 192);
        assertNotNull(r);
        // Head above neck above chest above hips, all near the middle.
        assertTrue(r.y[CharacterRig.HEAD] < r.y[CharacterRig.NECK]);
        assertTrue(r.y[CharacterRig.NECK] < r.y[CharacterRig.CHEST]);
        assertTrue(r.y[CharacterRig.CHEST] < r.y[CharacterRig.HIPS]);
        assertEquals(0.5f, r.x[CharacterRig.HEAD], 0.05f);
        // Shoulders either side of the chest.
        assertTrue(r.x[CharacterRig.L_SHOULDER] < r.x[CharacterRig.CHEST]);
        assertTrue(r.x[CharacterRig.R_SHOULDER] > r.x[CharacterRig.CHEST]);
        assertNull(CharacterRig.guess(new float[96 * 192], 96, 192));
    }

    @Test
    public void savedAndReadBack() {
        CharacterRig r = CharacterRig.guess(figure(96, 192), 96, 192);
        r.hair = false;
        CharacterRig back = CharacterRig.decode(r.encode());
        assertNotNull(back);
        assertTrue(back.arms);
        assertTrue(!back.hair);
        for (int j = 0; j < CharacterRig.COUNT; j++) {
            assertEquals(r.x[j], back.x[j], 1e-3f);
            assertEquals(r.y[j], back.y[j], 1e-3f);
        }
        assertNull(CharacterRig.decode(""));
        assertNull(CharacterRig.decode("r1;1;1;nonsense"));
    }

    @Test
    public void standsStillWithoutMotionAndNodsOnTheBeat() {
        int pw = 1000;
        int ph = 2000;
        CharacterRig r = CharacterRig.guess(figure(96, 192), 96, 192);
        r.animate(0.016f, 0f, 0f, 0f);
        r.prepare(pw, ph);
        float[] head = r.posed(CharacterRig.HEAD, pw, ph);
        assertEquals(r.x[CharacterRig.HEAD] * pw, head[0], 0.5f);
        assertEquals(r.y[CharacterRig.HEAD] * ph, head[1], 0.5f);
        // A beat: the head moves, the hips (the root) hardly.
        for (int i = 0; i < 10; i++) {
            r.animate(0.016f, 0.8f, 1f, 1f);
        }
        r.prepare(pw, ph);
        float[] nod = r.posed(CharacterRig.HEAD, pw, ph);
        float[] hips = r.posed(CharacterRig.HIPS, pw, ph);
        float moved = (float) Math.hypot(nod[0] - head[0], nod[1] - head[1]);
        assertTrue("head moved " + moved, moved > 10f);
        assertTrue("hips " + hips[1], Math.abs(hips[1] - r.y[CharacterRig.HIPS] * ph) < 0.02f * ph);
    }
}
