package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class AdDetectionPreferences {
    public static final String PREF_AD_DETECTION_ENABLED = "prefAdDetectionEnabled";
    public static final String PREF_AD_TRANSCRIPTION_PROFILES = "prefAdTranscriptionProfiles";
    public static final String PREF_AD_TRANSCRIPTION_ACTIVE_ID = "prefAdTranscriptionActiveId";
    public static final String PREF_AD_CHAT_PROFILES = "prefAdChatProfiles";
    public static final String PREF_AD_CHAT_ACTIVE_ID = "prefAdChatActiveId";

    public static final int ROLE_TRANSCRIPTION = 1;
    public static final int ROLE_CHAT = 2;

    public static final String DEFAULT_CLASSIFICATION_PROMPT =
            "You are an expert at detecting advertisements and sponsor reads in podcast transcripts. "
            + "Your goal is to catch ALL ads — favor false positives over false negatives. "
            + "When unsure, mark it as an ad.\n\n"
            + "IMPORTANT CONTEXT: Podcast ads come in many forms. Be highly suspicious of any segment that:\n"
            + "- Mentions a brand, product, service, or company by name (unless it's clearly the podcast's own topic)\n"
            + "- Contains pricing, discounts, coupon codes, promo codes, or special offers\n"
            + "- Includes calls to action: 'sign up', 'try for free', 'check it out', 'click the link', "
            + "'get 20% off', 'use code', 'visit our website', 'download the app', 'subscribe'\n"
            + "- Contains phrases like 'brought to you by', 'sponsored by', 'this episode is supported by', "
            + "'today's sponsor', 'a word from our sponsor', 'our sponsor', 'in partnership with', "
            + "'supported by', 'brought to you in part by'\n"
            + "- Mentions websites, URLs, or app stores\n"
            + "- The host directly endorses, recommends, or describes a product or service in detail\n"
            + "- Discusses a sale, promotion, limited-time offer, or 'special deal'\n"
            + "- References podcast platforms or ad networks ('available on Spotify', "
            + "'listen on Apple Podcasts', 'wherever you get your podcasts', 'rate and review')\n"
            + "- Mentions other podcasts in a promotional context, especially at the beginning or end\n"
            + "- Topic shifts abruptly away from the main content and later returns\n"
            + "- Contains content resumption phrases ('welcome back', 'now back to', 'let's continue', "
            + "'so anyway', 'enough of that', 'back to the show')\n"
            + "- Describes a service, tool, or platform with marketing-like language "
            + "(positive adjectives, benefit claims, testimonials)\n"
            + "- Contains political endorsements, candidate names, voting appeals\n"
            + "- Mentions affiliate links or partnerships\n\n"
            + "PATTERN RECOGNITION:\n"
            + "- Ads typically have a different tone, pace, or production quality than the main content\n"
            + "- Ad breaks often span MULTIPLE consecutive segments (usually 30-120 seconds total)\n"
            + "- The same ad or sponsor is often mentioned more than once in an episode\n"
            + "- Pre-roll ads (first few minutes) and post-roll ads (last few minutes) are very common\n"
            + "- Host-read ads often start with a conversational pivot before transitioning into promotional language\n"
            + "- Segments under 15 seconds are rarely standalone ads but may be the tail end of one\n\n"
            + "CRITICAL RULES:\n"
            + "1. If ANY segment in a cluster of 2+ consecutive segments has ad signals, "
            + "mark the ENTIRE cluster as an ad break.\n"
            + "2. If two ad breaks are separated by 2 minutes or less of non-ad content, "
            + "it's likely all one long ad break — merge them.\n"
            + "3. Pay special attention to the first 3 minutes and last 3 minutes of the episode — "
            + "this is where ads are most common.\n"
            + "4. A single ad break uses the startMs of the FIRST segment and the endMs of the LAST segment.\n"
            + "5. Make a second pass before finalizing. Look for any ad-like segments you might have missed "
            + "and any breaks that should be merged.\n\n"
            + "IMPORTANT FORMAT NOTES:\n"
            + "- The startMs and endMs values MUST be the actual millisecond timestamps shown in the transcript "
            + "(e.g., if a segment shows '2000-5000', use startMs=2000 and endMs=5000), "
            + "NOT the segment index numbers in brackets.\n"
            + "- Each individual ad break should typically be 10-180 seconds long, "
            + "not the entire episode runtime.\n"
            + "- If you find yourself wanting to mark more than 20% of the episode as ads, "
            + "you are likely over-detecting — recheck and be more conservative.\n"
            + "- If in doubt about a segment, do NOT mark it as an ad. "
            + "Better to miss a marginal ad than to falsely skip podcast content.\n\n"
            + "Return ONLY valid JSON: {\"ads\": [{\"startMs\": <int>, \"endMs\": <int>}, ...]}\n"
            + "If there are no ads return {\"ads\": []}.";

    private static SharedPreferences prefs;

    public static void init(Context context) {
        init(context, "");
    }

    public static void init(Context context, String defaultChatApiKey) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (!prefs.contains(PREF_AD_DETECTION_ENABLED)) {
            prefs.edit().putBoolean(PREF_AD_DETECTION_ENABLED, true).apply();
        }

        AdProviderProfile transcriptionProfile = new AdProviderProfile();
        transcriptionProfile.id = UUID.randomUUID().toString();
        transcriptionProfile.name = "Local Whisper";
        transcriptionProfile.type = AdProviderProfile.TYPE_CUSTOM;
        transcriptionProfile.apiKey = "not-needed-locally";
        transcriptionProfile.baseUrl = "http://192.168.2.58:8001/v1";
        transcriptionProfile.model = "deepdml/faster-whisper-large-v3-turbo-ct2";

        AdProviderProfile chatProfile = new AdProviderProfile();
        chatProfile.id = UUID.randomUUID().toString();
        chatProfile.name = "DeepSeek";
        chatProfile.type = AdProviderProfile.TYPE_CUSTOM;
        chatProfile.apiKey = defaultChatApiKey;
        chatProfile.baseUrl = "https://api.deepseek.com/v1";
        chatProfile.model = "deepseek-chat";
        chatProfile.prompt = DEFAULT_CLASSIFICATION_PROMPT;

        if (getProfiles(ROLE_TRANSCRIPTION).isEmpty()) {
            setProfiles(ROLE_TRANSCRIPTION, List.of(transcriptionProfile));
            setActiveProfileId(ROLE_TRANSCRIPTION, transcriptionProfile.id);
        }
        if (getProfiles(ROLE_CHAT).isEmpty()) {
            setProfiles(ROLE_CHAT, List.of(chatProfile));
            setActiveProfileId(ROLE_CHAT, chatProfile.id);
        }
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
