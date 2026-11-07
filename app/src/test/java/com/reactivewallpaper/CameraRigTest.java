package com.reactivewallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CameraRigTest {
    @Test
    public void beatKicksThenSettles() {
        CameraRig camera = new CameraRig();
        float peakZoom = 0f;
        float peakAngle = 0f;
        for (int f = 0; f < 120; f++) {
            float beat = f == 2 ? 1f : 0f; // one strong beat
            camera.update(1f / 60f, beat, 1f, 1f);
            peakZoom = Math.max(peakZoom, camera.zoom());
            peakAngle = Math.max(peakAngle, Math.abs(camera.angle()));
        }
        assertTrue("zoom punch too small: " + peakZoom, peakZoom > 0.03f && peakZoom < 0.12f);
        assertTrue("tilt out of range: " + peakAngle, peakAngle > 0.8f && peakAngle < 3f);
        // Two seconds later it is back at rest.
        assertEquals(0f, camera.zoom(), 0.002f);
        assertEquals(0f, camera.angle(), 0.05f);
    }

    @Test
    public void tiltAlternatesSides() {
        CameraRig camera = new CameraRig();
        camera.update(0.016f, 1f, 0f, 1f);
        float first = 0f;
        for (int f = 0; f < 8; f++) {
            camera.update(0.016f, 0f, 0f, 1f);
            first = camera.angle();
        }
        for (int f = 0; f < 200; f++) {
            camera.update(0.016f, 0f, 0f, 1f);
        }
        camera.update(0.016f, 1f, 0f, 1f);
        float second = 0f;
        for (int f = 0; f < 8; f++) {
            camera.update(0.016f, 0f, 0f, 1f);
            second = camera.angle();
        }
        assertTrue("expected opposite sides: " + first + " then " + second, first * second < 0f);
    }

    @Test
    public void staysStillWhenOff() {
        CameraRig camera = new CameraRig();
        for (int f = 0; f < 60; f++) {
            camera.update(0.016f, f % 15 == 0 ? 1f : 0f, 0f, 0f);
        }
        assertEquals(0f, camera.zoom(), 0f);
        assertEquals(0f, camera.angle(), 0f);
    }
}
