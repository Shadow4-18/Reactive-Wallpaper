package com.reactivewallpaper;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * A muted, looping video. Frames are decoded into GPU buffers and handed out
 * as hardware bitmaps, so the wallpaper can draw them (and its glow) like images.
 */
@TargetApi(29)
final class VideoBackground implements MediaBackground {
    private static final String TAG = "VideoBackground";

    private final ImageReader reader;
    private final MediaPlayer player;
    private final int codedWidth;
    private final int codedHeight;
    private final int rotation;
    private volatile boolean frameWaiting;
    private boolean prepared;
    private boolean playing = true;
    // The last two frames stay open: the GPU may still be drawing the older one.
    private Image current;
    private Image previous;
    private Bitmap currentBitmap;

    private VideoBackground(File file, int codedWidth, int codedHeight, int rotation) throws IOException {
        this.codedWidth = codedWidth;
        this.codedHeight = codedHeight;
        this.rotation = rotation;
        Handler main = new Handler(Looper.getMainLooper());
        reader = ImageReader.newInstance(codedWidth, codedHeight, ImageFormat.PRIVATE, 4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        reader.setOnImageAvailableListener(r -> frameWaiting = true, main);
        player = new MediaPlayer();
        player.setDataSource(file.getPath());
        player.setSurface(reader.getSurface());
        player.setLooping(true);
        // Silent, so the video's own sound can't drive the visualizer.
        player.setVolume(0f, 0f);
        player.setOnPreparedListener(mp -> {
            prepared = true;
            if (playing) {
                mp.start();
            }
        });
        player.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "Video playback error " + what + "/" + extra);
            return true;
        });
        player.prepareAsync();
    }

    static VideoBackground open(Context context, File file) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            int w = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int r = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (w <= 0 || h <= 0) {
                return null;
            }
            return new VideoBackground(file, w, h, ((r % 360) + 360) % 360);
        } catch (RuntimeException e) {
            throw new IOException(e);
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static int parse(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Frames arrive as coded (sideways for phone portrait video); this many degrees makes them upright. */
    @Override
    public int rotation() {
        return rotation;
    }

    @Override
    public Bitmap frame() {
        if (frameWaiting) {
            frameWaiting = false;
            Image image = null;
            try {
                image = reader.acquireLatestImage();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Too many frames held", e);
            }
            if (image != null) {
                HardwareBuffer buffer = image.getHardwareBuffer();
                Bitmap bitmap = buffer != null
                        ? Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)) : null;
                if (buffer != null) {
                    buffer.close();
                }
                if (bitmap == null) {
                    image.close();
                } else {
                    if (previous != null) {
                        previous.close();
                    }
                    previous = current;
                    current = image;
                    currentBitmap = bitmap;
                }
            }
        }
        return currentBitmap;
    }

    @Override
    public int width() {
        return rotation % 180 == 0 ? codedWidth : codedHeight;
    }

    @Override
    public int height() {
        return rotation % 180 == 0 ? codedHeight : codedWidth;
    }

    @Override
    public void setPlaying(boolean play) {
        playing = play;
        if (!prepared) {
            return;
        }
        try {
            if (play && !player.isPlaying()) {
                player.start();
            } else if (!play && player.isPlaying()) {
                player.pause();
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "Video not ready", e);
        }
    }

    @Override
    public void release() {
        player.release();
        currentBitmap = null;
        if (current != null) {
            current.close();
        }
        if (previous != null) {
            previous.close();
        }
        reader.close();
    }
}
