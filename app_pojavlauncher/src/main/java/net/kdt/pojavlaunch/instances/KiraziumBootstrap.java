package net.kdt.pojavlaunch.instances;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates and maintains the ready-to-play Kirazium profile. */
public final class KiraziumBootstrap {
    public static final String PROFILE_NAME = "Kirazium";
    public static final String PROFILE_ICON = "kirazium";
    public static final String GAME_VERSION = "26.1.2";

    private static final String TAG = "KiraziumBootstrap";
    private static final String FABRIC_LOADER_VERSION = "0.19.3";
    private static final String SERVER_NAME = "Kirazium";
    private static final String SERVER_ADDRESS = "play.kirazium.com";
    private static final String MODRINTH_VERSION_API =
            "https://api.modrinth.com/v2/project/%s/version?loaders=%%5B%%22fabric%%22%%5D&game_versions=%%5B%%22%s%%22%%5D";
    private static final String[] CLIENT_MODS = new String[] {
            "fabric-api",
            "cloth-config",
            "sodium",
            "lithium",
            "ferrite-core",
            "immediatelyfast",
            "entityculling",
            "sodium-extra",
            "moreculling",
            "badoptimizations",
            "krypton",
            "dynamic-fps",
            "simple-voice-chat"
    };
    private static final String BOOTSTRAP_MARKER = ".kirazium-bootstrap-v2";
    private static final String LANGUAGE_MARKER = ".kirazium-language-tr-v1";
    private static final String OPTIMIZATION_MARKER = ".kirazium-low-end-v2";
    public static final String LOW_GRAPHICS_PREFERENCE = "kiraziumLowGraphicsMode";
    private static final String BACKUP_RAM_PREFERENCE = "kiraziumNormalRamAllocation";
    private static final String RAM_INDEPENDENCE_MARKER =
            "kiraziumLowGraphicsRamIndependentV1";
    private static final String BACKUP_RESOLUTION_PREFERENCE = "kiraziumNormalResolutionRatio";
    private static final String BACKUP_VSYNC_PREFERENCE = "kiraziumNormalForceVsync";
    private static final String BACKUP_SUSTAINED_PREFERENCE = "kiraziumNormalSustainedPerformance";

    private KiraziumBootstrap() {
    }

    public static String installFabricProfile() throws IOException {
        try {
            return FabriclikeUtils.FABRIC_UTILS.install(GAME_VERSION, FABRIC_LOADER_VERSION);
        } catch (IOException exception) {
            Log.w(TAG, "Fabric profile could not be downloaded; using vanilla as fallback", exception);
            return GAME_VERSION;
        }
    }

    public static void ensureClientFiles(List<DisplayInstance> instances) {
        boolean hasKirazium = false;
        for (DisplayInstance instance : instances) {
            if (PROFILE_NAME.equals(instance.name)) {
                hasKirazium = true;
                break;
            }
        }
        if (!hasKirazium) return;

        File gameDirectory = Instances.SHARED_DATA_DIRECTORY;
        try {
            FileUtils.ensureDirectory(gameDirectory);
            ensureServerEntry(gameDirectory);
            ensureLowEndOptions(gameDirectory);
            ensureTurkishLanguage(gameDirectory);
            ensureLowEndOptimizations(gameDirectory);
            ensurePerformanceMods(gameDirectory);
        } catch (Exception exception) {
            // Do not make the launcher unusable when a third-party download is temporarily unavailable.
            Log.w(TAG, "Kirazium client preparation will be retried", exception);
        }
    }

    /** Re-check the active Kirazium game directory immediately before Minecraft starts. */
    public static void ensureServerEntry(Instance instance) {
        if (instance == null || !PROFILE_NAME.equals(instance.name)) return;
        try {
            File gameDirectory = instance.getGameDirectory();
            FileUtils.ensureDirectory(gameDirectory);
            ensureServerEntry(gameDirectory);
        } catch (IOException exception) {
            Log.w(TAG, "Kirazium server entry could not be prepared", exception);
        }
    }

