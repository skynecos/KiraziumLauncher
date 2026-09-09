#!/usr/bin/env python3
from pathlib import Path

path = Path("app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java")
text = path.read_text(encoding="utf-8")

old = '''    public static void setupFfmpegEnv(Context ctx, Map<String, String> envMap) {
        LibraryPlugin ffmpeg = LibraryPlugin.discoverPlugin(ctx, LibraryPlugin.ID_FFMPEG_PLUGIN);
        if(ffmpeg == null) return;
        envMap.put("POJAV_FFMPEG_PATH", ffmpeg.resolveAbsolutePath("libffmpeg.so"));
    }
'''

new = '''    public static void setupFfmpegEnv(Context ctx, Map<String, String> envMap) {
        // Single-APK path: Android extracts packaged JNI libraries into this app's
        // nativeLibraryDir. Keep the whole FFmpeg dependency bundle beside libffmpeg.so.
        File embeddedFfmpeg = new File(ctx.getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
        if (embeddedFfmpeg.isFile()) {
            String embeddedPath = embeddedFfmpeg.getAbsolutePath();
            envMap.put("POJAV_FFMPEG_PATH", embeddedPath);
            Logger.appendToLog("Using embedded Kirazium FFmpeg: " + embeddedPath);
            return;
        }

        // Compatibility fallback for test installs that still have the historical
        // Mojo FFmpeg helper APK. A successful single-APK build should never need this.
        LibraryPlugin ffmpeg = LibraryPlugin.discoverPlugin(ctx, LibraryPlugin.ID_FFMPEG_PLUGIN);
        if (ffmpeg == null) {
            Logger.appendToLog("Embedded Kirazium FFmpeg not found and external FFmpeg helper is unavailable.");
            return;
        }
        String fallbackPath = ffmpeg.resolveAbsolutePath("libffmpeg.so");
        envMap.put("POJAV_FFMPEG_PATH", fallbackPath);
        Logger.appendToLog("Using external FFmpeg helper fallback: " + fallbackPath);
    }
'''

count = text.count(old)
if count != 1:
    raise SystemExit(f"Expected exactly one setupFfmpegEnv baseline block, found {count}")

path.write_text(text.replace(old, new), encoding="utf-8")

patched = path.read_text(encoding="utf-8")
required = [
    'new File(ctx.getApplicationInfo().nativeLibraryDir, "libffmpeg.so")',
    'Using embedded Kirazium FFmpeg:',
    'LibraryPlugin.discoverPlugin(ctx, LibraryPlugin.ID_FFMPEG_PLUGIN)',
]
for marker in required:
    if marker not in patched:
        raise SystemExit(f"Missing embedded FFmpeg patch marker: {marker}")

print("Patched JREUtils.setupFfmpegEnv for embedded-first FFmpeg with external fallback.")
