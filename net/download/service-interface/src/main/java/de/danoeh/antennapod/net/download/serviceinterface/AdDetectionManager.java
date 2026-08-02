package de.danoeh.antennapod.net.download.serviceinterface;

import android.content.Context;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.i18n.R;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        Integer progress = progressMap.get(mediaId);
        return progress != null ? progress : -1;
    }

    public static long[] getAdSummary(Context context, FeedMedia media) {
        List<long[]> segments = getAdSegments(context, media);
        if (segments == null) {
            return null;
        }
        long totalMs = 0;
        for (long[] seg : segments) {
            totalMs += seg[1] - seg[0];
        }
        return new long[]{segments.size(), totalMs};
    }

    public static List<long[]> getAdSegments(Context context, FeedMedia media) {
        String content = readAdTimestampsContent(context, media);
        if (content == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(content);
            if (!"complete".equals(root.optString("status"))) {
                return null;
            }
            JSONArray ads = root.optJSONArray("ads");
            if (ads == null) {
                return Collections.emptyList();
            }
            List<long[]> segments = new ArrayList<>();
            for (int i = 0; i < ads.length(); i++) {
                JSONObject ad = ads.getJSONObject(i);
                segments.add(new long[]{ad.getLong("startMs"), ad.getLong("endMs")});
            }
            return segments;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<long[]> loadAdSegments(Context context, FeedMedia media) {
        return loadAdSegmentsFromFile(adTimestampsFileFor(context, media));
    }

    public static List<long[]> loadAdSegmentsFromFile(File file) {
        String content = readFileContent(file);
        if (content == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(content);
            JSONArray ads = root.optJSONArray("ads");
            if (ads == null) {
                return Collections.emptyList();
            }
            List<long[]> segments = new ArrayList<>();
            for (int i = 0; i < ads.length(); i++) {
                JSONObject ad = ads.getJSONObject(i);
                segments.add(new long[]{ad.getLong("startMs"), ad.getLong("endMs")});
            }
            return segments;
        } catch (Exception e) {
            return null;
        }
    }

    public static File adTimestampsFileFor(Context context, FeedMedia media) {
        return new File(UserPreferences.getDataFolder("adtimestamps"), media.getId() + ".json");
    }

    public static boolean isAdDetectionComplete(Context context, FeedMedia media) {
        String content = readAdTimestampsContent(context, media);
        return content != null && content.contains("\"complete\"");
    }

    private static String readAdTimestampsContent(Context context, FeedMedia media) {
        return readFileContent(adTimestampsFileFor(context, media));
    }

    private static String readFileContent(File file) {
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file);
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = fis.read(buf)) >= 0) {
                baos.write(buf, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return null;
        }
    }

    public static String buildAdSummaryHtml(Context context, FeedMedia media) {
        int progress = getProgress(media.getId());
        if (progress > 0 && progress < 100) {
            return context.getString(R.string.ad_detection_summary_processing);
        }
        List<long[]> segments = getAdSegments(context, media);
        if (segments == null) {
            return null;
        }
        if (segments.isEmpty()) {
            return context.getString(R.string.ad_detection_summary_none);
        }
        long totalMs = 0;
        for (long[] seg : segments) {
            totalMs += seg[1] - seg[0];
        }
        String totalStr = formatDurationForSummary(totalMs);
        StringBuilder sb = new StringBuilder(
                context.getString(R.string.ad_detection_summary_ads,
                        segments.size(), totalStr));
        for (long[] seg : segments) {
            sb.append("<br>&emsp;");
            sb.append(formatDurationForSummary(seg[0]));
            sb.append(" – ");
            sb.append(String.format(java.util.Locale.US,
                    "<a class=\"timecode\" href=\"antennapod://timecode/%d\">%s</a>",
                    seg[1], formatDurationForSummary(seg[1])));
        }
        return sb.toString();
    }

    public static String appendAdSummaryToWebviewData(Context context, FeedMedia media, String data) {
        String summaryHtml = buildAdSummaryHtml(context, media);
        if (summaryHtml == null) {
            return data;
        }
        return data.replace("</body>",
                "<br><br><div id='adSummary'>" + summaryHtml + "</div></body>");
    }

    private static String formatDurationForSummary(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes % 60, seconds % 60);
        }
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds % 60);
    }
}
