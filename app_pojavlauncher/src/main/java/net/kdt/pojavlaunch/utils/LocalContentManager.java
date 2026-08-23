package net.kdt.pojavlaunch.utils;

import net.kdt.pojavlaunch.instances.Instance;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Local resource-pack and mod enable/disable management for the selected instance. */
public final class LocalContentManager {
    private static final String RESOURCE_PACKS_KEY = "resourcePacks:";
    private static final String DISABLED_SUFFIX = ".disabled";
    private static final int MAX_ICON_BYTES = 8 * 1024 * 1024;
    private static final Pattern TOML_LOGO = Pattern.compile(
            "(?mi)^\\s*logoFile\\s*=\\s*[\"']([^\"']+)[\"']");

    private LocalContentManager() {}

    public static final class Entry {
        public final File file;
        public final String fileName;
        public final String displayName;
        public final boolean enabled;

        Entry(File file, String fileName, String displayName, boolean enabled) {
            this.file = file;
            this.fileName = fileName;
            this.displayName = displayName;
            this.enabled = enabled;
        }
    }

    public static List<Entry> listResourcePacks(Instance instance) throws IOException {
        File gameDirectory = requireGameDirectory(instance);
        File resourcePacksDirectory = new File(gameDirectory, "resourcepacks");
        FileUtils.ensureDirectory(resourcePacksDirectory);

        Set<String> enabledRefs = new HashSet<>(readResourcePackRefs(new File(gameDirectory, "options.txt")));
        File[] children = resourcePacksDirectory.listFiles();
        List<Entry> entries = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory() && !child.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    continue;
                }
                String name = child.getName();
                String ref = "file/" + name;
                entries.add(new Entry(child, name, stripPackExtension(name), enabledRefs.contains(ref)));
            }
        }
        sortEntries(entries);
        return entries;
    }

    public static void setResourcePackEnabled(Instance instance, String fileName, boolean enabled)
            throws IOException {
        File gameDirectory = requireGameDirectory(instance);
        File optionsFile = new File(gameDirectory, "options.txt");
        List<String> lines = readLines(optionsFile);

        int resourcePackLine = -1;
        JSONArray packs = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith(RESOURCE_PACKS_KEY)) continue;
            resourcePackLine = i;
            String raw = line.substring(RESOURCE_PACKS_KEY.length()).trim();
            try {
                packs = raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
            } catch (Exception error) {
                throw new IOException("Minecraft resourcePacks ayarı okunamadı.", error);
            }
            break;
        }

        if (packs == null) {
            packs = new JSONArray();
            packs.put("vanilla");
        }

        String ref = "file/" + fileName;
        removeValue(packs, ref);
        if (enabled) packs.put(ref);

        String updatedLine = RESOURCE_PACKS_KEY + packs.toString();
        if (resourcePackLine >= 0) {
            lines.set(resourcePackLine, updatedLine);
        } else {
            lines.add(updatedLine);
        }
        writeLines(optionsFile, lines);
    }

    public static List<Entry> listMods(Instance instance) throws IOException {
        File modsDirectory = new File(requireGameDirectory(instance), "mods");
        FileUtils.ensureDirectory(modsDirectory);

        File[] children = modsDirectory.listFiles();
        List<Entry> entries = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (!child.isFile()) continue;
                String lower = child.getName().toLowerCase(Locale.ROOT);
                boolean enabled = lower.endsWith(".jar");
                boolean disabled = lower.endsWith(".jar" + DISABLED_SUFFIX);
                if (!enabled && !disabled) continue;

                String fileName = child.getName();
                String activeName = disabled
                        ? fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length())
                        : fileName;
                String displayName = activeName.toLowerCase(Locale.ROOT).endsWith(".jar")
                        ? activeName.substring(0, activeName.length() - 4)
                        : activeName;
                entries.add(new Entry(child, fileName, displayName, enabled));
            }
        }
        sortEntries(entries);
        return entries;
    }

    public static void setModEnabled(Entry entry, boolean enabled) throws IOException {
        if (entry == null || entry.file == null || !entry.file.isFile()) {
            throw new IOException("Mod dosyası bulunamadı.");
        }
        if (entry.enabled == enabled) return;

        String currentName = entry.file.getName();
        String targetName;
        if (enabled) {
            if (!currentName.toLowerCase(Locale.ROOT).endsWith(".jar" + DISABLED_SUFFIX)) {
                throw new IOException("Devre dışı mod biçimi tanınmadı.");
            }
            targetName = currentName.substring(0, currentName.length() - DISABLED_SUFFIX.length());
        } else {
            if (!currentName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IOException("Mod JAR dosyası değil.");
            }
            targetName = currentName + DISABLED_SUFFIX;
        }

        File target = new File(entry.file.getParentFile(), targetName);
        if (target.exists()) throw new IOException("Aynı isimde başka bir mod dosyası zaten var.");
        if (!entry.file.renameTo(target)) throw new IOException("Mod durumu değiştirilemedi.");
    }

    /** Returns the real pack.png or mod metadata icon when the archive provides one. */
    public static byte[] loadIconBytes(Entry entry, boolean mod) throws IOException {
        if (entry == null || entry.file == null) return null;
        return mod ? loadModIcon(entry.file) : loadPackIcon(entry.file);
    }

    private static byte[] loadPackIcon(File pack) throws IOException {
        if (pack.isDirectory()) {
            File icon = new File(pack, "pack.png");
            return icon.isFile() ? readLimited(new FileInputStream(icon)) : null;
        }
        if (!pack.isFile()) return null;
        try (ZipFile zip = new ZipFile(pack)) {
            return readZipEntry(zip, "pack.png");
        }
    }

    private static byte[] loadModIcon(File mod) throws IOException {
        if (!mod.isFile()) return null;
        try (ZipFile zip = new ZipFile(mod)) {
            String iconPath = fabricIconPath(zip);
            if (iconPath == null) iconPath = quiltIconPath(zip);
            if (iconPath == null) iconPath = tomlIconPath(zip, "META-INF/neoforge.mods.toml");
            if (iconPath == null) iconPath = tomlIconPath(zip, "META-INF/mods.toml");

            byte[] icon = readZipEntry(zip, iconPath);
            if (icon != null) return icon;

            icon = readZipEntry(zip, "icon.png");
            if (icon != null) return icon;
            icon = readZipEntry(zip, "logo.png");
            if (icon != null) return icon;

            // Last-resort fallback for mods that keep their logo under assets/<modid>/.
            for (ZipEntry candidate : Collections.list(zip.entries())) {
                if (candidate.isDirectory()) continue;
                String lower = candidate.getName().toLowerCase(Locale.ROOT);
                if ((lower.endsWith("/icon.png") || lower.endsWith("/logo.png")) &&
                        candidate.getSize() <= MAX_ICON_BYTES) {
                    return readLimited(zip.getInputStream(candidate));
                }
            }
            return null;
        }
    }

    private static String fabricIconPath(ZipFile zip) {
        try {
            String json = readZipText(zip, "fabric.mod.json");
            if (json == null) return null;
            return iconValue(new JSONObject(json).opt("icon"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String quiltIconPath(ZipFile zip) {
        try {
            String json = readZipText(zip, "quilt.mod.json");
            if (json == null) return null;
            JSONObject root = new JSONObject(json);
            JSONObject loader = root.optJSONObject("quilt_loader");
            JSONObject metadata = loader == null ? null : loader.optJSONObject("metadata");
            String icon = metadata == null ? null : iconValue(metadata.opt("icon"));
            if (icon == null && loader != null) icon = iconValue(loader.opt("icon"));
            return icon;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String iconValue(Object value) {
        if (value instanceof String) return cleanArchivePath((String) value);
        if (!(value instanceof JSONObject)) return null;
        JSONObject sizes = (JSONObject) value;
        Iterator<String> keys = sizes.keys();
        int bestSize = -1;
        String best = null;
        while (keys.hasNext()) {
            String key = keys.next();
            String path = sizes.optString(key, null);
            if (path == null) continue;
            int size;
            try {
                size = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                size = 0;
            }
            if (best == null || size > bestSize) {
                best = path;
                bestSize = size;
            }
        }
        return cleanArchivePath(best);
    }

    private static String tomlIconPath(ZipFile zip, String metadataPath) {
        try {
            String toml = readZipText(zip, metadataPath);
            if (toml == null) return null;
            Matcher matcher = TOML_LOGO.matcher(toml);
            return matcher.find() ? cleanArchivePath(matcher.group(1)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readZipText(ZipFile zip, String path) throws IOException {
        byte[] bytes = readZipEntry(zip, path);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readZipEntry(ZipFile zip, String path) throws IOException {
        String clean = cleanArchivePath(path);
        if (clean == null || clean.isEmpty()) return null;
        ZipEntry entry = zip.getEntry(clean);
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_ICON_BYTES) return null;
        return readLimited(zip.getInputStream(entry));
    }

    private static String cleanArchivePath(String path) {
        if (path == null) return null;
        String clean = path.trim().replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.contains("../")) return null;
        return clean;
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        if (input == null) return null;
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_ICON_BYTES) return null;
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static File requireGameDirectory(Instance instance) throws IOException {
        if (instance == null) throw new IOException("Seçili profil bulunamadı.");
        File gameDirectory = instance.getGameDirectory();
        if (gameDirectory == null) throw new IOException("Profil klasörü bulunamadı.");
        FileUtils.ensureDirectory(gameDirectory);
        return gameDirectory;
    }

    private static List<String> readResourcePackRefs(File optionsFile) throws IOException {
        List<String> lines = readLines(optionsFile);
        for (String line : lines) {
            if (!line.startsWith(RESOURCE_PACKS_KEY)) continue;
            String raw = line.substring(RESOURCE_PACKS_KEY.length()).trim();
            if (raw.isEmpty()) return new ArrayList<>();
            try {
                JSONArray array = new JSONArray(raw);
                List<String> refs = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    String value = array.optString(i, null);
                    if (value != null) refs.add(value);
                }
                return refs;
            } catch (Exception error) {
                throw new IOException("Minecraft resourcePacks ayarı okunamadı.", error);
            }
        }
        return new ArrayList<>();
    }

    private static void removeValue(JSONArray array, String value) {
        for (int i = array.length() - 1; i >= 0; i--) {
            if (value.equals(array.optString(i, null))) array.remove(i);
        }
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        if (!file.isFile()) return lines;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    private static void writeLines(File file, List<String> lines) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) FileUtils.ensureDirectory(parent);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, false), "UTF-8"))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    private static String stripPackExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    private static void sortEntries(List<Entry> entries) {
        Collections.sort(entries, (left, right) ->
                String.CASE_INSENSITIVE_ORDER.compare(left.displayName, right.displayName));
    }
}
