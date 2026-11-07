package com.reactivewallpaper;

import android.graphics.Canvas;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the wallpaper into an MP4: H.264 video, each frame drawn on the
 * encoder's input surface with the GPU, plus (optionally) AAC sound from an
 * {@link AudioRecord}: the microphone, or the music playing on the phone.
 * Video frames are timed by when they're drawn and sound by the samples read,
 * both on the same clock, so call {@link #frame} at the rate it should play.
 */
final class ClipRecorder {
    private static final String TAG = "ClipRecorder";
    private static final String VIDEO = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO = MediaFormat.MIMETYPE_AUDIO_AAC;

    interface Painter {
        void paint(Canvas canvas, int width, int height);
    }

    private final File file;
    private final int width;
    private final int height;
    private final MediaCodec video;
    private final Surface input;
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
    private int frames;

    // Sound (optional), read and encoded on its own thread.
    private final AudioRecord record;
    private MediaCodec audio;
    private Thread audioThread;
    private volatile boolean recordingAudio;
    private int audioRate;
    private int audioChannels;

    // The muxer starts once every track's format is known; samples wait until then.
    private final Object muxLock = new Object();
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean muxing;
    private boolean wroteVideo;
    /** The sound never produced anything (e.g. the microphone was busy): keep the video alone. */
    private boolean soundGaveUp;
    private final List<Pending> pending = new ArrayList<>();

    private static final class Pending {
        final boolean isVideo;
        final ByteBuffer data;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        Pending(boolean isVideo, ByteBuffer source, MediaCodec.BufferInfo from) {
            this.isVideo = isVideo;
            data = ByteBuffer.allocate(from.size);
            source.position(from.offset);
            source.limit(from.offset + from.size);
            data.put(source);
            data.flip();
            info.set(0, from.size, from.presentationTimeUs, from.flags);
        }
    }

    /** Picks a size the encoder accepts: {@code width} wide, the screen's shape, multiples of 16. */
    static int[] size(int width, int screenW, int screenH) {
        int w = Math.max(16, width / 16 * 16);
        int h = Math.max(16, Math.round(w * screenH / (float) Math.max(1, screenW) / 16f) * 16);
        return new int[] {w, h};
    }

    ClipRecorder(File file, int width, int height, int fps) throws IOException {
        this(file, width, height, fps, null);
    }

    /**
     * @param sound an initialized, not yet started recorder (16-bit PCM) whose
     *     sound goes into the clip, or null for none. The clip starts and stops it,
     *     and releases it.
     */
    ClipRecorder(File file, int width, int height, int fps, AudioRecord sound) throws IOException {
        this.file = file;
        this.width = width;
        this.height = height;
        this.record = sound;
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        video = MediaCodec.createEncoderByType(VIDEO);
        try {
            video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = video.createInputSurface();
            video.start();
            muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (sound != null) {
                startAudio(sound);
            }
        } catch (RuntimeException | IOException e) {
            video.release();
            if (audio != null) {
                audio.release();
            }
            if (sound != null) {
                sound.release();
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    boolean hasSound() {
        return record != null;
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
        drainVideo(false);
    }

    /** Finishes the file (call off the main thread); returns it, or null if nothing was recorded. */
    File finish() {
        stopAudioThread(true);
        synchronized (muxLock) {
            if (!muxing && audioTrack < 0) {
                soundGaveUp = true;
                maybeStartMuxer();
            }
        }
        try {
            video.signalEndOfInputStream();
            drainVideo(true);
        } catch (RuntimeException e) {
            Log.w(TAG, "Finishing failed", e);
        }
        boolean ok = release();
        return ok ? file : null;
    }

    /** Stops without keeping anything. */
    void cancel() {
        stopAudioThread(false);
        release();
        file.delete();
    }

    private boolean release() {
        try {
            video.stop();
        } catch (RuntimeException ignored) {
            // Already stopped or failed; releasing is what matters.
        }
        video.release();
        input.release();
        if (audio != null) {
            try {
                audio.stop();
            } catch (RuntimeException ignored) {
                // As above.
            }
            audio.release();
        }
        if (record != null) {
            record.release();
        }
        boolean ok;
        synchronized (muxLock) {
            ok = muxing && wroteVideo;
            try {
                if (muxing) {
                    muxer.stop();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Muxer stop failed", e);
                ok = false;
            }
            muxer.release();
        }
        return ok;
    }

    // ---- video ----

    private void drainVideo(boolean end) {
        long deadline = System.currentTimeMillis() + 3000;
        while (true) {
            int index = video.dequeueOutputBuffer(videoInfo, end ? 10_000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || System.currentTimeMillis() > deadline) {
                    return;
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxLock) {
                    videoTrack = muxer.addTrack(video.getOutputFormat());
                    maybeStartMuxer();
                }
            } else if (index >= 0) {
                ByteBuffer data = video.getOutputBuffer(index);
                if (data != null && videoInfo.size > 0
                        && (videoInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    write(true, data, videoInfo);
                }
                video.releaseOutputBuffer(index, false);
                if ((videoInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return;
                }
            }
        }
    }

    // ---- sound ----

    private void startAudio(AudioRecord sound) throws IOException {
        audioRate = sound.getSampleRate();
        audioChannels = sound.getChannelCount();
        MediaFormat format = MediaFormat.createAudioFormat(AUDIO, audioRate, audioChannels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
        audio = MediaCodec.createEncoderByType(AUDIO);
        audio.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audio.start();
        sound.startRecording();
        recordingAudio = true;
        audioThread = new Thread(this::audioLoop, "ClipAudio");
        audioThread.start();
    }

    private void stopAudioThread(boolean keep) {
        if (audioThread == null) {
            return;
        }
        recordingAudio = false;
        try {
            audioThread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        audioThread = null;
    }

    /** Reads sound, encodes it and hands it to the muxer, until stopped; then ends the stream. */
    private void audioLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        // The same clock the video's frame timestamps come from.
        long startUs = System.nanoTime() / 1000;
        long samples = 0;
        int bytesPerFrame = 2 * audioChannels;
        boolean ended = false;
        try {
            while (true) {
                int in = audio.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer buffer = audio.getInputBuffer(in);
                    long pts = startUs + samples * 1_000_000L / audioRate;
                    if (!recordingAudio) {
                        audio.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        ended = true;
                    } else {
                        int capacity = Math.min(buffer.remaining(), 4096) / bytesPerFrame * bytesPerFrame;
                        int read = record.read(buffer, capacity);
                        if (read > 0) {
                            audio.queueInputBuffer(in, 0, read, pts, 0);
                            samples += read / bytesPerFrame;
                        } else {
                            audio.queueInputBuffer(in, 0, 0, pts, 0);
                        }
                    }
                }
                if (drainAudio(info)) {
                    return;
                }
                if (ended) {
                    // Wait for the end of the stream to come out.
                    long deadline = System.currentTimeMillis() + 2000;
                    while (System.currentTimeMillis() < deadline) {
                        if (drainAudio(info)) {
                            return;
                        }
                    }
                    return;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Sound recording failed", e);
        } finally {
            try {
                record.stop();
            } catch (RuntimeException ignored) {
                // Already stopped.
            }
        }
    }

    /** Moves encoded sound to the muxer; true at the end of the stream. */
    private boolean drainAudio(MediaCodec.BufferInfo info) {
        while (true) {
            int index = audio.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return false;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxLock) {
                    audioTrack = muxer.addTrack(audio.getOutputFormat());
                    maybeStartMuxer();
                }
            } else if (index >= 0) {
                ByteBuffer data = audio.getOutputBuffer(index);
                if (data != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    write(false, data, info);
                }
                audio.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return true;
                }
            }
        }
    }

    // ---- muxing ----

    /** Starts once every track is known; must hold muxLock. */
    private void maybeStartMuxer() {
        boolean ready = videoTrack >= 0 && (record == null || audioTrack >= 0 || soundGaveUp);
        if (!ready || muxing) {
            return;
        }
        muxer.start();
        muxing = true;
        for (Pending p : pending) {
            muxer.writeSampleData(p.isVideo ? videoTrack : audioTrack, p.data, p.info);
            wroteVideo |= p.isVideo;
        }
        pending.clear();
    }

    private void write(boolean isVideo, ByteBuffer data, MediaCodec.BufferInfo info) {
        synchronized (muxLock) {
            if (!muxing) {
                // Kept until the other track's format arrives (the first video frame matters).
                if (pending.size() < 600) {
                    pending.add(new Pending(isVideo, data, info));
                }
                return;
            }
            data.position(info.offset);
            data.limit(info.offset + info.size);
            muxer.writeSampleData(isVideo ? videoTrack : audioTrack, data, info);
            wroteVideo |= isVideo;
        }
    }
}
