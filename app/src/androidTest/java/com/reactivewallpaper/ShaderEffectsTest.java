package com.reactivewallpaper;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.util.Random;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The shader effects only run on a hardware canvas, so these frames are drawn
 * by the phone's real GPU renderer (HardwareRenderer into an ImageReader).
 */
@RunWith(AndroidJUnit4.class)
public class ShaderEffectsTest {
    private static final int W = 1080;
    private static final int H = 2400;
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private long now;

    @After
    public void restoreClock() {
        AudioAnalyzer.clock = android.os.SystemClock::uptimeMillis;
    }

    @Test
    public void shaderCompiles() {
        assertNull(ShaderEffects.compileError());
    }

    @Test
    public void effectsOnTheGpu() throws Exception {
        Bitmap image = TestScenes.neon(1200, 2660);
        snapGpu("50_gpu_plain", image, s -> s.style = WallpaperSettings.STYLE_NONE, -1);
        snapGpu("51_gpu_bass_ripple", image, s -> {
            s.style = WallpaperSettings.STYLE_NONE;
            s.fxRipple = 2f;
        }, -1);
        snapGpu("52_gpu_split_glitch", image, s -> {
            s.style = WallpaperSettings.STYLE_BARS;
            s.fxSplit = 2f;
            s.fxGlitch = 2f;
        }, -1);
        snapGpu("53_gpu_haze_grain_vignette", image, s -> {
            s.style = WallpaperSettings.STYLE_NONE;
            s.fxHaze = 2f;
            s.fxGrain = 1f;
            s.fxVignette = 1f;
        }, -1);
        snapGpu("54_gpu_tap_ripple", image, s -> {
            s.style = WallpaperSettings.STYLE_NONE;
            s.tapEffect = WallpaperSettings.TAP_RIPPLE;
        }, 80);
    }

    /** Renders 92 frames of music on the GPU; a tap lands at frame {@code tapFrame} (or never). */
    private void snapGpu(String name, Bitmap image, Consumer<WallpaperSettings> tweak, int tapFrame) throws Exception {
        WallpaperSettings s = new WallpaperSettings();
        tweak.accept(s);
        now = 0;
        AudioAnalyzer.clock = () -> now;
        AudioAnalyzer analyzer = new AudioAnalyzer(s.barCount);
        s.applyTo(analyzer, false, false);
        WallpaperRenderer renderer = new WallpaperRenderer(2.75f);
        renderer.setImage(image.copy(Bitmap.Config.ARGB_8888, false), null);

        ImageReader reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        HardwareRenderer gpu = new HardwareRenderer();
        gpu.setSurface(reader.getSurface());
        RenderNode root = new RenderNode("test");
        root.setPosition(0, 0, W, H);
        gpu.setContentRoot(root);
        Random random = new Random(11);
        Bitmap last = null;
        for (int f = 0; f < 92; f++) {
            now += 33;
            analyzer.submit(TestScenes.music(now, random), 44100);
            analyzer.update(0.033f);
            if (f == tapFrame) {
                renderer.touch(W * 0.5f, H * 0.4f, false, s);
            }
            RecordingCanvas canvas = root.beginRecording();
            renderer.draw(canvas, W, H, analyzer, s, 0.033f);
            root.endRecording();
            gpu.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            Image frame = reader.acquireLatestImage();
            if (frame != null) {
                if (f == 91) {
                    HardwareBuffer buffer = frame.getHardwareBuffer();
                    last = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
                            .copy(Bitmap.Config.ARGB_8888, false);
                    buffer.close();
                }
                frame.close();
            }
        }
        gpu.destroy();
        reader.close();
        renderer.release();
        assertNotNull("no frame came back from the GPU for " + name, last);
        TestScenes.saveShot(context, "render_" + name, last);
    }
}
