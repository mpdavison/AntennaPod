package de.danoeh.antennapod.ui.screen.preferences;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;
import de.danoeh.antennapod.storage.preferences.AdProviderProfile;

public class AdProviderProfileEditorFragment extends Fragment {
    public static final String ARG_ROLE = "role";
    public static final String ARG_PROFILE_ID = "profileId";

    private int role;
    private AdProviderProfile profile;
    private boolean isNew;

    private MaterialSwitch activeSwitch;
    private TextInputEditText nameInput;
    private AutoCompleteTextView typeInput;
    private TextInputLayout baseUrlLayout;
    private TextInputEditText baseUrlInput;
    private TextInputEditText apiKeyInput;
    private TextInputEditText modelInput;
    private TextInputLayout promptLayout;
    private TextInputEditText promptInput;
    private MaterialButton deleteButton;
    private MaterialButton saveButton;

    private String[] typeValues;
    private String[] typeLabels;

    public static AdProviderProfileEditorFragment newInstance(int role, @Nullable String profileId) {
        AdProviderProfileEditorFragment f = new AdProviderProfileEditorFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_ROLE, role);
        if (profileId != null) {
            b.putString(ARG_PROFILE_ID, profileId);
        }
        f.setArguments(b);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        role = args != null ? args.getInt(ARG_ROLE, AdDetectionPreferences.ROLE_TRANSCRIPTION)
                : AdDetectionPreferences.ROLE_TRANSCRIPTION;
        String id = args != null ? args.getString(ARG_PROFILE_ID) : null;
        if (id != null) {
            for (AdProviderProfile p : AdDetectionPreferences.getProfiles(role)) {
                if (p.id.equals(id)) {
                    profile = p;
                    break;
                }
            }
        }
        if (profile == null) {
            profile = new AdProviderProfile();
            isNew = true;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.ad_provider_profile_editor_fragment, container, false);

        activeSwitch = view.findViewById(R.id.activeSwitch);
        nameInput = view.findViewById(R.id.nameInput);
        typeInput = view.findViewById(R.id.typeInput);
        baseUrlLayout = view.findViewById(R.id.baseUrlLayout);
        baseUrlInput = view.findViewById(R.id.baseUrlInput);
        apiKeyInput = view.findViewById(R.id.apiKeyInput);
        modelInput = view.findViewById(R.id.modelInput);
        promptLayout = view.findViewById(R.id.promptLayout);
        promptInput = view.findViewById(R.id.promptInput);
        deleteButton = view.findViewById(R.id.deleteButton);
        saveButton = view.findViewById(R.id.saveButton);

        typeValues = getResources().getStringArray(R.array.pref_ad_provider_values);
        typeLabels = getResources().getStringArray(R.array.pref_ad_provider_entries);
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, typeLabels);
        typeInput.setAdapter(typeAdapter);
        typeInput.setOnItemClickListener((parent, v, pos, id) -> {
            profile.type = typeValues[pos];
            updateBaseUrlVisibility();
        });

        nameInput.setText(profile.name);
        baseUrlInput.setText(profile.baseUrl);
        apiKeyInput.setText(profile.apiKey);
        modelInput.setText(profile.model);
        String promptText = (profile.prompt == null || profile.prompt.isEmpty()) && isNew
                && role == AdDetectionPreferences.ROLE_CHAT
                ? AdDetectionPreferences.DEFAULT_CLASSIFICATION_PROMPT : profile.prompt;
        promptInput.setText(promptText);
        typeInput.setText(labelForType(profile.type), false);
        updateBaseUrlVisibility();

        promptLayout.setVisibility(role == AdDetectionPreferences.ROLE_CHAT ? View.VISIBLE : View.GONE);

        boolean isActive = profile.id.equals(AdDetectionPreferences.getActiveProfileId(role));
        if (isNew && AdDetectionPreferences.getProfiles(role).isEmpty()) {
            isActive = true;
        }
        activeSwitch.setChecked(isActive);

        deleteButton.setVisibility(isNew ? View.GONE : View.VISIBLE);
        deleteButton.setOnClickListener(v -> {
            AdDetectionPreferences.deleteProfile(role, profile.id);
            requireActivity().getSupportFragmentManager().popBackStack();
        });

        saveButton.setOnClickListener(v -> save());

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getActivity() instanceof PreferenceActivity) {
            int titleRes = isNew
                    ? R.string.pref_ad_profile_add
                    : R.string.pref_ad_profile_edit;
            ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(titleRes);
        }
    }

    private void save() {
        profile.name = textOf(nameInput);
        profile.baseUrl = textOf(baseUrlInput);
        profile.apiKey = textOf(apiKeyInput);
        profile.model = textOf(modelInput);
        profile.prompt = textOf(promptInput);
        // type already updated via item click; ensure default if untouched
        if (profile.type == null || profile.type.isEmpty()) {
            profile.type = AdProviderProfile.TYPE_OPENAI;
        }
        AdDetectionPreferences.upsertProfile(role, profile);
        if (activeSwitch.isChecked()) {
            AdDetectionPreferences.setActiveProfileId(role, profile.id);
        } else if (profile.id.equals(AdDetectionPreferences.getActiveProfileId(role))) {
            // user un-checked active for the previously active profile
            List<AdProviderProfile> others = new ArrayList<>(AdDetectionPreferences.getProfiles(role));
            String newActive = "";
            for (AdProviderProfile p : others) {
                if (!p.id.equals(profile.id)) {
                    newActive = p.id;
                    break;
                }
            }
            AdDetectionPreferences.setActiveProfileId(role, newActive);
        }
        requireActivity().getSupportFragmentManager().popBackStack();
    }

    private void updateBaseUrlVisibility() {
        boolean custom = AdProviderProfile.TYPE_CUSTOM.equals(profile.type);
        baseUrlLayout.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    private String labelForType(String type) {
        for (int i = 0; i < typeValues.length; i++) {
            if (typeValues[i].equals(type)) {
                return typeLabels[i];
            }
        }
        return typeLabels.length > 0 ? typeLabels[0] : "";
    }

    private static String textOf(TextInputEditText t) {
        return t.getText() == null ? "" : t.getText().toString();
    }
}
