package com.reactivewallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Runs automatic subject detection on the emulator. Whether it finds anything
 * depends on Play services having the model (and on the drawn test scene), so
 * the outcome is written down for review rather than asserted; a crash or a
 * callback that never comes fails the test.
 */
@RunWith(AndroidJUnit4.class)
public class SubjectDetectorTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void detectionAnswers() throws Exception {
        Bitmap image = TestScenes.neon(600, 1330);
        CountDownLatch done = new CountDownLatch(1);
        String[] outcome = new String[1];
        Bitmap[] found = new Bitmap[1];
        long start = System.currentTimeMillis();
        new Handler(Looper.getMainLooper()).post(() -> SubjectDetector.detect(context, image, (mask, error) -> {
            found[0] = mask;
            outcome[0] = mask != null ? "found a subject, mask " + mask.getWidth() + "x" + mask.getHeight()
                    : "no mask: " + error;
            done.countDown();
        }));
        if (!done.await(120, TimeUnit.SECONDS)) {
            throw new AssertionError("Subject detection never answered");
        }
        String line = outcome[0] + " (" + (System.currentTimeMillis() - start) + " ms)\n";
        File out = new File(new File(context.getFilesDir(), "screens"), "subject_detection.txt");
        out.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            stream.write(line.getBytes(StandardCharsets.UTF_8));
        }
        if (found[0] != null) {
            TestScenes.saveShot(context, "render_66_detected_subject", found[0]);
        }
    }
}
