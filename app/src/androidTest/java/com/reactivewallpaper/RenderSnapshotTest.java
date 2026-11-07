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
    /** When set, the next snapshot's picture gets this subject mask. */
    private Bitmap subjectMask;
    /** Phone tilt held during the next snapshot (-1..1). */
    private float tiltX;

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
        Bitmap copy = image.copy(Bitmap.Config.ARGB_8888, false);
        renderer.setImage(copy, mask);
        renderer.setFramed(framed);
        if (subjectMask != null) {
            renderer.setSubject(SubjectLayer.build(copy, subjectMask));
        }
        renderer.setTilt(tiltX, 0f);
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
        snap("06_mirror", image, true, s -> s.style = WallpaperSettings.STYLE_MIRROR);
        snap("07_led_meter", image, true, s -> s.style = WallpaperSettings.STYLE_LED);
        snap("08_orb", image, true, s -> {
            s.style = WallpaperSettings.STYLE_ORB;
            s.circleY = 0.58f;
            s.circleSize = 0.3f;
        });
        snap("09_neon_lines", image, true, s -> s.style = WallpaperSettings.STYLE_LINES);
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
    public void cameraAndTouch() throws Exception {
        Bitmap image = TestScenes.neon(1200, 2660);
        snap("40_camera_punch_tilt", image, true, s -> {
            s.style = WallpaperSettings.STYLE_NONE;
            s.camPunch = 2f;
            s.camTilt = 2f;
        });
        // Taps land partway through, so each effect is caught mid-flight.
        String[] names = {"ripple", "burst", "flare"};
        int[] kinds = {WallpaperSettings.TAP_RIPPLE, WallpaperSettings.TAP_BURST, WallpaperSettings.TAP_FLARE};
        for (int i = 0; i < kinds.length; i++) {
            int kind = kinds[i];
            snapWithTap("4" + (i + 1) + "_tap_" + names[i], image, kind);
        }
    }

    private void snapWithTap(String name, Bitmap image, int kind) throws Exception {
        WallpaperSettings s = new WallpaperSettings();
        s.style = WallpaperSettings.STYLE_NONE;
        s.tapEffect = kind;
        now = 0;
        AudioAnalyzer.clock = () -> now;
        AudioAnalyzer analyzer = new AudioAnalyzer(s.barCount);
        s.applyTo(analyzer, false, false);
        WallpaperRenderer renderer = new WallpaperRenderer(DENSITY);
        renderer.setImage(image.copy(Bitmap.Config.ARGB_8888, false), null);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        for (int f = 0; f < 40; f++) {
            now += 33;
            if (f == 30) {
                renderer.touch(W * 0.5f, H * 0.35f, false, s);
            }
            if (kind == WallpaperSettings.TAP_BURST && f > 30 && f < 36) {
                // A short drag, for the spark trail.
                renderer.touch(W * (0.5f + 0.05f * (f - 30)), H * (0.35f + 0.02f * (f - 30)), true, s);
            }
            analyzer.update(0.033f);
            renderer.draw(canvas, W, H, analyzer, s, 0.033f);
        }
        TestScenes.saveShot(context, "render_" + name, out);
        renderer.release();
        out.recycle();
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

    /** A mask of the dark figure in {@link TestScenes#neon}, as a painter would mark it. */
    static Bitmap figureMask() {
        Bitmap mask = Bitmap.createBitmap(300, 665, Bitmap.Config.ARGB_8888);
        mask.eraseColor(0xFF000000);
        Canvas c = new Canvas(mask);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(150, 400.7f, 27, p);
        c.drawRect(102, 424.7f, 198, 571.9f, p);
        return mask;
    }

    @Test
    public void subjectInFront() throws Exception {
        Bitmap image = TestScenes.neon(1200, 2660);
        subjectMask = figureMask();
        try {
            // Bars run behind the figure; strong particles and glow too.
            snap("60_subject_front", image, true, s -> {
                s.sparkleAmount = 1f;
                s.subjectAura = 1f;
                s.tiltParallax = 1f;
            });
            tiltX = 1f;
            snap("61_subject_depth_tilted", image, true, s -> {
                s.subjectDepth = 1f;
                s.tiltParallax = 1f;
            });
            tiltX = 0f;
            snap("62_subject_off", image, true, s -> s.subjectFront = false);
            snap("67_subject_viz_in_front", image, true, s -> s.vizFront = true);
            snap("63_subject_ring", image, true, s -> {
                s.style = WallpaperSettings.STYLE_CIRCLE;
                s.circleY = 0.58f;
                s.circleSize = 0.36f;
                s.subjectAura = 0.8f;
            });
            // The pieces themselves: the cut-out, and the background with the subject filled in.
            SubjectLayer layer = SubjectLayer.build(image, subjectMask);
            TestScenes.saveShot(context, "render_64_subject_cutout", layer.cutout);
            TestScenes.saveShot(context, "render_65_subject_plate", layer.plate);
            layer.release();
        } finally {
            subjectMask = null;
            tiltX = 0f;
        }
    }

    @Test
    public void ringAutoAlign() throws Exception {
        Bitmap image = TestScenes.neon(1200, 2660);
        Bitmap small = Bitmap.createScaledBitmap(image, 108, 240, true);
        int[] px = new int[108 * 240];
        small.getPixels(px, 0, 108, 0, 0, 108, 240);
        RingFinder.Ring ring = RingFinder.find(px, 108, 240);
        org.junit.Assert.assertNotNull("no ring found in the test scene", ring);
        // The scene's outer ring: centered, 58% down, radius 36% of the width.
        org.junit.Assert.assertEquals(54f, ring.x, 4f);
        org.junit.Assert.assertEquals(0.58f * 240, ring.y, 4f);
        org.junit.Assert.assertEquals(0.36f * 108, ring.radius, 4f);
        WallpaperSettings base = new WallpaperSettings();
        float[] fit = WallpaperRenderer.fit(108, 240, W, H, base, true);
        float x = (W - 108 * fit[0]) / 2f + fit[2] + ring.x * fit[0];
        float y = (H - 240 * fit[1]) / 2f + fit[3] + ring.y * fit[1];
        float size = ring.radius * fit[0] / Math.min(W, H);
        snap("10_ring_auto_aligned", image, true, s -> {
            s.style = WallpaperSettings.STYLE_CIRCLE;
            s.circleX = x / W;
            s.circleY = y / H;
            s.circleSize = size;
        });
    }

    @Test
    public void unlockAndCharging() throws Exception {
        Bitmap image = TestScenes.neon(1200, 2660);
        String[] names = {"fade", "zoom", "wave"};
        int[] kinds = {WallpaperSettings.INTRO_FADE, WallpaperSettings.INTRO_ZOOM, WallpaperSettings.INTRO_WAVE};
        for (int i = 0; i < kinds.length; i++) {
            int kind = kinds[i];
            // About a third of the way in.
            snapMoment("7" + i + "_unlock_" + names[i], image, 13, r -> {
                WallpaperSettings s = new WallpaperSettings();
                s.unlockIntro = kind;
                r.playIntro(s);
            });
        }
        snapMoment("73_charging", image, 25, r -> r.playCharge(57));
    }

    /** Plays a moment over a picture with music and catches it {@code framesIn} frames later. */
    private void snapMoment(String name, Bitmap image, int framesIn, Consumer<WallpaperRenderer> start)
            throws Exception {
        WallpaperSettings s = new WallpaperSettings();
        s.style = WallpaperSettings.STYLE_NONE;
        now = 0;
        AudioAnalyzer.clock = () -> now;
        AudioAnalyzer analyzer = new AudioAnalyzer(s.barCount);
        s.applyTo(analyzer, false, false);
        WallpaperRenderer renderer = new WallpaperRenderer(DENSITY);
        renderer.setImage(image.copy(Bitmap.Config.ARGB_8888, false), null);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Random random = new Random(11);
        int startFrame = 40;
        for (int f = 0; f < startFrame + framesIn; f++) {
            now += 33;
            analyzer.submit(TestScenes.music(now, random), 44100);
            if (f == startFrame) {
                start.accept(renderer);
            }
            analyzer.update(0.033f);
            renderer.draw(canvas, W, H, analyzer, s, 0.033f);
        }
        TestScenes.saveShot(context, "render_" + name, out);
        renderer.release();
        out.recycle();
    }
}
