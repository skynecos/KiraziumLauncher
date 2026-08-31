package com.kirazium.aniziumcapture;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private TextView statusView;
    private EditText urlView;
    private String captureScript;
    private String lastCapture = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        captureScript = readAsset("capture.js");
        buildUi();
        configureWebView();
        webView.loadUrl("https://anizium.co/");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);

        urlView = new EditText(this);
        urlView.setSingleLine(true);
        urlView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlView.setHint("Anizium bölüm linkini yapıştır");
        root.addView(urlView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button openButton = new Button(this);
        openButton.setText("Aç");
        openButton.setOnClickListener(v -> {
            String u = urlView.getText().toString().trim();
            if (u.isEmpty()) u = "https://anizium.co/";
            webView.loadUrl(u);
        });
        buttons.addView(openButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button hookButton = new Button(this);
        hookButton.setText("Dinlemeyi Aç");
        hookButton.setOnClickListener(v -> injectCaptureScript());
        buttons.addView(hookButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button copyButton = new Button(this);
        copyButton.setText("Sonucu Kopyala");
        copyButton.setOnClickListener(v -> copyLastCapture());
        buttons.addView(copyButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setText("Anizium'a giriş yap. Bölümü açmadan önce 'Dinlemeyi Aç'a bas.");
        statusView.setTextIsSelectable(true);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new CaptureBridge(), "KiraziumBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                urlView.setText(url);
                injectCaptureScript();
            }
        });
    }

    private void injectCaptureScript() {
        if (captureScript == null || captureScript.isEmpty()) {
            setStatus("capture.js okunamadı.");
            return;
        }
        webView.evaluateJavascript(captureScript, value -> setStatus(
                "Dinleme aktif. Şimdi istediğin bölümü aç/yenile; video + altyazı cevabı yakalanacak."));
    }

    private void copyLastCapture() {
        if (lastCapture.isEmpty()) {
            Toast.makeText(this, "Henüz video/altyazı yakalanmadı.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Anizium media", lastCapture));
        Toast.makeText(this, "Temizlenmiş video + altyazı bilgisi kopyalandı.", Toast.LENGTH_LONG).show();
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusView.setText(text));
    }

    private String summarize(String raw) {
        try {
            JSONObject obj = new JSONObject(raw);
            JSONObject payload = obj.optJSONObject("payload");
            if (payload == null) payload = obj;

            JSONArray videos = payload.optJSONArray("videos");
            JSONArray subtitles = payload.optJSONArray("subtitles");
            int videoCount = videos == null ? 0 : videos.length();
            int subtitleCount = subtitles == null ? 0 : subtitles.length();

            String tr = "";
            if (subtitles != null) {
                for (int i = 0; i < subtitles.length(); i++) {
                    JSONObject s = subtitles.optJSONObject(i);
                    if (s == null) continue;
                    String name = s.optString("name", "");
                    String group = s.optString("group", "");
                    String normalized = (name + " " + group).toLowerCase(new Locale("tr", "TR"));
                    if (normalized.contains("türk") || normalized.contains("turk") || normalized.matches(".*\\btr\\b.*")) {
                        tr = s.optString("link", "");
                        if (!tr.isEmpty()) break;
                    }
                }
            }

            String result = "Yakalandı: " + videoCount + " video kaynağı, " + subtitleCount + " altyazı.";
            if (!tr.isEmpty()) result += "\nTürkçe altyazı bulundu ✅\n" + tr;
            else if (subtitleCount > 0) result += "\nAltyazılar bulundu; Türkçe etiketi otomatik eşleşmedi.";
            return result;
        } catch (Exception e) {
            return "Yanıt yakalandı ama özetlenemedi: " + e.getMessage();
        }
    }

    private String readAsset(String name) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    public final class CaptureBridge {
        @JavascriptInterface
        public void capture(String sanitizedJson) {
            // The injected script sends ONLY sanitized media/subtitle URLs and labels.
            // It never sends cookies, passwords, Authorization, x-api-key, or request headers.
            lastCapture = sanitizedJson == null ? "" : sanitizedJson;
            setStatus(summarize(lastCapture));
        }

        @JavascriptInterface
        public void status(String message) {
            if (message != null && !message.isEmpty()) setStatus(message);
        }
    }
}
