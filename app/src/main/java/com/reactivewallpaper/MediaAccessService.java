package com.reactivewallpaper;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Exists so the user can grant "notification access", which Android requires
 * before an app may see which song is playing (for album art and per-song look
 * rotation). For the optional notification pulse it passes on only that a new
 * notification arrived and its app's accent color; titles, text and senders are
 * never read.
 */
public class MediaAccessService extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || sbn.isOngoing() || getPackageName().equals(sbn.getPackageName())
                || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                || Notification.CATEGORY_TRANSPORT.equals(n.category)) {
            // Media players, downloads and other ongoing ones would pulse constantly.
            return;
        }
        NotificationPulse.post(n.color);
    }
}
