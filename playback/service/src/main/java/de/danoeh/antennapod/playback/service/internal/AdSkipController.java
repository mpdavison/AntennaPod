package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Log;
import de.danoeh.antennapod.net.download.serviceinterface.AdDetectionManager;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.R;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AdSkipController {
    private static final String TAG = "AdSkipController";
    private static final long MAX_NATURAL_ADVANCE_MS = 3000;
    private static final long RELOAD_INTERVAL_MS = 60_000L;
    private static final long START_THRESHOLD_MS = 5000L;

    public interface SeekCallback {
        void seekTo(long positionMs);
    }

    private final Context context;
    private final SeekCallback seekCallback;
    private long lastObservedPositionMs = -1;
    private String adTimestampsPath = null;
    private List<long[]> adSegments = null;
    private boolean processingComplete = false;
    private long currentMediaId = -1;
    private long furthestPositionMs = 0;
    private final Set<Integer> skippedSegments = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> suppressedSegments = Collections.synchronizedSet(new HashSet<>());
    private long lastLoadAttemptMs = 0;

    public AdSkipController(Context context, SeekCallback seekCallback) {
        this.context = context;
        this.seekCallback = seekCallback;
    }

    public void onReset() {
        lastObservedPositionMs = -1;
        adTimestampsPath = null;
        adSegments = null;
        processingComplete = false;
        currentMediaId = -1;
        furthestPositionMs = 0;
        skippedSegments.clear();
        suppressedSegments.clear();
        lastLoadAttemptMs = 0;
    }

    public void onMediaLoaded(FeedMedia media) {
        skippedSegments.clear();
        suppressedSegments.clear();
        lastLoadAttemptMs = 0;
        adSegments = null;
        processingComplete = false;
        currentMediaId = -1;
        furthestPositionMs = 0;
        if (media != null && media.getDownloadUrl() != null) {
            currentMediaId = media.getId();
            File tsFile = AdDetectionManager.adTimestampsFileFor(context, media);
            adTimestampsPath = tsFile.getAbsolutePath();
            tryLoadAdSegments();
            if (!processingComplete && media.getItem() != null) {
                AdDetectionManager.getInstance().enqueueAdDetection(context, media.getItem());
            }
        } else {
            adTimestampsPath = null;
        }
    }

    public void checkPosition(long positionMs) {
        if (positionMs < 0) {
            lastObservedPositionMs = positionMs;
            return;
        }
        long prevPositionMs = lastObservedPositionMs;
        long delta = prevPositionMs < 0 ? 0 : positionMs - prevPositionMs;
        lastObservedPositionMs = positionMs;

        if (delta > MAX_NATURAL_ADVANCE_MS || delta < 0) {
            if (delta > 0) {
                furthestPositionMs = Math.max(furthestPositionMs, positionMs);
            }
            if (delta < 0 && positionMs < START_THRESHOLD_MS
                    && furthestPositionMs > START_THRESHOLD_MS
                    && processingComplete && currentMediaId >= 0) {
                processingComplete = false;
                furthestPositionMs = 0;
                skippedSegments.clear();
                suppressedSegments.clear();
            }
            return;
        }
        furthestPositionMs = Math.max(furthestPositionMs, positionMs);

        if ((adSegments == null || !processingComplete) && adTimestampsPath != null) {
            long now = System.currentTimeMillis();
            if (now - lastLoadAttemptMs >= RELOAD_INTERVAL_MS) {
                lastLoadAttemptMs = now;
                tryLoadAdSegments();
            }
        }

        if (adSegments == null) {
            return;
        }

        for (int i = 0; i < adSegments.size(); i++) {
            long[] seg = adSegments.get(i);
            if (positionMs >= seg[0] && positionMs < seg[1]) {
                if (!skippedSegments.contains(i) && !suppressedSegments.contains(i)) {
                    skippedSegments.add(i);
                    long skippedFrom = positionMs;
                    final int segIdx = i;
                    final long skipTo = seg[1];
                    Log.d(TAG, "Ad skip: jumping from " + positionMs + " to " + skipTo);
                    playBeep();
                    EventBus.getDefault().post(new MessageEvent(
                            context.getString(R.string.ad_skip_toast),
                            ctx -> {
                                suppressedSegments.add(segIdx);
                                seekCallback.seekTo(skippedFrom);
                            },
                            context.getString(R.string.undo)));
                    seekCallback.seekTo(skipTo);
                }
                break;
            }
        }
    }

    private void tryLoadAdSegments() {
        if (adTimestampsPath == null) {
            return;
        }
        File file = new File(adTimestampsPath);
        if (!file.exists()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            JSONObject root = new JSONObject(sb.toString());
            processingComplete = "complete".equals(root.optString("status"));
            JSONArray arr = root.getJSONArray("ads");
            List<long[]> segs = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                segs.add(new long[]{o.getLong("startMs"), o.getLong("endMs")});
            }
            adSegments = segs;
            Log.i(TAG, "Loaded " + segs.size() + " ad segment(s) from " + adTimestampsPath);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load ad timestamps: " + e.getMessage());
        }
    }

    private void playBeep() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        } catch (RuntimeException e) {
            Log.d(TAG, "Could not play skip beep: " + e.getMessage());
        }
    }
}
