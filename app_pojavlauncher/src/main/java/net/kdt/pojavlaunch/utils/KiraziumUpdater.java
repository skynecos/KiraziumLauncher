package net.kdt.pojavlaunch.utils;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import net.kdt.pojavlaunch.PojavApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Automatic GitHub Releases updater for Kirazium Launcher. */
public final class KiraziumUpdater {
    private static final String RELEASE_API =
            "https://api.github.com/repos/skynecos/KiraziumLauncher/releases/latest";
    private static final String APK_ASSET_NAME = "KiraziumLauncher.apk";
    private static final String CHECKSUM_ASSET_NAME = APK_ASSET_NAME + ".sha512";
    private static final byte[] EXPECTED_KIRAZIUM_CERT_SHA256 = new byte[] {
            32, 109, 10, 98, 57, 115, (byte) 209, 43,
            111, 80, (byte) 241, 71, (byte) 214, (byte) 233, 65, 124,
            22, 79, 59, 102, (byte) 163, 12, (byte) 225, (byte) 244,
            24, (byte) 195, 73, 64, (byte) 233, 75, (byte) 183, 85
    };
    private static final String PREFS_NAME = "kirazium_updater";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_PENDING_APK = "pending_apk";
    private static final long CHECK_INTERVAL_MS = 10L * 60L * 1000L;
    private static final long MAX_APK_BYTES = 768L * 1024L * 1024L;
    private static final int MAX_RELEASE_METADATA_BYTES = 1024 * 1024;
    private static final int MAX_CHECKSUM_BYTES = 4096;
    private static final int MAX_REDIRECTS = 5;
    private static final AtomicBoolean CHECK_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean DOWNLOAD_RUNNING = new AtomicBoolean(false);

    private KiraziumUpdater() {}

