package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdaterTest {
    @Test
    public void comparesVersionsNumberByNumber() {
        assertTrue(Updater.isNewer("v2.1", "2.0"));
        assertTrue(Updater.isNewer("v2.10", "2.9"));
        assertTrue(Updater.isNewer("v3", "2.9.9"));
        assertTrue(Updater.isNewer("2.0.1", "2.0"));
        assertFalse(Updater.isNewer("v2.0", "2.0"));
        assertFalse(Updater.isNewer("v1.9", "2.0"));
    }

    @Test
    public void notesAreTheTextAboveTheDivider() {
        assertEquals("New: video backgrounds.",
                Updater.notesOf("New: video backgrounds.\n\n---\nDownload Resona.apk below"));
        assertEquals("", Updater.notesOf("---\nDownload Resona.apk below"));
        assertEquals("", Updater.notesOf(null));
    }
}
