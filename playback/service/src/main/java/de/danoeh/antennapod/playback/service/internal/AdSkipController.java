package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Log;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.playback.service.R;
import org.greenrobot.eventbus.EventBus;

public class AdSkipController {
    private static final String TAG = "AdSkipController";
    private static final long AD_INTERVAL_MS = 5 * 60 * 1000;
    private static final long SKIP_AMOUNT_MS = 60 * 1000;
    private static final long MAX_NATURAL_ADVANCE_MS = 3000;

    public interface SeekCallback {
        void seekTo(long positionMs);
    }

    private final Context context;
    private final SeekCallback seekCallback;
    private long lastSkipPositionMs = -1;
    private long lastObservedPositionMs = -1;
    private long suppressBucketMs = -1;

    public AdSkipController(Context context, SeekCallback seekCallback) {
        this.context = context;
        this.seekCallback = seekCallback;
    }

    public void onReset() {
        lastSkipPositionMs = -1;
        lastObservedPositionMs = -1;
        suppressBucketMs = -1;
    }

    public void checkPosition(long positionMs) {
        if (positionMs <= 0) {
            lastObservedPositionMs = positionMs;
            return;
        }
        long delta = lastObservedPositionMs < 0 ? 0 : positionMs - lastObservedPositionMs;
        lastObservedPositionMs = positionMs;

        long bucket = positionMs / AD_INTERVAL_MS;
        long bucketStart = bucket * AD_INTERVAL_MS;

        if (delta > MAX_NATURAL_ADVANCE_MS || delta < 0) {
            lastSkipPositionMs = bucketStart;
            return;
        }

        if (suppressBucketMs == bucketStart) {
            lastSkipPositionMs = bucketStart;
            return;
        }

        if (lastSkipPositionMs < bucketStart) {
            lastSkipPositionMs = bucketStart;
            long skipTo = positionMs + SKIP_AMOUNT_MS;
            long skippedFrom = positionMs;
            Log.d(TAG, "Ad skip: jumping from " + positionMs + " to " + skipTo);
            playBeep();
            EventBus.getDefault().post(new MessageEvent(
                    context.getString(R.string.ad_skip_toast),
                    ctx -> {
                        suppressBucketMs = bucketStart;
                        lastSkipPositionMs = bucketStart - 1;
                        seekCallback.seekTo(skippedFrom);
                    },
                    context.getString(R.string.undo)));
            seekCallback.seekTo(skipTo);
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
