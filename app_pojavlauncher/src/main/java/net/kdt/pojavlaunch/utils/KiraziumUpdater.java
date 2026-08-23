package net.kdt.pojavlaunch.utils;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Automatic GitHub Releases updater for Kirazium Launcher. */
public final class KiraziumUpdater {
    private static final String RELEASE_API =
            "https://api.github.com/repos/skynecos/KiraziumLauncher/releases/latest";
    private static final String PREFS_NAME = "kirazium_updater";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_PENDING_APK = "pending_apk";
    private static final long CHECK_INTERVAL_MS = 3L * 60L * 60L * 1000L;
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

                JSONObject release = new JSONObject(readStream(connection.getInputStream()));
                if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) return;

                String remoteVersion = normalizeVersion(release.optString("tag_name", ""));
                long remoteBuild = extractBuildNumber(remoteVersion);
                long currentBuild = getCurrentVersionCode(activity);
                String apkUrl = findApkUrl(release.optJSONArray("assets"));
                if (remoteBuild < 0 || apkUrl == null || remoteBuild <= currentBuild) return;

                activity.runOnUiThread(() -> showUpdateDialog(
                        activity, remoteVersion, currentBuild, apkUrl));
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

        launchPackageInstaller(activity, apk);
    }

    private static void showUpdateDialog(Activity activity, String remoteVersion,
                                         long currentBuild, String apkUrl) {
        if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
        new AlertDialog.Builder(activity)
                .setTitle("Kirazium güncellemesi hazır")
                .setMessage("Yeni sürüm: " + remoteVersion + "\nMevcut build: " + currentBuild +
                        "\n\nAPK otomatik indirilecek. Son adımda Android'in Güncelle/Yükle onayına dokunman yeterli.")
                .setPositiveButton("İndir ve Güncelle", (dialog, which) ->
                        downloadUpdate(activity, remoteVersion, apkUrl))
                .setNegativeButton("Sonra", null)
                .show();
    }

    private static void downloadUpdate(Activity activity, String version, String apkUrl) {
        if (!DOWNLOAD_RUNNING.compareAndSet(false, true)) {
            Toast.makeText(activity, "Güncelleme zaten indiriliyor.", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Kirazium " + version);
        progress.setMessage("Güncelleme indiriliyor...");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        PojavApplication.sExecutorService.submit(() -> {
            File output = null;
            HttpURLConnection connection = null;
            try {
                File downloadDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (downloadDir == null) throw new IllegalStateException("İndirme klasörü açılamadı.");
                if (!downloadDir.isDirectory() && !downloadDir.mkdirs()) {
                    throw new IllegalStateException("İndirme klasörü oluşturulamadı.");
                }

                output = new File(downloadDir, "KiraziumLauncher-" + version + ".apk");
                connection = openConnection(apkUrl);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                }

                long total = connection.getContentLength();
                if (total > 0) activity.runOnUiThread(() -> progress.setIndeterminate(false));

                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream fileOutput = new FileOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024];
                    long downloaded = 0L;
                    int lastProgress = -1;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        fileOutput.write(buffer, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int percent = (int) Math.min(100, (downloaded * 100L) / total);
                            if (percent != lastProgress) {
                                lastProgress = percent;
                                final int shownProgress = percent;
                                activity.runOnUiThread(() -> progress.setProgress(shownProgress));
                            }
                        }
                    }
                    fileOutput.flush();
                }

                verifyPackageName(activity, output);
                prefs(activity).edit().putString(KEY_PENDING_APK, output.getAbsolutePath()).apply();
                File readyApk = output;
                activity.runOnUiThread(() -> {
                    if (progress.isShowing()) progress.dismiss();
                    installOrRequestPermission(activity, readyApk);
                });
            } catch (Exception error) {
                if (output != null && output.isFile()) output.delete();
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

    private static void verifyPackageName(Activity activity, File apk) {
        PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (archive == null || archive.packageName == null ||
                !activity.getPackageName().equals(archive.packageName)) {
            throw new SecurityException("APK paket kimliği Kirazium Launcher ile eşleşmiyor.");
        }
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
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "KiraziumLauncher-Updater");
        return connection;
    }

    private static String readStream(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String findApkUrl(JSONArray assets) {
        if (assets == null) return null;
        String fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
            String url = asset.optString("browser_download_url", "");
            if (!name.endsWith(".apk") || url.isEmpty()) continue;
            if (fallback == null) fallback = url;
            if (name.contains("kirazium")) return url;
        }
        return fallback;
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
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
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
}
