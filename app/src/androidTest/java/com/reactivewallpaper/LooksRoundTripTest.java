package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Export and import looks with the phone's real image decoder. */
@RunWith(AndroidJUnit4.class)
public class LooksRoundTripTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void lookSurvivesExportAndImport() throws Exception {
        ImageStore.Picture draft = ImageStore.draft(context);
        try (FileOutputStream out = new FileOutputStream(draft.image)) {
            TestScenes.neon(300, 660).compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        Bitmap mask = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888);
        mask.eraseColor(0xFFFFFFFF);
        ImageStore.saveMask(mask, draft.mask);
        ImageStore.saveMask(RenderSnapshotTest.figureMask(), draft.subject);

        WallpaperSettings settings = new WallpaperSettings();
        settings.bassGlow = 2.4f;
        settings.particleStyle = WallpaperSettings.PARTICLE_EMBERS;
        settings.subjectDepth = 0.8f;
        PresetStore.Preset saved = PresetStore.save(context, "Round trip", settings, true);
        List<PresetStore.Preset> one = new ArrayList<>();
        one.add(saved);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PresetStore.export(context, one, bytes);
        PresetStore.delete(context, saved);

        int added = PresetStore.importFrom(context, new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(1, added);
        PresetStore.Preset back = null;
        for (PresetStore.Preset p : PresetStore.all(context)) {
            if (p.name.equals("Round trip")) {
                back = p;
            }
        }
        assertTrue(back != null && back.hasImage);
        ImageStore.Picture picture = PresetStore.picture(context, back.id);
        assertTrue(ImageStore.load(picture.image, 100, 100) != null);
        assertTrue(ImageStore.loadMask(picture.mask) != null);
        Bitmap subject = ImageStore.loadMask(picture.subject);
        assertTrue(subject != null && subject.getWidth() == 300);
        WallpaperSettings restored = PresetStore.fromJson(back.look);
        assertEquals(2.4f, restored.bassGlow, 0.001f);
        assertEquals(WallpaperSettings.PARTICLE_EMBERS, restored.particleStyle);
        assertEquals(0.8f, restored.subjectDepth, 0.001f);
        PresetStore.delete(context, back);
    }
}
