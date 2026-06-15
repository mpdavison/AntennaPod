package de.danoeh.antennapod.net.download.serviceinterface;

import de.danoeh.antennapod.event.AdDetectionProgressEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AdDetectionManagerTest {

    @Test
    public void setProgress_storesAndRetrievesProgress() {
        AdDetectionManager.setProgress(42, 50);
        assertEquals(50, AdDetectionManager.getProgress(42));
    }

    @Test
    public void setProgress_removesEntryAtOneHundred() {
        AdDetectionManager.setProgress(42, 100);
        assertEquals(-1, AdDetectionManager.getProgress(42));
    }

    @Test
    public void setProgress_removesEntryAboveOneHundred() {
        AdDetectionManager.setProgress(42, 150);
        assertEquals(-1, AdDetectionManager.getProgress(42));
    }

    @Test
    public void setProgress_storesMultipleEntriesIndependently() {
        AdDetectionManager.setProgress(1, 25);
        AdDetectionManager.setProgress(2, 75);
        assertEquals(25, AdDetectionManager.getProgress(1));
        assertEquals(75, AdDetectionManager.getProgress(2));
    }

    @Test
    public void getProgress_returnsNegativeForUnknownMedia() {
        assertEquals(-1, AdDetectionManager.getProgress(999));
    }

    @Test
    public void indexOfItemWithMediaId_findsMatchingItem() {
        List<FeedItem> items = new ArrayList<>();
        Feed feed = new Feed("http://example.com", null);
        for (int i = 0; i < 5; i++) {
            FeedItem item = new FeedItem(0, null, null, null, null, 0, null);
            FeedMedia media = new FeedMedia(i, null, 0, 0, 0, null, null, null, 0L, null, 0, 0L);
            item.setMedia(media);
            item.setFeed(feed);
            items.add(item);
        }
        int pos = AdDetectionProgressEvent.indexOfItemWithMediaId(items, 3);
        assertEquals(3, pos);
    }

    @Test
    public void indexOfItemWithMediaId_returnsNegativeForNonExistentMedia() {
        List<FeedItem> items = new ArrayList<>();
        Feed feed = new Feed("http://example.com", null);
        FeedItem item = new FeedItem(0, null, null, null, null, 0, null);
        FeedMedia media = new FeedMedia(1, null, 0, 0, 0, null, null, null, 0L, null, 0, 0L);
        item.setMedia(media);
        item.setFeed(feed);
        items.add(item);
        int pos = AdDetectionProgressEvent.indexOfItemWithMediaId(items, 99);
        assertEquals(-1, pos);
    }

    @Test
    public void indexOfItemWithMediaId_handlesItemsWithoutMedia() {
        List<FeedItem> items = new ArrayList<>();
        Feed feed = new Feed("http://example.com", null);
        FeedItem item = new FeedItem(0, null, null, null, null, 0, null);
        item.setFeed(feed);
        items.add(item);
        int pos = AdDetectionProgressEvent.indexOfItemWithMediaId(items, 1);
        assertEquals(-1, pos);
    }
}
