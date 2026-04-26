package de.danoeh.antennapod.playback.service.internal;

import android.content.Context;
import de.danoeh.antennapod.model.feed.FeedMedia;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AdSkipControllerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

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

        mediaFile = tempFolder.newFile("episode.mp3");
        timestampsFile = new File(mediaFile.getAbsolutePath() + ".adtimestamps");

        media = mock(FeedMedia.class);
        when(media.getId()).thenReturn(1L);
        when(media.getDownloadUrl()).thenReturn("https://example.com/episode.mp3");
        when(media.getLocalFileUrl()).thenReturn(mediaFile.getAbsolutePath());
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
}
