package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LookCodeTest {
    @Test
    public void roundTripsALook() {
        WallpaperSettings s = new WallpaperSettings();
        s.style = WallpaperSettings.STYLE_ORB;
        s.bassGlow = 2.4f;
        s.particleStyle = WallpaperSettings.PARTICLE_EMBERS;
        s.camPunch = 1.5f;
        s.tempoSync = false;
        s.hueShift = 120f;
        String code = LookCode.encode("Neon night", s);
        assertTrue(code.startsWith(LookCode.PREFIX));
        // Small enough for an easy QR code.
        assertTrue("code is " + code.length() + " chars", code.length() < 300);
        LookCode.Look back = LookCode.decode(code);
        assertNotNull(back);
        assertEquals("Neon night", back.name);
        assertEquals(WallpaperSettings.STYLE_ORB, back.settings.style);
        assertEquals(2.4f, back.settings.bassGlow, 0.001f);
        assertEquals(WallpaperSettings.PARTICLE_EMBERS, back.settings.particleStyle);
        assertEquals(1.5f, back.settings.camPunch, 0.001f);
        assertEquals(false, back.settings.tempoSync);
        assertEquals(120f, back.settings.hueShift, 0.001f);
    }

    @Test
    public void leavesOutThePhonesOwnSettingsAndFraming() {
        WallpaperSettings s = new WallpaperSettings();
        s.syncBluetoothMs = 280;
        s.source = WallpaperSettings.SOURCE_MIC;
        s.imageZoom = 2.2f;
        LookCode.Look back = LookCode.decode(LookCode.encode("x", s));
        WallpaperSettings defaults = new WallpaperSettings();
        assertEquals(defaults.syncBluetoothMs, back.settings.syncBluetoothMs);
        assertEquals(defaults.source, back.settings.source);
        assertEquals(defaults.imageZoom, back.settings.imageZoom, 0.001f);
    }

    @Test
    public void findsTheCodeInsideAMessage() {
        String code = LookCode.encode("Club", new WallpaperSettings());
        LookCode.Look back = LookCode.decode("Try my wallpaper look: " + code + " (made with Resona)");
        assertNotNull(back);
        assertEquals("Club", back.name);
    }

    @Test
    public void rejectsWhatIsNotALook() {
        assertNull(LookCode.decode(null));
        assertNull(LookCode.decode("https://example.com"));
        assertNull(LookCode.decode(LookCode.PREFIX + "not-really-a-look"));
        assertNull(LookCode.decode(LookCode.PREFIX));
    }
}
