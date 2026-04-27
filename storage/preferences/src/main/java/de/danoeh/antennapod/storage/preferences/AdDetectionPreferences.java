package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public abstract class AdDetectionPreferences {
    public static final String PREF_AD_DETECTION_ENABLED = "prefAdDetectionEnabled";
    public static final String PREF_AD_TRANSCRIPTION_PROFILES = "prefAdTranscriptionProfiles";
    public static final String PREF_AD_TRANSCRIPTION_ACTIVE_ID = "prefAdTranscriptionActiveId";
    public static final String PREF_AD_CHAT_PROFILES = "prefAdChatProfiles";
    public static final String PREF_AD_CHAT_ACTIVE_ID = "prefAdChatActiveId";

    public static final int ROLE_TRANSCRIPTION = 1;
    public static final int ROLE_CHAT = 2;

    public static final String DEFAULT_CLASSIFICATION_PROMPT =
            "You are an expert at detecting advertisements and sponsor reads in podcast transcripts.\n\n"
            + "Advertisements often span several consecutive segments — a single ad break typically runs "
            + "30\u2013120 seconds. Signals of an ad include:\n"
            + "- In the beginning of a podcast episode, they often have ads for /other/ podcasts.\n"
            + "- Many ads are played more than once throughout a podcast\n"
            + "- Phrases like 'brought to you by', 'sponsored by', 'this episode is supported by', "
            + "'today's sponsor', 'a word from our sponsor'\n"
            + "- Brand names, product descriptions, pricing, discount codes (e.g. 'use code XYZ')\n"
            + "- Website, sale or app mentions (e.g. 'blowout sale', 'go to brand.com', 'download the app')\n"
            + "- Calls to action: 'sign up', 'try for free', 'check it out', 'click the link', 'get 20% off'\n"
            + "- The host directly endorsing or describing a product or service\n"
            + "- Topic suddenly shifting away from the main content and then returning\n"
            + "- Mentions of other podcasts, especially in a promotional context\n"
            + "- References to podcast platforms or ad networks (e.g. 'available on Spotify', "
            + "'listen on Apple Podcasts', 'wherever you get your podcasts')\n"
            + "- Look out for podcast content resumption phrases (e.g. 'welcome back') to help identify where ads end\n"
            + "- Political ads often mention candidates, parties, voting, elections, or political issues\n\n"
            + "IMPORTANT: A single ad break is usually spread across MULTIPLE consecutive segments. "
            + "Always use the startMs of the FIRST segment of the ad break and the endMs of the LAST segment "
            + "of the same break. It is very unlikely that an ad segment is less than 15 seconds.\n\n"
            + "EXTRA CRITICALLY IMPORTANT: Make a second pass before returning the output. If any ads are "
            + "close together but separated by a non-ad segment (like one minute or less of non-ad time "
            + "between the end of one ad and the start of the next), then it is likely that the time in "
            + "between the ads is really just more ad content, so mark that as ad content, too.\n\n"
            + "Return a JSON object: {\"ads\": [{\"startMs\": <int>, \"endMs\": <int>}, ...]}\n"
            + "If there are no ads return {\"ads\": []}.";

    private static SharedPreferences prefs;

    public static void init(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isEnabled() {
        return prefs.getBoolean(PREF_AD_DETECTION_ENABLED, false);
    }

    public static List<AdProviderProfile> getProfiles(int role) {
        List<AdProviderProfile> result = new ArrayList<>();
        String json = prefs.getString(profilesKey(role), "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                result.add(AdProviderProfile.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException ignore) {
            // return empty
        }
        return result;
    }

    public static void setProfiles(int role, List<AdProviderProfile> profiles) {
        JSONArray arr = new JSONArray();
        try {
            for (AdProviderProfile p : profiles) {
                arr.put(p.toJson());
            }
        } catch (JSONException ignore) {
            // skip
        }
        prefs.edit().putString(profilesKey(role), arr.toString()).apply();
    }

    public static String getActiveProfileId(int role) {
        return prefs.getString(activeIdKey(role), "");
    }

    public static void setActiveProfileId(int role, String id) {
        prefs.edit().putString(activeIdKey(role), id == null ? "" : id).apply();
    }

    public static AdProviderProfile getActiveProfile(int role) {
        String activeId = getActiveProfileId(role);
        List<AdProviderProfile> profiles = getProfiles(role);
        for (AdProviderProfile p : profiles) {
            if (p.id.equals(activeId)) {
                return p;
            }
        }
        if (!profiles.isEmpty()) {
            return profiles.get(0);
        }
        return null;
    }

    public static void upsertProfile(int role, AdProviderProfile profile) {
        List<AdProviderProfile> profiles = getProfiles(role);
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(profile.id)) {
                profiles.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            profiles.add(profile);
        }
        setProfiles(role, profiles);
    }

    public static void deleteProfile(int role, String id) {
        List<AdProviderProfile> profiles = getProfiles(role);
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(id)) {
                profiles.remove(i);
                break;
            }
        }
        setProfiles(role, profiles);
        if (id != null && id.equals(getActiveProfileId(role))) {
            setActiveProfileId(role, profiles.isEmpty() ? "" : profiles.get(0).id);
        }
    }

    public static String getTranscriptionApiKey() {
        AdProviderProfile p = getActiveProfile(ROLE_TRANSCRIPTION);
        return p == null ? "" : p.apiKey.trim();
    }

    public static String getTranscriptionBaseUrl() {
        AdProviderProfile p = getActiveProfile(ROLE_TRANSCRIPTION);
        return p == null ? "" : p.getEffectiveBaseUrl();
    }

    public static String getTranscriptionModel() {
        AdProviderProfile p = getActiveProfile(ROLE_TRANSCRIPTION);
        if (p == null) {
            return "whisper-1";
        }
        if (p.model != null && !p.model.isEmpty()) {
            return p.model;
        }
        return AdProviderProfile.TYPE_GROQ.equals(p.type) ? "whisper-large-v3-turbo" : "whisper-1";
    }

    public static String getChatApiKey() {
        AdProviderProfile p = getActiveProfile(ROLE_CHAT);
        return p == null ? "" : p.apiKey.trim();
    }

    public static String getChatBaseUrl() {
        AdProviderProfile p = getActiveProfile(ROLE_CHAT);
        return p == null ? "" : p.getEffectiveBaseUrl();
    }

    public static String getChatModel() {
        AdProviderProfile p = getActiveProfile(ROLE_CHAT);
        if (p == null) {
            return "gpt-4o-mini";
        }
        if (p.model != null && !p.model.isEmpty()) {
            return p.model;
        }
        return AdProviderProfile.TYPE_GROQ.equals(p.type) ? "llama-3.3-70b-versatile" : "gpt-4o-mini";
    }

    public static String getChatPrompt() {
        AdProviderProfile p = getActiveProfile(ROLE_CHAT);
        return p == null ? "" : (p.prompt == null ? "" : p.prompt);
    }

    private static String profilesKey(int role) {
        return role == ROLE_TRANSCRIPTION ? PREF_AD_TRANSCRIPTION_PROFILES : PREF_AD_CHAT_PROFILES;
    }

    private static String activeIdKey(int role) {
        return role == ROLE_TRANSCRIPTION ? PREF_AD_TRANSCRIPTION_ACTIVE_ID : PREF_AD_CHAT_ACTIVE_ID;
    }
}
