package com.reactivewallpaper;

/**
 * Beat-driven camera moves: each strong beat kicks a zoom "punch" and a small
 * tilt (alternating sides) into damped springs, so the picture jumps and then
 * settles naturally instead of snapping.
 */
final class CameraRig {
    private static final float ZOOM_STIFFNESS = 60f;
    private static final float ZOOM_DAMPING = 9f;
    private static final float TILT_STIFFNESS = 40f;
    private static final float TILT_DAMPING = 7f;

    private float zoom;
    private float zoomVelocity;
    private float angle;
    private float angleVelocity;
    private float lastBeat;
    private float direction = 1f;

    /**
     * @param punch 0 .. 2, zoom kick strength
     * @param tilt 0 .. 2, rotation kick strength
     */
    void update(float dt, float beat, float punch, float tilt) {
        boolean hit = beat > 0.7f && lastBeat <= 0.7f;
        lastBeat = beat;
        if (hit) {
            zoomVelocity += 0.9f * punch;
            angleVelocity += direction * 14f * tilt;
            direction = -direction;
        }
        // Semi-implicit Euler: stable at wallpaper frame rates, even at 10 fps.
        dt = Math.min(dt, 0.05f);
        zoomVelocity += (-ZOOM_STIFFNESS * zoom - ZOOM_DAMPING * zoomVelocity) * dt;
        zoom += zoomVelocity * dt;
        angleVelocity += (-TILT_STIFFNESS * angle - TILT_DAMPING * angleVelocity) * dt;
        angle += angleVelocity * dt;
    }

    /** Extra zoom right now (about +6% at the peak of a full-strength punch). */
    float zoom() {
        return zoom;
    }

    /** Rotation in degrees right now (about ±1.5° at full strength). */
    float angle() {
        return angle;
    }
}
