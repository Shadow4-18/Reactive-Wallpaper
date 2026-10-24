package com.reactivewallpaper;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the user can grant "notification access", which Android
 * requires before an app may see which song is playing (for album art and
 * per-song look rotation). Notifications themselves are never read.
 */
public class MediaAccessService extends NotificationListenerService {
}
