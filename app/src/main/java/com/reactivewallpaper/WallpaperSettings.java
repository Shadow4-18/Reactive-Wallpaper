package com.reactivewallpaper;

import android.content.Context;
import android.content.SharedPreferences;

/** User choices, shared between the editor screen and the live wallpaper. */
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

    int style = STYLE_BARS;
    boolean pulse = true;
    boolean flash = true;
    boolean shake = false;
    /** 0.25 .. 3 */
    float sensitivity = 1f;
    /** 0.25 .. 2 */
    float intensity = 1f;
    int barCount = 48;
    int colorMode = COLOR_AUTO;
    /** 0 .. 360 */
    float hue = 190f;
    int source = SOURCE_SYSTEM;
    /** Ring center as a fraction of screen height, 0.2 .. 0.8 */
    float circleY = 0.5f;
    /** Ring radius as a fraction of screen width, 0.15 .. 0.45 */
    float circleSize = 0.27f;
    /** Bumped whenever a new image is chosen so running wallpapers reload it. */
    long imageVersion;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static WallpaperSettings load(Context context) {
        SharedPreferences p = prefs(context);
        WallpaperSettings s = new WallpaperSettings();
        s.style = p.getInt("style", s.style);
        s.pulse = p.getBoolean("pulse", s.pulse);
        s.flash = p.getBoolean("flash", s.flash);
        s.shake = p.getBoolean("shake", s.shake);
        s.sensitivity = p.getFloat("sensitivity", s.sensitivity);
        s.intensity = p.getFloat("intensity", s.intensity);
        s.barCount = Math.max(MIN_BARS, Math.min(MAX_BARS, p.getInt("barCount", s.barCount)));
        s.colorMode = p.getInt("colorMode", s.colorMode);
        s.hue = p.getFloat("hue", s.hue);
        s.source = p.getInt("source", s.source);
        s.circleY = p.getFloat("circleY", s.circleY);
        s.circleSize = p.getFloat("circleSize", s.circleSize);
        s.imageVersion = p.getLong("imageVersion", s.imageVersion);
        return s;
    }

    void save(Context context) {
        prefs(context).edit()
                .putInt("style", style)
                .putBoolean("pulse", pulse)
                .putBoolean("flash", flash)
                .putBoolean("shake", shake)
                .putFloat("sensitivity", sensitivity)
                .putFloat("intensity", intensity)
                .putInt("barCount", barCount)
                .putInt("colorMode", colorMode)
                .putFloat("hue", hue)
                .putInt("source", source)
                .putFloat("circleY", circleY)
                .putFloat("circleSize", circleSize)
                .putLong("imageVersion", imageVersion)
                .apply();
    }
}
