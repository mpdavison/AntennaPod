package de.danoeh.antennapod.net.download.service.episode;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class AdDetectionWorker extends Worker {
    private static final String TAG = "AdDetection";
    static final String KEY_FEED_MEDIA_ID = "feedMediaId";
    private static final String AD_SERVICE_URL = "https://adskip.1681248.com/adskip/";
    private static final int POLL_INTERVAL_SECONDS = 60;
    private static final int MAX_POLLS = 120;

    public AdDetectionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context, long feedMediaId) {
        Data inputData = new Data.Builder()
                .putLong(KEY_FEED_MEDIA_ID, feedMediaId)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                "adskip_" + feedMediaId,
                ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(AdDetectionWorker.class)
                        .setInputData(inputData)
                        .build());
    }

    public static File adTimestampsFileFor(Context context, FeedMedia media) {
        if (media.getLocalFileUrl() != null) {
            return new File(media.getLocalFileUrl() + ".adtimestamps");
        }
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

    @NonNull
    @Override
    public Result doWork() {
        long feedMediaId = getInputData().getLong(KEY_FEED_MEDIA_ID, -1);
        Log.i(TAG, "doWork() started for feedMediaId=" + feedMediaId);
        if (feedMediaId < 0) {
            Log.w(TAG, "Invalid feedMediaId, aborting");
            return Result.failure();
        }
        FeedMedia media = DBReader.getFeedMedia(feedMediaId);
        if (media == null) {
            Log.w(TAG, "Media not found for id " + feedMediaId);
            return Result.success();
        }
        File outFile = adTimestampsFileFor(getApplicationContext(), media);
        String episodeUrl = media.getDownloadUrl();
        if (episodeUrl == null || episodeUrl.isEmpty()) {
            Log.w(TAG, "No episode URL for media " + feedMediaId);
            return Result.success();
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        pollWithPost(client, episodeUrl, outFile);
        return Result.success();
    }

    private void pollWithPost(OkHttpClient client, String episodeUrl, File outFile) {
        long feedMediaId = getInputData().getLong(KEY_FEED_MEDIA_ID, -1);
        FeedMedia media = DBReader.getFeedMedia(feedMediaId);
        String podcastName = null;
        String podcastUrl = null;
        String episodeTitle = null;
        if (media != null && media.getItem() != null) {
            episodeTitle = media.getItem().getTitle();
            if (media.getItem().getFeed() != null) {
                podcastName = media.getItem().getFeed().getTitle();
                podcastUrl = media.getItem().getFeed().getDownloadUrl();
            }
        }
        for (int attempt = 1; attempt <= MAX_POLLS; attempt++) {
            Log.i(TAG, "Posting for ad timestamps, attempt " + attempt + "/" + MAX_POLLS);
            try {
                JSONObject body = new JSONObject();
                body.put("url", episodeUrl);
                if (podcastName != null) {
                    body.put("podcast_name", podcastName);
                }
                if (podcastUrl != null) {
                    body.put("podcast_url", podcastUrl);
                }
                if (episodeTitle != null) {
                    body.put("episode_title", episodeTitle);
                }
                RequestBody requestBody = RequestBody.create(
                        body.toString(), MediaType.get("application/json"));
                Request request = new Request.Builder()
                        .url(AD_SERVICE_URL)
                        .post(requestBody)
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (response.code() == 200 && response.body() != null) {
                        String json = response.body().string();
                        writeAdTimestamps(outFile, json);
                        Log.i(TAG, "Ad timestamps written (attempt " + attempt + "): " + outFile.getPath());
                        JSONObject obj = new JSONObject(json);
                        if ("complete".equals(obj.optString("status"))) {
                            Log.i(TAG, "Processing complete, stopping poll");
                            return;
                        }
                        Log.i(TAG, "Processing still in progress, will poll again");
                    } else {
                        Log.i(TAG, "Ad service returned HTTP " + response.code());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Poll attempt " + attempt + " failed: " + e.getMessage());
            }
            if (attempt < MAX_POLLS) {
                try {
                    Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        Log.w(TAG, "Gave up polling for ad timestamps after " + MAX_POLLS + " attempts");
    }

    private void writeAdTimestamps(File file, String json) throws IOException {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete existing ad timestamps file");
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }
}
