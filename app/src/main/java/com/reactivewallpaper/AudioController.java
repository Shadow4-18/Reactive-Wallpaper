package com.reactivewallpaper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

/** Starts the chosen audio source, falling back to the microphone if phone audio is unavailable. */
final class AudioController {
    private final Context context;
    private final AudioAnalyzer analyzer;
    private AudioInput input;
    private int activeSource = -1;

    AudioController(Context context, AudioAnalyzer analyzer) {
        this.context = context;
        this.analyzer = analyzer;
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    void start(int source) {
        if (input != null && activeSource == source) {
            return;
        }
        stop();
        if (!hasPermission(context)) {
            return;
        }
        activeSource = source;
        if (source == WallpaperSettings.SOURCE_SYSTEM) {
            input = new SystemAudioInput(analyzer);
            if (input.start()) {
                return;
            }
        }
        input = new MicAudioInput(analyzer);
        if (!input.start()) {
            input = null;
        }
    }

    void stop() {
        if (input != null) {
            input.stop();
            input = null;
        }
        activeSource = -1;
    }
}
