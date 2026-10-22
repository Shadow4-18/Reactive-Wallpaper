package com.reactivewallpaper;

import android.os.SystemClock;

/**
 * Turns raw FFT magnitudes into smoothed, auto-gained frequency bands plus a
 * bass level and a beat pulse, ready for drawing.
 *
 * Audio threads call {@link #submit}; the render thread calls {@link #update}
 * once per frame and then reads the public getters.
 */
final class AudioAnalyzer {
    private static final float MIN_FREQ = 35f;
    private static final float MAX_FREQ = 16000f;
    private static final float BASS_MAX_FREQ = 160f;
    private static final long STALE_MS = 400;

    private final Object lock = new Object();
    private float[] latestMags;
    private int latestRate;
    private long latestTime;

    private float[] work = new float[0];
    private float[] raw = new float[0];
    private float[] bands = new float[0];
    private float peak;
    private float noiseFloor = 0.03f;
    private float sensitivity = 1f;

    private float bass;
    private float bassAverage;
    private float beat;
    private float sinceBeat = 1f;
    private float level;
    private float high;

    AudioAnalyzer(int bandCount) {
        setBandCount(bandCount);
    }

    void setBandCount(int count) {
        if (count != bands.length) {
            bands = new float[count];
            raw = new float[count];
        }
    }

    void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
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
            if (latestMags == null || latestMags.length != mags.length) {
                latestMags = new float[mags.length];
            }
            System.arraycopy(mags, 0, latestMags, 0, mags.length);
            latestRate = sampleRate;
            latestTime = SystemClock.uptimeMillis();
        }
    }

    /** Advances smoothing by dt seconds using the most recent audio data. */
    void update(float dt) {
        int binCount = 0;
        int rate = 0;
        synchronized (lock) {
            if (latestMags != null && SystemClock.uptimeMillis() - latestTime < STALE_MS) {
                binCount = latestMags.length;
                if (work.length != binCount) {
                    work = new float[binCount];
                }
                System.arraycopy(latestMags, 0, work, 0, binCount);
                rate = latestRate;
            }
        }

        int n = bands.length;
        float bassRaw = 0f;
        int bassCount = 0;
        float frameMax = 0f;
        if (binCount > 1 && rate > 0) {
            float binHz = rate / (2f * binCount);
            float ratio = MAX_FREQ / MIN_FREQ;
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
                if (fHi <= BASS_MAX_FREQ || i == 0) {
                    bassRaw += v;
                    bassCount++;
                }
            }
            bassRaw /= Math.max(1, bassCount);
        } else {
            for (int i = 0; i < n; i++) {
                raw[i] = 0f;
            }
        }

        // Automatic gain: follow the loudest recent band, decaying slowly.
        peak = Math.max(frameMax, peak * (float) Math.pow(0.55, dt));
        float norm = Math.max(peak, noiseFloor);

        float riseRate = Math.min(1f, dt * 28f);
        float fallRate = Math.min(1f, dt * 7f);
        float total = 0f;
        float highTotal = 0f;
        int highStart = (int) (n * 0.35f);
        for (int i = 0; i < n; i++) {
            float target = Math.min(1f, raw[i] / norm * sensitivity);
            float current = bands[i];
            bands[i] = current + (target - current) * (target > current ? riseRate : fallRate);
            total += bands[i];
            if (i >= highStart) {
                highTotal += bands[i];
            }
        }
        level = n > 0 ? total / n : 0f;
        high = n > highStart ? highTotal / (n - highStart) : 0f;

        float bassNorm = Math.min(1f, bassRaw / norm);
        float bassTarget = Math.min(1f, bassNorm * sensitivity);
        bass += (bassTarget - bass) * (bassTarget > bass ? riseRate : fallRate);

        // Beat: bass jumps well above its recent average.
        sinceBeat += dt;
        if (bassNorm > 0.25f && bassNorm > bassAverage * 1.3f + 0.05f && sinceBeat > 0.2f) {
            beat = Math.min(1f, 0.6f + 0.4f * sensitivity * bassNorm);
            sinceBeat = 0f;
        }
        bassAverage += (bassNorm - bassAverage) * Math.min(1f, dt * 3f);
        beat *= (float) Math.pow(0.015, dt);
    }

    float[] bands() {
        return bands;
    }

    float bass() {
        return bass;
    }

    float beat() {
        return beat;
    }

    float level() {
        return level;
    }

    /** Average of the mid and high bands, 0..1. */
    float high() {
        return high;
    }

    /** True when nothing is moving, so the renderer can drop its frame rate. */
    boolean isIdle() {
        return level < 0.003f && beat < 0.003f && bass < 0.003f;
    }
}
