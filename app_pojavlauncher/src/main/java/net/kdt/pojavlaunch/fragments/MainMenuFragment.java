package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.CustomControls;
import net.kdt.pojavlaunch.customcontrols.LayoutConverter;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.instances.KiraziumBootstrap;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";
    private static final int RAM_MIN_MB = 256;
    private static final int RAM_STEP_MB = 8;

    private static final String CONTROL_MODE_PREF = "kiraziumControlMode";
    private static final String CONTROL_MODE_CLASSIC = "classic";
    private static final String CONTROL_MODE_KIRAZIUM = "kirazium";
    private static final String CLASSIC_LAYOUT_PREF = "kiraziumClassicControlLayout";
    private static final String GENERATED_JOYSTICK_LAYOUT = "kirazium_joystick_v1.json";
    private static final String CONTROL_TAG = "KiraziumControls";

    private mcVersionSpinner mVersionSpinner;
    private SeekBar mRamSeekBar;
    private TextView mRamValueText;
    private TextView mRamSummaryText;
    private Button mControlModeButton;
    private int mRamMaxMb;

    private final ActivityResultLauncher<Object> mModInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (data)->{
                if(data != null) Tools.launchModInstaller(requireContext(), data);
            });

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.social_media_button);
        Button mTexturePacksButton = view.findViewById(R.id.texture_packs_button);
        Button mModsButton = view.findViewById(R.id.mods_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);
        View mLowGraphicsCard = view.findViewById(R.id.low_graphics_card);
        SwitchCompat mLowGraphicsSwitch = view.findViewById(R.id.low_graphics_switch);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        installControlModeButton(mTexturePacksButton);

        mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.social_media_invite)));
        mTexturePacksButton.setOnClickListener(v -> Tools.swapFragment(
                requireActivity(), TexturePackFragment.class, TexturePackFragment.TAG, null));
        mModsButton.setOnClickListener(v -> Tools.swapFragment(
                requireActivity(), ModStoreFragment.class, ModStoreFragment.TAG, null));
        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation());
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        mPlayButton.setOnClickListener(v -> {
            if (ensureSelectedControlMode()) {
                ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
            }
        });

        mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));

        mOpenDirectoryButton.setOnClickListener((v)-> openGameDirectory(v.getContext()));

        KiraziumBootstrap.ensureLowGraphicsRamIndependence(requireContext());
        mLowGraphicsSwitch.setChecked(KiraziumBootstrap.isLowGraphicsModeEnabled());
        setupRamControl(view);
        mLowGraphicsCard.setOnClickListener(v ->
                mLowGraphicsSwitch.setChecked(!mLowGraphicsSwitch.isChecked()));
        mLowGraphicsSwitch.setOnCheckedChangeListener((button, isChecked) -> {
            Instance instance = Instances.loadSelectedInstance();
            KiraziumBootstrap.setLowGraphicsMode(requireContext(), instance, isChecked);
            refreshRamControl();
            Toast.makeText(requireContext(), isChecked
                    ? R.string.low_graphics_enabled
                    : R.string.low_graphics_disabled, Toast.LENGTH_SHORT).show();
        });

        mNewsButton.setOnLongClickListener((v)->{
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });
    }

    private void installControlModeButton(Button texturePacksButton) {
        View parentView = (View) texturePacksButton.getParent();
        if (!(parentView instanceof ConstraintLayout)) return;

        ConstraintLayout parent = (ConstraintLayout) parentView;
        mControlModeButton = (Button) LayoutInflater.from(requireContext())
                .inflate(R.layout.item_control_mode_button, parent, false);
        parent.addView(mControlModeButton);

        ConstraintSet constraints = new ConstraintSet();
        constraints.clone(parent);
        constraints.connect(R.id.control_mode_button, ConstraintSet.START,
                ConstraintSet.PARENT_ID, ConstraintSet.START);
        constraints.connect(R.id.control_mode_button, ConstraintSet.END,
                ConstraintSet.PARENT_ID, ConstraintSet.END);
        constraints.connect(R.id.control_mode_button, ConstraintSet.TOP,
                R.id.ram_card, ConstraintSet.BOTTOM);
        constraints.clear(R.id.texture_packs_button, ConstraintSet.TOP);
        constraints.connect(R.id.texture_packs_button, ConstraintSet.TOP,
                R.id.control_mode_button, ConstraintSet.BOTTOM);
        constraints.applyTo(parent);

        refreshControlModeButton();
        mControlModeButton.setOnClickListener(v -> showControlModeDialog());
    }

    private void refreshControlModeButton() {
        if (mControlModeButton == null) return;
        boolean kirazium = CONTROL_MODE_KIRAZIUM.equals(getSavedControlMode());
        mControlModeButton.setText("Kontrol Tipi • " + (kirazium ? "Kirazium" : "Classic"));
    }

    private String getSavedControlMode() {
        SharedPreferences preferences = LauncherPreferences.DEFAULT_PREF;
        if (preferences == null) return CONTROL_MODE_CLASSIC;
        return preferences.getString(CONTROL_MODE_PREF, CONTROL_MODE_CLASSIC);
    }

    private void showControlModeDialog() {
        final String[] options = new String[]{
                "Classic — Tıklamalı hareket tuşları",
                "Kirazium — Joystick"
        };
        int selected = CONTROL_MODE_KIRAZIUM.equals(getSavedControlMode()) ? 1 : 0;

        new AlertDialog.Builder(requireContext())
                .setTitle("Kontrol Tipi")
                .setSingleChoiceItems(options, selected, (dialog, which) -> {
                    boolean success = which == 1
                            ? activateKiraziumControlMode()
                            : activateClassicControlMode();
                    if (!success) return;

                    refreshControlModeButton();
                    Toast.makeText(requireContext(), which == 1
                                    ? "Kirazium joystick aktif"
                                    : "Classic kontrol aktif",
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean ensureSelectedControlMode() {
        SharedPreferences preferences = LauncherPreferences.DEFAULT_PREF;
        if (preferences == null) return true;

        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) return true;

        if (CONTROL_MODE_KIRAZIUM.equals(getSavedControlMode())) {
            File generated = new File(Tools.CTRLMAP_PATH, GENERATED_JOYSTICK_LAYOUT);
            if (!GENERATED_JOYSTICK_LAYOUT.equals(instance.controlLayout) || !generated.isFile()) {
                return activateKiraziumControlMode();
            }
        } else if (GENERATED_JOYSTICK_LAYOUT.equals(instance.controlLayout)) {
            return activateClassicControlMode();
        }
        return true;
    }

    private boolean activateClassicControlMode() {
        SharedPreferences preferences = LauncherPreferences.DEFAULT_PREF;
        if (preferences == null) return false;

        Instance instance = Instances.loadSelectedInstance();
        if (instance != null && GENERATED_JOYSTICK_LAYOUT.equals(instance.controlLayout)) {
            String classicLayout = preferences.getString(CLASSIC_LAYOUT_PREF, "");
            instance.controlLayout = classicLayout == null || classicLayout.isEmpty()
                    ? null : classicLayout;
            instance.maybeWrite();
        }

        preferences.edit().putString(CONTROL_MODE_PREF, CONTROL_MODE_CLASSIC).apply();
        return true;
    }

    private boolean activateKiraziumControlMode() {
        SharedPreferences preferences = LauncherPreferences.DEFAULT_PREF;
        if (preferences == null) return false;

        Instance instance = Instances.loadSelectedInstance();
        if (instance == null) {
            Toast.makeText(requireContext(), R.string.no_instance, Toast.LENGTH_LONG).show();
            return false;
        }

        try {
            if (!GENERATED_JOYSTICK_LAYOUT.equals(instance.controlLayout)) {
                String classicLayout = instance.controlLayout == null ? "" : instance.controlLayout;
                preferences.edit().putString(CLASSIC_LAYOUT_PREF, classicLayout).apply();
            }

            String classicLayout = preferences.getString(CLASSIC_LAYOUT_PREF, "");
            String sourcePath = classicLayout == null || classicLayout.isEmpty()
                    ? LauncherPreferences.PREF_DEFAULTCTRL_PATH
                    : new File(Tools.CTRLMAP_PATH, classicLayout).getAbsolutePath();

            DisplayMetrics displayMetrics = Tools.getDisplayMetrics(requireActivity());
            Point displaySize = new Point(displayMetrics.widthPixels, displayMetrics.heightPixels);
            CustomControls controls = LayoutConverter.loadAndConvertIfNecessary(displaySize, sourcePath);
            replaceWalkingControlsWithJoystick(controls, displaySize);

            File controlDirectory = new File(Tools.CTRLMAP_PATH);
            FileUtils.ensureDirectory(controlDirectory);
            File generatedLayout = new File(controlDirectory, GENERATED_JOYSTICK_LAYOUT);
            controls.save(generatedLayout.getAbsolutePath());

            instance.controlLayout = GENERATED_JOYSTICK_LAYOUT;
            instance.maybeWrite();
            preferences.edit().putString(CONTROL_MODE_PREF, CONTROL_MODE_KIRAZIUM).apply();
            return true;
        } catch (Exception exception) {
            Log.e(CONTROL_TAG, "Could not prepare Kirazium joystick controls", exception);
            Toast.makeText(requireContext(),
                    "Kirazium joystick hazırlanamadı: " + readableError(exception),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void replaceWalkingControlsWithJoystick(CustomControls controls, Point screen)
            throws IOException {
        if (controls == null || controls.mControlDataList == null) {
            throw new IOException("Kontrol düzeni boş");
        }

        ControlData forward = null;
        ControlData left = null;
        ControlData back = null;
        ControlData right = null;

        for (ControlData data : controls.mControlDataList) {
            int movementKey = getPlainMovementKey(data);
            if (movementKey == KeyEvent.KEYCODE_W && forward == null) forward = data;
            else if (movementKey == KeyEvent.KEYCODE_A && left == null) left = data;
            else if (movementKey == KeyEvent.KEYCODE_S && back == null) back = data;
            else if (movementKey == KeyEvent.KEYCODE_D && right == null) right = data;
        }

        if (forward == null || left == null || back == null || right == null) {
            throw new IOException("W/A/S/D hareket tuşları bulunamadı");
        }

        ControlData[] movement = new ControlData[]{forward, left, back, right};
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (ControlData data : movement) {
            float x = data.insertDynamicPos(data.dynamicX, screen.x, screen.y);
            float y = data.insertDynamicPos(data.dynamicY, screen.x, screen.y);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + data.getWidth());
            maxY = Math.max(maxY, y + data.getHeight());
        }

        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;
        float joystickSize = Math.max(maxX - minX, maxY - minY);
        joystickSize = Math.max(joystickSize, Tools.dpToPx(110));
        joystickSize = Math.min(joystickSize,
                Math.min(screen.x, screen.y) * 0.45f);

        float joystickX = centerX - joystickSize / 2f;
        float joystickY = centerY - joystickSize / 2f;
        joystickX = Math.max(0f, Math.min(joystickX, screen.x - joystickSize));
        joystickY = Math.max(0f, Math.min(joystickY, screen.y - joystickSize));

        ControlJoystickData joystick = new ControlJoystickData();
        joystick.name = "Kirazium";
        joystick.dynamicX = String.format(Locale.US, "%.6f * ${screen_width}",
                joystickX / screen.x);
        joystick.dynamicY = String.format(Locale.US, "%.6f * ${screen_height}",
                joystickY / screen.y);
        joystick.setWidth(joystickSize);
        joystick.setHeight(joystickSize);
        joystick.opacity = forward.opacity;
        joystick.bgColor = forward.bgColor;
        joystick.strokeColor = forward.strokeColor;
        joystick.strokeWidth = forward.strokeWidth;
        joystick.cornerRadius = 100f;
        joystick.displayInGame = forward.displayInGame;
        joystick.displayInMenu = forward.displayInMenu;
        joystick.absolute = false;
        joystick.forwardLock = false;

        controls.mControlDataList.remove(forward);
        controls.mControlDataList.remove(left);
        controls.mControlDataList.remove(back);
        controls.mControlDataList.remove(right);
        if (controls.mJoystickDataList == null) {
            controls.mJoystickDataList = new java.util.ArrayList<>();
        }
        controls.mJoystickDataList.add(joystick);
    }

    private int getPlainMovementKey(ControlData data) {
        if (data == null || data.keycodes == null || data.isToggle) {
            return KeyEvent.KEYCODE_UNKNOWN;
        }

        int movementKey = KeyEvent.KEYCODE_UNKNOWN;
        for (int keycode : data.keycodes) {
            if (keycode == KeyEvent.KEYCODE_UNKNOWN) continue;
            if (keycode != KeyEvent.KEYCODE_W
                    && keycode != KeyEvent.KEYCODE_A
                    && keycode != KeyEvent.KEYCODE_S
                    && keycode != KeyEvent.KEYCODE_D) {
                return KeyEvent.KEYCODE_UNKNOWN;
            }
            if (movementKey != KeyEvent.KEYCODE_UNKNOWN && movementKey != keycode) {
                return KeyEvent.KEYCODE_UNKNOWN;
            }
            movementKey = keycode;
        }
        return movementKey;
    }

    private String readableError(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    private void setupRamControl(View view) {
        mRamSeekBar = view.findViewById(R.id.ram_seekbar);
        mRamValueText = view.findViewById(R.id.ram_value);
        mRamSummaryText = view.findViewById(R.id.ram_summary);

        int deviceRam = Tools.getTotalDeviceMemory(requireContext());
        if (Architecture.is32BitsDevice() || deviceRam < 2048) {
            mRamMaxMb = Math.min(1024, deviceRam);
        } else {
            mRamMaxMb = deviceRam - (deviceRam < 3064 ? 800 : 1024);
        }
        mRamMaxMb = Math.max(RAM_MIN_MB,
                (mRamMaxMb / RAM_STEP_MB) * RAM_STEP_MB);
        mRamSeekBar.setMax((mRamMaxMb - RAM_MIN_MB) / RAM_STEP_MB);
        mRamSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateRamLabels(ramFromProgress(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int ramMb = ramFromProgress(seekBar.getProgress());
                KiraziumBootstrap.setRamAllocation(requireContext(), ramMb);
                Toast.makeText(requireContext(),
                        getString(R.string.ram_control_saved, formatRam(ramMb)),
                        Toast.LENGTH_SHORT).show();
            }
        });
        refreshRamControl();
    }

    private void refreshRamControl() {
        if (mRamSeekBar == null) return;
        int ramMb = Math.max(RAM_MIN_MB,
                Math.min(mRamMaxMb, LauncherPreferences.PREF_RAM_ALLOCATION));
        int progress = (ramMb - RAM_MIN_MB) / RAM_STEP_MB;
        mRamSeekBar.setProgress(progress);
        updateRamLabels(ramFromProgress(progress));
    }

    private int ramFromProgress(int progress) {
        return RAM_MIN_MB + (progress * RAM_STEP_MB);
    }

    private void updateRamLabels(int ramMb) {
        mRamValueText.setText(formatRam(ramMb));
        mRamSummaryText.setText(getString(
                R.string.ram_control_summary, formatRam(mRamMaxMb)));
    }

    private String formatRam(int ramMb) {
        if (ramMb < 1024) return ramMb + " MB";
        if (ramMb % 1024 == 0) return (ramMb / 1024) + " GB";
        return String.format(Locale.getDefault(), "%.1f GB", ramMb / 1024f);
    }

    private void openGameDirectory(Context context) {
        Instance instance = Instances.loadSelectedInstance();
        if(instance == null) {
            Toast.makeText(context, R.string.no_instance, Toast.LENGTH_LONG).show();
            return;
        }
        File gameDirectory = instance.getGameDirectory();
        if(FileUtils.ensureDirectorySilently(gameDirectory)) {
            openPath(context, gameDirectory, false);
        }else {
            Toast.makeText(context, R.string.gamedir_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        ExtraCore.setValue(ExtraConstants.REFRESH_ACCOUNT_SPINNER, true);
        refreshRamControl();
        refreshControlModeButton();
    }

    private void runInstallerWithConfirmation() {
        if (ProgressKeeper.getTaskCount() == 0) {
            mModInstallerLauncher.launch(null);
        } else Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
