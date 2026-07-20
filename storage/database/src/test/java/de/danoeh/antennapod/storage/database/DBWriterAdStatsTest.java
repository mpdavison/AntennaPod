package de.danoeh.antennapod.storage.database;

import android.content.Context;
import android.database.Cursor;

import androidx.test.platform.app.InstrumentationRegistry;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class DBWriterAdStatsTest {
    private PodDBAdapter adapter;
    private long mediaId;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        UserPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());
        adapter = PodDBAdapter.getInstance();
        adapter.open();

        Feed feed = new Feed("url", null, null);
        feed.setItems(new ArrayList<>());
        FeedItem item = new FeedItem();
        item.setItemIdentifier("id1");
        item.setTitle("Item 1");
        item.setMedia(new FeedMedia(item, "url-1", 2, "mime"));
        item.setFeed(feed);
        feed.getItems().add(item);

        adapter.setCompleteFeed(feed);
        mediaId = item.getMedia().getId();
    }

    @After
    public void tearDown() {
        adapter.close();
        PodDBAdapter.tearDownTests();
        DBWriter.tearDownTests();
    }

    @Test
    public void testSetFeedMediaAdStatsPersistsViaDBThread() throws Exception {
        DBWriter.setFeedMediaAdStats(mediaId, 5, 120000).get(5, TimeUnit.SECONDS);

        Cursor c = adapter.getFeedStatisticsCursor(false, 0, Long.MAX_VALUE, 0);
        c.moveToFirst();
        assertEquals(5, c.getLong(c.getColumnIndexOrThrow("ad_segments")));
        assertEquals(120000, c.getLong(c.getColumnIndexOrThrow("ad_duration")));
        c.close();
    }

    @Test
    public void testSetFeedMediaAdStatsOverwritesPrevious() throws Exception {
        adapter.setFeedMediaAdStats(mediaId, 3, 60000);

        DBWriter.setFeedMediaAdStats(mediaId, 8, 240000).get(5, TimeUnit.SECONDS);

        Cursor c = adapter.getFeedStatisticsCursor(false, 0, Long.MAX_VALUE, 0);
        c.moveToFirst();
        assertEquals(8, c.getLong(c.getColumnIndexOrThrow("ad_segments")));
        assertEquals(240000, c.getLong(c.getColumnIndexOrThrow("ad_duration")));
        c.close();
    }
}
