package com.reactivewallpaper;

import android.media.audiofx.Visualizer;
import android.util.Log;

/** Listens to whatever the phone is playing (music, videos) via the global output mix. */
final class SystemAudioInput implements AudioInput {
    private static final String TAG = "SystemAudioInput";

    private final AudioAnalyzer analyzer;
    private Visualizer visualizer;
    private float[] mags = new float[0];

    SystemAudioInput(AudioAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public boolean start() {
        stop();
        try {
            // Session 0 is the global output mix.
            Visualizer v = new Visualizer(0);
            visualizer = v;
            v.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.max(range[0], Math.min(1024, range[1])));
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer viz, byte[] waveform, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(Visualizer viz, byte[] fft, int samplingRate) {
                    onFft(fft, samplingRate);
                }
            }, Visualizer.getMaxCaptureRate(), false, true);
            analyzer.setNoiseFloor(0.03f);
            return v.setEnabled(true) == Visualizer.SUCCESS;
        } catch (RuntimeException e) {
            Log.w(TAG, "System audio capture unavailable", e);
            stop();
            return false;
        }
    }

    private void onFft(byte[] fft, int samplingRateMilliHz) {
        int half = fft.length / 2;
        if (mags.length != half) {
            mags = new float[half];
        }
        // Layout: [Re0, Re(n/2), Re1, Im1, Re2, Im2, ...]
        mags[0] = Math.abs(fft[0]) / 128f;
        for (int k = 1; k < half; k++) {
            float re = fft[2 * k];
            float im = fft[2 * k + 1];
            mags[k] = (float) Math.sqrt(re * re + im * im) / 128f;
        }
        analyzer.submit(mags, samplingRateMilliHz / 1000);
    }

    @Override
    public void stop() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
            } catch (RuntimeException ignored) {
            }
            visualizer.release();
            visualizer = null;
        }
    }
}
