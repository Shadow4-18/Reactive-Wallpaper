package com.reactivewallpaper;

import android.content.Context;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;

/** Whether the wallpaper should save battery right now, per the user's choice. */
final class PowerState {
    private static final long CHECK_INTERVAL_MS = 15000;
    private static final int LOW_BATTERY_PERCENT = 20;

    private final PowerManager powerManager;
    private final BatteryManager batteryManager;
    private long lastCheck = -CHECK_INTERVAL_MS;
    private boolean low;

    PowerState(Context context) {
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
    }

    boolean shouldSave(int mode) {
        if (mode == WallpaperSettings.SAVER_ALWAYS) {
            return true;
        }
        if (mode == WallpaperSettings.SAVER_OFF) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastCheck >= CHECK_INTERVAL_MS) {
            lastCheck = now;
            boolean saverMode = powerManager != null && powerManager.isPowerSaveMode();
            boolean lowBattery = false;
            if (batteryManager != null && !batteryManager.isCharging()) {
                int percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                lowBattery = percent > 0 && percent <= LOW_BATTERY_PERCENT;
            }
            low = saverMode || lowBattery;
        }
        return low;
    }
}
