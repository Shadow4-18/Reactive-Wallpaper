package com.reactivewallpaper;

import android.graphics.Canvas;
import android.os.SystemClock;

/**
 * One look on screen (the home screen's, or the lock screen's): its settings
 * and a renderer holding its background. Changing the background crossfades
 * from the old renderer to a new one.
 */
final class LookSlot {
    private static final long FADE_MS = 700;

    private final float density;
    WallpaperSettings settings;
    private WallpaperRenderer renderer;
    private WallpaperSettings previousSettings;
    private WallpaperRenderer previous;
    private long fadeStart;
    private String backgroundKey;
    private boolean playing = true;
    private float tiltX;
    private float tiltY;
    private float swipe;
    private boolean paged;

    LookSlot(float density, WallpaperSettings settings) {
        this.density = density;
        this.settings = settings;
    }

    String backgroundKey() {
        return backgroundKey;
    }

    boolean hasBackground() {
        return renderer != null;
    }

    /**
     * Shows a new background. {@code key} names it so callers can skip
     * reloading one that is already shown.
     */
    void setBackground(String key, Background background, boolean fade) {
        WallpaperRenderer next = new WallpaperRenderer(density);
        next.setImage(background.image, background.mask);
        next.setSubject(background.subject);
        next.setFramed(background.framed);
        next.setMedia(background.media);
        next.setMediaPlaying(playing);
        next.setTilt(tiltX, tiltY);
        next.setSwipe(swipe, paged);
        if (previous != null) {
            previous.release();
            previous = null;
        }
        if (fade && renderer != null) {
            previous = renderer;
            previousSettings = settings;
            fadeStart = SystemClock.uptimeMillis();
        } else if (renderer != null) {
            renderer.release();
        }
        renderer = next;
        backgroundKey = key;
    }

    /** True while a crossfade is running (so frames keep coming). */
    boolean isFading() {
        return previous != null;
    }

    void draw(Canvas canvas, int w, int h, AudioAnalyzer audio, float dt) {
        if (renderer == null) {
            return;
        }
        if (previous != null) {
            float t = (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_MS;
            if (t >= 1f) {
                previous.release();
                previous = null;
            } else {
                previous.draw(canvas, w, h, audio, previousSettings, dt);
                int saved = canvas.saveLayerAlpha(0, 0, w, h, (int) (255 * t));
                renderer.draw(canvas, w, h, audio, settings, dt);
                canvas.restoreToCount(saved);
                return;
            }
        }
        renderer.draw(canvas, w, h, audio, settings, dt);
    }

    void setTilt(float x, float y) {
        tiltX = x;
        tiltY = y;
        if (renderer != null) {
            renderer.setTilt(x, y);
        }
        if (previous != null) {
            previous.setTilt(x, y);
        }
    }

    void setSwipe(float position, boolean pages) {
        swipe = position;
        paged = pages;
        if (renderer != null) {
            renderer.setSwipe(position, pages);
        }
        if (previous != null) {
            previous.setSwipe(position, pages);
        }
    }

    void touch(float x, float y, boolean moving) {
        if (renderer != null) {
            renderer.touch(x, y, moving, settings);
        }
    }

    void playIntro() {
        if (renderer != null) {
            renderer.playIntro(settings);
        }
    }

    void playPulse(int color) {
        if (renderer != null && settings.notifyPulse) {
            renderer.playPulse(settings.notifyUseAppColor ? color : 0);
        }
    }

    void playCharge(int percent) {
        if (renderer != null && settings.chargeEffect) {
            renderer.playCharge(percent);
        }
    }

    /** True while a tap effect or camera move is still playing out. */
    boolean isBusy() {
        return renderer != null && renderer.isBusy();
    }

    void setSaving(boolean saving) {
        if (renderer != null) {
            renderer.setSaving(saving);
        }
    }

    /** Pauses or resumes a video/animated background while the wallpaper is hidden. */
    void setPlaying(boolean play) {
        playing = play;
        if (renderer != null) {
            renderer.setMediaPlaying(play);
        }
    }

    void release() {
        if (renderer != null) {
            renderer.release();
            renderer = null;
        }
        if (previous != null) {
            previous.release();
            previous = null;
        }
        backgroundKey = null;
    }

    /** What a renderer needs to show a background; ownership passes to the slot. */
    static final class Background {
        final android.graphics.Bitmap image;
        final android.graphics.Bitmap mask;
        final MediaBackground media;
        /** Built from the image and its subject mask, or null. */
        final SubjectLayer subject;
        /** False for backgrounds already shaped for the screen (album art), which skip framing. */
        final boolean framed;

        Background(android.graphics.Bitmap image, android.graphics.Bitmap mask, MediaBackground media,
                   SubjectLayer subject, boolean framed) {
            this.image = image;
            this.mask = mask;
            this.media = media;
            this.subject = subject;
            this.framed = framed;
        }
    }
}
