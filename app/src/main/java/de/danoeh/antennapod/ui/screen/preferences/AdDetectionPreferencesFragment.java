package de.danoeh.antennapod.ui.screen.preferences;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceManager;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;

public class AdDetectionPreferencesFragment extends AnimatedPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_ad_detection);
        setupApiKeyPreference(AdDetectionPreferences.PREF_AD_TRANSCRIPTION_API_KEY);
        setupApiKeyPreference(AdDetectionPreferences.PREF_AD_CHAT_API_KEY);
        updateBaseUrlVisibility(AdDetectionPreferences.PREF_AD_TRANSCRIPTION_PROVIDER,
                AdDetectionPreferences.PREF_AD_TRANSCRIPTION_BASE_URL);
        updateBaseUrlVisibility(AdDetectionPreferences.PREF_AD_CHAT_PROVIDER,
                AdDetectionPreferences.PREF_AD_CHAT_BASE_URL);
        updateApiKeySummary(AdDetectionPreferences.PREF_AD_TRANSCRIPTION_API_KEY);
        updateApiKeySummary(AdDetectionPreferences.PREF_AD_CHAT_API_KEY);
    }

    @Override
    public void onStart() {
        super.onStart();
        ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(R.string.pref_ad_detection_title);
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (AdDetectionPreferences.PREF_AD_TRANSCRIPTION_PROVIDER.equals(key)) {
            updateBaseUrlVisibility(key, AdDetectionPreferences.PREF_AD_TRANSCRIPTION_BASE_URL);
        } else if (AdDetectionPreferences.PREF_AD_CHAT_PROVIDER.equals(key)) {
            updateBaseUrlVisibility(key, AdDetectionPreferences.PREF_AD_CHAT_BASE_URL);
        } else if (AdDetectionPreferences.PREF_AD_TRANSCRIPTION_API_KEY.equals(key)) {
            updateApiKeySummary(key);
        } else if (AdDetectionPreferences.PREF_AD_CHAT_API_KEY.equals(key)) {
            updateApiKeySummary(key);
        }
    }

    private void setupApiKeyPreference(String key) {
        EditTextPreference pref = findPreference(key);
        if (pref != null) {
            pref.setOnBindEditTextListener(editText ->
                    editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        }
    }

    private void updateBaseUrlVisibility(String providerKey, String baseUrlKey) {
        ListPreference providerPref = findPreference(providerKey);
        if (providerPref == null) {
            return;
        }
        String value = providerPref.getValue();
        if (value == null) {
            value = "openai";
        }
        findPreference(baseUrlKey).setVisible("custom".equals(value));
    }

    private void updateApiKeySummary(String key) {
        EditTextPreference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        String value = pref.getText();
        if (value != null && !value.isEmpty()) {
            pref.setSummary(R.string.pref_ad_api_key_set);
        } else {
            pref.setSummary(R.string.pref_ad_api_key_not_set);
        }
    }
}
