package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Each Bluetooth device keeps its own sync delay; others fall back to the general one. */
@RunWith(AndroidJUnit4.class)
public class DeviceDelayTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void devicesKeepTheirOwnDelays() {
        WallpaperSettings.setDeviceDelay(context, "Test Buds", 180);
        WallpaperSettings.setDeviceDelay(context, "Test Speaker", 340);
        assertEquals(Integer.valueOf(180), WallpaperSettings.deviceDelay(context, "Test Buds"));
        assertEquals(Integer.valueOf(340), WallpaperSettings.deviceDelay(context, "Test Speaker"));
        assertNull(WallpaperSettings.deviceDelay(context, "Never Calibrated"));
        assertNull(WallpaperSettings.deviceDelay(context, null));
        WallpaperSettings.setDeviceDelay(context, "Test Buds", null);
        assertNull(WallpaperSettings.deviceDelay(context, "Test Buds"));
        assertEquals(Integer.valueOf(340), WallpaperSettings.deviceDelay(context, "Test Speaker"));
        WallpaperSettings.setDeviceDelay(context, "Test Speaker", null);
    }
}
