package com.reactivewallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the subject all through a video or GIF, once, in the editor: frames
 * are taken at even steps, the subject is found in each with the same
 * on-device detector as for still pictures, and the masks are saved as a
 * {@link SubjectTrack}. Takes a while (a second or so per step), so it
 * reports progress and can be cancelled.
 */
final class SubjectTrackBuilder {
    private static final String TAG = "SubjectTrackBuilder";
    /** At most this many steps; shorter clips get a step every {@link #MIN_STEP_MS}. */
    private static final int MAX_STEPS = 72;
    private static final int MIN_STEP_MS = 200;
    private static final int FRAME_SIDE = 512;

    interface Listener {
        /** On the main thread, after each step. */
        void progress(int done, int total);

        /** On the main thread: the saved track, or null with a reason (null reason = cancelled). */
        void done(SubjectTrack track, String error);
    }

    private final Context context;
    private final File media;
    private final int mediaType;
    private final File out;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;

    private FrameSource source;
    private final List<byte[]> masks = new ArrayList<>();
    private int steps;
    private int stepMs;
    private long loopMs;
    private int height;
    private int found;

    SubjectTrackBuilder(Context context, File media, int mediaType, File out, Listener listener) {
        this.context = context.getApplicationContext();
        this.media = media;
        this.mediaType = mediaType;
        this.out = out;
        this.listener = listener;
    }

    void cancel() {
        cancelled = true;
    }

    void start() {
        worker.execute(() -> {
            try {
                source = mediaType == WallpaperSettings.MEDIA_VIDEO ? new VideoFrames(media) : new GifFrames(media);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Can't read the frames", e);
                finish(null, mediaType == WallpaperSettings.MEDIA_VIDEO
                        ? "Couldn't read this video's frames."
                        : "Only GIFs and videos can be tracked (not animated WebP).");
                return;
            }
            loopMs = Math.max(1, source.durationMs());
            stepMs = (int) Math.max(MIN_STEP_MS, (loopMs + MAX_STEPS - 1) / MAX_STEPS);
            steps = (int) Math.max(1, Math.min(MAX_STEPS, (loopMs + stepMs - 1) / stepMs));
            next();
        });
    }

    /** Takes the next frame (on the worker), then finds its subject (the detector calls back on main). */
    private void next() {
        if (cancelled) {
            finish(null, null);
            return;
        }
        int index = masks.size();
        if (index >= steps) {
            save();
            return;
        }
        Bitmap frame;
        try {
            // The middle of each step stands for it.
            frame = source.frameAt(Math.min(loopMs - 1, (long) (index + 0.5) * loopMs / steps));
        } catch (RuntimeException e) {
            frame = null;
        }
        if (frame == null) {
            addMask(null);
            return;
        }
        if (height == 0) {
            height = Math.max(8, Math.round(SubjectTrack.WIDTH * frame.getHeight() / (float) frame.getWidth()));
        }
        Bitmap input = frame;
        main.post(() -> SubjectDetector.detect(context, input, (mask, error) -> {
            input.recycle();
            worker.execute(() -> addMask(mask));
        }));
    }

    private void addMask(Bitmap mask) {
        int h = Math.max(8, height);
        byte[] m = new byte[SubjectTrack.WIDTH * h];
        if (mask != null) {
            Bitmap small = Bitmap.createScaledBitmap(mask, SubjectTrack.WIDTH, h, true);
            int[] px = new int[m.length];
            small.getPixels(px, 0, SubjectTrack.WIDTH, 0, 0, SubjectTrack.WIDTH, h);
            if (small != mask) {
                small.recycle();
            }
            mask.recycle();
            boolean any = false;
            for (int i = 0; i < m.length; i++) {
                // Separate subjects come in different color channels; all of them count.
                int v = Math.max((px[i] >> 16) & 0xFF, Math.max((px[i] >> 8) & 0xFF, px[i] & 0xFF));
                m[i] = (byte) v;
                any |= v > 127;
            }
            if (any) {
                found++;
            }
        }
        masks.add(m);
        int done = masks.size();
        main.post(() -> {
            if (!cancelled) {
                listener.progress(done, steps);
            }
        });
        next();
    }

