package net.kdt.pojavlaunch.authenticator.impl;

import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.model.OAuthTokenResponse;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import git.artdeell.mojo.R;

public class CommonLoginUtils {

    public static OAuthTokenResponse exchangeAuthCode(URL url, String formData) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Refusing non-HTTPS authentication endpoint");
        }

        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("charset", "utf-8");
        conn.setRequestProperty("Content-Length", Integer.toString(formData.getBytes(StandardCharsets.UTF_8).length));
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setUseCaches(false);
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.connect();
        try(OutputStream wr = conn.getOutputStream()) {
            wr.write(formData.getBytes(StandardCharsets.UTF_8));
        }
        if(conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
                return Tools.GLOBAL_GSON.fromJson(reader, OAuthTokenResponse.class);
            } finally {
                conn.disconnect();
            }
        }else{
            // Never write OAuth error bodies to Logcat: identity providers may echo sensitive data.
            Log.w("CommonLogin", "Authentication request rejected with HTTP " + conn.getResponseCode());
            try {
                return throwResponse(conn);
            } finally {
                conn.disconnect();
            }
        }
    }

    private static OAuthTokenResponse throwResponse(HttpURLConnection conn) throws IOException {
        throw getResponseThrowable(conn);
    }

    /**
     * @param data A series a strings: key1, value1, key2, value2...
     * @return the data converted as a form string for a POST request
     */
    public static String convertToFormData(String... data) throws UnsupportedEncodingException {
        if (data == null || (data.length % 2) != 0) {
            throw new IllegalArgumentException("Form data must contain key/value pairs");
        }
        StringBuilder builder = new StringBuilder();
        for(int i=0; i<data.length; i+=2){
            if (builder.length() > 0) builder.append("&");
            builder.append(URLEncoder.encode(data[i], "UTF-8"))
                    .append("=")
                    .append(URLEncoder.encode(data[i+1], "UTF-8"));
        }
        return builder.toString();
    }

    public static RuntimeException getResponseThrowable(HttpURLConnection conn) throws IOException {
        int responseCode = conn.getResponseCode();
        Log.w("Authentication", "Authentication endpoint returned HTTP " + responseCode);
        if(responseCode == 429) {
            return new PresentedException(R.string.microsoft_login_retry_later);
        }
        String responseMessage = conn.getResponseMessage();
        return new RuntimeException(responseMessage == null ? "Authentication request failed" : responseMessage);
    }
}
