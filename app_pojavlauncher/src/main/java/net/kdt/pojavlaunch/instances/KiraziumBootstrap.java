package net.kdt.pojavlaunch.instances;

import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
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
