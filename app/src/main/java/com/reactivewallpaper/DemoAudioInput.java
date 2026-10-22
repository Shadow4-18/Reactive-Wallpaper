package com.reactivewallpaper;

import java.util.Random;

/** A synthetic 120 BPM groove, so effects can be tuned in the preview without playing music. */
final class DemoAudioInput implements AudioInput {
    private static final int SAMPLE_RATE = 44100;
    private static final int BINS = 512;
    private static final float BEAT_SECONDS = 0.5f;

    private final AudioAnalyzer analyzer;
    private Thread thread;
    private volatile boolean running;

    DemoAudioInput(AudioAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public boolean start() {
        stop();
        analyzer.setNoiseFloor(0.03f);
        running = true;
        thread = new Thread(this::loop, "DemoAudioInput");
        thread.start();
        return true;
    }

    private void loop() {
        float[] mags = new float[BINS];
        Random random = new Random();
        long start = System.nanoTime();
        float binHz = SAMPLE_RATE / (2f * BINS);
        while (running) {
            float t = (System.nanoTime() - start) / 1e9f;
            float sinceKick = t % BEAT_SECONDS;
            float sinceSnare = (t + BEAT_SECONDS) % (BEAT_SECONDS * 2f);
            float sinceHat = t % (BEAT_SECONDS / 2f);
            float kick = (float) Math.exp(-sinceKick * 9f);
            float snare = (float) Math.exp(-sinceSnare * 12f);
            float hat = (float) Math.exp(-sinceHat * 30f);
            float melody = 0.5f + 0.5f * (float) Math.sin(t * 1.7f);
            for (int k = 0; k < BINS; k++) {
                float f = k * binHz;
                float v = 0.01f + 0.01f * random.nextFloat();
                if (f < 150f) {
                    v += 0.9f * kick;
                }
                if (f > 200f && f < 2500f) {
                    v += 0.12f * melody * (0.6f + 0.4f * random.nextFloat()) + 0.25f * snare * random.nextFloat();
                }
                if (f > 5000f) {
                    v += 0.08f * hat * random.nextFloat();
                }
                mags[k] = v;
            }
            analyzer.submit(mags, SAMPLE_RATE);
            try {
                Thread.sleep(23);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }
}
