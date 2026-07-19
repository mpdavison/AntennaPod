package de.danoeh.antennapod.net.common;

import android.content.Context;
import android.content.SharedPreferences;

import java.math.BigInteger;

public abstract class NostrPreferences {
    private static final String PREFS_NAME = "nostr";
    private static final String KEY_PRIVATE_KEY = "privateKey";
    private static final String KEY_PUBLIC_KEY = "publicKey";

    private static SharedPreferences prefs;

    private NostrPreferences() {
    }

    public static void init(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean hasKeyPair() {
        return prefs != null && prefs.contains(KEY_PRIVATE_KEY);
    }

    public static BigInteger getPrivateKey() {
        if (prefs == null) {
            return null;
        }
        String hex = prefs.getString(KEY_PRIVATE_KEY, null);
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        return NostrClient.hexToPrivateKey(hex);
    }

    public static void setKeyPair(BigInteger privateKey) {
        if (prefs != null) {
            prefs.edit()
                    .putString(KEY_PRIVATE_KEY, NostrClient.privateKeyToHex(privateKey))
                    .putString(KEY_PUBLIC_KEY, NostrClient.getPublicKeyHex(privateKey))
                    .apply();
        }
    }

    public static String getPublicKeyHex() {
        if (prefs == null) {
            return null;
        }
        return prefs.getString(KEY_PUBLIC_KEY, null);
    }
}
