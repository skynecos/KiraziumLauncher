package net.kdt.pojavlaunch.utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Security-sensitive download helpers used only by Kirazium-controlled online stores. */
public final class KiraziumSecureDownloads {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 5;

    private KiraziumSecureDownloads() {}

    public static String downloadModrinthString(String url, int maxBytes) throws IOException {
        return new String(downloadModrinthBytes(url, maxBytes), StandardCharsets.UTF_8);
    }

    public static byte[] downloadModrinthBytes(String url, int maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        HttpURLConnection connection = null;
        try {
            connection = openModrinthConnection(url);
            requireSuccess(connection);
            int declaredLength = connection.getContentLength();
            if (declaredLength > maxBytes) throw new IOException("Remote response exceeds safety limit");

            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream(
                         declaredLength > 0 ? Math.min(declaredLength, maxBytes) : 32 * 1024)) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) throw new IOException("Remote response exceeds safety limit");
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static void downloadVerifiedModrinthFile(String url, File destination,
                                                     String sha512, long expectedSize,
                                                     long maxBytes) throws IOException {
        if (destination == null) throw new IllegalArgumentException("destination is null");
        if (sha512 == null || !sha512.matches("[0-9a-fA-F]{128}")) {
            throw new IOException("Modrinth SHA-512 is missing or malformed");
        }
        if (expectedSize <= 0L || expectedSize > maxBytes) {
            throw new IOException("Modrinth file size is outside safety limits");
        }

        if (destination.isFile()) {
            if (destination.length() == expectedSize && verifySha512(destination, sha512)) return;
            if (!destination.delete()) throw new IOException("Could not replace invalid existing file");
        }

        FileUtils.ensureParentDirectory(destination);
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        if (temporary.isFile() && !temporary.delete()) {
            throw new IOException("Could not replace stale partial download");
        }

        HttpURLConnection connection = null;
        try {
            connection = openModrinthConnection(url);
            requireSuccess(connection);

            int declaredLength = connection.getContentLength();
            if (declaredLength > 0 && declaredLength != expectedSize) {
                throw new IOException("Modrinth Content-Length does not match metadata");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            long downloaded = 0L;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    downloaded += read;
                    if (downloaded > expectedSize || downloaded > maxBytes) {
                        throw new IOException("Downloaded file exceeds expected size");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.flush();
            }

            if (downloaded != expectedSize) {
                throw new IOException("Downloaded file size does not match Modrinth metadata");
            }
            if (!MessageDigest.isEqual(decodeHexSha512(sha512), digest.digest())) {
                throw new IOException("Downloaded file failed SHA-512 verification");
            }

            if (destination.isFile() && !destination.delete()) {
                throw new IOException("Could not replace destination file");
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("Could not finalize verified download");
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-512 unavailable", impossible);
        } finally {
            if (connection != null) connection.disconnect();
            if (temporary.isFile() && !temporary.equals(destination)) temporary.delete();
        }
    }

    private static boolean verifySha512(File file, String expectedHex) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return MessageDigest.isEqual(decodeHexSha512(expectedHex), digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-512 unavailable", impossible);
        }
    }

    private static byte[] decodeHexSha512(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-fA-F]{128}")) {
            throw new IOException("Malformed SHA-512");
        }
        byte[] output = new byte[64];
        for (int i = 0; i < output.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IOException("Malformed SHA-512");
            output[i] = (byte) ((high << 4) | low);
        }
        return output;
    }

    private static HttpURLConnection openModrinthConnection(String address) throws IOException {
        URL current = new URL(address);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            validateModrinthUrl(current);
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", DownloadUtils.USER_AGENT);
            connection.setRequestProperty("Accept", "*/*");

            int responseCode = connection.getResponseCode();
            if (!isRedirect(responseCode)) return connection;

            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.trim().isEmpty() || redirects == MAX_REDIRECTS) {
                throw new IOException("Invalid Modrinth redirect");
            }
            current = new URL(current, location);
        }
        throw new IOException("Too many Modrinth redirects");
    }

    private static void validateModrinthUrl(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Refusing non-HTTPS Modrinth URL");
        }
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (!"api.modrinth.com".equals(host) && !"cdn.modrinth.com".equals(host)) {
            throw new IOException("Refusing unexpected Modrinth host: " + host);
        }
    }

    private static void requireSuccess(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        if (code < 200 || code > 299) throw new IOException("Remote server returned HTTP " + code);
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308;
    }
}
