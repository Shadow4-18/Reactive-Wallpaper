package com.reactivewallpaper;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

/**
 * Passes "a notification arrived (in this color)" from the notification
 * listener to running wallpaper engines, on the main thread. Arrivals within a
 * moment of each other count once, so a burst of messages pulses once.
 */
final class NotificationPulse {
    private static final long MIN_GAP_MS = 1500;
    private static final CopyOnWriteArrayList<IntConsumer> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static long lastPost;

    private NotificationPulse() {}

    static void add(IntConsumer listener) {
        listeners.add(listener);
    }

    static void remove(IntConsumer listener) {
        listeners.remove(listener);
    }

    /** @param color the app's accent color, or 0 for none */
    static void post(int color) {
        main.post(() -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPost < MIN_GAP_MS) {
                return;
            }
            lastPost = now;
            for (IntConsumer l : listeners) {
                l.accept(color);
            }
        });
    }
}
