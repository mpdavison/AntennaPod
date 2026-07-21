package de.danoeh.antennapod.storage.database;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class DBReaderAdStatsTest {
    private PodDBAdapter adapter;
    private long feedId1;
    private long feedId2;
    private long media1;
    private long media2;
    private long media3;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        UserPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        adapter = PodDBAdapter.getInstance();
        adapter.open();

        Feed feed1 = new Feed("url1", null, null);
        feed1.setItems(new ArrayList<>());
        FeedItem item1 = createItem("id1", "Item 1", feed1);
        FeedItem item2 = createItem("id2", "Item 2", feed1);
        feed1.getItems().add(item1);
        feed1.getItems().add(item2);

        Feed feed2 = new Feed("url2", null, null);
        feed2.setItems(new ArrayList<>());
        FeedItem item3 = createItem("id3", "Item 3", feed2);
        feed2.getItems().add(item3);

        adapter.setCompleteFeed(feed1, feed2);
        feedId1 = feed1.getId();
        feedId2 = feed2.getId();
        media1 = item1.getMedia().getId();
        media2 = item2.getMedia().getId();
        media3 = item3.getMedia().getId();
    }

    private FeedItem createItem(String identifier, String title, Feed feed) {
        FeedItem item = new FeedItem();
        item.setItemIdentifier(identifier);
        item.setTitle(title);
        item.setMedia(new FeedMedia(item, "url-" + title, 2, "mime"));
        item.setFeed(feed);
        return item;
    }

    @After
    public void tearDown() {
        adapter.close();
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void testGetStatisticsAggregatesAdDataPerFeed() {
        adapter.setFeedMediaAdStats(media1, 3, 90000);
        adapter.setFeedMediaAdStats(media2, 5, 150000);
        adapter.setFeedMediaAdStats(media3, 2, 60000);
        adapter.close();

        DBReader.StatisticsResult result = DBReader.getStatistics(false, 0, Long.MAX_VALUE);
        List<StatisticsItem> items = result.feedTime;

        assertEquals(2, items.size());
        for (StatisticsItem item : items) {
            if (item.feed.getId() == feedId1) {
                assertEquals(8, item.adSegmentCount);
                assertEquals(240000, item.adTotalDurationMs);
            } else if (item.feed.getId() == feedId2) {
                assertEquals(2, item.adSegmentCount);
                assertEquals(60000, item.adTotalDurationMs);
            }
        }
    }

    @Test
    public void testGetStatisticsAdDefaultsToZero() {
        adapter.close();

        DBReader.StatisticsResult result = DBReader.getStatistics(false, 0, Long.MAX_VALUE);

        for (StatisticsItem item : result.feedTime) {
            assertEquals(0, item.adSegmentCount);
            assertEquals(0, item.adTotalDurationMs);
        }
    }
}
