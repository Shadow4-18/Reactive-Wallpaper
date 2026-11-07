package com.reactivewallpaper;

import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Records the wallpaper into an MP4 (H.264, no sound): each frame is drawn on
 * the video encoder's input surface with the GPU, so recording costs about as
 * much as showing the preview. Frames are timed by when they're drawn, so call
 * {@link #frame} at the rate it should play back.
 */
final class ClipRecorder {
    private static final String TAG = "ClipRecorder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    interface Painter {
        void paint(Canvas canvas, int width, int height);
    }

    private final File file;
    private final int width;
    private final int height;
    private final MediaCodec codec;
    private final Surface input;
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private int track = -1;
    private boolean muxing;
    private int frames;

    /** Picks a size the encoder accepts: {@code width} wide, the screen's shape, multiples of 16. */
    static int[] size(int width, int screenW, int screenH) {
        int w = Math.max(16, width / 16 * 16);
        int h = Math.max(16, Math.round(w * screenH / (float) Math.max(1, screenW) / 16f) * 16);
        return new int[] {w, h};
    }

    ClipRecorder(File file, int width, int height, int fps) throws IOException {
        this.file = file;
        this.width = width;
        this.height = height;
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        codec = MediaCodec.createEncoderByType(MIME);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface();
            codec.start();
            muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (RuntimeException | IOException e) {
            codec.release();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int frames() {
        return frames;
    }

    /** Draws and encodes one frame. */
    void frame(Painter painter) {
        Canvas canvas = input.lockHardwareCanvas();
        try {
            painter.paint(canvas, width, height);
        } finally {
            input.unlockCanvasAndPost(canvas);
        }
        frames++;
        drain(false);
    }

    /** Finishes the file (call off the main thread); returns it, or null if nothing was recorded. */
    File finish() {
        try {
            codec.signalEndOfInputStream();
            drain(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "Finishing failed", e);
        }
        release();
        return muxing ? file : null;
    }

    /** Stops without keeping anything. */
    void cancel() {
        release();
        file.delete();
    }

    private void release() {
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // Already stopped or failed; releasing is what matters.
        }
        codec.release();
        input.release();
        try {
            if (muxing) {
                muxer.stop();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Muxer stop failed", e);
            muxing = false;
        }
        muxer.release();
    }

    /** Moves encoded data into the file; at the end, waits for the last of it. */
    private void drain(boolean end) {
        long deadline = System.currentTimeMillis() + 3000;
        while (true) {
            int index = codec.dequeueOutputBuffer(info, end ? 10_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || System.currentTimeMillis() > deadline) {
                    return;
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxing = true;
            } else if (index >= 0) {
                ByteBuffer data = codec.getOutputBuffer(index);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (data != null && info.size > 0 && muxing && !config) {
                    data.position(info.offset);
                    data.limit(info.offset + info.size);
                    muxer.writeSampleData(track, data, info);
                }
                codec.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return;
                }
            }
        }
    }
}