    public static boolean isLowGraphicsModeEnabled() {
        return LauncherPreferences.DEFAULT_PREF != null &&
                LauncherPreferences.DEFAULT_PREF.getBoolean(LOW_GRAPHICS_PREFERENCE, false);
    }

    /** Restores RAM values that older low-graphics builds may have reduced. */
    public static void ensureLowGraphicsRamIndependence(Context context) {
        if (context == null || LauncherPreferences.DEFAULT_PREF == null) return;

        SharedPreferences preferences = LauncherPreferences.DEFAULT_PREF;
        if (preferences.getBoolean(RAM_INDEPENDENCE_MARKER, false)) return;

        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(RAM_INDEPENDENCE_MARKER, true);
        if (preferences.getBoolean(LOW_GRAPHICS_PREFERENCE, false) &&
                preferences.contains(BACKUP_RAM_PREFERENCE)) {
            editor.putInt("allocation", preferences.getInt(BACKUP_RAM_PREFERENCE,
                    LauncherPreferences.PREF_RAM_ALLOCATION));
        }
        editor.apply();
        LauncherPreferences.loadPreferences(context);
    }

    /** Applies a user-selected RAM value independently from graphics presets. */
    public static void setRamAllocation(Context context, int megabytes) {
        if (context == null || LauncherPreferences.DEFAULT_PREF == null) return;
        ensureLowGraphicsRamIndependence(context);
        LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", megabytes).apply();
        LauncherPreferences.loadPreferences(context);
    }

    /** Enables the aggressive low-device preset while preserving the user's launcher settings. */
    public static void setLowGraphicsMode(Context context, Instance instance, boolean enabled) {
        if (context == null || LauncherPreferences.DEFAULT_PREF == null) return;
        ensureLowGraphicsRamIndependence(context);

        SharedPreferences preferences = LauncherPreferences.DEFAULT_PREF;
        boolean wasEnabled = preferences.getBoolean(LOW_GRAPHICS_PREFERENCE, false);
        SharedPreferences.Editor editor = preferences.edit();

        if (enabled && !wasEnabled) {
            editor.putInt(BACKUP_RESOLUTION_PREFERENCE,
                    Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f));
            editor.putBoolean(BACKUP_VSYNC_PREFERENCE, LauncherPreferences.PREF_FORCE_VSYNC);
            editor.putBoolean(BACKUP_SUSTAINED_PREFERENCE,
                    LauncherPreferences.PREF_SUSTAINED_PERFORMANCE);
        }

