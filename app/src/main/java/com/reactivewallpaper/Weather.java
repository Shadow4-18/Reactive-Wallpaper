package com.reactivewallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The weather outside, for the weather effect: fetched from Open-Meteo (free,
 * no account) for the saved approximate location at most every half hour,
 * and kept so every screen of the app sees the same answer.
 */
final class Weather {
    private static final String TAG = "Weather";
    private static final String PREFS = "weather";
    private static final long FRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 10 * 60_000L;

    static final int CLEAR = 0;
    static final int CLOUDY = 1;
    static final int FOG = 2;
    static final int RAIN = 3;
    static final int SNOW = 4;
    static final int STORM = 5;

    /** What's falling (or hanging) in the air. */
    static final class State {
        final int kind;
        /** 0..1: drizzle to downpour, flurries to blizzard. */
        final float intensity;
        /** -1..1: which way and how hard the wind leans the rain (west wind = positive). */
        final float wind;

        State(int kind, float intensity, float wind) {
            this.kind = kind;
            this.intensity = intensity;
            this.wind = wind;
        }
    }

    private Weather() {}

    private static volatile boolean fetching;

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The last weather fetched, or clear skies when there's none yet. */
    static State current(Context context) {
        SharedPreferences p = prefs(context);
        return new State(p.getInt("kind", CLEAR), p.getFloat("intensity", 0f), p.getFloat("wind", 0f));
    }

    /** When the weather was last fetched (0 = never). */
    static long fetchedAt(Context context) {
        return prefs(context).getLong("time", 0L);
    }

    /**
     * Fetches the weather in the background when it's older than half an hour
     * (or a failed try was long enough ago), then calls {@code done} on that
     * thread if anything new came in. Needs a saved location.
     */
    static void refreshIfStale(Context context, Runnable done) {
        double[] where = WallpaperSettings.sunLocation(context);
        if (where == null || fetching) {
            return;
        }
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        if (now - p.getLong("time", 0L) < FRESH_MS || now - p.getLong("tried", 0L) < RETRY_MS) {
            return;
        }
        fetching = true;
        p.edit().putLong("tried", now).apply();
        new Thread(() -> {
            try {
                State s = fetch(where[0], where[1]);
                if (s != null) {
                    p.edit().putInt("kind", s.kind).putFloat("intensity", s.intensity)
                            .putFloat("wind", s.wind).putLong("time", System.currentTimeMillis()).apply();
                    if (done != null) {
                        done.run();
                    }
                }
            } finally {
                fetching = false;
            }
        }, "weather").start();
    }

    private static State fetch(double lat, double lon) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                            + "&current=weather_code,precipitation,wind_speed_10m,wind_direction_10m",
                    lat, lon));
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            if (c.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) {
                    out.write(buf, 0, n);
                }
                return parse(new JSONObject(out.toString("UTF-8")));
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't get the weather", e);
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** Open-Meteo's "current" block to a state. */
    static State parse(JSONObject response) {
        JSONObject cur = response.optJSONObject("current");
        if (cur == null) {
            return null;
        }
        int code = cur.optInt("weather_code", 0);
        double rainMm = cur.optDouble("precipitation", 0);
        double windKmh = cur.optDouble("wind_speed_10m", 0);
        double windFrom = cur.optDouble("wind_direction_10m", 270);
        int kind = kindOf(code);
        float intensity = intensityOf(code, rainMm);
        // Wind blowing from the west pushes rain to the east (screen right).
        float lean = (float) (-Math.sin(Math.toRadians(windFrom)) * Math.min(1.0, windKmh / 50.0));
        return new State(kind, intensity, lean);
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    static int kindOf(int code) {
        if (code == 45 || code == 48) {
            return FOG;
        }
        if (code >= 95) {
            return STORM;
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return SNOW;
        }
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
            return RAIN;
        }
        if (code == 2 || code == 3) {
            return CLOUDY;
        }
        return CLEAR;
    }

    static float intensityOf(int code, double precipitationMm) {
        float byCode;
        switch (code) {
            case 51: case 56: case 61: case 66: case 71: case 80: case 85:
                byCode = 0.35f;
                break;
            case 53: case 63: case 73: case 81:
                byCode = 0.6f;
                break;
            case 55: case 57: case 65: case 67: case 75: case 82: case 86: case 99:
                byCode = 0.9f;
                break;
            case 45: case 48: case 95: case 96:
                byCode = 0.7f;
                break;
            default:
                byCode = 0.5f;
        }
        float byAmount = (float) Math.min(1.0, precipitationMm / 6.0);
        return Math.max(byCode, byAmount);
    }

    static String describe(State s) {
        String[] names = {"Clear", "Cloudy", "Fog", "Rain", "Snow", "Thunderstorm"};
        return names[Math.max(0, Math.min(names.length - 1, s.kind))];
    }
}
