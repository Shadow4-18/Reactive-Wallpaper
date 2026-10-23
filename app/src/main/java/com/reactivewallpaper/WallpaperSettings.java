package com.reactivewallpaper;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * User choices. Two copies are kept: the live one the wallpaper draws from, and
 * the editor's draft, which only reaches the wallpaper when the user applies it.
 * Effect amounts are multipliers where 0 turns the effect off and 1 is the
 * default look.
 */
final class WallpaperSettings {
    static final String PREFS_NAME = "wallpaper_settings";
    static final String DRAFT_PREFS_NAME = "editor_settings";

    static final int STYLE_NONE = 0;
    static final int STYLE_BARS = 1;
    static final int STYLE_CIRCLE = 2;
    static final int STYLE_WAVE = 3;

    static final int COLOR_AUTO = 0;
    static final int COLOR_RAINBOW = 1;
    static final int COLOR_CUSTOM = 2;

    static final int SOURCE_SYSTEM = 0;
    static final int SOURCE_MIC = 1;

    static final int MIN_BARS = 16;
    static final int MAX_BARS = 96;
    static final int MAX_SPARKLES = 200;
    static final float DEFAULT_COVERAGE = 0.6f;

    // Living image: the picture's own glowing colors.
    float bassGlow = 1.5f;
    float highGlow = 1.2f;
    float beatFlare = 1f;
    float bloom = 1.2f;
    /** 0 .. 1: how much of the image counts as "glowing". */
    float glowCoverage = DEFAULT_COVERAGE;
    boolean swapGlowColors = false;
    float breathe = 1f;
    float breatheSpeed = 1f;

    // Sparkles.
    /** 0 .. 1 of MAX_SPARKLES. */
    float sparkleAmount = 0.35f;
    float sparkleSize = 1f;
    float sparkleSpeed = 1f;
    float sparkleBrightness = 1f;
    float sparkleReact = 1f;

    // Image motion.
    float pulse = 1f;
    float sway = 1f;
    float swaySpeed = 1f;
    float shake = 0f;
    float flash = 0.5f;

    // Visualizer overlay.
    int style = STYLE_BARS;
    float vizHeight = 1f;
    float vizOpacity = 0.9f;
    int barCount = 48;
    /** Ring center as a fraction of screen height, 0.2 .. 0.8 */
    float circleY = 0.5f;
    /** Ring radius as a fraction of screen width, 0.15 .. 0.45 */
    float circleSize = 0.27f;
    int colorMode = COLOR_AUTO;
    /** 0 .. 360 */
    float hue = 190f;

    // Audio.
    /** 0.25 .. 3 */
    float sensitivity = 1f;
    /** 0 = snappy .. 1 = smooth */
    float smoothing = 0.4f;
    /** 0 = only strong hits .. 1 = nearly every bass hit */
    float beatSensitivity = 0.5f;
    /** How long to hold the visuals back so they match what is heard, in ms. */
    int syncSpeakerMs = 60;
    int syncBluetoothMs = 220;
    int source = SOURCE_SYSTEM;

    /** Bumped whenever a new image is chosen so running wallpapers reload it. */
    long imageVersion;

    /** Where settings are read from: the app's preferences or a saved look. */
    interface Reader {
        float getFloat(String key, float def);

        int getInt(String key, int def);

        boolean getBoolean(String key, boolean def);

        long getLong(String key, long def);
    }

    interface Writer {
        void putFloat(String key, float value);

        void putInt(String key, int value);

        void putBoolean(String key, boolean value);

        void putLong(String key, long value);
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The settings the wallpaper is showing. */
    static WallpaperSettings load(Context context) {
        return load(prefs(context));
    }

    /**
     * The editor's working copy. Starts as a copy of the live settings and image
     * the first time it is needed.
     */
    static WallpaperSettings loadDraft(Context context) {
        SharedPreferences draft = context.getSharedPreferences(DRAFT_PREFS_NAME, Context.MODE_PRIVATE);
        if (!draft.contains("imageVersion")) {
            WallpaperSettings live = load(context);
            if (ImageStore.hasImage(context)) {
                ImageStore.copy(ImageStore.file(context), ImageStore.draftFile(context));
            }
            live.saveDraft(context);
            return live;
        }
        return load(draft);
    }

