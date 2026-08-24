package net.kdt.pojavlaunch.fragments;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.util.Locale;

import git.artdeell.mojo.R;

public abstract class WebViewCompletionFragment extends Fragment {
    private final String mTrackedUrl;
    private final String mAuthUrl;
    private final String mAuthHost;
    private WebView mWebview;
    private boolean mBlankClient = true;
    private boolean mIsCompleted = false;

    protected WebViewCompletionFragment(String mTrackedUrl, String mAuthUrl) {
        this.mTrackedUrl = mTrackedUrl;
        this.mAuthUrl = mAuthUrl;
        Uri authUri = Uri.parse(mAuthUrl);
        String authHost = authUri.getHost();
        this.mAuthHost = authHost == null ? null : authHost.toLowerCase(Locale.ROOT);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mWebview = (WebView) inflater.inflate(R.layout.fragment_microsoft_login, container, false);
        setWebViewSettings();
        if(savedInstanceState == null) startNewSession();
        else restoreWebViewState(savedInstanceState);
        return mWebview;
    }

    @SuppressLint({"SetJavaScriptEnabled", "ObsoleteSdkInt"})
    private void setWebViewSettings() {
        WebSettings settings = mWebview.getSettings();

        // JavaScript is required by the identity providers. Everything unrelated to HTTPS OAuth is disabled.
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSaveFormData(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        mWebview.setWebViewClient(new WebViewTrackClient());
        mBlankClient = false;
    }

    private void startNewSession() {
        CookieManager.getInstance().removeAllCookies((b)->{
            if (mWebview == null) return;
            mWebview.clearHistory();
            mWebview.clearCache(true);
            mWebview.clearFormData();
            if (!isAllowedWebUrl(mAuthUrl)) {
                Log.e("OAuthWebView", "Blocked untrusted OAuth start URL");
                return;
            }
            mWebview.loadUrl(mAuthUrl);
        });
    }

    private void restoreWebViewState(Bundle savedInstanceState) {
        Log.i("MSAuthFragment","Restoring state...");
        if(mWebview.restoreState(savedInstanceState) == null) {
            Log.w("MSAuthFragment", "Failed to restore state, starting afresh");
            startNewSession();
            return;
        }

        String restoredUrl = mWebview.getUrl();
        if (restoredUrl != null && !isTrackedUrl(restoredUrl) && !isAllowedWebUrl(restoredUrl)) {
            Log.w("OAuthWebView", "Discarded restored OAuth state on an untrusted host");
            startNewSession();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if(mBlankClient) {
            mWebview.setWebViewClient(new WebViewTrackClient());
            mBlankClient = false;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        mWebview.setWebViewClient(new WebViewClient());
        mBlankClient = true;
        super.onSaveInstanceState(outState);
        mWebview.saveState(outState);
    }

    public boolean canGoBack(){ return mWebview != null && mWebview.canGoBack();}
    public void goBack(){ if (mWebview != null) mWebview.goBack();}

    private boolean isTrackedUrl(String url) {
        if (url == null || mTrackedUrl == null || mTrackedUrl.isEmpty()) return false;
        Uri callback = Uri.parse(url);
        String callbackScheme = callback.getScheme();
        if (callbackScheme == null) return false;

        Uri tracked = Uri.parse(mTrackedUrl);
        String expectedScheme = tracked.getScheme();
        if (expectedScheme == null) expectedScheme = mTrackedUrl;
        if (!expectedScheme.equalsIgnoreCase(callbackScheme)) return false;

        // For tracked URLs that specify host/path, preserve the original exact-prefix requirement too.
        if (mTrackedUrl.contains("://")) {
            return url.regionMatches(true, 0, mTrackedUrl, 0, mTrackedUrl.length());
        }
        return true;
    }

    private static boolean isSameOrSubdomain(String host, String trustedDomain) {
        if (host == null || trustedDomain == null || trustedDomain.isEmpty()) return false;
        return host.equals(trustedDomain) || host.endsWith("." + trustedDomain);
    }

    private boolean isAllowedIdentityHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);

        // Always trust the provider that initiated this OAuth session (for example account.ely.by).
        if (isSameOrSubdomain(normalized, mAuthHost)) return true;

        // Microsoft authentication may legitimately move between these Microsoft-controlled domains.
        return isSameOrSubdomain(normalized, "live.com")
                || isSameOrSubdomain(normalized, "microsoft.com")
                || isSameOrSubdomain(normalized, "microsoftonline.com")
                || isSameOrSubdomain(normalized, "xbox.com")
                || isSameOrSubdomain(normalized, "xboxlive.com");
    }

    private boolean isAllowedWebUrl(String url) {
        if (url == null) return false;
        if ("about:blank".equalsIgnoreCase(url)) return true;
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme())
                && isAllowedIdentityHost(uri.getHost());
    }

    /** Client that only permits trusted HTTPS identity hosts plus the exact OAuth callback scheme. */
    class WebViewTrackClient extends WebViewClient {
        private boolean handleUrl(String url) {
            if(isTrackedUrl(url)) {
                internalSignalCompletion(url);
                return true;
            }
            if (!isAllowedWebUrl(url)) {
                Log.w("OAuthWebView", "Blocked untrusted OAuth navigation");
                return true;
            }
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return request == null || request.getUrl() == null
                    || handleUrl(request.getUrl().toString());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {}

        @Override
        public void onPageFinished(WebView view, String url) {
            if(isTrackedUrl(url)) internalSignalCompletion(url);
        }
    }

    private void internalSignalCompletion(String fullUrl) {
        if(mIsCompleted) return;
        mIsCompleted = true;
        signalCompletion(fullUrl);
    }

    protected abstract void signalCompletion(String fullUrl);
}
