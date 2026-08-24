package net.kdt.pojavlaunch.authenticator.accounts;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.Keep;

import com.google.gson.JsonParseException;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.AuthType;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.JSONUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

@Keep
public class Account {
    private static final int SKIN_CONNECT_TIMEOUT_MS = 10_000;
    private static final int SKIN_READ_TIMEOUT_MS = 15_000;
    private static final int MAX_SKIN_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SKIN_DIMENSION = 2048;

    public transient File mSaveLocation;
    public String accessToken = "0";
    public String profileId = "00000000-0000-0000-0000-000000000000";
    public String username = "Steve";
    public AuthType authType = AuthType.LOCAL;
    public boolean isMicrosoft = false;
    public String refreshToken = "0";
    public String xuid;
    public long expiresAt;
    private transient Bitmap mFaceCache;

    protected Account() {}

    public void updateSkinFace() {
        String skinFaceUrlTemplate = authType == null ? null : authType.skinUrl;
        if(skinFaceUrlTemplate == null) return;

        HttpURLConnection connection = null;
        Bitmap skinBitmap = null;
        Bitmap skinFace = null;
        try {
            URL url = new URL(String.format(Locale.ROOT, skinFaceUrlTemplate, username));
            validateSkinUrl(url);

            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(SKIN_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(SKIN_READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", Tools.APP_NAME);
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Skin service returned HTTP " + connection.getResponseCode());
            }
            int declaredLength = connection.getContentLength();
            if (declaredLength > MAX_SKIN_BYTES) {
                throw new IOException("Skin image exceeds safety limit");
            }

            byte[] skinBytes;
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream(
                         declaredLength > 0 ? Math.min(declaredLength, MAX_SKIN_BYTES) : 64 * 1024)) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_SKIN_BYTES) throw new IOException("Skin image exceeds safety limit");
                    output.write(buffer, 0, read);
                }
                if (total <= 0) throw new IOException("Skin image is empty");
                skinBytes = output.toByteArray();
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(skinBytes, 0, skinBytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > MAX_SKIN_DIMENSION || bounds.outHeight > MAX_SKIN_DIMENSION) {
                throw new IOException("Skin image dimensions exceed safety limit");
            }

            skinBitmap = BitmapFactory.decodeByteArray(skinBytes, 0, skinBytes.length);
            if(skinBitmap == null) throw new IOException("Skin image could not be decoded");
            skinFace = new SkinHeadRenderer().render(100, skinBitmap);
            if(skinFace == null) throw new IOException("Skin face could not be rendered");

            File skinFile = getSkinFaceFile();
            FileUtils.ensureParentDirectory(skinFile);
            try(FileOutputStream fileOutputStream = new FileOutputStream(skinFile)) {
                if (!skinFace.compress(Bitmap.CompressFormat.WEBP, 90, fileOutputStream)) {
                    throw new IOException("Skin face could not be stored");
                }
            }
            mFaceCache = null;
            Log.i("SkinLoader", "Skin face refreshed securely");
        } catch (Exception error) {
            Log.w("SkinLoader", "Could not refresh skin face", error);
        } finally {
            if (skinBitmap != null && !skinBitmap.isRecycled()) skinBitmap.recycle();
            if (skinFace != null && !skinFace.isRecycled()) skinFace.recycle();
            if (connection != null) connection.disconnect();
        }
    }

    private static void validateSkinUrl(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Refusing non-HTTPS skin endpoint");
        }
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        if (!"mineskin.eu".equals(host)) {
            throw new IOException("Refusing unexpected skin host");
        }
    }

    public boolean isLocal(){
        return accessToken == null || accessToken.equals("0");
    }

    public void save() throws IOException {
        FileUtils.ensureParentDirectory(mSaveLocation);
        JSONUtils.writeToFile(mSaveLocation, this);
    }

    public Account reload() {
        try {
            Account account = JSONUtils.readFromFile(mSaveLocation, Account.class);
            if(account == null) return null;
            account.mSaveLocation = mSaveLocation;
            return account;
        }catch (IOException | JsonParseException e) {
            return null;
        }
    }

    public Bitmap getSkinFace(){
        if(isLocal()) return null;
        File skinFaceFile = getSkinFaceFile();
        if(!skinFaceFile.exists()) return null;
        if(mFaceCache == null) {
            mFaceCache = BitmapFactory.decodeFile(skinFaceFile.getAbsolutePath());
        }
        return mFaceCache;
    }

    private File getSkinFaceFile() {
        return new File(Tools.DIR_CACHE,  "skin-face-" + profileId +"-"+authType.name() + ".webp");
    }
}
