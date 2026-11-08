package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;

/** A subject tracked through a video or GIF: saved, read back, and followed between steps. */
@RunWith(AndroidJUnit4.class)
public class SubjectTrackTest {
    private static final int H = 160;

    /** A mask with a 20 px wide subject whose left edge is at x. */
    private static byte[] subjectAt(int x) {
        byte[] m = new byte[SubjectTrack.WIDTH * H];
        for (int y = 40; y < 120; y++) {
            for (int i = x; i < x + 20; i++) {
                m[y * SubjectTrack.WIDTH + i] = (byte) 255;
            }
        }
        return m;
    }

    /** Where the subject's middle is in a filled frame (by alpha), as x. */
    private static float centerX(int[] px) {
        double sum = 0;
        double sx = 0;
        for (int i = 0; i < px.length; i++) {
            int a = px[i] >>> 24;
            sum += a;
            sx += a * (i % SubjectTrack.WIDTH);
        }
        return (float) (sx / sum);
    }

    @Test
    public void savedReadBackAndFollowedBetweenSteps() throws Exception {
        List<byte[]> masks = new ArrayList<>();
        // The subject walks right across four steps of a 2-second loop.
        masks.add(subjectAt(10));
        masks.add(subjectAt(30));
        masks.add(subjectAt(50));
        masks.add(subjectAt(70));
        Bitmap sheet = SubjectTrack.save(masks, 500, 2000, H);
        File file = File.createTempFile("track", ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        sheet.recycle();

        SubjectTrack track = SubjectTrack.read(file);
        assertNotNull(track);
        assertEquals(4, track.count);
        assertEquals(2000, track.durationMs());
        assertEquals(H, track.height);

        int[] px = new int[SubjectTrack.WIDTH * H];
        track.fill(SubjectTrack.MASK, 0, px);
        assertEquals(20f, centerX(px), 0.6f);
        // Halfway between the first two steps: halfway between their places.
        track.fill(SubjectTrack.MASK, 250, px);
        assertEquals(30f, centerX(px), 0.6f);
        track.fill(SubjectTrack.MASK, 1500, px);
        assertEquals(80f, centerX(px), 0.6f);
        // It loops.
        track.fill(SubjectTrack.MASK, 2000 + 1500, px);
        assertEquals(80f, centerX(px), 0.6f);
        // The outline is brightest on the subject's edge, not inside it.
        track.fill(SubjectTrack.RIM, 0, px);
        int edge = px[80 * SubjectTrack.WIDTH + 10] >>> 24;
        int inside = px[80 * SubjectTrack.WIDTH + 20] >>> 24;
        assertTrue("edge " + edge + " inside " + inside, edge > inside + 100);
        file.delete();
    }

    @Test
    public void aStillPicturesSubjectMaskIsNotATrack() {
        Bitmap gray = Bitmap.createBitmap(SubjectTrack.WIDTH, 200, Bitmap.Config.ARGB_8888);
        gray.eraseColor(0xFF808080);
        assertNull(SubjectTrack.load(gray));
        gray.recycle();
    }
}
