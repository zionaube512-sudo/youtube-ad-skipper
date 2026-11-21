package com.zionaube.adskipper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdSkipperService extends AccessibilityService {

    private static final String YT_PKG = "com.google.android.youtube";
    private static final String YTM_PKG = "com.google.android.apps.youtube.music";
    private static final Pattern TIME_PAIR_PATTERN = Pattern.compile("^(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*/\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)$");

    private long lastClickMs = 0L;
    private long lastSponsorJumpMs = 0L;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        boolean isYt = YT_PKG.equals(pkg);
        boolean isYtm = YTM_PKG.equals(pkg);
        if (!isYt && !isYtm) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        long now = SystemClock.elapsedRealtime();

        boolean adsEnabled = isYt
                ? PrefsHelper.isEnabled(this, PrefsHelper.KEY_YT_ADS)
                : PrefsHelper.isEnabled(this, PrefsHelper.KEY_YTM_ADS);

        if (adsEnabled && now - lastClickMs >= 750L) {
            AccessibilityNodeInfo skipTarget = findAdSkipTarget(root);
            if (skipTarget != null && clickNodeOrParent(skipTarget)) {
                lastClickMs = now;
                PrefsHelper.incStat(this, isYt ? PrefsHelper.STAT_YT_ADS : PrefsHelper.STAT_YTM_ADS);
                return;
            }
        }

        if (PrefsHelper.isEnabled(this, PrefsHelper.KEY_SURVEYS) && now - lastClickMs >= 900L) {
            AccessibilityNodeInfo surveyTarget = findSurveyOrUpsellTarget(root);
            if (surveyTarget != null && clickNodeOrParent(surveyTarget)) {
                lastClickMs = now;
                PrefsHelper.incStat(this, PrefsHelper.STAT_SURVEYS);
                return;
            }
        }

        if (PrefsHelper.isEnabled(this, PrefsHelper.KEY_RESUME) && now - lastClickMs >= 1000L) {
            AccessibilityNodeInfo resumeTarget = findContinueWatchingYes(root);
            if (resumeTarget != null && clickNodeOrParent(resumeTarget)) {
                lastClickMs = now;
                PrefsHelper.incStat(this, PrefsHelper.STAT_RESUMES);
                return;
            }
        }

        if (isYt && PrefsHelper.isEnabled(this, PrefsHelper.KEY_SPONSORBLOCK) && now - lastSponsorJumpMs >= 4000L) {
            trySponsorBlockSkip(root, now);
        }
    }

    @Override
    public void onInterrupt() {
    }

    private AccessibilityNodeInfo findAdSkipTarget(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName().toLowerCase() : "";
            String text = node.getText() != null ? node.getText().toString().trim().toLowerCase() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim().toLowerCase() : "";

            boolean match = id.contains("skip_ad_button") || id.contains("modern_skip_ad_button")
                    || "skip".equals(text) || "skip ad".equals(text) || "skip ads".equals(text)
                    || "skip".equals(desc) || "skip ad".equals(desc) || "skip ads".equals(desc);

            if (match && node.isEnabled()) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findSurveyOrUpsellTarget(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        boolean hasPromoContext = false;
        AccessibilityNodeInfo candidateDismiss = null;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName().toLowerCase() : "";
            String text = node.getText() != null ? node.getText().toString().trim().toLowerCase() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim().toLowerCase() : "";

            if (id.contains("skip_survey") || id.contains("survey_skip")
                    || "skip survey".equals(text) || "skip survey".equals(desc)
                    || desc.contains("skip survey")) {
                if (node.isEnabled()) return node;
            }

            if (text.contains("youtube premium") || text.contains("music premium")
                    || text.contains("free trial") || text.contains("survey")
                    || text.contains("how was your experience") || id.contains("mealbar")
                    || id.contains("interstitial")) {
                hasPromoContext = true;
            }

            if (("no thanks".equals(text) || "not now".equals(text) || "dismiss".equals(text)
                    || "no thanks".equals(desc) || "not now".equals(desc) || "dismiss".equals(desc))
                    && node.isEnabled()) {
                candidateDismiss = node;
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }

        if (hasPromoContext && candidateDismiss != null) {
            return candidateDismiss;
        }
        return null;
    }

    private AccessibilityNodeInfo findContinueWatchingYes(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        boolean hasPausedPrompt = false;
        AccessibilityNodeInfo yesButton = null;

        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = node.getText() != null ? node.getText().toString().trim().toLowerCase() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim().toLowerCase() : "";

            if (text.contains("continue watching") || desc.contains("continue watching")
                    || text.contains("video paused") || text.contains("still listening")) {
                hasPausedPrompt = true;
            }
            if (("yes".equals(text) || "yes".equals(desc)) && node.isEnabled()) {
                yesButton = node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return (hasPausedPrompt && yesButton != null) ? yesButton : null;
    }

    private void trySponsorBlockSkip(AccessibilityNodeInfo root, long now) {
        String videoTitle = null;
        double currentSec = -1;
        double totalSec = -1;
        Rect playerBounds = null;

        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName().toLowerCase() : "";
            String text = node.getText() != null ? node.getText().toString().trim() : "";

            if (id.contains("player_fragment_container") || id.contains("watch_player")) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.width() > 200 && r.height() > 150) {
                    playerBounds = r;
                }
            }

            if (videoTitle == null && (id.contains("title") || id.contains("video_title"))
                    && text.length() >= 5 && !text.contains("/")) {
                videoTitle = text;
            }

            if (currentSec < 0 && text.contains("/")) {
                Matcher m = TIME_PAIR_PATTERN.matcher(text);
                if (m.matches()) {
                    currentSec = parseTimeSeconds(m.group(1));
                    totalSec = parseTimeSeconds(m.group(2));
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }

        if (videoTitle == null || currentSec < 0 || totalSec <= 0) return;

        List<SponsorBlockHelper.Segment> segments = SponsorBlockHelper.getSegmentsForTitle(videoTitle);
        for (SponsorBlockHelper.Segment seg : segments) {
            if (currentSec >= seg.startSec && currentSec < (seg.endSec - 1.5)) {
                double skipDelta = seg.endSec - currentSec;
                int taps = Math.max(1, Math.min(12, (int) Math.round(skipDelta / 10.0)));
                if (performDoubleTapSkipForward(playerBounds, taps)) {
                    lastSponsorJumpMs = now;
                    PrefsHelper.incStat(this, PrefsHelper.STAT_SPONSORS);
                    NotifyHelper.postAlert(this, "SponsorBlock Jump", "Skipped " + seg.category + " segment");
                }
                break;
            }
        }
    }

    private boolean performDoubleTapSkipForward(Rect playerBounds, int tenSecSteps) {
        int x = (playerBounds != null) ? (playerBounds.left + (int) (playerBounds.width() * 0.8)) : 850;
        int y = (playerBounds != null) ? (playerBounds.top + (int) (playerBounds.height() / 2)) : 450;

        GestureDescription.Builder builder = new GestureDescription.Builder();
        long t = 0L;
        int totalTaps = tenSecSteps + 1;
        for (int i = 0; i < totalTaps; i++) {
            Path p = new Path();
            p.moveTo(x, y);
            builder.addStroke(new GestureDescription.StrokeDescription(p, t, 35L));
            t += 90L;
        }
        return dispatchGesture(builder.build(), null, null);
    }

    private double parseTimeSeconds(String ts) {
        String[] parts = ts.split(":");
        double sec = 0;
        for (String part : parts) {
            sec = sec * 60 + Integer.parseInt(part);
        }
        return sec;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int depth = 0;
        while (current != null && depth < 5) {
            if (current.isClickable() && current.isEnabled()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
            depth++;
        }
        return false;
    }
}
