#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()


def replace(path: str, old: str, new: str, count: int = 1):
    p = ROOT / path
    s = p.read_text()
    found = s.count(old)
    if found < count:
        raise SystemExit(
            f"Patch anchor not found enough times in {path}: wanted {count}, found {found}: {old[:140]!r}"
        )
    p.write_text(s.replace(old, new, count))


# Android9 is the first build that moves the expensive video decode in-process and
# asks Android's MediaCodec block to do it. The standalone FFmpeg helper remains a
# software fallback: MediaCodec is only selected by dreamdisplays_lav, where the
# library is loaded by the JVM and can register the real JavaVM with libavcodec.

# 1) Give MediaCodec its own LAV ABI code and select it by default on Android.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/process/HwAccelBackend.kt",
    '    MEDIACODEC("mediacodec", null, 0),\n',
    '    MEDIACODEC("mediacodec", null, 6),\n',
)
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/process/HwAccelBackend.kt",
    '            System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true -> NONE\n',
    '            System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true -> MEDIACODEC\n',
)

# 2) LAV's generic Linux "auto" mapping would otherwise try VAAPI/CUDA because
# Android reports itself as Linux. Pin the Android in-process path to MediaCodec.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/NativeVideoFramePipe.kt",
    '''    private fun lavHwCode(hwAccel: HwAccelBackend): Int {
        if (hwAccel == HwAccelBackend.NONE) return HwAccelBackend.NONE.lavCode
        val configured = System.getProperty("dreamdisplays.native.libav.hw")?.lowercase()
''',
    '''    private fun lavHwCode(hwAccel: HwAccelBackend): Int {
        if (hwAccel == HwAccelBackend.NONE) return HwAccelBackend.NONE.lavCode
        // Android is reported as Linux by the Java runtime, but VAAPI/CUDA are not its
        // decoder APIs. The JNI-loaded LAV library owns the MediaCodec path instead.
        if (System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true) {
            return HwAccelBackend.MEDIACODEC.lavCode
        }
        val configured = System.getProperty("dreamdisplays.native.libav.hw")?.lowercase()
''',
)

# 3) On Android provision LAV's FFmpeg shared dependencies from the Kirazium
# helper APK instead of downloading a glibc Linux BtbN bundle. Copying them into
# the launcher's private cache also keeps Android's linker in one app namespace.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge/LavFfmpeg.kt",
    '''    fun ensure(dir: File): Boolean {
        if (hasFfmpeg(dir)) return true
        val source = source() ?: return false
''',
    '''    fun ensure(dir: File): Boolean {
        if (System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true) {
            return provisionAndroidHelperLibraries(dir)
        }
        if (hasFfmpeg(dir)) return true
        val source = source() ?: return false
''',
)
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge/LavFfmpeg.kt",
    '''    /** True once at least the core decode library is present in [dir]. */
    private fun hasFfmpeg(dir: File): Boolean =
''',
    '''    /** Copies the MediaCodec-enabled helper APK's native libraries into [dir]. */
    private fun provisionAndroidHelperLibraries(dir: File): Boolean {
        val helperPath = System.getenv("POJAV_FFMPEG_PATH")?.takeIf { it.isNotBlank() } ?: return false
        val helper = File(helperPath)
        val sourceDir = helper.parentFile
        if (!helper.isFile || sourceDir == null || !sourceDir.isDirectory) {
            logger.warn("Android FFmpeg helper directory is unavailable: $helperPath.")
            return false
        }
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create $dir.")
            val libraries = sourceDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".so", ignoreCase = true) }
                ?.sortedBy { it.name }
                .orEmpty()
            if (libraries.isEmpty()) throw IOException("No helper shared libraries are visible in $sourceDir.")
            var copied = 0
            for (source in libraries) {
                // libffmpeg.so / libffprobe.so are executable entry points; LAV only needs
                // shared dependencies. Leaving them out avoids copying tens of extra MiB.
                if (source.name == "libffmpeg.so" || source.name == "libffprobe.so") continue
                val target = File(dir, source.name)
                if (!target.isFile || target.length() != source.length()) {
                    source.copyTo(target, overwrite = true)
                }
                copied++
            }
            logger.info("Android in-process FFmpeg dependencies ready ($copied helper libraries copied).")
            hasFfmpeg(dir)
        }.getOrElse { e ->
            logger.warn("Could not provision Android helper libraries (${e.javaClass.simpleName}: ${e.message}).")
            false
        }
    }

    /** True once at least the core decode library is present in [dir]. */
    private fun hasFfmpeg(dir: File): Boolean =
''',
)

