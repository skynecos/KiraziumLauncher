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
import java.util.List;
import java.util.zip.GZIPOutputStream;

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
    private static final String[] PERFORMANCE_MODS = new String[] {
            "fabric-api",
            "sodium",
            "lithium",
            "ferrite-core",
            "immediatelyfast",
            "entityculling",
            "sodium-extra"
    };
    private static final String BOOTSTRAP_MARKER = ".kirazium-bootstrap-v1";

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
            ensurePerformanceMods(gameDirectory);
        } catch (Exception exception) {
            // Do not make the launcher unusable when a third-party download is temporarily unavailable.
            Log.w(TAG, "Kirazium client preparation will be retried", exception);
        }
    }

    private static void ensurePerformanceMods(File gameDirectory) throws IOException, JSONException {
        File marker = new File(gameDirectory, BOOTSTRAP_MARKER);
        if (marker.isFile()) return;

        File modsDirectory = new File(gameDirectory, "mods");
        FileUtils.ensureDirectory(modsDirectory);
        for (String project : PERFORMANCE_MODS) {
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
                "renderDistance:6\n" +
                "simulationDistance:5\n" +
                "entityDistanceScaling:0.5\n" +
                "graphicsMode:0\n" +
                "particles:1\n" +
                "mipmapLevels:2\n" +
                "biomeBlendRadius:0\n" +
                "maxFps:60\n" +
                "enableVsync:false\n";
        Tools.write(options, lowEndDefaults);
    }

    private static void ensureServerEntry(File gameDirectory) throws IOException {
        File serversFile = new File(gameDirectory, "servers.dat");
        if (serversFile.exists()) return;
        FileUtils.ensureParentDirectory(serversFile);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(serversFile))))) {
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

    private static void writeStringTag(DataOutputStream output, String name, String value) throws IOException {
        output.writeByte(8); // TAG_String
        output.writeUTF(name);
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(encoded.length);
        output.write(encoded);
    }
}
