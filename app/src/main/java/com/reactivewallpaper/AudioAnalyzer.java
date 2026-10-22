package com.reactivewallpaper;

import android.os.SystemClock;

/**
 * Turns raw FFT magnitudes into smoothed, auto-gained frequency bands plus a
 * bass level, a mids/highs level and a beat pulse, ready for drawing.
 *
 * Audio threads call {@link #submit}; the render thread calls {@link #update}
 * once per frame and then reads the getters. Frames are kept with timestamps
 * so the visuals can be delayed to line up with what is actually heard (e.g.
 * Bluetooth plays audio well after the app sees it).
 */
final class AudioAnalyzer {
    private static final float MIN_FREQ = 35f;
    private static final float MAX_FREQ = 16000f;
    private static final float BASS_MAX_FREQ = 160f;
    private static final long STALE_MS = 400;
    private static final int HISTORY = 96;

    // Ring buffer of submitted frames, guarded by lock.
    private final Object lock = new Object();
    private final float[][] history = new float[HISTORY][];
    private final long[] historyTime = new long[HISTORY];
    private final int[] historyRate = new int[HISTORY];
    private int historyHead = -1;
    private long submitted;
    private long processedSequence = -1;

    private float[] work = new float[0];
    private float[] raw = new float[0];
    private float[] previousRaw = new float[0];
    private float[] target = new float[0];
    private float[] bands = new float[0];
    private float peak;
    private float noiseFloor = 0.03f;
    private float sensitivity = 1f;
    private float smoothing = 0.4f;
    private float beatSensitivity = 0.5f;
    private long delayMs;

    private float bassTarget;
    private float bass;
    private float highTarget;
    private float high;
    private float level;
    private float beat;
    private float fluxAverage;
    private long lastFrameTime;
    private long lastBeatTime;

    AudioAnalyzer(int bandCount) {
        setBandCount(bandCount);
    }

    void setBandCount(int count) {
        if (count != bands.length) {
            bands = new float[count];
            raw = new float[count];
            previousRaw = new float[count];
            target = new float[count];
        }
    }

    void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    /** 0 = snappy (falls back instantly) .. 1 = smooth (slow fall). */
    void setSmoothing(float smoothing) {
        this.smoothing = smoothing;
    }

    /** 0 = only strong hits count as beats .. 1 = nearly every bass hit. */
    void setBeatSensitivity(float beatSensitivity) {
        this.beatSensitivity = beatSensitivity;
    }

    /** Shows audio this many milliseconds after it was captured. */
    void setDelayMs(long delayMs) {
        this.delayMs = Math.max(0, delayMs);
    }

    /** Magnitudes below this (in the source's normalized units) count as silence. */
    void setNoiseFloor(float noiseFloor) {
        this.noiseFloor = noiseFloor;
    }

    /**
     * @param mags FFT magnitudes for bins 0..n/2-1, roughly normalized to 0..1
     * @param sampleRate sample rate in Hz of the audio the FFT came from
     */
    void submit(float[] mags, int sampleRate) {
        synchronized (lock) {
            int slot = (historyHead + 1) % HISTORY;
            if (history[slot] == null || history[slot].length != mags.length) {
                history[slot] = new float[mags.length];
            }
            System.arraycopy(mags, 0, history[slot], 0, mags.length);
            historyTime[slot] = SystemClock.uptimeMillis();
            historyRate[slot] = sampleRate;
            historyHead = slot;
            submitted++;
        }
    }

    /** Advances by dt seconds using the audio frame that is due now. */
    void update(float dt) {
        long now = SystemClock.uptimeMillis();
        long due = now - delayMs;
        int binCount = 0;
        int rate = 0;
        long frameTime = 0;
        long sequence = -1;
        synchronized (lock) {
            // Newest frame captured at or before the due time.
            for (int i = 0; i < HISTORY && i < submitted; i++) {
                int slot = (historyHead - i + HISTORY) % HISTORY;
                if (historyTime[slot] <= due) {
                    if (due - historyTime[slot] < STALE_MS) {
                        sequence = submitted - 1 - i;
                        if (sequence != processedSequence) {
                            float[] frame = history[slot];
                            binCount = frame.length;
                            if (work.length != binCount) {
                                work = new float[binCount];
                            }
                            System.arraycopy(frame, 0, work, 0, binCount);
                            rate = historyRate[slot];
                            frameTime = historyTime[slot];
                        }
                    }
                    break;
                }
            }
        }

        if (sequence < 0) {
            // Silence or no audio: let everything fall away.
            clearTargets();
            processedSequence = -1;
        } else if (sequence != processedSequence && binCount > 1) {
            analyzeFrame(binCount, rate, frameTime);
            processedSequence = sequence;
        }

        // Instant attack so hits land on time; release speed set by smoothing.
        float fall = Math.min(1f, dt * (22f - 19f * smoothing));
        int n = bands.length;
        float total = 0f;
        for (int i = 0; i < n; i++) {
            bands[i] = target[i] > bands[i] ? target[i] : bands[i] + (target[i] - bands[i]) * fall;
            total += bands[i];
        }
        level = n > 0 ? total / n : 0f;
        bass = bassTarget > bass ? bassTarget : bass + (bassTarget - bass) * fall;
        high = highTarget > high ? highTarget : high + (highTarget - high) * fall;
        beat *= (float) Math.pow(0.012, dt);
    }

