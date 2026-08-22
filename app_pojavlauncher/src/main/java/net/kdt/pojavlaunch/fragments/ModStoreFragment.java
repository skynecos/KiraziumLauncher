package net.kdt.pojavlaunch.fragments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.instances.KiraziumBootstrap;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Kirazium-styled Modrinth Fabric mod browser and installer. */
public class ModStoreFragment extends Fragment {
    public static final String TAG = "ModStoreFragment";

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final int RESULT_LIMIT = 30;

    private EditText mSearchInput;
    private ProgressBar mProgress;
    private TextView mStatus;
    private ModAdapter mAdapter;
    private int mSearchGeneration;

    private final LruCache<String, Bitmap> mIconCache = new LruCache<>(40);
    private final Set<String> mInstalledProjects = new HashSet<>();

    public ModStoreFragment() {
        super(R.layout.fragment_mod_store);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageButton backButton = view.findViewById(R.id.mod_store_back);
        ImageButton searchButton = view.findViewById(R.id.mod_store_search_button);
        RecyclerView list = view.findViewById(R.id.mod_store_list);
        mSearchInput = view.findViewById(R.id.mod_store_search);
        mProgress = view.findViewById(R.id.mod_store_progress);
        mStatus = view.findViewById(R.id.mod_store_status);

        mAdapter = new ModAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(mAdapter);

        backButton.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        searchButton.setOnClickListener(v -> searchMods(mSearchInput.getText().toString()));
        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchMods(mSearchInput.getText().toString());
                mSearchInput.clearFocus();
                return true;
            }
            return false;
        });

        searchMods("");
    }

    private void searchMods(String query) {
        final int generation = ++mSearchGeneration;
        mProgress.setVisibility(View.VISIBLE);
        mStatus.setText(R.string.mods_loading);
        mStatus.setVisibility(View.VISIBLE);

        final String cleanQuery = query == null ? "" : query.trim();
        PojavApplication.sExecutorService.execute(() -> {
            try {
                String facets = "[[\"project_type:mod\"],[\"versions:" +
                        KiraziumBootstrap.GAME_VERSION + "\"],[\"categories:fabric\"]]";
                String url = MODRINTH_API + "/search?limit=" + RESULT_LIMIT +
                        "&index=downloads&query=" + Uri.encode(cleanQuery) +
                        "&facets=" + Uri.encode(facets);

                JSONObject response = new JSONObject(DownloadUtils.downloadString(url));
                JSONArray hits = response.optJSONArray("hits");
                List<ModItem> mods = new ArrayList<>();
                if (hits != null) {
                    for (int i = 0; i < hits.length(); i++) {
                        JSONObject hit = hits.optJSONObject(i);
                        if (hit == null) continue;
                        if ("unsupported".equals(hit.optString("client_side"))) continue;

                        String projectId = hit.optString("project_id", "");
                        String title = hit.optString("title", "");
                        if (TextUtils.isEmpty(projectId) || TextUtils.isEmpty(title)) continue;

                        mods.add(new ModItem(
                                projectId,
                                title,
                                hit.optString("description", ""),
                                hit.optString("icon_url", ""),
                                hit.optLong("downloads", 0L)));
                    }
                }

                Tools.runOnUiThread(() -> {
                    if (!isAdded() || generation != mSearchGeneration) return;
                    mProgress.setVisibility(View.GONE);
                    mAdapter.setItems(mods);
                    if (mods.isEmpty()) {
                        mStatus.setText(R.string.mods_empty);
                        mStatus.setVisibility(View.VISIBLE);
                    } else {
                        mStatus.setVisibility(View.GONE);
                    }
                });
            } catch (Exception exception) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || generation != mSearchGeneration) return;
                    mProgress.setVisibility(View.GONE);
                    mAdapter.setItems(new ArrayList<>());
                    mStatus.setText(R.string.mods_error);
                    mStatus.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void downloadMod(ModItem mod, Button button) {
        button.setEnabled(false);
        button.setText(R.string.mod_downloading);

        PojavApplication.sExecutorService.execute(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new IOException("No selected instance");

                File modsDir = new File(instance.getGameDirectory(), "mods");
                FileUtils.ensureDirectory(modsDir);

                JSONObject version = findCompatibleVersion(mod.projectId);
                if (version == null) throw new IOException("No compatible Fabric version");

                installVersionWithDependencies(version, modsDir, new HashSet<>());
                mInstalledProjects.add(mod.projectId);

                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    button.setEnabled(false);
                    button.setText(R.string.mod_installed);
                    Toast.makeText(requireContext(),
                            getString(R.string.mod_installed_message, mod.title),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    button.setEnabled(true);
                    button.setText(R.string.mod_download);
                    Toast.makeText(requireContext(),
                            R.string.mod_download_failed,
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private JSONObject findCompatibleVersion(String projectId) throws Exception {
        String versions = "[\"" + KiraziumBootstrap.GAME_VERSION + "\"]";
        String loaders = "[\"fabric\"]";
        String url = MODRINTH_API + "/project/" + Uri.encode(projectId) + "/version" +
                "?game_versions=" + Uri.encode(versions) +
                "&loaders=" + Uri.encode(loaders) +
                "&include_changelog=false";

        JSONArray versionList = new JSONArray(DownloadUtils.downloadString(url));
        JSONObject fallback = null;
        for (int i = 0; i < versionList.length(); i++) {
            JSONObject candidate = versionList.optJSONObject(i);
            if (candidate == null) continue;
            if (fallback == null) fallback = candidate;
            if ("release".equals(candidate.optString("version_type"))) return candidate;
        }
        return fallback;
    }

    private JSONObject fetchVersion(String versionId) throws Exception {
        return new JSONObject(DownloadUtils.downloadString(
                MODRINTH_API + "/version/" + Uri.encode(versionId)));
    }

    private void installVersionWithDependencies(JSONObject version, File modsDir,
                                                Set<String> visited) throws Exception {
        String versionId = version.optString("id", "");
        if (!TextUtils.isEmpty(versionId) && !visited.add(versionId)) return;

        JSONArray dependencies = version.optJSONArray("dependencies");
        if (dependencies != null) {
            for (int i = 0; i < dependencies.length(); i++) {
                JSONObject dependency = dependencies.optJSONObject(i);
                if (dependency == null ||
                        !"required".equals(dependency.optString("dependency_type"))) continue;

                String dependencyVersionId = dependency.optString("version_id", "");
                String dependencyProjectId = dependency.optString("project_id", "");
                JSONObject dependencyVersion = null;

                if (!TextUtils.isEmpty(dependencyVersionId)) {
                    dependencyVersion = fetchVersion(dependencyVersionId);
                } else if (!TextUtils.isEmpty(dependencyProjectId)) {
                    dependencyVersion = findCompatibleVersion(dependencyProjectId);
                }

                if (dependencyVersion == null) {
                    throw new IOException("Required dependency is unavailable");
                }
                installVersionWithDependencies(dependencyVersion, modsDir, visited);
            }
        }

        JSONObject file = pickJar(version);
        if (file == null) throw new IOException("No installable JAR in version");

        String filename = new File(file.getString("filename")).getName();
        File destination = new File(modsDir, filename);
        String downloadUrl = file.getString("url");
        JSONObject hashes = file.optJSONObject("hashes");
        String sha1 = hashes == null ? null : hashes.optString("sha1", null);

        DownloadUtils.ensureSha1(destination, sha1, () -> {
            DownloadUtils.downloadFile(downloadUrl, destination);
            return null;
        });
    }

    private JSONObject pickJar(JSONObject version) {
        JSONArray files = version.optJSONArray("files");
        if (files == null) return null;

        JSONObject firstJar = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String name = file.optString("filename", "").toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) continue;
            if (name.contains("-sources") || name.contains("-dev") || name.contains("-javadoc")) continue;
            if (firstJar == null) firstJar = file;
            if (file.optBoolean("primary", false)) return file;
        }
        return firstJar;
    }

    private void loadIcon(ModItem mod, ImageView imageView) {
        imageView.setTag(mod.projectId);
        imageView.setImageResource(R.drawable.ic_px_java);
        if (TextUtils.isEmpty(mod.iconUrl)) return;

        Bitmap cached = mIconCache.get(mod.iconUrl);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        PojavApplication.sExecutorService.execute(() -> {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                DownloadUtils.download(mod.iconUrl, output);
                byte[] data = output.toByteArray();
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) return;
                mIconCache.put(mod.iconUrl, bitmap);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (mod.projectId.equals(imageView.getTag())) imageView.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // Keep the built-in mod icon when a remote icon cannot be loaded.
            }
        });
    }

    private String formatDownloads(long downloads) {
        if (downloads >= 1_000_000L) {
            return String.format(Locale.getDefault(), "%.1f Mn", downloads / 1_000_000f);
        }
        if (downloads >= 1_000L) {
            return String.format(Locale.getDefault(), "%.1f B", downloads / 1_000f);
        }
        return Long.toString(downloads);
    }

    private final class ModAdapter extends RecyclerView.Adapter<ModViewHolder> {
        private final List<ModItem> items = new ArrayList<>();

        void setItems(List<ModItem> mods) {
            items.clear();
            items.addAll(mods);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ModViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_texture_pack, parent, false);
            return new ModViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ModViewHolder holder, int position) {
            ModItem mod = items.get(position);
            holder.title.setText(mod.title);
            holder.description.setText(mod.description);
            holder.downloads.setText(getString(R.string.mod_downloads, formatDownloads(mod.downloads)));
            loadIcon(mod, holder.icon);

            boolean installed = mInstalledProjects.contains(mod.projectId);
            holder.download.setEnabled(!installed);
            holder.download.setText(installed ? R.string.mod_installed : R.string.mod_download);
            holder.download.setOnClickListener(v -> downloadMod(mod, holder.download));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class ModViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView description;
        final TextView downloads;
        final Button download;

        ModViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.texture_pack_icon);
            title = itemView.findViewById(R.id.texture_pack_item_title);
            description = itemView.findViewById(R.id.texture_pack_item_description);
            downloads = itemView.findViewById(R.id.texture_pack_item_downloads);
            download = itemView.findViewById(R.id.texture_pack_download_button);
        }
    }

    private static final class ModItem {
        final String projectId;
        final String title;
        final String description;
        final String iconUrl;
        final long downloads;

        ModItem(String projectId, String title, String description, String iconUrl, long downloads) {
            this.projectId = projectId;
            this.title = title;
            this.description = description;
            this.iconUrl = iconUrl;
            this.downloads = downloads;
        }
    }
}
