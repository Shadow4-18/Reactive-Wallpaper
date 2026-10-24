package com.reactivewallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import java.io.File;

/** A moving background (animated image or video) that supplies a frame to draw each time. */
interface MediaBackground {
    /** The frame to show now, upright, or null if none is ready yet. */
    Bitmap frame();

    /** Degrees to turn each frame clockwise so it is upright (videos record this separately). */
    default int rotation() {
        return 0;
    }

    /** Size of the frames as stored, before {@link #rotation()}. */
    default int frameWidth() {
        return rotation() % 180 == 0 ? width() : height();
    }

    default int frameHeight() {
        return rotation() % 180 == 0 ? height() : width();
    }

    /** Upright size of the frames. */
    int width();

    int height();

    /** Called while the wallpaper is hidden or shown again. */
    void setPlaying(boolean playing);

    void release();

    /** Opens the right kind of background, or null if this phone can't play it. */
    static MediaBackground open(Context context, File media, int type) {
        if (media.length() == 0) {
            return null;
        }
        try {
            if (type == WallpaperSettings.MEDIA_ANIMATED && Build.VERSION.SDK_INT >= 28) {
                return AnimatedBackground.open(media);
            }
            if (type == WallpaperSettings.MEDIA_VIDEO && Build.VERSION.SDK_INT >= 29) {
                return VideoBackground.open(context, media);
            }
        } catch (Exception e) {
            Log.w("MediaBackground", "Can't play background", e);
        }
        return null;
    }
}
