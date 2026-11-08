package com.reactivewallpaper;

import android.content.Context;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemClock;

/** Whether the wallpaper should save battery right now, per the user's choice. */
final class PowerState {
    private static final long CHECK_INTERVAL_MS = 15000;

    private final PowerManager powerManager;
    private final BatteryManager batteryManager;
    private long lastCheck = -CHECK_INTERVAL_MS;
    private boolean low;
    private int lastPercent = -1;
    private boolean lastWhenHot;

    PowerState(Context context) {
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
    }

    boolean shouldSave(int mode) {
        return shouldSave(mode, 20, true);
    }

    /**
     * @param percent auto saving starts at or below this battery level
     * @param whenHot auto saving also starts when the phone is running hot
     */
    boolean shouldSave(int mode, int percent, boolean whenHot) {
        if (mode == WallpaperSettings.SAVER_ALWAYS) {
            return true;
        }
        if (mode == WallpaperSettings.SAVER_OFF) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastCheck >= CHECK_INTERVAL_MS || percent != lastPercent || whenHot != lastWhenHot) {
            lastPercent = percent;
            lastWhenHot = whenHot;
            lastCheck = now;
            boolean saverMode = powerManager != null && powerManager.isPowerSaveMode();
            boolean lowBattery = false;
            if (batteryManager != null && !batteryManager.isCharging()) {
                int level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                lowBattery = level > 0 && level <= percent;
            }
            boolean hot = whenHot && android.os.Build.VERSION.SDK_INT >= 29 && powerManager != null
                    && powerManager.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE;
            low = saverMode || lowBattery || hot;
        }
        return low;
    }
}
