package com.zionaube.adskipper;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

public class NotifyHelper {
    public static final String CHANNEL_ID = "adskipper_alerts";
    public static final String ACTION_DAILY_SUMMARY = "com.zionaube.adskipper.action.DAILY_SUMMARY";
    private static final String TAG = "NotifyHelper";
    private static final long TWENTY_FOUR_HOURS_MS = 24L * 60L * 60L * 1000L;
    private static final int DAILY_SUMMARY_NOTIF_ID = 2026;
    private static final int DAILY_ALARM_REQ_CODE = 9001;
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
                    ch.setDescription("Optional notifications for SponsorBlock jumps, daily category summaries, and app activity");
                    nm.createNotificationChannel(ch);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed ensuring notification channel", e);
        }
    }

    public static boolean hasNotificationPermission(Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT >= 33) {
            return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static void postAlert(Context ctx, String title, String message) {
        try {
            if (!hasNotificationPermission(ctx)) {
                Log.d(TAG, "Skipping alert (POST_NOTIFICATIONS not granted): " + title);
                return;
            }
            ensureChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);
            builder.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setAutoCancel(true);
            nm.notify(nextId++, builder.build());
        } catch (Exception e) {
            Log.w(TAG, "Failed posting alert", e);
        }
    }

    public static void scheduleDailySummary(Context ctx) {
        if (ctx == null) return;
        try {
            Context appCtx = ctx.getApplicationContext();
            AlarmManager am = (AlarmManager) appCtx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;

            Intent intent = new Intent(appCtx, DailySummaryReceiver.class);
            intent.setAction(ACTION_DAILY_SUMMARY);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(appCtx, DAILY_ALARM_REQ_CODE, intent, flags);

            long triggerAt = SystemClock.elapsedRealtime() + TWENTY_FOUR_HOURS_MS;
            am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    TWENTY_FOUR_HOURS_MS,
                    pi
            );
            Log.i(TAG, "Scheduled 24-hour local summary notification");
        } catch (Exception e) {
            Log.w(TAG, "Failed scheduling 24-hour summary notification", e);
        }
    }

    public static void postDailySummaryNow(Context ctx) {
        if (ctx == null) return;
        if (!PrefsHelper.isEnabled(ctx, PrefsHelper.KEY_DAILY_SUMMARY)) {
            Log.d(TAG, "24h summary disabled in preferences");
            return;
        }
        try {
            if (!hasNotificationPermission(ctx)) {
                Log.d(TAG, "Cannot post 24h summary without POST_NOTIFICATIONS permission");
                return;
            }
            ensureChannel(ctx);
            int yt = PrefsHelper.getStat(ctx, PrefsHelper.STAT_YT_ADS);
            int ytm = PrefsHelper.getStat(ctx, PrefsHelper.STAT_YTM_ADS);
            int adsTotal = yt + ytm;
            int spn = PrefsHelper.getStat(ctx, PrefsHelper.STAT_SPONSORS);
            int srv = PrefsHelper.getStat(ctx, PrefsHelper.STAT_SURVEYS);
            int res = PrefsHelper.getStat(ctx, PrefsHelper.STAT_RESUMES);
            int total = adsTotal + spn + srv + res;

            String summaryLine = "Ads: " + adsTotal + " (YT: " + yt + ", YTM: " + ytm + ")"
                    + " | Sponsors: " + spn
                    + " | Surveys/Popups: " + srv
                    + " | Resumes: " + res;

            String bigText = "Congrats! You've avoided " + total + " interruptions overall:\n"
                    + "• Ads Blocked: " + adsTotal + " (YouTube: " + yt + ", YouTube Music: " + ytm + ")\n"
                    + "• Sponsors Blocked: " + spn + "\n"
                    + "• Surveys & Promo Popups Dismissed: " + srv + "\n"
                    + "• 'Continue Watching' Auto-Resumes: " + res;

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);
            builder.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Congrats! 24h Skipper Summary (" + total + " total)")
                    .setContentText(summaryLine)
                    .setStyle(new Notification.BigTextStyle().bigText(bigText))
                    .setAutoCancel(true);

            nm.notify(DAILY_SUMMARY_NOTIF_ID, builder.build());
            Log.i(TAG, "Posted 24-hour local summary notification");
        } catch (Exception e) {
            Log.w(TAG, "Failed posting 24-hour summary notification", e);
        }
    }

    public static class DailySummaryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (context == null) return;
            postDailySummaryNow(context);
            scheduleDailySummary(context);
        }
    }
}
