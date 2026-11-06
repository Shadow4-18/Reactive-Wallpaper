package com.reactivewallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.util.Random;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Draws the real wallpaper renderer into pictures, with simulated music, so each
 * look can be checked by eye. Every frame ends 36 ms after a kick drum, when the
 * music reaction is near its peak.
 */
@RunWith(AndroidJUnit4.class)
public class RenderSnapshotTest {
    private static final int W = 1080;
    private static final int H = 2400;
    private static final float DENSITY = 2.75f;
    /** 92 frames of 33 ms: the last lands 36 ms after a kick. */
    private static final int FRAMES = 92;

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private long now;

    @After
    public void restoreClock() {
        AudioAnalyzer.clock = android.os.SystemClock::uptimeMillis;
    }

    private void snap(String name, Bitmap image, boolean music, Consumer<WallpaperSettings> tweak) throws Exception {
        snap(name, image, null, true, music, tweak);
    }

    private void snap(String name, Bitmap image, Bitmap mask, boolean framed, boolean music,
                      Consumer<WallpaperSettings> tweak) throws Exception {
        WallpaperSettings s = new WallpaperSettings();
        tweak.accept(s);
        now = 0;
        AudioAnalyzer.clock = () -> now;
        AudioAnalyzer analyzer = new AudioAnalyzer(s.barCount);
        s.applyTo(analyzer, false, false);
        WallpaperRenderer renderer = new WallpaperRenderer(DENSITY);
        renderer.setImage(image.copy(Bitmap.Config.ARGB_8888, false), mask);
        renderer.setFramed(framed);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Random random = new Random(11);
        float dt = 0.033f;
        for (int f = 0; f < FRAMES; f++) {
            now += 33;
            if (music) {
                analyzer.submit(TestScenes.music(now, random), 44100);
            }
            analyzer.update(dt);
            renderer.draw(canvas, W, H, analyzer, s, dt);
        }
        TestScenes.saveShot(context, "render_" + name, out);
        renderer.release();
        out.recycle();
    }

    @Test
    public void visualizers() throws Exception {
        Bitmap image = TestScenes.neon(1200, 2660);
        snap("01_bars", image, true, s -> { });
        snap("02_circle", image, true, s -> {
            s.style = WallpaperSettings.STYLE_CIRCLE;
            s.circleY = 0.58f;
            s.circleSize = 0.36f;
        });
        snap("03_wave", image, true, s -> s.style = WallpaperSettings.STYLE_WAVE);
        snap("04_neon_glow_on_beat", image, true, s -> {
            s.style = WallpaperSettings.STYLE_NONE;
            s.flash = 0f;
        });
        snap("05_silence", image, false, s -> s.style = WallpaperSettings.STYLE_NONE);
    }

    @Test
    public void particles() throws Exception {
        Bitmap image = TestScenes.neon(1200, 2660);
        String[] names = {"sparkles", "embers", "stars", "bubbles", "streaks"};
        int[] styles = {WallpaperSettings.PARTICLE_SPARKLES, WallpaperSettings.PARTICLE_EMBERS,
                WallpaperSettings.PARTICLE_STARS, WallpaperSettings.PARTICLE_BUBBLES,
                WallpaperSettings.PARTICLE_STREAKS};
        for (int i = 0; i < styles.length; i++) {
            int style = styles[i];
            snap("1" + i + "_particles_" + names[i], image, true, s -> {
                s.style = WallpaperSettings.STYLE_NONE;
                s.particleStyle = style;
                s.sparkleAmount = 0.6f;
            });
        }
    }

    @Test
    public void colorAndFraming() throws Exception {
        Bitmap image = TestScenes.neon(1200, 2660);
        snap("20_recolor_120", image, true, s -> {
            s.hueShift = 120f;
            s.recolorImage = true;
        });
        Bitmap wide = TestScenes.landscape(1600, 900);
        snap("21_fit_blurred_edges", wide, true, s -> s.fitMode = WallpaperSettings.FIT_FIT);
        snap("22_fit_black_edges", wide, true, s -> {
            s.fitMode = WallpaperSettings.FIT_FIT;
            s.edgeStyle = WallpaperSettings.EDGES_BLACK;
        });
        snap("23_fit_width_zoomed_moved", wide, true, s -> {
            s.fitMode = WallpaperSettings.FIT_WIDTH;
            s.imageZoom = 1.4f;
            s.imagePanY = -0.15f;
        });
        snap("24_stretch", wide, true, s -> s.fitMode = WallpaperSettings.FIT_STRETCH);
    }

    @Test
    public void albumArtAndPaintedGlow() throws Exception {
        Bitmap art = AlbumArt.compose(TestScenes.cover(800), W, H, WallpaperSettings.ART_CARD);
        snap("30_album_art_card", art, null, false, true, s -> { });
        Bitmap image = TestScenes.neon(1200, 2660);
        // Paint the dark figure to glow, and the outer ring not to.
        Bitmap mask = Bitmap.createBitmap(256, 568, Bitmap.Config.ARGB_8888);
        mask.eraseColor(0xFF808080);
        Canvas c = new Canvas(mask);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(0xFFFFFFFF);
        c.drawRect(90, 340, 166, 480, p);
        p.setColor(0xFF000000);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(26);
        c.drawCircle(128, 330, 92, p);
        snap("31_painted_glow", image, mask, true, true, s -> s.style = WallpaperSettings.STYLE_NONE);
    }
}
