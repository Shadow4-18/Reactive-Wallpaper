package com.reactivewallpaper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A look as a short link (resona://look/...), for QR codes and messages. It
 * carries the look's name and only the settings that differ from the
 * defaults, compressed. Pictures, framing and this phone's own settings
 * (audio source, sync delays, battery, notifications) are left out: a shared
 * look applies to whoever opens it, on their own picture.
 */
final class LookCode {
    static final String PREFIX = "resona://look/";
    /**
     * The same look as a web link, which chat apps make tappable; the page it
     * opens hands the look to Resona (see site/look/index.html).
     */
    static final String WEB_PREFIX = "https://shadow4-18.github.io/Reactive-Wallpaper/look/#";
    private static final String WEB_MARKER = "/look/#";
    /** Bumped if the format ever changes incompatibly. */
    private static final int VERSION = 1;
    /** Decoded looks are refused past this size (a code is a few hundred bytes). */
    private static final int MAX_JSON = 64 * 1024;

    /** Settings that belong to the phone or the picture, not to a look. */
    private static final String[] LEFT_OUT = {
            "imageVersion", "mediaType", "fitMode", "imageZoom", "imagePanX", "imagePanY", "edgeStyle",
            "hideEdges", "source", "syncSpeakerMs", "syncBluetoothMs", "batterySaver", "sleepMinutes",
            "maxFps", "notifyPulse", "notifyUseAppColor"};

    /** A decoded look. */
    static final class Look {
        final String name;
        final WallpaperSettings settings;

        Look(String name, WallpaperSettings settings) {
            this.name = name;
            this.settings = settings;
        }
    }

    private LookCode() {}

    static String encode(String name, WallpaperSettings settings) {
        JSONObject all = PresetStore.toJson(settings);
        JSONObject defaults = PresetStore.toJson(new WallpaperSettings());
        JSONObject diff = new JSONObject();
        try {
            for (Iterator<String> keys = all.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (isLeftOut(key)) {
                    continue;
                }
                Object value = all.get(key);
                Object def = defaults.opt(key);
                if (def == null || !sameValue(value, def)) {
                    diff.put(key, value);
                }
            }
            JSONObject o = new JSONObject().put("v", VERSION).put("n", name == null ? "" : name).put("s", diff);
            byte[] packed = deflate(o.toString().getBytes(StandardCharsets.UTF_8));
            return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The look as a tappable web link, for messages and QR codes. */
    static String webLink(String name, WallpaperSettings settings) {
        return WEB_PREFIX + encode(name, settings).substring(PREFIX.length());
    }

    /** The look in a code or link (app or web form), or null if it isn't one (or is damaged). */
    static Look decode(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        String data;
        int at = trimmed.indexOf(PREFIX);
        if (at >= 0) {
            data = trimmed.substring(at + PREFIX.length());
        } else {
            int web = trimmed.indexOf(WEB_MARKER);
            if (web < 0) {
                return null;
            }
            data = trimmed.substring(web + WEB_MARKER.length());
        }
        int end = 0;
        while (end < data.length() && isBase64Url(data.charAt(end))) {
            end++;
        }
        try {
            byte[] packed = java.util.Base64.getUrlDecoder().decode(data.substring(0, end));
            String json = new String(inflate(packed), StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(json);
            if (o.optInt("v", 0) != VERSION) {
                return null;
            }
            JSONObject diff = o.optJSONObject("s");
            if (diff == null) {
                return null;
            }
            // Start from defaults and apply the differences; reading back through
            // the settings keeps only known keys, with values clamped as usual.
            JSONObject merged = PresetStore.toJson(new WallpaperSettings());
            for (Iterator<String> keys = diff.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!isLeftOut(key) && merged.has(key)) {
                    merged.put(key, diff.get(key));
                }
            }
            String name = o.optString("n", "").trim();
            if (name.isEmpty()) {
                name = "Shared look";
            }
            name = name.length() > 60 ? name.substring(0, 60) : name;
            return new Look(name, PresetStore.fromJson(merged));
        } catch (IllegalArgumentException | JSONException | DataFormatException e) {
            return null;
        }
    }

    private static boolean isLeftOut(String key) {
        for (String k : LEFT_OUT) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Math.abs(((Number) a).doubleValue() - ((Number) b).doubleValue()) < 1e-6;
        }
        return a.equals(b);
    }

    private static boolean isBase64Url(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    private static byte[] deflate(byte[] input) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(input);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] input) throws DataFormatException {
        Inflater i = new Inflater(true);
        i.setInput(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        try {
            while (!i.finished()) {
                int n = i.inflate(buf);
                if (n == 0 && (i.needsInput() || i.needsDictionary())) {
                    break;
                }
                out.write(buf, 0, n);
                if (out.size() > MAX_JSON) {
                    throw new DataFormatException("too large");
                }
            }
        } finally {
            i.end();
        }
        return out.toByteArray();
    }
}
