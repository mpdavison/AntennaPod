package de.danoeh.antennapod.net.download.service.episode;

import android.app.Notification;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import androidx.annotation.NonNull;
import android.content.pm.ServiceInfo;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import de.danoeh.antennapod.net.download.service.R;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import de.danoeh.antennapod.net.download.serviceinterface.AdDetectionManager;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AdDetectionWorker extends Worker {
    private static final String TAG = "AdDetection";
    static final String KEY_FEED_MEDIA_ID = "feedMediaId";
    private static final long MAX_CHUNK_BYTES = 24L * 1024 * 1024;
    private static final long GAP_FILL_MS = 30_000L;
    private static final long MAX_CHUNK_DURATION_US = 5L * 60L * 1_000_000L;

    private static final String DEFAULT_CLASSIFICATION_PROMPT =
            AdDetectionPreferences.DEFAULT_CLASSIFICATION_PROMPT;

    private static class AudioChunk {
        final File file;
        final double offsetSeconds;
        final boolean isTemp;

        AudioChunk(File file, double offsetSeconds, boolean isTemp) {
            this.file = file;
            this.offsetSeconds = offsetSeconds;
            this.isTemp = isTemp;
        }
    }

    private static class Segment {
        final long startMs;
        final long endMs;
        final String text;

        Segment(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    public AdDetectionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context, long feedMediaId) {
        if (!AdDetectionPreferences.isEnabled()) {
            return;
        }
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

    @NonNull
    @Override
    public ListenableFuture<ForegroundInfo> getForegroundInfoAsync() {
        return Futures.immediateFuture(new ForegroundInfo(R.id.notification_ad_detection, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC));
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(getApplicationContext(), NotificationUtils.CHANNEL_ID_DOWNLOADING)
                .setContentTitle(getApplicationContext().getString(R.string.ad_detection_notification_title))
                .setSmallIcon(R.drawable.ic_notification_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!AdDetectionPreferences.isEnabled()) {
            return Result.success();
        }
        setForegroundAsync(new ForegroundInfo(R.id.notification_ad_detection, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC));
        long feedMediaId = getInputData().getLong(KEY_FEED_MEDIA_ID, -1);
        if (feedMediaId < 0) {
            return Result.failure();
        }
        FeedMedia media = DBReader.getFeedMedia(feedMediaId);
        if (media == null) {
            return Result.success();
        }
        if (AdDetectionManager.isAdDetectionComplete(getApplicationContext(), media)) {
            return Result.success();
        }
        AdDetectionManager.adTimestampsFileFor(getApplicationContext(), media).delete();
        String transcriptionApiKey = AdDetectionPreferences.getTranscriptionApiKey();
        String chatApiKey = AdDetectionPreferences.getChatApiKey();
        if (transcriptionApiKey.isEmpty() || chatApiKey.isEmpty()) {
            Log.w(TAG, "API keys not configured, skipping ad detection");
            return Result.success();
        }
        String episodeUrl = media.getDownloadUrl();
        if (episodeUrl == null || episodeUrl.isEmpty()) {
            return Result.success();
        }
        String episodeTitle = media.getItem() != null ? media.getItem().getTitle() : episodeUrl;
        Log.i(TAG, "Starting ad detection for: " + episodeTitle);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();

        File audioFile = null;
        boolean isDownloaded = false;
        if (media.isDownloaded() && media.getLocalFileUrl() != null) {
            File local = new File(media.getLocalFileUrl());
            if (local.exists()) {
                audioFile = local;
            }
        }
        if (audioFile == null) {
            audioFile = new File(getApplicationContext().getCacheDir(), feedMediaId + ".adskip_audio");
            try {
                downloadFile(client, episodeUrl, audioFile);
                isDownloaded = true;
            } catch (IOException e) {
                Log.w(TAG, "Failed to download audio: " + e.getMessage());
                audioFile.delete();
                return Result.success();
            }
        }

        List<AudioChunk> chunks = null;
        try {
            chunks = splitAudio(audioFile);
            Log.i(TAG, "Audio split into " + chunks.size() + " chunk(s)");

            List<Segment> allSegments = new ArrayList<>();
            String transcriptionBaseUrl = AdDetectionPreferences.getTranscriptionBaseUrl();
            String transcriptionModel = AdDetectionPreferences.getTranscriptionModel();
            int transcriptionSuccesses = 0;
            for (int i = 0; i < chunks.size(); i++) {
                Log.i(TAG, "Transcribing chunk " + (i + 1) + "/" + chunks.size());
                try {
                    List<Segment> segs = transcribeChunk(chunks.get(i), client,
                            transcriptionApiKey, transcriptionBaseUrl, transcriptionModel);
                    allSegments.addAll(segs);
                    transcriptionSuccesses++;
                } catch (Exception e) {
                    Log.w(TAG, "Transcription of chunk " + i + " failed: " + e.getMessage());
                }
            }

            if (transcriptionSuccesses == 0 && !chunks.isEmpty()) {
                Log.w(TAG, "All transcription chunks failed, will retry later");
                return Result.success();
            }

            Log.i(TAG, "Classifying " + allSegments.size() + " transcript segments");
            String chatBaseUrl = AdDetectionPreferences.getChatBaseUrl();
            String chatModel = AdDetectionPreferences.getChatModel();
            String chatPrompt = AdDetectionPreferences.getChatPrompt();
            List<long[]> ads = classifyAds(allSegments, client,
                    chatApiKey, chatBaseUrl, chatModel, chatPrompt);
            ads = mergeConsecutiveAds(ads);

            File outFile = AdDetectionManager.adTimestampsFileFor(getApplicationContext(), media);
            writeAdTimestamps(outFile, ads);
            Log.i(TAG, "Ad detection complete: " + ads.size() + " ad segment(s) for " + episodeTitle);
        } catch (Exception e) {
            Log.e(TAG, "Ad detection failed: " + e.getMessage());
        } finally {
            if (chunks != null) {
                for (AudioChunk chunk : chunks) {
                    if (chunk.isTemp) {
                        chunk.file.delete();
                    }
                }
            }
            if (isDownloaded && audioFile != null) {
                audioFile.delete();
            }
        }
        return Result.success();
    }

    private void downloadFile(OkHttpClient client, String url, File dest) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Download failed: HTTP " + response.code());
            }
            try (FileOutputStream fos = new FileOutputStream(dest);
                    InputStream is = response.body().byteStream()) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = is.read(buf)) >= 0) {
                    fos.write(buf, 0, read);
                }
            }
        }
    }

    private List<AudioChunk> splitAudio(File audioFile) {
        MediaExtractor probe = new MediaExtractor();
        try {
            probe.setDataSource(audioFile.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat audioFormat = null;
            String mime = null;
            for (int i = 0; i < probe.getTrackCount(); i++) {
                MediaFormat fmt = probe.getTrackFormat(i);
                String m = fmt.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    audioTrack = i;
                    audioFormat = fmt;
                    mime = m;
                    break;
                }
            }
            if (audioTrack < 0 || audioFormat == null) {
                return Collections.singletonList(new AudioChunk(audioFile, 0, false));
            }
            long durationUs = audioFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? audioFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            if (durationUs <= 0) {
                return Collections.singletonList(new AudioChunk(audioFile, 0, false));
            }
            if ("audio/mp4a-latm".equals(mime)) {
                return splitWithMuxer(audioFile, audioFormat, audioTrack, durationUs);
            } else {
                return splitByBytes(audioFile, durationUs);
            }
        } catch (IOException e) {
            Log.w(TAG, "Audio probe failed: " + e.getMessage());
            return Collections.singletonList(new AudioChunk(audioFile, 0, false));
        } finally {
            probe.release();
        }
    }

    private List<AudioChunk> splitWithMuxer(File audioFile, MediaFormat audioFormat,
            int audioTrackIndex, long durationUs) {
        long fileSizeBytes = audioFile.length();
        long chunkDurationUs = (long) ((double) durationUs * MAX_CHUNK_BYTES / fileSizeBytes);
        chunkDurationUs = Math.min(chunkDurationUs, MAX_CHUNK_DURATION_US);
        long mediaId = getInputData().getLong(KEY_FEED_MEDIA_ID, 0);
        List<AudioChunk> chunks = new ArrayList<>();
        long currentStartUs = 0;
        int chunkIdx = 0;

        while (currentStartUs < durationUs) {
            long chunkEndUs = Math.min(currentStartUs + chunkDurationUs, durationUs);
            File chunkFile = new File(getApplicationContext().getCacheDir(),
                    "adskip_" + mediaId + "_" + chunkIdx + ".m4a");
            MediaExtractor extractor = new MediaExtractor();
            MediaMuxer muxer = null;
            boolean muxerStarted = false;
            try {
                extractor.setDataSource(audioFile.getAbsolutePath());
                extractor.selectTrack(audioTrackIndex);
                extractor.seekTo(currentStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                muxer = new MediaMuxer(chunkFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int muxerTrack = muxer.addTrack(audioFormat);
                muxer.start();
                muxerStarted = true;
                ByteBuffer buffer = ByteBuffer.allocate(512 * 1024);
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                while (true) {
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        break;
                    }
                    long sampleTimeUs = extractor.getSampleTime();
                    if (sampleTimeUs > chunkEndUs) {
                        break;
                    }
                    info.offset = 0;
                    info.size = sampleSize;
                    info.presentationTimeUs = sampleTimeUs;
                    info.flags = extractor.getSampleFlags();
                    muxer.writeSampleData(muxerTrack, buffer, info);
                    extractor.advance();
                }
                muxer.stop();
                muxerStarted = false;
                chunks.add(new AudioChunk(chunkFile, currentStartUs / 1_000_000.0, true));
            } catch (Exception e) {
                Log.w(TAG, "Muxer failed for chunk " + chunkIdx + ": " + e.getMessage());
                chunkFile.delete();
                for (AudioChunk c : chunks) {
                    if (c.isTemp) {
                        c.file.delete();
                    }
                }
                return Collections.singletonList(new AudioChunk(audioFile, 0, false));
            } finally {
                extractor.release();
                if (muxer != null) {
                    if (muxerStarted) {
                        try { muxer.stop(); } catch (Exception ignored) { }
                    }
                    muxer.release();
                }
            }
            currentStartUs = chunkEndUs;
            chunkIdx++;
        }
        return chunks;
    }

    private List<AudioChunk> splitByBytes(File audioFile, long durationUs) throws IOException {
        long fileSizeBytes = audioFile.length();
        long mediaId = getInputData().getLong(KEY_FEED_MEDIA_ID, 0);
        String name = audioFile.getName();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".mp3";
        List<AudioChunk> chunks = new ArrayList<>();
        int chunkBytes = (int) Math.min(MAX_CHUNK_BYTES,
                (long) ((double) MAX_CHUNK_DURATION_US / durationUs * fileSizeBytes));
        byte[] buf = new byte[chunkBytes];
        long bytesRead = 0;
        int chunkIdx = 0;
        try (FileInputStream fis = new FileInputStream(audioFile)) {
            while (true) {
                int total = 0;
                while (total < buf.length) {
                    int read = fis.read(buf, total, buf.length - total);
                    if (read < 0) {
                        break;
                    }
                    total += read;
                }
                if (total <= 0) {
                    break;
                }
                int start = 0;
                if (chunkIdx > 0) {
                    for (int i = 0; i < total - 1; i++) {
                        if ((buf[i] & 0xFF) == 0xFF && (buf[i + 1] & 0xE0) == 0xE0) {
                            start = i;
                            break;
                        }
                    }
                }
                double offsetSeconds = (double) bytesRead / fileSizeBytes * (durationUs / 1_000_000.0);
                File chunkFile = new File(getApplicationContext().getCacheDir(),
                        "adskip_" + mediaId + "_" + chunkIdx + ext);
                try (FileOutputStream fos = new FileOutputStream(chunkFile)) {
                    fos.write(buf, start, total - start);
                }
                chunks.add(new AudioChunk(chunkFile, offsetSeconds, true));
                bytesRead += total;
                chunkIdx++;
            }
        }
        return chunks.isEmpty()
                ? Collections.singletonList(new AudioChunk(audioFile, 0, false))
                : chunks;
    }

    private List<Segment> transcribeChunk(AudioChunk chunk, OkHttpClient client,
            String apiKey, String baseUrl, String model) throws IOException, JSONException {
        String fileName = chunk.file.getName();
        String mimeType = fileName.endsWith(".m4a") ? "audio/mp4" : "audio/mpeg";
        RequestBody fileBody = RequestBody.create(chunk.file, MediaType.get(mimeType));
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .build();
        Request request = new Request.Builder()
                .url(baseUrl + "/audio/transcriptions")
                .header("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Transcription API error: HTTP " + response.code() + " " + body);
            }
            return parseSegments(body, chunk.offsetSeconds);
        }
    }

    private List<Segment> parseSegments(String json, double offsetSeconds) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray segments = root.optJSONArray("segments");
        if (segments == null) {
            return Collections.emptyList();
        }
        List<Segment> result = new ArrayList<>();
        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.getJSONObject(i);
            long startMs = (long) ((seg.getDouble("start") + offsetSeconds) * 1000);
            long endMs = (long) ((seg.getDouble("end") + offsetSeconds) * 1000);
            String text = seg.optString("text", "").trim();
            result.add(new Segment(startMs, endMs, text));
        }
        return result;
    }

    private List<long[]> classifyAds(List<Segment> segments, OkHttpClient client,
            String apiKey, String baseUrl, String model, String customPrompt)
            throws IOException, JSONException {
        if (segments.isEmpty()) {
            return Collections.emptyList();
        }
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            lines.append("[").append(i).append("] ")
                    .append(s.startMs).append("-").append(s.endMs).append(": ")
                    .append(s.text).append("\n");
        }
        String basePrompt = (customPrompt == null || customPrompt.isEmpty())
                ? DEFAULT_CLASSIFICATION_PROMPT : customPrompt;

        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", basePrompt);

        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", "Transcript segments (format: [index] startMs-endMs: text):\n"
                + lines
                + "\nRespond with ONLY the JSON object. No explanation, no markdown, no other text.");

        JSONArray messages = new JSONArray();
        messages.put(systemMessage);
        messages.put(userMessage);
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Chat API error: HTTP " + response.code() + " " + responseBody);
            }
            return parseAdSegments(responseBody);
        }
    }

    private List<long[]> parseAdSegments(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        String content = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        int jsonStart = content.indexOf('{');
        int jsonEnd = content.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            return Collections.emptyList();
        }
        content = content.substring(jsonStart, jsonEnd + 1);
        JSONArray adsArray = new JSONObject(content).optJSONArray("ads");
        if (adsArray == null) {
            return Collections.emptyList();
        }
        List<long[]> ads = new ArrayList<>();
        for (int i = 0; i < adsArray.length(); i++) {
            JSONObject ad = adsArray.getJSONObject(i);
            long start = ad.has("startMs") ? ad.getLong("startMs") : ad.getLong("start");
            long end = ad.has("endMs") ? ad.getLong("endMs") : ad.getLong("end");
            ads.add(new long[]{start, end});
        }
        return ads;
    }

    private List<long[]> mergeConsecutiveAds(List<long[]> ads) {
        if (ads.isEmpty()) {
            return ads;
        }
        List<long[]> sorted = new ArrayList<>(ads);
        sorted.sort((a, b) -> Long.compare(a[0], b[0]));
        List<long[]> merged = new ArrayList<>();
        merged.add(sorted.get(0).clone());
        for (int i = 1; i < sorted.size(); i++) {
            long[] current = sorted.get(i);
            long[] last = merged.get(merged.size() - 1);
            if (current[0] - last[1] <= GAP_FILL_MS) {
                last[1] = Math.max(last[1], current[1]);
            } else {
                merged.add(current.clone());
            }
        }
        return merged;
    }

    private void writeAdTimestamps(File outFile, List<long[]> ads) throws IOException, JSONException {
        JSONArray adsArray = new JSONArray();
        for (long[] ad : ads) {
            JSONObject adObj = new JSONObject();
            adObj.put("startMs", ad[0]);
            adObj.put("endMs", ad[1]);
            adsArray.put(adObj);
        }
        JSONObject root = new JSONObject();
        root.put("status", "complete");
        root.put("ads", adsArray);

        File tmpFile = new File(outFile.getParent(), outFile.getName() + ".tmp");
        tmpFile.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmpFile.renameTo(outFile)) {
            try (FileInputStream fis = new FileInputStream(tmpFile);
                    FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = fis.read(buf)) >= 0) {
                    fos.write(buf, 0, read);
                }
            } finally {
                tmpFile.delete();
            }
        }
    }
}
