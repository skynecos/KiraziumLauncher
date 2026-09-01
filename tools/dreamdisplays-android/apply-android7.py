#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()


def replace(path: str, old: str, new: str, count: int = 1):
    p = ROOT / path
    s = p.read_text()
    found = s.count(old)
    if found < count:
        raise SystemExit(
            f"Patch anchor not found enough times in {path}: wanted {count}, found {found}: {old[:120]!r}"
        )
    p.write_text(s.replace(old, new, count))


# Android7 goals:
# - ship the lightweight Rust native frame pipe as Android ARM64 instead of the
#   incompatible glibc Linux binary;
# - extract it into Kirazium Launcher's private cache, which Android's linker can
#   load, rather than Minecraft's shared game directory;
# - make its direct FFmpeg child process use the helper APK's library directory,
#   just as the launcher's Java ProcessBuilder hook does.
#
# The native pipe keeps frames in NV12/I420 until the GPU shader, cutting frame-pipe
# traffic in half and removing PPM parsing/RGB conversion from the JVM. Video quality,
# resolution and playback cadence are unchanged.

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge/NativeMedia.kt",
    '    private const val CACHE_ROOT = "./dreamdisplays/native"\n',
    '''    /**
     * Android's linker only accepts a library from the app's private storage. Kirazium
     * Launcher exposes such a directory through MOD_ANDROID_RUNTIME; desktop keeps the
     * portable game directory cache used by upstream Dream Displays.
     */
    private fun cacheRoot(): File {
        val androidRuntime = System.getenv("MOD_ANDROID_RUNTIME")?.takeIf { it.isNotBlank() }
        return if (androidRuntime != null) {
            File(androidRuntime, "dreamdisplays/native")
        } else {
            File("./dreamdisplays/native")
        }
    }
''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge/NativeMedia.kt",
    '        val cached = File("$CACHE_ROOT/${platformKey()}/$libName")\n',
    '        val cached = File(File(cacheRoot(), platformKey()), libName)\n',
)

replace(
    "native/src/session.rs",
    '''        let mut cmd = Command::new(&args[0]);
        cmd.args(&args[1..])
''',
    '''        let mut cmd = Command::new(&args[0]);
        // The Android helper is an executable shared object plus sibling libav*.so
        // files. Java-launched FFmpeg gets this directory, through the launcher's
        // ProcessBuilder hook; the native pipe starts it directly, so mirror that
        // environment here before exec().
        if std::env::var_os("POJAV_FFMPEG_PATH").is_some() {
            if let Some(dir) = std::path::Path::new(&args[0]).parent() {
                let inherited = std::env::var_os("LD_LIBRARY_PATH")
                    .map(|value| std::env::split_paths(&value).collect::<Vec_>())
                    .unwrap_or_default();
                let mut paths = Vec::with_capacity(inherited.len() + 1);
                paths.push(dir.to_path_buf());
                paths.extend(inherited);
                if let Ok(value) = std::env::join_paths(paths) {
                    cmd.env("LD_LIBRARY_PATH", value);
                    cmd.env("PATH", dir);
                    info!("Using Android FFmpeg library directory {}.", dir.display());
                }
            }
        }
        cmd.args(&args[1..])
''',
)

# Version marker.
gp = ROOT / "gradle.properties"
g = gp.read_text()
if "version=1.9.5-kirazium-android6" not in g:
    raise SystemExit("Expected android6 version marker before android7 patch")
gp.write_text(g.replace("version=1.9.5-kirazium-android6", "version=1.9.5-kirazium-android7", 1))

print("Applied Kirazium Android7 native ARM64 frame-pipe patch for Dream Displays 1.9.5 / MC 26.1.2.")
