package com.zionaube.adskipper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.List;

public class MainActivity extends Activity {

    private TextView statusText;
    private TextView statsText;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NotifyHelper.ensureChannel(this);

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 201);
            }
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(56, 48, 56, 56);

        TextView titleText = new TextView(this);
        titleText.setText("YouTube Ad Skipper");
        titleText.setTextSize(26f);
        titleText.setGravity(Gravity.CENTER);

        TextView creditText = new TextView(this);
        creditText.setText("Made by Zion Aube v1.2");
        creditText.setTextSize(15f);
        creditText.setGravity(Gravity.CENTER);
        creditText.setPadding(0, 8, 0, 28);

        statusText = new TextView(this);
        statusText.setTextSize(17f);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 0, 0, 24);

        startButton = new Button(this);
        startButton.setText("Start");
        startButton.setTextSize(18f);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        Button appInfoButton = new Button(this);
        appInfoButton.setText("Open App Info (Restricted Settings)");
        appInfoButton.setTextSize(13f);
        appInfoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", getPackageName(), null));
                startActivity(intent);
            }
        });

        statsText = new TextView(this);
        statsText.setTextSize(15f);
        statsText.setGravity(Gravity.CENTER);
        statsText.setPadding(0, 32, 0, 20);

        Button shareButton = new Button(this);
        shareButton.setText("Share Stats");
        shareButton.setTextSize(15f);
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareStats();
            }
        });

        Button resetButton = new Button(this);
        resetButton.setText("Reset Stats");
        resetButton.setTextSize(13f);
        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PrefsHelper.resetStats(MainActivity.this);
                refreshUI();
            }
        });

        root.addView(titleText);
        root.addView(creditText);
        root.addView(statusText);
        root.addView(startButton);
        root.addView(appInfoButton);
        root.addView(statsText);
        root.addView(shareButton);
        root.addView(resetButton);

        addToggle(root, "Skip YouTube Ads", PrefsHelper.KEY_YT_ADS);
        addToggle(root, "Skip YouTube Music Ads", PrefsHelper.KEY_YTM_ADS);
        addToggle(root, "Skip Surveys & Promo Popups", PrefsHelper.KEY_SURVEYS);
        addToggle(root, "Auto-Resume 'Continue Watching?'", PrefsHelper.KEY_RESUME);
        addToggle(root, "SponsorBlock Mid-Video Skip", PrefsHelper.KEY_SPONSORBLOCK);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void addToggle(LinearLayout parent, String label, final String prefKey) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(15f);
        sw.setPadding(0, 24, 0, 12);
        sw.setChecked(PrefsHelper.isEnabled(this, prefKey));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PrefsHelper.setEnabled(MainActivity.this, prefKey, isChecked);
            }
        });
        parent.addView(sw);
    }

    private void shareStats() {
        int yt = PrefsHelper.getStat(this, PrefsHelper.STAT_YT_ADS);
        int ytm = PrefsHelper.getStat(this, PrefsHelper.STAT_YTM_ADS);
        int adsTotal = yt + ytm;
        int spn = PrefsHelper.getStat(this, PrefsHelper.STAT_SPONSORS);
        int srv = PrefsHelper.getStat(this, PrefsHelper.STAT_SURVEYS);
        int res = PrefsHelper.getStat(this, PrefsHelper.STAT_RESUMES);
        int total = adsTotal + spn + srv + res;

        String msg = "YouTube Ad Skipper v1.2 by Zion Aube has handled " + total + " interruptions!\n"
                + "• Ads Skipped: " + adsTotal + " (YT: " + yt + ", YT Music: " + ytm + ")\n"
                + "• Sponsors Blocked: " + spn + "\n"
                + "• Surveys & Upsells Dismissed: " + srv + "\n"
                + "• 'Continue Watching' Auto-Resumes: " + res + "\n"
                + "Self-compiled pure Java utility for Android.";

        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_SUBJECT, "YouTube Ad Skipper v1.2 Stats");
        sendIntent.putExtra(Intent.EXTRA_TEXT, msg);
        startActivity(Intent.createChooser(sendIntent, "Share Stats via"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUI();
    }

    private void refreshUI() {
        if (isAccessibilityServiceEnabled()) {
            statusText.setText("Status: Running");
            startButton.setText("Accessibility Settings");
        } else {
            statusText.setText("Status: Stopped");
            startButton.setText("Start");
        }

        int yt = PrefsHelper.getStat(this, PrefsHelper.STAT_YT_ADS);
        int ytm = PrefsHelper.getStat(this, PrefsHelper.STAT_YTM_ADS);
        int adsTotal = yt + ytm;
        int spn = PrefsHelper.getStat(this, PrefsHelper.STAT_SPONSORS);
        int srv = PrefsHelper.getStat(this, PrefsHelper.STAT_SURVEYS);
        int res = PrefsHelper.getStat(this, PrefsHelper.STAT_RESUMES);

        statsText.setText("Ads Skipped: " + adsTotal + " (YT: " + yt + " | YTM: " + ytm + ")\n"
                + "Sponsors Blocked: " + spn + "\n"
                + "Surveys/Popups: " + srv + "  |  Auto-Resumes: " + res);
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        ComponentName expected = new ComponentName(this, AdSkipperService.class);
        List<AccessibilityServiceInfo> enabled = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (enabled == null) return false;
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                ComponentName actual = new ComponentName(
                        info.getResolveInfo().serviceInfo.packageName,
                        info.getResolveInfo().serviceInfo.name);
                if (expected.equals(actual)) {
                    return true;
                }
            }
        }
        return false;
    }
}
