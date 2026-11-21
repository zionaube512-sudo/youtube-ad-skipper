package com.zionaube.adskipper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class SponsorBlockHelper {

    public static class Segment {
        public final double startSec;
        public final double endSec;
        public final String category;

        public Segment(double startSec, double endSec, String category) {
            this.startSec = startSec;
            this.endSec = endSec;
            this.category = category;
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, List<Segment>> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\"");

    public static List<Segment> getSegmentsForTitle(final String titleKey) {
        if (titleKey == null || titleKey.trim().length() < 4) return Collections.emptyList();
        final String key = titleKey.trim();
        List<Segment> cached = CACHE.get(key);
        if (cached != null) return cached;

        if (IN_FLIGHT.putIfAbsent(key, Boolean.TRUE) == null) {
            EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String videoId = resolveVideoId(key);
                        if (videoId != null) {
                            List<Segment> segs = fetchSponsorSegments(videoId);
                            CACHE.put(key, segs);
                        } else {
                            CACHE.put(key, Collections.<Segment>emptyList());
                        }
                    } catch (Exception e) {
                        CACHE.put(key, Collections.<Segment>emptyList());
                    } finally {
                        IN_FLIGHT.remove(key);
                    }
                }
            });
        }
        return Collections.emptyList();
    }

    private static String resolveVideoId(String query) {
        HttpURLConnection conn = null;
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            URL url = new URL("https://www.youtube.com/results?search_query=" + encoded);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    Matcher m = VIDEO_ID_PATTERN.matcher(line);
                    if (m.find()) {
                        return m.group(1);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static List<Segment> fetchSponsorSegments(String videoId) {
        HttpURLConnection conn = null;
        try {
            String cats = URLEncoder.encode("[\"sponsor\",\"selfpromo\"]", "UTF-8");
            String urlStr = "https://sponsor.ajay.app/api/skipSegments?videoID="
                    + URLEncoder.encode(videoId, "UTF-8")
                    + "&categories=" + cats;
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "YouTubeAdSkipper-ZionAube/1.2");
            if (conn.getResponseCode() != 200) {
                return Collections.emptyList();
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            List<Segment> list = new ArrayList<>();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                JSONArray seg = obj.getJSONArray("segment");
                double start = seg.getDouble(0);
                double end = seg.getDouble(1);
                String cat = obj.optString("category", "sponsor");
                if (end - start >= 2.0) {
                    list.add(new Segment(start, end, cat));
                }
            }
            return list;
        } catch (Exception ignored) {
            return Collections.emptyList();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
