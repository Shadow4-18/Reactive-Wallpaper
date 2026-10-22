package com.reactivewallpaper;

import android.content.Context;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/** Tracks whether sound is going to Bluetooth, which plays it noticeably later. */
final class OutputRoute {
    interface Listener {
        void onRouteChanged(boolean bluetooth);
    }

    private final AudioManager audioManager;
    private final Listener listener;
    private boolean bluetooth;
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
        boolean now = detect();
        if (now != bluetooth) {
            bluetooth = now;
            listener.onRouteChanged(now);
        }
    }

    private boolean detect() {
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
                    return true;
                default:
                    break;
            }
        }
        return false;
    }
}
