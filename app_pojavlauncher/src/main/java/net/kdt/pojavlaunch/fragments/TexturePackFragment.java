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

/** A Kirazium-styled Modrinth resource-pack browser. */
public class TexturePackFragment extends Fragment {
    public static final String TAG = "TexturePackFragment";

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final int RESULT_LIMIT = 30;

    private EditText mSearchInput;
    private ProgressBar mProgress;
    private TextView mStatus;
    private TexturePackAdapter mAdapter;
    private int mSearchGeneration;

    private final LruCache<String, Bitmap> mIconCache = new LruCache<>(40);
    private final Set<String> mInstalledProjects = new HashSet<>();

    public TexturePackFragment() {
        super(R.layout.fragment_texture_packs);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageButton backButton = view.findViewById(R.id.texture_pack_back);
        ImageButton searchButton = view.findViewById(R.id.texture_pack_search_button);
        RecyclerView list = view.findViewById(R.id.texture_pack_list);
        mSearchInput = view.findViewById(R.id.texture_pack_search);
        mProgress = view.findViewById(R.id.texture_pack_progress);
        mStatus = view.findViewById(R.id.texture_pack_status);

        mAdapter = new TexturePackAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(mAdapter);

        backButton.setOnClickListener(v -> Tools.removeCurrentFragment(requireActivity()));
        searchButton.setOnClickListener(v -> searchPacks(mSearchInput.getText().toString()));
        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchPacks(mSearchInput.getText().toString());
                mSearchInput.clearFocus();
                return true;
            }
            return false;
        });

        searchPacks("");
    }

    private void searchPacks(String query) {
        final int generation = ++mSearchGeneration;
        mProgress.setVisibility(View.VISIBLE);
        mStatus.setText(R.string.texture_packs_loading);
        mStatus.setVisibility(View.VISIBLE);

        final String cleanQuery = query == null ? "" : query.trim();
        PojavApplication.sExecutorService.execute(() -> {
            try {
                String facets = "[[\"project_type:resourcepack\"],[\"versions:" +
                        KiraziumBootstrap.GAME_VERSION + "\"]]";
                String url = MODRINTH_API + "/search?limit=" + RESULT_LIMIT +
                        "&index=downloads&query=" + Uri.encode(cleanQuery) +
                        "&facets=" + Uri.encode(facets);

                JSONObject response = new JSONObject(DownloadUtils.downloadString(url));
                JSONArray hits = response.optJSONArray("hits");
                List<TexturePack> packs = new ArrayList<>();
                if (hits != null) {
                    for (int i = 0; i < hits.length(); i++) {
                        JSONObject hit = hits.optJSONObject(i);
                        if (hit == null) continue;
                        String projectId = hit.optString("project_id", "");
                        String title = hit.optString("title", "");
                        if (TextUtils.isEmpty(projectId) || TextUtils.isEmpty(title)) continue;
                        packs.add(new TexturePack(
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
                    mAdapter.setItems(packs);
                    if (packs.isEmpty()) {
                        mStatus.setText(R.string.texture_packs_empty);
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
                    mStatus.setText(R.string.texture_packs_error);
                    mStatus.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void downloadPack(TexturePack pack, Button button) {
        button.setEnabled(false);
        button.setText(R.string.texture_pack_downloading);

        PojavApplication.sExecutorService.execute(() -> {
            try {
                Instance instance = Instances.loadSelectedInstance();
                if (instance == null) throw new IOException("No selected instance");

                JSONObject file = findCompatibleFile(pack.projectId);
                if (file == null) {
                    Tools.runOnUiThread(() -> {
                        if (!isAdded()) return;
                        button.setEnabled(true);
                        button.setText(R.string.texture_pack_download);
                        Toast.makeText(requireContext(),
                                R.string.texture_pack_no_compatible_version,
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                String filename = new File(file.optString("filename", "resource-pack.zip")).getName();
                if (!filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    throw new IOException("Resource pack file is not a ZIP");
                }

                File resourcePacks = new File(instance.getGameDirectory(), "resourcepacks");
                FileUtils.ensureDirectory(resourcePacks);
                File destination = new File(resourcePacks, filename);
                boolean alreadyThere = destination.isFile();

                String downloadUrl = file.getString("url");
                JSONObject hashes = file.optJSONObject("hashes");
                String sha1 = hashes == null ? null : hashes.optString("sha1", null);
                DownloadUtils.ensureSha1(destination, sha1, () -> {
                    DownloadUtils.downloadFile(downloadUrl, destination);
                    return null;
                });

                mInstalledProjects.add(pack.projectId);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    button.setEnabled(false);
                    button.setText(R.string.texture_pack_installed);
                    Toast.makeText(requireContext(),
                            alreadyThere
                                    ? R.string.texture_pack_already_installed
                                    : getString(R.string.texture_pack_installed_message, pack.title),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    button.setEnabled(true);
                    button.setText(R.string.texture_pack_download);
                    Toast.makeText(requireContext(),
                            R.string.texture_pack_download_failed,
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private JSONObject findCompatibleFile(String projectId) throws Exception {
        String versions = "[\"" + KiraziumBootstrap.GAME_VERSION + "\"]";
        String loaders = "[\"minecraft\"]";
        String url = MODRINTH_API + "/project/" + Uri.encode(projectId) + "/version" +
                "?game_versions=" + Uri.encode(versions) +
                "&loaders=" + Uri.encode(loaders) +
                "&include_changelog=false";

        JSONArray versionList = new JSONArray(DownloadUtils.downloadString(url));
        JSONObject fallbackVersion = null;
        for (int i = 0; i < versionList.length(); i++) {
            JSONObject candidate = versionList.optJSONObject(i);
            if (candidate == null) continue;
            if (fallbackVersion == null) fallbackVersion = candidate;
            if ("release".equals(candidate.optString("version_type"))) {
                fallbackVersion = candidate;
                break;
            }
        }
        if (fallbackVersion == null) return null;

        JSONArray files = fallbackVersion.optJSONArray("files");
        if (files == null || files.length() == 0) return null;

        JSONObject firstZip = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            String name = file.optString("filename", "").toLowerCase(Locale.ROOT);
            if (!name.endsWith(".zip")) continue;
            if (firstZip == null) firstZip = file;
            if (file.optBoolean("primary", false)) return file;
        }
        return firstZip;
    }

    private void loadIcon(TexturePack pack, ImageView imageView) {
        imageView.setTag(pack.projectId);
        imageView.setImageResource(R.drawable.ic_px_image);
        if (TextUtils.isEmpty(pack.iconUrl)) return;

        Bitmap cached = mIconCache.get(pack.iconUrl);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        PojavApplication.sExecutorService.execute(() -> {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                DownloadUtils.download(pack.iconUrl, output);
                byte[] data = output.toByteArray();
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bitmap == null) return;
                mIconCache.put(pack.iconUrl, bitmap);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Object tag = imageView.getTag();
                    if (pack.projectId.equals(tag)) imageView.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // Keep the built-in resource-pack icon when an external icon cannot be loaded.
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

    private final class TexturePackAdapter extends RecyclerView.Adapter<TexturePackViewHolder> {
        private final List<TexturePack> items = new ArrayList<>();

        void setItems(List<TexturePack> packs) {
            items.clear();
            items.addAll(packs);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public TexturePackViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_texture_pack, parent, false);
            return new TexturePackViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull TexturePackViewHolder holder, int position) {
            TexturePack pack = items.get(position);
            holder.title.setText(pack.title);
            holder.description.setText(pack.description);
            holder.downloads.setText(getString(
                    R.string.texture_pack_downloads,
                    formatDownloads(pack.downloads)));
            loadIcon(pack, holder.icon);

            boolean installed = mInstalledProjects.contains(pack.projectId);
            holder.download.setEnabled(!installed);
            holder.download.setText(installed
                    ? R.string.texture_pack_installed
                    : R.string.texture_pack_download);
            holder.download.setOnClickListener(v -> downloadPack(pack, holder.download));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class TexturePackViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView description;
        final TextView downloads;
        final Button download;

        TexturePackViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.texture_pack_icon);
            title = itemView.findViewById(R.id.texture_pack_item_title);
            description = itemView.findViewById(R.id.texture_pack_item_description);
            downloads = itemView.findViewById(R.id.texture_pack_item_downloads);
            download = itemView.findViewById(R.id.texture_pack_download_button);
        }
    }

    private static final class TexturePack {
        final String projectId;
        final String title;
        final String description;
        final String iconUrl;
        final long downloads;

        TexturePack(String projectId, String title, String description, String iconUrl,
                    long downloads) {
            this.projectId = projectId;
            this.title = title;
            this.description = description;
            this.iconUrl = iconUrl;
            this.downloads = downloads;
        }
    }
}
