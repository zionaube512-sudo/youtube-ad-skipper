package com.zionaube.adskipper;

import android.app.Notification;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class YouTubeNotificationListenerService
        extends NotificationListenerService {

    private static final String TAG = "YTAdSkipperListener";
    private static final String YT_PKG = "com.google.android.youtube";

    private static volatile String latestYouTubeTitle = "";
    private static volatile long latestYouTubeTitleMs;
    private static volatile String lastMediaIdentity = "";

    private static final Object LOCK = new Object();
    private static final long TITLE_MAX_AGE_MS = 5L * 60L * 1000L;

    public static String getLatestPlayingTitle() {
        String title = latestYouTubeTitle;
        long timestamp = latestYouTubeTitleMs;

        if (title == null || title.isEmpty() || timestamp == 0L
                || System.currentTimeMillis() - timestamp > TITLE_MAX_AGE_MS) {
            return "";
        }

        return title;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "Notification listener connected.");
        scanActiveNotifications();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null && YT_PKG.equals(sbn.getPackageName())) {
            processMediaNotification(sbn);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && YT_PKG.equals(sbn.getPackageName())) {
            Log.d(TAG, "YouTube notification removed: " + sbn.getKey());
        }
    }

    private void scanActiveNotifications() {
        try {
            StatusBarNotification[] notifications = getActiveNotifications();
            if (notifications == null) return;

            for (StatusBarNotification sbn : notifications) {
                if (sbn != null && YT_PKG.equals(sbn.getPackageName())) {
                    processMediaNotification(sbn);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Active notification scan failed.", e);
        }
    }

    private void processMediaNotification(StatusBarNotification sbn) {
        try {
            Notification notification = sbn.getNotification();
            if (notification == null || notification.extras == null) return;

            Bundle extras = notification.extras;

            if (!extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                Log.d(TAG, "Rejected ordinary YouTube notification: no media session.");
                return;
            }

            MediaSession.Token token;

            if (Build.VERSION.SDK_INT >= 33) {
                token = extras.getParcelable(
                        Notification.EXTRA_MEDIA_SESSION,
                        MediaSession.Token.class);
            } else {
                token = getLegacyToken(extras);
            }

            if (token == null) {
                Log.d(TAG, "Rejected notification: invalid media token.");
                return;
            }

            MediaController controller =
                    new MediaController(getApplicationContext(), token);

            if (!YT_PKG.equals(controller.getPackageName())) return;

            PlaybackState playback = controller.getPlaybackState();

            if (playback == null) return;

            int state = playback.getState();

            if (state != PlaybackState.STATE_PLAYING
                    && state != PlaybackState.STATE_BUFFERING) {
                Log.d(TAG, "Rejected notification: playback inactive, state=" + state);
                return;
            }

            MediaMetadata metadata = controller.getMetadata();
            String title = "";

            if (metadata != null) {
                title = clean(metadata.getText(
                        MediaMetadata.METADATA_KEY_TITLE));

                if (title.isEmpty()) {
                    title = clean(metadata.getText(
                            MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                }
            }

            if (title.isEmpty()) {
                title = clean(extras.getCharSequence(Notification.EXTRA_TITLE));
            }

            if (title.isEmpty()) {
                Log.w(TAG, "Active YouTube session has no media title.");
                return;
            }

            String mediaId = metadata == null ? "" :
                    clean(metadata.getText(MediaMetadata.METADATA_KEY_MEDIA_ID));

            String identity = title + "|" + mediaId;
            boolean newItem;

            synchronized (LOCK) {
                latestYouTubeTitle = title;
                latestYouTubeTitleMs = System.currentTimeMillis();

                newItem = !identity.equals(lastMediaIdentity);

                if (newItem) lastMediaIdentity = identity;
            }

            if (!newItem) return;

            Log.i(TAG, "Confirmed active YouTube playback: " + title);

            SponsorBlockHelper.getSegmentsForTitle(
                    getApplicationContext(), title, -1.0);

        } catch (SecurityException e) {
            Log.e(TAG, "Media-session access denied.", e);
        } catch (Exception e) {
            Log.e(TAG, "Media notification processing failed.", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static MediaSession.Token getLegacyToken(Bundle extras) {
        return extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
    }

    private static String clean(CharSequence value) {
        if (value == null) return "";

        String title = value.toString().trim();
        String lower = title.toLowerCase();

        if (title.isEmpty()
                || lower.equals("youtube")
                || lower.equals("youtube music")
                || lower.equals("playing")
                || lower.equals("no title")) {
            return "";
        }

        return title;
    }
}
