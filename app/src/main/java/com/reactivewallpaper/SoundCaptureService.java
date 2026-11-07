package com.reactivewallpaper;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.function.Consumer;

/**
 * Holds Android's permission to capture the sound other apps are playing,
 * for a clip with the music in it (Android 10+). Android requires a visible
 * foreground service while capturing; it stops as soon as the clip is done.
 * Nothing is captured from the screen: the permission is used for sound only.
 */
@TargetApi(29)
public class SoundCaptureService extends Service {
    private static final String TAG = "SoundCapture";
    private static final String CHANNEL = "clip";
    private static final String EXTRA_RESULT = "result";
    private static final String EXTRA_DATA = "data";

    private static Consumer<AudioRecord> pending;
    private static MediaProjection projection;

    /**
     * Starts capturing with the permission the user just gave (from the screen
     * capture prompt); {@code ready} gets a recorder for the playing sound, or
     * null if it couldn't be set up.
     */
    static void start(Context context, int resultCode, Intent data, Consumer<AudioRecord> ready) {
        pending = ready;
        Intent intent = new Intent(context, SoundCaptureService.class)
                .putExtra(EXTRA_RESULT, resultCode)
                .putExtra(EXTRA_DATA, data);
        context.startForegroundService(intent);
    }

    /** Ends the capture (after a clip, or if it was abandoned). */
    static void stop(Context context) {
        context.stopService(new Intent(context, SoundCaptureService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Recording clips",
                NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Recording a clip with sound")
                .setOngoing(true)
                .build();
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }
        Consumer<AudioRecord> ready = pending;
        pending = null;
        AudioRecord record = null;
        try {
            int result = intent.getIntExtra(EXTRA_RESULT, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(result, data);
            // Android 14 wants a callback registered before anything is captured.
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            int rate = 44100;
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int size = Math.max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT) * 4, 32 * 1024);
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(size)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                record = null;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Couldn't capture the playing sound", e);
            record = null;
        }
        if (ready != null) {
            AudioRecord result = record;
            new Handler(Looper.getMainLooper()).post(() -> ready.accept(result));
        } else if (record != null) {
            record.release();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