        editor.putBoolean(LOW_GRAPHICS_PREFERENCE, enabled);
        if (enabled) {
            editor.putInt("resolutionRatio", 50);
            editor.putBoolean("force_vsync", false);
            editor.putBoolean("vsync_in_zink", false);
            editor.putBoolean("sustainedPerformance", true);
        } else if (wasEnabled) {
            editor.putInt("resolutionRatio", preferences.getInt(BACKUP_RESOLUTION_PREFERENCE,
                    Math.round(LauncherPreferences.PREF_SCALE_FACTOR * 100f)));
            editor.putBoolean("force_vsync", preferences.getBoolean(BACKUP_VSYNC_PREFERENCE,
                    LauncherPreferences.PREF_FORCE_VSYNC));
            editor.putBoolean("sustainedPerformance",
                    preferences.getBoolean(BACKUP_SUSTAINED_PREFERENCE,
                            LauncherPreferences.PREF_SUSTAINED_PERFORMANCE));
        }
        editor.apply();
        LauncherPreferences.loadPreferences(context);
        applyGraphicsOptions(instance, enabled);
        applySafeModProfile(instance, enabled);
    }

    /** Re-applies the selected preset immediately before launch. */
    public static void applySelectedGraphicsMode(Context context, Instance instance) {
        if (context == null || instance == null || !PROFILE_NAME.equals(instance.name)) return;
        ensureLowGraphicsRamIndependence(context);
        boolean lowGraphics = isLowGraphicsModeEnabled();
        if (lowGraphics) {
            SharedPreferences preferences = LauncherPreferences.DEFAULT_PREF;
            preferences.edit()
                    .putInt("resolutionRatio", 50)
                    .putBoolean("force_vsync", false)
                    .putBoolean("vsync_in_zink", false)
                    .putBoolean("sustainedPerformance", true)
                    .apply();
            LauncherPreferences.loadPreferences(context);
        }
        applyGraphicsOptions(instance, lowGraphics);
        applySafeModProfile(instance, lowGraphics);
    }

    private static void applyGraphicsOptions(Instance instance, boolean lowGraphics) {
        if (instance == null || !PROFILE_NAME.equals(instance.name)) return;
        try {
            File gameDirectory = instance.getGameDirectory();
            FileUtils.ensureDirectory(gameDirectory);
            upsertOptions(new File(gameDirectory, "options.txt"),
                    lowGraphics ? createLowGraphicsOptions() : createNormalGraphicsOptions());
        } catch (IOException exception) {
            Log.w(TAG, "Kirazium graphics preset could not be applied", exception);
        }
    }

    private static LinkedHashMap<String, String> createLowGraphicsOptions() {
        LinkedHashMap<String, String> options = createNormalGraphicsOptions();
        options.put("renderDistance", "3");
        options.put("simulationDistance", "5");
        options.put("entityDistanceScaling", "0.5");
        options.put("weatherRadius", "0");
        options.put("particles", "2");
        options.put("mipmapLevels", "0");
        options.put("maxFps", "60");
        options.put("bobView", "false");
        return options;
    }

    private static LinkedHashMap<String, String> createNormalGraphicsOptions() {
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("graphicsPreset", "custom");
        options.put("renderDistance", "6");
        options.put("simulationDistance", "5");
        options.put("entityDistanceScaling", "0.5");
        options.put("ao", "false");
        options.put("renderClouds", "false");
        options.put("entityShadows", "false");
        options.put("cutoutLeaves", "false");
        options.put("improvedTransparency", "false");
        options.put("weatherRadius", "5");
        options.put("menuBackgroundBlurriness", "0");
        options.put("particles", "1");
        options.put("mipmapLevels", "2");
        options.put("biomeBlendRadius", "0");
        options.put("maxFps", "60");
        options.put("enableVsync", "false");
        options.put("bobView", "true");
        return options;
    }

    /**
     * Applies only stable mod settings. Every touched config is preserved byte-for-byte and is
     * restored when Ultra Low Graphics is disabled. Experimental ImmediatelyFast features and
     * EntityCulling's visually aggressive display/solid-leaf modes deliberately remain disabled.
     */
    private static void applySafeModProfile(Instance instance, boolean enabled) {
        if (instance == null || !PROFILE_NAME.equals(instance.name)) return;

        File configDirectory = new File(instance.getGameDirectory(), "config");
        if (enabled) {
            try {
                FileUtils.ensureDirectory(configDirectory);
            } catch (IOException exception) {
                Log.w(TAG, "Kirazium mod config directory could not be prepared", exception);
                return;
            }
        }

        updateModConfig(new File(configDirectory, "sodium-options.json"), enabled,
                KiraziumBootstrap::configureSodium);
        updateModConfig(new File(configDirectory, "sodium-extra-options.json"), enabled,
                KiraziumBootstrap::configureSodiumExtra);
        updateModConfig(new File(configDirectory, "entityculling.json"), enabled,
                KiraziumBootstrap::configureEntityCulling);
        updateModConfig(new File(configDirectory, "immediatelyfast.json"), enabled,
                KiraziumBootstrap::configureImmediatelyFast);
    }

    private static void configureSodium(JSONObject root) throws JSONException {
        JSONObject quality = getOrCreateObject(root, "quality");
        quality.put("hidden_fluid_culling", true);
        quality.put("improved_fluid_shaping", false);
        quality.put("use_closest_point_entity_sort", false);
        quality.put("pixel_filtering_mode", "NEAREST");

        JSONObject performance = getOrCreateObject(root, "performance");
        performance.put("chunk_builder_threads", 0);
        performance.put("chunk_build_defer_mode", "ALWAYS");
        performance.put("animate_only_visible_textures", true);
        performance.put("use_entity_culling", true);
        performance.put("use_fog_occlusion", true);
        performance.put("use_block_face_culling", true);
        performance.put("use_no_error_gl_context", true);
        performance.put("quad_splitting_mode", "SAFE");

        JSONObject advanced = getOrCreateObject(root, "advanced");
        advanced.put("enable_memory_tracing", false);
        advanced.put("use_advanced_staging_buffers", true);
        advanced.put("cpu_render_ahead_limit", 3);
    }

    private static void configureSodiumExtra(JSONObject root) throws JSONException {
        JSONObject animations = getOrCreateObject(root, "animation_settings");
        animations.put("animation", false);
        animations.put("water", false);
        animations.put("lava", false);
        animations.put("fire", false);
        animations.put("portal", false);
        animations.put("block_animations", false);
        animations.put("sculk_sensor", false);

        JSONObject particles = getOrCreateObject(root, "particle_settings");
        particles.put("particles", false);
        particles.put("rain_splash", false);
        particles.put("block_break", false);
        particles.put("block_breaking", false);

        JSONObject details = getOrCreateObject(root, "detail_settings");
        details.put("rain_snow", false);

        JSONObject extras = getOrCreateObject(root, "extra_settings");
        extras.put("prevent_shaders", true);
    }

    private static void configureEntityCulling(JSONObject root) throws JSONException {
        root.put("debugMode", false);
        root.put("tickCulling", true);
        root.put("skipEntityCulling", false);
        root.put("skipBlockEntityCulling", false);
        root.put("blockEntityFrustumCulling", true);
        root.put("forceDisplayCulling", false);
        root.put("solidLeaves", false);
    }

    private static void configureImmediatelyFast(JSONObject root) throws JSONException {
        root.put("enhanced_batching", true);
        root.put("font_atlas_resizing", true);
        root.put("map_atlas_generation", true);
        root.put("skip_text_translucency_sorting", true);
        root.put("fast_text_lookup", true);
        root.put("avoid_redundant_framebuffer_switching", true);
        root.put("fix_slow_buffer_upload_on_apple_gpu", true);

        root.put("experimental_disable_resource_pack_conflict_handling", false);
        root.put("experimental_sign_text_buffering", false);
        root.put("debug_only_and_not_recommended_disable_mod_conflict_handling", false);
        root.put("debug_only_and_not_recommended_disable_hardware_conflict_handling", false);
        root.put("debug_only_print_additional_error_information", false);
        root.put("debug_only_use_last_usage_for_batch_ordering", false);
        root.put("debug_only_detailed_memory_leak_detection", false);
    }

    private static JSONObject getOrCreateObject(JSONObject root, String key) throws JSONException {
        JSONObject object = root.optJSONObject(key);
        if (object == null) {
            object = new JSONObject();
            root.put(key, object);
        }
        return object;
    }

    private static void updateModConfig(File config, boolean enabled, JsonConfigUpdater updater) {
        File backup = new File(config.getParentFile(), config.getName() + ".kirazium-normal");
        File absentMarker = new File(config.getParentFile(), config.getName() + ".kirazium-absent");
        try {
            if (enabled) {
                if (!backup.isFile() && !absentMarker.isFile()) {
                    if (config.isFile()) {
                        Tools.write(backup, Tools.read(config));
                    } else {
                        Tools.write(absentMarker, "absent\n");
                    }
                }

                JSONObject json = config.isFile()
                        ? new JSONObject(Tools.read(config))
                        : new JSONObject();
                updater.update(json);
                Tools.write(config, json.toString(2) + "\n");
                return;
            }

            if (backup.isFile()) {
                Tools.write(config, Tools.read(backup));
                if (!backup.delete()) {
                    Log.w(TAG, "Could not remove restored mod config backup: " + backup);
                }
                if (absentMarker.isFile() && !absentMarker.delete()) {
                    Log.w(TAG, "Could not remove stale mod config marker: " + absentMarker);
                }
            } else if (absentMarker.isFile()) {
                if (config.exists() && !config.delete()) {
                    throw new IOException("Could not remove Ultra-mode mod config: " + config);
                }
                if (!absentMarker.delete()) {
                    Log.w(TAG, "Could not remove mod config marker: " + absentMarker);
                }
            }
        } catch (IOException | JSONException exception) {
            // One incompatible or user-edited config must not block the other safe optimizations.
            Log.w(TAG, "Kirazium safe mod profile could not update " + config.getName(), exception);
        }
    }

    @FunctionalInterface
    private interface JsonConfigUpdater {
        void update(JSONObject root) throws JSONException;
    }

    private static void ensurePerformanceMods(File gameDirectory) throws IOException, JSONException {
        File marker = new File(gameDirectory, BOOTSTRAP_MARKER);
        if (marker.isFile()) return;

        File modsDirectory = new File(gameDirectory, "mods");
        FileUtils.ensureDirectory(modsDirectory);
        for (String project : CLIENT_MODS) {
            downloadLatestRelease(project, modsDirectory);
        }
        Tools.write(marker, "game=" + GAME_VERSION + "\nloader=" + FABRIC_LOADER_VERSION + "\n");
    }

    private static void downloadLatestRelease(String project, File modsDirectory) throws IOException, JSONException {
        String metadataUrl = String.format(MODRINTH_VERSION_API, project, GAME_VERSION);
        JSONArray versions = new JSONArray(DownloadUtils.downloadString(metadataUrl));
        JSONObject selectedVersion = null;
        for (int index = 0; index < versions.length(); index++) {
            JSONObject candidate = versions.getJSONObject(index);
            if ("release".equals(candidate.optString("version_type"))) {
                selectedVersion = candidate;
                break;
            }
        }
        if (selectedVersion == null) {
            throw new IOException("No stable " + project + " release for Minecraft " + GAME_VERSION);
        }

        JSONArray files = selectedVersion.getJSONArray("files");
        JSONObject selectedFile = files.getJSONObject(0);
        for (int index = 0; index < files.length(); index++) {
            JSONObject candidate = files.getJSONObject(index);
            if (candidate.optBoolean("primary")) {
                selectedFile = candidate;
                break;
            }
        }

        File destination = new File(modsDirectory, selectedFile.getString("filename"));
        String sha1 = selectedFile.getJSONObject("hashes").optString("sha1", null);
        String downloadUrl = selectedFile.getString("url");
        DownloadUtils.ensureSha1(destination, sha1, () -> {
            DownloadUtils.downloadFile(downloadUrl, destination);
            return null;
        });
    }

    private static void ensureLowEndOptions(File gameDirectory) throws IOException {
        File options = new File(gameDirectory, "options.txt");
        if (options.exists()) return;
        String lowEndDefaults =
                "lang:tr_tr\n" +
                "renderDistance:6\n" +
                "simulationDistance:5\n" +
                "entityDistanceScaling:0.5\n" +
                "graphicsPreset:custom\n" +
                "ao:false\n" +
                "renderClouds:false\n" +
                "entityShadows:false\n" +
                "cutoutLeaves:false\n" +
                "improvedTransparency:false\n" +
                "weatherRadius:5\n" +
                "menuBackgroundBlurriness:0\n" +
                "particles:1\n" +
                "mipmapLevels:2\n" +
                "biomeBlendRadius:0\n" +
                "maxFps:60\n" +
                "enableVsync:false\n";
        Tools.write(options, lowEndDefaults);
    }

    private static void ensureLowEndOptimizations(File gameDirectory) throws IOException {
        File marker = new File(gameDirectory, OPTIMIZATION_MARKER);
        if (marker.isFile()) return;

        LinkedHashMap<String, String> defaults = new LinkedHashMap<>();
        defaults.put("graphicsPreset", "custom");
        defaults.put("renderDistance", "6");
        defaults.put("simulationDistance", "5");
        defaults.put("entityDistanceScaling", "0.5");
        defaults.put("ao", "false");
        defaults.put("renderClouds", "false");
        defaults.put("entityShadows", "false");
        defaults.put("cutoutLeaves", "false");
        defaults.put("improvedTransparency", "false");
        defaults.put("weatherRadius", "5");
        defaults.put("menuBackgroundBlurriness", "0");
        defaults.put("particles", "1");
        defaults.put("mipmapLevels", "2");
        defaults.put("biomeBlendRadius", "0");
        defaults.put("maxFps", "60");
        defaults.put("enableVsync", "false");
        upsertOptions(new File(gameDirectory, "options.txt"), defaults);
        Tools.write(marker, "low-end-v2\n");
    }

    private static void ensureTurkishLanguage(File gameDirectory) throws IOException {
        File marker = new File(gameDirectory, LANGUAGE_MARKER);
        if (marker.isFile()) return;

        File options = new File(gameDirectory, "options.txt");
        LinkedHashMap<String, String> language = new LinkedHashMap<>();
        language.put("lang", "tr_tr");
        upsertOptions(options, language);
        Tools.write(marker, "tr_tr\n");
    }

    private static void upsertOptions(File options, LinkedHashMap<String, String> values) throws IOException {
        String existing = options.isFile() ? Tools.read(options) : "";
        StringBuilder updated = new StringBuilder();
        for (String line : existing.split("\\r?\\n")) {
            if (line.isEmpty()) continue;
            int separator = line.indexOf(':');
            String key = separator > 0 ? line.substring(0, separator) : line;
            if (values.containsKey(key)) {
                updated.append(key).append(':').append(values.remove(key)).append('\n');
            } else {
                updated.append(line).append('\n');
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            updated.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        Tools.write(options, updated.toString());
    }

    private static void ensureServerEntry(File gameDirectory) throws IOException {
        File serversFile = new File(gameDirectory, "servers.dat");
        if (containsServerAddress(serversFile)) return;

        // Minecraft may create an empty servers.dat during its first startup. Preserve any
        // previous file before replacing it so the launcher never silently destroys user data.
        if (serversFile.isFile()) {
            File backup = new File(gameDirectory, "servers.dat.kirazium-backup");
            int suffix = 1;
            while (backup.exists()) {
                backup = new File(gameDirectory, "servers.dat.kirazium-backup-" + suffix++);
            }
            if (!serversFile.renameTo(backup)) {
                throw new IOException("Could not back up existing servers.dat");
            }
        }

        FileUtils.ensureParentDirectory(serversFile);
        // Minecraft 26.1 uses NbtIo.write/read here, which is raw (uncompressed) NBT.
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(serversFile)))) {
            output.writeByte(10); // TAG_Compound root
            output.writeUTF("");

            output.writeByte(9); // TAG_List
            output.writeUTF("servers");
            output.writeByte(10); // list element type: TAG_Compound
            output.writeInt(1);

            writeStringTag(output, "name", SERVER_NAME);
            writeStringTag(output, "ip", SERVER_ADDRESS);
            output.writeByte(1); // TAG_Byte
            output.writeUTF("acceptTextures");
            output.writeByte(1);
            output.writeByte(0); // end server compound
            output.writeByte(0); // end root compound
        }
    }

    private static boolean containsServerAddress(File serversFile) {
        if (!serversFile.isFile()) return false;
        byte[] address = SERVER_ADDRESS.getBytes(StandardCharsets.UTF_8);
        try (java.io.FileInputStream input = new java.io.FileInputStream(serversFile)) {
            int matched = 0;
            int value;
            while ((value = input.read()) != -1) {
                if (value == (address[matched] & 0xff)) {
                    matched++;
                    if (matched == address.length) return true;
                } else {
                    matched = value == (address[0] & 0xff) ? 1 : 0;
                }
            }
        } catch (IOException exception) {
            Log.w(TAG, "Existing servers.dat could not be read; it will be backed up", exception);
        }
        return false;
    }

    private static void writeStringTag(DataOutputStream output, String name, String value) throws IOException {
        output.writeByte(8); // TAG_String
        output.writeUTF(name);
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(encoded.length);
        output.write(encoded);
    }
}
