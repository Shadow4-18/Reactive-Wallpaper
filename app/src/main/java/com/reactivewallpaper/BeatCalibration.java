package com.reactivewallpaper;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures the sync delay: plays a click track through the current output,
 * detects each click the same way the wallpaper detects beats, and compares
 * that with when the user taps along to what they hear.
 */
final class BeatCalibration {
    static final int TOTAL_CLICKS = 20;
    static final int WARMUP_CLICKS = 4;
    private static final int SAMPLE_RATE = 44100;
    /** Not a multiple of the ~50 ms capture interval, so capture timing errors average out. */
    private static final int PERIOD_MS = 780;
    private static final int LEAD_MS = 600;
    private static final int MIN_PAIRS = 6;
    static final int MAX_DELAY_MS = 600;

    interface Callback {
        void onProgress(int clicksPlayed, int taps);

        void onFinished(Result result);
    }

    static final class Result {
        final boolean ok;
        final int delayMs;
        /** Typical distance of a tap from the median, a measure of consistency. */
        final int spreadMs;
        final String message;

        Result(boolean ok, int delayMs, int spreadMs, String message) {
            this.ok = ok;
            this.delayMs = delayMs;
            this.spreadMs = spreadMs;
            this.message = message;
        }
    }

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioAnalyzer analyzer = new AudioAnalyzer(32);
    private final List<Long> onsets = new ArrayList<>();
    private final List<Long> taps = new ArrayList<>();
    private SystemAudioInput input;
    private AudioTrack track;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private int totalFrames;
    private long lastTick;
    private long startTime;
    private long lastOnset;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            analyzer.update(Math.min(0.1f, (now - lastTick) / 1000f));
            lastTick = now;
            long beatTime = analyzer.lastBeatTime();
            if (beatTime != lastOnset && beatTime > 0) {
                onsets.add(beatTime);
                lastOnset = beatTime;
            }
            int head = track.getPlaybackHeadPosition();
            int leadFrames = SAMPLE_RATE * LEAD_MS / 1000;
            int periodFrames = SAMPLE_RATE * PERIOD_MS / 1000;
            int played = head < leadFrames ? 0 : Math.min(TOTAL_CLICKS, (head - leadFrames) / periodFrames + 1);
            callback.onProgress(played, taps.size());
            long expectedMs = totalFrames * 1000L / SAMPLE_RATE;
            if (head >= totalFrames || track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING
                    || now - startTime > expectedMs + 3000) {
                // Leave time for a late (e.g. Bluetooth) final click to be heard and tapped.
                handler.postDelayed(BeatCalibration.this::finish, MAX_DELAY_MS + 200);
                running = false;
                return;
            }
            handler.postDelayed(this, 8);
        }
    };

    BeatCalibration(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    /** Returns false if phone audio can't be captured on this device. */
    boolean start() {
        input = new SystemAudioInput(analyzer);
        if (!input.start()) {
            input = null;
            return false;
        }
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        if (audioManager != null) {
            // Pauses music apps so only the clicks are playing.
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        }

        short[] pcm = clickTrack();
        totalFrames = pcm.length;
        track = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        track.write(pcm, 0, pcm.length);
        running = true;
        lastTick = SystemClock.uptimeMillis();
        startTime = lastTick;
        track.play();
        handler.post(tick);
        return true;
    }

    /** @param eventTime the touch's MotionEvent.getEventTime(), in uptime ms */
    void tap(long eventTime) {
        taps.add(eventTime);
    }

    void cancel() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        release();
    }

    private void release() {
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
            track = null;
        }
        if (input != null) {
            input.stop();
            input = null;
        }
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    private void finish() {
        release();
        callback.onFinished(computeResult(onsets, taps, PERIOD_MS));
    }

    /** Pairs each tap with the click detection it followed and takes a robust average. */
    static Result computeResult(List<Long> onsets, List<Long> taps, int periodMs) {
        if (onsets.size() < TOTAL_CLICKS / 2) {
            return new Result(false, 0, 0,
                    "Couldn't pick up the clicks from phone audio. Turn the volume up and try again. "
                            + "If it keeps failing, sound effects like Dolby Atmos may be blocking it.");
        }
        List<Long> diffs = new ArrayList<>();
        for (long tap : taps) {
            // Latest detected click at or a little after the tap (taps can land slightly early).
            int match = -1;
            for (int i = 0; i < onsets.size(); i++) {
                if (onsets.get(i) <= tap + 120) {
                    match = i;
                }
            }
            if (match < WARMUP_CLICKS) {
                continue;
            }
            long diff = tap - onsets.get(match);
            if (diff < periodMs * 0.85f) {
                diffs.add(diff);
            }
        }
        if (diffs.size() < MIN_PAIRS) {
            return new Result(false, 0, 0,
                    "Not enough taps were counted. Tap once for every click you hear, after the first "
                            + WARMUP_CLICKS + " practice clicks.");
        }
        long median = median(diffs);
        List<Long> deviations = new ArrayList<>();
        for (long d : diffs) {
            deviations.add(Math.abs(d - median));
        }
        int spread = (int) median(deviations);
        int delay = (int) Math.max(0, Math.min(MAX_DELAY_MS, median));
        String message = spread > 60
                ? "Your taps were a bit uneven (±" + spread + " ms), so this may be off. Try again for a closer result."
                : "Taps were consistent (±" + spread + " ms).";
        return new Result(true, delay, spread, message);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** A bass thump (for beat detection) with a short tick on top (easy to hear and tap to). */
    static short[] clickTrack() {
        int lead = SAMPLE_RATE * LEAD_MS / 1000;
        int period = SAMPLE_RATE * PERIOD_MS / 1000;
        int tail = SAMPLE_RATE / 2;
        short[] pcm = new short[lead + period * (TOTAL_CLICKS - 1) + tail];
        int clickLength = SAMPLE_RATE * 150 / 1000;
        for (int c = 0; c < TOTAL_CLICKS; c++) {
            int start = lead + c * period;
            for (int i = 0; i < clickLength && start + i < pcm.length; i++) {
                double t = i / (double) SAMPLE_RATE;
                double thump = Math.sin(2 * Math.PI * 70 * t) * Math.exp(-t / 0.05) * 0.75;
                double tickSound = Math.sin(2 * Math.PI * 1800 * t) * Math.exp(-t / 0.008) * 0.35;
                pcm[start + i] = (short) (Math.max(-1, Math.min(1, thump + tickSound)) * 32000);
            }
        }
        return pcm;
    }
}
