package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The look gallery: a list of shared looks (as {@link LookCode}s) published
 * from this app's repository (gallery/looks.json, on the "gallery" release),
 * plus previews of each look drawn on the user's own picture.
 */
final class Gallery {
    static final String URL = "https://github.com/Shadow4-18/Reactive-Wallpaper/releases/download/gallery/looks.json";
    /** Where people suggest looks for the gallery. */
    static final String SUGGEST_URL = "https://github.com/Shadow4-18/Reactive-Wallpaper/issues/new";
    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_LOOKS = 200;

    static final class Entry {
        final String name;
        final String by;
        final String about;
        final LookCode.Look look;
        final String code;

        Entry(String name, String by, String about, LookCode.Look look, String code) {
            this.name = name;
            this.by = by;
            this.about = about;
            this.look = look;
            this.code = code;
        }
    }

    private Gallery() {}

    /** Downloads the gallery list. Call off the main thread. */
    static List<Entry> fetch() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "Resona");
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) {
                        throw new IOException("Gallery list too large");
                    }
                }
                return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    /** The looks in a gallery list; entries that aren't valid looks are skipped. */
    static List<Entry> parse(String json) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray looks = new JSONObject(json).getJSONArray("looks");
            for (int i = 0; i < looks.length() && entries.size() < MAX_LOOKS; i++) {
                JSONObject o = looks.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String code = o.optString("code", "");
                LookCode.Look look = LookCode.decode(code);
                if (look == null) {
                    continue;
                }
                String name = clip(o.optString("name", look.name), 60);
                entries.add(new Entry(name.isEmpty() ? look.name : name, clip(o.optString("by", ""), 40),
                        clip(o.optString("about", ""), 200), new LookCode.Look(name.isEmpty() ? look.name : name,
                        look.settings), code));
            }
        } catch (JSONException e) {
            throw new IOException("Damaged gallery list", e);
        }
        return entries;
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * Draws a look over {@code picture} (or the default backdrop when null) as it
     * looks just after a kick drum of the demo song. Call off the main thread.
     */
    static Bitmap preview(Bitmap picture, WallpaperSettings settings, int width, int height, float density) {
        long[] now = {0};
        AudioAnalyzer analyzer = new AudioAnalyzer(settings.barCount);
        analyzer.useClock(() -> now[0]);
        settings.applyTo(analyzer, false, false);
        WallpaperRenderer renderer = new WallpaperRenderer(density);
        renderer.setImage(picture != null ? picture.copy(Bitmap.Config.ARGB_8888, false) : null, null);
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float[] mags = new float[512];
        Random random = new Random(7);
        // 46 frames of 33 ms: the last lands just after the fourth kick.
        for (int f = 0; f < 46; f++) {
            now[0] += 33;
            DemoAudioInput.frame(now[0] / 1000f, random, mags);
            analyzer.submit(mags, DemoAudioInput.RATE);
            analyzer.update(0.033f);
            renderer.draw(canvas, width, height, analyzer, settings, 0.033f);
        }
        renderer.release();
        return out;
    }
}
