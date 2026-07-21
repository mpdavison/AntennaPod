package de.danoeh.antennapod.storage.preferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public class AdProviderProfile {
    public static final String TYPE_OPENAI = "openai";
    public static final String TYPE_GROQ = "groq";
    public static final String TYPE_CUSTOM = "custom";
    public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String GROQ_BASE_URL = "https://api.groq.com/openai/v1";

    public String id;
    public String name;
    public String type;
    public String apiKey;
    public String baseUrl;
    public String model;
    public String prompt;

    public AdProviderProfile() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.type = TYPE_OPENAI;
        this.apiKey = "";
        this.baseUrl = "";
        this.model = "";
        this.prompt = "";
    }

    public String getEffectiveBaseUrl() {
        if (TYPE_OPENAI.equals(type)) {
            return OPENAI_BASE_URL;
        }
        if (TYPE_GROQ.equals(type)) {
            return GROQ_BASE_URL;
        }
        return baseUrl == null ? "" : baseUrl;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("type", type);
        o.put("apiKey", apiKey);
        o.put("baseUrl", baseUrl);
        o.put("model", model);
        o.put("prompt", prompt);
        return o;
    }

    public static AdProviderProfile fromJson(JSONObject o) {
        AdProviderProfile p = new AdProviderProfile();
        p.id = o.optString("id", UUID.randomUUID().toString());
        p.name = o.optString("name", "");
        p.type = o.optString("type", TYPE_OPENAI);
        p.apiKey = o.optString("apiKey", "");
        p.baseUrl = o.optString("baseUrl", "");
        p.model = o.optString("model", "");
        p.prompt = o.optString("prompt", "");
        return p;
    }
}
