package de.danoeh.antennapod.net.download.serviceinterface;

import android.content.Context;
import de.danoeh.antennapod.model.feed.FeedItem;

public abstract class AdDetectionManager {
    private static AdDetectionManager instance;

    public static AdDetectionManager getInstance() {
        return instance;
    }

    public static void setInstance(AdDetectionManager instance) {
        AdDetectionManager.instance = instance;
    }

    public abstract void enqueueAdDetection(Context context, FeedItem item);
}
