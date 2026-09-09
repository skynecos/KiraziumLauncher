package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installs Modrinth .mrpack files into an existing Minecraft game directory. */
public final class MrpackInstaller {
    private static final String INDEX_NAME = "modrinth.index.json";
    private static final String USER_AGENT = "KiraziumLauncher/1.0";
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private MrpackInstaller() {
    }

    public static final class Result {
        public final String name;
        public final String versionId;
        public final int downloadedFiles;
        public final int extractedFiles;

        Result(String name, String versionId, int downloadedFiles, int extractedFiles) {
            this.name = name;
            this.versionId = versionId;
            this.downloadedFiles = downloadedFiles;
            this.extractedFiles = extractedFiles;
        }
    }

    public static Result install(Context context, Uri source, File gameDirectory,
                                 String currentVersionId) throws IOException {
        if (context == null) throw new IOException("Uygulama bağlamı bulunamadı");
        if (source == null) throw new IOException("Modpack dosyası seçilmedi");
        if (gameDirectory == null) throw new IOException("Oyun klasörü bulunamadı");

        FileUtils.ensureDirectory(gameDirectory);
        File cachedPack = File.createTempFile("kirazium-modpack-", ".mrpack", context.getCacheDir());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(source)) {
                if (input == null) throw new IOException("Seçilen modpack dosyası açılamadı");
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(cachedPack))) {
                    copy(input, output);
                }
            }

            try (ZipFile zip = new ZipFile(cachedPack)) {
                ZipEntry indexEntry = zip.getEntry(INDEX_NAME);
                if (indexEntry == null || indexEntry.isDirectory()) {
                    throw new IOException("Geçersiz .mrpack: modrinth.index.json bulunamadı");
                }

                JSONObject index = parseJson(readEntry(zip, indexEntry), "modrinth.index.json bozuk");
                validateIndex(index, currentVersionId);

                int downloaded = installManifestFiles(context, index, gameDirectory);
                int extracted = extractOverrides(zip, "overrides/", gameDirectory);
                extracted += extractOverrides(zip, "client-overrides/", gameDirectory);

                String name = index.optString("name", "Modpack");
                String versionId = index.optString("versionId", "");
                return new Result(name, versionId, downloaded, extracted);
            }
        } finally {
            if (cachedPack.exists() && !cachedPack.delete()) cachedPack.deleteOnExit();
        }
    }

    private static void validateIndex(JSONObject index, String currentVersionId) throws IOException {
        int formatVersion = index.optInt("formatVersion", -1);
        if (formatVersion != 1) {
            throw new IOException("Desteklenmeyen .mrpack formatı: " + formatVersion);
        }
        if (!"minecraft".equalsIgnoreCase(index.optString("game", ""))) {
            throw new IOException("Bu paket bir Minecraft modpack'i değil");
        }

        JSONObject dependencies = index.optJSONObject("dependencies");
        if (dependencies == null) return;

        String minecraft = dependencies.optString("minecraft", "");
        if (!minecraft.isEmpty() && currentVersionId != null && !currentVersionId.isEmpty()
                && !currentVersionId.contains(minecraft)) {
            throw new IOException("Modpack Minecraft " + minecraft
                    + " istiyor. Seçili profil: " + currentVersionId);
        }

        if (dependencies.has("forge") || dependencies.has("neoforge")
                || dependencies.has("quilt-loader")) {
            throw new IOException("Bu modpack'in loader'ı henüz desteklenmiyor. Şimdilik Fabric .mrpack kullanın.");
        }

        if (dependencies.has("fabric-loader") && currentVersionId != null
                && !currentVersionId.isEmpty()) {
            String lower = currentVersionId.toLowerCase(Locale.ROOT);
            if (!lower.contains("fabric")) {
                throw new IOException("Bu modpack Fabric istiyor ancak seçili profil Fabric değil");
            }
        }
    }

    private static int installManifestFiles(Context context, JSONObject index, File gameDirectory)
            throws IOException {
        JSONArray files = index.optJSONArray("files");
        if (files == null) return 0;

        int installed = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject fileObject = files.optJSONObject(i);
            if (fileObject == null) continue;

            JSONObject env = fileObject.optJSONObject("env");
            if (env != null && "unsupported".equalsIgnoreCase(env.optString("client", ""))) {
                continue;
            }

            String path = fileObject.optString("path", "");
            if (path.isEmpty()) throw new IOException("Modpack içinde boş dosya yolu var");
            File destination = safeDestination(gameDirectory, path);

            JSONObject hashes = fileObject.optJSONObject("hashes");
            String algorithm = null;
            String expectedHash = null;
            if (hashes != null) {
                if (hashes.has("sha512")) {
                    algorithm = "SHA-512";
                    expectedHash = hashes.optString("sha512", "");
                } else if (hashes.has("sha1")) {
                    algorithm = "SHA-1";
                    expectedHash = hashes.optString("sha1", "");
                }
            }

            if (destination.isFile() && algorithm != null && !expectedHash.isEmpty()
                    && expectedHash.equalsIgnoreCase(hash(destination, algorithm))) {
                continue;
            }

            JSONArray downloads = fileObject.optJSONArray("downloads");
            if (downloads == null || downloads.length() == 0) {
                throw new IOException("İndirme adresi olmayan modpack dosyası: " + path);
            }

            File temp = File.createTempFile("kirazium-mrpack-file-", ".tmp", context.getCacheDir());
            try {
                IOException lastError = null;
                boolean downloaded = false;
                for (int d = 0; d < downloads.length(); d++) {
                    String downloadUrl = downloads.optString(d, "");
                    if (downloadUrl.isEmpty()) continue;
                    try {
                        download(downloadUrl, temp);
                        if (algorithm != null && !expectedHash.isEmpty()) {
                            String actual = hash(temp, algorithm);
                            if (!expectedHash.equalsIgnoreCase(actual)) {
                                throw new IOException("Hash doğrulaması başarısız: " + path);
                            }
                        }
                        downloaded = true;
                        break;
                    } catch (IOException exception) {
                        lastError = exception;
                    }
                }
                if (!downloaded) {
                    if (lastError != null) throw lastError;
                    throw new IOException("Dosya indirilemedi: " + path);
                }

                installFile(temp, destination, isModJar(path));
                installed++;
            } finally {
                if (temp.exists() && !temp.delete()) temp.deleteOnExit();
            }
        }
        return installed;
    }

    private static int extractOverrides(ZipFile zip, String prefix, File gameDirectory)
            throws IOException {
        int extracted = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String entryName = entry.getName();
            if (!entryName.startsWith(prefix)) continue;

            String relativePath = entryName.substring(prefix.length());
            if (relativePath.isEmpty()) continue;
            File destination = safeDestination(gameDirectory, relativePath);

            if (entry.isDirectory()) {
                FileUtils.ensureDirectory(destination);
                continue;
            }

            if (isModJar(relativePath)) {
                File temp = File.createTempFile("kirazium-override-mod-", ".jar");
                try {
                    try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
                         OutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
                        copy(input, output);
                    }
                    installFile(temp, destination, true);
                } finally {
                    if (temp.exists() && !temp.delete()) temp.deleteOnExit();
                }
            } else {
                FileUtils.ensureParentDirectory(destination);
                try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                    copy(input, output);
                }
            }
            extracted++;
        }
        return extracted;
    }

    private static void installFile(File source, File destination, boolean dedupeFabricMod)
            throws IOException {
        FileUtils.ensureParentDirectory(destination);
        if (dedupeFabricMod) removeOlderFabricMod(source, destination);

        File staged = new File(destination.getParentFile(), destination.getName() + ".kirazium-part");
        if (staged.exists() && !staged.delete()) {
            throw new IOException("Geçici dosya temizlenemedi: " + staged.getName());
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(staged))) {
            copy(input, output);
        }

        if (destination.exists() && !destination.delete()) {
            if (!staged.delete()) staged.deleteOnExit();
            throw new IOException("Eski dosya değiştirilemedi: " + destination.getName());
        }
        if (!staged.renameTo(destination)) {
            try (InputStream input = new BufferedInputStream(new FileInputStream(staged));
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                copy(input, output);
            }
            if (!staged.delete()) staged.deleteOnExit();
        }
    }

    private static void removeOlderFabricMod(File incomingJar, File destination) throws IOException {
        String incomingId = readFabricModId(incomingJar);
        if (incomingId == null || incomingId.isEmpty()) return;

        File modsDirectory = destination.getParentFile();
        if (modsDirectory == null || !modsDirectory.isDirectory()) return;
        File[] files = modsDirectory.listFiles();
        if (files == null) return;

        String destinationCanonical = destination.getCanonicalPath();
        for (File existing : files) {
            if (!existing.isFile() || !existing.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            if (existing.getCanonicalPath().equals(destinationCanonical)) continue;
            String existingId = readFabricModId(existing);
            if (incomingId.equals(existingId) && !existing.delete()) {
                throw new IOException("Eski mod kaldırılamadı: " + existing.getName());
            }
        }
    }

    private static String readFabricModId(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry metadata = zip.getEntry("fabric.mod.json");
            if (metadata == null || metadata.isDirectory()) return null;
            JSONObject json = new JSONObject(readEntry(zip, metadata));
            return json.optString("id", null);
        } catch (IOException | JSONException ignored) {
            return null;
        }
    }

    private static File safeDestination(File baseDirectory, String relativePath) throws IOException {
        if (relativePath.indexOf('\0') >= 0) throw new IOException("Geçersiz dosya yolu");
        String normalized = relativePath.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isEmpty()) throw new IOException("Geçersiz dosya yolu");

        File destination = new File(baseDirectory, normalized);
        String base = baseDirectory.getCanonicalPath();
        String target = destination.getCanonicalPath();
        String prefix = base.endsWith(File.separator) ? base : base + File.separator;
        if (!target.startsWith(prefix)) {
            throw new IOException("Güvensiz modpack dosya yolu: " + relativePath);
        }
        return destination;
    }

    private static boolean isModJar(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith("mods/") && normalized.endsWith(".jar");
    }

    private static JSONObject parseJson(String value, String errorMessage) throws IOException {
        try {
            return new JSONObject(value);
        } catch (JSONException exception) {
            throw new IOException(errorMessage, exception);
        }
    }

    private static String readEntry(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void download(String urlString, File destination) throws IOException {
        URL url = new URL(urlString);
        String protocol = url.getProtocol();
        if (!"https".equalsIgnoreCase(protocol) && !"http".equalsIgnoreCase(protocol)) {
            throw new IOException("Desteklenmeyen indirme adresi: " + protocol);
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("İndirme hatası HTTP " + code);
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                copy(input, output);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String hash(File file, String algorithm) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("Hash algoritması kullanılamıyor: " + algorithm, exception);
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }

        byte[] result = digest.digest();
        StringBuilder hex = new StringBuilder(result.length * 2);
        for (byte value : result) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }
}
