package com.zionaube.adskipper;

import android.content.Context;
import android.content.SharedPreferences;

public class PrefsHelper {
    private static final String PREFS_NAME = "adskipper_prefs";

    public static final String KEY_YT_ADS = "enable_yt_ads";
    public static final String KEY_YTM_ADS = "enable_ytm_ads";
    public static final String KEY_SURVEYS = "enable_surveys";
    public static final String KEY_RESUME = "enable_resume";
    public static final String KEY_SPONSORBLOCK = "enable_sponsorblock";

    public static final String STAT_YT_ADS = "stat_yt_ads";
    public static final String STAT_YTM_ADS = "stat_ytm_ads";
    public static final String STAT_SURVEYS = "stat_surveys";
    public static final String STAT_RESUMES = "stat_resumes";
    public static final String STAT_SPONSORS = "stat_sponsors";

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx, String key) {
        return get(ctx).getBoolean(key, true);
    }

    public static void setEnabled(Context ctx, String key, boolean val) {
        get(ctx).edit().putBoolean(key, val).apply();
    }

    public static int getStat(Context ctx, String key) {
        return get(ctx).getInt(key, 0);
    }

    public static void incStat(Context ctx, String key) {
        SharedPreferences p = get(ctx);
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply();
    }

    public static void resetStats(Context ctx) {
        get(ctx).edit()
                .putInt(STAT_YT_ADS, 0)
                .putInt(STAT_YTM_ADS, 0)
                .putInt(STAT_SURVEYS, 0)
                .putInt(STAT_RESUMES, 0)
                .putInt(STAT_SPONSORS, 0)
                .apply();
    }
}
