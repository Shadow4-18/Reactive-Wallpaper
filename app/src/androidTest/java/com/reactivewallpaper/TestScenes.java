package com.reactivewallpaper;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Pictures, music and screenshot saving for the device tests. The pictures are
 * drawn here (neon rings on a night sky) so no real artwork goes in the repo.
 */
final class TestScenes {
    private TestScenes() {}

    /** A portrait neon scene, roughly like the wallpapers Resona is used with. */
    static Bitmap neon(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF05030F, 0xFF1C0B33, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        Random random = new Random(5);
        for (int i = 0; i < 260; i++) {
            p.setColor(random.nextBoolean() ? 0xFFFF4FD8 : 0xFF3FE6FF);
            p.setAlpha(80 + random.nextInt(170));
            c.drawCircle(random.nextFloat() * w, random.nextFloat() * h, 1 + random.nextFloat() * 3, p);
        }
        float cx = w / 2f;
        float cy = h * 0.58f;
        ring(c, cx, cy, w * 0.36f, 0xFFFF3DCB, w);
        ring(c, cx, cy, w * 0.27f, 0xFF22E6FF, w);
        // A dark figure in front of the rings, like a subject in the art.
        p.setColor(0xFF0A0710);
        c.drawCircle(cx, cy + w * 0.05f, w * 0.09f, p);
        c.drawRect(cx - w * 0.16f, cy + w * 0.13f, cx + w * 0.16f, h * 0.86f, p);
        // Reflective water at the bottom.
        p.setShader(new LinearGradient(0, h * 0.86f, 0, h, 0xFF2A0E3A, 0xFF07040D, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.86f, w, h, p);
        return b;
    }

    private static void ring(Canvas c, float cx, float cy, float r, int color, int w) {
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setStyle(Paint.Style.STROKE);
        glow.setColor(color);
        glow.setStrokeWidth(w * 0.03f);
        glow.setMaskFilter(new BlurMaskFilter(w * 0.03f, BlurMaskFilter.Blur.NORMAL));
        glow.setAlpha(150);
        c.drawCircle(cx, cy, r, glow);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(color);
        line.setStrokeWidth(w * 0.008f);
        c.drawCircle(cx, cy, r, line);
    }

    /** A wide landscape picture, for the fit modes. */
    static Bitmap landscape(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, w, 0, 0xFF0B1A3A, 0xFF3A0B33, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        ring(c, w * 0.3f, h * 0.5f, h * 0.3f, 0xFF22E6FF, h);
        ring(c, w * 0.7f, h * 0.5f, h * 0.3f, 0xFFFF3DCB, h);
        return b;
    }

    /** A square "album cover". */
    static Bitmap cover(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(size * 0.4f, size * 0.4f, size * 0.8f,
                0xFFFFB04F, 0xFF6A1B9A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
        p.setShader(null);
        p.setColor(Color.WHITE);
        p.setTextSize(size * 0.12f);
        c.drawText("RESONA", size * 0.1f, size * 0.9f, p);
        return b;
    }

    /**
     * One FFT frame of a 120 BPM groove at time {@code ms}: a kick every 500 ms
     * over a melody, with hats. Deterministic for a given random.
     */
    static float[] music(long ms, Random random) {
        int bins = 512;
        float binHz = 44100 / (2f * bins);
        float t = ms / 1000f;
        float kick = (float) Math.exp(-(t % 0.5f) * 9f);
        float hat = (float) Math.exp(-(t % 0.25f) * 30f);
        float melody = 0.5f + 0.5f * (float) Math.sin(t * 1.7f);
        float[] mags = new float[bins];
        for (int k = 0; k < bins; k++) {
            float f = k * binHz;
            float v = 0.01f + 0.01f * random.nextFloat();
            if (f < 150f) {
                v += 0.9f * kick;
            }
            if (f > 200f && f < 2500f) {
                v += 0.15f * melody * (0.6f + 0.4f * random.nextFloat());
            }
            if (f > 5000f) {
                v += 0.08f * hat * random.nextFloat();
            }
            mags[k] = v;
        }
        return mags;
    }

    static void savePng(Bitmap bitmap, File file) throws IOException {
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** Screenshots go to the app's files, where the test script collects them. */
    static void saveShot(Context context, String name, Bitmap bitmap) throws IOException {
        savePng(bitmap, new File(new File(context.getFilesDir(), "screens"), name + ".png"));
    }
}