# 4) System.load invokes JNI_OnLoad. Panama's SymbolLookup alone is a plain dlopen
# and would not give libavcodec the JavaVM that FFmpeg 6 MediaCodec requires.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/nativebridge/NativeMedia.kt",
    '''            LavFfmpeg.ensure(lib.parentFile)
            preloadLavDependencies(lib.parentFile)
            val lookup = SymbolLookup.libraryLookup(lib.toPath(), Arena.global())
''',
    '''            if (!LavFfmpeg.ensure(lib.parentFile)) {
                throw UnsatisfiedLinkError("Android/desktop FFmpeg dependencies could not be provisioned")
            }
            preloadLavDependencies(lib.parentFile)
            if (System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true) {
                // Required specifically on Android: invokes JNI_OnLoad in dreamdisplays_lav,
                // which registers this process' real JavaVM with FFmpeg's MediaCodec bridge.
                System.load(lib.absolutePath)
            }
            val lookup = SymbolLookup.libraryLookup(lib.toPath(), Arena.global())
''',
)

# 5) Register the JVM with FFmpeg when libdreamdisplays_lav is loaded through JNI.
replace(
    "native/lav/src/lib.rs",
    '''use session::{ERR_BAD_ARGS, ERR_IO, LavSessions, NO_PTS_NANOS};
use std::any::Any;
''',
    '''use session::{ERR_BAD_ARGS, ERR_IO, LavSessions, NO_PTS_NANOS};
use std::any::Any;
#[cfg(target_os = "android")]
use std::ffi::c_void;
''',
)
replace(
    "native/lav/src/lib.rs",
    '''/// Global state, one per process.
static SESSIONS: OnceLock<LavSessions> = OnceLock::new();
''',
    '''/// Global state, one per process.
static SESSIONS: OnceLock<LavSessions> = OnceLock::new();

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn av_jni_set_java_vm(vm: *mut c_void, log_ctx: *mut c_void) -> i32;
}

/// JNI entry point used only by the Kirazium Android build. FFmpeg 6's Java
/// MediaCodec wrapper refuses to initialize until the hosting JavaVM is registered.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut c_void, _reserved: *mut c_void) -> i32 {
    const JNI_VERSION_1_6: i32 = 0x0001_0006;
    let rc = unsafe { av_jni_set_java_vm(vm, std::ptr::null_mut()) };
    if rc < 0 {
        log::error!("Could not register JavaVM with FFmpeg MediaCodec (error {rc}).");
        return -1;
    }
    dreamdisplays_logging::init();
    log::info!("Registered Kirazium JVM with FFmpeg MediaCodec.");
    JNI_VERSION_1_6
}
''',
)
replace(
    "native/lav/src/lib.rs",
    '''/// 0 = software only, 1 = auto, 2 = VideoToolbox, 3 = D3D11VA, 4 = VAAPI, 5 = CUDA.
''',
    '''/// 0 = software only, 1 = auto, 2 = VideoToolbox, 3 = D3D11VA, 4 = VAAPI, 5 = CUDA,
/// 6 = Android MediaCodec (Kirazium Android build).
''',
)

