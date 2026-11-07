package com.reactivewallpaper;

/**
 * The four parts of the day that can each have their own look, and when they
 * start: fixed hours, the user's own times, or following sunrise and sunset.
 * Times are minutes after midnight, local time.
 */
final class TimeOfDay {
    static final int MORNING = 0;
    static final int AFTERNOON = 1;
    static final int EVENING = 2;
    static final int NIGHT = 3;
    static final String[] NAMES = {"Morning", "Afternoon", "Evening", "Night"};

    /** How the parts' start times are chosen. */
    static final int TIMES_FIXED = 0;
    static final int TIMES_CUSTOM = 1;
    static final int TIMES_SUN = 2;

    /** 6 am, noon, 6 pm, 10 pm. */
    static final int[] FIXED = {6 * 60, 12 * 60, 18 * 60, 22 * 60};

    private TimeOfDay() {}

    /** The part of the day an hour (0–23) falls in, with the fixed times. */
    static int part(int hour) {
        return part(hour * 60, FIXED);
    }

    /**
     * The part {@code minute} (0..1439) falls in, given each part's start
     * (morning, afternoon, evening, night, in order through the day; night runs
     * past midnight to the next morning).
     */
    static int part(int minute, int[] starts) {
        for (int p = NIGHT; p >= MORNING; p--) {
            if (minute >= starts[p]) {
                return p;
            }
        }
        return NIGHT;
    }

    /**
     * Start times that follow the sun: morning at sunrise, afternoon at noon,
     * evening an hour before sunset and night an hour after. Where the sun
     * doesn't rise or set that day (far north or south), the fixed times.
     */
    static int[] sunStarts(double latitude, double longitude, int dayOfYear, int utcOffsetMinutes) {
        int[] sun = sunriseSunset(latitude, longitude, dayOfYear, utcOffsetMinutes);
        if (sun == null) {
            return FIXED.clone();
        }
        int morning = clamp(sun[0], 3 * 60, 11 * 60);
        int evening = clamp(sun[1] - 60, 13 * 60, 22 * 60);
        int night = clamp(sun[1] + 60, evening + 30, 23 * 60 + 30);
        return new int[] {morning, 12 * 60, evening, night};
    }

    /** Puts custom start times in order (each at least a minute after the one before). */
    static int[] ordered(int[] starts) {
        int[] out = starts.clone();
        for (int i = 1; i < 4; i++) {
            out[i] = Math.max(out[i], out[i - 1] + 1);
        }
        out[3] = Math.min(out[3], 1439);
        return out;
    }

    /**
     * Local sunrise and sunset (minutes after midnight) by NOAA's solar
     * equations, or null when the sun stays up or down all day.
     */
    static int[] sunriseSunset(double latitude, double longitude, int dayOfYear, int utcOffsetMinutes) {
        double g = 2 * Math.PI / 365 * (dayOfYear - 1);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g)
                - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double lat = Math.toRadians(latitude);
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(lat) * Math.cos(decl))
                - Math.tan(lat) * Math.tan(decl);
        if (cosHa < -1 || cosHa > 1) {
            return null;
        }
        double ha = Math.toDegrees(Math.acos(cosHa));
        double rise = 720 - 4 * (longitude + ha) - eqTime + utcOffsetMinutes;
        double set = 720 - 4 * (longitude - ha) - eqTime + utcOffsetMinutes;
        return new int[] {wrap(rise), wrap(set)};
    }

    private static int wrap(double minutes) {
        int m = (int) Math.round(minutes) % 1440;
        return m < 0 ? m + 1440 : m;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static String format(int minute) {
        int h = minute / 60;
        int m = minute % 60;
        String ampm = h < 12 ? "am" : "pm";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return m == 0 ? h12 + " " + ampm : h12 + ":" + (m < 10 ? "0" : "") + m + " " + ampm;
    }

    /**
     * The look for {@code part}: its own if set, otherwise the nearest earlier
     * part's (so a morning and a night look cover the whole day), or "" when
     * none is set at all.
     */
    static String lookFor(String[] ids, int part) {
        for (int i = 0; i < 4; i++) {
            String id = ids[(part - i + 4) % 4];
            if (id != null && !id.isEmpty()) {
                return id;
            }
        }
        return "";
    }
}
