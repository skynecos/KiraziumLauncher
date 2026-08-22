package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.appcompat.widget.SwitchCompat;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import git.artdeell.mojo.R;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.instances.KiraziumBootstrap;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.File;
import java.util.Locale;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";
    private static final int RAM_MIN_MB = 256;
    private static final int RAM_STEP_MB = 8;

    private mcVersionSpinner mVersionSpinner;
    private SeekBar mRamSeekBar;
    private TextView mRamValueText;
    private TextView mRamSummaryText;
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
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);
        View mLowGraphicsCard = view.findViewById(R.id.low_graphics_card);
        SwitchCompat mLowGraphicsSwitch = view.findViewById(R.id.low_graphics_switch);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.social_media_invite)));
        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation());
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        mPlayButton.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));

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
    }

    private void runInstallerWithConfirmation() {
        if (ProgressKeeper.getTaskCount() == 0) {
            mModInstallerLauncher.launch(null);
        } else Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
