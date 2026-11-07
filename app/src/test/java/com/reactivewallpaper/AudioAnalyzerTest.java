package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.After;
import org.junit.Test;

/** Beat detection and sync delay, fed a synthetic 120 BPM song on a simulated clock. */
public class AudioAnalyzerTest {
    private long now;

    @After
    public void restoreClock() {
        AudioAnalyzer.clock = android.os.SystemClock::uptimeMillis;
    }

    /** Runs 8 s of a kick every 500 ms over sustained bass; returns when beats fired. */
    private List<Long> beatTimes(long delayMs) {
        return beatTimes(delayMs, true, false);
    }

    /**
     * @param dropEveryFourth whether every fourth kick is left out of the audio
     *     (like a soft or masked hit), from the sixth beat on
     */
    private List<Long> beatTimes(long delayMs, boolean tempoSync, boolean dropEveryFourth) {
        AudioAnalyzer.clock = () -> now;
        AudioAnalyzer analyzer = new AudioAnalyzer(48);
        analyzer.setDelayMs(delayMs);
        analyzer.setTempoSync(tempoSync);
        int bins = 512;
        int rate = 44100;
        float binHz = rate / (2f * bins);
        Random random = new Random(1);
        List<Long> beats = new ArrayList<>();
        float previous = 0f;
        long nextCapture = 0;
        for (long t = 0; t < 8000; t += 16) {
            // Audio arrives about 20 times a second, like the phone's visualizer.
            while (nextCapture <= t) {
                float[] mags = new float[bins];
                float sinceKick = (nextCapture / 1000f) % 0.5f;
                long kickIndex = nextCapture / 500;
                boolean dropped = dropEveryFourth && kickIndex >= 6 && kickIndex % 4 == 3;
                float kick = dropped ? 0f : (float) Math.exp(-sinceKick * 9);
                for (int k = 0; k < bins; k++) {
                    float f = k * binHz;
                    float v = 0.01f * random.nextFloat();
                    if (f < 150) {
                        v += 0.55f + 0.45f * kick; // a sustained 808 under the kick
                    }
                    if (f > 200 && f < 2500) {
                        v += 0.1f + 0.05f * random.nextFloat();
                    }
                    mags[k] = v;
                }
                now = nextCapture;
                analyzer.submit(mags, rate);
                nextCapture += 50;
            }
            now = t;
            analyzer.update(0.016f);
            float beat = analyzer.beat();
            if (beat > previous + 0.3f) {
                beats.add(t);
            }
            previous = beat;
        }
        return beats;
    }

    @Test
    public void catchesKicksOverSustainedBass() {
        // 16 kicks; the first has nothing before it to stand out against.
        assertEquals(15, beatTimes(0).size());
    }

    @Test
    public void syncDelayHoldsBeatsBack() {
        for (long beat : beatTimes(200)) {
            long sinceKick = beat % 500;
            assertTrue("beat at " + beat + " not ~200 ms after a kick", sinceKick >= 190 && sinceKick <= 230);
        }
    }

    @Test
    public void tempoSyncFillsInMissedKicksOnTheBeat() {
        // Kicks 7, 11 and 15 are missing from the audio.
        List<Long> plain = beatTimes(0, false, true);
        List<Long> synced = beatTimes(0, true, true);
        assertTrue("plain detection misses them: " + plain, plain.size() <= 12);
        assertTrue("tempo sync fills them in: " + synced, synced.size() >= 14);
        for (long beat : synced) {
            long off = Math.min(beat % 500, 500 - beat % 500);
            assertTrue("beat at " + beat + " is off the grid", off <= 60);
        }
    }

    /** Beats shown after the music stops: none, whether frames stop coming or turn silent. */
    private List<Long> beatsAfterStop(boolean silentFrames) {
        AudioAnalyzer.clock = () -> now;
        AudioAnalyzer analyzer = new AudioAnalyzer(48);
        analyzer.setTempoSync(true);
        int bins = 512;
        float binHz = 44100 / (2f * bins);
        Random random = new Random(5);
        List<Long> after = new ArrayList<>();
        float previous = 0f;
        long nextCapture = 0;
        for (long t = 0; t < 9000; t += 16) {
            while (nextCapture <= t) {
                boolean music = nextCapture < 6000;
                if (music || silentFrames) {
                    float[] mags = new float[bins];
                    float kick = (float) Math.exp(-((nextCapture / 1000f) % 0.5f) * 9);
                    for (int k = 0; k < bins; k++) {
                        float f = k * binHz;
                        mags[k] = !music ? 0f : 0.01f * random.nextFloat() + (f < 150 ? 0.55f + 0.45f * kick : 0f);
                    }
                    now = nextCapture;
                    analyzer.submit(mags, 44100);
                }
                nextCapture += 50;
            }
            now = t;
            analyzer.update(0.016f);
            float beat = analyzer.beat();
            if (beat > previous + 0.3f && t > 6100) {
                after.add(t);
            }
            previous = beat;
        }
        return after;
    }

    @Test
    public void noGhostBeatsWhenTheMusicStops() {
        assertTrue("beats after frames stopped: " + beatsAfterStop(false), beatsAfterStop(false).isEmpty());
        assertTrue("beats through silent frames: " + beatsAfterStop(true), beatsAfterStop(true).isEmpty());
    }
}