# 6) Add a decoder-specific MediaCodec backend. MediaCodec is not a normal
# AVHWDevice transfer path in FFmpeg 6: h264_mediacodec/hevc_mediacodec/etc.
# are decoders themselves. With no Surface requested they return CPU-visible
# YUV frames, which Dream Displays keeps planar and uploads to the existing GPU shader.
replace(
    "native/lav/src/session.rs",
    '''    Vaapi,
    Cuda,
}
''',
    '''    Vaapi,
    Cuda,
    MediaCodec,
}
''',
)
replace(
    "native/lav/src/session.rs",
    '''            5 => HwAccelRequest::Cuda,
            _ => HwAccelRequest::None,
''',
    '''            5 => HwAccelRequest::Cuda,
            6 => HwAccelRequest::MediaCodec,
            _ => HwAccelRequest::None,
''',
)
replace(
    "native/lav/src/session.rs",
    '''            HwAccelRequest::Cuda => &[HW_CUDA],
            HwAccelRequest::Auto => auto_hw_candidates(),
''',
    '''            HwAccelRequest::Cuda => &[HW_CUDA],
            // MediaCodec is selected by decoder name, not AVHWDeviceType.
            HwAccelRequest::MediaCodec => &[],
            HwAccelRequest::Auto => auto_hw_candidates(),
''',
)
replace(
    "native/lav/src/session.rs",
    '''#[cfg(all(unix, not(target_os = "macos")))]
fn auto_hw_candidates() -> &'static [HwBackend] {
    &[HW_VAAPI, HW_CUDA]
}
''',
    '''#[cfg(all(unix, not(target_os = "macos"), not(target_os = "android")))]
fn auto_hw_candidates() -> &'static [HwBackend] {
    &[HW_VAAPI, HW_CUDA]
}
#[cfg(target_os = "android")]
fn auto_hw_candidates() -> &'static [HwBackend] {
    &[]
}
''',
)
replace(
    "native/lav/src/session.rs",
    '''    let codec = codec::decoder::find(parameters.id())
        .with_context(|| format!("no decoder available for codec {:?}", parameters.id()))?;

    if request != HwAccelRequest::None {
''',
    '''    let codec = codec::decoder::find(parameters.id())
        .with_context(|| format!("no decoder available for codec {:?}", parameters.id()))?;

    #[cfg(target_os = "android")]
    if request == HwAccelRequest::MediaCodec || request == HwAccelRequest::Auto {
        if let Some(decoder) = open_android_mediacodec_decoder(parameters, packet_time_base)? {
            return Ok((decoder, None));
        }
        if request == HwAccelRequest::MediaCodec {
            warn!("Android MediaCodec decoder unavailable for this stream; falling back to software decode.");
        }
    }

    if request != HwAccelRequest::None {
''',
)
replace(
    "native/lav/src/session.rs",
    '''fn new_decoder_context(
    parameters: &codec::Parameters,
) -> Result<codec::context::Context, ffmpeg::Error> {
''',
    '''#[cfg(target_os = "android")]
fn open_android_mediacodec_decoder(
    parameters: &codec::Parameters,
    packet_time_base: ffmpeg::Rational,
) -> Result<Option<codec::decoder::Video>> {
    // Try every MediaCodec decoder name we ship and select the one whose AVCodecID
    // matches the input. This avoids hard-coding ffmpeg-next enum spellings and makes
    // unsupported codecs fall back cleanly.
    const NAMES: [&str; 7] = [
        "h264_mediacodec",
        "hevc_mediacodec",
        "vp9_mediacodec",
        "vp8_mediacodec",
        "av1_mediacodec",
        "mpeg4_mediacodec",
        "mpeg2_mediacodec",
    ];
    for name in NAMES {
        let Some(candidate) = codec::decoder::find_by_name(name) else {
            continue;
        };
        if candidate.id() != parameters.id() {
            continue;
        }
        let context = new_decoder_context(parameters)?;
        let mut decoder = context.decoder();
        decoder.set_packet_time_base(packet_time_base);
        match decoder.open_as(candidate).and_then(|opened| opened.video()) {
            Ok(decoder) => {
                info!("LAV decoder opened with Android MediaCodec ({name}).");
                return Ok(Some(decoder));
            }
            Err(e) => {
                warn!("Android MediaCodec decoder {name} failed to open: {e}.");
                return Ok(None);
            }
        }
    }
    Ok(None)
}

fn new_decoder_context(
    parameters: &codec::Parameters,
) -> Result<codec::context::Context, ffmpeg::Error> {
''',
)

# Version marker.
gp = ROOT / "gradle.properties"
g = gp.read_text()
if "version=1.9.5-kirazium-android8" not in g:
    raise SystemExit("Expected android8 version marker before android9 patch")
gp.write_text(g.replace("version=1.9.5-kirazium-android8", "version=1.9.5-kirazium-android9", 1))

print("Applied Kirazium Android9 in-process MediaCodec patch for Dream Displays 1.9.5 / MC 26.1.2.")
