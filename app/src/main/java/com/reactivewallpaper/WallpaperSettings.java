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
    static final int INTRO_OFF = 0;
    static final int INTRO_FADE = 1;
    static final int INTRO_ZOOM = 2;
    static final int INTRO_WAVE = 3;

    static final int STYLE_MIRROR = 4;
    static final int STYLE_LED = 5;
    static final int STYLE_ORB = 6;
    static final int STYLE_LINES = 7;

    /** Styles centered on the ring position (which can line up with artwork). */
    static boolean isRing(int style) {
        return style == STYLE_CIRCLE || style == STYLE_ORB;
    }

    static final int COLOR_AUTO = 0;
    static final int COLOR_RAINBOW = 1;
    static final int COLOR_CUSTOM = 2;

    static final int SOURCE_SYSTEM = 0;
    static final int SOURCE_MIC = 1;

    static final int PARTICLE_SPARKLES = 0;
    static final int PARTICLE_EMBERS = 1;
    static final int PARTICLE_STARS = 2;
    static final int PARTICLE_BUBBLES = 3;
    static final int PARTICLE_STREAKS = 4;

    static final int FIT_FILL = 0;
    static final int FIT_FIT = 1;
    static final int FIT_STRETCH = 2;
    static final int FIT_WIDTH = 3;
    static final int FIT_HEIGHT = 4;

    static final int EDGES_BLUR = 0;
    static final int EDGES_BLACK = 1;

    static final int MEDIA_IMAGE = 0;
    /** An animated GIF or WebP. */
    static final int MEDIA_ANIMATED = 1;
    static final int MEDIA_VIDEO = 2;

    static final int ART_FILL = 0;
    /** The cover as a card over a blurred copy of itself. */
    static final int ART_CARD = 1;

    static final int ROTATE_OFF = 0;
    static final int ROTATE_MINUTES = 1;
    static final int ROTATE_SONG = 2;
    static final int ROTATE_DOUBLE_TAP = 3;

    static final int TAP_OFF = 0;
    static final int TAP_RIPPLE = 1;
    static final int TAP_BURST = 2;
    static final int TAP_FLARE = 3;

    static final int SAVER_OFF = 0;
    static final int SAVER_AUTO = 1;
    static final int SAVER_ALWAYS = 2;

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
    int particleStyle = PARTICLE_SPARKLES;

    // Recolor.
    /** 0 .. 360 degrees added to the glow's hues. */
    float hueShift = 0f;
    /** 0 .. 1: how fast the hue keeps turning on its own. */
    float colorCycle = 0f;
    /** Recolor the whole picture, not only the glow and effects. */
    boolean recolorImage = false;

    // Album art: show the playing song's cover instead of the image when there is one.
    boolean albumArt = false;
    int albumArtStyle = ART_CARD;

    // Parallax.
    float tiltParallax = 0.6f;
    float swipeParallax = 0.5f;

    // Image motion.
    float pulse = 1f;
    float sway = 1f;
    float swaySpeed = 1f;
    float shake = 0f;
    float flash = 0.5f;
    /** Zoom kick on strong beats, 0 .. 2. */
    float camPunch = 0f;
    /** Small alternating rotation on strong beats, 0 .. 2. */
    float camTilt = 0f;
    /** What plays when the phone is unlocked (or the lock screen wakes). */
    int unlockIntro = INTRO_ZOOM;
    /** A wave of light and the battery level when a charger is plugged in. */
    boolean chargeEffect = true;
    /** A glow around the screen edges when a notification arrives (needs notification access). */
    boolean notifyPulse = false;
    /** Glow in the notifying app's color rather than the look's. */
    boolean notifyUseAppColor = true;
    /** Frame rate cap: 0 = the screen's own, else 30 or 60. */
    int maxFps = 0;
    /** What touching the home screen does. */
    int tapEffect = TAP_OFF;

    // Shader effects over the whole frame (Android 13+), 0 = off.
    float fxRipple = 0f;
    float fxSplit = 0f;
    float fxGlitch = 0f;
    float fxHaze = 0f;
    float fxGrain = 0f;
    float fxVignette = 0f;

    // Subject in front (when the picture has a subject mask).
    boolean subjectFront = true;
    /** How far the subject moves apart from the background, 0 .. 1. */
    float subjectDepth = 0.4f;
    /** A light glowing behind the subject with the bass, 0 .. 1. */
    float subjectAura = 0.5f;
    /** A neon line along the subject's outline that flares on the beat, 0 .. 1. */
    float subjectRim = 0f;
    /** Where on the visualizer's colors the rim takes its color, 0 .. 1; -1 = white. */
    float subjectRimColor = 0.15f;
    /** Draw the visualizer in front of the subject instead of behind it. */
    boolean vizFront = false;

    // Visualizer overlay.
    int style = STYLE_BARS;
    float vizHeight = 1f;
    float vizOpacity = 0.9f;
    int barCount = 48;
    /** Ring center as a fraction of screen height, 0.2 .. 0.8 */
    float circleY = 0.5f;
    /** Ring center across the screen, 0..1. */
    float circleX = 0.5f;
    /** Ring radius as a fraction of screen width, 0.15 .. 0.45 */
    float circleSize = 0.27f;
    /** Ring height over width: 1 is round; a stretched picture's ring is an oval. */
    float circleStretch = 1f;
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
    /** Learn the song's tempo and land beat effects on its grid, filling in missed beats. */
    boolean tempoSync = true;
    /** How long to hold the visuals back so they match what is heard, in ms. */
    int syncSpeakerMs = 60;
    int syncBluetoothMs = 220;
    int source = SOURCE_SYSTEM;

    // Battery (a property of the phone, like sync).
    int batterySaver = SAVER_AUTO;
    /** Auto saving starts at or below this battery level, in percent. */
    int saverPercent = 20;
    /** Auto saving also starts when the phone runs hot. */
    boolean saverWhenHot = true;
    /** While saving, screen effects (glitch, ripples, haze...) pause too. */
    boolean saverPausesEffects = true;
    /** Stop animating after this many minutes without sound; 0 = never. */
    int sleepMinutes = 0;

    /**
     * Bumped whenever the image or its painted glow mask changes, so running
     * wallpapers reload both.
     */
    long imageVersion;
    /** What kind of background {@link #imageVersion} refers to; travels with the image. */
    int mediaType = MEDIA_IMAGE;

    // Framing: how the picture sits on screen. Travels with the image, like its type.
    int fitMode = FIT_FILL;
    /** 0.5 .. 3, on top of the fit. */
    float imageZoom = 1f;
    /** -0.5 .. 0.5 of the screen width/height. */
    float imagePanX = 0f;
    float imagePanY = 0f;
    /** What shows where the picture doesn't cover the screen. */
    int edgeStyle = EDGES_BLUR;
    /** Zoom in just enough that motion effects never reveal the edges. */
    boolean hideEdges = true;

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

    private static final String KEY_LOCK_LOOK = "lockLookId";

    /** The saved look shown on the lock screen, or "" for the same as the home screen. */
    static String lockLookId(Context context) {
        return prefs(context).getString(KEY_LOCK_LOOK, "");
    }

    static void setLockLookId(Context context, String id) {
        prefs(context).edit().putString(KEY_LOCK_LOOK, id == null ? "" : id).apply();
    }

    private static final String KEY_FAVORITES = "favoriteLookIds";
    private static final String KEY_SHOWN_LOOK = "shownLookId";

    /** Starred looks, in the order they were starred (the looks widget shows the first four). */
    static java.util.List<String> favoriteIds(Context context) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String id : prefs(context).getString(KEY_FAVORITES, "").split("\n")) {
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    static void setFavorite(Context context, String id, boolean favorite) {
        java.util.List<String> ids = favoriteIds(context);
        ids.remove(id);
        if (favorite) {
            ids.add(id);
        }
        prefs(context).edit().putString(KEY_FAVORITES, String.join("\n", ids)).apply();
    }

    /**
     * A saved look picked from the looks widget, shown on the home screen in
     * place of the applied wallpaper; "" for the applied wallpaper (and its
     * rotation or time-of-day looks). Applying from the editor clears it.
     */
    static String shownLookId(Context context) {
        return prefs(context).getString(KEY_SHOWN_LOOK, "");
    }

    static void setShownLookId(Context context, String id) {
        prefs(context).edit().putString(KEY_SHOWN_LOOK, id == null ? "" : id).apply();
    }

    private static final String KEY_ROTATION_IDS = "rotationIds";
    private static final String KEY_ROTATION_MODE = "rotationMode";
    private static final String KEY_ROTATION_MINUTES = "rotationMinutes";

    /** Saved looks the home screen cycles through (empty = no rotation). */
    static java.util.List<String> rotationIds(Context context) {
        String joined = prefs(context).getString(KEY_ROTATION_IDS, "");
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String id : joined.split("\n")) {
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    static int rotationMode(Context context) {
        return prefs(context).getInt(KEY_ROTATION_MODE, ROTATE_OFF);
    }

    static int rotationMinutes(Context context) {
        return prefs(context).getInt(KEY_ROTATION_MINUTES, 30);
    }

    static void setRotation(Context context, java.util.List<String> ids, int mode, int minutes) {
        prefs(context).edit()
                .putString(KEY_ROTATION_IDS, String.join("\n", ids))
                .putInt(KEY_ROTATION_MODE, mode)
                .putInt(KEY_ROTATION_MINUTES, minutes)
                .apply();
    }

    private static final String KEY_TIME_OF_DAY = "timeOfDayIds";

    /** A saved look's id for each part of the day ("" = none), see {@link TimeOfDay}. */
    static String[] timeOfDayIds(Context context) {
        String[] ids = {"", "", "", ""};
        String[] stored = prefs(context).getString(KEY_TIME_OF_DAY, "").split("\n", -1);
        for (int i = 0; i < 4 && i < stored.length; i++) {
            ids[i] = stored[i];
        }
        return ids;
    }

    static void setTimeOfDay(Context context, String[] ids) {
        prefs(context).edit().putString(KEY_TIME_OF_DAY, String.join("\n", ids)).apply();
    }

    private static final String KEY_TOD_MODE = "timeOfDayMode";
    private static final String KEY_TOD_STARTS = "timeOfDayStarts";
    private static final String KEY_TOD_LAT = "timeOfDayLat";
    private static final String KEY_TOD_LON = "timeOfDayLon";

    /** How the parts of the day start: {@link TimeOfDay#TIMES_FIXED}, custom or by the sun. */
    static int timeOfDayMode(Context context) {
        return prefs(context).getInt(KEY_TOD_MODE, TimeOfDay.TIMES_FIXED);
    }

    /** The user's own start times (minutes after midnight). */
    static int[] customStarts(Context context) {
        String[] parts = prefs(context).getString(KEY_TOD_STARTS, "").split(",");
        int[] starts = TimeOfDay.FIXED.clone();
        if (parts.length == 4) {
            try {
                for (int i = 0; i < 4; i++) {
                    starts[i] = Math.max(0, Math.min(1439, Integer.parseInt(parts[i].trim())));
                }
            } catch (NumberFormatException e) {
                return TimeOfDay.FIXED.clone();
            }
        }
        return TimeOfDay.ordered(starts);
    }

    /** The saved approximate location for following the sun, or null. */
    static double[] sunLocation(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_TOD_LAT)) {
            return null;
        }
        return new double[] {p.getFloat(KEY_TOD_LAT, 0f), p.getFloat(KEY_TOD_LON, 0f)};
    }

    static void setTimeOfDayTimes(Context context, int mode, int[] custom, double[] location) {
        SharedPreferences.Editor e = prefs(context).edit()
                .putInt(KEY_TOD_MODE, mode)
                .putString(KEY_TOD_STARTS, custom[0] + "," + custom[1] + "," + custom[2] + "," + custom[3]);
        if (location != null) {
            // Rounded to about a kilometer: plenty for sunrise times.
            e.putFloat(KEY_TOD_LAT, Math.round(location[0] * 100) / 100f)
                    .putFloat(KEY_TOD_LON, Math.round(location[1] * 100) / 100f);
        }
        e.apply();
    }

    /** Today's start times for the parts of the day, as chosen. */
    static int[] timeOfDayStarts(Context context) {
        int mode = timeOfDayMode(context);
        if (mode == TimeOfDay.TIMES_CUSTOM) {
            return customStarts(context);
        }
        double[] where = sunLocation(context);
        if (mode == TimeOfDay.TIMES_SUN && where != null) {
            java.util.Calendar now = java.util.Calendar.getInstance();
            int offset = java.util.TimeZone.getDefault().getOffset(now.getTimeInMillis()) / 60000;
            return TimeOfDay.sunStarts(where[0], where[1], now.get(java.util.Calendar.DAY_OF_YEAR), offset);
        }
        return TimeOfDay.FIXED.clone();
    }

    private static final String KEY_DEVICE_DELAYS = "bluetoothDelays";

    /** A Bluetooth device's own calibrated delay, or null if it has none (use the general one). */
    static Integer deviceDelay(Context context, String device) {
        if (device == null) {
            return null;
        }
        try {
            org.json.JSONObject all = new org.json.JSONObject(prefs(context).getString(KEY_DEVICE_DELAYS, "{}"));
            return all.has(device) ? Math.max(0, Math.min(600, all.getInt(device))) : null;
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /** Every Bluetooth device's own delay, as saved (for diagnostics). */
    static String deviceDelays(Context context) {
        return prefs(context).getString(KEY_DEVICE_DELAYS, "{}");
    }

    /** Saves (or with null, forgets) a Bluetooth device's own delay. */
    static void setDeviceDelay(Context context, String device, Integer ms) {
        if (device == null) {
            return;
        }
        try {
            org.json.JSONObject all = new org.json.JSONObject(prefs(context).getString(KEY_DEVICE_DELAYS, "{}"));
            if (ms == null) {
                all.remove(device);
            } else {
                all.put(device, (int) ms);
            }
            prefs(context).edit().putString(KEY_DEVICE_DELAYS, all.toString()).apply();
        } catch (org.json.JSONException e) {
            prefs(context).edit().remove(KEY_DEVICE_DELAYS).apply();
        }
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
            ImageStore.copy(ImageStore.live(context), ImageStore.draft(context));
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
        s.particleStyle = p.getInt("particleStyle", s.particleStyle);
        s.hueShift = p.getFloat("hueShift", s.hueShift);
        s.colorCycle = p.getFloat("colorCycle", s.colorCycle);
        s.recolorImage = p.getBoolean("recolorImage", s.recolorImage);
        s.albumArt = p.getBoolean("albumArt", s.albumArt);
        s.albumArtStyle = p.getInt("albumArtStyle", s.albumArtStyle);
        s.tiltParallax = p.getFloat("tiltParallax", s.tiltParallax);
        s.swipeParallax = p.getFloat("swipeParallax", s.swipeParallax);

        s.pulse = p.getFloat("pulseAmount", p.getBoolean("pulse", true) ? s.pulse : 0f);
        s.sway = p.getFloat("sway", oldAmbient ? s.sway : 0f);
        s.swaySpeed = p.getFloat("swaySpeed", s.swaySpeed);
        s.shake = p.getFloat("shakeAmount", p.getBoolean("shake", false) ? 1f : 0f);
        s.flash = p.getFloat("flashAmount", p.getBoolean("flash", true) ? s.flash : 0f);
        s.camPunch = p.getFloat("camPunch", s.camPunch);
        s.camTilt = p.getFloat("camTilt", s.camTilt);
        s.tapEffect = p.getInt("tapEffect", s.tapEffect);
        s.unlockIntro = p.getInt("unlockIntro", s.unlockIntro);
        s.chargeEffect = p.getBoolean("chargeEffect", s.chargeEffect);
        s.notifyPulse = p.getBoolean("notifyPulse", s.notifyPulse);
        s.notifyUseAppColor = p.getBoolean("notifyUseAppColor", s.notifyUseAppColor);
        s.maxFps = p.getInt("maxFps", s.maxFps);
        s.saverPercent = Math.max(5, Math.min(50, p.getInt("saverPercent", s.saverPercent)));
        s.saverWhenHot = p.getBoolean("saverWhenHot", s.saverWhenHot);
        s.saverPausesEffects = p.getBoolean("saverPausesEffects", s.saverPausesEffects);
        s.fxRipple = p.getFloat("fxRipple", s.fxRipple);
        s.fxSplit = p.getFloat("fxSplit", s.fxSplit);
        s.fxGlitch = p.getFloat("fxGlitch", s.fxGlitch);
        s.fxHaze = p.getFloat("fxHaze", s.fxHaze);
        s.fxGrain = p.getFloat("fxGrain", s.fxGrain);
        s.fxVignette = p.getFloat("fxVignette", s.fxVignette);
        s.subjectFront = p.getBoolean("subjectFront", s.subjectFront);
        s.subjectDepth = p.getFloat("subjectDepth", s.subjectDepth);
        s.subjectAura = p.getFloat("subjectAura", s.subjectAura);
        s.subjectRim = p.getFloat("subjectRim", s.subjectRim);
        s.subjectRimColor = p.getFloat("subjectRimColor", s.subjectRimColor);
        s.vizFront = p.getBoolean("vizFront", s.vizFront);

        s.style = p.getInt("style", s.style);
        s.vizHeight = p.getFloat("vizHeight", p.getFloat("intensity", s.vizHeight));
        s.vizOpacity = p.getFloat("vizOpacity", s.vizOpacity);
        s.barCount = Math.max(MIN_BARS, Math.min(MAX_BARS, p.getInt("barCount", s.barCount)));
        s.circleY = p.getFloat("circleY", s.circleY);
        s.circleX = p.getFloat("circleX", s.circleX);
        s.circleSize = p.getFloat("circleSize", s.circleSize);
        s.circleStretch = Math.max(0.4f, Math.min(2.5f, p.getFloat("circleStretch", s.circleStretch)));
        s.colorMode = p.getInt("colorMode", s.colorMode);
        s.hue = p.getFloat("hue", s.hue);

        s.sensitivity = p.getFloat("sensitivity", s.sensitivity);
        s.smoothing = p.getFloat("smoothing", s.smoothing);
        s.beatSensitivity = p.getFloat("beatSensitivity", s.beatSensitivity);
        s.tempoSync = p.getBoolean("tempoSync", s.tempoSync);
        s.syncSpeakerMs = p.getInt("syncSpeakerMs", s.syncSpeakerMs);
        s.syncBluetoothMs = p.getInt("syncBluetoothMs", s.syncBluetoothMs);
        s.source = p.getInt("source", s.source);
        s.batterySaver = p.getInt("batterySaver", s.batterySaver);
        s.sleepMinutes = p.getInt("sleepMinutes", s.sleepMinutes);
        s.imageVersion = p.getLong("imageVersion", s.imageVersion);
        s.mediaType = p.getInt("mediaType", s.mediaType);
        s.fitMode = p.getInt("fitMode", s.fitMode);
        s.imageZoom = p.getFloat("imageZoom", s.imageZoom);
        s.imagePanX = p.getFloat("imagePanX", s.imagePanX);
        s.imagePanY = p.getFloat("imagePanY", s.imagePanY);
        s.edgeStyle = p.getInt("edgeStyle", s.edgeStyle);
        s.hideEdges = p.getBoolean("hideEdges", s.hideEdges);
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
        w.putInt("particleStyle", particleStyle);
        w.putFloat("hueShift", hueShift);
        w.putFloat("colorCycle", colorCycle);
        w.putBoolean("recolorImage", recolorImage);
        w.putBoolean("albumArt", albumArt);
        w.putInt("albumArtStyle", albumArtStyle);
        w.putFloat("tiltParallax", tiltParallax);
        w.putFloat("swipeParallax", swipeParallax);
        w.putFloat("pulseAmount", pulse);
        w.putFloat("sway", sway);
        w.putFloat("swaySpeed", swaySpeed);
        w.putFloat("shakeAmount", shake);
        w.putFloat("flashAmount", flash);
        w.putFloat("camPunch", camPunch);
        w.putFloat("camTilt", camTilt);
        w.putInt("tapEffect", tapEffect);
        w.putInt("unlockIntro", unlockIntro);
        w.putBoolean("chargeEffect", chargeEffect);
        w.putBoolean("notifyPulse", notifyPulse);
        w.putBoolean("notifyUseAppColor", notifyUseAppColor);
        w.putInt("maxFps", maxFps);
        w.putInt("saverPercent", saverPercent);
        w.putBoolean("saverWhenHot", saverWhenHot);
        w.putBoolean("saverPausesEffects", saverPausesEffects);
        w.putFloat("fxRipple", fxRipple);
        w.putFloat("fxSplit", fxSplit);
        w.putFloat("fxGlitch", fxGlitch);
        w.putFloat("fxHaze", fxHaze);
        w.putFloat("fxGrain", fxGrain);
        w.putFloat("fxVignette", fxVignette);
        w.putBoolean("subjectFront", subjectFront);
        w.putFloat("subjectDepth", subjectDepth);
        w.putFloat("subjectAura", subjectAura);
        w.putFloat("subjectRim", subjectRim);
        w.putFloat("subjectRimColor", subjectRimColor);
        w.putBoolean("vizFront", vizFront);
        w.putInt("style", style);
        w.putFloat("vizHeight", vizHeight);
        w.putFloat("vizOpacity", vizOpacity);
        w.putInt("barCount", barCount);
        w.putFloat("circleY", circleY);
        w.putFloat("circleX", circleX);
        w.putFloat("circleSize", circleSize);
        w.putFloat("circleStretch", circleStretch);
        w.putInt("colorMode", colorMode);
        w.putFloat("hue", hue);
        w.putFloat("sensitivity", sensitivity);
        w.putFloat("smoothing", smoothing);
        w.putFloat("beatSensitivity", beatSensitivity);
        w.putBoolean("tempoSync", tempoSync);
        w.putInt("syncSpeakerMs", syncSpeakerMs);
        w.putInt("syncBluetoothMs", syncBluetoothMs);
        w.putInt("source", source);
        w.putInt("batterySaver", batterySaver);
        w.putInt("sleepMinutes", sleepMinutes);
        w.putLong("imageVersion", imageVersion);
        w.putInt("mediaType", mediaType);
        w.putInt("fitMode", fitMode);
        w.putFloat("imageZoom", imageZoom);
        w.putFloat("imagePanX", imagePanX);
        w.putFloat("imagePanY", imagePanY);
        w.putInt("edgeStyle", edgeStyle);
        w.putBoolean("hideEdges", hideEdges);
    }

    /** Defaults for every effect, keeping the chosen image and audio source. */
    WallpaperSettings resetEffects() {
        return new WallpaperSettings().keepDeviceSettingsFrom(this);
    }

    /**
     * Copies what belongs to this phone rather than to a look: the image in use
     * plus the device settings (see {@link #copyDeviceSettingsFrom}).
     */
    WallpaperSettings keepDeviceSettingsFrom(WallpaperSettings current) {
        imageVersion = current.imageVersion;
        mediaType = current.mediaType;
        copyFramingFrom(current);
        copyDeviceSettingsFrom(current);
        return this;
    }

    /** Copies how the picture is framed, which belongs to the picture rather than the look. */
    void copyFramingFrom(WallpaperSettings other) {
        fitMode = other.fitMode;
        imageZoom = other.imageZoom;
        imagePanX = other.imagePanX;
        imagePanY = other.imagePanY;
        edgeStyle = other.edgeStyle;
        hideEdges = other.hideEdges;
    }

    /** Back to centered at the fit's own size, for a newly chosen picture. */
    void resetPosition() {
        imageZoom = 1f;
        imagePanX = 0f;
        imagePanY = 0f;
    }

    /**
     * Copies the audio source, sync delays (a property of the headphones) and
     * battery options. Returns true if anything changed.
     */
    boolean copyDeviceSettingsFrom(WallpaperSettings other) {
        boolean changed = source != other.source || syncSpeakerMs != other.syncSpeakerMs
                || syncBluetoothMs != other.syncBluetoothMs || batterySaver != other.batterySaver
                || sleepMinutes != other.sleepMinutes || maxFps != other.maxFps
                || notifyPulse != other.notifyPulse || notifyUseAppColor != other.notifyUseAppColor
                || saverPercent != other.saverPercent || saverWhenHot != other.saverWhenHot
                || saverPausesEffects != other.saverPausesEffects;
        saverPercent = other.saverPercent;
        saverWhenHot = other.saverWhenHot;
        saverPausesEffects = other.saverPausesEffects;
        source = other.source;
        maxFps = other.maxFps;
        notifyPulse = other.notifyPulse;
        notifyUseAppColor = other.notifyUseAppColor;
        syncSpeakerMs = other.syncSpeakerMs;
        syncBluetoothMs = other.syncBluetoothMs;
        batterySaver = other.batterySaver;
        sleepMinutes = other.sleepMinutes;
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
        applyTo(analyzer, bluetooth, delayed, null);
    }

    /**
     * @param deviceDelayMs the connected Bluetooth device's own calibrated delay,
     *     or null to use the general Bluetooth delay
     */
    void applyTo(AudioAnalyzer analyzer, boolean bluetooth, boolean delayed, Integer deviceDelayMs) {
        analyzer.setBandCount(barCount);
        analyzer.setSensitivity(sensitivity);
        analyzer.setSmoothing(smoothing);
        analyzer.setBeatSensitivity(beatSensitivity);
        analyzer.setTempoSync(tempoSync);
        // The microphone already hears the sound late, so it never needs holding back.
        int bluetoothMs = deviceDelayMs != null ? deviceDelayMs : syncBluetoothMs;
        analyzer.setDelayMs(delayed && source == SOURCE_SYSTEM ? (bluetooth ? bluetoothMs : syncSpeakerMs) : 0);
    }
}
