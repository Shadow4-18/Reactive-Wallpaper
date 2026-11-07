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

    static void detect(Bitmap image, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        try {
            float scale = Math.min(1f, MAX_SIDE / (float) Math.max(image.getWidth(), image.getHeight()));
            Bitmap input = scale < 1f
                    ? Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * scale)),
                            Math.max(1, Math.round(image.getHeight() * scale)), true)
                    : image.copy(Bitmap.Config.ARGB_8888, false);
            SubjectSegmenter segmenter = SubjectSegmentation.getClient(new SubjectSegmenterOptions.Builder()
                    .enableForegroundConfidenceMask()
                    .build());
            segmenter.process(InputImage.fromBitmap(input, 0))
                    .addOnSuccessListener(result -> {
                        FloatBuffer confidence = result.getForegroundConfidenceMask();
                        Bitmap mask = confidence != null ? toMask(confidence, input.getWidth(), input.getHeight()) : null;
                        input.recycle();
                        close(segmenter);
                        if (mask == null) {
                            callback.done(null, "No subject was found. Paint it by hand instead.");
                        } else {
                            callback.done(mask, null);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Subject detection failed", e);
                        input.recycle();
                        close(segmenter);
                        callback.done(null, explain(e));
                    });
        } catch (Throwable t) {
            // No Play services, or an old one: say so instead of crashing the editor.
            Log.w(TAG, "Subject detection unavailable", t);
            main.post(() -> callback.done(null,
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
