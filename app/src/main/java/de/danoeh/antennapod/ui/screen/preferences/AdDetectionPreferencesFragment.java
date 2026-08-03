package de.danoeh.antennapod.ui.screen.preferences;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;
import de.danoeh.antennapod.storage.preferences.AdProviderProfile;
import de.danoeh.antennapod.ui.preferences.screen.AnimatedPreferenceFragment;

public class AdDetectionPreferencesFragment extends AnimatedPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "AdDetectionPrefs";
    private static final String KEY_TRANSCRIPTION_PICKER = "prefAdTranscriptionActiveProfile";
    private static final String KEY_CHAT_PICKER = "prefAdChatActiveProfile";
    private static final String KEY_TRANSCRIPTION_MANAGE = "prefAdTranscriptionManage";
    private static final String KEY_CHAT_MANAGE = "prefAdChatManage";
    private static final String KEY_SKIP_SOUND_MODE = "prefAdSkipSoundMode";
    private static final String KEY_SKIP_SOUND_CUSTOM = "prefAdSkipSoundCustom";

    private final ActivityResultLauncher<Intent> chooseSkipSoundLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    return;
                }
                Uri uri = result.getData().getData();
                if (uri == null) {
                    return;
                }
                try {
                    getContext().getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.d(TAG, "Could not take persistable permission for skip sound", e);
                }
                AdDetectionPreferences.setSkipSoundCustomUri(uri.toString());
                updateCustomSoundSummary();
            });

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

        ListPreference skipSoundMode = findPreference(KEY_SKIP_SOUND_MODE);
        skipSoundMode.setOnPreferenceChangeListener((p, value) -> {
            updateSkipSoundCustomVisibility((String) value);
            ListPreference pref = (ListPreference) p;
            pref.setSummary(pref.getEntries()[indexOf(pref.getEntryValues(), (String) value)]);
            return true;
        });
        findPreference(KEY_SKIP_SOUND_CUSTOM).setOnPreferenceClickListener(p -> {
            openSkipSoundPicker();
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
        if (KEY_SKIP_SOUND_MODE.equals(key)) {
            String mode = sharedPreferences.getString(key, AdDetectionPreferences.SKIP_SOUND_BEEP);
            ListPreference skipSoundMode = findPreference(KEY_SKIP_SOUND_MODE);
            skipSoundMode.setSummary(skipSoundMode.getEntry());
            updateSkipSoundCustomVisibility(mode);
        }
    }

    private void refresh() {
        populatePicker(findPreference(KEY_TRANSCRIPTION_PICKER), AdDetectionPreferences.ROLE_TRANSCRIPTION);
        populatePicker(findPreference(KEY_CHAT_PICKER), AdDetectionPreferences.ROLE_CHAT);
        ListPreference skipSoundMode = findPreference(KEY_SKIP_SOUND_MODE);
        skipSoundMode.setValue(AdDetectionPreferences.getSkipSoundMode());
        skipSoundMode.setSummary(skipSoundMode.getEntry());
        updateSkipSoundCustomVisibility(AdDetectionPreferences.getSkipSoundMode());
        updateCustomSoundSummary();
    }

    private void updateSkipSoundCustomVisibility(String mode) {
        findPreference(KEY_SKIP_SOUND_CUSTOM).setVisible(
                AdDetectionPreferences.SKIP_SOUND_CUSTOM.equals(mode));
    }

    private void updateCustomSoundSummary() {
        Preference custom = findPreference(KEY_SKIP_SOUND_CUSTOM);
        String uri = AdDetectionPreferences.getSkipSoundCustomUri();
        if (uri == null) {
            custom.setSummary(R.string.pref_ad_skip_sound_custom_sum);
        } else {
            custom.setSummary(queryDisplayName(Uri.parse(uri)));
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContext().getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not read skip sound display name", e);
        }
        return getString(R.string.pref_ad_skip_sound_custom_sum);
    }

    private void openSkipSoundPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        try {
            chooseSkipSoundLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Snackbar.make(getView(), R.string.unable_to_start_system_file_manager, Snackbar.LENGTH_LONG)
                    .show();
        }
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
