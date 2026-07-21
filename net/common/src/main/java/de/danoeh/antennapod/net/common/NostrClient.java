package de.danoeh.antennapod.net.common;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.JsonWriter;
import android.util.Log;

import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.math.ec.ECPoint;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class NostrClient {
    private static final String TAG = "NostrClient";
    private static final int EVENT_KIND = 31337;
    private static final int RELAY_TIMEOUT_MS = 4000;

    static final String[] RELAYS = {
        "wss://adskip.1681248.com",
        "wss://relay.damus.io",
        "wss://relay.primal.net"
    };

    private static final X9ECParameters CURVE;
    private static final BigInteger N;
    private static final ECPoint G;

    static {
        CURVE = ECNamedCurveTable.getByName("secp256k1");
        N = CURVE.getN();
        G = CURVE.getG();
    }

    private NostrClient() {
    }

    public static BigInteger generatePrivateKey() {
        byte[] bytes = new byte[32];
        SecureRandom random = new SecureRandom();
        BigInteger d;
        do {
            random.nextBytes(bytes);
            d = new BigInteger(1, bytes);
        } while (d.equals(BigInteger.ZERO) || d.compareTo(N) >= 0);
        return d;
    }

    public static String privateKeyToHex(BigInteger d) {
        return bytesToHex(bigIntToBytes(d, 32));
    }

    public static BigInteger hexToPrivateKey(String hex) {
        return new BigInteger(1, hexToBytes(hex));
    }

    public static String getPublicKeyHex(BigInteger privateKey) {
        return bytesToHex(bigIntToBytes(
                G.multiply(privateKey).normalize().getAffineXCoord().toBigInteger(), 32));
    }

    public static String signEvent(byte[] serialized, BigInteger privateKey) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] eventId = sha256.digest(serialized);
            byte[] sig = schnorrSign(eventId, privateKey);
            return bytesToHex(sig);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] schnorrSign(byte[] message, BigInteger d) {
        try {
            ECPoint pubPoint = G.multiply(d).normalize();
            if (pubPoint.getAffineYCoord().toBigInteger().testBit(0)) {
                d = N.subtract(d);
            }

            byte[] auxRand = new byte[32];
            new SecureRandom().nextBytes(auxRand);

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] derivedBytes = bigIntToBytes(d, 32);

            byte[] tagHash = sha256.digest(
                    "BIP0340/nonce".getBytes(StandardCharsets.UTF_8));
            sha256.reset();
            sha256.update(tagHash);
            sha256.update(tagHash);
            sha256.update(derivedBytes);
            sha256.update(message);
            sha256.update(auxRand);
            BigInteger k = new BigInteger(1, sha256.digest()).mod(N);
            if (k.equals(BigInteger.ZERO)) {
                k = BigInteger.ONE;
            }

            ECPoint restoredPoint = G.multiply(k).normalize();
            BigInteger rx = restoredPoint.getAffineXCoord().toBigInteger();
            if (restoredPoint.getAffineYCoord().toBigInteger().testBit(0)) {
                k = N.subtract(k);
            }

            BigInteger pubX = G.multiply(d).normalize().getAffineXCoord().toBigInteger();
            final byte[] rxBytes = bigIntToBytes(rx, 32);
            final byte[] pxBytes = bigIntToBytes(pubX, 32);

            tagHash = sha256.digest(
                    "BIP0340/challenge".getBytes(StandardCharsets.UTF_8));
            sha256.reset();
            sha256.update(tagHash);
            sha256.update(tagHash);
            sha256.update(rxBytes);
            sha256.update(pxBytes);
            sha256.update(message);
            BigInteger e = new BigInteger(1, sha256.digest()).mod(N);

            BigInteger s = k.add(e.multiply(d)).mod(N);

            byte[] sig = new byte[64];
            System.arraycopy(rxBytes, 0, sig, 0, 32);
            System.arraycopy(bigIntToBytes(s, 32), 0, sig, 32, 32);
            return sig;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static JSONObject buildSignedEvent(String content, JSONArray tags,
            BigInteger privateKey, long createdAt) {
        try {
            String pubkeyHex = getPublicKeyHex(privateKey);

            StringWriter sw = new StringWriter();
            JsonWriter jw = new JsonWriter(sw);
            jw.beginArray();
            jw.value(0L);
            jw.value(pubkeyHex);
            jw.value(createdAt);
            jw.value(EVENT_KIND);
            jw.beginArray();
            for (int i = 0; i < tags.length(); i++) {
                JSONArray tag = tags.getJSONArray(i);
                jw.beginArray();
                jw.value(tag.getString(0));
                jw.value(tag.getString(1));
                jw.endArray();
            }
            jw.endArray();
            jw.value(content);
            jw.endArray();
            jw.close();

            String serializedStr = sw.toString();
            byte[] serializedBytes = serializedStr.getBytes(StandardCharsets.UTF_8);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(serializedBytes);
            String eventId = bytesToHex(hash);
            String sig = signEvent(serializedBytes, privateKey);

            Log.i(TAG, "SIGN: id=" + eventId.substring(0, 16) + "..."
                    + " pk=" + pubkeyHex.substring(0, 12) + "..."
                    + " sig=" + sig.substring(0, 16) + "..."
                    + " priv=" + privateKeyToHex(privateKey).substring(0, 12) + "..."
                    + " raw=" + serializedStr.substring(0,
                            Math.min(300, serializedStr.length())));

            JSONObject event = new JSONObject();
            event.put("id", eventId);
            event.put("pubkey", pubkeyHex);
            event.put("created_at", createdAt);
            event.put("kind", EVENT_KIND);
            event.put("tags", tags);
            event.put("content", content);
            event.put("sig", sig);
            return event;
        } catch (IOException | NoSuchAlgorithmException | JSONException e) {
            throw new RuntimeException("Failed to build Nostr event", e);
        }
    }

    public static List<long[]> queryAdTimestamps(String md5) {
        try {
            JSONObject filter = new JSONObject();
            filter.put("kinds", new JSONArray().put(EVENT_KIND));
            filter.put("#d", new JSONArray().put(md5));

            JSONArray req = new JSONArray();
            req.put("REQ");
            req.put("antennapod-query");
            req.put(filter);

            final List<long[]> resultHolder = new ArrayList<>();
            final CountDownLatch doneLatch = new CountDownLatch(1);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();

            for (final String relayUrl : RELAYS) {
                final String subId = "antennapod-" + md5.substring(0, 8);
                JSONArray reqMsg = new JSONArray();
                reqMsg.put("REQ");
                reqMsg.put(subId);
                reqMsg.put(filter);
                final String reqStr = reqMsg.toString();

                Request wsRequest = new Request.Builder().url(relayUrl).build();
                client.newWebSocket(wsRequest, new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                        Log.i(TAG, "QUERY opened to " + relayUrl);
                        webSocket.send(reqStr);
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                        Log.i(TAG, "QUERY msg from " + relayUrl + ": " + text);
                        try {
                            JSONArray msg = new JSONArray(text);
                            String type = msg.optString(0, "");
                            if ("EVENT".equals(type) && resultHolder.isEmpty()) {
                                JSONObject event = msg.getJSONObject(2);
                                List<long[]> segs = parseAdContent(
                                        event.getString("content"));
                                if (segs != null) {
                                    Log.i(TAG, "QUERY hit on " + relayUrl
                                            + ": " + segs.size() + " ad(s)");
                                    resultHolder.addAll(segs);
                                    doneLatch.countDown();
                                }
                            } else if ("EOSE".equals(type)) {
                                Log.i(TAG, "QUERY eose from " + relayUrl);
                                if (resultHolder.isEmpty()) {
                                    doneLatch.countDown();
                                }
                            }
                        } catch (JSONException e) {
                            Log.d(TAG, "Bad relay message", e);
                        }
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t,
                            Response response) {
                        String msg = t != null ? t.toString() : "null";
                        int code = response != null ? response.code() : -1;
                        Log.w(TAG, "QUERY fail to " + relayUrl + ": " + msg
                                + " http=" + code);
                        if (doneLatch.getCount() > 0) {
                            doneLatch.countDown();
                        }
                    }
                });
            }

            doneLatch.await(RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return resultHolder.isEmpty() ? null : new ArrayList<>(resultHolder);
        } catch (Exception e) {
            Log.w(TAG, "Failed to query Nostr relays", e);
            return null;
        }
    }

    public static void publishAdTimestamps(String md5, List<long[]> ads,
            String feedUrl, String episodeTitle, BigInteger privateKey) {
        try {
            String content = buildAdContent(ads, feedUrl, episodeTitle);

            JSONArray tags = new JSONArray();
            tags.put(new JSONArray().put("d").put(md5));
            tags.put(new JSONArray().put("t").put("antennapod-adskip"));

            long createdAt = System.currentTimeMillis() / 1000;
            JSONObject event = buildSignedEvent(content, tags, privateKey, createdAt);

            JSONArray eventMsg = new JSONArray();
            eventMsg.put("EVENT");
            eventMsg.put(event);
            String eventStr = eventMsg.toString();

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();

            for (String relayUrl : RELAYS) {
                final boolean[] accepted = {false};
                Request wsRequest = new Request.Builder().url(relayUrl).build();
                client.newWebSocket(wsRequest, new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                        Log.i(TAG, "SEND opened to " + relayUrl);
                        webSocket.send(eventStr);
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                        Log.i(TAG, "SEND reply from " + relayUrl + ": " + text);
                        try {
                            JSONArray msg = new JSONArray(text);
                            if ("OK".equals(msg.optString(0))) {
                                accepted[0] = true;
                                webSocket.close(1000, null);
                            }
                        } catch (JSONException e) {
                            Log.d(TAG, "Bad relay message", e);
                        }
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t,
                            Response response) {
                        if (accepted[0]) {
                            return;
                        }
                        String msg = t != null ? t.toString() : "null";
                        int code = response != null ? response.code() : -1;
                        Log.w(TAG, "SEND fail to " + relayUrl + ": " + msg
                                + " http=" + code);
                    }
                });
            }
            Log.i(TAG, "Published ad timestamps to Nostr relays: " + md5);
        } catch (Exception e) {
            Log.w(TAG, "Failed to publish ad timestamps to Nostr", e);
        }
    }

    private static String buildAdContent(List<long[]> ads, String feedUrl,
            String episodeTitle) {
        try {
            JSONArray adsArray = new JSONArray();
            for (long[] ad : ads) {
                JSONObject adObj = new JSONObject();
                adObj.put("startMs", ad[0]);
                adObj.put("endMs", ad[1]);
                adsArray.put(adObj);
            }
            JSONObject content = new JSONObject();
            content.put("status", "complete");
            content.put("ads", adsArray);
            SimpleDateFormat isoFormat = new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            content.put("createdAt", isoFormat.format(new Date()));
            if (feedUrl != null && !feedUrl.isEmpty()) {
                content.put("feedUrl", feedUrl);
            }
            if (episodeTitle != null && !episodeTitle.isEmpty()) {
                content.put("episodeTitle", episodeTitle);
            }
            return content.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    private static List<long[]> parseAdContent(String content) {
        try {
            JSONObject root = new JSONObject(content);
            String status = root.optString("status", "");
            if (!"complete".equals(status)) {
                return null;
            }
            JSONArray ads = root.optJSONArray("ads");
            if (ads == null || ads.length() == 0) {
                return new ArrayList<>();
            }
            List<long[]> segments = new ArrayList<>();
            for (int i = 0; i < ads.length(); i++) {
                JSONObject ad = ads.getJSONObject(i);
                segments.add(new long[]{ad.getLong("startMs"),
                        ad.getLong("endMs")});
            }
            return segments;
        } catch (JSONException e) {
            return null;
        }
    }

    private static final int MD5_BUFFER_SIZE = 8192;

    public static String computeAudioMd5(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audioTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    break;
                }
            }
            if (audioTrack < 0) {
                throw new IOException("No audio track found in file: "
                        + file.getName());
            }
            extractor.selectTrack(audioTrack);
            MessageDigest md = MessageDigest.getInstance("MD5");
            ByteBuffer buffer = ByteBuffer.allocate(MD5_BUFFER_SIZE);
            while (true) {
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                md.update(buffer.array(), 0, size);
                buffer.clear();
                extractor.advance();
            }
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 not available", e);
        } finally {
            extractor.release();
        }
    }

    private static byte[] bigIntToBytes(BigInteger value, int len) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > len) {
            byte[] trimmed = new byte[len];
            System.arraycopy(bytes, bytes.length - len, trimmed, 0, len);
            return trimmed;
        } else if (bytes.length < len) {
            byte[] padded = new byte[len];
            System.arraycopy(bytes, 0, padded, len - bytes.length, bytes.length);
            return padded;
        }
        return bytes;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
