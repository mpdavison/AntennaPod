package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import de.danoeh.antennapod.net.download.serviceinterface.AdDetectionManager;
import de.danoeh.antennapod.net.common.NostrClient;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.playback.service.R;
import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;
import de.danoeh.antennapod.storage.database.DBWriter;
import org.greenrobot.eventbus.EventBus;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class AdSkipController {
    private static final String TAG = "AdSkipController";
    private static final long MAX_NATURAL_ADVANCE_MS = 3000;
    private static final long RELOAD_INTERVAL_MS = 60_000L;
    private static final long RELOAD_INTERVAL_FAST_MS = 5_000L;
    private static final long START_THRESHOLD_MS = 5000L;
    private static final long END_THRESHOLD_MS = 3000L;

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
    private volatile long furthestPositionMs = 0;
    private final Set<Integer> skippedSegments = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> suppressedSegments = Collections.synchronizedSet(new HashSet<>());
    private long lastLoadAttemptMs = 0;
    private boolean adSkippingDisabledForFeed = false;
    private boolean adMartyrEnabled = false;
    private List<long[]> pendingAdSegments = new ArrayList<>();
    private boolean isPlayingAdMartyr = false;
    private int currentMartyrSegmentIndex = -1;
    private ToneGenerator toneGenerator;

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
        adSkippingDisabledForFeed = false;
        adMartyrEnabled = false;
        pendingAdSegments.clear();
        isPlayingAdMartyr = false;
        currentMartyrSegmentIndex = -1;
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    @VisibleForTesting
    void advanceReloadTimerForTest() {
        lastLoadAttemptMs = 0;
    }

    @VisibleForTesting
    void suppressAndSeek(int segmentIndex, long positionMs) {
        suppressedSegments.add(segmentIndex);
        seekCallback.seekTo(positionMs);
    }

    @VisibleForTesting
    void setAdMartyrEnabledForTest(boolean enabled) {
        adMartyrEnabled = enabled;
        if (enabled && adSegments != null) {
            pendingAdSegments.clear();
            for (long[] seg : adSegments) {
                pendingAdSegments.add(new long[]{seg[0], seg[1]});
            }
        } else {
            pendingAdSegments.clear();
        }
    }

    public void onMediaLoaded(FeedMedia media) {
        skippedSegments.clear();
        suppressedSegments.clear();
        lastLoadAttemptMs = 0;
        adSegments = null;
        processingComplete = false;
        currentMediaId = -1;
        furthestPositionMs = 0;
        adSkippingDisabledForFeed = false;
        adMartyrEnabled = false;
        pendingAdSegments.clear();
        isPlayingAdMartyr = false;
        currentMartyrSegmentIndex = -1;
        if (media != null && media.getDownloadUrl() != null) {
            currentMediaId = media.getId();
            FeedItem item = media.getItem();
            if (item != null) {
                Feed feed = item.getFeed();
                if (feed != null && feed.getPreferences() != null) {
                    adSkippingDisabledForFeed = !feed.getPreferences()
                            .isAdDetectionEnabled(AdDetectionPreferences.isEnabled());
                }
            }
            if (!adSkippingDisabledForFeed) {
                File tsFile = AdDetectionManager.adTimestampsFileFor(context, media);
                adTimestampsPath = tsFile.getAbsolutePath();
                tryLoadAdSegments();
                if (adSegments == null && media.isDownloaded()
                        && media.getLocalFileUrl() != null
                        && AdDetectionPreferences.isNostrEnabled()) {
                    tryNostrFallback(media);
                }
                adMartyrEnabled = AdDetectionPreferences.isAdMartyrEnabled();
                if (adMartyrEnabled && adSegments != null) {
                    pendingAdSegments.clear();
                    for (long[] seg : adSegments) {
                        pendingAdSegments.add(new long[]{seg[0], seg[1]});
                    }
                }
                if (adSegments != null && processingComplete && currentMediaId >= 0) {
                    long totalMs = 0;
                    for (long[] seg : adSegments) {
                        totalMs += seg[1] - seg[0];
                    }
                    DBWriter.setFeedMediaAdStats(currentMediaId, adSegments.size(), totalMs);
                }
            }
        } else {
            adTimestampsPath = null;
        }
    }

    public void checkPosition(long positionMs) {
        checkPosition(positionMs, -1);
    }

    public void checkPosition(long positionMs, long durationMs) {
        if (adSkippingDisabledForFeed) {
            return;
        }
        if (positionMs < 0) {
            lastObservedPositionMs = positionMs;
            return;
        }

        if (adMartyrEnabled && isPlayingAdMartyr) {
            lastObservedPositionMs = positionMs;
            handleMartyrPlayback(positionMs, durationMs);
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
            } else if (delta < 0 && adSegments != null) {
                Iterator<Integer> it = skippedSegments.iterator();
                while (it.hasNext()) {
                    int i = it.next();
                    if (i < adSegments.size() && adSegments.get(i)[0] > positionMs) {
                        it.remove();
                    }
                }
            }
            return;
        }
        furthestPositionMs = Math.max(furthestPositionMs, positionMs);

        if ((adSegments == null || !processingComplete) && adTimestampsPath != null) {
            long now = System.currentTimeMillis();
            long interval = (adSegments == null) ? RELOAD_INTERVAL_FAST_MS : RELOAD_INTERVAL_MS;
            if (now - lastLoadAttemptMs >= interval) {
                lastLoadAttemptMs = now;
                tryLoadAdSegments();
            }
        }

        if (adSegments == null) {
            return;
        }

        if (adMartyrEnabled && !pendingAdSegments.isEmpty()
                && durationMs > 0 && positionMs >= durationMs - END_THRESHOLD_MS) {
            isPlayingAdMartyr = true;
            currentMartyrSegmentIndex = 0;
            seekCallback.seekTo(pendingAdSegments.get(0)[0]);
            return;
        }

        for (int i = 0; i < adSegments.size(); i++) {
            long[] seg = adSegments.get(i);
            if (positionMs >= seg[0] && positionMs < seg[1]) {
                if (!skippedSegments.contains(i) && !suppressedSegments.contains(i)) {
                    skippedSegments.add(i);
                    final long skipTo = seg[1];
                    Log.d(TAG, "Ad skip: jumping from " + positionMs + " to " + skipTo);
                    playSkipSound();
                    long skippedFrom = positionMs;
                    final int segIdx = i;
                    long adDurationMs = skipTo - seg[0];
                    String durationStr = formatDuration(adDurationMs);
                    EventBus.getDefault().post(new MessageEvent(
                            context.getString(R.string.ad_skip_toast, durationStr),
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

    private void handleMartyrPlayback(long positionMs, long durationMs) {
        for (int i = currentMartyrSegmentIndex; i < pendingAdSegments.size(); i++) {
            long[] seg = pendingAdSegments.get(i);
            if (positionMs >= seg[0] && positionMs < seg[1]) {
                currentMartyrSegmentIndex = i;
                return;
            }
            if (positionMs < seg[0]) {
                currentMartyrSegmentIndex = i;
                seekCallback.seekTo(seg[0]);
                return;
            }
            currentMartyrSegmentIndex = i + 1;
        }
        isPlayingAdMartyr = false;
        if (durationMs > 0) {
            seekCallback.seekTo(durationMs);
        }
    }

    private void tryLoadAdSegments() {
        if (adTimestampsPath == null) {
            return;
        }
        File file = new File(adTimestampsPath);
        if (!file.exists()) {
            adSegments = null;
            return;
        }
        try {
            List<long[]> segs = AdDetectionManager.loadAdSegmentsFromFile(file);
            if (segs == null) {
                adSegments = null;
                return;
            }
            adSegments = segs;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            processingComplete = sb.toString().contains("\"complete\"");
            Log.i(TAG, "Loaded " + segs.size() + " ad segment(s) from " + adTimestampsPath);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load ad timestamps: " + e.getMessage());
        }
    }

    private void tryNostrFallback(FeedMedia media) {
        try {
            File localFile = new File(media.getLocalFileUrl());
            if (!localFile.exists()) {
                return;
            }
            String md5Hash = NostrClient.computeAudioMd5(localFile);
            List<long[]> nostrAds = NostrClient.queryAdTimestamps(md5Hash);
            if (nostrAds != null) {
                adSegments = nostrAds;
                processingComplete = true;
                Log.i(TAG, "Loaded " + nostrAds.size()
                        + " ad segment(s) from Nostr");
            }
        } catch (Exception e) {
            Log.w(TAG, "Nostr check failed during playback", e);
        }
    }

    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }

    private void playSkipSound() {
        String mode = AdDetectionPreferences.getSkipSoundMode();
        if (AdDetectionPreferences.SKIP_SOUND_DING.equals(mode)) {
            playDing();
        } else if (AdDetectionPreferences.SKIP_SOUND_CUSTOM.equals(mode)
                && AdDetectionPreferences.getSkipSoundCustomUri() != null) {
            playCustomSound();
        } else {
            playBeep();
        }
    }

    private void playDing() {
        try {
            MediaPlayer player = MediaPlayer.create(context, R.raw.ad_skip_ding);
            if (player != null) {
                player.setOnCompletionListener(MediaPlayer::release);
                player.start();
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "Could not play skip ding: " + e.getMessage());
        }
    }

    private void playCustomSound() {
        try {
            Uri uri = Uri.parse(AdDetectionPreferences.getSkipSoundCustomUri());
            MediaPlayer player = MediaPlayer.create(context, uri);
            if (player != null) {
                player.setOnCompletionListener(MediaPlayer::release);
                player.start();
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "Could not play custom skip sound: " + e.getMessage());
        }
    }

    private void playBeep() {
        try {
            if (toneGenerator == null) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            }
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        } catch (RuntimeException e) {
            Log.d(TAG, "Could not play skip beep: " + e.getMessage());
        }
    }
}
