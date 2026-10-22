package com.reactivewallpaper;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User choices, shared between the editor screen and the live wallpaper.
 * Effect amounts are multipliers where 0 turns the effect off and 1 is the
 * default look.
 */
final class WallpaperSettings {
    static final String PREFS_NAME = "wallpaper_settings";

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
    int source = SOURCE_SYSTEM;

    /** Bumped whenever a new image is chosen so running wallpapers reload it. */
    long imageVersion;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static WallpaperSettings load(Context context) {
        SharedPreferences p = prefs(context);
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
        s.source = p.getInt("source", s.source);
        s.imageVersion = p.getLong("imageVersion", s.imageVersion);
        return s;
    }

    void save(Context context) {
        prefs(context).edit()
                .putFloat("bassGlow", bassGlow)
                .putFloat("highGlow", highGlow)
                .putFloat("beatFlare", beatFlare)
                .putFloat("bloom", bloom)
                .putFloat("glowCoverage", glowCoverage)
                .putBoolean("swapGlowColors", swapGlowColors)
                .putFloat("breathe", breathe)
                .putFloat("breatheSpeed", breatheSpeed)
                .putFloat("sparkleAmount", sparkleAmount)
                .putFloat("sparkleSize", sparkleSize)
                .putFloat("sparkleSpeed", sparkleSpeed)
                .putFloat("sparkleBrightness", sparkleBrightness)
                .putFloat("sparkleReact", sparkleReact)
                .putFloat("pulseAmount", pulse)
                .putFloat("sway", sway)
                .putFloat("swaySpeed", swaySpeed)
                .putFloat("shakeAmount", shake)
                .putFloat("flashAmount", flash)
                .putInt("style", style)
                .putFloat("vizHeight", vizHeight)
                .putFloat("vizOpacity", vizOpacity)
                .putInt("barCount", barCount)
                .putFloat("circleY", circleY)
                .putFloat("circleSize", circleSize)
                .putInt("colorMode", colorMode)
                .putFloat("hue", hue)
                .putFloat("sensitivity", sensitivity)
                .putInt("source", source)
                .putLong("imageVersion", imageVersion)
                .apply();
    }

    /** Defaults for every effect, keeping the chosen image and audio source. */
    WallpaperSettings resetEffects() {
        WallpaperSettings fresh = new WallpaperSettings();
        fresh.imageVersion = imageVersion;
        fresh.source = source;
        return fresh;
    }
}
