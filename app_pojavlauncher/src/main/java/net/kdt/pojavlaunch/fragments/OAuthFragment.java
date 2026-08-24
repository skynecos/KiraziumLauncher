package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraCore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import git.artdeell.mojo.R;

public class OAuthFragment extends WebViewCompletionFragment {

    private static final String QUERY_ERROR_NAME = "error";
    private static final String QUERY_ERROR_DESCRIPTION = "error_description";
    private static final String QUERY_OAUTH_CODE = "code";
    private static final String QUERY_OAUTH_STATE = "state";
    private static final String ERROR_ACCESS_DENIED = "access_denied";
    public static final String PKCE_PAYLOAD_PREFIX = "kirazium-pkce:";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String mExtraCoreConstant;
    private final String mExpectedState;
    private final String mCodeVerifier;

    protected OAuthFragment(String trackedUrl, String authUrl, String extraCoreConstant) {
        this(trackedUrl, createSession(authUrl, false), extraCoreConstant);
    }

    protected OAuthFragment(String trackedUrl, String authUrl, String extraCoreConstant,
                            boolean usePkce) {
        this(trackedUrl, createSession(authUrl, usePkce), extraCoreConstant);
    }

    private OAuthFragment(String trackedUrl, OAuthSession session, String extraCoreConstant) {
        super(trackedUrl, session.authUrl);
        mExtraCoreConstant = extraCoreConstant;
        mExpectedState = session.state;
        mCodeVerifier = session.codeVerifier;
    }

    private static OAuthSession createSession(String authUrl, boolean usePkce) {
        String state = randomBase64Url(24);
        String verifier = null;
        StringBuilder securedUrl = new StringBuilder(authUrl);
        securedUrl.append(authUrl.contains("?") ? '&' : '?')
                .append("state=").append(Uri.encode(state));

        if (usePkce) {
            verifier = randomBase64Url(32);
            String challenge = sha256Base64Url(verifier);
            securedUrl.append("&code_challenge=").append(Uri.encode(challenge))
                    .append("&code_challenge_method=S256");
        }
        return new OAuthSession(securedUrl.toString(), state, verifier);
    }

    private static String randomBase64Url(int byteCount) {
        byte[] bytes = new byte[byteCount];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String sha256Base64Url(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(hashed, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void displayError(Context context, Uri uri) {
        String errorMessage = uri.getQueryParameter(QUERY_ERROR_DESCRIPTION);
        if(errorMessage == null) errorMessage = uri.getQueryParameter(QUERY_ERROR_NAME);
        if(errorMessage == null) errorMessage = getString(R.string.oauth_unknown_error);
        Tools.dialog(context, getString(R.string.global_error), errorMessage);
    }

    @Override
    protected void signalCompletion(String fullUrl) {
        FragmentActivity activity = getActivity();
        if(activity == null) return;
        Uri uri = Uri.parse(fullUrl);

        String returnedState = uri.getQueryParameter(QUERY_OAUTH_STATE);
        if (!constantTimeEquals(mExpectedState, returnedState)) {
            Tools.dialog(activity, getString(R.string.global_error),
                    getString(R.string.oauth_unknown_error));
            activity.onBackPressed();
            return;
        }

        String error = uri.getQueryParameter(QUERY_ERROR_NAME);
        String code = uri.getQueryParameter(QUERY_OAUTH_CODE);
        if(code == null) {
            activity.onBackPressed();
            if(ERROR_ACCESS_DENIED.equals(error)) return;
            displayError(activity, uri);
            return;
        }

        String completionValue = code;
        if (mCodeVerifier != null) {
            // Keep the PKCE verifier transient and in-memory only; the background login consumes it immediately.
            completionValue = PKCE_PAYLOAD_PREFIX + mCodeVerifier + ":" + code;
        }
        ExtraCore.setValue(mExtraCoreConstant, completionValue);
        Toast.makeText(activity, R.string.oauth_web_complete, Toast.LENGTH_SHORT).show();
        Tools.backToMainMenu(activity);
    }

    private static final class OAuthSession {
        final String authUrl;
        final String state;
        final String codeVerifier;

        OAuthSession(String authUrl, String state, String codeVerifier) {
            this.authUrl = authUrl;
            this.state = state;
            this.codeVerifier = codeVerifier;
        }
    }
}
