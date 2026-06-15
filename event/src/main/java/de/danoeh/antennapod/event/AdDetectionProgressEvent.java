package de.danoeh.antennapod.event;

import de.danoeh.antennapod.model.feed.FeedItem;

import java.util.List;
import java.util.Set;

public class AdDetectionProgressEvent {
    private final Set<Long> mediaIds;

    public AdDetectionProgressEvent(Set<Long> mediaIds) {
        this.mediaIds = mediaIds;
    }

    public Set<Long> getMediaIds() {
        return mediaIds;
    }

    public static int indexOfItemWithMediaId(List<FeedItem> items, long mediaId) {
        for (int i = 0; i < items.size(); i++) {
            FeedItem item = items.get(i);
            if (item != null && item.getMedia() != null && item.getMedia().getId() == mediaId) {
                return i;
            }
        }
        return -1;
    }
}
