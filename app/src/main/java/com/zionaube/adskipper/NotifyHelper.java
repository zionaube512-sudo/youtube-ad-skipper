package com.zionaube.adskipper;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

public class NotifyHelper {
    public static final String CHANNEL_ID = "adskipper_alerts";
    private static int nextId = 1001;

    public static void ensureChannel(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_ID,
                            "YouTube Ad Skipper Alerts",
                            NotificationManager.IMPORTANCE_LOW
                    );
                    ch.setDescription("Optional notifications for SponsorBlock jumps and app activity");
                    nm.createNotificationChannel(ch);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void postAlert(Context ctx, String title, String message) {
        try {
            ensureChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            android.app.Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new android.app.Notification.Builder(ctx, CHANNEL_ID)
                    : new android.app.Notification.Builder(ctx);
            builder.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setAutoCancel(true);
            nm.notify(nextId++, builder.build());
        } catch (Exception ignored) {
        }
    }
}
