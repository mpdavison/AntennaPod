package de.danoeh.antennapod.storage.database;

import android.content.ContentValues;
import android.database.Cursor;

import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class PodDBAdapterAdStatsTest {
    private PodDBAdapter adapter;
    private long mediaId1;
    private long mediaId2;

    @Before
    public void setUp() {
        adapter = PodDBAdapter.getInstance();
        PodDBAdapter.init(InstrumentationRegistry.getInstrumentation().getContext());
        PodDBAdapter.deleteDatabase();
        adapter.open();

        Feed feed = new Feed("url", null, null);
        feed.setItems(new ArrayList<>());
        FeedItem item1 = new FeedItem();
        item1.setItemIdentifier("id1");
        item1.setTitle("Item 1");
        item1.setMedia(new FeedMedia(item1, "url-1", 2, "mime"));
        item1.setFeed(feed);
        feed.getItems().add(item1);

        FeedItem item2 = new FeedItem();
        item2.setItemIdentifier("id2");
        item2.setTitle("Item 2");
        item2.setMedia(new FeedMedia(item2, "url-2", 2, "mime"));
        item2.setFeed(feed);
        feed.getItems().add(item2);

        adapter.setCompleteFeed(feed);
        mediaId1 = item1.getMedia().getId();
        mediaId2 = item2.getMedia().getId();
    }

    @After
    public void tearDown() {
        adapter.close();
        PodDBAdapter.tearDownTests();
    }

    @Test
    public void testSetFeedMediaAdStatsPersistsValues() {
        adapter.setFeedMediaAdStats(mediaId1, 5, 120000);

        Cursor c = adapter.getFeedStatisticsCursor(false, 0, Long.MAX_VALUE, 0);
        c.moveToFirst();
        assertEquals(5, c.getLong(c.getColumnIndexOrThrow("ad_segments")));
        assertEquals(120000, c.getLong(c.getColumnIndexOrThrow("ad_duration")));
        c.close();
    }

    @Test
    public void testSetFeedMediaAdStatsOverwritesExisting() {
        adapter.setFeedMediaAdStats(mediaId1, 3, 60000);
        adapter.setFeedMediaAdStats(mediaId1, 7, 180000);

        Cursor c = adapter.getFeedStatisticsCursor(false, 0, Long.MAX_VALUE, 0);
        c.moveToFirst();
        assertEquals(7, c.getLong(c.getColumnIndexOrThrow("ad_segments")));
        assertEquals(180000, c.getLong(c.getColumnIndexOrThrow("ad_duration")));
        c.close();
    }

    @Test
    public void testSetFeedMediaAdStatsZeroValues() {
        adapter.setFeedMediaAdStats(mediaId1, 5, 120000);
        adapter.setFeedMediaAdStats(mediaId1, 0, 0);

        Cursor c = adapter.getFeedStatisticsCursor(false, 0, Long.MAX_VALUE, 0);
        c.moveToFirst();
        assertEquals(0, c.getLong(c.getColumnIndexOrThrow("ad_segments")));
        assertEquals(0, c.getLong(c.getColumnIndexOrThrow("ad_duration")));
        c.close();
    }

    @Test
    public void testResetAllMediaPlayedDurationZerosAdStats() {
        adapter.setFeedMediaAdStats(mediaId1, 5, 120000);

        adapter.resetAllMediaPlayedDuration();

        Cursor c = adapter.getFeedStatisticsCursor(false, 0, Long.MAX_VALUE, 0);
        c.moveToFirst();
        assertEquals(0, c.getLong(c.getColumnIndexOrThrow("ad_segments")));
        assertEquals(0, c.getLong(c.getColumnIndexOrThrow("ad_duration")));
        c.close();
    }

    @Test
    public void testNewColumnsDefaultToZero() {
        Cursor c = adapter.getFeedStatisticsCursor(false, 0, Long.MAX_VALUE, 0);
        c.moveToFirst();
        assertEquals(0, c.getLong(c.getColumnIndexOrThrow("ad_segments")));
        assertEquals(0, c.getLong(c.getColumnIndexOrThrow("ad_duration")));
        c.close();
    }
}
