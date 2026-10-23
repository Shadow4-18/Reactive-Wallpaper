package com.reactivewallpaper;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Reports how far the phone is tilted from the way it is being held, as -1..1
 * on each axis. "Neutral" slowly follows the current angle, so lying down or
 * standing up both feel centered and only movements shift the picture.
 */
final class TiltSensor implements SensorEventListener {
    private static final float RANGE_RADIANS = (float) Math.toRadians(18);
    /** How quickly neutral catches up with the way the phone is held, per second. */
    private static final float RECENTER_RATE = 0.35f;

    private final SensorManager sensorManager;
    private final Sensor sensor;
    private final float[] rotation = new float[9];
    private final float[] orientation = new float[3];
    private boolean registered;
    private boolean hasBaseline;
    private float basePitch;
    private float baseRoll;
    private long lastEventNanos;
    private volatile float x;
    private volatile float y;

    TiltSensor(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = null;
        if (sensorManager != null) {
            // The game rotation vector skips the compass, so it doesn't drift near magnets.
            s = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (s == null) {
                s = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            }
        }
        sensor = s;
    }

    void start() {
        if (!registered && sensor != null) {
            hasBaseline = false;
            registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    void stop() {
        if (registered) {
            sensorManager.unregisterListener(this);
            registered = false;
        }
        x = 0f;
        y = 0f;
    }

    float x() {
        return x;
    }

    float y() {
        return y;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        SensorManager.getOrientation(rotation, orientation);
        float pitch = orientation[1];
        float roll = orientation[2];
        if (!hasBaseline) {
            basePitch = pitch;
            baseRoll = roll;
            lastEventNanos = event.timestamp;
            hasBaseline = true;
        }
        float dt = Math.min(0.2f, (event.timestamp - lastEventNanos) / 1e9f);
        lastEventNanos = event.timestamp;
        float follow = Math.min(1f, dt * RECENTER_RATE);
        basePitch += angleDelta(pitch, basePitch) * follow;
        baseRoll += angleDelta(roll, baseRoll) * follow;
        x = clamp(angleDelta(roll, baseRoll) / RANGE_RADIANS);
        y = clamp(angleDelta(pitch, basePitch) / RANGE_RADIANS);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static float angleDelta(float a, float b) {
        float d = a - b;
        while (d > Math.PI) {
            d -= 2 * Math.PI;
        }
        while (d < -Math.PI) {
            d += 2 * Math.PI;
        }
        return d;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
