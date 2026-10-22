package com.reactivewallpaper;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/** Listens to the microphone, for sound around the phone rather than what it plays. */
final class MicAudioInput implements AudioInput {
    private static final String TAG = "MicAudioInput";
    private static final int SAMPLE_RATE = 44100;
    private static final int FFT_SIZE = 1024;

    private final AudioAnalyzer analyzer;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;

    MicAudioInput(AudioAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public boolean start() {
        stop();
        try {
            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) {
                return false;
            }
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, FFT_SIZE * 4));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return false;
            }
            record = r;
            r.startRecording();
        } catch (RuntimeException e) {
            Log.w(TAG, "Microphone unavailable", e);
            stop();
            return false;
        }
        analyzer.setNoiseFloor(0.004f);
        running = true;
        AudioRecord r = record;
        thread = new Thread(() -> readLoop(r), "MicAudioInput");
        thread.start();
        return true;
    }

    private void readLoop(AudioRecord r) {
        short[] samples = new short[FFT_SIZE];
        float[] re = new float[FFT_SIZE];
        float[] im = new float[FFT_SIZE];
        float[] mags = new float[FFT_SIZE / 2];
        float[] window = new float[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) {
            window[i] = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
        }
        float scale = 4f / FFT_SIZE;
        while (running) {
            int read = 0;
            while (running && read < FFT_SIZE) {
                int got = r.read(samples, read, FFT_SIZE - read);
                if (got <= 0) {
                    return;
                }
                read += got;
            }
            for (int i = 0; i < FFT_SIZE; i++) {
                re[i] = samples[i] / 32768f * window[i];
                im[i] = 0f;
            }
            FFT.transform(re, im);
            for (int k = 0; k < mags.length; k++) {
                mags[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) * scale;
            }
            analyzer.submit(mags, SAMPLE_RATE);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (record != null) {
            try {
                record.stop();
            } catch (RuntimeException ignored) {
            }
        }
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        if (record != null) {
            record.release();
            record = null;
        }
    }
}
