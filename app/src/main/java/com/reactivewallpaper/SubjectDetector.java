package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.subject.Subject;
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
            SubjectSegmenterOptions.Builder options = new SubjectSegmenterOptions.Builder()
                    .enableForegroundConfidenceMask();
            if (multiple) {
                options.enableMultipleSubjects(new SubjectSegmenterOptions.SubjectResultOptions.Builder()
                        .enableConfidenceMask()
                        .build());
            }
            SubjectSegmenter segmenter = SubjectSegmentation.getClient(options.build());
            segmenter.process(InputImage.fromBitmap(input, 0))
                    .addOnSuccessListener(result -> {
                        int w = input.getWidth();
                        int h = input.getHeight();
                        Bitmap mask = null;
                        // Separate subjects when there are several; if that part fails for any
                        // reason, the whole foreground as one subject (as before).
                        try {
                            mask = multiple ? subjectsMask(result.getSubjects(), w, h) : null;
                        } catch (Throwable t) {
                            Log.w(TAG, "Separate subjects unavailable", t);
                        }
                        try {
                            if (mask == null) {
                                FloatBuffer confidence = result.getForegroundConfidenceMask();
                                mask = confidence != null ? toMask(confidence, w, h) : null;
                            }
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
     * Two or more subjects as one mask with a subject in each color channel
     * (red, green, blue; the largest three), so each can be its own layer.
     * Null for fewer than two (the foreground mask covers a single one).
     */
    static Bitmap subjectsMask(java.util.List<Subject> subjects, int w, int h) {
        if (subjects == null || subjects.size() < 2) {
            return null;
        }
        java.util.List<Subject> largest = new java.util.ArrayList<>(subjects);
        largest.sort((a, b) -> Integer.compare(b.getWidth() * b.getHeight(), a.getWidth() * a.getHeight()));
        int count = Math.min(SubjectLayer.MAX_PARTS, largest.size());
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xFF000000);
        int found = 0;
        for (int c = 0; c < count; c++) {
            Subject s = largest.get(c);
            FloatBuffer confidence = s.getConfidenceMask();
            if (confidence == null) {
                continue;
            }
            confidence.rewind();
            int shift = 16 - 8 * c;
            int marked = 0;
            for (int y = 0; y < s.getHeight(); y++) {
                for (int x = 0; x < s.getWidth(); x++) {
                    float v = confidence.hasRemaining() ? confidence.get() : 0f;
                    int ix = s.getStartX() + x;
                    int iy = s.getStartY() + y;
                    if (ix < 0 || iy < 0 || ix >= w || iy >= h) {
                        continue;
                    }
                    float t = Math.max(0f, Math.min(1f, (v - 0.35f) / 0.3f));
                    int level = Math.round(t * t * (3f - 2f * t) * 255f);
                    if (level > 127) {
                        marked++;
                    }
                    px[iy * w + ix] |= level << shift;
                }
            }
            if (marked > w * h * 0.002f) {
                found++;
            }
        }
        return found >= 2 ? Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) : null;
    }

    /**
     * Confidence (0..1 per pixel) to a gray mask, with a soft step so the edge
     * stays smooth; null when (almost) nothing is subject.
     */
    static Bitmap toMask(FloatBuffer confidence, int w, int h) {
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
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }
}