    private static WallpaperSettings load(SharedPreferences p) {
        return read(new Reader() {
            @Override
            public float getFloat(String key, float def) {
                return p.getFloat(key, def);
            }

            @Override
            public int getInt(String key, int def) {
                return p.getInt(key, def);
            }

            @Override
            public boolean getBoolean(String key, boolean def) {
                return p.getBoolean(key, def);
            }

            @Override
            public long getLong(String key, long def) {
                return p.getLong(key, def);
            }
        });
    }

    static WallpaperSettings read(Reader p) {
        WallpaperSettings s = new WallpaperSettings();

        // Version 1.0/1.1 stored on/off switches; keep anything that was switched off, off.
        boolean oldGlow = p.getBoolean("glow", true);
        boolean oldAmbient = p.getBoolean("ambient", true);

        s.bassGlow = p.getFloat("bassGlow", oldGlow ? s.bassGlow : 0f);
        s.highGlow = p.getFloat("highGlow", oldGlow ? s.highGlow : 0f);
        s.beatFlare = p.getFloat("beatFlare", oldGlow ? s.beatFlare : 0f);
        s.bloom = p.getFloat("bloom", s.bloom);
        s.glowCoverage = p.getFloat("glowCoverage", s.glowCoverage);
        s.swapGlowColors = p.getBoolean("swapGlowColors", s.swapGlowColors);
        s.breathe = p.getFloat("breathe", oldAmbient ? s.breathe : 0f);
        s.breatheSpeed = p.getFloat("breatheSpeed", s.breatheSpeed);

        s.sparkleAmount = p.getFloat("sparkleAmount", oldAmbient ? s.sparkleAmount : 0f);
        s.sparkleSize = p.getFloat("sparkleSize", s.sparkleSize);
        s.sparkleSpeed = p.getFloat("sparkleSpeed", s.sparkleSpeed);
        s.sparkleBrightness = p.getFloat("sparkleBrightness", s.sparkleBrightness);
        s.sparkleReact = p.getFloat("sparkleReact", s.sparkleReact);

        s.pulse = p.getFloat("pulseAmount", p.getBoolean("pulse", true) ? s.pulse : 0f);
        s.sway = p.getFloat("sway", oldAmbient ? s.sway : 0f);
        s.swaySpeed = p.getFloat("swaySpeed", s.swaySpeed);
        s.shake = p.getFloat("shakeAmount", p.getBoolean("shake", false) ? 1f : 0f);
        s.flash = p.getFloat("flashAmount", p.getBoolean("flash", true) ? s.flash : 0f);

        s.style = p.getInt("style", s.style);
        s.vizHeight = p.getFloat("vizHeight", p.getFloat("intensity", s.vizHeight));
        s.vizOpacity = p.getFloat("vizOpacity", s.vizOpacity);
        s.barCount = Math.max(MIN_BARS, Math.min(MAX_BARS, p.getInt("barCount", s.barCount)));
        s.circleY = p.getFloat("circleY", s.circleY);
        s.circleSize = p.getFloat("circleSize", s.circleSize);
        s.colorMode = p.getInt("colorMode", s.colorMode);
        s.hue = p.getFloat("hue", s.hue);

        s.sensitivity = p.getFloat("sensitivity", s.sensitivity);
        s.smoothing = p.getFloat("smoothing", s.smoothing);
        s.beatSensitivity = p.getFloat("beatSensitivity", s.beatSensitivity);
        s.syncSpeakerMs = p.getInt("syncSpeakerMs", s.syncSpeakerMs);
        s.syncBluetoothMs = p.getInt("syncBluetoothMs", s.syncBluetoothMs);
        s.source = p.getInt("source", s.source);
        s.imageVersion = p.getLong("imageVersion", s.imageVersion);
        return s;
    }

    /** Makes these the settings the wallpaper shows. */
    void save(Context context) {
        save(prefs(context));
    }

    void saveDraft(Context context) {
        save(context.getSharedPreferences(DRAFT_PREFS_NAME, Context.MODE_PRIVATE));
    }

    private void save(SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit();
        write(new Writer() {
            @Override
            public void putFloat(String key, float value) {
                editor.putFloat(key, value);
            }

            @Override
            public void putInt(String key, int value) {
                editor.putInt(key, value);
            }

            @Override
            public void putBoolean(String key, boolean value) {
                editor.putBoolean(key, value);
            }

            @Override
            public void putLong(String key, long value) {
                editor.putLong(key, value);
            }
        });
        editor.apply();
    }

