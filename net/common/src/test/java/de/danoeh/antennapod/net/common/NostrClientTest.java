package de.danoeh.antennapod.net.common;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class NostrClientTest {

    @Test
    public void testKeyHexRoundtrip() {
        BigInteger priv = NostrClient.generatePrivateKey();
        assertEquals(64, NostrClient.privateKeyToHex(priv).length());

        String hex = NostrClient.privateKeyToHex(priv);
        BigInteger reloaded = NostrClient.hexToPrivateKey(hex);
        assertEquals(priv, reloaded);
    }

    @Test
    public void testPublicKeyDerivationMatchesCoincurve() {
        BigInteger priv = NostrClient.hexToPrivateKey(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        String pubkey = NostrClient.getPublicKeyHex(priv);
        assertEquals(
                "4646ae5047316b4230d0086c8acec687f00b1cd9d1dc634f6cb358ac0a9a8fff",
                pubkey);
    }

    @Test
    public void testSignEventProducesCorrectLength() {
        BigInteger priv = NostrClient.generatePrivateKey();
        byte[] message = "test".getBytes(StandardCharsets.UTF_8);
        String sigHex = NostrClient.signEvent(message, priv);
        assertEquals(128, sigHex.length());
    }

    @Test
    public void testBuildSignedEventFields() throws Exception {
        BigInteger priv = NostrClient.hexToPrivateKey(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        String expectedPubkey =
                "4646ae5047316b4230d0086c8acec687f00b1cd9d1dc634f6cb358ac0a9a8fff";

        JSONArray tags = new JSONArray();
        String content = "{\"status\":\"complete\",\"ads\":[]}";
        long createdAt = 1782926107L;

        org.json.JSONObject event = NostrClient.buildSignedEvent(
                content, tags, priv, createdAt);

        assertEquals(expectedPubkey, event.getString("pubkey"));
        assertEquals(31337, event.getInt("kind"));
        assertEquals(createdAt, event.getLong("created_at"));
        assertEquals(content, event.getString("content"));
        assertEquals(64, event.getString("id").length());
        assertEquals(128, event.getString("sig").length());
        assertEquals(0, event.getJSONArray("tags").length());
    }

    @Test
    public void testEventIdIsDeterministic() throws Exception {
        BigInteger priv = NostrClient.hexToPrivateKey(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        JSONArray tags = new JSONArray();
        String content = "data";
        long createdAt = 1L;

        org.json.JSONObject event1 = NostrClient.buildSignedEvent(
                content, tags, priv, createdAt);
        org.json.JSONObject event2 = NostrClient.buildSignedEvent(
                content, tags, priv, createdAt);

        assertEquals(event1.getString("id"), event2.getString("id"));
        assertEquals(event1.getString("pubkey"), event2.getString("pubkey"));
    }

    @Test
    public void testDifferentKeysProduceDifferentPubkeys() {
        BigInteger priv1 = NostrClient.generatePrivateKey();
        BigInteger priv2 = NostrClient.generatePrivateKey();
        assertFalse(NostrClient.getPublicKeyHex(priv1)
                .equals(NostrClient.getPublicKeyHex(priv2)));
    }

    @Test
    public void testDifferentContentProducesDifferentEventId() throws Exception {
        BigInteger priv = NostrClient.generatePrivateKey();
        JSONArray tags = new JSONArray();
        long createdAt = 1234567890L;

        org.json.JSONObject event1 = NostrClient.buildSignedEvent(
                "content-a", tags, priv, createdAt);
        org.json.JSONObject event2 = NostrClient.buildSignedEvent(
                "content-b", tags, priv, createdAt);

        assertFalse(event1.getString("id").equals(event2.getString("id")));
        assertFalse(event1.getString("sig").equals(event2.getString("sig")));
    }

    @Test
    public void testSignatureLengthIsConstant() throws Exception {
        BigInteger priv = NostrClient.generatePrivateKey();
        JSONArray tags = new JSONArray();

        for (int i = 0; i < 5; i++) {
            org.json.JSONObject event = NostrClient.buildSignedEvent(
                    "test" + i, tags, priv, 1L);
            assertEquals(128, event.getString("sig").length());
        }
    }

    @Test
    public void testPrivateKeyHexIsConsistent() {
        BigInteger priv = NostrClient.generatePrivateKey();
        String hex1 = NostrClient.privateKeyToHex(priv);
        String hex2 = NostrClient.privateKeyToHex(priv);
        assertEquals(hex1, hex2);
        assertEquals(64, hex1.length());
    }

    @Test
    public void testIntegrationPublishAndQueryRoundTrip() throws Exception {
        Assume.assumeTrue("Set NOSTR_INTEGRATION_TEST=true to run",
                "true".equalsIgnoreCase(System.getenv("NOSTR_INTEGRATION_TEST")));

        String relayUrl = System.getenv("NOSTR_TEST_RELAY_URL");
        if (relayUrl == null || relayUrl.trim().isEmpty()) {
            relayUrl = "wss://adskip.1681248.com";
        }

        String md5 = UUID.randomUUID().toString().replace("-", "");
        List<long[]> ads = new ArrayList<>();
        ads.add(new long[]{1000L, 5000L});
        ads.add(new long[]{8000L, 12000L});

        String content = new JSONObject()
                .put("status", "complete")
                .put("ads", new JSONArray()
                        .put(new JSONObject().put("startMs", 1000L).put("endMs", 5000L))
                        .put(new JSONObject().put("startMs", 8000L).put("endMs", 12000L)))
                .put("createdAt", "2026-07-21T00:00:00Z")
                .put("feedUrl", "https://example.com/feed.xml")
                .put("episodeTitle", "Integration Test Episode")
                .toString();

        JSONArray tags = new JSONArray();
        tags.put(new JSONArray().put("d").put(md5));
        tags.put(new JSONArray().put("t").put("antennapod-adskip"));

        BigInteger key = NostrClient.generatePrivateKey();
        JSONObject event = NostrClient.buildSignedEvent(
                content,
                tags,
                key,
                System.currentTimeMillis() / 1000L);

        publishEventToRelay(relayUrl, event);

        List<long[]> pulled = null;
        for (int i = 0; i < 10; i++) {
            pulled = NostrClient.queryAdTimestamps(md5);
            if (pulled != null && pulled.size() == ads.size()) {
                break;
            }
            Thread.sleep(1000L);
        }

        assertNotNull(pulled);
        assertEquals(ads.size(), pulled.size());
        for (int i = 0; i < ads.size(); i++) {
            assertEquals(ads.get(i)[0], pulled.get(i)[0]);
            assertEquals(ads.get(i)[1], pulled.get(i)[1]);
        }
    }

    private static void publishEventToRelay(String relayUrl, JSONObject event) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        JSONArray eventMsg = new JSONArray();
        eventMsg.put("EVENT");
        eventMsg.put(event);
        String eventStr = eventMsg.toString();
        String eventId = event.getString("id");

        CountDownLatch done = new CountDownLatch(1);
        final boolean[] accepted = {false};
        final Throwable[] failure = new Throwable[1];

        Request wsRequest = new Request.Builder().url(relayUrl).build();
        client.newWebSocket(wsRequest, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(eventStr);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONArray msg = new JSONArray(text);
                    if (!"OK".equals(msg.optString(0))) {
                        return;
                    }
                    if (!eventId.equals(msg.optString(1))) {
                        return;
                    }
                    if (msg.optBoolean(2, false)) {
                        accepted[0] = true;
                    }
                    webSocket.close(1000, null);
                    done.countDown();
                } catch (Exception e) {
                    failure[0] = e;
                    done.countDown();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                failure[0] = t;
                done.countDown();
            }
        });

        assertTrue("Timed out waiting for relay OK", done.await(10, TimeUnit.SECONDS));
        if (failure[0] != null) {
            throw new RuntimeException(failure[0]);
        }
        assertTrue("Relay did not acknowledge event", accepted[0]);
    }
}
