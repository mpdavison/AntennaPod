package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.Charset;

import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;

/**
 * Exports and imports the ad-detection related preferences as a JSON document
 * that the user explicitly chooses to write or read.
 */
public class AdSkipPreferencesTransporter {
    private static final String TAG = "AdSkipPrefsTransporter";

    private static final String[] STRING_KEYS = {
            AdDetectionPreferences.PREF_AD_TRANSCRIPTION_PROFILES,
            AdDetectionPreferences.PREF_AD_TRANSCRIPTION_ACTIVE_ID,
            AdDetectionPreferences.PREF_AD_CHAT_PROFILES,
            AdDetectionPreferences.PREF_AD_CHAT_ACTIVE_ID
    };

    private static final String[] BOOLEAN_KEYS = {
            AdDetectionPreferences.PREF_AD_DETECTION_ENABLED
    };

    public static void writeDocument(Writer writer, Context context) throws IOException {
        writeJson(writer, PreferenceManager.getDefaultSharedPreferences(context));
    }

    public static void importBackup(Uri uri, Context context) throws IOException {
        byte[] bytes;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Unable to open file for reading");
            }
            bytes = IOUtils.toByteArray(in);
        }
        restoreJson(new String(bytes, Charset.forName("UTF-8")),
                PreferenceManager.getDefaultSharedPreferences(context));
    }

    static void writeJson(Writer writer, SharedPreferences prefs) throws IOException {
        JSONObject json = new JSONObject();
        try {
            for (String k : STRING_KEYS) {
                if (prefs.contains(k)) {
                    json.put(k, prefs.getString(k, ""));
                }
            }
            for (String k : BOOLEAN_KEYS) {
                if (prefs.contains(k)) {
                    json.put(k, prefs.getBoolean(k, false));
                }
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        writer.write(json.toString());
    }

    static void restoreJson(String content, SharedPreferences prefs) throws IOException {
        try {
            JSONObject json = new JSONObject(content);
            SharedPreferences.Editor editor = prefs.edit();
            for (String k : STRING_KEYS) {
                if (json.has(k)) {
                    editor.putString(k, json.optString(k, ""));
                }
            }
            for (String k : BOOLEAN_KEYS) {
                if (json.has(k)) {
                    editor.putBoolean(k, json.optBoolean(k, false));
                }
            }
            editor.apply();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse ad-skip preferences file", e);
            throw new IOException(e);
        }
    }
}
