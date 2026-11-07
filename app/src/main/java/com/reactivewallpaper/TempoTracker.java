package com.reactivewallpaper;

/**
 * Learns a song's tempo from the beats the analyzer detects, and predicts
 * where the next beats fall, so effects can land exactly on the beat even
 * when a hit is soft, masked or detected late.
 *
 * <p>Plain Java (times in milliseconds) so it can be tested without a phone.
 * Onsets go in with {@link #onset}; {@link #isLocked} says whether the
 * pattern is steady enough to trust, and {@link #gridIndexAt}/{@link #phaseAt}
 * place any moment on the beat grid.
 */
final class TempoTracker {
    /** Tempos considered, as beat periods: 60 .. 190 BPM. */
    static final int MIN_PERIOD = 316;
    static final int MAX_PERIOD = 1000;
    private static final int MAX_ONSETS = 24;
    /** Onsets older than this no longer count. */
    private static final long WINDOW_MS = 9000;
    /** How far an onset may sit from the grid and still count as on it. */
    private static final float TOLERANCE = 0.11f;

    private final long[] onsets = new long[MAX_ONSETS];
    private int count;
    private float period;
    private double anchor;
    private float confidence;

    /** Forgets everything (new song, silence). */
    void reset() {
        count = 0;
        period = 0f;
        confidence = 0f;
    }

    /** A detected beat at {@code timeMs}. */
    void onset(long timeMs) {
        if (count > 0 && timeMs - onsets[count - 1] > 2600) {
            // A long gap means the music stopped or changed: start over.
            reset();
        }
        if (count == MAX_ONSETS) {
            System.arraycopy(onsets, 1, onsets, 0, MAX_ONSETS - 1);
            count--;
        }
        onsets[count++] = timeMs;
        int first = 0;
        while (first < count && timeMs - onsets[first] > WINDOW_MS) {
            first++;
        }
        if (first > 0) {
            System.arraycopy(onsets, first, onsets, 0, count - first);
            count -= first;
        }
        estimate();
    }

    /** True when the tempo is known well enough to predict beats. */
    boolean isLocked() {
        return period > 0f && confidence >= 0.6f && count >= 6;
    }

    /** Also stops being locked once beats stop coming. */
    boolean isLockedAt(long timeMs) {
        return isLocked() && count > 0 && timeMs - onsets[count - 1] < Math.max(2200, 3.5f * period);
    }

    float bpm() {
        return period > 0f ? 60000f / period : 0f;
    }

    float period() {
        return period;
    }

    /** 0..1: the share of recent beats that sit on the grid. */
    float confidence() {
        return confidence;
    }

    /** Which beat of the grid {@code timeMs} is in (the one at or before it). */
    long gridIndexAt(long timeMs) {
        return (long) Math.floor((timeMs - anchor) / period);
    }

    /** When beat number {@code index} of the grid falls. */
    double gridTime(long index) {
        return anchor + index * (double) period;
    }

    /** 0 on a beat, rising to 1 just before the next one. */
    float phaseAt(long timeMs) {
        if (period <= 0f) {
            return 0f;
        }
        double p = (timeMs - anchor) / period;
        return (float) (p - Math.floor(p));
    }

    /**
     * Picks the period that best explains the gaps between recent onsets
     * (counting gaps of several beats, so missed beats don't throw it off),
     * then fits the grid's position to the onsets.
     */
    private void estimate() {
        if (count < 4) {
            confidence = 0f;
            return;
        }
        float bestScore = 0f;
        float best = 0f;
        // Try periods on a 4 ms grid; score how many onset gaps are whole multiples of it.
        for (int p = MIN_PERIOD; p <= MAX_PERIOD; p += 4) {
            float score = 0f;
            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count && j <= i + 4; j++) {
                    float beats = (onsets[j] - onsets[i]) / (float) p;
                    int whole = Math.round(beats);
                    if (whole < 1 || whole > 4) {
                        continue;
                    }
                    float off = Math.abs(beats - whole) / whole;
                    if (off < TOLERANCE) {
                        // Neighbors weigh most; the nearer the multiple, the more it counts.
                        score += (1f - off / TOLERANCE) / whole;
                    }
                }
            }
            // Mildly prefer common dance tempos, so 2x/0.5x ambiguity settles sensibly.
            float bpm = 60000f / p;
            score *= 1f - 0.15f * Math.min(1f, Math.abs(bpm - 120f) / 60f);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (best == 0f) {
            confidence = 0f;
            return;
        }
        // Refine to the average gap per beat between onsets that fit it.
        double sum = 0;
        int beatsTotal = 0;
        for (int i = 1; i < count; i++) {
            float beats = (onsets[i] - onsets[i - 1]) / best;
            int whole = Math.round(beats);
            if (whole >= 1 && whole <= 4 && Math.abs(beats - whole) / whole < TOLERANCE) {
                sum += onsets[i] - onsets[i - 1];
                beatsTotal += whole;
            }
        }
        float refined = beatsTotal > 0 ? (float) (sum / beatsTotal) : best;
        if (period > 0f && Math.abs(refined - period) / period < 0.04f) {
            // Same tempo as before: move gently so the grid doesn't wobble.
            period += (refined - period) * 0.35f;
        } else {
            period = refined;
        }

        // Grid position: average where the onsets sit within a beat (as an angle,
        // so values near 0 and near 1 average correctly), favoring recent ones.
        long last = onsets[count - 1];
        double sx = 0;
        double sy = 0;
        for (int i = 0; i < count; i++) {
            double frac = ((onsets[i] - last) / (double) period) % 1.0;
            double a = frac * 2 * Math.PI;
            double w = 1.0 + i / (double) count;
            sx += Math.cos(a) * w;
            sy += Math.sin(a) * w;
        }
        double shift = Math.atan2(sy, sx) / (2 * Math.PI);
        anchor = last + shift * period;

        int onGrid = 0;
        for (int i = 0; i < count; i++) {
            double p = (onsets[i] - anchor) / period;
            double off = Math.abs(p - Math.round(p));
            if (off < 0.12) {
                onGrid++;
            }
        }
        confidence = onGrid / (float) count;
    }
}
