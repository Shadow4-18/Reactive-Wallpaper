package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

public class GlitchBurstsTest {
    @Test
    public void eachBeatStartsAFreshBurstThatFades() {
        GlitchBursts g = new GlitchBursts(new Random(1));
        Set<Float> patterns = new HashSet<>();
        int bursts = 0;
        for (int beatNo = 0; beatNo < 8; beatNo++) {
            // A kick, then its pulse decays over half a second.
            float first = -1f;
            float last = 0f;
            for (int f = 0; f < 30; f++) {
                float beat = (float) Math.exp(-f * 0.25);
                g.update(1f / 60f, beat, 1f);
                if (f == 0) {
                    first = g.strength();
                    patterns.add(g.seed());
                }
                last = g.strength();
            }
            assertTrue("burst on beat " + beatNo, first > 0.5f);
            assertEquals("faded by the next beat", 0f, last, 0.0001f);
            bursts++;
        }
        assertEquals("every burst has its own pattern", bursts, patterns.size());
    }

    @Test
    public void patternsFlickerWithinABurst() {
        GlitchBursts g = new GlitchBursts(new Random(2));
        Set<Float> seeds = new HashSet<>();
        g.update(1f / 120f, 1f, 1f);
        while (g.isActive()) {
            seeds.add(g.seed());
            g.update(1f / 120f, 0.9f, 1f);
        }
        assertTrue("seen " + seeds.size() + " patterns", seeds.size() >= 2);
    }

    @Test
    public void offMeansOffAndQuietMusicStillGetsTheOddGlitch() {
        GlitchBursts off = new GlitchBursts(new Random(3));
        for (int f = 0; f < 600; f++) {
            off.update(1f / 60f, f % 30 == 0 ? 1f : 0f, 0f);
            assertEquals(0f, off.strength(), 0f);
        }
        GlitchBursts quiet = new GlitchBursts(new Random(4));
        int glitchyFrames = 0;
        for (int f = 0; f < 60 * 60; f++) {
            quiet.update(1f / 60f, 0f, 1f);
            if (quiet.strength() > 0f) {
                glitchyFrames++;
            }
        }
        // A minute without beats: a few small ones, not constant noise.
        assertTrue("glitchy frames: " + glitchyFrames, glitchyFrames > 10 && glitchyFrames < 60 * 60 / 5);
    }
}
