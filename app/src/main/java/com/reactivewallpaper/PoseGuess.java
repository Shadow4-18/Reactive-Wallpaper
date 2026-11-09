package com.reactivewallpaper;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

/**
 * Places a skeleton from a person's body pose (Google's on-device pose
 * detection; nothing leaves the phone). Works well on photos of people,
 * less often on drawn characters: when it finds no clear person, the guess
 * from the subject's shape stays.
 */
final class PoseGuess {
    private static final String TAG = "PoseGuess";
    private static final float SURE = 0.6f;

    interface Done {
        /** On the main thread: true if the rig was moved to the detected pose. */
        void done(boolean found);
    }

    private PoseGuess() {}

    static void refine(Bitmap image, CharacterRig rig, Done done) {
        Handler main = new Handler(Looper.getMainLooper());
        try {
            PoseDetector detector = PoseDetection.getClient(new PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
                    .build());
            detector.process(InputImage.fromBitmap(image, 0))
                    .addOnSuccessListener(pose -> {
                        boolean found = false;
                        try {
                            found = apply(pose, rig, image.getWidth(), image.getHeight());
                        } catch (RuntimeException e) {
                            Log.w(TAG, "Couldn't read the pose", e);
                        }
                        close(detector);
                        done.done(found);
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Pose detection failed", e);
                        close(detector);
                        done.done(false);
                    });
        } catch (Throwable t) {
            Log.w(TAG, "Pose detection unavailable", t);
            main.post(() -> done.done(false));
        }
    }

    private static void close(PoseDetector detector) {
        try {
            detector.close();
        } catch (Exception ignored) {
            // Dropped anyway.
        }
    }

    /** Moves the body joints onto the pose; hair keeps its place relative to the head. */
    private static boolean apply(Pose pose, CharacterRig rig, int w, int h) {
        PointF nose = sure(pose, PoseLandmark.NOSE);
        PointF ls = sure(pose, PoseLandmark.LEFT_SHOULDER);
        PointF rs = sure(pose, PoseLandmark.RIGHT_SHOULDER);
        if (nose == null || ls == null || rs == null) {
            return false;
        }
        // "Left" in the rig is the picture's left, whichever way the person faces.
        boolean swap = ls.x > rs.x;
        PointF leftShoulder = swap ? rs : ls;
        PointF rightShoulder = swap ? ls : rs;
        PointF leftElbow = sure(pose, swap ? PoseLandmark.RIGHT_ELBOW : PoseLandmark.LEFT_ELBOW);
        PointF rightElbow = sure(pose, swap ? PoseLandmark.LEFT_ELBOW : PoseLandmark.RIGHT_ELBOW);
        PointF leftHand = sure(pose, swap ? PoseLandmark.RIGHT_WRIST : PoseLandmark.LEFT_WRIST);
        PointF rightHand = sure(pose, swap ? PoseLandmark.LEFT_WRIST : PoseLandmark.RIGHT_WRIST);
        PointF lh = sure(pose, PoseLandmark.LEFT_HIP);
        PointF rh = sure(pose, PoseLandmark.RIGHT_HIP);

        float headDx = rig.x[CharacterRig.HEAD];
        float headDy = rig.y[CharacterRig.HEAD];
        float neckX = (leftShoulder.x + rightShoulder.x) / 2f;
        float neckY = (leftShoulder.y + rightShoulder.y) / 2f;
        set(rig, CharacterRig.HEAD, nose.x, nose.y, w, h);
        set(rig, CharacterRig.NECK, neckX + (nose.x - neckX) * 0.2f, neckY + (nose.y - neckY) * 0.2f, w, h);
        float hipsX;
        float hipsY;
        if (lh != null && rh != null) {
            hipsX = (lh.x + rh.x) / 2f;
            hipsY = (lh.y + rh.y) / 2f;
        } else {
            // Hips out of the picture: below the bottom edge, roughly where they'd be.
            float shoulders = Math.abs(rightShoulder.x - leftShoulder.x);
            hipsX = neckX;
            hipsY = Math.min(h - 1f, neckY + shoulders * 1.4f);
        }
        set(rig, CharacterRig.HIPS, hipsX, hipsY, w, h);
        set(rig, CharacterRig.CHEST, neckX + (hipsX - neckX) * 0.3f, neckY + (hipsY - neckY) * 0.3f, w, h);
        set(rig, CharacterRig.L_SHOULDER, leftShoulder.x, leftShoulder.y, w, h);
        set(rig, CharacterRig.R_SHOULDER, rightShoulder.x, rightShoulder.y, w, h);
        if (leftElbow != null) {
            set(rig, CharacterRig.L_ELBOW, leftElbow.x, leftElbow.y, w, h);
        }
        if (rightElbow != null) {
            set(rig, CharacterRig.R_ELBOW, rightElbow.x, rightElbow.y, w, h);
        }
        if (leftHand != null) {
            set(rig, CharacterRig.L_HAND, leftHand.x, leftHand.y, w, h);
        }
        if (rightHand != null) {
            set(rig, CharacterRig.R_HAND, rightHand.x, rightHand.y, w, h);
        }
        // Hair moves with the head.
        float mx = rig.x[CharacterRig.HEAD] - headDx;
        float my = rig.y[CharacterRig.HEAD] - headDy;
        for (int j = CharacterRig.L_HAIR1; j <= CharacterRig.R_HAIR3; j++) {
            rig.x[j] = Math.max(0f, Math.min(1f, rig.x[j] + mx));
            rig.y[j] = Math.max(0f, Math.min(1f, rig.y[j] + my));
        }
        return true;
    }

    private static PointF sure(Pose pose, int type) {
        PoseLandmark l = pose.getPoseLandmark(type);
        return l != null && l.getInFrameLikelihood() >= SURE ? l.getPosition() : null;
    }

    private static void set(CharacterRig rig, int j, float px, float py, int w, int h) {
        rig.x[j] = Math.max(0f, Math.min(1f, px / w));
        rig.y[j] = Math.max(0f, Math.min(1f, py / h));
    }
}
