package de.danoeh.antennapod.net.download.service.episode;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Data;
import androidx.work.WorkerParameters;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.VolumeAdaptionSetting;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
public class AdDetectionWorkerTest {

    private static class TestableAdDetectionWorker extends AdDetectionWorker {
        private List<String> chatApiCalls = new ArrayList<>();
        private String cannedChatResponse;

        TestableAdDetectionWorker(Context context, WorkerParameters params) {
            super(context, params);
        }

        void setCannedChatResponse(String response) {
            this.cannedChatResponse = response;
        }

        List<String> getChatApiCalls() {
            return chatApiCalls;
        }

        @Override
        protected String callChatApi(OkHttpClient client, String apiKey, String baseUrl,
                String model, JSONArray messages) throws IOException {
            chatApiCalls.add(messages.toString());
            if (cannedChatResponse == null) {
                throw new IOException("No canned response set");
            }
            return cannedChatResponse;
        }
    }

    private static String wrapAdResponse(List<long[]> ads) {
        StringBuilder sb = new StringBuilder("{\"choices\":[{\"message\":{\"content\":\"");
        sb.append("{\\\"ads\\\":[");
        for (int i = 0; i < ads.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\\\"startMs\\\":").append(ads.get(i)[0])
                    .append(",\\\"endMs\\\":").append(ads.get(i)[1]).append("}");
        }
        sb.append("]}\"}}]}");
        return sb.toString();
    }

    private static String wrapAdResponseStartEnd(List<long[]> ads) {
        StringBuilder sb = new StringBuilder("{\"choices\":[{\"message\":{\"content\":\"");
        sb.append("{\\\"ads\\\":[");
        for (int i = 0; i < ads.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\\\"start\\\":").append(ads.get(i)[0])
                    .append(",\\\"end\\\":").append(ads.get(i)[1]).append("}");
        }
        sb.append("]}\"}}]}");
        return sb.toString();
    }

    private Context context;
    private WorkerParameters params;
    private TestableAdDetectionWorker worker;
    private OkHttpClient client;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AdDetectionPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        Data inputData = new Data.Builder()
                .putLong(AdDetectionWorker.KEY_FEED_MEDIA_ID, 1)
                .build();
        params = mock(WorkerParameters.class);
        when(params.getInputData()).thenReturn(inputData);
        worker = new TestableAdDetectionWorker(context, params);
        client = new OkHttpClient();
    }

    @After
    public void tearDown() {
        PodDBAdapter.tearDownTests();
    }

    private AdDetectionWorker.Segment seg(long startMs, long endMs, String text) {
        return new AdDetectionWorker.Segment(startMs, endMs, text);
    }

    // --- parseAdSegments tests ---

    @Test
    public void testParseAdSegmentsSingleAd() throws Exception {
        String json = wrapAdResponse(Collections.singletonList(new long[]{30000, 60000}));
        List<long[]> ads = worker.parseAdSegments(json);
        assertEquals(1, ads.size());
        assertEquals(30000L, ads.get(0)[0]);
        assertEquals(60000L, ads.get(0)[1]);
    }

    @Test
    public void testParseAdSegmentsNoAds() throws Exception {
        String json = wrapAdResponse(Collections.emptyList());
        List<long[]> ads = worker.parseAdSegments(json);
        assertTrue(ads.isEmpty());
    }

    @Test
    public void testParseAdSegmentsUsesStartEndFallback() throws Exception {
        String json = wrapAdResponseStartEnd(Collections.singletonList(new long[]{10000, 45000}));
        List<long[]> ads = worker.parseAdSegments(json);
        assertEquals(1, ads.size());
        assertEquals(10000L, ads.get(0)[0]);
        assertEquals(45000L, ads.get(0)[1]);
    }

    @Test
    public void testParseAdSegmentsMalformedContentReturnsEmpty() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"not valid json\"}}]}";
        List<long[]> ads = worker.parseAdSegments(json);
        assertTrue(ads.isEmpty());
    }

    @Test
    public void testParseAdSegmentsMissingContentFieldReturnsEmpty() throws Exception {
        String json = "{\"choices\":[{\"message\":{}}]}";
        List<long[]> ads = worker.parseAdSegments(json);
        assertTrue(ads.isEmpty());
    }

    // --- mergeConsecutiveAds tests ---

    @Test
    public void testMergeConsecutiveAdsNoOverlap() {
        List<long[]> input = Arrays.asList(
                new long[]{10000, 20000},
                new long[]{80000, 90000}
        );
        List<long[]> merged = worker.mergeConsecutiveAds(input);
        assertEquals(2, merged.size());
    }

    @Test
    public void testMergeConsecutiveAdsWithinGap() {
        List<long[]> input = Arrays.asList(
                new long[]{10000, 20000},
                new long[]{40000, 50000}
        );
        List<long[]> merged = worker.mergeConsecutiveAds(input);
        assertEquals(1, merged.size());
        assertEquals(10000L, merged.get(0)[0]);
        assertEquals(50000L, merged.get(0)[1]);
    }

    @Test
    public void testMergeConsecutiveAdsUnorderedInput() {
        List<long[]> input = Arrays.asList(
                new long[]{90000, 110000},
                new long[]{10000, 20000}
        );
        List<long[]> merged = worker.mergeConsecutiveAds(input);
        assertEquals(2, merged.size());
        assertEquals(10000L, merged.get(0)[0]);
        assertEquals(90000L, merged.get(1)[0]);
    }

    @Test
    public void testMergeConsecutiveAdsSingleElement() {
        List<long[]> input = Collections.singletonList(
                new long[]{0, 30000}
        );
        List<long[]> merged = worker.mergeConsecutiveAds(input);
        assertEquals(1, merged.size());
        assertEquals(0L, merged.get(0)[0]);
        assertEquals(30000L, merged.get(0)[1]);
    }

    @Test
    public void testMergeConsecutiveAdsEmpty() {
        List<long[]> merged = worker.mergeConsecutiveAds(Collections.emptyList());
        assertTrue(merged.isEmpty());
    }

    // --- classifyAds tests ---

    @Test
    public void testClassifyAdsInvokesApi() throws Exception {
        List<AdDetectionWorker.Segment> segments = Arrays.asList(
                seg(0, 5000, "Welcome to the show"),
                seg(5000, 15000, "Today's episode is sponsored by Acme"),
                seg(15000, 25000, "Use code PODCAST20 for 20% off"),
                seg(25000, 60000, "Now let's dive into the main topic")
        );
        worker.setCannedChatResponse(
                wrapAdResponse(Collections.singletonList(new long[]{5000, 25000})));

        List<long[]> ads = worker.classifyAds(segments, client,
                "apikey", "https://api.example.com/v1", "test-model", null);

        assertEquals(1, ads.size());
        assertEquals(5000L, ads.get(0)[0]);
        assertEquals(25000L, ads.get(0)[1]);
        assertEquals(1, worker.getChatApiCalls().size());
        String callContent = worker.getChatApiCalls().get(0);
        assertTrue(callContent.contains("Acme"));
        assertTrue(callContent.contains("PODCAST20"));
    }

    @Test
    public void testClassifyAdsEmptySegments() throws Exception {
        List<long[]> ads = worker.classifyAds(Collections.emptyList(), client,
                "key", "url", "model", null);
        assertTrue(ads.isEmpty());
        assertTrue(worker.getChatApiCalls().isEmpty());
    }

    @Test
    public void testClassifyAdsUsesCustomPrompt() throws Exception {
        List<AdDetectionWorker.Segment> segments = Collections.singletonList(
                seg(0, 10000, "Test content"));
        worker.setCannedChatResponse(wrapAdResponse(Collections.emptyList()));

        worker.classifyAds(segments, client, "key", "url", "model", "Custom prompt here");

        String callContent = worker.getChatApiCalls().get(0);
        assertTrue(callContent.contains("Custom prompt here"));
    }

    // --- validateAds tests ---

    @Test
    public void testValidateAdsAddsMissedZeroAd() throws Exception {
        List<AdDetectionWorker.Segment> segments = Arrays.asList(
                seg(0, 5000, "This show is brought to you by Squarespace"),
                seg(5000, 10000, "Build your website today with code LISTENER"),
                seg(10000, 30000, "Welcome everyone to episode 42, today we discuss"),
                seg(30000, 60000, "The main topic of this episode is")
        );
        List<long[]> firstPass = Collections.singletonList(
                new long[]{30000, 60000});
        worker.setCannedChatResponse(
                wrapAdResponse(Arrays.asList(
                        new long[]{0, 10000},
                        new long[]{30000, 60000})));

        List<long[]> validated = worker.validateAds(segments, firstPass, client,
                "key", "https://api.example.com/v1", "model", null);

        assertEquals(2, validated.size());
        assertEquals(0L, validated.get(0)[0]);
        assertEquals(10000L, validated.get(0)[1]);
        assertEquals(30000L, validated.get(1)[0]);
        assertEquals(60000L, validated.get(1)[1]);
    }

    @Test
    public void testValidateAdsPreservesCorrectAds() throws Exception {
        List<AdDetectionWorker.Segment> segments = Arrays.asList(
                seg(0, 5000, "Hello and welcome"),
                seg(5000, 25000, "Our sponsor today is HelloFresh"),
                seg(25000, 60000, "Now let's get into the episode")
        );
        List<long[]> firstPass = Collections.singletonList(
                new long[]{5000, 25000});
        worker.setCannedChatResponse(
                wrapAdResponse(Collections.singletonList(new long[]{5000, 25000})));

        List<long[]> validated = worker.validateAds(segments, firstPass, client,
                "key", "url", "model", null);

        assertEquals(1, validated.size());
        assertEquals(5000L, validated.get(0)[0]);
        assertEquals(25000L, validated.get(0)[1]);
    }

    @Test
    public void testValidateAdsFallsBackOnApiFailure() throws Exception {
        List<AdDetectionWorker.Segment> segments = Collections.singletonList(
                seg(0, 10000, "Test"));
        List<long[]> firstPass = Collections.singletonList(
                new long[]{0, 10000});
        worker.setCannedChatResponse(null); // will throw IOException

        List<long[]> validated = worker.validateAds(segments, firstPass, client,
                "key", "url", "model", null);

        assertEquals(firstPass, validated);
    }

    @Test
    public void testValidateAdsEmptySegmentsReturnsFirstPass() {
        List<long[]> firstPass = Collections.singletonList(
                new long[]{0, 10000});
        List<long[]> validated = worker.validateAds(Collections.emptyList(), firstPass, client,
                "key", "url", "model", null);

        assertEquals(firstPass, validated);
    }

    @Test
    public void testValidateAdsEmptyFirstPassGetsCorrected() throws Exception {
        List<AdDetectionWorker.Segment> segments = Arrays.asList(
                seg(0, 5000, "Welcome to our sponsor NordVPN"),
                seg(5000, 15000, "Get 70% off at nordvpn.com/podcast"),
                seg(15000, 60000, "Now for today's main discussion")
        );
        List<long[]> firstPass = Collections.emptyList();
        worker.setCannedChatResponse(
                wrapAdResponse(Collections.singletonList(new long[]{0, 15000})));

        List<long[]> validated = worker.validateAds(segments, firstPass, client,
                "key", "url", "model", null);

        assertEquals(1, validated.size());
        assertEquals(0L, validated.get(0)[0]);
        assertEquals(15000L, validated.get(0)[1]);
    }

    @Test
    public void testValidateAdsIncludesTranscriptInPrompt() throws Exception {
        List<AdDetectionWorker.Segment> segments = Collections.singletonList(
                seg(10000, 30000, "Sponsored by Acme Corp"));
        List<long[]> firstPass = Collections.emptyList();
        worker.setCannedChatResponse(wrapAdResponse(firstPass));

        worker.validateAds(segments, firstPass, client,
                "key", "url", "model", null);

        String callContent = worker.getChatApiCalls().get(0);
        assertTrue(callContent.contains("Acme Corp"));
        assertTrue(callContent.contains("No ads detected"));
        assertTrue(callContent.contains("first-pass"));
    }

    @Test
    public void testClassifyAndValidateEndToEnd() throws Exception {
        // Simulate: first pass misses the zero-start ad, validation catches it
        List<AdDetectionWorker.Segment> segments = Arrays.asList(
                seg(0, 8000, "Before we start, a word from our sponsor BetterHelp"),
                seg(8000, 15000, "Visit betterhelp.com/pod for 10% off your first month"),
                seg(15000, 30000, "Welcome to episode 100! Today we are joined by"),
                seg(30000, 120000, "Let's talk about the science behind...")
        );

        // First pass: LLM misses the pre-roll ad
        worker.setCannedChatResponse(
                wrapAdResponse(Collections.emptyList()));
        List<long[]> firstPassResult = worker.classifyAds(segments, client,
                "key", "url", "model", null);
        assertTrue("First pass should find no ads (simulated)", firstPassResult.isEmpty());

        // Validation pass: catches the missed pre-roll ad
        worker.getChatApiCalls().clear();
        worker.setCannedChatResponse(
                wrapAdResponse(Collections.singletonList(new long[]{0, 15000})));
        List<long[]> validated = worker.validateAds(segments, firstPassResult, client,
                "key", "url", "model", null);

        assertEquals(1, validated.size());
        assertEquals(0L, validated.get(0)[0]);
        assertEquals(15000L, validated.get(0)[1]);
    }

    @Test
    public void testMergeAfterValidationCombinesCloseAds() throws Exception {
        List<AdDetectionWorker.Segment> segments = Arrays.asList(
                seg(0, 10000, "Sponsored by Acme"),
                seg(10000, 30000, "Brief content"),
                seg(30000, 50000, "Also sponsored by Beta"),
                seg(50000, 60000, "More brief content"),
                seg(60000, 90000, "Final sponsor Gamma")
        );

        // Validation returns three separate ads that are close together
        worker.setCannedChatResponse(
                wrapAdResponse(Arrays.asList(
                        new long[]{0, 10000},
                        new long[]{30000, 50000},
                        new long[]{60000, 90000})));
        List<long[]> validated = worker.validateAds(segments, Collections.emptyList(), client,
                "key", "url", "model", null);

        // mergeConsecutiveAds should combine them since gaps are <= 30s
        List<long[]> merged = worker.mergeConsecutiveAds(validated);
        assertEquals(1, merged.size());
        assertEquals(0L, merged.get(0)[0]);
        assertEquals(90000L, merged.get(0)[1]);
    }

    private long seedFeedWithAdDetectionSetting(FeedPreferences.AdDetectionSetting setting)
            throws ExecutionException, InterruptedException {
        Feed feed = new Feed(0, null, "Test Feed", "http://example.com/feed", "desc",
                null, null, null, "rss", null, null, null, "http://example.com/feed",
                System.currentTimeMillis());
        FeedPreferences prefs = new FeedPreferences(0,
                FeedPreferences.AutoDownloadSetting.GLOBAL,
                FeedPreferences.AutoDeleteAction.GLOBAL,
                VolumeAdaptionSetting.OFF,
                FeedPreferences.NewEpisodesAction.GLOBAL,
                null, null);
        prefs.setAdDetectionSetting(setting);
        feed.setPreferences(prefs);
        FeedItem item = new FeedItem(0, "Test Item", "id-item", "http://example.com/item",
                new Date(), FeedItem.UNPLAYED, feed);
        FeedMedia media = new FeedMedia(0, item, 10000, 0, 1024, "audio/mpeg",
                null, "http://example.com/audio.mp3", 0, null, 0, 0);
        item.setMedia(media);
        feed.setItems(new java.util.ArrayList<>(Collections.singletonList(item)));

        PodDBAdapter.getInstance().setCompleteFeed(feed);

        return feed.getItems().get(0).getMedia().getId();
    }

    @Test
    public void testIsAdDetectionEnabledForFeedDisabled() throws Exception {
        long mediaId = seedFeedWithAdDetectionSetting(
                FeedPreferences.AdDetectionSetting.DISABLED);
        assertFalse(AdDetectionWorker.isAdDetectionEnabledForFeed(context, mediaId));
    }

    @Test
    public void testIsAdDetectionEnabledForFeedEnabled() throws Exception {
        long mediaId = seedFeedWithAdDetectionSetting(
                FeedPreferences.AdDetectionSetting.ENABLED);
        assertTrue(AdDetectionWorker.isAdDetectionEnabledForFeed(context, mediaId));
    }

    @Test
    public void testIsAdDetectionEnabledForFeedGlobalWhenGloballyDisabled() throws Exception {
        long mediaId = seedFeedWithAdDetectionSetting(
                FeedPreferences.AdDetectionSetting.GLOBAL);
        assertFalse(AdDetectionWorker.isAdDetectionEnabledForFeed(context, mediaId));
    }

    @Test
    public void testIsAdDetectionEnabledForFeedNegativeId() throws Exception {
        assertTrue(AdDetectionWorker.isAdDetectionEnabledForFeed(context, -1));
    }

    @Test
    public void testIsAdDetectionEnabledForFeedNonexistentMedia() throws Exception {
        assertTrue(AdDetectionWorker.isAdDetectionEnabledForFeed(context, 99999));
    }

    @Test
    public void testGetConcurrentLimitDefault() {
        assertEquals(2, AdDetectionPreferences.getConcurrentLimit());
    }
}
