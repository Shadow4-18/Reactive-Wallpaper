package com.reactivewallpaper;

import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.After;
import org.junit.Test;

/**
 * Real music is far louder in the bass than in the mids and highs. The bands
 * the visualizer draws must still move across the whole spectrum, not just at
 * the bass end.
 */
public class SpectrumBalanceTest {
    private long now;

    @After
    public void restoreClock() {
        AudioAnalyzer.clock = android.os.SystemClock::uptimeMillis;
    }

    /** Average band level in each third of the spectrum over 4 s of bass-heavy music. */
    private float[] thirds() {
        AudioAnalyzer.clock = () -> now;
        AudioAnalyzer analyzer = new AudioAnalyzer(48);
        Random random = new Random(2);
        int bins = 512;
        float binHz = 44100 / (2f * bins);
        float[] sums = new float[3];
        int frames = 0;
        for (long t = 0; t < 4000; t += 33) {
            now = t;
            float kick = (float) Math.exp(-((t / 1000f) % 0.5f) * 9f);
            float[] mags = new float[bins];
            for (int k = 0; k < bins; k++) {
                float f = k * binHz;
                float v = 0.01f * random.nextFloat();
                if (f < 150) {
                    v += 0.9f * kick;
                }
                if (f > 200 && f < 2500) {
                    v += 0.12f * (0.6f + 0.4f * random.nextFloat());
                }
                if (f > 5000) {
                    v += 0.04f * random.nextFloat();
                }
                mags[k] = v;
            }
            analyzer.submit(mags, 44100);
            analyzer.update(0.033f);
            if (t > 1000) {
                float[] bands = analyzer.bands();
                for (int i = 0; i < bands.length; i++) {
                    sums[Math.min(2, i * 3 / bands.length)] += bands[i];
                }
                frames++;
            }
        }
        for (int i = 0; i < 3; i++) {
            sums[i] /= frames * (48 / 3f);
        }
        return sums;
    }

    @Test
    public void midsAndHighsMoveToo() {
        float[] levels = thirds();
        String report = String.format("low %.2f, mid %.2f, high %.2f", levels[0], levels[1], levels[2]);
        assertTrue("mids too quiet: " + report, levels[1] > 0.3f);
        assertTrue("highs too quiet: " + report, levels[2] > 0.2f);
        // Moving, not pinned at the top: a steady melody shouldn't fill its bars.
        assertTrue("mids pinned: " + report, levels[1] < 0.85f);
        assertTrue("highs pinned: " + report, levels[2] < 0.85f);
    }
}