    private void clearTargets() {
        for (int i = 0; i < target.length; i++) {
            target[i] = 0f;
        }
        bassTarget = 0f;
        highTarget = 0f;
    }

    /** Runs once per captured audio frame. */
    private void analyzeFrame(int binCount, int rate, long frameTime) {
        float frameDt = lastFrameTime > 0 ? Math.min(0.2f, (frameTime - lastFrameTime) / 1000f) : 0.03f;
        lastFrameTime = frameTime;

        int n = bands.length;
        float binHz = rate / (2f * binCount);
        float ratio = MAX_FREQ / MIN_FREQ;
        float frameMax = 0f;
        for (int i = 0; i < n; i++) {
            float fLo = MIN_FREQ * (float) Math.pow(ratio, i / (float) n);
            float fHi = MIN_FREQ * (float) Math.pow(ratio, (i + 1) / (float) n);
            int lo = Math.max(1, (int) (fLo / binHz));
            int hi = Math.min(binCount, Math.max(lo + 1, (int) Math.ceil(fHi / binHz)));
            float sum = 0f;
            float max = 0f;
            for (int k = lo; k < hi; k++) {
                sum += work[k];
                max = Math.max(max, work[k]);
            }
            float v = hi > lo ? 0.5f * (max + sum / (hi - lo)) : 0f;
            // Higher frequencies carry far less energy; tilt so the
            // spectrum looks balanced instead of bass-only.
            v *= 1f + 2.5f * i / n;
            raw[i] = v;
            frameMax = Math.max(frameMax, v);
        }

        // Automatic gain: follow the loudest recent band, decaying slowly.
        peak = Math.max(frameMax, peak * (float) Math.pow(0.55, frameDt));
        float norm = Math.max(peak, noiseFloor);

        float bassSum = 0f;
        float flux = 0f;
        int bassCount = 0;
        float highSum = 0f;
        int highStart = (int) (n * 0.35f);
        for (int i = 0; i < n; i++) {
            float normalized = raw[i] / norm;
            target[i] = Math.min(1f, normalized * sensitivity);
            float fHi = MIN_FREQ * (float) Math.pow(ratio, (i + 1) / (float) n);
            if (fHi <= BASS_MAX_FREQ || i == 0) {
                bassSum += normalized;
                // Onset strength: how much the bass jumped since the last frame.
                flux += Math.max(0f, normalized - previousRaw[i] / norm);
                bassCount++;
            }
            if (i >= highStart) {
                highSum += target[i];
            }
            previousRaw[i] = raw[i];
        }
        float bassNorm = bassSum / Math.max(1, bassCount);
        flux /= Math.max(1, bassCount);
        bassTarget = Math.min(1f, bassNorm * sensitivity);
        highTarget = n > highStart ? highSum / (n - highStart) : 0f;

        float multiplier = 1.3f + (1f - beatSensitivity) * 1.6f;
        float minimum = 0.03f + (1f - beatSensitivity) * 0.12f;
        if (flux > fluxAverage * multiplier + minimum && bassNorm > 0.15f
                && frameTime - lastBeatTime > 150) {
            beat = Math.min(1f, 0.6f + 0.4f * sensitivity * bassNorm);
            lastBeatTime = frameTime;
        }
        fluxAverage += (flux - fluxAverage) * Math.min(1f, frameDt * 2.5f);
    }

    float[] bands() {
        return bands;
    }

    float bass() {
        return bass;
    }

    /** Average of the mid and high bands, 0..1. */
    float high() {
        return high;
    }

    float beat() {
        return beat;
    }

    /** Capture time (uptime ms) of the most recent detected beat, ignoring the sync delay. */
    long lastBeatTime() {
        return lastBeatTime;
    }

    float level() {
        return level;
    }

    /** True when nothing is moving, so the renderer can drop its frame rate. */
    boolean isIdle() {
        return level < 0.003f && beat < 0.003f && bass < 0.003f;
    }
}
