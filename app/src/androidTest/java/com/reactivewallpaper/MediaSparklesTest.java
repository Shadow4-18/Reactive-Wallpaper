package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.lang.reflect.Field;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Sparkles on videos and GIFs: glowing spots are found per frame, and sparkles follow them. */
@RunWith(AndroidJUnit4.class)
public class MediaSparklesTest {
    /** A dark frame with one glowing magenta spot centered at (u, v). */
    private static Bitmap frameWithSpot(float u, float v) {
        Bitmap b = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF0A0614);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFF3DCB);
        new Canvas(b).drawCircle(u * 400, v * 800, 30, p);
        return b;
    }

    private static float mean(float[] values, int count) {
        float sum = 0f;
        for (int i = 0; i < count; i++) {
            sum += values[i];
        }
        return sum / count;
    }

    @Test
    public void findsTheGlowingSpotWhereverItIs() {
        float[][] left = MediaSparkles.spots(frameWithSpot(0.2f, 0.3f), 0, 0.6f);
        assertNotNull(left);
        int n = (int) left[2][0];
        assertEquals(0.2f, mean(left[0], n), 0.03f);
        assertEquals(0.3f, mean(left[1], n), 0.03f);
        float[][] right = MediaSparkles.spots(frameWithSpot(0.75f, 0.6f), 0, 0.6f);
        assertEquals(0.75f, mean(right[0], (int) right[2][0]), 0.03f);
        // A video recorded sideways: the spot lands where it shows once turned upright.
        float[][] turned = MediaSparkles.spots(frameWithSpot(0.2f, 0.3f), 90, 0.6f);
        int t = (int) turned[2][0];
        assertEquals(0.7f, mean(turned[0], t), 0.03f);
        assertEquals(0.2f, mean(turned[1], t), 0.03f);
    }

    @Test
    public void sparklesFollowTheirSpotAndFadeWhenItIsGone() throws Exception {
        float[] u = {0.3f};
        float[] v = {0.5f};
        ParticleSystem particles = new ParticleSystem(u, v, new int[] {0xFFFF3DCB}, 1, new float[] {300f, 190f});
        WallpaperSettings s = new WallpaperSettings();
        s.particleStyle = WallpaperSettings.PARTICLE_SPARKLES;
        AudioAnalyzer audio = new AudioAnalyzer(s.barCount);
        Canvas canvas = new Canvas(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888));
        for (int f = 0; f < 5; f++) {
            particles.draw(canvas, new Matrix(), 200, 400, ParticleSystem.MAX, 0.03f, 1f, audio, s, 0f, 0f, null);
        }
        float[] pu = field(particles, "u");
        float[] life = field(particles, "life");
        float[] maxLife = field(particles, "maxLife");
        // The spot moves a little to the right: the sparkles go with it.
        particles.moveSpawns(new float[] {0.34f}, new float[] {0.5f}, 1);
        for (int i = 0; i < ParticleSystem.MAX; i++) {
            if (life[i] < maxLife[i]) {
                assertEquals("sparkle " + i, 0.34f, pu[i], 0.015f);
            }
        }
        // The spot vanishes and another appears far away: the old sparkles fade out.
        particles.moveSpawns(new float[] {0.9f}, new float[] {0.1f}, 1);
        for (int i = 0; i < ParticleSystem.MAX; i++) {
            assertTrue("sparkle " + i + " should be fading", life[i] >= maxLife[i] - 0.36f);
        }
        particles.release();
    }

    private static float[] field(ParticleSystem p, String name) throws Exception {
        Field f = ParticleSystem.class.getDeclaredField(name);
        f.setAccessible(true);
        return (float[]) f.get(p);
    }
}
