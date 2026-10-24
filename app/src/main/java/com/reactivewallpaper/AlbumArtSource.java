package com.reactivewallpaper;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.List;
import java.util.Objects;

/** Follows the song playing in any music app: its cover art and when the track changes. */
final class AlbumArtSource {
    interface Listener {
        /** @param art a private copy of the cover, or null when none is available */
        void onTrack(String trackKey, Bitmap art);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaSessionManager sessions;
    private final ComponentName component;
    private MediaController controller;
    private String trackKey;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            report();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            // Another app may have started playing; follow whichever is playing.
            pick(null);
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    AlbumArtSource(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        component = new ComponentName(context, MediaAccessService.class);
    }

    /** True once the user has allowed notification access for Resona. */
    static boolean hasAccess(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        String ours = new ComponentName(context, MediaAccessService.class).flattenToString();
        return enabled != null && enabled.contains(ours);
    }

    void start() {
        if (started || sessions == null || !hasAccess(context)) {
            return;
        }
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, component, handler);
            started = true;
            pick(null);
        } catch (SecurityException e) {
            // Access was withdrawn between the check and the call.
            started = false;
        }
    }

    void stop() {
        if (!started) {
            return;
        }
        sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        if (controller != null) {
            controller.unregisterCallback(callback);
            controller = null;
        }
        started = false;
        trackKey = null;
    }

    /** Follows the session that is playing (or else the most recent one). */
    private void pick(List<MediaController> active) {
        List<MediaController> list;
        try {
            list = active != null ? active : sessions.getActiveSessions(component);
        } catch (SecurityException e) {
            return;
        }
        MediaController best = null;
        for (MediaController c : list) {
            PlaybackState state = c.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                best = c;
                break;
            }
        }
        if (best == null && !list.isEmpty()) {
            best = list.get(0);
        }
        boolean same = best != null && controller != null
                && best.getSessionToken().equals(controller.getSessionToken());
        if (!same) {
            if (controller != null) {
                controller.unregisterCallback(callback);
            }
            controller = best;
            if (controller != null) {
                controller.registerCallback(callback, handler);
            }
        }
        report();
    }

    private void report() {
        MediaMetadata metadata = controller != null ? controller.getMetadata() : null;
        String key = null;
        Bitmap art = null;
        if (metadata != null) {
            key = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) + "|"
                    + metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) + "|"
                    + metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) {
                art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
        }
        if (Objects.equals(key, trackKey)) {
            return;
        }
        trackKey = key;
        // The music app owns its bitmap and may recycle it; keep our own copy.
        Bitmap copy = art != null && !art.isRecycled() ? art.copy(Bitmap.Config.ARGB_8888, false) : null;
        listener.onTrack(key, copy);
    }
}
