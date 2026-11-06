package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class BeatCalibrationTest {
    private static final int PERIOD = 780;

    @Test
    public void measuresTheGapBetweenDetectionAndTaps() {
        Random random = new Random(3);
        List<Long> onsets = new ArrayList<>();
        List<Long> taps = new ArrayList<>();
        for (int c = 0; c < BeatCalibration.TOTAL_CLICKS; c++) {
            long click = 1000 + c * (long) PERIOD;
            onsets.add(click + 25);
            taps.add(click + 250 + Math.round(random.nextGaussian() * 20));
        }
        BeatCalibration.Result result = BeatCalibration.computeResult(onsets, taps, PERIOD);
        assertTrue(result.ok);
        assertEquals(225, result.delayMs, 15);
    }

    @Test
    public void failsWhenClicksWerentHeard() {
        List<Long> onsets = new ArrayList<>();
        onsets.add(1000L);
        List<Long> taps = new ArrayList<>();
        taps.add(1200L);
        assertFalse(BeatCalibration.computeResult(onsets, taps, PERIOD).ok);
    }

    @Test
    public void failsWithTooFewTaps() {
        List<Long> onsets = new ArrayList<>();
        for (int c = 0; c < BeatCalibration.TOTAL_CLICKS; c++) {
            onsets.add(1000 + c * (long) PERIOD);
        }
        List<Long> taps = new ArrayList<>();
        taps.add(onsets.get(5) + 200);
        assertFalse(BeatCalibration.computeResult(onsets, taps, PERIOD).ok);
    }
}
