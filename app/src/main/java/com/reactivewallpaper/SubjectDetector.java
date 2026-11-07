package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter;
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions;

import java.nio.FloatBuffer;

/**
 * Finds the picture's main subject with Google's on-device subject
 * segmentation (ML Kit, through Google Play services; nothing leaves the
 * phone). The model is downloaded by Play services, so the first try right
 * after installing may fail; so may phones without Play services. Every
 * failure is reported, never thrown: painting by hand always works.
 */
final class SubjectDetector {
    private static final String TAG = "SubjectDetector";
    /** The detector works on a copy no larger than this; its mask is soft anyway. */
    private static final int MAX_SIDE = 1024;

    interface Callback {
        /** On the main thread: a gray mask (255 = subject) at the given image's aspect, or null with a reason. */
        void done(Bitmap mask, String error);
    }

    private SubjectDetector() {}

    private static final String PREFS = "subject_detector";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_SINGLE_ONLY = "singleOnly";

    static void detect(android.content.Context context, Bitmap image, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        android.content.SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        // If the last detection never came back, the app died inside the detector itself
        // (beyond what any catch can stop): from then on, don't ask for separate subjects.
        if (prefs.getBoolean(KEY_RUNNING, false)) {
            Log.w(TAG, "The last detection never finished; using single-subject detection");
            prefs.edit().putBoolean(KEY_SINGLE_ONLY, true).commit();
        }
        boolean multiple = !prefs.getBoolean(KEY_SINGLE_ONLY, false);
        prefs.edit().putBoolean(KEY_RUNNING, true).commit();
        Callback inner = callback;
        callback = (mask, error) -> {
            prefs.edit().putBoolean(KEY_RUNNING, false).apply();
            inner.done(mask, error);
        };
        Callback done = callback;
        try {
            float scale = Math.min(1f, MAX_SIDE / (float) Math.max(image.getWidth(), image.getHeight()));
            Bitmap input = scale < 1f
                    ? Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * scale)),
                            Math.max(1, Math.round(image.getHeight() * scale)), true)
                    : image.copy(Bitmap.Config.ARGB_8888, false);
            // Only the foreground mask: asking the detector for separate subjects crashes it
            // (natively, past any catch) on some phones. Separate subjects are found here instead.
            SubjectSegmenter segmenter = SubjectSegmentation.getClient(new SubjectSegmenterOptions.Builder()
                    .enableForegroundConfidenceMask()
                    .build());
            segmenter.process(InputImage.fromBitmap(input, 0))
                    .addOnSuccessListener(result -> {
                        int w = input.getWidth();
                        int h = input.getHeight();
                        Bitmap mask = null;
                        try {
                            FloatBuffer confidence = result.getForegroundConfidenceMask();
                            mask = confidence != null ? toMask(confidence, w, h, multiple) : null;
                        } catch (Throwable t) {
                            Log.w(TAG, "Reading the subject failed", t);
                        }
                        input.recycle();
                        close(segmenter);
                        if (mask == null) {
                            done.done(null, "No subject was found. Paint it by hand instead.");
                        } else {
                            done.done(mask, null);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Subject detection failed", e);
                        input.recycle();
                        close(segmenter);
                        done.done(null, explain(e));
                    });
        } catch (Throwable t) {
            // No Play services, or an old one: say so instead of crashing the editor.
            Log.w(TAG, "Subject detection unavailable", t);
            main.post(() -> done.done(null,
                    "Automatic detection isn't available on this phone. Paint the subject by hand."));
        }
    }

    private static void close(SubjectSegmenter segmenter) {
        try {
            segmenter.close();
        } catch (Exception ignored) {
            // Nothing to do; it's being dropped anyway.
        }
    }

    private static String explain(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase(java.util.Locale.ROOT) : "";
        if (message.contains("download") || message.contains("module")) {
            return "Google Play services is still downloading the detector. Try again in a minute.";
        }
        return "Automatic detection didn't work on this phone. Paint the subject by hand.";
    }

    /**
     * Confidence (0..1 per pixel) to a gray mask, with a soft step so the edge
     * stays smooth; null when (almost) nothing is subject.
     */
    static Bitmap toMask(FloatBuffer confidence, int w, int h) {
        return toMask(confidence, w, h, false);
    }

    /**
     * As above; with {@code separate}, distinct shapes in the foreground (people
     * or objects not touching each other) become separate subjects, one per
     * color channel (see {@link #separate}).
     */
    static Bitmap toMask(FloatBuffer confidence, int w, int h, boolean separate) {
        confidence.rewind();
        int[] px = new int[w * h];
        int marked = 0;
        for (int i = 0; i < px.length && confidence.hasRemaining(); i++) {
            float c = confidence.get();
            float t = Math.max(0f, Math.min(1f, (c - 0.35f) / 0.3f));
            int v = Math.round(t * t * (3f - 2f * t) * 255f);
            if (v > 127) {
                marked++;
            }
            px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        if (marked < px.length * 0.002f) {
            return null;
        }
        if (separate) {
            int[] split = separate(px, w, h);
            if (split != null) {
                return Bitmap.createBitmap(split, w, h, Bitmap.Config.ARGB_8888);
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /**
     * Splits a gray foreground mask into its separate shapes: the largest up to
     * three, each big enough to be a subject (1.5% of the picture), go into the
     * red, green and blue channels; anything else joins the largest. Null when
     * there's only one such shape (keep the gray mask).
     */
    static int[] separate(int[] gray, int w, int h) {
        int n = w * h;
        int[] label = new int[n];
        java.util.Arrays.fill(label, -1);
        int[] queue = new int[n];
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        // Shapes: pixels at least a little inside the foreground, joined side to side.
        for (int start = 0; start < n; start++) {
            if (label[start] >= 0 || (gray[start] & 0xFF) <= 40) {
                continue;
            }
            int id = sizes.size();
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            label[start] = id;
            while (head < tail) {
                int i = queue[head++];
                int x = i % w;
                int y = i / w;
                int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                for (int j : next) {
                    if (j >= 0 && label[j] < 0 && (gray[j] & 0xFF) > 40) {
                        label[j] = id;
                        queue[tail++] = j;
                    }
                }
            }
            sizes.add(tail);
        }
        Integer[] order = new Integer[sizes.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(sizes.get(b), sizes.get(a)));
        int big = 0;
        for (Integer id : order) {
            if (big < SubjectLayer.MAX_PARTS && sizes.get(id) >= n * 0.015f) {
                big++;
            }
        }
        if (big < 2) {
            return null;
        }
        // Each shape's channel: the largest red, then green, then blue; the rest with the largest.
        int[] channel = new int[sizes.size()];
        for (int rank = 0; rank < order.length; rank++) {
            channel[order[rank]] = rank < big ? rank : 0;
        }
        // Soft edges outside every shape join the shape next to them (a few passes reach
        // the whole fringe), so no faint outline of one subject lands in another's layer.
        for (int pass = 0; pass < 6; pass++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                if (label[i] >= 0 || (gray[i] & 0xFF) == 0) {
                    continue;
                }
                int x = i % w;
                int y = i / w;
                int near = x > 0 ? label[i - 1] : -1;
                if (near < 0 && x < w - 1) {
                    near = label[i + 1];
                }
                if (near < 0 && y > 0) {
                    near = label[i - w];
                }
                if (near < 0 && y < h - 1) {
                    near = label[i + w];
                }
                if (near >= 0) {
                    label[i] = near;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int v = gray[i] & 0xFF;
            int c = label[i] >= 0 ? channel[label[i]] : 0;
            out[i] = 0xFF000000 | (v << (16 - 8 * c));
        }
        return out;
    }
}
