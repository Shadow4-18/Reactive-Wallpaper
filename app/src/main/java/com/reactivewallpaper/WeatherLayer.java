package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * Rain, snow, fog or a thunderstorm over the wallpaper, in natural colors
 * (never tinted by the look's colors or hue shift). Rain falls in depth
 * layers with fading streaks and splashes; snow drifts in soft flakes, the
 * near ones out of focus; a storm darkens the sky and throws real bolts,
 * on big beats and now and then on its own. The music only nudges it: rain
 * falls harder and snow sparkles a little on the beat.
 */
final class WeatherLayer {
    private static final int MAX_DROPS = 320;
    private static final int MAX_SPLASHES = 48;
    private static final int FOG_BANKS = 4;
    /** Natural colors: cool gray-white rain, white snow, blue-white lightning. */
    private static final int RAIN_RGB = 0xC8D4E2;
    private static final int SNOW_RGB = 0xFAFCFF;
    private static final int BOLT_CORE = 0xFFF4F6FF;
    private static final int BOLT_GLOW = 0xA6C4FF;

    private final float density;
    private final Random random = new Random(7);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sprite = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF dst = new android.graphics.RectF();
    // Each drop or flake: position (0..1 of the screen), speed, depth (0 far .. 1 near), phase.
    private final float[] x = new float[MAX_DROPS];
    private final float[] y = new float[MAX_DROPS];
    private final float[] speed = new float[MAX_DROPS];
    private final float[] depth = new float[MAX_DROPS];
    private final float[] phase = new float[MAX_DROPS];
    /** Where a near raindrop lands (0..1 of the height), so splashes are spread over the ground. */
    private final float[] land = new float[MAX_DROPS];
    // Splashes: position (px), age (s), size.
    private final float[] splashX = new float[MAX_SPLASHES];
    private final float[] splashY = new float[MAX_SPLASHES];
    private final float[] splashAge = new float[MAX_SPLASHES];
    private final float[] splashSize = new float[MAX_SPLASHES];
    private int nextSplash;
    private final float[] fogX = new float[FOG_BANKS];
    private Bitmap flake;
    private float time;
    private float gust;

    // Lightning.
    /** The main channel, then thinner forks off it, then their forks. */
    private final Path[] bolt = {new Path(), new Path(), new Path()};
    private float flash;
    private float boltLife;
    /** How soon a new storm's first strike comes, in seconds (tests time it to a frame). */
    static float firstStrikeSeconds = 2f;
    private float strikeIn = firstStrikeSeconds;
    private float lastBeat;
    private int flickers;

    private int lastKind = -1;

    WeatherLayer(float density) {
        this.density = density;
        for (int i = 0; i < MAX_DROPS; i++) {
            respawn(i, true);
        }
        for (int i = 0; i < FOG_BANKS; i++) {
            fogX[i] = random.nextFloat();
        }
        for (int i = 0; i < MAX_SPLASHES; i++) {
            splashAge[i] = 1f;
        }
    }

    private void respawn(int i, boolean anywhere) {
        x[i] = random.nextFloat() * 1.2f - 0.1f;
        y[i] = anywhere ? random.nextFloat() : -0.05f - random.nextFloat() * 0.15f;
        speed[i] = 0.85f + random.nextFloat() * 0.3f;
        // Most drops are far away; a few are close.
        float d = random.nextFloat();
        depth[i] = d * d;
        phase[i] = random.nextFloat() * 6.28f;
        land[i] = 0.72f + random.nextFloat() * 0.3f;
    }

    /**
     * @param kind     a {@link Weather} kind
     * @param strength 0..1, the weather's own intensity times the look's amount
     * @param wind     -1..1, how far rain and snow lean
     */
    void draw(Canvas canvas, int w, int h, int kind, float strength, float wind, float bass, float beat, float dt) {
        time += dt;
        // The wind comes and goes a little.
        gust = (float) (Math.sin(time * 0.37f) * 0.6f + Math.sin(time * 0.91f + 1.3f) * 0.4f);
        if (kind != lastKind) {
            lastKind = kind;
            for (int i = 0; i < MAX_DROPS; i++) {
                respawn(i, true);
            }
        }
        if (strength <= 0.01f) {
            return;
        }
        switch (kind) {
            case Weather.RAIN:
                darkenSky(canvas, w, h, 0.18f * strength);
                drawRain(canvas, w, h, strength, wind, bass, dt);
                break;
            case Weather.STORM:
                darkenSky(canvas, w, h, 0.32f * Math.max(0.6f, strength));
                drawRain(canvas, w, h, Math.max(0.65f, strength), wind * 1.4f + 0.1f, bass, dt);
                drawLightning(canvas, w, h, beat, dt);
                break;
            case Weather.SNOW:
                drawSnow(canvas, w, h, strength, wind, beat, dt);
                break;
            case Weather.FOG:
            case Weather.CLOUDY:
                drawFog(canvas, w, h, kind == Weather.FOG ? strength : strength * 0.45f, bass, dt);
                break;
            default:
                break;
        }
    }

    /** Overcast: the top of the sky dims, as under heavy cloud. */
    private void darkenSky(Canvas canvas, int w, int h, float amount) {
        int a = (int) (255 * Math.min(0.45f, amount));
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, h, new int[] {Color.argb(a, 8, 10, 16),
                Color.argb(a / 2, 8, 10, 16), Color.argb(a / 4, 8, 10, 16)}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    private void drawRain(Canvas canvas, int w, int h, float strength, float wind, float bass, float dt) {
        int count = Math.round(MAX_DROPS * Math.min(1f, 0.3f + 0.7f * strength));
        float lean = 0.1f + (wind + 0.15f * gust) * 0.3f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setShader(null);
        // Real rain is fast: a drop crosses the screen in well under a second.
        float fall = 1.9f * (1f + 0.35f * bass);
        for (int i = 0; i < count; i++) {
            float d = depth[i];
            float v = speed[i] * (0.55f + 0.9f * d) * fall;
            y[i] += v * dt;
            x[i] += v * lean * dt * h / w;
            // Drops blown off one side come back in on the other, so the wind never leaves a
            // corner dry (they'd otherwise all start along the top and drift one way).
            if (x[i] > 1.1f) {
                x[i] -= 1.2f;
            } else if (x[i] < -0.1f) {
                x[i] += 1.2f;
            }
            boolean near = d > 0.45f;
            if (y[i] > (near ? land[i] : 1.05f)) {
                if (near) {
                    splash(x[i] * w, land[i] * h, d);
                }
                respawn(i, false);
                continue;
            }
            // A streak as long as the drop travels in a frame or two (motion blur), fading at its tail.
            float length = (14f + 44f * d) * density * (0.8f + 0.25f * bass);
            float px = x[i] * w;
            float py = y[i] * h;
            float tx = px - lean * length;
            float ty = py - length;
            float width = (0.5f + 1.3f * d) * density;
            int alpha = (int) ((26 + 70 * d) * Math.min(1f, 0.45f + strength));
            paint.setStrokeWidth(width);
            paint.setColor((alpha / 3 << 24) | RAIN_RGB);
            canvas.drawLine(px, py, tx, ty, paint);
            // The head is brighter than the tail.
            paint.setColor((alpha << 24) | RAIN_RGB);
            canvas.drawLine(px, py, px - lean * length * 0.45f, py - length * 0.45f, paint);
        }
        drawSplashes(canvas, dt);
        paint.setStyle(Paint.Style.FILL);
    }

    private void splash(float px, float py, float d) {
        int i = nextSplash;
        nextSplash = (nextSplash + 1) % MAX_SPLASHES;
        splashX[i] = px;
        splashY[i] = py;
        splashAge[i] = 0f;
        splashSize[i] = (3f + 7f * d) * density;
    }

    private void drawSplashes(Canvas canvas, float dt) {
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < MAX_SPLASHES; i++) {
            if (splashAge[i] >= 0.3f) {
                continue;
            }
            splashAge[i] += dt;
            float t = Math.min(1f, splashAge[i] / 0.3f);
            float r = splashSize[i] * (0.4f + t);
            int alpha = (int) (90 * (1f - t));
            paint.setColor((alpha << 24) | RAIN_RGB);
            paint.setStrokeWidth(0.8f * density);
            // A flat ring on the ground, and two droplets thrown up.
            dst.set(splashX[i] - r, splashY[i] - r * 0.28f, splashX[i] + r, splashY[i] + r * 0.28f);
            canvas.drawOval(dst, paint);
            float up = splashSize[i] * 1.6f * t * (1f - t);
            canvas.drawPoint(splashX[i] - r * 0.6f, splashY[i] - up, paint);
            canvas.drawPoint(splashX[i] + r * 0.5f, splashY[i] - up * 0.8f, paint);
        }
    }

    /** A soft round flake: bright middle, feathered edge. */
    private Bitmap flake() {
        if (flake == null) {
            int s = 48;
            flake = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                    new int[] {0xFF000000 | SNOW_RGB, 0xCC000000 | SNOW_RGB, 0x40000000 | SNOW_RGB, SNOW_RGB},
                    new float[] {0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            new Canvas(flake).drawCircle(s / 2f, s / 2f, s / 2f, p);
        }
        return flake;
    }

    private void drawSnow(Canvas canvas, int w, int h, float strength, float wind, float beat, float dt) {
        int count = Math.round(MAX_DROPS * 0.75f * Math.min(1f, 0.3f + 0.7f * strength));
        Bitmap f = flake();
        for (int i = 0; i < count; i++) {
            float d = depth[i];
            // Snow falls slowly, near flakes faster (they're closer), all of them swaying.
            float v = 0.035f + 0.11f * d * speed[i];
            y[i] += v * dt;
            float sway = (float) Math.sin(time * (0.6f + 0.5f * speed[i]) + phase[i]);
            x[i] += ((wind + 0.3f * gust) * 0.05f + sway * 0.02f) * (0.4f + d) * dt;
            if (y[i] > 1.04f) {
                respawn(i, false);
            }
            if (x[i] < -0.1f) {
                x[i] += 1.2f;
            } else if (x[i] > 1.1f) {
                x[i] -= 1.2f;
            }
            // Near flakes are big and out of focus (softer, fainter); far ones small and crisp.
            float r = (1.1f + 7f * d * d) * density;
            float alpha = (0.85f - 0.4f * d * d) * (0.9f + 0.15f * beat);
            sprite.setAlpha((int) (255 * Math.min(1f, alpha)));
            float px = x[i] * w;
            float py = y[i] * h;
            dst.set(px - r, py - r, px + r, py + r);
            canvas.drawBitmap(f, null, dst, sprite);
        }
    }

    private void drawFog(Canvas canvas, int w, int h, float strength, float bass, float dt) {
        float amount = Math.min(1f, strength) * (0.85f + 0.2f * bass);
        paint.setStyle(Paint.Style.FILL);
        // Mist lying low: clear at the top, thickest along the bottom, fading smoothly in between.
        int base = (int) (110 * amount);
        paint.setShader(new LinearGradient(0, h * 0.3f, 0, h, new int[] {Color.TRANSPARENT,
                Color.argb(base / 3, 205, 210, 218), Color.argb(base, 215, 219, 226)}, new float[] {0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.3f, w, h, paint);
        // Soft wisps drifting sideways through it.
        for (int i = 0; i < FOG_BANKS; i++) {
            fogX[i] += dt * (0.01f + 0.005f * i) * (i % 2 == 0 ? 1 : -1);
            if (fogX[i] > 1.3f) {
                fogX[i] = -0.3f;
            } else if (fogX[i] < -0.3f) {
                fogX[i] = 1.3f;
            }
            float cx = fogX[i] * w;
            float cy = h * (0.55f + 0.12f * i);
            float r = Math.min(w, h) * (0.45f + 0.08f * i);
            int alpha = (int) (55 * amount);
            paint.setShader(new RadialGradient(cx, cy, r, new int[] {Color.argb(alpha, 220, 223, 230),
                    Color.argb(alpha / 3, 210, 214, 222), Color.TRANSPARENT}, new float[] {0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, paint);
        }
        paint.setShader(null);
    }

    private void drawLightning(Canvas canvas, int w, int h, float beat, float dt) {
        // Strikes come with big beats (not too often), and every so often in silence too.
        strikeIn -= dt;
        boolean onBeat = beat > 0.5f && lastBeat <= 0.5f && strikeIn < 3f && random.nextFloat() < 0.5f;
        lastBeat = beat;
        if (onBeat || strikeIn <= 0f) {
            strike(w, h);
            strikeIn = 5f + random.nextFloat() * 9f;
        }
        if (flash > 0.01f) {
            // The whole sky lights up for a moment, brightest at the top.
            paint.setStyle(Paint.Style.FILL);
            int a = (int) (170 * flash);
            paint.setShader(new LinearGradient(0, 0, 0, h, new int[] {Color.argb(a, 225, 232, 255),
                    Color.argb(a / 3, 210, 220, 255)}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
        if (boltLife > 0f) {
            float a = Math.min(1f, boltLife / 0.08f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            // Per channel: a faint wide glow, a brighter halo, then a thin white-hot core;
            // forks are thinner and dimmer than the main channel.
            for (int level = 0; level < bolt.length; level++) {
                float k = level == 0 ? 1f : level == 1 ? 0.5f : 0.3f;
                float fade = level == 0 ? 1f : level == 1 ? 0.75f : 0.5f;
                paint.setColor(((int) (38 * a * fade) << 24) | BOLT_GLOW);
                paint.setStrokeWidth(10f * k * density);
                canvas.drawPath(bolt[level], paint);
                paint.setColor(((int) (110 * a * fade) << 24) | BOLT_GLOW);
                paint.setStrokeWidth(3.2f * k * density);
                canvas.drawPath(bolt[level], paint);
                paint.setColor(BOLT_CORE & 0x00FFFFFF | ((int) (255 * a * fade) << 24));
                paint.setStrokeWidth(Math.max(0.6f, 1.3f * k) * density);
                canvas.drawPath(bolt[level], paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        // Lightning flickers: a few quick re-flashes before it's gone.
        flash *= (float) Math.pow(0.004, dt);
        boltLife -= dt;
        if (flickers > 0 && flash < 0.25f) {
            flickers--;
            flash = 0.55f + 0.35f * random.nextFloat();
            boltLife = Math.max(boltLife, 0.07f);
        }
    }

    private void strike(int w, int h) {
        flash = 1f;
        boltLife = 0.22f;
        flickers = 1 + random.nextInt(3);
        for (Path p : bolt) {
            p.reset();
        }
        float x0 = w * (0.12f + 0.76f * random.nextFloat());
        float bottom = h * (0.45f + 0.3f * random.nextFloat());
        branch(x0, -h * 0.02f, x0 + (random.nextFloat() - 0.5f) * w * 0.3f, bottom, 0, h);
    }

    /** A jagged channel from one point to another, now and then forking. */
    private void branch(float x0, float y0, float x1, float y1, int level, int h) {
        int steps = 14 - level * 4;
        float jag = (level == 0 ? 0.045f : 0.03f) * h;
        Path path = bolt[level];
        path.moveTo(x0, y0);
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float nx = x0 + (x1 - x0) * t + (i < steps ? (random.nextFloat() - 0.5f) * jag : 0f);
            float ny = y0 + (y1 - y0) * t + (random.nextFloat() - 0.5f) * jag * 0.3f;
            path.lineTo(nx, ny);
            if (level < 2 && i < steps - 2 && random.nextFloat() < (level == 0 ? 0.22f : 0.12f)) {
                float len = (y1 - y0) * (0.25f + 0.25f * random.nextFloat());
                float side = (random.nextFloat() < 0.5f ? -1f : 1f) * len * (0.4f + 0.5f * random.nextFloat());
                branch(nx, ny, nx + side, ny + len, level + 1, h);
            }
        }
    }

    void release() {
        if (flake != null) {
            flake.recycle();
            flake = null;
        }
    }
}
