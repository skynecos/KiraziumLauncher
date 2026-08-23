package net.kdt.pojavlaunch;

import android.content.*;
import android.os.*;
import android.view.View;

import androidx.appcompat.app.*;

import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.game.GameView;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.*;

import git.artdeell.mojo.R;

import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_IGNORE_NOTCH;

public abstract class BaseActivity extends AppCompatActivity {
    private static final String LOW_GRAPHICS_KEY = "kiraziumLowGraphicsMode";
    private static final String USER_RESOLUTION_KEY = "kiraziumUserResolutionRatio";
    private static final String RESOLUTION_KEY = "resolutionRatio";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleUtils.setLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocaleUtils.setLocale(this);
        applyKiraziumResolutionOverride();
        Tools.setInsetsMode(this, setFullscreen(), shouldIgnoreNotch());
        Tools.getDisplayMetrics(this);
    }

    /** @return Whether the activity should be set as a fullscreen one */
    public boolean setFullscreen(){
        return true;
    }


    @Override
    public void startActivity(Intent i) {
        super.startActivity(i);
        //new Throwable("StartActivity").printStackTrace();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Tools.checkStorageInteractive(this);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        applyKiraziumResolutionOverride();
        Tools.setInsetsMode(this, setFullscreen(), shouldIgnoreNotch());
        refreshLayoutAfterInsets();
    }

    private void applyKiraziumResolutionOverride() {
        SharedPreferences preferences = LauncherPreferences.DEFAULT_PREF;
        if (preferences == null ||
                !preferences.getBoolean(LOW_GRAPHICS_KEY, false) ||
                !preferences.contains(USER_RESOLUTION_KEY)) {
            return;
        }

        int ratio = preferences.getInt(USER_RESOLUTION_KEY, 80);
        ratio = Math.max(5, Math.min(100, ratio));

        // Low graphics used to force resolutionRatio back to 80 before every launch.
        // Keep the user's explicit value authoritative instead.
        if (preferences.getInt(RESOLUTION_KEY, ratio) != ratio) {
            preferences.edit().putInt(RESOLUTION_KEY, ratio).apply();
        }
        LauncherPreferences.PREF_SCALE_FACTOR = ratio / 100f;
    }

    /**
     * Insets and display-cutout changes are asynchronous on modern Android. Re-measure the game
     * surface and touch controls once Android has applied the new safe-area/fullscreen geometry.
     */
    private void refreshLayoutAfterInsets() {
        View decorView = getWindow().getDecorView();
        decorView.requestApplyInsets();
        decorView.requestLayout();
        decorView.post(() -> {
            Tools.getDisplayMetrics(this);

            View content = findViewById(android.R.id.content);
            if (content != null) content.requestLayout();

            ControlLayout controls = findViewById(R.id.main_control_layout);
            if (controls == null) return;
            controls.requestLayout();
            controls.post(() -> {
                controls.refreshControlButtonPositions();
                GameView gameView = controls.findViewById(R.id.main_game_render_view);
                if (gameView != null) gameView.refreshSize();
            });
        });
    }

    /** @return Whether or not the notch should be ignored */
    protected boolean shouldIgnoreNotch(){
        return PREF_IGNORE_NOTCH;
    }
}