    void write(Writer w) {
        w.putFloat("bassGlow", bassGlow);
        w.putFloat("highGlow", highGlow);
        w.putFloat("beatFlare", beatFlare);
        w.putFloat("bloom", bloom);
        w.putFloat("glowCoverage", glowCoverage);
        w.putBoolean("swapGlowColors", swapGlowColors);
        w.putFloat("breathe", breathe);
        w.putFloat("breatheSpeed", breatheSpeed);
        w.putFloat("sparkleAmount", sparkleAmount);
        w.putFloat("sparkleSize", sparkleSize);
        w.putFloat("sparkleSpeed", sparkleSpeed);
        w.putFloat("sparkleBrightness", sparkleBrightness);
        w.putFloat("sparkleReact", sparkleReact);
        w.putFloat("pulseAmount", pulse);
        w.putFloat("sway", sway);
        w.putFloat("swaySpeed", swaySpeed);
        w.putFloat("shakeAmount", shake);
        w.putFloat("flashAmount", flash);
        w.putInt("style", style);
        w.putFloat("vizHeight", vizHeight);
        w.putFloat("vizOpacity", vizOpacity);
        w.putInt("barCount", barCount);
        w.putFloat("circleY", circleY);
        w.putFloat("circleSize", circleSize);
        w.putInt("colorMode", colorMode);
        w.putFloat("hue", hue);
        w.putFloat("sensitivity", sensitivity);
        w.putFloat("smoothing", smoothing);
        w.putFloat("beatSensitivity", beatSensitivity);
        w.putInt("syncSpeakerMs", syncSpeakerMs);
        w.putInt("syncBluetoothMs", syncBluetoothMs);
        w.putInt("source", source);
        w.putLong("imageVersion", imageVersion);
    }

    /** Defaults for every effect, keeping the chosen image and audio source. */
    WallpaperSettings resetEffects() {
        return new WallpaperSettings().keepDeviceSettingsFrom(this);
    }

    /**
     * Copies what belongs to this phone rather than to a look: the image in use,
     * the audio source and the sync delays (a property of the headphones).
     */
    WallpaperSettings keepDeviceSettingsFrom(WallpaperSettings current) {
        imageVersion = current.imageVersion;
        copyAudioRoutingFrom(current);
        return this;
    }

    /** Copies the audio source and sync delays. Returns true if anything changed. */
    boolean copyAudioRoutingFrom(WallpaperSettings other) {
        boolean changed = source != other.source || syncSpeakerMs != other.syncSpeakerMs
                || syncBluetoothMs != other.syncBluetoothMs;
        source = other.source;
        syncSpeakerMs = other.syncSpeakerMs;
        syncBluetoothMs = other.syncBluetoothMs;
        return changed;
    }

    private Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        write(new Writer() {
            @Override
            public void putFloat(String key, float value) {
                map.put(key, value);
            }

            @Override
            public void putInt(String key, int value) {
                map.put(key, value);
            }

            @Override
            public void putBoolean(String key, boolean value) {
                map.put(key, value);
            }

            @Override
            public void putLong(String key, long value) {
                map.put(key, value);
            }
        });
        return map;
    }

    /** True if every setting (including which image) matches. */
    boolean sameAs(WallpaperSettings other) {
        return toMap().equals(other.toMap());
    }

    /** An independent copy, safe to hand to a background thread. */
    WallpaperSettings copy() {
        Map<String, Object> map = toMap();
        return read(new Reader() {
            @Override
            public float getFloat(String key, float def) {
                Object v = map.get(key);
                return v instanceof Float ? (Float) v : def;
            }

            @Override
            public int getInt(String key, int def) {
                Object v = map.get(key);
                return v instanceof Integer ? (Integer) v : def;
            }

            @Override
            public boolean getBoolean(String key, boolean def) {
                Object v = map.get(key);
                return v instanceof Boolean ? (Boolean) v : def;
            }

            @Override
            public long getLong(String key, long def) {
                Object v = map.get(key);
                return v instanceof Long ? (Long) v : def;
            }
        });
    }

    /** Applies the audio tuning to an analyzer for the current output route. */
    void applyTo(AudioAnalyzer analyzer, boolean bluetooth, boolean delayed) {
        analyzer.setBandCount(barCount);
        analyzer.setSensitivity(sensitivity);
        analyzer.setSmoothing(smoothing);
        analyzer.setBeatSensitivity(beatSensitivity);
        // The microphone already hears the sound late, so it never needs holding back.
        analyzer.setDelayMs(delayed && source == SOURCE_SYSTEM ? (bluetooth ? syncBluetoothMs : syncSpeakerMs) : 0);
    }
}
