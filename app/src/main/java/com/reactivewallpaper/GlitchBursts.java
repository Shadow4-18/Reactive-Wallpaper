package com.reactivewallpaper;

import java.util.Random;

/**
 * Times the glitch effect: each beat starts a burst with its own pattern
 * (a random seed the shader builds everything from), length and flicker
 * speed, and the odd small burst comes between beats, so the glitch never
 * settles into a recognizable repeat. Plain Java, so it can be tested.
 */
final class GlitchBursts {
    private final Random random;
    private float age = 1f;
    private float length;
    private float power;
    private float rate;
    private float baseSeed;
    private float lastBeat;
    private float strength;
    private float seed;

    GlitchBursts(Random random) {
        this.random = random;
    }

    /**
     * Advances by {@code dt} seconds.
     *
     * @param beat the analyzer's beat pulse, 0..1
     * @param amount the look's glitch setting, 0 = off
     */
    void update(float dt, float beat, float amount) {
        boolean hit = beat > 0.35f && beat > lastBeat + 0.15f;
        lastBeat = beat;
        if (amount <= 0f) {
            strength = 0f;
            age = 1f;
            length = 0f;
            return;
        }
        if (hit) {
            start(amount * (0.55f + 0.45f * beat), 0.12f + 0.3f * random.nextFloat());
        } else if (age >= length && random.nextFloat() < dt * 0.25f * amount) {
            // A small, short one between beats.
            start(amount * (0.2f + 0.2f * random.nextFloat()), 0.06f + 0.1f * random.nextFloat());
        }
        age += dt;
        if (age >= length) {
            strength = 0f;
            return;
        }
        float t = age / length;
        strength = power * (float) Math.sqrt(1f - t);
        // Within a burst the pattern flickers to new ones at the burst's own speed.
        seed = baseSeed + (float) Math.floor(age * rate) * 17.31f;
    }

    private void start(float burstPower, float burstLength) {
        age = 0f;
        length = burstLength;
        power = Math.min(1.5f, burstPower);
        rate = 10f + 22f * random.nextFloat();
        baseSeed = random.nextFloat() * 900f;
    }

    /** How strong the glitch is right now (0 = none). */
    float strength() {
        return strength;
    }

    /** The current pattern: every value gives a different arrangement of slices and blocks. */
    float seed() {
        return seed;
    }

    boolean isActive() {
        return age < length;
    }
}
