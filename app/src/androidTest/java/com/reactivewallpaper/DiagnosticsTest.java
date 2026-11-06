package com.reactivewallpaper;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DiagnosticsTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void reportCoversEachPart() throws Exception {
        String report = Diagnostics.report(context);
        for (String part : new String[] {"== App and phone ==", "== Access ==", "== Wallpaper ==",
                "== Pictures ==", "== Live settings ==", "== Recent crashes ==", "== Recent app log =="}) {
            assertTrue("missing " + part, report.contains(part));
        }
        // Saved next to the screenshots, to read the real thing.
        File out = new File(new File(context.getFilesDir(), "screens"), "diagnostics.txt");
        out.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(report.getBytes(StandardCharsets.UTF_8));
        }
    }
}
