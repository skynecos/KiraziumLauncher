package net.kdt.pojavlaunch.tasks;


import static net.kdt.pojavlaunch.Architecture.archAsString;
import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class AsyncAssetManager {

    private static final String KIRAZIUM_BEDROCK_CONTROLS = "kirazium_bedrock.json";
    private static final String KIRAZIUM_BEDROCK_CONTROLS_MARKER =
            "kiraziumBedrockControlsV1Installed";

    private AsyncAssetManager(){}

    /**
     * Attempt to install the java 8 runtime, if necessary
     * @param am App context
     */
    public static void unpackRuntime(AssetManager am) {
        /* Check if JRE is included */
        String rt_version = null;
        String current_rt_version = MultiRTUtils.readInternalRuntimeVersion("Internal");
        try {
            rt_version = Tools.read(am.open("components/jre/version"));
        } catch (IOException e) {
            Log.e("JREAuto", "JRE was not included on this APK.", e);
        }
        String exactJREName = MultiRTUtils.getExactJreName(8);
        if(current_rt_version == null && exactJREName != null && !exactJREName.equals("Internal")/*this clause is for when the internal runtime is goofed*/) return;
        if(rt_version == null) return;
        if(rt_version.equals(current_rt_version)) return;

        // Install the runtime in an async manner, hope for the best
        String finalRt_version = rt_version;
        sExecutorService.execute(() -> {

            try {
                MultiRTUtils.installRuntimeNamedBinpack(
                        am.open("components/jre/universal.tar.xz"),
                        am.open("components/jre/bin-" + archAsString(Tools.DEVICE_ARCHITECTURE) + ".tar.xz"),
                        "Internal", finalRt_version);
                MultiRTUtils.postPrepare("Internal");
            }catch (IOException e) {
                Log.e("JREAuto", "Internal JRE unpack failed", e);
            }
        });
    }

    /** Unpack single files, with no regard to version tracking */
    public static void unpackSingleFiles(Context ctx){
        ProgressLayout.setProgress(ProgressLayout.EXTRACT_SINGLE_FILES, 0);
        sExecutorService.execute(() -> {
            try {
                Tools.copyAssetFile(ctx, "default.json", Tools.CTRLMAP_PATH, false);
                installKiraziumBedrockControls(ctx);
                Tools.copyAssetFile(ctx, "launcher_profiles.json", Tools.DIR_GAME_NEW, false);
                Tools.copyAssetFile(ctx,"resolv.conf",Tools.DIR_DATA, false);
            } catch (IOException e) {
                Log.e("AsyncAssetManager", "Failed to unpack critical components !");
            }
            ProgressLayout.clearProgress(ProgressLayout.EXTRACT_SINGLE_FILES);
        });
    }

    /**
     * Installs Kirazium's Bedrock-style touch layout and selects it once for users who are still
     * using MojoLauncher's stock controls. An explicitly selected custom layout is never replaced.
     */
    private static void installKiraziumBedrockControls(Context ctx) throws IOException {
        Tools.copyAssetFile(ctx, KIRAZIUM_BEDROCK_CONTROLS, Tools.CTRLMAP_PATH, false);

        SharedPreferences preferences = LauncherPreferences.DEFAULT_PREF;
        if (preferences == null ||
                preferences.getBoolean(KIRAZIUM_BEDROCK_CONTROLS_MARKER, false)) return;

        String currentControlPath = preferences.getString("defaultCtrl", Tools.CTRLDEF_FILE);
        boolean usesStockControls = !preferences.contains("defaultCtrl") ||
                Tools.CTRLDEF_FILE.equals(currentControlPath);

        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(KIRAZIUM_BEDROCK_CONTROLS_MARKER, true);
        if (usesStockControls) {
            String bedrockControlPath = new File(
                    Tools.CTRLMAP_PATH, KIRAZIUM_BEDROCK_CONTROLS).getAbsolutePath();
            editor.putString("defaultCtrl", bedrockControlPath);
            LauncherPreferences.PREF_DEFAULTCTRL_PATH = bedrockControlPath;
        }
        editor.apply();
    }

    public static void unpackComponents(Context ctx){
        ProgressLayout.setProgress(ProgressLayout.EXTRACT_COMPONENTS, 0);
        sExecutorService.execute(() -> {
            tryUnpackComponent(ctx, "caciocavallo", false);
            tryUnpackComponent(ctx, "caciocavallo17", false);

            tryUnpackComponent(ctx, "security", true);
            tryUnpackComponent(ctx, "forge_installer", true);
            tryUnpackComponent(ctx, "authlib-injector", true);
            ProgressLayout.clearProgress(ProgressLayout.EXTRACT_COMPONENTS);
        });
    }

    private static String readInstalledComponentVersion(File componentRoot) {
        File localVersionFile = new File(componentRoot, "version");
        try(FileInputStream fileInputStream = new FileInputStream(localVersionFile)) {
            return IOUtils.toString(fileInputStream, StandardCharsets.UTF_8);
        }catch (IOException ignored) {}
        return null;
    }

    private static String readBuiltinComponentVersion(AssetManager assetManager, String componentName) {
        String componentVersionLocation = "components/"+componentName+"/version";
        try (InputStream inputStream = assetManager.open(componentVersionLocation)) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        }catch (IOException ignored) {}
        return null;
    }

    private static void tryUnpackComponent(Context ctx, String component, boolean privateDirectory) {
        try {
            unpackComponent(ctx, component, privateDirectory);
        }catch (IOException e) {
            Log.e("AssetUnpacker", "Failed to unpack component "+component, e);
        }
    }

    private static void unpackComponent(Context ctx, String component, boolean privateDirectory) throws IOException {
        AssetManager am = ctx.getAssets();
        String rootDir = privateDirectory ? Tools.DIR_DATA : Tools.DIR_GAME_HOME;
        File componentTarget = new File(rootDir, component);
        String installedVersion = readInstalledComponentVersion(componentTarget);
        String builtinVersion = readBuiltinComponentVersion(am, component);
        if(installedVersion != null && installedVersion.equals(builtinVersion)) {
            Log.i("AssetUnpacker", "Component "+component+" is up-to-date, continuing...");
            return;
        }
        Log.i("AssetUnpacker", "Updating "+component);

        if(componentTarget.exists()) {
            FileUtils.deleteDirectory(componentTarget);
        }
        if(!componentTarget.mkdirs()) {
            throw new IOException("Failed to create directory for "+component);
        }

        String componentSource = "components/" + component;

        String[] fileList = am.list(componentSource);
        for (String fileName : fileList) {
            if(fileName.equals("version")) continue;
            String sourcePath = componentSource + "/" + fileName;
            Tools.copyAssetFile(ctx, sourcePath, componentTarget.getAbsolutePath(), true);
        }

        // Always write the version file separately after extracting everything else, to improve
        // reliability.
        Tools.write(new File(componentTarget, "version"), builtinVersion);
    }

    public static void extractDefaultSettings(Context context, File gamedir)  {
        try {
            String gameDirPath = gamedir.getAbsolutePath();
            Tools.copyAssetFile(context, "options.txt", gameDirPath, false);
        }catch (IOException e) {
            Tools.showError(context, e);
        }
    }
}
