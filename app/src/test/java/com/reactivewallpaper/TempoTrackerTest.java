package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class TempoTrackerTest {
    /** Feeds a steady beat with timing jitter and some beats missed, like a real detector. */
    private static long feed(TempoTracker t, float bpm, long start, int beats, int jitterMs, float missed, long seed) {
        Random r = new Random(seed);
        double period = 60000.0 / bpm;
        long time = start;
        for (int i = 0; i < beats; i++) {
            time = start + Math.round(i * period);
            if (r.nextFloat() >= missed) {
                t.onset(time + r.nextInt(2 * jitterMs + 1) - jitterMs);
            }
        }
        return time;
    }

    @Test
    public void locksOntoADanceTempoDespiteJitterAndMissedBeats() {
        for (float bpm : new float[] {90f, 105f, 128f, 140f, 174f}) {
            TempoTracker t = new TempoTracker();
            long last = feed(t, bpm, 1000, 24, 15, 0.2f, (long) bpm);
            assertTrue("locked at " + bpm, t.isLockedAt(last + 100));
            assertEquals("bpm " + bpm, bpm, t.bpm(), 1.5f);
            // The next predicted beat lands within 25 ms of the real one.
            double period = 60000.0 / bpm;
            long nextReal = Math.round(1000 + 24 * period);
            long index = t.gridIndexAt(nextReal + 60);
            assertEquals("next beat at " + bpm, nextReal, t.gridTime(index), 25);
        }
    }

    @Test
    public void randomHitsDoNotLock() {
        TempoTracker t = new TempoTracker();
        Random r = new Random(3);
        long time = 0;
        for (int i = 0; i < 24; i++) {
            time += 150 + r.nextInt(900);
            t.onset(time);
        }
        assertFalse(t.isLocked());
    }

    @Test
    public void unlocksWhenTheMusicStopsAndRelearnsANewSong() {
        TempoTracker t = new TempoTracker();
        long last = feed(t, 128f, 0, 20, 10, 0f, 1);
        assertTrue(t.isLockedAt(last));
        assertFalse("stopped", t.isLockedAt(last + 4000));
        long second = feed(t, 95f, last + 5000, 20, 10, 0f, 2);
        assertTrue(t.isLockedAt(second));
        assertEquals(95f, t.bpm(), 1.5f);
    }

    @Test
    public void phaseRunsFromZeroOnTheBeat() {
        TempoTracker t = new TempoTracker();
        long last = feed(t, 120f, 0, 16, 0, 0f, 4);
        assertEquals(0f, t.phaseAt(last + 500), 0.03f);
        assertEquals(0.5f, t.phaseAt(last + 750), 0.03f);
    }
}
