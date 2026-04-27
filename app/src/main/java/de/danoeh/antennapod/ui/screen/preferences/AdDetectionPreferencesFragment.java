package de.danoeh.antennapod.ui.screen.preferences;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;
import de.danoeh.antennapod.storage.preferences.AdProviderProfile;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;

public class AdDetectionPreferencesFragment extends AnimatedPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String KEY_TRANSCRIPTION_PICKER = "prefAdTranscriptionActiveProfile";
    private static final String KEY_CHAT_PICKER = "prefAdChatActiveProfile";
    private static final String KEY_TRANSCRIPTION_MANAGE = "prefAdTranscriptionManage";
    private static final String KEY_CHAT_MANAGE = "prefAdChatManage";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_ad_detection);

        findPreference(KEY_TRANSCRIPTION_MANAGE).setOnPreferenceClickListener(p -> {
            openManager(AdDetectionPreferences.ROLE_TRANSCRIPTION);
            return true;
        });
        findPreference(KEY_CHAT_MANAGE).setOnPreferenceClickListener(p -> {
            openManager(AdDetectionPreferences.ROLE_CHAT);
            return true;
        });

        ListPreference transcriptionPicker = findPreference(KEY_TRANSCRIPTION_PICKER);
        transcriptionPicker.setOnPreferenceChangeListener((p, value) -> {
            AdDetectionPreferences.setActiveProfileId(
                    AdDetectionPreferences.ROLE_TRANSCRIPTION, (String) value);
            populatePicker(transcriptionPicker, AdDetectionPreferences.ROLE_TRANSCRIPTION);
            return true;
        });
        ListPreference chatPicker = findPreference(KEY_CHAT_PICKER);
        chatPicker.setOnPreferenceChangeListener((p, value) -> {
            AdDetectionPreferences.setActiveProfileId(
                    AdDetectionPreferences.ROLE_CHAT, (String) value);
            populatePicker(chatPicker, AdDetectionPreferences.ROLE_CHAT);
            return true;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(R.string.pref_ad_detection_title);
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    @Override
    public void onStop() {
        super.onStop();
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // active id changes are handled directly; nothing to do here
    }

    private void refresh() {
        populatePicker(findPreference(KEY_TRANSCRIPTION_PICKER), AdDetectionPreferences.ROLE_TRANSCRIPTION);
        populatePicker(findPreference(KEY_CHAT_PICKER), AdDetectionPreferences.ROLE_CHAT);
    }

    private void populatePicker(ListPreference picker, int role) {
        List<AdProviderProfile> profiles = AdDetectionPreferences.getProfiles(role);
        if (profiles.isEmpty()) {
            picker.setEntries(new CharSequence[]{getString(R.string.pref_ad_profiles_empty)});
            picker.setEntryValues(new CharSequence[]{""});
            picker.setValue("");
            picker.setSummary(R.string.pref_ad_profiles_empty);
            picker.setEnabled(false);
            return;
        }
        picker.setEnabled(true);
        CharSequence[] entries = new CharSequence[profiles.size()];
        CharSequence[] values = new CharSequence[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            AdProviderProfile p = profiles.get(i);
            entries[i] = (p.name == null || p.name.isEmpty())
                    ? getString(R.string.pref_ad_profile_unnamed) : p.name;
            values[i] = p.id;
        }
        picker.setEntries(entries);
        picker.setEntryValues(values);
        AdProviderProfile active = AdDetectionPreferences.getActiveProfile(role);
        if (active != null) {
            picker.setValue(active.id);
            picker.setSummary(entries[indexOf(values, active.id)]);
        } else {
            picker.setValue(values[0].toString());
            picker.setSummary(entries[0]);
        }
    }

    private int indexOf(CharSequence[] arr, String target) {
        for (int i = 0; i < arr.length; i++) {
            if (target.equals(arr[i].toString())) {
                return i;
            }
        }
        return 0;
    }

    private void openManager(int role) {
        Fragment fragment = AdProviderProfileListFragment.newInstance(role);
        ((PreferenceActivity) requireActivity()).openCustomFragment(fragment);
    }
}
