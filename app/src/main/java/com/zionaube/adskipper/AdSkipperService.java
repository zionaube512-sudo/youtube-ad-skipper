package com.zionaube.adskipper;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import android.view.accessibility.AccessibilityWindowInfo;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdSkipperService extends AccessibilityService {
    private enum QualityContextKind { MENU_ACTION, RESOLUTION_OPTION }
    private volatile long activeQualityToken = 0L;
    private volatile String inFlightQualityTitle = null;
    private Runnable activeQualityStepRunnable = null;
    private Runnable activeQualityTimeoutRunnable = null;
    private static final Pattern EXPLICIT_FPS_NUMBER_PATTERN = Pattern.compile("\\b(24|25|30|48|50|60|120)\\s*(?:fps|p\\d+)?\\b", Pattern.CASE_INSENSITIVE);


    private static final String YT_PKG = "com.google.android.youtube";
    private static final String YTM_PKG = "com.google.android.apps.youtube.music";
    private static final String TAG = "AdSkipperService";
    private static final Pattern TIME_PAIR_PATTERN = Pattern.compile("^(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*/\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)$");

    private static final Set<String> IGNORED_TITLES = new HashSet<>(Arrays.asList(
            "subscribe", "subscribed", "share", "remix", "thanks", "download",
            "clip", "save", "report", "comments", "live chat", "description",
            "shorts", "subscriptions", "library", "home", "you", "explore",
            "autoplay", "captions", "settings", "cast", "search", "notifications"
    ));

    private long lastClickMs = 0L;
    private long lastSponsorJumpMs = 0L;
    private long lastFeedHideMs = 0L;
    private volatile boolean feedHideFlowRunning = false;

    private long lastControlRevealMs = 0L;
    private Rect lastPlayerBounds = null;

    // Tracks estimated playback position even after YouTube's overlay auto-hides
    private String lastQualityAppliedTitle = null;
    private volatile boolean qualityFlowRunning = false;
    private long qualityFlowStartMs = 0L;
    private static final long QUALITY_FLOW_TIMEOUT_MS = 20000L;
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("(\\d{3,4})p");
    private String trackedVideoTitle = null;
    private final Set<String> skippedSegmentKeys = new HashSet<>();
    private double anchorVideoSec = -1.0;
    private double anchorTotalSec = -1.0;
    private long anchorRealtimeMs = 0L;
    private volatile boolean ytInForeground = false;

    private final Handler sbHandler = new Handler(Looper.getMainLooper());
    private final Runnable sbHeartbeat = new Runnable() {
        @Override
        public void run() {
            try {
                if (ytInForeground && PrefsHelper.isEnabled(AdSkipperService.this, PrefsHelper.KEY_SPONSORBLOCK)) {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
                        if (YT_PKG.equals(pkg)) {
                            trySponsorBlockSkip(root, SystemClock.elapsedRealtime());
                        } else {
                            ytInForeground = false;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "SponsorBlock heartbeat error", e);
            } finally {
                sbHandler.postDelayed(this, 1500L);
            }
        }
    };

    private final SponsorBlockCoordinatorService.ControlRevealListener controlListener =
            new SponsorBlockCoordinatorService.ControlRevealListener() {
                @Override
                public void onRequestPlayerControls(String videoTitle, String category) {
                    SponsorBlockCoordinatorService.registerListener(controlListener);
        long now = SystemClock.elapsedRealtime();
                    if (now - lastControlRevealMs < 3500L) return;
                    lastControlRevealMs = now;
                    int x = (lastPlayerBounds != null) ? (lastPlayerBounds.left + Math.max(36, (int) (lastPlayerBounds.width() * 0.12))) : 140;
                    int y = (lastPlayerBounds != null) ? (lastPlayerBounds.top + Math.max(36, (int) (lastPlayerBounds.height() * 0.18))) : 200;
                    Path p = new Path();
                    p.moveTo(x, y);
                    GestureDescription.Builder b = new GestureDescription.Builder();
                    b.addStroke(new GestureDescription.StrokeDescription(p, 0L, 40L));
                    Log.i(TAG, "Coordinator revealing controls for '" + videoTitle + "' (" + category + ") at " + x + "," + y);
                    dispatchGesture(b.build(), null, null);
                }
            };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "AdSkipperService connected");
        SponsorBlockCoordinatorService.registerListener(controlListener);
        sbHandler.removeCallbacks(sbHeartbeat);
        sbHandler.postDelayed(sbHeartbeat, 1500L);
    }

    @Override
    public void onDestroy() {
        sbHandler.removeCallbacks(sbHeartbeat);
        SponsorBlockCoordinatorService.unregisterListener(controlListener);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        boolean isYt = YT_PKG.equals(pkg);
        boolean isYtm = YTM_PKG.equals(pkg);
        if (!isYt && !isYtm) {
            ytInForeground = false;
            return;
        }
        ytInForeground = isYt;

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

        if (isYt && PrefsHelper.isEnabled(this, PrefsHelper.KEY_SPONSORBLOCK) && now - lastSponsorJumpMs >= 2500L) {
            trySponsorBlockSkip(root, now);
        }

        if (qualityFlowRunning && (now - qualityFlowStartMs > QUALITY_FLOW_TIMEOUT_MS)) {
            Log.w(TAG, "Auto-quality flow timed out after 20s; resetting state");
            finishQualityFlow(false);
        }

        if (isYt && PrefsHelper.isEnabled(this, PrefsHelper.KEY_AUTO_QUALITY) && !qualityFlowRunning) {
            maybeStartAutoHighestQuality(root, now);
        }

        if (isYt && PrefsHelper.isEnabled(this, PrefsHelper.KEY_HIDE_FEATURED_PLAYABLES) && !qualityFlowRunning && !feedHideFlowRunning && now - lastFeedHideMs >= 3000L) {
            maybeDismissFeaturedOrFeedAd(root, now);
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

    private boolean isValidVideoTitleCandidate(String s) {
        if (s == null) return false;
        String clean = s.trim();
        if (clean.length() < 6 || clean.length() > 160) return false;
        if (clean.contains("/")) return false;
        String lower = clean.toLowerCase();
        if (IGNORED_TITLES.contains(lower)) return false;
        if (lower.endsWith(" views") || lower.endsWith(" ago") || lower.endsWith(" subscribers")) return false;
        if (lower.startsWith("ad ·") || lower.startsWith("sponsored")) return false;
        return true;
    }

    private void trySponsorBlockSkip(AccessibilityNodeInfo root, long now) {
        String idMatchedTitle = null;
        String spatialFallbackTitle = null;
        int bestTitleTop = Integer.MAX_VALUE;
        double visibleCurrentSec = -1;
        double visibleTotalSec = -1;
        Rect playerBounds = null;

        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName().toLowerCase() : "";
            String text = node.getText() != null ? node.getText().toString().trim() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim() : "";

            if (id.contains("player_fragment_container") || id.contains("watch_player") || id.contains("player_view")) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.width() > 250 && r.height() > 150) {
                    playerBounds = r;
                    lastPlayerBounds = new Rect(r);
                }
            }

            if (idMatchedTitle == null && (id.contains("title") || id.contains("video_title"))) {
                if (isValidVideoTitleCandidate(text)) {
                    idMatchedTitle = text;
                } else if (isValidVideoTitleCandidate(desc)) {
                    idMatchedTitle = desc;
                }
            }

            if (visibleCurrentSec < 0) {
                String timeSource = text.contains("/") ? text : (desc.contains("/") ? desc : "");
                if (!timeSource.isEmpty()) {
                    Matcher m = TIME_PAIR_PATTERN.matcher(timeSource);
                    if (m.matches()) {
                        visibleCurrentSec = parseTimeSeconds(m.group(1));
                        visibleTotalSec = parseTimeSeconds(m.group(2));
                    }
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }

        // Second pass for Litho/Elements UI where video title has no resource-id
        if (idMatchedTitle == null && playerBounds != null) {
            Deque<AccessibilityNodeInfo> q2 = new ArrayDeque<>();
            q2.add(root);
            while (!q2.isEmpty()) {
                AccessibilityNodeInfo node = q2.removeFirst();
                String text = node.getText() != null ? node.getText().toString().trim() : "";
                String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim() : "";
                String candidate = isValidVideoTitleCandidate(text) ? text : (isValidVideoTitleCandidate(desc) ? desc : null);
                if (candidate != null) {
                    Rect r = new Rect();
                    node.getBoundsInScreen(r);
                    if (r.top >= playerBounds.bottom - 10 && r.top <= playerBounds.bottom + 280 && r.width() > 250) {
                        if (r.top < bestTitleTop) {
                            bestTitleTop = r.top;
                            spatialFallbackTitle = candidate;
                        }
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) q2.add(child);
                }
            }
        }

        String videoTitle = (idMatchedTitle != null) ? idMatchedTitle : spatialFallbackTitle;
        if (videoTitle == null) {
            String notifTitle = YouTubeNotificationListenerService.getLatestPlayingTitle();
            if (isValidVideoTitleCandidate(notifTitle)) {
                videoTitle = notifTitle;
            }
        }
        if (videoTitle == null) {
            return;
        }

        if (!videoTitle.equals(trackedVideoTitle)) {
            Log.i(TAG, "Detected YouTube video title: '" + videoTitle + "'");
            trackedVideoTitle = videoTitle;
            skippedSegmentKeys.clear();
            anchorVideoSec = -1.0;
            anchorTotalSec = -1.0;
            anchorRealtimeMs = 0L;
        }

        if (visibleCurrentSec >= 0 && visibleTotalSec > 0) {
            anchorVideoSec = visibleCurrentSec;
            anchorTotalSec = visibleTotalSec;
            anchorRealtimeMs = now;
        }

        double effectiveSec = visibleCurrentSec;
        double effectiveTotal = visibleTotalSec;
        if (effectiveSec < 0 && anchorVideoSec >= 0 && anchorTotalSec > 0 && anchorRealtimeMs > 0) {
            effectiveSec = anchorVideoSec + ((now - anchorRealtimeMs) / 1000.0);
            effectiveTotal = anchorTotalSec;
        }

        // Always fetch/retrieve segments immediately once video title is known
        List<SponsorBlockHelper.Segment> segments =
                SponsorBlockHelper.getSegmentsForTitle(getApplicationContext(), videoTitle, effectiveSec);

        if (segments.isEmpty()) return;

        // If we have segments but don't have a live or anchored timestamp yet, ask coordinator to reveal controls
        if (effectiveSec < 0 || effectiveTotal <= 0) {
            SponsorBlockCoordinatorService.requestControlsForSkip(getApplicationContext(), videoTitle, segments.get(0).category);
            return;
        }

        for (SponsorBlockHelper.Segment seg : segments) {
            String segKey = trackedVideoTitle + ":" + Math.round(seg.startSec * 10) + ":" + Math.round(seg.endSec * 10);
            if (!skippedSegmentKeys.contains(segKey)
                    && (now - lastSponsorJumpMs) >= 2500L
                    && effectiveSec >= seg.startSec
                    && effectiveSec < (seg.endSec - 1.5)) {
                double skipDelta = (seg.endSec - effectiveSec) + 2.0;
                int taps = Math.max(1, Math.min(12, (int) Math.ceil(skipDelta / 10.0)));
                boolean jumped = false;

                AccessibilityNodeInfo ffNode = findSafeFastForwardNode(root);
                if (ffNode != null && clickNodeSafely(ffNode, false)) {
                    Log.i(TAG, "Triggered fast-forward node for SponsorBlock [" + seg.category + "]");
                    jumped = true;
                } else if (performDoubleTapSkipForward(playerBounds, taps)) {
                    Log.i(TAG, "Jumping SponsorBlock [" + seg.category + "] from " + effectiveSec + "s to " + seg.endSec + "s (" + taps + "x10s)");
                    jumped = true;
                }

                if (jumped) {
                    skippedSegmentKeys.add(segKey);
                    lastSponsorJumpMs = now;
                    anchorVideoSec = seg.endSec;
                    anchorRealtimeMs = now;
                    PrefsHelper.incStat(this, PrefsHelper.STAT_SPONSORS);
                    NotifyHelper.postAlert(this, "SponsorBlock Jump", "Skipped " + seg.category + " segment");
                }
                break;
            }
        }
    }

    private AccessibilityNodeInfo findSafeFastForwardNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
            if (!n.isEnabled() || isTransportControlSelfOrDescendant(n, 0)) continue;
            String id = n.getViewIdResourceName() != null ? n.getViewIdResourceName().toLowerCase() : "";
            String txt = n.getText() != null ? n.getText().toString().trim().toLowerCase() : "";
            String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim().toLowerCase() : "";
            if (id.contains("fast_forward") || id.contains("ffwd")
                    || txt.equals("fast forward") || desc.equals("fast forward")
                    || desc.startsWith("fast forward")) {
                return n;
            }
        }
        return null;
    }

    private boolean performDoubleTapSkipForward(Rect playerBounds, int tenSecSteps) {
        if (android.os.Build.VERSION.SDK_INT < 24) return false;
        if (playerBounds == null || playerBounds.width() <= 250 || playerBounds.height() <= 150) {
            return false;
        }
        int x = playerBounds.left + (int) (playerBounds.width() * 0.8);
        int y = playerBounds.top + (int) (playerBounds.height() / 2);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        long t = 0L;
        int totalTaps = Math.max(2, Math.min(13, tenSecSteps + 1));
        for (int i = 0; i < totalTaps; i++) {
            Path p = new Path();
            p.moveTo(x, y);
            builder.addStroke(new GestureDescription.StrokeDescription(p, t, 35L));
            t += 95L;
        }
        return dispatchGesture(builder.build(), null, null);
    }

    private double parseTimeSeconds(String ts) {
        if (ts == null || ts.trim().isEmpty()) return -1.0;
        try {
            String[] parts = ts.trim().split(":");
            double sec = 0;
            for (String part : parts) {
                sec = sec * 60 + Integer.parseInt(part.trim());
            }
            return sec;
        } catch (Exception e) {
            return -1.0;
        }
    }

    private boolean isBlockedPlaybackOrTransportNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        String id = n.getViewIdResourceName() != null ? n.getViewIdResourceName().toLowerCase() : "";
        String txt = n.getText() != null ? n.getText().toString().trim().toLowerCase() : "";
        String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim().toLowerCase() : "";
        String combined = (id + " " + txt + " " + desc).trim();

        if ("pause".equals(txt) || "play".equals(txt) || "pause video".equals(desc) || "play video".equals(desc)
                || "play/pause".equals(desc) || desc.startsWith("pause ") || desc.startsWith("play ")) {
            return true;
        }
        if (id.contains("play_pause") || id.contains("player_control_play_pause")
                || id.contains("video_play_pause") || id.contains("media_control_play_pause")
                || id.contains("skip_prev") || id.contains("skip_next") || id.contains("seek_bar")) {
            return true;
        }
        if (combined.contains("pause video") || combined.contains("play video") || combined.contains("toggle play")) {
            return true;
        }
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        if (node == null || isBlockedPlaybackOrTransportNode(node)) {
            return false;
        }
        AccessibilityNodeInfo current = node;
        int depth = 0;
        while (current != null && depth < 4) {
            if (isBlockedPlaybackOrTransportNode(current)) {
                return false;
            }
            if (current.isClickable() && current.isEnabled()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
            depth++;
        }
        return false;
    }

    private void maybeStartAutoHighestQuality(AccessibilityNodeInfo root, long nowMs) {
        String title = extractCurrentVideoTitle(root);
        if (title == null || title.equals(lastQualityAppliedTitle)) {
            return;
        }
        if (!hasActivePlaybackTimer(root)) {
            return;
        }
        lastQualityAppliedTitle = title;
        qualityFlowRunning = true;
        qualityFlowStartMs = nowMs;
        Log.i(TAG, "Starting auto-highest-quality flow for video: '" + title + "'");
        runQualityStepMoreOptions(0);
    }

    private String extractCurrentVideoTitle(AccessibilityNodeInfo root) {
        String idTitle = null;
        String spatialTitle = null;
        int bestTop = Integer.MAX_VALUE;
        android.graphics.Rect pBounds = lastPlayerBounds;

        java.util.Deque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String id = n.getViewIdResourceName() != null ? n.getViewIdResourceName().toLowerCase() : "";
            String txt = n.getText() != null ? n.getText().toString().trim() : "";
            String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim() : "";

            if (id.contains("player_fragment_container") || id.contains("watch_player") || id.contains("player_view")) {
                android.graphics.Rect r = new android.graphics.Rect();
                n.getBoundsInScreen(r);
                if (r.width() > 250 && r.height() > 150) {
                    pBounds = r;
                }
            }
            if (idTitle == null && (id.contains("title") || id.contains("video_title"))) {
                if (isValidVideoTitleCandidate(txt)) idTitle = txt;
                else if (isValidVideoTitleCandidate(desc)) idTitle = desc;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }

        if (idTitle != null) return idTitle;

        if (pBounds != null) {
            java.util.Deque<AccessibilityNodeInfo> q2 = new java.util.ArrayDeque<>();
            q2.add(root);
            while (!q2.isEmpty()) {
                AccessibilityNodeInfo n = q2.removeFirst();
                String txt = n.getText() != null ? n.getText().toString().trim() : "";
                String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim() : "";
                String cand = isValidVideoTitleCandidate(txt) ? txt : (isValidVideoTitleCandidate(desc) ? desc : null);
                if (cand != null) {
                    android.graphics.Rect r = new android.graphics.Rect();
                    n.getBoundsInScreen(r);
                    if (r.top >= pBounds.bottom - 10 && r.top <= pBounds.bottom + 280 && r.width() > 250 && r.top < bestTop) {
                        bestTop = r.top;
                        spatialTitle = cand;
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q2.add(c);
                }
            }
        }
        return spatialTitle != null ? spatialTitle : YouTubeNotificationListenerService.getLatestPlayingTitle();
    }
    private String extractCurrentVideoTitle(List<AccessibilityNodeInfo> roots) {
        if (roots == null) return null;
        for (AccessibilityNodeInfo r : roots) {
            if (r == null) continue;
            String t = extractCurrentVideoTitle(r);
            if (t != null && !t.isEmpty()) {
                return t;
            }
        }
        return null;
    }


    private boolean hasActivePlaybackTimer(AccessibilityNodeInfo root) {
        java.util.Deque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String txt = n.getText() != null ? n.getText().toString().trim() : "";
            String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim() : "";
            String src = txt.contains("/") ? txt : (desc.contains("/") ? desc : "");
            if (!src.isEmpty() && TIME_PAIR_PATTERN.matcher(src).matches()) {
                return true;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return false;
    }

    private boolean hasQualityFlowTimedOut() {
        return qualityFlowRunning && (SystemClock.elapsedRealtime() - qualityFlowStartMs > QUALITY_FLOW_TIMEOUT_MS);
    }

    private List<AccessibilityNodeInfo> collectAllWindowRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
                if (wins != null) {
                    for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                        if (w != null && w.getRoot() != null) {
                            roots.add(w.getRoot());
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && !roots.contains(active)) {
            roots.add(active);
        }
        return roots;
    }

    private AccessibilityNodeInfo findGearControlInRoots(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo r : roots) {
            if (r == null) continue;
            Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(r);
            while (!q.isEmpty()) {
                AccessibilityNodeInfo n = q.removeFirst();
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                }
                if (!n.isEnabled()) continue;
                String id = n.getViewIdResourceName() != null ? n.getViewIdResourceName().toLowerCase() : "";
                String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim().toLowerCase() : "";
                boolean match = id.contains("player_overflow")
                        || id.contains("quick_actions_overflow")
                        || "settings".equals(desc)
                        || "more options".equals(desc)
                        || desc.startsWith("settings")
                        || desc.startsWith("more options");
                if (match) {
                    Rect b = new Rect();
                    n.getBoundsInScreen(b);
                    if (!b.isEmpty() && (lastPlayerBounds == null || b.top <= lastPlayerBounds.bottom)) {
                        return n;
                    }
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findSheetRowExact(List<AccessibilityNodeInfo> roots, String target) {
        for (AccessibilityNodeInfo r : roots) {
            if (r == null) continue;
            Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(r);
            while (!q.isEmpty()) {
                AccessibilityNodeInfo n = q.removeFirst();
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                }
                if (!n.isEnabled()) continue;
                String txt = n.getText() != null ? n.getText().toString().trim().toLowerCase() : "";
                String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim().toLowerCase() : "";
                if (target.equals(txt) || target.equals(desc)) {
                    Rect b = new Rect();
                    n.getBoundsInScreen(b);
                    if (!b.isEmpty() && (lastPlayerBounds == null || b.top >= lastPlayerBounds.bottom - 20)) {
                        return n;
                    }
                }
            }
        }
        return null;
    }

    private void runQualityStepMoreOptions(final int attempt) {
        if (!qualityFlowRunning || hasQualityFlowTimedOut() || attempt > 4) {
            finishQualityFlow(false);
            return;
        }
        List<AccessibilityNodeInfo> roots = collectAllWindowRoots();
        if (findSheetRowExact(roots, "advanced") != null) {
            runQualityStepOpenAdvanced(0);
            return;
        }
        for (AccessibilityNodeInfo r : roots) {
            if (findHighestResolutionNode(r) != null) {
                runQualityStepPickHighestRes(0);
                return;
            }
        }
        AccessibilityNodeInfo gear = findGearControlInRoots(roots);
        if (gear != null && clickNodeSafely(gear, true)) {
            Log.i(TAG, "Opened YouTube player settings menu");
            sbHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    runQualityStepOpenQuality(0);
                }
            }, 550L);
            return;
        }
        if (attempt == 0) {
            tapPlayerCenter();
        }
        sbHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                runQualityStepMoreOptions(attempt + 1);
            }
        }, 550L);
    }

    private void runQualityStepOpenQuality(final int attempt) {
        if (!qualityFlowRunning || hasQualityFlowTimedOut() || attempt > 5) {
            finishQualityFlow(false);
            return;
        }
        List<AccessibilityNodeInfo> roots = collectAllWindowRoots();
        AccessibilityNodeInfo qualRow = findSheetRowExact(roots, "quality");
        if (qualRow != null && clickNodeSafely(qualRow, true)) {
            Log.i(TAG, "Selected Quality row");
            sbHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    runQualityStepOpenAdvanced(0);
                }
            }, 550L);
            return;
        }
        sbHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                runQualityStepOpenQuality(attempt + 1);
            }
        }, 400L);
    }

    private void runQualityStepOpenAdvanced(final int attempt) {
        if (!qualityFlowRunning || hasQualityFlowTimedOut() || attempt > 5) {
            finishQualityFlow(false);
            return;
        }
        List<AccessibilityNodeInfo> roots = collectAllWindowRoots();
        AccessibilityNodeInfo advRow = findSheetRowExact(roots, "advanced");
        if (advRow != null && clickNodeSafely(advRow, true)) {
            Log.i(TAG, "Selected Advanced quality row");
            sbHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    runQualityStepPickHighestRes(0);
                }
            }, 550L);
            return;
        }
        sbHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                runQualityStepOpenAdvanced(attempt + 1);
            }
        }, 400L);
    }

    private void runQualityStepPickHighestRes(final int attempt) {
        if (!qualityFlowRunning || hasQualityFlowTimedOut() || attempt > 5) {
            finishQualityFlow(false);
            return;
        }
        List<AccessibilityNodeInfo> roots = collectAllWindowRoots();
        AccessibilityNodeInfo bestRes = null;
        for (AccessibilityNodeInfo r : roots) {
            AccessibilityNodeInfo cand = findHighestResolutionNode(r);
            if (cand != null) {
                bestRes = cand;
                break;
            }
        }
        if (bestRes != null && clickNodeSafely(bestRes, true)) {
            Log.i(TAG, "Selected highest resolution: " + bestRes.getText());
            finishQualityFlow(true);
            return;
        }
        sbHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                runQualityStepPickHighestRes(attempt + 1);
            }
        }, 400L);
    }

    private void finishQualityFlow(boolean success) {
        qualityFlowRunning = false;
        Log.d(TAG, "Quality flow finished (success=" + success + ")");
    }

    private void tapPlayerCenter() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return;
        int x = (lastPlayerBounds != null)
                ? (lastPlayerBounds.left + Math.max(36, (int) (lastPlayerBounds.width() * 0.12)))
                : 140;
        int y = (lastPlayerBounds != null)
                ? (lastPlayerBounds.top + Math.max(36, (int) (lastPlayerBounds.height() * 0.18)))
                : 200;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(p, 0L, 45L));
        dispatchGesture(gb.build(), null, null);
    }

    private AccessibilityNodeInfo findNodeByKeywords(AccessibilityNodeInfo root, String[] keywords) {
        java.util.Deque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String id = n.getViewIdResourceName() != null ? n.getViewIdResourceName().toLowerCase() : "";
            String txt = n.getText() != null ? n.getText().toString().trim().toLowerCase() : "";
            String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim().toLowerCase() : "";
            for (String kw : keywords) {
                if (txt.contains(kw) || desc.contains(kw) || id.contains(kw)) {
                    if (n.isEnabled()) return n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findHighestResolutionNode(AccessibilityNodeInfo root) {
        int highestRes = -1;
        AccessibilityNodeInfo bestNode = null;
        java.util.List<AccessibilityNodeInfo> allNodes = new java.util.ArrayList<>();

        java.util.Deque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            allNodes.add(n);
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }

        for (AccessibilityNodeInfo n : allNodes) {
            if (!n.isEnabled()) continue;
            String txt = n.getText() != null ? n.getText().toString().trim() : "";
            String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim() : "";
            if (isBlockedPremiumOption(n, txt, desc)) continue;
            int res = extractResolutionValue(txt);
            if (res < 0) res = extractResolutionValue(desc);
            if (res > highestRes) {
                highestRes = res;
                bestNode = n;
            }
        }
        return bestNode;
    }

    private boolean isBlockedPremiumOption(AccessibilityNodeInfo node, String txt, String desc) {
        String combined = (txt + " " + desc).toLowerCase();
        if (combined.contains("premium") || combined.contains("enhanced bitrate")) {
            return true;
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            String pTxt = parent.getText() != null ? parent.getText().toString().toLowerCase() : "";
            String pDesc = parent.getContentDescription() != null ? parent.getContentDescription().toString().toLowerCase() : "";
            if (pTxt.contains("premium") || pDesc.contains("premium")
                    || pTxt.contains("enhanced bitrate") || pDesc.contains("enhanced bitrate")) {
                return true;
            }
            for (int i = 0; i < parent.getChildCount(); i++) {
                AccessibilityNodeInfo sibling = parent.getChild(i);
                if (sibling != null) {
                    String sTxt = sibling.getText() != null ? sibling.getText().toString().toLowerCase() : "";
                    String sDesc = sibling.getContentDescription() != null ? sibling.getContentDescription().toString().toLowerCase() : "";
                    if (sTxt.contains("premium") || sDesc.contains("premium")
                            || sTxt.contains("enhanced bitrate") || sDesc.contains("enhanced bitrate")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int extractResolutionValue(String s) {
        if (s == null || s.isEmpty()) return -1;
        java.util.regex.Matcher m = RESOLUTION_PATTERN.matcher(s.toLowerCase());
        int max = -1;
        while (m.find()) {
            try {
                int val = Integer.parseInt(m.group(1));
                if (val >= 144 && val <= 4320 && val > max) {
                    max = val;
                }
            } catch (Exception ignored) {
            }
        }
        return max;
    }

    private void maybeDismissFeaturedOrFeedAd(AccessibilityNodeInfo root, long now) {
        AccessibilityNodeInfo directHide = findHideMenuAction(root);
        if (directHide != null && clickNodeOrParent(directHide)) {
            lastFeedHideMs = now;
            feedHideFlowRunning = false;
            Log.i(TAG, "Dismissed featured/playables item via Not interested");
            return;
        }

        AccessibilityNodeInfo targetCard = findFeaturedOrFeedAdCard(root);
        if (targetCard == null) return;

        AccessibilityNodeInfo menuBtn = findOverflowButtonWithinOrNear(root, targetCard);
        if (menuBtn != null && clickNodeOrParent(menuBtn)) {
            lastFeedHideMs = now;
            feedHideFlowRunning = true;
            Log.i(TAG, "Opened overflow menu for featured/playables item");
            sbHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        AccessibilityNodeInfo curRoot = getRootInActiveWindow();
                        if (curRoot != null) {
                            AccessibilityNodeInfo action = findHideMenuAction(curRoot);
                            if (action != null) {
                                clickNodeOrParent(action);
                                Log.i(TAG, "Tapped Not interested on featured/playables item");
                            } else {
                                performGlobalAction(GLOBAL_ACTION_BACK);
                            }
                        }
                    } finally {
                        feedHideFlowRunning = false;
                    }
                }
            }, 350L);
        }
    }

    private AccessibilityNodeInfo findFeaturedOrFeedAdCard(AccessibilityNodeInfo root) {
        java.util.Deque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String txt = n.getText() != null ? n.getText().toString().trim().toLowerCase() : "";
            String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim().toLowerCase() : "";
            String combined = txt + " " + desc;

            boolean isTargetJunk = combined.contains("youtube featured")
                    || combined.contains("featured from youtube")
                    || combined.contains("youtube playables")
                    || combined.contains("playables");

            if (isTargetJunk && n.isEnabled()) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findOverflowButtonWithinOrNear(AccessibilityNodeInfo root, AccessibilityNodeInfo card) {
        android.graphics.Rect cardRect = new android.graphics.Rect();
        card.getBoundsInScreen(cardRect);
        if (cardRect.isEmpty()) return null;

        java.util.Deque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String id = n.getViewIdResourceName() != null ? n.getViewIdResourceName().toLowerCase() : "";
            String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim().toLowerCase() : "";
            boolean looksLikeMenu = id.contains("menu") || id.contains("overflow") || id.contains("more")
                    || desc.contains("more actions") || desc.contains("action menu");
            if (looksLikeMenu && n.isEnabled()) {
                android.graphics.Rect r = new android.graphics.Rect();
                n.getBoundsInScreen(r);
                if (android.graphics.Rect.intersects(cardRect, r)
                        || (r.top >= cardRect.top - 40 && r.bottom <= cardRect.bottom + 120 && r.left >= cardRect.centerX())) {
                    return n;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findHideMenuAction(AccessibilityNodeInfo root) {
        java.util.Deque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String txt = n.getText() != null ? n.getText().toString().trim().toLowerCase() : "";
            String desc = n.getContentDescription() != null ? n.getContentDescription().toString().trim().toLowerCase() : "";
            if (n.isEnabled() && (txt.contains("not interested") || desc.contains("not interested"))) {
                return n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }


    private boolean isTransportControlSelfOrDescendant(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 4) return false;
        String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName().toLowerCase() : "";
        String txt = node.getText() != null ? node.getText().toString().trim().toLowerCase() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString().trim().toLowerCase() : "";

        if ("pause".equals(txt) || "play".equals(txt)
                || "pause".equals(desc) || "play".equals(desc)
                || desc.startsWith("pause video") || desc.startsWith("play video")
                || "play/pause".equals(desc)
                || desc.startsWith("rewind") || desc.startsWith("fast forward")
                || desc.startsWith("previous video") || desc.startsWith("next video")
                || desc.contains("seek slider") || desc.contains("scrub")) {
            return true;
        }
        if (id.contains("play_pause") || id.contains("player_control_play_pause")
                || id.contains("video_play_pause") || id.contains("media_control")
                || id.contains("skip_prev") || id.contains("skip_next")
                || id.contains("seek_bar") || id.contains("time_bar")
                || id.contains("player_overlays") || id.contains("watch_player")) {
            return true;
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            if (isTransportControlSelfOrDescendant(node.getChild(i), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean clickNodeSafely(AccessibilityNodeInfo node, boolean allowTapFallback) {
        if (node == null || isTransportControlSelfOrDescendant(node, 0)) {
            return false;
        }
        AccessibilityNodeInfo cur = node;
        int depth = 0;
        while (cur != null && depth < 7) {
            if (isTransportControlSelfOrDescendant(cur, 0)) {
                break;
            }
            if (cur.isClickable() && cur.isEnabled()) {
                Rect r = new Rect();
                cur.getBoundsInScreen(r);
                if (lastPlayerBounds != null
                        && r.width() >= (int) (lastPlayerBounds.width() * 0.85)
                        && r.height() >= (int) (lastPlayerBounds.height() * 0.85)) {
                    break;
                }
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
            cur = cur.getParent();
            depth++;
        }
        if (allowTapFallback && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            if (!b.isEmpty() && b.width() > 8 && b.height() > 8) {
                if (lastPlayerBounds != null && b.contains(lastPlayerBounds.centerX(), lastPlayerBounds.centerY())) {
                    return false;
                }
                Path p = new Path();
                p.moveTo(b.centerX(), b.centerY());
                GestureDescription.Builder gb = new GestureDescription.Builder();
                gb.addStroke(new GestureDescription.StrokeDescription(p, 0L, 45L));
                return dispatchGesture(gb.build(), null, null);
            }
        }
        return false;
    }

    private void runQualityStepPickHighestRes(final int attempt, final long token) {
        if (token != activeQualityToken || !qualityFlowRunning) {
            return;
        }
        if (attempt > 3) {
            finishQualityFlow(false, token);
            return;
        }
        List<AccessibilityNodeInfo> roots = collectQualityRoots();
        if (!verifyActiveVideoTitleMatches(roots, token)) {
            return;
        }
        List<ResolutionCandidate> group = findBestResolutionMenuGroup(roots);
        if (group == null || group.size() < 2) {
            finishQualityFlow(false, token);
            return;
        }
        ResolutionCandidate highestCandidate = null;
        for (ResolutionCandidate cand : group) {
            if (cand == null || cand.clickableTarget == null) continue;
            if (cand.resolution < 144 || cand.resolution > 4320) continue;
            if (!hasVerifiedYouTubePackage(cand.clickableTarget)
                    || !cand.clickableTarget.isClickable()
                    || !cand.clickableTarget.isEnabled()
                    || !cand.clickableTarget.isVisibleToUser()
                    || isDisallowedPlayerOrTransportNode(cand.clickableTarget)) {
                continue;
            }
            if (highestCandidate == null
                    || cand.resolution > highestCandidate.resolution
                    || (cand.resolution == highestCandidate.resolution && cand.fps60 && !highestCandidate.fps60)) {
                highestCandidate = cand;
            }
        }
        if (highestCandidate == null || highestCandidate.resolution < 144 || highestCandidate.resolution > 4320) {
            finishQualityFlow(false, token);
            return;
        }
        final int verifiedTargetRes = highestCandidate.resolution;
        if (clickExactValidatedTarget(highestCandidate.clickableTarget)) {
            scheduleQualityStep(token, new Runnable() {
                @Override
                public void run() {
                    if (token != activeQualityToken || !qualityFlowRunning) return;
                    List<AccessibilityNodeInfo> verifyRoots = collectQualityRoots();
                    if (!verifyActiveVideoTitleMatches(verifyRoots, token)) {
                        return;
                    }
                    if (verifyResolutionSelectedFromFreshRoots(verifyRoots, verifiedTargetRes)) {
                        Log.i(TAG, "Verified selection state for " + verifiedTargetRes + "p");
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        finishQualityFlow(true, token);
                    } else if (hasDemonstrableResolutionList(verifyRoots)) {
                        runQualityStepPickHighestRes(attempt + 1, token);
                    } else {
                        Log.w(TAG, "Resolution selection for " + verifiedTargetRes + "p is UNVERIFIED");
                        finishQualityFlow(false, token);
                    }
                }
            }, 400L);
            return;
        }
        scheduleQualityStep(token, new Runnable() {
            @Override
            public void run() {
                runQualityStepPickHighestRes(attempt + 1, token);
            }
        }, 350L);
    }
    private boolean wrapperIsDirectParentOfOption(
            AccessibilityNodeInfo wrapper,
            AccessibilityNodeInfo optionNode) {
        if (wrapper == null || optionNode == null) {
            return false;
        }
        AccessibilityNodeInfo directParent = optionNode.getParent();
        return directParent != null && nodesEqualSafely(directParent, wrapper);
    }

    private boolean isImmediateOptionRowWrapper(
            AccessibilityNodeInfo wrapper,
            AccessibilityNodeInfo optionNode,
            int targetRes) {
        if (wrapper == null || optionNode == null
                || !hasVerifiedYouTubePackage(wrapper)
                || !wrapper.isVisibleToUser()
                || !wrapper.isEnabled()) {
            return false;
        }
        if (!wrapperIsDirectParentOfOption(wrapper, optionNode)) {
            return false;
        }
        if (wrapper.isScrollable() || wrapper.getChildCount() <= 0 || wrapper.getChildCount() > 3) {
            return false;
        }
        String wCls = safeNonEmptyString(wrapper.getClassName());
        String wClsLower = wCls != null ? wCls.toLowerCase() : "";
        if (wClsLower.contains("listview") || wClsLower.contains("recyclerview")
                || wClsLower.contains("scrollview") || wClsLower.contains("dialog")
                || wClsLower.contains("sheet")) {
            return false;
        }
        String wTxt = safeNonEmptyString(wrapper.getText());
        String wDesc = safeNonEmptyString(wrapper.getContentDescription());
        if (hasBlockedPremiumOption(wrapper, wTxt != null ? wTxt : "", wDesc != null ? wDesc : "")) {
            return false;
        }
        int wRes = extractUnambiguousResolutionValue(
                wrapper,
                wTxt != null ? wTxt : "",
                wDesc != null ? wDesc : "");
        if (wRes != -1 && wRes != targetRes) {
            return false;
        }
        if (countResolutionMentionsInSubtree(wrapper, 24) > 1) {
            return false;
        }
        Rect wRect = getNodeBoundsCopy(wrapper);
        Rect oRect = getNodeBoundsCopy(optionNode);
        if (wRect.isEmpty() || wRect.width() <= 0 || wRect.height() <= 0
                || oRect.isEmpty() || oRect.width() <= 0 || oRect.height() <= 0) {
            return false;
        }
        if (!wRect.contains(oRect)) {
            return false;
        }
        if (wRect.height() > (int) (oRect.height() * 1.6)) {
            return false;
        }
        return true;
    }

    private boolean isNodeMarkedSelectedOrCheckedForTarget(AccessibilityNodeInfo node, int targetRes) {
        if (node == null || !hasVerifiedYouTubePackage(node) || targetRes < 144 || targetRes > 4320) {
            return false;
        }
        String nTxt = safeNonEmptyString(node.getText());
        String nDesc = safeNonEmptyString(node.getContentDescription());
        if (hasBlockedPremiumOption(node, nTxt != null ? nTxt : "", nDesc != null ? nDesc : "")) {
            return false;
        }
        int ownRes = extractUnambiguousResolutionValue(
                node,
                nTxt != null ? nTxt : "",
                nDesc != null ? nDesc : "");
        if (ownRes == targetRes && (node.isChecked() || node.isSelected())) {
            return true;
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null && isImmediateOptionRowWrapper(parent, node, targetRes)) {
            if (parent.isChecked() || parent.isSelected()) {
                return true;
            }
        }
        return false;
    }

    private boolean verifyResolutionSelectedFromFreshRoots(List<AccessibilityNodeInfo> freshRoots, int targetRes) {
        if (targetRes < 144 || targetRes > 4320 || freshRoots == null) {
            return false;
        }
        List<ResolutionCandidate> activeGroup = findBestResolutionMenuGroup(freshRoots);
        if (activeGroup == null || activeGroup.size() < 2) {
            return false;
        }
        for (int rootIdx = 0; rootIdx < freshRoots.size(); rootIdx++) {
            AccessibilityNodeInfo r = freshRoots.get(rootIdx);
            if (r == null) continue;
            Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(r);
            while (!q.isEmpty()) {
                AccessibilityNodeInfo n = q.removeFirst();
                if (hasVerifiedYouTubePackage(n) && n.isVisibleToUser()) {
                    String txt = safeNonEmptyString(n.getText());
                    String desc = safeNonEmptyString(n.getContentDescription());
                    int res = extractUnambiguousResolutionValue(
                            n,
                            txt != null ? txt : "",
                            desc != null ? desc : "");
                    if (res == targetRes) {
                        AccessibilityNodeInfo clickable = resolveClickableQualityTarget(n, 4);
                        if (clickable != null
                                && hasVerifiedYouTubePackage(clickable)
                                && !isDisallowedPlayerOrTransportNode(clickable)
                                && !hasBlockedPremiumContext(n, clickable)) {
                            String tKey = buildClickableTargetKey(clickable, rootIdx);
                            boolean inActiveGroup = false;
                            if (tKey != null) {
                                for (ResolutionCandidate rc : activeGroup) {
                                    if (tKey.equals(rc.optionKey)) {
                                        inActiveGroup = true;
                                        break;
                                    }
                                }
                            }
                            if (inActiveGroup
                                    && (isNodeMarkedSelectedOrCheckedForTarget(n, targetRes)
                                    || isNodeMarkedSelectedOrCheckedForTarget(clickable, targetRes))) {
                                return true;
                            }
                        }
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                }
            }
        }
        return false;
    }
    private boolean hasExplicit60FpsLabel(String text, String desc) {
        String[] sources = new String[]{text, desc};
        for (String src : sources) {
            if (src == null || src.isEmpty()) continue;
            Matcher m = EXPLICIT_FPS_NUMBER_PATTERN.matcher(src);
            while (m.find()) {
                for (int g = 1; g <= m.groupCount(); g++) {
                    String numStr = m.group(g);
                    if (numStr != null) {
                        try {
                            if (Integer.parseInt(numStr) == 60) {
                                return true;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean hasExplicitNon60FpsLabel(String text, String desc) {
        String[] sources = new String[]{text, desc};
        for (String src : sources) {
            if (src == null || src.isEmpty()) continue;
            Matcher m = EXPLICIT_FPS_NUMBER_PATTERN.matcher(src);
            while (m.find()) {
                for (int g = 1; g <= m.groupCount(); g++) {
                    String numStr = m.group(g);
                    if (numStr != null) {
                        try {
                            int fps = Integer.parseInt(numStr);
                            if (fps > 0 && fps != 60) {
                                return true;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isStructurallyAndSpatiallyAssociatedHeader(
            AccessibilityNodeInfo candidateHeader,
            AccessibilityNodeInfo container,
            Rect containerBounds) {
        if (candidateHeader == null || container == null
                || containerBounds == null || containerBounds.isEmpty()) {
            return false;
        }
        if (!candidateHeader.isVisibleToUser() || !hasVerifiedYouTubePackage(candidateHeader)) {
            return false;
        }
        if (!nodeHasIndependentQualityMenuLabel(candidateHeader, container)) {
            return false;
        }
        Rect headerRect = getNodeBoundsCopy(candidateHeader);
        if (headerRect.isEmpty() || headerRect.width() <= 0 || headerRect.height() <= 0) {
            return false;
        }
        int overlapLeft = Math.max(containerBounds.left, headerRect.left);
        int overlapRight = Math.min(containerBounds.right, headerRect.right);
        int overlapWidth = overlapRight - overlapLeft;
        int minWidth = Math.min(containerBounds.width(), headerRect.width());
        if (minWidth <= 0 || overlapWidth < (int) (minWidth * 0.6)) {
            return false;
        }
        return headerRect.bottom <= containerBounds.top + 32
                && headerRect.top >= containerBounds.top - 220;
    }
    private boolean nodesEqualSafely(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }

    private Rect getNodeBoundsCopy(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        if (node != null) {
            node.getBoundsInScreen(r);
        }
        return r;
    }

    private boolean hasExplicitQualityContextInSubtree(AccessibilityNodeInfo root) {
        if (root == null) return false;
        int inspected = 0;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty() && inspected < 120) {
            AccessibilityNodeInfo n = q.removeFirst();
            inspected++;
            if (hasVerifiedYouTubePackage(n) && n.isVisibleToUser()) {
                String id = safeNonEmptyString(n.getViewIdResourceName());
                String txt = safeNonEmptyString(n.getText());
                String desc = safeNonEmptyString(n.getContentDescription());
                String safeTxt = txt != null ? txt : "";
                String safeDesc = desc != null ? desc : "";
                if (extractUnambiguousResolutionValue(n, safeTxt, safeDesc) < 144) {
                    String idLower = id != null ? id.toLowerCase() : "";
                    String txtLower = safeTxt.toLowerCase();
                    String descLower = safeDesc.toLowerCase();
                    if (idLower.contains("quality") || idLower.contains("resolution") || idLower.contains("video_quality")
                            || "quality".equals(txtLower) || "quality".equals(descLower)
                            || txtLower.startsWith("quality ") || descLower.startsWith("quality ")
                            || txtLower.contains("video quality") || descLower.contains("video quality")
                            || txtLower.contains("select a specific resolution") || descLower.contains("select a specific resolution")
                            || txtLower.contains("higher picture quality") || descLower.contains("higher picture quality")) {
                        return true;
                    }
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return false;
    }

    private int countResolutionMentionsInSubtree(AccessibilityNodeInfo root, int maxNodes) {
        if (root == null) return 0;
        Set<Integer> distinctRes = new HashSet<>();
        int inspected = 0;
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty() && inspected < maxNodes) {
            AccessibilityNodeInfo n = q.removeFirst();
            inspected++;
            if (hasVerifiedYouTubePackage(n) && n.isVisibleToUser()) {
                String txt = safeNonEmptyString(n.getText());
                String desc = safeNonEmptyString(n.getContentDescription());
                int res = extractUnambiguousResolutionValue(n, txt != null ? txt : "", desc != null ? desc : "");
                if (res >= 144 && res <= 4320) {
                    distinctRes.add(res);
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return distinctRes.size();
    }
    private boolean nodeHasIndependentQualityMenuLabel(
            AccessibilityNodeInfo node,
            AccessibilityNodeInfo scopeContainer) {
        if (node == null || !hasVerifiedYouTubePackage(node) || !node.isVisibleToUser()) {
            return false;
        }
        String id = safeNonEmptyString(node.getViewIdResourceName());
        String txt = safeNonEmptyString(node.getText());
        String desc = safeNonEmptyString(node.getContentDescription());
        String safeTxt = txt != null ? txt : "";
        String safeDesc = desc != null ? desc : "";
        if (extractUnambiguousResolutionValue(node, safeTxt, safeDesc) >= 144) {
            return false;
        }
        String idLower = id != null ? id.toLowerCase() : "";
        String txtLower = safeTxt.toLowerCase();
        String descLower = safeDesc.toLowerCase();
        if (idLower.contains("quality") || idLower.contains("resolution") || idLower.contains("video_quality")) {
            return true;
        }
        if ("quality".equals(txtLower) || "quality".equals(descLower)
                || txtLower.startsWith("quality ") || descLower.startsWith("quality ")
                || txtLower.contains("video quality") || descLower.contains("video quality")
                || txtLower.contains("select a specific resolution") || descLower.contains("select a specific resolution")
                || txtLower.contains("higher picture quality") || descLower.contains("higher picture quality")) {
            return true;
        }
        boolean isAdvancedLabel = "advanced".equals(txtLower) || "advanced".equals(descLower)
                || txtLower.startsWith("advanced");
        if (isAdvancedLabel && hasExplicitQualityContextInSubtree(scopeContainer)) {
            return true;
        }
        return false;
    }

    private boolean hasIndependentQualityMenuContextForContainer(
            AccessibilityNodeInfo container,
            Rect containerBounds) {
        if (container == null || !hasVerifiedYouTubePackage(container) || !container.isVisibleToUser()) {
            return false;
        }
        if (nodeHasIndependentQualityMenuLabel(container, container)) {
            return true;
        }
        AccessibilityNodeInfo immediateParent = container.getParent();
        if (immediateParent != null && hasVerifiedYouTubePackage(immediateParent) && immediateParent.isVisibleToUser()) {
            Rect parentBounds = getNodeBoundsCopy(immediateParent);
            if (!isDisallowedMenuContainer(immediateParent, parentBounds)
                    && parentBounds.contains(containerBounds)) {
                if (nodeHasIndependentQualityMenuLabel(immediateParent, immediateParent)) {
                    return true;
                }
                int containerIdx = -1;
                int childCount = immediateParent.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    AccessibilityNodeInfo child = immediateParent.getChild(i);
                    if (nodesEqualSafely(child, container)) {
                        containerIdx = i;
                        break;
                    }
                }
                if (containerIdx > 0) {
                    AccessibilityNodeInfo immediatelyPrecedingSibling = immediateParent.getChild(containerIdx - 1);
                    if (immediatelyPrecedingSibling != null
                            && isStructurallyAndSpatiallyAssociatedHeader(immediatelyPrecedingSibling, container, containerBounds)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean containerHasResolutionMenuEvidence(
            AccessibilityNodeInfo container,
            Rect containerBounds,
            List<SelectableResolutionItem> members) {
        if (container == null || !container.isVisibleToUser()
                || containerBounds == null || members == null || members.size() < 2) {
            return false;
        }
        if (isDisallowedMenuContainer(container, containerBounds)) {
            return false;
        }
        for (SelectableResolutionItem item : members) {
            if (item == null || item.clickableTarget == null) return false;
            Rect itemRect = new Rect();
            item.clickableTarget.getBoundsInScreen(itemRect);
            if (itemRect.isEmpty() || !containerBounds.contains(itemRect)) {
                return false;
            }
        }
        String cls = safeNonEmptyString(container.getClassName());
        String clsLower = cls != null ? cls.toLowerCase() : "";
        String id = safeNonEmptyString(container.getViewIdResourceName());
        String idLower = id != null ? id.toLowerCase() : "";
        boolean hasMenuOrListRole = container.isScrollable()
                || clsLower.contains("listview")
                || clsLower.contains("recyclerview")
                || clsLower.contains("dialog")
                || clsLower.contains("sheet")
                || idLower.contains("menu")
                || idLower.contains("list")
                || idLower.contains("sheet")
                || idLower.contains("quality")
                || idLower.contains("resolution");
        if (!hasMenuOrListRole) {
            return false;
        }
        return hasIndependentQualityMenuContextForContainer(container, containerBounds);
    }
    private void finishQualityFlow(boolean success, long token) {
        if (token != activeQualityToken) {
            return;
        }
        String finishedTitle = inFlightQualityTitle;
        activeQualityToken++;
        qualityFlowRunning = false;
        inFlightQualityTitle = null;
        if (activeQualityStepRunnable != null) {
            sbHandler.removeCallbacks(activeQualityStepRunnable);
            activeQualityStepRunnable = null;
        }
        if (activeQualityTimeoutRunnable != null) {
            sbHandler.removeCallbacks(activeQualityTimeoutRunnable);
            activeQualityTimeoutRunnable = null;
        }
        if (success && finishedTitle != null && !finishedTitle.isEmpty()) {
            lastQualityAppliedTitle = finishedTitle;
            Log.i(TAG, "Quality flow succeeded for '" + finishedTitle + "'");
        } else {
            Log.i(TAG, "Quality flow finished without verified selection (success=" + success + ")");
        }
    }

    private boolean verifyActiveVideoTitleMatches(List<AccessibilityNodeInfo> roots, long token) {
        if (token != activeQualityToken || !qualityFlowRunning) {
            return false;
        }
        if (inFlightQualityTitle == null || inFlightQualityTitle.isEmpty()) {
            cancelQualityFlow("missing in-flight video title");
            return false;
        }
        String currentTitle = extractCurrentVideoTitle(roots);
        if (currentTitle == null || currentTitle.isEmpty() || !currentTitle.equals(inFlightQualityTitle)) {
            cancelQualityFlow("active video title changed or unavailable");
            return false;
        }
        return true;
    }

    private boolean clickExactValidatedTarget(AccessibilityNodeInfo target) {
        if (target == null || !hasVerifiedYouTubePackage(target)) {
            return false;
        }
        if (!target.isVisibleToUser() || !target.isEnabled() || !target.isClickable()) {
            return false;
        }
        if (isDisallowedPlayerOrTransportNode(target)) {
            return false;
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    private List<SelectableResolutionItem> collectValidSelectableResolutionItems(
            List<AccessibilityNodeInfo> roots,
            int rootIdx) {
        List<SelectableResolutionItem> items = new ArrayList<>();
        if (roots == null) return items;
        Set<String> seenKeys = new HashSet<>();
        for (AccessibilityNodeInfo r : roots) {
            if (r == null) continue;
            Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(r);
            while (!q.isEmpty()) {
                AccessibilityNodeInfo n = q.removeFirst();
                if (hasVerifiedYouTubePackage(n) && n.isVisibleToUser()) {
                    String txt = safeNonEmptyString(n.getText());
                    String desc = safeNonEmptyString(n.getContentDescription());
                    String safeTxt = txt != null ? txt : "";
                    String safeDesc = desc != null ? desc : "";
                    int res = extractUnambiguousResolutionValue(n, safeTxt, safeDesc);
                    if (res >= 144 && res <= 4320) {
                        AccessibilityNodeInfo clickable = resolveClickableQualityTarget(n, 4);
                        if (clickable != null
                                && hasVerifiedYouTubePackage(clickable)
                                && clickable.isClickable()
                                && clickable.isEnabled()
                                && clickable.isVisibleToUser()
                                && !isDisallowedPlayerOrTransportNode(clickable)
                                && !hasBlockedPremiumContext(n, clickable)) {
                            String key = buildClickableTargetKey(clickable, rootIdx);
                            if (key != null && seenKeys.add(key)) {
                                boolean fps60 = hasExplicit60FpsLabel(safeTxt, safeDesc);
                                boolean non60Fps = hasExplicitNon60FpsLabel(safeTxt, safeDesc);
                                items.add(new SelectableResolutionItem(res, fps60, non60Fps, clickable, key));
                            }
                        }
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                }
            }
        }
        return items;
    }

    private boolean hasDemonstrableResolutionList(List<AccessibilityNodeInfo> roots) {
        List<ResolutionCandidate> group = findBestResolutionMenuGroup(roots);
        return group != null && group.size() >= 2;
    }
    private List<ResolutionCandidate> findBestResolutionMenuGroup(List<AccessibilityNodeInfo> roots) {
        if (roots == null || roots.isEmpty()) return Collections.emptyList();
        List<ResolutionCandidate> bestGroup = Collections.emptyList();
        for (int rootIdx = 0; rootIdx < roots.size(); rootIdx++) {
            AccessibilityNodeInfo root = roots.get(rootIdx);
            if (root == null) continue;
            List<SelectableResolutionItem> items = collectValidSelectableResolutionItems(
                    Collections.singletonList(root), rootIdx);
            if (items.size() < 2) continue;

            Map<String, List<SelectableResolutionItem>> byContainer = new LinkedHashMap<>();
            Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            while (!q.isEmpty()) {
                AccessibilityNodeInfo cur = q.removeFirst();
                if (hasVerifiedYouTubePackage(cur) && cur.isVisibleToUser()) {
                    Rect cRect = getNodeBoundsCopy(cur);
                    if (!cRect.isEmpty() && !isDisallowedMenuContainer(cur, cRect)) {
                        List<SelectableResolutionItem> inside = new ArrayList<>();
                        for (SelectableResolutionItem item : items) {
                            Rect iRect = getNodeBoundsCopy(item.clickableTarget);
                            if (!iRect.isEmpty() && cRect.contains(iRect)) {
                                inside.add(item);
                            }
                        }
                        if (inside.size() >= 2 && containerHasResolutionMenuEvidence(cur, cRect, inside)) {
                            String cKey = buildContainerKey(cur, cRect, rootIdx);
                            if (cKey != null) {
                                byContainer.put(cKey, inside);
                            }
                        }
                    }
                }
                for (int i = 0; i < cur.getChildCount(); i++) {
                    AccessibilityNodeInfo c = cur.getChild(i);
                    if (c != null) q.add(c);
                }
            }

            for (List<SelectableResolutionItem> inside : byContainer.values()) {
                Map<Integer, ResolutionCandidate> mergedByRes = new LinkedHashMap<>();
                for (SelectableResolutionItem item : inside) {
                    ResolutionCandidate existing = mergedByRes.get(item.resolution);
                    if (existing == null) {
                        mergedByRes.put(item.resolution, new ResolutionCandidate(
                                item.resolution, item.fps60, item.non60Fps, item.clickableTarget, item.optionKey));
                    } else {
                        boolean any60 = existing.fps60 || item.fps60;
                        boolean anyNon60 = existing.non60Fps || item.non60Fps;
                        boolean conflict = any60 && anyNon60;
                        ResolutionCandidate chosen = (!existing.fps60 && item.fps60) ? new ResolutionCandidate(item.resolution, item.fps60, item.non60Fps, item.clickableTarget, item.optionKey) : null;
                        AccessibilityNodeInfo target = chosen != null ? chosen.clickableTarget : existing.clickableTarget;
                        String key = chosen != null ? chosen.optionKey : existing.optionKey;
                        mergedByRes.put(item.resolution, new ResolutionCandidate(
                                item.resolution, any60 && !conflict, anyNon60 && !conflict, target, key));
                    }
                }
                if (mergedByRes.size() >= 2) {
                    List<ResolutionCandidate> group = new ArrayList<>(mergedByRes.values());
                    if (group.size() > bestGroup.size()) {
                        bestGroup = group;
                    }
                }
            }
        }
        return bestGroup;
    }
    private static final class SelectableResolutionItem {
        final int resolution;
        final boolean fps60;
        final boolean non60Fps;
        final AccessibilityNodeInfo clickableTarget;
        final String optionKey;

        SelectableResolutionItem(
                int resolution,
                boolean fps60,
                boolean non60Fps,
                AccessibilityNodeInfo clickableTarget,
                String optionKey) {
            this.resolution = resolution;
            this.fps60 = fps60;
            this.non60Fps = non60Fps;
            this.clickableTarget = clickableTarget;
            this.optionKey = optionKey;
        }
    }

    private static final class ResolutionCandidate {
        final int resolution;
        final boolean fps60;
        final boolean non60Fps;
        final AccessibilityNodeInfo clickableTarget;
        final String optionKey;

        ResolutionCandidate(
                int resolution,
                boolean fps60,
                boolean non60Fps,
                AccessibilityNodeInfo clickableTarget,
                String optionKey) {
            this.resolution = resolution;
            this.fps60 = fps60;
            this.non60Fps = non60Fps;
            this.clickableTarget = clickableTarget;
            this.optionKey = optionKey;
        }
    }
    private void cancelQualityFlow(String reason) {
        activeQualityToken++;
        qualityFlowRunning = false;
        inFlightQualityTitle = null;
        if (activeQualityStepRunnable != null) {
            sbHandler.removeCallbacks(activeQualityStepRunnable);
            activeQualityStepRunnable = null;
        }
        if (activeQualityTimeoutRunnable != null) {
            sbHandler.removeCallbacks(activeQualityTimeoutRunnable);
            activeQualityTimeoutRunnable = null;
        }
        Log.d(TAG, "Quality flow cancelled (" + reason + ")");
    }

    private void scheduleQualityTimeout(final long token, final long timeoutMs) {
        if (token != activeQualityToken || !qualityFlowRunning) return;
        if (activeQualityTimeoutRunnable != null) {
            sbHandler.removeCallbacks(activeQualityTimeoutRunnable);
            activeQualityTimeoutRunnable = null;
        }
        final Runnable timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (token != activeQualityToken || !qualityFlowRunning) {
                    return;
                }
                if (this == activeQualityTimeoutRunnable) {
                    activeQualityTimeoutRunnable = null;
                }
                Log.w(TAG, "Quality flow timed out after " + timeoutMs + "ms");
                finishQualityFlow(false, token);
            }
        };
        activeQualityTimeoutRunnable = timeoutRunnable;
        sbHandler.postDelayed(timeoutRunnable, timeoutMs);
    }
    private boolean hasVerifiedYouTubePackage(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence pkg = node.getPackageName();
        return pkg != null && "com.google.android.youtube".contentEquals(pkg);
    }

    private String safeNonEmptyString(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private String buildClickableTargetKey(AccessibilityNodeInfo target, int rootIdx) {
        if (target == null) return null;
        Rect r = getNodeBoundsCopy(target);
        String cls = safeNonEmptyString(target.getClassName());
        String id = safeNonEmptyString(target.getViewIdResourceName());
        return rootIdx + "|" + (cls != null ? cls : "") + "|" + (id != null ? id : "")
                + "|" + r.left + "," + r.top + "," + r.right + "," + r.bottom;
    }

    private String buildContainerKey(AccessibilityNodeInfo container, Rect bounds, int rootIdx) {
        if (container == null || bounds == null || bounds.isEmpty()) return null;
        String cls = safeNonEmptyString(container.getClassName());
        String id = safeNonEmptyString(container.getViewIdResourceName());
        return "C|" + rootIdx + "|" + (cls != null ? cls : "") + "|" + (id != null ? id : "")
                + "|" + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom;
    }
    private boolean isDisallowedMenuContainer(AccessibilityNodeInfo container, Rect bounds) {
        if (container == null || bounds == null || bounds.isEmpty()) return true;
        if (bounds.width() <= 0 || bounds.height() <= 0) return true;
        String id = safeNonEmptyString(container.getViewIdResourceName());
        String idLower = id != null ? id.toLowerCase() : "";
        if (idLower.contains("watch_player") || idLower.contains("player_view")
                || idLower.contains("controls_layout") || idLower.contains("transport_controls")
                || idLower.contains("reel_watch_player") || idLower.contains("shorts_player")) {
            return true;
        }
        return false;
    }

    private boolean isDisallowedPlayerOrTransportNode(AccessibilityNodeInfo node) {
        if (node == null) return true;
        String id = safeNonEmptyString(node.getViewIdResourceName());
        String idLower = id != null ? id.toLowerCase() : "";
        String desc = safeNonEmptyString(node.getContentDescription());
        String descLower = desc != null ? desc.toLowerCase() : "";
        if (idLower.contains("scrubber") || idLower.contains("seek_bar")
                || idLower.contains("play_pause") || idLower.contains("fullscreen_button")
                || "video player".equals(descLower) || "seek bar".equals(descLower)) {
            return true;
        }
        return false;
    }
    private boolean hasBlockedPremiumOption(AccessibilityNodeInfo node, String text, String desc) {
        if (node == null) return false;
        String combined = ((text != null ? text : "") + " " + (desc != null ? desc : "")).toLowerCase();
        String id = safeNonEmptyString(node.getViewIdResourceName());
        String idLower = id != null ? id.toLowerCase() : "";
        return combined.contains("premium")
                || combined.contains("higher bitrate")
                || combined.contains("enhanced bitrate")
                || idLower.contains("premium");
    }

    private boolean hasBlockedPremiumContext(
            AccessibilityNodeInfo optionNode,
            AccessibilityNodeInfo clickableTarget) {
        String oTxt = optionNode != null ? safeNonEmptyString(optionNode.getText()) : "";
        String oDesc = optionNode != null ? safeNonEmptyString(optionNode.getContentDescription()) : "";
        if (hasBlockedPremiumOption(optionNode, oTxt != null ? oTxt : "", oDesc != null ? oDesc : "")) {
            return true;
        }
        String cTxt = clickableTarget != null ? safeNonEmptyString(clickableTarget.getText()) : "";
        String cDesc = clickableTarget != null ? safeNonEmptyString(clickableTarget.getContentDescription()) : "";
        if (hasBlockedPremiumOption(clickableTarget, cTxt != null ? cTxt : "", cDesc != null ? cDesc : "")) {
            return true;
        }
        AccessibilityNodeInfo parent = clickableTarget != null ? clickableTarget.getParent() : null;
        if (parent != null) {
            String pTxt = safeNonEmptyString(parent.getText());
            String pDesc = safeNonEmptyString(parent.getContentDescription());
            if (hasBlockedPremiumOption(parent, pTxt != null ? pTxt : "", pDesc != null ? pDesc : "")) {
                return true;
            }
        }
        return false;
    }
    private int extractUnambiguousResolutionValue(
            AccessibilityNodeInfo node,
            String text,
            String desc) {
        String[] sources = new String[]{text, desc};
        int found = -1;
        for (String src : sources) {
            if (src == null || src.isEmpty()) continue;
            Matcher m = Pattern.compile("\\b(144|240|360|480|720|1080|1440|2160|4320)p?\\b", Pattern.CASE_INSENSITIVE).matcher(src);
            while (m.find()) {
                try {
                    int val = Integer.parseInt(m.group(1));
                    if (val >= 144 && val <= 4320) {
                        if (found != -1 && found != val) {
                            return -1;
                        }
                        found = val;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return found;
    }

    private AccessibilityNodeInfo resolveClickableQualityTarget(AccessibilityNodeInfo node, int maxDepth) {
        if (node == null) return null;
        if (node.isClickable() && node.isEnabled() && node.isVisibleToUser()) {
            return node;
        }
        AccessibilityNodeInfo cur = node.getParent();
        int depth = 0;
        while (cur != null && depth < maxDepth) {
            if (cur.isClickable() && cur.isEnabled() && cur.isVisibleToUser()) {
                return cur;
            }
            cur = cur.getParent();
            depth++;
        }
        return null;
    }
    private AccessibilityNodeInfo findValidatedClickableTarget(
            List<AccessibilityNodeInfo> roots,
            String[] tokens,
            QualityContextKind contextKind) {
        if (roots == null || tokens == null || tokens.length == 0) return null;
        for (AccessibilityNodeInfo r : roots) {
            if (r == null) continue;
            Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(r);
            while (!q.isEmpty()) {
                AccessibilityNodeInfo n = q.removeFirst();
                if (hasVerifiedYouTubePackage(n) && n.isVisibleToUser()) {
                    String txt = safeNonEmptyString(n.getText());
                    String desc = safeNonEmptyString(n.getContentDescription());
                    String id = safeNonEmptyString(n.getViewIdResourceName());
                    String combined = ((txt != null ? txt : "") + " " + (desc != null ? desc : "") + " " + (id != null ? id : "")).toLowerCase();
                    for (String tok : tokens) {
                        if (tok != null && !tok.isEmpty() && combined.contains(tok.toLowerCase())) {
                            AccessibilityNodeInfo target = resolveClickableQualityTarget(n, 4);
                            if (target != null
                                    && hasVerifiedYouTubePackage(target)
                                    && target.isClickable()
                                    && target.isEnabled()
                                    && target.isVisibleToUser()
                                    && !isDisallowedPlayerOrTransportNode(target)
                                    && !hasBlockedPremiumContext(n, target)) {
                                if (contextKind == QualityContextKind.RESOLUTION_OPTION
                                        && extractUnambiguousResolutionValue(
                                                n,
                                                txt != null ? txt : "",
                                                desc != null ? desc : "") < 144) {
                                    continue;
                                }
                                return target;
                            }
                        }
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                }
            }
        }
        return null;
    }

    private List<AccessibilityNodeInfo> collectQualityRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null) {
            roots.add(activeRoot);
        }
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo win : windows) {
                    if (win == null) continue;
                    AccessibilityNodeInfo wRoot = win.getRoot();
                    if (wRoot != null && !roots.contains(wRoot)) {
                        roots.add(wRoot);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return roots;
    }


    private void scheduleQualityStep(final long token, final Runnable action, final long delayMs) {
        if (token != activeQualityToken || !qualityFlowRunning || action == null) {
            return;
        }
        if (activeQualityStepRunnable != null) {
            sbHandler.removeCallbacks(activeQualityStepRunnable);
            activeQualityStepRunnable = null;
        }
        final Runnable wrapped = new Runnable() {
            @Override
            public void run() {
                if (token != activeQualityToken || !qualityFlowRunning) {
                    return;
                }
                if (this == activeQualityStepRunnable) {
                    activeQualityStepRunnable = null;
                }
                action.run();
            }
        };
        activeQualityStepRunnable = wrapped;
        sbHandler.postDelayed(wrapped, Math.max(0L, delayMs));
    }

}
