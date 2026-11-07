package com.reactivewallpaper;

/** The four parts of the day that can each have their own look. */
final class TimeOfDay {
    static final int MORNING = 0;
    static final int AFTERNOON = 1;
    static final int EVENING = 2;
    static final int NIGHT = 3;
    static final String[] NAMES = {"Morning (6 am – noon)", "Afternoon (noon – 6 pm)",
            "Evening (6 pm – 10 pm)", "Night (10 pm – 6 am)"};

    private TimeOfDay() {}

    /** The part of the day an hour (0–23) falls in. */
    static int part(int hour) {
        if (hour >= 6 && hour < 12) {
            return MORNING;
        }
        if (hour >= 12 && hour < 18) {
            return AFTERNOON;
        }
        if (hour >= 18 && hour < 22) {
            return EVENING;
        }
        return NIGHT;
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
