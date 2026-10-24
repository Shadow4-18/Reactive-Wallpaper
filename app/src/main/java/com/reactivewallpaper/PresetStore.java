package com.reactivewallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.graphics.BitmapFactory;

import com.reactivewallpaper.ImageStore.Picture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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

    /** The look with this id, or null if it no longer exists. */
    static Preset find(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Preset p : all(context)) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** Notified when saved looks change (e.g. so the lock screen picks up an edited look). */
    static void registerListener(Context context, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(context).registerOnSharedPreferenceChangeListener(l);
    }

    static void unregisterListener(Context context, SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(l);
    }

    // ---- Export / import (.resona files: a zip of looks with their images) ----

    private static final String MANIFEST = "resona.json";
    private static final int FORMAT_VERSION = 1;
    private static final long MAX_MANIFEST_BYTES = 1L << 20;
    private static final long MAX_IMAGE_BYTES = 60L << 20;
    private static final long MAX_MEDIA_BYTES = 300L << 20;
    private static final int MAX_LOOKS = 200;

    /** Writes the looks (with their images and painted masks) as a .resona file. */
    static void export(Context context, List<Preset> presets, OutputStream out) throws IOException {
        JSONArray looks = new JSONArray();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < presets.size(); i++) {
                Preset p = presets.get(i);
                boolean withImage = p.hasImage && imageFile(context, p.id).length() > 0;
                try {
                    looks.put(new JSONObject()
                            .put("name", p.name)
                            .put("hasImage", withImage)
                            .put("look", p.look));
                } catch (JSONException e) {
                    throw new IOException(e);
                }
                if (withImage) {
                    putFile(zip, "looks/" + i + "/image", imageFile(context, p.id));
                    if (maskFile(context, p.id).length() > 0) {
                        putFile(zip, "looks/" + i + "/mask.png", maskFile(context, p.id));
                    }
                    File media = picture(context, p.id).media;
                    if (media.length() > 0) {
                        putFile(zip, "looks/" + i + "/media", media);
                    }
                }
            }
            JSONObject manifest = new JSONObject();
            try {
                manifest.put("format", FORMAT_VERSION).put("looks", looks);
            } catch (JSONException e) {
                throw new IOException(e);
            }
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void putFile(ZipOutputStream zip, String name, File file) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                zip.write(buffer, 0, n);
            }
        }
        zip.closeEntry();
    }

    /**
     * Adds the looks in a .resona file as new saved looks and returns how many
     * were added. The file comes from outside the app, so sizes are capped,
     * entry names are only matched (never used as paths) and images must decode.
     * Call off the main thread.
     */
    static int importFrom(Context context, InputStream in) throws IOException {
        File work = new File(context.getCacheDir(), "import-" + UUID.randomUUID());
        if (!work.mkdirs()) {
            throw new IOException("Could not create a temporary folder");
        }
        try {
            String manifestText = null;
            try (ZipInputStream zip = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.equals(MANIFEST)) {
                        manifestText = new String(readLimited(zip, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8);
                    } else {
                        File target = importTarget(work, name);
                        if (target != null) {
                            long limit = target.getName().endsWith(".media") ? MAX_MEDIA_BYTES : MAX_IMAGE_BYTES;
                            try (OutputStream out = new FileOutputStream(target)) {
                                copyLimited(zip, out, limit);
                            }
                        }
                    }
                }
            }
            if (manifestText == null) {
                throw new IOException("Not a Resona looks file");
            }
            JSONArray looks;
            try {
                looks = new JSONObject(manifestText).getJSONArray("looks");
            } catch (JSONException e) {
                throw new IOException("Damaged Resona looks file", e);
            }
            List<Preset> list = userPresets(context);
            int added = 0;
            for (int i = 0; i < looks.length() && i < MAX_LOOKS; i++) {
                JSONObject o = looks.optJSONObject(i);
                if (o == null || o.optJSONObject("look") == null) {
                    continue;
                }
                String id = UUID.randomUUID().toString();
                String name = o.optString("name", "Imported look").trim();
                if (name.isEmpty()) {
                    name = "Imported look";
                }
                name = name.length() > 60 ? name.substring(0, 60) : name;
                // Round-trip through settings so only known values are kept.
                WallpaperSettings settings = fromJson(o.optJSONObject("look"));
                boolean hasImage = false;
                File image = new File(work, i + ".image");
                if (o.optBoolean("hasImage") && isImage(image)) {
                    hasImage = ImageStore.copy(image, imageFile(context, id));
                    File mask = new File(work, i + ".mask");
                    if (hasImage && isImage(mask)) {
                        ImageStore.copy(mask, maskFile(context, id));
                    }
                    File media = new File(work, i + ".media");
                    if (!hasImage || settings.mediaType == WallpaperSettings.MEDIA_IMAGE || media.length() == 0
                            || !ImageStore.copy(media, picture(context, id).media)) {
                        // Without its video/animation the look still works as a still image.
                        settings.mediaType = WallpaperSettings.MEDIA_IMAGE;
                    }
                } else {
                    settings.mediaType = WallpaperSettings.MEDIA_IMAGE;
                }
                JSONObject look = toJson(settings);
                list.add(new Preset(id, name, false, hasImage, look));
                added++;
            }
            store(context, list);
            return added;
        } finally {
            deleteRecursively(work);
        }
    }

    /** Where an entry like "looks/3/image" is unpacked, or null to skip it. */
    private static File importTarget(File work, String entryName) {
        String[] parts = entryName.split("/");
        if (parts.length != 3 || !parts[0].equals("looks")) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (index < 0 || index >= MAX_LOOKS) {
            return null;
        }
        if (parts[2].equals("image")) {
            return new File(work, index + ".image");
        }
        if (parts[2].equals("mask.png")) {
            return new File(work, index + ".mask");
        }
        if (parts[2].equals("media")) {
            return new File(work, index + ".media");
        }
        return null;
    }

    private static boolean isImage(File f) {
        if (f.length() == 0) {
            return false;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        return bounds.outWidth > 0 && bounds.outHeight > 0;
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copyLimited(in, out, limit);
        return out.toByteArray();
    }

    private static void copyLimited(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > limit) {
                throw new IOException("File inside the looks file is too large");
            }
            out.write(buffer, 0, n);
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }

    /** Saves the current look. Call off the main thread when including the image. */
    static Preset save(Context context, String name, WallpaperSettings settings, boolean includeImage) {
        String id = UUID.randomUUID().toString();
        boolean hasImage = includeImage && ImageStore.hasDraftImage(context);
        if (hasImage) {
            ImageStore.copy(ImageStore.draft(context), picture(context, id));
        }
        Preset preset = new Preset(id, name, false, hasImage, toJson(settings));
        List<Preset> list = userPresets(context);
        list.add(preset);
        store(context, list);
        return preset;
    }

    /** Replaces a saved look's settings (and image, if it has one) with the current ones. */
    static void update(Context context, Preset preset, WallpaperSettings settings) {
        if (preset.hasImage && ImageStore.hasDraftImage(context)) {
            ImageStore.copy(ImageStore.draft(context), picture(context, preset.id));
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
        Picture picture = picture(context, preset.id);
        picture.image.delete();
        picture.mask.delete();
        picture.media.delete();
    }

    /**
     * Loads the look into the editor: its image (if any) and settings become the
     * draft, keeping this phone's sync and audio source. The wallpaper itself
     * changes only when the user applies. Call off the main thread.
     */
    static WallpaperSettings apply(Context context, Preset preset, WallpaperSettings current) {
        WallpaperSettings next = fromJson(preset.look).keepDeviceSettingsFrom(current);
        if (preset.hasImage && imageFile(context, preset.id).length() > 0) {
            ImageStore.copy(picture(context, preset.id), ImageStore.draft(context));
            next.imageVersion = System.currentTimeMillis();
            next.mediaType = fromJson(preset.look).mediaType;
        }
        next.saveDraft(context);
        return next;
    }

    /** The look's own image, or null when it uses whatever image is current. */
    static File image(Context context, Preset preset) {
        return preset.hasImage ? imageFile(context, preset.id) : null;
    }

    static File imageFile(Context context, String id) {
        File dir = new File(context.getFilesDir(), "presets");
        dir.mkdirs();
        return new File(dir, id + ".img");
    }

    static File maskFile(Context context, String id) {
        return new File(imageFile(context, id).getParentFile(), id + ".mask.png");
    }

    /** A saved look's own background files. */
    static Picture picture(Context context, String id) {
        File image = imageFile(context, id);
        return new Picture(image, maskFile(context, id), new File(image.getParentFile(), id + ".media"));
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
