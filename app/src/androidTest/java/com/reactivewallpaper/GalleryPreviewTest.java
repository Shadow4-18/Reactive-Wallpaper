package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Draws every look in the gallery list on the test picture, as the gallery shows them. */
@RunWith(AndroidJUnit4.class)
public class GalleryPreviewTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void previewsEveryGalleryLook() throws Exception {
        String json;
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("looks.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Gallery.Entry> entries = Gallery.parse(json);
        assertEquals(new org.json.JSONObject(json).getJSONArray("looks").length(), entries.size());
        Bitmap picture = TestScenes.neon(600, 1330);
        int w = 270;
        int h = 600;
        int columns = 5;
        int rows = (entries.size() + columns - 1) / columns;
        Bitmap sheet = Bitmap.createBitmap(columns * (w + 10), rows * (h + 10), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sheet);
        for (int i = 0; i < entries.size(); i++) {
            Bitmap p = Gallery.preview(picture, entries.get(i).look.settings, w, h, 1.2f);
            c.drawBitmap(p, (i % columns) * (w + 10), (i / columns) * (h + 10), null);
            p.recycle();
        }
        TestScenes.saveShot(context, "render_90_gallery_previews", sheet);
    }
}
