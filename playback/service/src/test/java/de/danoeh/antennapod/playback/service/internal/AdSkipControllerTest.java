package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AdSkipControllerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private MockedStatic<UserPreferences> userPreferencesMock;

    private AdSkipController.SeekCallback seekCallback;
    private Context context;
    private File mediaFile;
    private File timestampsFile;
    private FeedMedia media;

    @Before
    public void setUp() throws Exception {
        seekCallback = mock(AdSkipController.SeekCallback.class);
        context = mock(Context.class);
        when(context.getString(anyInt())).thenReturn("");
        when(context.getString(anyInt(), any())).thenReturn("");
        when(context.getCacheDir()).thenReturn(tempFolder.getRoot());

        userPreferencesMock = mockStatic(UserPreferences.class);
        userPreferencesMock.when(() -> UserPreferences.getDataFolder("adtimestamps"))
                .thenReturn(new File(tempFolder.getRoot(), "adtimestamps"));

        mediaFile = tempFolder.newFile("episode.mp3");
        timestampsFile = new File(new File(tempFolder.getRoot(), "adtimestamps"), "1.json");
        timestampsFile.getParentFile().mkdirs();

        media = mock(FeedMedia.class);
        when(media.getId()).thenReturn(1L);
        when(media.getDownloadUrl()).thenReturn("https://example.com/episode.mp3");
        when(media.getLocalFileUrl()).thenReturn(mediaFile.getAbsolutePath());
    }

    @After
    public void tearDown() {
        userPreferencesMock.close();
    }

    private AdSkipController createController() {
        return new AdSkipController(context, seekCallback);
    }

    private void writeTimestamps(String status, long... startEndPairs) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"status\":\"").append(status).append("\",\"ads\":[");
        for (int i = 0; i < startEndPairs.length; i += 2) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"startMs\":").append(startEndPairs[i])
              .append(",\"endMs\":").append(startEndPairs[i + 1]).append("}");
        }
        sb.append("]}");
        try (FileWriter fw = new FileWriter(timestampsFile)) {
            fw.write(sb.toString());
        }
    }

    @Test
    public void skipsSingleAdSegment() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(35000);

        verify(seekCallback).seekTo(60000L);
    }

    @Test
    public void noSkipOutsideAdSegment() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(100000);

        verify(seekCallback, never()).seekTo(anyLong());
    }

    @Test
    public void noSkipBeforeSegmentStart() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(29999);

        verify(seekCallback, never()).seekTo(anyLong());
    }

    @Test
    public void noSkipAtSegmentEnd() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(60000);

        verify(seekCallback, never()).seekTo(anyLong());
    }

    @Test
    public void noReskipAlreadySkippedSegment() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(35000);
        controller.checkPosition(35000);

        verify(seekCallback, times(1)).seekTo(60000L);
    }

    @Test
    public void skipsMultipleDistinctSegments() throws Exception {
        writeTimestamps("complete", 10000, 20000, 50000, 70000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(15000);  // hits first segment → seekTo(20000)

        // simulate natural position advance after the seek (first tick from 20000 is a
        // large delta and is ignored, subsequent 1s ticks are accepted)
        for (long pos = 20000; pos <= 55000; pos += 1000) {
            controller.checkPosition(pos);
        }

        verify(seekCallback).seekTo(20000L);
        verify(seekCallback).seekTo(70000L);
    }

    @Test
    public void partialSegmentsUsedWhileProcessing() throws Exception {
        writeTimestamps("processing", 10000, 20000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(15000);

        verify(seekCallback).seekTo(20000L);
    }

    @Test
    public void resetClearsState() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.onReset();
        controller.checkPosition(35000);

        verify(seekCallback, never()).seekTo(anyLong());
    }

    @Test
    public void skipsAdSegmentStartingAtZero() throws Exception {
        writeTimestamps("complete", 0, 30000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(0);

        verify(seekCallback).seekTo(30000L);
    }

    @Test
    public void rewindToStartReskipsAdAtZeroAfterAdSkip() throws Exception {
        // After the first skip jumps from 0 to 30000, the next position tick has a large
        // delta and returns early without updating furthestPositionMs. If the user seeks
        // back to the start before furthestPositionMs advances past START_THRESHOLD_MS
        // via a normal tick, the rewind detection must still fire.
        writeTimestamps("complete", 0, 30000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(0);     // first skip fires → seekTo(30000)
        controller.checkPosition(30500); // first tick after skip: large delta, early return
        controller.checkPosition(0);     // user seeks back to start
        controller.checkPosition(1000);  // should re-skip

        verify(seekCallback, times(2)).seekTo(30000L);
    }

    @Test
    public void seekBackBeforeAdReskipsWhenReachedAgain() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(35000);
        verify(seekCallback, times(1)).seekTo(60000L);

        for (long pos = 60000; pos <= 65000; pos += 1000) {
            controller.checkPosition(pos);
        }

        controller.checkPosition(20000);

        for (long pos = 20000; pos <= 35000; pos += 1000) {
            controller.checkPosition(pos);
        }

        verify(seekCallback, times(2)).seekTo(60000L);
    }

    @Test
    public void undoPreventsReskip() throws Exception {
        writeTimestamps("complete", 30000, 60000, 120000, 150000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(35000);
        verify(seekCallback, times(1)).seekTo(60000L);

        controller.suppressAndSeek(0, 35000);

        for (long pos = 35000; pos <= 121000; pos += 1000) {
            controller.checkPosition(pos);
        }

        verify(seekCallback, times(1)).seekTo(60000L);
        verify(seekCallback, times(1)).seekTo(150000L);
    }

    @Test
    public void undoDoesNotClearOnRewindDetection() throws Exception {
        writeTimestamps("complete", 0, 30000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(0);      // first skip fires → seekTo(30000)
        verify(seekCallback, times(1)).seekTo(30000L);

        controller.suppressAndSeek(0, 0);

        controller.checkPosition(30500);  // large positive delta, early return
        controller.checkPosition(0);      // rewinds through start → detection fires
        controller.checkPosition(1000);   // should NOT re-skip because of undo

        verify(seekCallback, times(1)).seekTo(30000L);
    }

    @Test
    public void undoPreventsReskipAfterRewindThenRestart() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(35000);
        verify(seekCallback, times(1)).seekTo(60000L);

        controller.checkPosition(60500);  // past the ad
        controller.checkPosition(61000);  // normal advance
        controller.suppressAndSeek(0, 35000);

        controller.checkPosition(35000);   // position update after undo
        controller.checkPosition(36000);   // advance within ad segment — should not re-skip
        controller.checkPosition(55000);   // near end — should not re-skip

        verify(seekCallback, times(1)).seekTo(60000L);
    }

    @Test
    public void reloadsQuicklyWhenFileAppears() throws Exception {
        // Simulate worker still running: file doesn't exist yet when onMediaLoaded fires.
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);
        controller.checkPosition(0);
        verify(seekCallback, never()).seekTo(anyLong());

        // Worker finishes and writes the file. Advance the reload timer so the
        // fast retry (5s) is past, then the next checkPosition should reload.
        controller.advanceReloadTimerForTest();
        writeTimestamps("complete", 0, 30000);

        controller.checkPosition(1500);

        verify(seekCallback).seekTo(30000L);
    }

    @Test
    public void noSkipWhenPerFeedAdDetectionDisabled() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        FeedItem item = mock(FeedItem.class);
        Feed feed = mock(Feed.class);
        FeedPreferences prefs = mock(FeedPreferences.class);
        when(media.getItem()).thenReturn(item);
        when(item.getFeed()).thenReturn(feed);
        when(feed.getPreferences()).thenReturn(prefs);
        when(prefs.isAdDetectionEnabled(false)).thenReturn(false);
        when(prefs.getAdDetectionSetting()).thenReturn(FeedPreferences.AdDetectionSetting.DISABLED);

        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(35000);

        verify(seekCallback, never()).seekTo(anyLong());
    }

    @Test
    public void skipWhenPerFeedAdDetectionEnabled() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        FeedItem item = mock(FeedItem.class);
        Feed feed = mock(Feed.class);
        FeedPreferences prefs = mock(FeedPreferences.class);
        when(media.getItem()).thenReturn(item);
        when(item.getFeed()).thenReturn(feed);
        when(feed.getPreferences()).thenReturn(prefs);
        when(prefs.isAdDetectionEnabled(false)).thenReturn(true);
        when(prefs.getAdDetectionSetting()).thenReturn(FeedPreferences.AdDetectionSetting.ENABLED);

        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(35000);

        verify(seekCallback).seekTo(60000L);
    }

    @Test
    public void noSkipWhenPerFeedAdDetectionGlobalAndGloballyDisabled() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        FeedItem item = mock(FeedItem.class);
        Feed feed = mock(Feed.class);
        FeedPreferences prefs = mock(FeedPreferences.class);
        when(media.getItem()).thenReturn(item);
        when(item.getFeed()).thenReturn(feed);
        when(feed.getPreferences()).thenReturn(prefs);
        when(prefs.isAdDetectionEnabled(false)).thenReturn(false);
        when(prefs.getAdDetectionSetting()).thenReturn(FeedPreferences.AdDetectionSetting.GLOBAL);

        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(35000);

        verify(seekCallback, never()).seekTo(anyLong());
    }

    @Test
    public void skipWhenFeedItemIsNull() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        when(media.getItem()).thenReturn(null);

        AdSkipController controller = createController();
        controller.onMediaLoaded(media);

        controller.checkPosition(35000);

        verify(seekCallback).seekTo(60000L);
    }

    // --- Ad Martyr tests ---

    @Test
    public void skipsAdInAdMartyrMode() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);
        controller.setAdMartyrEnabledForTest(true);

        controller.checkPosition(35000, 300000);

        verify(seekCallback).seekTo(60000L);
    }

    @Test
    public void entersMartyrModeNearEnd() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);
        controller.setAdMartyrEnabledForTest(true);

        controller.checkPosition(298000, 300000);

        verify(seekCallback).seekTo(30000L);
    }

    @Test
    public void playsAdSegmentsSequentiallyInMartyrMode() throws Exception {
        writeTimestamps("complete", 10000, 20000, 50000, 70000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);
        controller.setAdMartyrEnabledForTest(true);

        controller.checkPosition(298000, 300000);
        verify(seekCallback).seekTo(10000L);

        controller.checkPosition(10000, 300000);
        controller.checkPosition(15000, 300000);
        controller.checkPosition(20000, 300000);
        verify(seekCallback).seekTo(50000L);

        controller.checkPosition(50000, 300000);
        controller.checkPosition(60000, 300000);
        controller.checkPosition(70000, 300000);
        verify(seekCallback).seekTo(300000L);
    }

    @Test
    public void noSkipInMartyrPlaybackMode() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);
        controller.setAdMartyrEnabledForTest(true);

        controller.checkPosition(298000, 300000);
        verify(seekCallback).seekTo(30000L);

        controller.checkPosition(30000, 300000);

        // Should NOT skip past the ad — should let it play in martyr mode
        verify(seekCallback, never()).seekTo(60000L);
    }

    @Test
    public void toastShownInAdMartyrMode() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);
        controller.setAdMartyrEnabledForTest(true);

        controller.checkPosition(35000, 300000);

        // Toast is always shown, regardless of martyr mode
        verify(context).getString(anyInt(), any());
    }

    @Test
    public void resetClearsAdMartyrState() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);
        controller.setAdMartyrEnabledForTest(true);

        controller.checkPosition(298000, 300000);
        verify(seekCallback).seekTo(30000L);

        controller.onReset();
        controller.onMediaLoaded(media);
        controller.setAdMartyrEnabledForTest(true);

        controller.checkPosition(298000, 300000);
        verify(seekCallback, times(2)).seekTo(30000L);
    }

    @Test
    public void doesNotEnterMartyrModeWithEmptyPendingSegments() throws Exception {
        // No timestamps file → no ad segments → pendingAdSegments stays empty
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);
        controller.setAdMartyrEnabledForTest(true);

        controller.checkPosition(298000, 300000);

        verify(seekCallback, never()).seekTo(anyLong());
    }

    @Test
    public void doesNotEnterMartyrModeWithInvalidDuration() throws Exception {
        writeTimestamps("complete", 30000, 60000);
        AdSkipController controller = createController();
        controller.onMediaLoaded(media);
        controller.setAdMartyrEnabledForTest(true);

        controller.checkPosition(298000, 0);
        controller.checkPosition(298000, -1);

        verify(seekCallback, never()).seekTo(anyLong());
    }
}
