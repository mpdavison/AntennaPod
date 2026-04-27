package de.danoeh.antennapod.ui.screen.preferences;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;
import de.danoeh.antennapod.storage.preferences.AdProviderProfile;

public class AdProviderProfileListFragment extends Fragment {
    public static final String ARG_ROLE = "role";

    private int role;
    private RecyclerView recyclerView;
    private TextView emptyView;

    public static AdProviderProfileListFragment newInstance(int role) {
        AdProviderProfileListFragment f = new AdProviderProfileListFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_ROLE, role);
        f.setArguments(b);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        role = getArguments() != null
                ? getArguments().getInt(ARG_ROLE, AdDetectionPreferences.ROLE_TRANSCRIPTION)
                : AdDetectionPreferences.ROLE_TRANSCRIPTION;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.ad_provider_profile_list_fragment, container, false);
        recyclerView = view.findViewById(R.id.recyclerView);
        emptyView = view.findViewById(R.id.emptyView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        FloatingActionButton fab = view.findViewById(R.id.fabAdd);
        fab.setOnClickListener(v -> openEditor(null));
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getActivity() instanceof PreferenceActivity) {
            int titleRes = role == AdDetectionPreferences.ROLE_TRANSCRIPTION
                    ? R.string.pref_ad_transcription_profiles_title
                    : R.string.pref_ad_chat_profiles_title;
            ((PreferenceActivity) getActivity()).getSupportActionBar().setTitle(titleRes);
        }
        refresh();
    }

    private void refresh() {
        List<AdProviderProfile> profiles = AdDetectionPreferences.getProfiles(role);
        String activeId = AdDetectionPreferences.getActiveProfileId(role);
        recyclerView.setAdapter(new Adapter(profiles, activeId));
        emptyView.setVisibility(profiles.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openEditor(@Nullable String profileId) {
        AdProviderProfileEditorFragment fragment =
                AdProviderProfileEditorFragment.newInstance(role, profileId);
        ((PreferenceActivity) requireActivity()).openCustomFragment(fragment);
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final List<AdProviderProfile> profiles;
        private final String activeId;

        Adapter(List<AdProviderProfile> profiles, String activeId) {
            this.profiles = profiles;
            this.activeId = activeId == null ? "" : activeId;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.ad_provider_profile_list_item, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AdProviderProfile p = profiles.get(position);
            String label = p.name == null || p.name.isEmpty()
                    ? getString(R.string.pref_ad_profile_unnamed) : p.name;
            h.name.setText(label);
            h.summary.setText(buildSummary(p));
            h.activeIcon.setVisibility(p.id.equals(activeId) ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> openEditor(p.id));
        }

        @Override
        public int getItemCount() {
            return profiles.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView summary;
            final ImageView activeIcon;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                summary = v.findViewById(R.id.summary);
                activeIcon = v.findViewById(R.id.activeIcon);
            }
        }
    }

    private String buildSummary(AdProviderProfile p) {
        String typeLabel;
        if (AdProviderProfile.TYPE_OPENAI.equals(p.type)) {
            typeLabel = "OpenAI";
        } else if (AdProviderProfile.TYPE_GROQ.equals(p.type)) {
            typeLabel = "Groq";
        } else {
            typeLabel = p.baseUrl == null || p.baseUrl.isEmpty()
                    ? getString(R.string.pref_ad_provider_custom) : p.baseUrl;
        }
        String model = p.model == null || p.model.isEmpty() ? "" : " · " + p.model;
        return typeLabel + model;
    }
}
