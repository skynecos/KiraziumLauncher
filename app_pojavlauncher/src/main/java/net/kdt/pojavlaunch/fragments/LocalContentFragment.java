package net.kdt.pojavlaunch.fragments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.instances.SelectedProfileInfo;
import net.kdt.pojavlaunch.utils.LocalContentManager;

import java.util.ArrayList;
import java.util.List;

/** Manages installed resource packs and mods for the selected instance. */
public class LocalContentFragment extends Fragment {
    public static final String ARG_MODE = "kirazium_local_content_mode";
    public static final int MODE_PACKS = 0;
    public static final int MODE_MODS = 1;
    public static final String TAG_PACKS = "ActivePacksFragment";
    public static final String TAG_MODS = "ActiveModsFragment";

    private int mMode = MODE_PACKS;
    private TextView mSubtitle;
    private TextView mStatus;
    private LocalContentAdapter mAdapter;
    private int mLoadGeneration;
    private final LruCache<String, Bitmap> mLocalIconCache = new LruCache<>(48);

    public LocalContentFragment() {
        super(R.layout.fragment_local_content);
    }

    public static Bundle createArgs(int mode) {
        Bundle bundle = new Bundle();
        bundle.putInt(ARG_MODE, mode);
        return bundle;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle arguments = getArguments();
        mMode = arguments == null ? MODE_PACKS : arguments.getInt(ARG_MODE, MODE_PACKS);

        ImageButton back = view.findViewById(R.id.local_content_back);
        TextView title = view.findViewById(R.id.local_content_title);
        mSubtitle = view.findViewById(R.id.local_content_subtitle);
        mStatus = view.findViewById(R.id.local_content_status);
        RecyclerView list = view.findViewById(R.id.local_content_list);

        title.setText(mMode == MODE_PACKS
                ? R.string.active_packs_title
                : R.string.active_mods_title);
        back.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));

        mAdapter = new LocalContentAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(mAdapter);

        loadContent();
    }

    private void loadContent() {
        final int generation = ++mLoadGeneration;
        mStatus.setText(R.string.local_content_loading);
        mStatus.setVisibility(View.VISIBLE);

        PojavApplication.sExecutorService.execute(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new IllegalStateException("No selected profile");

                List<LocalContentManager.Entry> entries = mMode == MODE_PACKS
                        ? LocalContentManager.listResourcePacks(instance)
                        : LocalContentManager.listMods(instance);

                String subtitle = buildSubtitle(instance);
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || generation != mLoadGeneration) return;
                    mSubtitle.setText(subtitle);
                    mAdapter.setItems(entries);
                    if (entries.isEmpty()) {
                        mStatus.setText(mMode == MODE_PACKS
                                ? R.string.local_content_no_packs
                                : R.string.local_content_no_mods);
                        mStatus.setVisibility(View.VISIBLE);
                    } else {
                        mStatus.setVisibility(View.GONE);
                    }
                });
            } catch (Exception error) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || generation != mLoadGeneration) return;
                    mAdapter.setItems(new ArrayList<>());
                    mStatus.setText(R.string.local_content_profile_unknown);
                    mStatus.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private String buildSubtitle(Instance instance) {
        try {
            SelectedProfileInfo profile = SelectedProfileInfo.resolve(instance);
            if (mMode == MODE_PACKS) {
                return getString(R.string.active_packs_subtitle,
                        instance.name, profile.gameVersion);
            }
            return getString(R.string.active_mods_subtitle,
                    instance.name, profile.gameVersion, profile.loader.displayName);
        } catch (Exception ignored) {
            String version = instance.versionId == null ? "?" : instance.versionId;
            if (mMode == MODE_PACKS) {
                return getString(R.string.active_packs_subtitle, instance.name, version);
            }
            return getString(R.string.active_mods_subtitle, instance.name, version, "?");
        }
    }

    private void toggle(LocalContentManager.Entry entry, boolean enabled, SwitchCompat toggle) {
        toggle.setEnabled(false);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new IllegalStateException("No selected profile");

                if (mMode == MODE_PACKS) {
                    LocalContentManager.setResourcePackEnabled(instance, entry.fileName, enabled);
                } else {
                    LocalContentManager.setModEnabled(entry, enabled);
                }

                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), successMessage(enabled), Toast.LENGTH_SHORT).show();
                    loadContent();
                });
            } catch (Exception error) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(),
                            R.string.local_content_toggle_failed, Toast.LENGTH_LONG).show();
                    loadContent();
                });
            }
        });
    }

    private int successMessage(boolean enabled) {
        if (mMode == MODE_PACKS) {
            return enabled ? R.string.local_pack_enabled : R.string.local_pack_disabled;
        }
        return enabled ? R.string.local_mod_enabled : R.string.local_mod_disabled;
    }

    private void loadLocalIcon(LocalContentManager.Entry entry, ImageView imageView) {
        boolean mod = mMode == MODE_MODS;
        int fallback = mod ? R.drawable.ic_px_java : R.drawable.ic_px_image;
        String key = (mod ? "mod:" : "pack:") + entry.file.getAbsolutePath() + ":" +
                entry.file.lastModified();

        imageView.setTag(key);
        imageView.setImageResource(fallback);

        Bitmap cached = mLocalIconCache.get(key);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        PojavApplication.sExecutorService.execute(() -> {
            try {
                byte[] bytes = LocalContentManager.loadIconBytes(entry, mod);
                if (bytes == null || bytes.length == 0) return;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) return;
                mLocalIconCache.put(key, bitmap);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (key.equals(imageView.getTag())) imageView.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // Keep the built-in icon only when the pack/mod does not provide a readable image.
            }
        });
    }

    private final class LocalContentAdapter
            extends RecyclerView.Adapter<LocalContentViewHolder> {
        private final List<LocalContentManager.Entry> mItems = new ArrayList<>();

        void setItems(List<LocalContentManager.Entry> entries) {
            mItems.clear();
            mItems.addAll(entries);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public LocalContentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_local_content, parent, false);
            return new LocalContentViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull LocalContentViewHolder holder, int position) {
            LocalContentManager.Entry entry = mItems.get(position);
            holder.title.setText(entry.displayName);
            holder.file.setText(entry.fileName);
            loadLocalIcon(entry, holder.icon);
            holder.status.setText(entry.enabled
                    ? R.string.local_content_enabled
                    : R.string.local_content_disabled);
            holder.status.setTextColor(ContextCompat.getColor(requireContext(), entry.enabled
                    ? R.color.minebutton_color
                    : R.color.secondary_text));

            holder.toggle.setOnCheckedChangeListener(null);
            holder.toggle.setEnabled(true);
            holder.toggle.setChecked(entry.enabled);
            holder.toggle.setOnCheckedChangeListener((buttonView, isChecked) ->
                    toggle(entry, isChecked, holder.toggle));
            holder.itemView.setOnClickListener(v -> {
                if (holder.toggle.isEnabled()) holder.toggle.performClick();
            });
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }

    private static final class LocalContentViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView file;
        final TextView status;
        final SwitchCompat toggle;

        LocalContentViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.local_content_icon);
            title = itemView.findViewById(R.id.local_content_item_title);
            file = itemView.findViewById(R.id.local_content_item_file);
            status = itemView.findViewById(R.id.local_content_item_status);
            toggle = itemView.findViewById(R.id.local_content_switch);
        }
    }
}
