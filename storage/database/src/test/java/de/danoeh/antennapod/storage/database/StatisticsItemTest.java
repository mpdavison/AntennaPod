package de.danoeh.antennapod.storage.database;

import de.danoeh.antennapod.model.feed.Feed;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StatisticsItemTest {

    @Test
    public void testConstructorSetsAllFields() {
        Feed feed = new Feed("id", null, "Title");
        feed.setId(1);

        StatisticsItem item = new StatisticsItem(feed, 100, 50, 10, 5, 2000, 3, true, 4, 30000);

        assertEquals(feed, item.feed);
        assertEquals(100, item.time);
        assertEquals(50, item.timePlayed);
        assertEquals(10, item.episodes);
        assertEquals(5, item.episodesStarted);
        assertEquals(2000, item.totalDownloadSize);
        assertEquals(3, item.episodesDownloadCount);
        assertEquals(true, item.hasRecentUnplayed);
        assertEquals(4, item.adSegmentCount);
        assertEquals(30000, item.adTotalDurationMs);
    }

    @Test
    public void testAdStatsDefaultToZero() {
        Feed feed = new Feed("id", null, "Title");
        feed.setId(1);

        StatisticsItem item = new StatisticsItem(feed, 100, 50, 10, 5, 2000, 3, true, 0, 0);

        assertEquals(0, item.adSegmentCount);
        assertEquals(0, item.adTotalDurationMs);
    }
}
