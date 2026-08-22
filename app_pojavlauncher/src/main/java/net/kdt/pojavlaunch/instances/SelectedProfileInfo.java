package net.kdt.pojavlaunch.instances;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.JSONUtils;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/** Resolves the actual Minecraft version and mod loader of a selected launcher instance. */
public final class SelectedProfileInfo {
    public enum Loader {
        VANILLA("minecraft", "Vanilla"),
        FABRIC("fabric", "Fabric"),
        FORGE("forge", "Forge"),
        NEOFORGE("neoforge", "NeoForge"),
        QUILT("quilt", "Quilt");

        public final String modrinthId;
        public final String displayName;

        Loader(String modrinthId, String displayName) {
            this.modrinthId = modrinthId;
            this.displayName = displayName;
        }
    }

    public final String gameVersion;
    public final Loader loader;

    private SelectedProfileInfo(String gameVersion, Loader loader) {
        this.gameVersion = gameVersion;
        this.loader = loader;
    }

    public boolean supportsMods() {
        return loader != Loader.VANILLA;
    }

    public static SelectedProfileInfo resolve(Instance instance) throws Exception {
        if (instance == null || instance.versionId == null || instance.versionId.trim().isEmpty()) {
            throw new IllegalArgumentException("No selected Minecraft version");
        }

        String currentId = resolveAlias(instance.versionId.trim());
        Loader loader = detectLoaderFromId(currentId);
        String gameVersion = null;

        // Follow inheritsFrom until the vanilla/base Minecraft JSON is reached.
        for (int depth = 0; depth < 12 && currentId != null && !currentId.isEmpty(); depth++) {
            File jsonFile = new File(new File(Tools.DIR_HOME_VERSION, currentId), currentId + ".json");
            if (!jsonFile.isFile()) {
                if (gameVersion == null && looksLikeMinecraftVersion(currentId)) gameVersion = currentId;
                break;
            }

            JsonObject json = JSONUtils.readFromFile(jsonFile, JsonObject.class);
            if (json == null) break;

            Loader detected = detectLoader(json, currentId);
            if (detected != Loader.VANILLA) loader = detected;

            String explicitVersion = firstString(json,
                    "minecraftVersion", "minecraft_version", "gameVersion", "game_version");
            if (gameVersion == null && looksLikeMinecraftVersion(explicitVersion)) {
                gameVersion = explicitVersion;
            }

            String inheritsFrom = getString(json, "inheritsFrom");
            if (inheritsFrom == null || inheritsFrom.trim().isEmpty()) {
                String jsonId = getString(json, "id");
                if (gameVersion == null && looksLikeMinecraftVersion(jsonId)) gameVersion = jsonId;
                if (gameVersion == null && looksLikeMinecraftVersion(currentId)) gameVersion = currentId;
                break;
            }

            currentId = resolveAlias(inheritsFrom.trim());
            if (looksLikeMinecraftVersion(currentId)) gameVersion = currentId;
        }

        if (gameVersion == null) {
            gameVersion = extractGameVersionFromLoaderId(instance.versionId);
        }
        if (gameVersion == null || gameVersion.isEmpty()) {
            throw new IllegalStateException("Minecraft version could not be resolved for " + instance.versionId);
        }

        return new SelectedProfileInfo(gameVersion, loader);
    }

    private static String resolveAlias(String versionId) throws Exception {
        if (!Instance.VERSION_LATEST_RELEASE.equals(versionId) &&
                !Instance.VERSION_LATEST_SNAPSHOT.equals(versionId)) {
            return versionId;
        }

        JSONObject manifest = new JSONObject(DownloadUtils.downloadString(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
        JSONObject latest = manifest.getJSONObject("latest");
        return Instance.VERSION_LATEST_SNAPSHOT.equals(versionId)
                ? latest.getString("snapshot")
                : latest.getString("release");
    }

    private static Loader detectLoader(JsonObject json, String id) {
        Loader fromId = detectLoaderFromId(id);
        if (fromId != Loader.VANILLA) return fromId;

        JsonArray libraries = json.has("libraries") && json.get("libraries").isJsonArray()
                ? json.getAsJsonArray("libraries") : null;
        if (libraries == null) return Loader.VANILLA;

        Loader best = Loader.VANILLA;
        for (JsonElement element : libraries) {
            if (!element.isJsonObject()) continue;
            String name = getString(element.getAsJsonObject(), "name");
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);

            if (lower.startsWith("org.quiltmc:quilt-loader:")) return Loader.QUILT;
            if (lower.startsWith("net.neoforged:neoforge:") ||
                    lower.startsWith("net.neoforged:forge:")) return Loader.NEOFORGE;
            if (lower.startsWith("net.minecraftforge:forge:")) best = Loader.FORGE;
            if (lower.startsWith("net.fabricmc:fabric-loader:") && best == Loader.VANILLA) {
                best = Loader.FABRIC;
            }
        }
        return best;
    }

    private static Loader detectLoaderFromId(String id) {
        if (id == null) return Loader.VANILLA;
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("quilt-loader") || lower.contains("quilt")) return Loader.QUILT;
        if (lower.contains("neoforge") || lower.contains("neo-forge")) return Loader.NEOFORGE;
        if (lower.contains("fabric-loader") || lower.contains("fabric")) return Loader.FABRIC;
        if (lower.contains("forge")) return Loader.FORGE;
        return Loader.VANILLA;
    }

    private static String extractGameVersionFromLoaderId(String id) {
        if (id == null) return null;
        String lower = id.toLowerCase(Locale.ROOT);

        int forge = lower.indexOf("-forge-");
        if (forge > 0) {
            String candidate = id.substring(0, forge);
            if (looksLikeMinecraftVersion(candidate)) return candidate;
        }
        int neo = lower.indexOf("-neoforge-");
        if (neo > 0) {
            String candidate = id.substring(0, neo);
            if (looksLikeMinecraftVersion(candidate)) return candidate;
        }

        if (lower.startsWith("fabric-loader-") || lower.startsWith("quilt-loader-")) {
            String[] parts = id.split("-");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (looksLikeMinecraftVersion(parts[i])) return parts[i];
            }
        }
        return looksLikeMinecraftVersion(id) ? id : null;
    }

    private static boolean looksLikeMinecraftVersion(String value) {
        if (value == null) return false;
        // Release/snapshot/pre-release ids all begin with a Minecraft-style numeric version.
        return value.matches("^(?:1\\.)?\\d+\\.\\d+(?:\\.\\d+)?(?:[-_].+)?$") ||
                value.matches("^\\d{2}w\\d{2}[a-z]$");
    }

    private static String firstString(JsonObject json, String... keys) {
        for (String key : keys) {
            String value = getString(json, key);
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private static String getString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) return null;
        JsonElement value = json.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private SelectedProfileInfo() {
        gameVersion = null;
        loader = Loader.VANILLA;
    }
}