    private void save() {
        if (found == 0) {
            finish(null, "No subject was found in this " + (mediaType == WallpaperSettings.MEDIA_VIDEO
                    ? "video." : "GIF."));
            return;
        }
        // Steps where the detector missed take their neighbors' masks, so the effects don't blink.
        for (int i = 0; i < masks.size(); i++) {
            if (empty(masks.get(i))) {
                for (int d = 1; d < masks.size(); d++) {
                    byte[] near = masks.get((i + d) % masks.size());
                    if (!empty(near)) {
                        masks.set(i, near.clone());
                        break;
                    }
                }
            }
        }
        Bitmap sheet = SubjectTrack.save(masks, stepMs, loopMs, Math.max(8, height));
        try (FileOutputStream stream = new FileOutputStream(out)) {
            sheet.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } catch (IOException e) {
            sheet.recycle();
            finish(null, "Couldn't save the tracking.");
            return;
        }
        SubjectTrack track = SubjectTrack.load(sheet);
        sheet.recycle();
        finish(track, track == null ? "Couldn't save the tracking." : null);
    }

    private static boolean empty(byte[] m) {
        for (byte b : m) {
            if ((b & 0xFF) > 127) {
                return false;
            }
        }
        return true;
    }

    private void finish(SubjectTrack track, String error) {
        if (source != null) {
            source.release();
            source = null;
        }
        worker.shutdown();
        main.post(() -> listener.done(track, error));
    }

    // ---- frames ----

    private interface FrameSource {
        long durationMs();

        /** The upright frame at a time, at most FRAME_SIDE on its long side; the caller recycles it. */
        Bitmap frameAt(long ms);

        void release();
    }

    private static final class VideoFrames implements FrameSource {
        private final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        private final long duration;

        VideoFrames(File file) throws IOException {
            retriever.setDataSource(file.getPath());
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            duration = d != null ? Long.parseLong(d) : 0L;
            if (duration <= 0) {
                throw new IOException("No duration");
            }
        }

        @Override
        public long durationMs() {
            return duration;
        }

        @Override
        public Bitmap frameAt(long ms) {
            Bitmap frame = Build.VERSION.SDK_INT >= 27
                    ? retriever.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                            FRAME_SIDE, FRAME_SIDE)
                    : retriever.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
            return frame == null ? null : shrink(frame);
        }

        @Override
        public void release() {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Going away anyway.
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static final class GifFrames implements FrameSource {
        private final android.graphics.Movie movie;

        GifFrames(File file) throws IOException {
            movie = android.graphics.Movie.decodeFile(file.getPath());
            if (movie == null || movie.width() <= 0 || movie.duration() <= 0) {
                throw new IOException("Not a GIF this can read");
            }
        }

        @Override
        public long durationMs() {
            return movie.duration();
        }

        @Override
        public Bitmap frameAt(long ms) {
            Bitmap frame = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888);
            movie.setTime((int) ms);
            movie.draw(new Canvas(frame), 0, 0);
            return shrink(frame);
        }

        @Override
        public void release() {
            // Nothing held.
        }
    }

    private static Bitmap shrink(Bitmap frame) {
        float scale = Math.min(1f, FRAME_SIDE / (float) Math.max(frame.getWidth(), frame.getHeight()));
        if (scale >= 1f && frame.getConfig() == Bitmap.Config.ARGB_8888) {
            return frame;
        }
        Bitmap small = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * scale)),
                Math.max(1, Math.round(frame.getHeight() * scale)), true);
        if (small != frame) {
            frame.recycle();
        }
        if (small.getConfig() == Bitmap.Config.ARGB_8888) {
            return small;
        }
        Bitmap copy = small.copy(Bitmap.Config.ARGB_8888, false);
        small.recycle();
        return copy;
    }
}
