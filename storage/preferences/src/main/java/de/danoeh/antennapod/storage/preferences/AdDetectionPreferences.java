package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

public abstract class AdDetectionPreferences {
    public static final String PREF_AD_DETECTION_ENABLED = "prefAdDetectionEnabled";
    public static final String PREF_AD_TRANSCRIPTION_PROVIDER = "prefAdTranscriptionProvider";
    public static final String PREF_AD_TRANSCRIPTION_API_KEY = "prefAdTranscriptionApiKey";
    public static final String PREF_AD_TRANSCRIPTION_BASE_URL = "prefAdTranscriptionBaseUrl";
    public static final String PREF_AD_TRANSCRIPTION_MODEL = "prefAdTranscriptionModel";
    public static final String PREF_AD_CHAT_PROVIDER = "prefAdChatProvider";
    public static final String PREF_AD_CHAT_API_KEY = "prefAdChatApiKey";
    public static final String PREF_AD_CHAT_BASE_URL = "prefAdChatBaseUrl";
    public static final String PREF_AD_CHAT_MODEL = "prefAdChatModel";
    public static final String PREF_AD_CHAT_PROMPT = "prefAdChatPrompt";

    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_GROQ = "groq";
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String GROQ_BASE_URL = "https://api.groq.com/openai/v1";

    private static SharedPreferences prefs;

    public static void init(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isEnabled() {
        return prefs.getBoolean(PREF_AD_DETECTION_ENABLED, false);
    }

    public static String getTranscriptionProvider() {
        return prefs.getString(PREF_AD_TRANSCRIPTION_PROVIDER, PROVIDER_OPENAI);
    }

    public static String getTranscriptionApiKey() {
        return prefs.getString(PREF_AD_TRANSCRIPTION_API_KEY, "").trim();
    }

    public static String getTranscriptionBaseUrl() {
        String provider = getTranscriptionProvider();
        if (PROVIDER_GROQ.equals(provider)) {
            return GROQ_BASE_URL;
        }
        if (PROVIDER_OPENAI.equals(provider)) {
            return OPENAI_BASE_URL;
        }
        return prefs.getString(PREF_AD_TRANSCRIPTION_BASE_URL, "");
    }

    public static String getTranscriptionModel() {
        String model = prefs.getString(PREF_AD_TRANSCRIPTION_MODEL, "");
        if (!model.isEmpty()) {
            return model;
        }
        return PROVIDER_GROQ.equals(getTranscriptionProvider()) ? "whisper-large-v3-turbo" : "whisper-1";
    }

    public static String getChatProvider() {
        return prefs.getString(PREF_AD_CHAT_PROVIDER, PROVIDER_OPENAI);
    }

    public static String getChatApiKey() {
        return prefs.getString(PREF_AD_CHAT_API_KEY, "").trim();
    }

    public static String getChatBaseUrl() {
        String provider = getChatProvider();
        if (PROVIDER_GROQ.equals(provider)) {
            return GROQ_BASE_URL;
        }
        if (PROVIDER_OPENAI.equals(provider)) {
            return OPENAI_BASE_URL;
        }
        return prefs.getString(PREF_AD_CHAT_BASE_URL, "");
    }

    public static String getChatModel() {
        String model = prefs.getString(PREF_AD_CHAT_MODEL, "");
        if (!model.isEmpty()) {
            return model;
        }
        return PROVIDER_GROQ.equals(getChatProvider()) ? "llama-3.3-70b-versatile" : "gpt-4o-mini";
    }

    public static String getChatPrompt() {
        return prefs.getString(PREF_AD_CHAT_PROMPT, "");
    }
}
