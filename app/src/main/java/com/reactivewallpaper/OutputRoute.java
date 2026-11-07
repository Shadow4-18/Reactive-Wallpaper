package com.reactivewallpaper;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Tracks whether sound is going to Bluetooth, which plays it noticeably later,
 * and which Bluetooth device (each has its own delay).
 */
final class OutputRoute {
    interface Listener {
        void onRouteChanged(boolean bluetooth);
    }

    private final AudioManager audioManager;
    private final Listener listener;
    private boolean bluetooth;
    /** The Bluetooth device's name, or null (not Bluetooth, or it has no name). */
    private String deviceName;
    private boolean registered;

    private final AudioDeviceCallback callback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            refresh();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            refresh();
        }
    };

    OutputRoute(Context context, Listener listener) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
        bluetooth = detect();
    }

    /** The Bluetooth device sound goes to (e.g. "Galaxy Buds3 Pro"), or null. */
    String deviceName() {
        return bluetooth ? deviceName : null;
    }

    void start() {
        if (!registered && audioManager != null) {
            audioManager.registerAudioDeviceCallback(callback, new Handler(Looper.getMainLooper()));
            registered = true;
        }
        refresh();
    }

    void stop() {
        if (registered) {
            audioManager.unregisterAudioDeviceCallback(callback);
            registered = false;
        }
    }

    boolean isBluetooth() {
        return bluetooth;
    }

    private void refresh() {
        String previousName = deviceName;
        boolean now = detect();
        boolean nameChanged = previousName == null ? deviceName != null : !previousName.equals(deviceName);
        if (now != bluetooth || (now && nameChanged)) {
            bluetooth = now;
            listener.onRouteChanged(now);
        }
    }

    private boolean detect() {
        deviceName = null;
        if (audioManager == null) {
            return false;
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                    CharSequence name = device.getProductName();
                    String clean = name != null ? name.toString().trim() : "";
                    deviceName = clean.isEmpty() ? null : clean;
                    return true;
                default:
                    break;
            }
        }
        return false;
    }
}
