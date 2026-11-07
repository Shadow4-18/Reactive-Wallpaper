package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.util.Random;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Records a real clip of the renderer through the phone's video encoder, and checks the file. */
@RunWith(AndroidJUnit4.class)
public class ClipRecorderTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private long now;

    @After
    public void restoreClock() {
        AudioAnalyzer.clock = SystemClock::uptimeMillis;
    }

    @Test
    public void recordsAPlayableClip() throws Exception {
        int[] size = ClipRecorder.size(720, 1080, 2400);
        assertEquals(720, size[0]);
        assertEquals(0, size[1] % 16);
        File out = new File(context.getCacheDir(), "test_clip.mp4");
        ClipRecorder recorder = new ClipRecorder(out, size[0], size[1], 30);

        WallpaperSettings s = new WallpaperSettings();
        s.style = WallpaperSettings.STYLE_CIRCLE;
        s.circleY = 0.58f;
        s.circleSize = 0.36f;
        AudioAnalyzer.clock = () -> now;
        AudioAnalyzer analyzer = new AudioAnalyzer(s.barCount);
        s.applyTo(analyzer, false, false);
        WallpaperRenderer renderer = new WallpaperRenderer(2.75f);
        renderer.setImage(TestScenes.neon(1200, 2660), null);
        Random random = new Random(3);
        // Two seconds at 30 frames a second, in real time (frames are timed as drawn).
        for (int f = 0; f < 60; f++) {
            now += 33;
            analyzer.submit(TestScenes.music(now, random), 44100);
            analyzer.update(0.033f);
            recorder.frame((canvas, w, h) -> renderer.draw(canvas, w, h, analyzer, s, 0.033f));
            SystemClock.sleep(33);
        }
        File file = recorder.finish();
        renderer.release();
        assertNotNull("no clip written", file);

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(file.getPath());
        assertEquals(1, extractor.getTrackCount());
        MediaFormat format = extractor.getTrackFormat(0);
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME));
        long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
        extractor.release();
        assertTrue("clip is " + durationUs / 1000 + " ms", durationUs > 1_500_000);

        // A frame from the middle, to look at.
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(file.getPath());
        Bitmap frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST);
        retriever.release();
        assertNotNull("no frame decoded", frame);
        TestScenes.saveShot(context, "render_81_clip_frame", frame);
        file.delete();
    }
}
