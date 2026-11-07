package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Test;

public class GalleryTest {
    @Test
    public void readsTheGalleryAndSkipsBrokenEntries() throws Exception {
        String good = LookCode.encode("Night", new WallpaperSettings());
        String json = "{\"looks\":[{\"name\":\"Night\",\"by\":\"Me\",\"about\":\"Dark\",\"code\":\"" + good + "\"},"
                + "{\"name\":\"Broken\",\"code\":\"resona://look/xyz\"},{\"name\":\"No code\"}]}";
        List<Gallery.Entry> entries = Gallery.parse(json);
        assertEquals(1, entries.size());
        assertEquals("Night", entries.get(0).name);
        assertEquals("Me", entries.get(0).by);
    }

    @Test
    public void thePublishedGalleryIsValid() throws Exception {
        // gallery/looks.json at the repository root; unit tests run from the app module.
        String json = new String(Files.readAllBytes(Paths.get("../gallery/looks.json")), StandardCharsets.UTF_8);
        int listed = new org.json.JSONObject(json).getJSONArray("looks").length();
        assertEquals("every gallery look must decode", listed, Gallery.parse(json).size());
    }
}
