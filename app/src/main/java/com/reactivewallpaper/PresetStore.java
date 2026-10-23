package com.reactivewallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Saved "looks": a full set of effect settings, optionally with the image they
 * were made for. A few built-in looks are provided as starting points.
 */
final class PresetStore {
    private static final String TAG = "PresetStore";
    private static final String PREFS_NAME = "presets";
    private static final String KEY_LIST = "list";

    static final class Preset {
        final String id;
        final String name;
        final boolean builtIn;
        final boolean hasImage;
        final JSONObject look;

        Preset(String id, String name, boolean builtIn, boolean hasImage, JSONObject look) {
            this.id = id;
            this.name = name;
            this.builtIn = builtIn;
            this.hasImage = hasImage;
            this.look = look;
        }
    }

    private PresetStore() {}

    static List<Preset> all(Context context) {
        List<Preset> presets = builtIns();
        presets.addAll(userPresets(context));
        return presets;
    }

    /** Saves the current look. Call off the main thread when including the image. */
    static Preset save(Context context, String name, WallpaperSettings settings, boolean includeImage) {
        String id = UUID.randomUUID().toString();
        boolean hasImage = includeImage && ImageStore.hasDraftImage(context)
                && ImageStore.copy(ImageStore.draftFile(context), imageFile(context, id));
        Preset preset = new Preset(id, name, false, hasImage, toJson(settings));
        List<Preset> list = userPresets(context);
        list.add(preset);
        store(context, list);
        return preset;
    }

    /** Replaces a saved look's settings (and image, if it has one) with the current ones. */
    static void update(Context context, Preset preset, WallpaperSettings settings) {
        if (preset.hasImage && ImageStore.hasDraftImage(context)) {
            ImageStore.copy(ImageStore.draftFile(context), imageFile(context, preset.id));
        }
        replace(context, preset, new Preset(preset.id, preset.name, false, preset.hasImage, toJson(settings)));
    }

    static void rename(Context context, Preset preset, String name) {
        replace(context, preset, new Preset(preset.id, name, false, preset.hasImage, preset.look));
    }

    static void delete(Context context, Preset preset) {
        List<Preset> list = userPresets(context);
        list.removeIf(p -> p.id.equals(preset.id));
        store(context, list);
        imageFile(context, preset.id).delete();
    }

    /**
     * Loads the look into the editor: its image (if any) and settings become the
     * draft, keeping this phone's sync and audio source. The wallpaper itself
     * changes only when the user applies. Call off the main thread.
     */
    static WallpaperSettings apply(Context context, Preset preset, WallpaperSettings current) {
        WallpaperSettings next = fromJson(preset.look).keepDeviceSettingsFrom(current);
        if (preset.hasImage && ImageStore.copy(imageFile(context, preset.id), ImageStore.draftFile(context))) {
            next.imageVersion = System.currentTimeMillis();
        }
        next.saveDraft(context);
        return next;
    }

    /** The look's own image, or null when it uses whatever image is current. */
    static File image(Context context, Preset preset) {
        return preset.hasImage ? imageFile(context, preset.id) : null;
    }

    private static File imageFile(Context context, String id) {
        File dir = new File(context.getFilesDir(), "presets");
        dir.mkdirs();
        return new File(dir, id + ".img");
    }

    private static void replace(Context context, Preset old, Preset updated) {
        List<Preset> list = userPresets(context);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(old.id)) {
                list.set(i, updated);
            }
        }
        store(context, list);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static List<Preset> userPresets(Context context) {
        List<Preset> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_LIST, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                list.add(new Preset(o.getString("id"), o.getString("name"), false,
                        o.optBoolean("hasImage"), o.getJSONObject("look")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Could not read saved looks", e);
        }
        return list;
    }

    private static void store(Context context, List<Preset> list) {
        JSONArray array = new JSONArray();
        try {
            for (Preset p : list) {
                array.put(new JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("hasImage", p.hasImage)
                        .put("look", p.look));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply();
    }

    static JSONObject toJson(WallpaperSettings settings) {
        JSONObject o = new JSONObject();
        settings.write(new WallpaperSettings.Writer() {
            @Override
            public void putFloat(String key, float value) {
                put(key, (double) value);
            }

            @Override
            public void putInt(String key, int value) {
                put(key, value);
            }

            @Override
            public void putBoolean(String key, boolean value) {
                put(key, value);
            }

            @Override
            public void putLong(String key, long value) {
                put(key, value);
            }

            private void put(String key, Object value) {
                try {
                    o.put(key, value);
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        return o;
    }

    static WallpaperSettings fromJson(JSONObject o) {
        return WallpaperSettings.read(new WallpaperSettings.Reader() {
            @Override
            public float getFloat(String key, float def) {
                return (float) o.optDouble(key, def);
            }

            @Override
            public int getInt(String key, int def) {
                return o.optInt(key, def);
            }

            @Override
            public boolean getBoolean(String key, boolean def) {
                return o.optBoolean(key, def);
            }

            @Override
            public long getLong(String key, long def) {
                return o.optLong(key, def);
            }
        });
    }

    private static List<Preset> builtIns() {
        List<Preset> list = new ArrayList<>();

        WallpaperSettings glow = new WallpaperSettings();
        glow.style = WallpaperSettings.STYLE_NONE;
        glow.flash = 0f;
        list.add(builtIn("neon", "Neon glow", glow));

        WallpaperSettings halo = new WallpaperSettings();
        halo.style = WallpaperSettings.STYLE_CIRCLE;
        halo.circleY = 0.594f;
        halo.circleSize = 0.36f;
        halo.barCount = 72;
        halo.vizHeight = 0.8f;
        halo.flash = 0f;
        halo.sway = 0.5f;
        list.add(builtIn("halo", "Halo ring", halo));

        WallpaperSettings club = new WallpaperSettings();
        club.bassGlow = 2.5f;
        club.beatFlare = 2.5f;
        club.flash = 1.2f;
        club.shake = 0.8f;
        club.pulse = 1.4f;
        club.sparkleAmount = 0.6f;
        club.sparkleReact = 1.8f;
        club.smoothing = 0.15f;
        club.colorMode = WallpaperSettings.COLOR_RAINBOW;
        list.add(builtIn("club", "Club", club));

        WallpaperSettings calm = new WallpaperSettings();
        calm.bassGlow = 0.6f;
        calm.highGlow = 0.5f;
        calm.beatFlare = 0.3f;
        calm.breathe = 1.3f;
        calm.breatheSpeed = 0.6f;
        calm.sparkleAmount = 0.5f;
        calm.sparkleSpeed = 0.5f;
        calm.sway = 1.3f;
        calm.swaySpeed = 0.6f;
        calm.pulse = 0.3f;
        calm.flash = 0f;
        calm.style = WallpaperSettings.STYLE_NONE;
        calm.smoothing = 0.8f;
        list.add(builtIn("calm", "Calm", calm));
        return list;
    }

    private static Preset builtIn(String id, String name, WallpaperSettings look) {
        return new Preset("builtin:" + id, name, true, false, toJson(look));
    }
}