    public static void checkForUpdates(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        SharedPreferences prefs = prefs(activity);
        String pending = prefs.getString(KEY_PENDING_APK, null);
        if (pending != null && new File(pending).isFile()) return;

        long now = System.currentTimeMillis();
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return;
        if (!CHECK_RUNNING.compareAndSet(false, true)) return;

        PojavApplication.sExecutorService.submit(() -> {
            HttpURLConnection connection = null;
            try {
                connection = openConnection(RELEASE_API);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;

                JSONObject release = new JSONObject(readStream(
                        connection.getInputStream(), MAX_RELEASE_METADATA_BYTES));
                if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) return;

                String remoteVersion = normalizeVersion(release.optString("tag_name", ""));
                long remoteBuild = extractBuildNumber(remoteVersion);
                long currentBuild = getCurrentVersionCode(activity);
                ReleaseAssets assets = findReleaseAssets(release.optJSONArray("assets"));
                if (remoteBuild < 0 || assets == null || remoteBuild <= currentBuild) return;

                long finalRemoteBuild = remoteBuild;
                activity.runOnUiThread(() -> showUpdateDialog(
                        activity, remoteVersion, currentBuild, finalRemoteBuild, assets));
            } catch (Exception ignored) {
                // Update checks must never block or crash the launcher.
            } finally {
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                if (connection != null) connection.disconnect();
                CHECK_RUNNING.set(false);
            }
        });
    }

    /** Continue installing after Android grants the per-app unknown-source permission. */
    public static void resumePendingInstall(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String pendingPath = prefs(activity).getString(KEY_PENDING_APK, null);
        if (pendingPath == null) return;

        File apk = new File(pendingPath);
        if (!apk.isFile()) {
            prefs(activity).edit().remove(KEY_PENDING_APK).apply();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.getPackageManager().canRequestPackageInstalls()) return;

        try {
            if (apk.length() <= 0L || apk.length() > MAX_APK_BYTES) {
                throw new SecurityException("Güncelleme APK boyutu geçersiz.");
            }
            verifyDownloadedApk(activity, apk);
            launchPackageInstaller(activity, apk);
        } catch (Exception error) {
            prefs(activity).edit().remove(KEY_PENDING_APK).apply();
            apk.delete();
            Toast.makeText(activity, "Güncelleme APK'sı doğrulanamadı.", Toast.LENGTH_LONG).show();
        }
    }

    private static void showUpdateDialog(Activity activity, String remoteVersion,
                                         long currentBuild, long remoteBuild,
                                         ReleaseAssets assets) {
        if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
        new AlertDialog.Builder(activity)
                .setTitle("Kirazium güncellemesi hazır")
                .setMessage("Yeni sürüm: " + remoteVersion +
                        "\nMevcut build: " + currentBuild +
                        "\nYeni build: " + remoteBuild +
                        "\n\nAPK imzası ve SHA-512 doğrulandıktan sonra Android yükleyicisi açılacak.")
                .setPositiveButton("İndir ve Güncelle", (dialog, which) ->
                        downloadUpdate(activity, remoteVersion, assets))
                .setNegativeButton("Sonra", null)
                .show();
    }

    private static void downloadUpdate(Activity activity, String version, ReleaseAssets assets) {
        if (!DOWNLOAD_RUNNING.compareAndSet(false, true)) {
            Toast.makeText(activity, "Güncelleme zaten indiriliyor.", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Kirazium " + version);
        progress.setMessage("Güncelleme indiriliyor ve doğrulanıyor...");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        PojavApplication.sExecutorService.submit(() -> {
            File tempFile = null;
            File finalFile = null;
            HttpURLConnection connection = null;
            try {
                if (assets.expectedSize <= 0L || assets.expectedSize > MAX_APK_BYTES) {
                    throw new SecurityException("Release APK boyutu güvenlik sınırının dışında.");
                }

                String expectedSha512 = downloadExpectedSha512(assets.checksumUrl);

                File downloadDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (downloadDir == null) throw new IllegalStateException("İndirme klasörü açılamadı.");
                if (!downloadDir.isDirectory() && !downloadDir.mkdirs()) {
                    throw new IllegalStateException("İndirme klasörü oluşturulamadı.");
                }

                finalFile = new File(downloadDir, "KiraziumLauncher-" + version + ".apk");
                tempFile = new File(downloadDir, "KiraziumLauncher-" + version + ".apk.part");
                if (tempFile.isFile() && !tempFile.delete()) {
                    throw new IllegalStateException("Eski geçici güncelleme dosyası silinemedi.");
                }

                connection = openConnection(assets.apkUrl);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                }

                long contentLength = getContentLength(connection);
                if (contentLength > MAX_APK_BYTES) {
                    throw new SecurityException("Sunucunun bildirdiği APK boyutu çok büyük.");
                }
                if (contentLength > 0L && contentLength != assets.expectedSize) {
                    throw new SecurityException("Release APK boyutu GitHub metadatasıyla eşleşmiyor.");
                }

                long total = assets.expectedSize;
                activity.runOnUiThread(() -> progress.setIndeterminate(false));

                MessageDigest digest = MessageDigest.getInstance("SHA-512");
                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream fileOutput = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[64 * 1024];
                    long downloaded = 0L;
                    int lastProgress = -1;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        downloaded += read;
                        if (downloaded > MAX_APK_BYTES || downloaded > assets.expectedSize) {
                            throw new SecurityException("APK beklenenden büyük geldi.");
                        }

                        digest.update(buffer, 0, read);
                        fileOutput.write(buffer, 0, read);

                        int percent = (int) Math.min(100, (downloaded * 100L) / total);
                        if (percent != lastProgress) {
                            lastProgress = percent;
                            final int shownProgress = percent;
                            activity.runOnUiThread(() -> progress.setProgress(shownProgress));
                        }
                    }
                    fileOutput.flush();

                    if (downloaded != assets.expectedSize) {
                        throw new SecurityException("APK eksik veya fazla byte içeriyor.");
                    }
                }

                byte[] expectedDigest = decodeSha512(expectedSha512);
                byte[] actualDigest = digest.digest();
                if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                    throw new SecurityException("APK SHA-512 doğrulaması başarısız.");
                }

                verifyDownloadedApk(activity, tempFile);

                if (finalFile.isFile() && !finalFile.delete()) {
                    throw new IllegalStateException("Eski güncelleme dosyası silinemedi.");
                }
                if (!tempFile.renameTo(finalFile)) {
                    throw new IllegalStateException("Doğrulanmış APK son dosyaya taşınamadı.");
                }

                prefs(activity).edit().putString(KEY_PENDING_APK, finalFile.getAbsolutePath()).apply();
                File readyApk = finalFile;
                activity.runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    installOrRequestPermission(activity, readyApk);
                });
            } catch (Exception error) {
                if (tempFile != null && tempFile.isFile()) tempFile.delete();
                String message = error.getMessage() == null ? "Bilinmeyen hata" : error.getMessage();
                activity.runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    Toast.makeText(activity, "Güncelleme indirilemedi: " + message, Toast.LENGTH_LONG).show();
                });
            } finally {
                if (connection != null) connection.disconnect();
                DOWNLOAD_RUNNING.set(false);
            }
        });
    }

    private static String downloadExpectedSha512(String checksumUrl) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(checksumUrl);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new SecurityException("SHA-512 dosyası indirilemedi.");
            }
            String checksumText = readStream(connection.getInputStream(), MAX_CHECKSUM_BYTES).trim();
            if (checksumText.isEmpty()) throw new SecurityException("SHA-512 dosyası boş.");

            String firstToken = checksumText.split("\\s+", 2)[0].trim();
            if (!firstToken.matches("[0-9a-fA-F]{128}")) {
                throw new SecurityException("SHA-512 formatı geçersiz.");
            }
            return firstToken.toLowerCase(Locale.ROOT);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void verifyDownloadedApk(Activity activity, File apk) throws Exception {
        PackageManager packageManager = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;

        PackageInfo archive = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = packageManager.getPackageInfo(activity.getPackageName(), flags);

        if (archive == null || archive.packageName == null ||
                !activity.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("APK paket kimliği Kirazium Launcher ile eşleşmiyor.");
        }

        long archiveVersion = getVersionCode(archive);
        long installedVersion = getVersionCode(installed);
        if (archiveVersion <= installedVersion) {
            throw new SecurityException("İndirilen APK mevcut sürümden daha yeni değil.");
        }

        Signature[] archiveSignatures = getSignatures(archive);
        if (!hasPinnedProductionCertificate(archiveSignatures)) {
            throw new SecurityException("APK Kirazium production sertifikasıyla imzalanmamış.");
        }

        Signature[] installedSignatures = getSignatures(installed);
        if (!hasMatchingSignature(archiveSignatures, installedSignatures)) {
            throw new SecurityException("APK imzası mevcut Kirazium Launcher imzasıyla eşleşmiyor.");
        }
    }

    private static Signature[] getSignatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo == null) return null;
            return info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures;
    }

    private static boolean hasPinnedProductionCertificate(Signature[] signatures) throws Exception {
        if (signatures == null || signatures.length == 0) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Signature signature : signatures) {
            if (signature == null) continue;
            byte[] actualDigest = digest.digest(signature.toByteArray());
            if (MessageDigest.isEqual(EXPECTED_KIRAZIUM_CERT_SHA256, actualDigest)) return true;
        }
        return false;
    }

    private static boolean hasMatchingSignature(Signature[] first, Signature[] second) {
        if (first == null || second == null || first.length == 0 || second.length == 0) return false;
        for (Signature left : first) {
            for (Signature right : second) {
                if (left != null && left.equals(right)) return true;
            }
        }
        return false;
    }

    private static long getVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
    }

    private static void installOrRequestPermission(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            prefs(activity).edit().putString(KEY_PENDING_APK, apk.getAbsolutePath()).apply();
            Toast.makeText(activity,
                    "Kirazium Launcher için 'Bu kaynaktan uygulama yükle' iznini aç.",
                    Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception error) {
                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }
            return;
        }
        launchPackageInstaller(activity, apk);
    }

    private static void launchPackageInstaller(Activity activity, File apk) {
        try {
            Uri apkUri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".updateprovider", apk);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            prefs(activity).edit().remove(KEY_PENDING_APK).apply();
            activity.startActivity(installIntent);
        } catch (Exception error) {
            Toast.makeText(activity, "Android yükleyicisi açılamadı.", Toast.LENGTH_LONG).show();
        }
    }

    private static HttpURLConnection openConnection(String address) throws Exception {
        URL current = new URL(address);
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            validateSecureUrl(current);

            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "KiraziumLauncher-Updater");

            int response = connection.getResponseCode();
            if (!isRedirect(response)) return connection;

            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.trim().isEmpty()) {
                throw new SecurityException("Boş HTTPS yönlendirmesi engellendi.");
            }
            if (redirectCount == MAX_REDIRECTS) {
                throw new SecurityException("Çok fazla HTTPS yönlendirmesi engellendi.");
            }
            current = new URL(current, location);
        }
        throw new SecurityException("Güncelleme bağlantısı güvenli biçimde açılamadı.");
    }

    private static void validateSecureUrl(URL url) {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new SecurityException("HTTPS olmayan güncelleme bağlantısı engellendi.");
        }

        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = "api.github.com".equals(host)
                || "github.com".equals(host)
                || "release-assets.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host);
        if (!allowed) {
            throw new SecurityException("Beklenmeyen güncelleme sunucusu engellendi: " + host);
        }
    }

    private static boolean isRedirect(int responseCode) {
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM
                || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                || responseCode == 307
                || responseCode == 308;
    }

    private static String readStream(InputStream input, int maxBytes) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new SecurityException("Sunucu yanıtı güvenlik sınırını aştı.");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static long getContentLength(HttpURLConnection connection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return connection.getContentLengthLong();
        }
        return connection.getContentLength();
    }

    private static byte[] decodeSha512(String hex) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{128}")) {
            throw new SecurityException("SHA-512 biçimi geçersiz.");
        }
        byte[] bytes = new byte[64];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new SecurityException("SHA-512 biçimi geçersiz.");
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    private static ReleaseAssets findReleaseAssets(JSONArray assets) {
        if (assets == null) return null;

        String apkUrl = null;
        String checksumUrl = null;
        long apkSize = -1L;

        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;

            String name = asset.optString("name", "");
            String url = asset.optString("browser_download_url", "");
            if (url.isEmpty()) continue;

            if (APK_ASSET_NAME.equals(name)) {
                apkUrl = url;
                apkSize = asset.optLong("size", -1L);
            } else if (CHECKSUM_ASSET_NAME.equals(name)) {
                checksumUrl = url;
            }
        }

        if (apkUrl == null || checksumUrl == null || apkSize <= 0L || apkSize > MAX_APK_BYTES) {
            return null;
        }
        return new ReleaseAssets(apkUrl, checksumUrl, apkSize);
    }

    private static long extractBuildNumber(String version) {
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+")) return -1L;
        try {
            String[] pieces = version.split("\\.");
            return Long.parseLong(pieces[2]);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static long getCurrentVersionCode(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return getVersionCode(info);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static String normalizeVersion(String version) {
        if (version == null) return "";
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static SharedPreferences prefs(Activity activity) {
        return activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
    }

    private static final class ReleaseAssets {
        final String apkUrl;
        final String checksumUrl;
        final long expectedSize;

        ReleaseAssets(String apkUrl, String checksumUrl, long expectedSize) {
            this.apkUrl = apkUrl;
            this.checksumUrl = checksumUrl;
            this.expectedSize = expectedSize;
        }
    }
}
