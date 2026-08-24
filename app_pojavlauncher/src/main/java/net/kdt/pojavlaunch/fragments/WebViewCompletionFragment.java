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

import git.artdeell.mojo.R;

public abstract class WebViewCompletionFragment extends Fragment {
    private final String mTrackedUrl;
    private final String mAuthUrl;
    private WebView mWebview;
    private boolean mBlankClient = true;
    private boolean mIsCompleted = false;

    protected WebViewCompletionFragment(String mTrackedUrl, String mAuthUrl) {
        this.mTrackedUrl = mTrackedUrl;
        this.mAuthUrl = mAuthUrl;
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
                Log.e("OAuthWebView", "Blocked non-HTTPS OAuth start URL");
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

    private boolean isAllowedWebUrl(String url) {
        if (url == null) return false;
        if ("about:blank".equalsIgnoreCase(url)) return true;
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
    }

    /** Client that only permits HTTPS navigation plus the exact OAuth callback scheme. */
    class WebViewTrackClient extends WebViewClient {
        private boolean handleUrl(String url) {
            if(isTrackedUrl(url)) {
                internalSignalCompletion(url);
                return true;
            }
            if (!isAllowedWebUrl(url)) {
                Log.w("OAuthWebView", "Blocked non-HTTPS OAuth navigation");
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
