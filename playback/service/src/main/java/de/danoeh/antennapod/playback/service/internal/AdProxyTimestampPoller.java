package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.util.Log;

import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.download.serviceinterface.AdDetectionManager;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;
import org.greenrobot.eventbus.EventBus;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

public class AdProxyTimestampPoller {
    private static final String TAG = "AdProxyTimestampPoller";
    private static final long POLL_INTERVAL_SEC = 15;
    private static final long MAX_DURATION_MS = 30 * 60 * 1000L;

    private static ScheduledExecutorService executor;
    private static ScheduledFuture<?> currentTask;
    private static long currentMediaId = -1;

    public static synchronized void start(Context context, FeedMedia media) {
        if (media == null || media.getDownloadUrl() == null) {
            return;
        }
        if (!AdDetectionPreferences.isProxyEnabled()) {
            return;
        }
        if (AdDetectionManager.isAdDetectionComplete(context, media)) {
            return;
        }
        if (currentMediaId == media.getId() && currentTask != null && !currentTask.isDone()) {
            return;
        }
        stop();
        currentMediaId = media.getId();
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor();
        }
        final Context appContext = context.getApplicationContext();
        final long mediaId = media.getId();
        final String originalUrl = media.getDownloadUrl();
        final File outFile = AdDetectionManager.adTimestampsFileFor(context, media);
        final long startedAt = System.currentTimeMillis();
        currentTask = executor.scheduleWithFixedDelay(() -> {
            try {
                if (System.currentTimeMillis() - startedAt > MAX_DURATION_MS) {
                    stop();
                    return;
                }
                if (pollOnce(appContext, originalUrl, outFile, mediaId)) {
                    stop();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Polling error: " + t.getMessage());
            }
        }, 0, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        if (currentTask != null) {
            currentTask.cancel(false);
            currentTask = null;
        }
        currentMediaId = -1;
    }

    private static boolean pollOnce(Context context, String originalUrl, File outFile, long mediaId)
            throws Exception {
        String proxyBase = AdDetectionPreferences.getProxyBaseUrl();
        if (proxyBase.isEmpty()) {
            return true;
        }
        String url = proxyBase + "/timestamps?u="
                + URLEncoder.encode(originalUrl, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.d(TAG, "Proxy /timestamps responded " + code);
                return false;
            }
            String text;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
                byte[] tmp = new byte[4096];
                int n;
                while ((n = in.read(tmp)) > 0) {
                    buf.write(tmp, 0, n);
                }
                text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject root = new JSONObject(text);
            String status = root.optString("status", "pending");
            if (!"ready".equals(status)) {
                return false;
            }
            JSONArray ads = root.optJSONArray("ads");
            if (ads == null) {
                ads = new JSONArray();
            }
            JSONObject out = new JSONObject();
            out.put("status", "complete");
            out.put("ads", ads);
            File parent = outFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "Wrote " + ads.length() + " ad segment(s) for media " + mediaId);
            FeedMedia loadedMedia = DBReader.getFeedMedia(mediaId);
            if (loadedMedia != null && loadedMedia.getItem() != null) {
                EventBus.getDefault().post(new FeedItemEvent(
                        Collections.singletonList(loadedMedia.getItem()), false));
            }
            return true;
        } finally {
            conn.disconnect();
        }
    }
}
