package de.danoeh.antennapod.net.download.serviceinterface;

import android.content.Context;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public abstract class AdDetectionManager {
    private static AdDetectionManager instance;
    private static final ConcurrentHashMap<Long, Integer> progressMap = new ConcurrentHashMap<>();

    public static AdDetectionManager getInstance() {
        return instance;
    }

    public static void setInstance(AdDetectionManager instance) {
        AdDetectionManager.instance = instance;
    }

    public abstract void enqueueAdDetection(Context context, FeedItem item);

    public static void setProgress(long mediaId, int progress) {
        if (progress >= 100) {
            progressMap.remove(mediaId);
        } else {
            progressMap.put(mediaId, progress);
        }
    }

    public static int getProgress(long mediaId) {
        return progressMap.getOrDefault(mediaId, -1);
    }

    public static long[] getAdSummary(Context context, FeedMedia media) {
        File file = adTimestampsFileFor(context, media);
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 65536)];
            int read = fis.read(buf);
            String content = new String(buf, 0, Math.max(read, 0), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);
            if (!"complete".equals(root.optString("status"))) {
                return null;
            }
            JSONArray ads = root.optJSONArray("ads");
            if (ads == null) {
                return new long[]{0, 0};
            }
            long count = ads.length();
            long totalMs = 0;
            for (int i = 0; i < ads.length(); i++) {
                JSONObject ad = ads.getJSONObject(i);
                totalMs += ad.getLong("endMs") - ad.getLong("startMs");
            }
            return new long[]{count, totalMs};
        } catch (Exception e) {
            return null;
        }
    }

    public static File adTimestampsFileFor(Context context, FeedMedia media) {
        File dir = new File(context.getCacheDir(), "adtimestamps");
        dir.mkdirs();
        return new File(dir, media.getId() + ".adtimestamps");
    }

    public static boolean isAdDetectionComplete(Context context, FeedMedia media) {
        File file = adTimestampsFileFor(context, media);
        if (!file.exists()) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[200];
            int read = fis.read(buf, 0, buf.length);
            String head = new String(buf, 0, Math.max(read, 0), StandardCharsets.UTF_8);
            return head.contains("\"complete\"");
        } catch (Exception e) {
            return false;
        }
    }
}
