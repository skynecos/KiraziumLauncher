#!/usr/bin/env python3
from pathlib import Path

ROOT = Path.cwd()


def replace(path: str, old: str, new: str, count: int = 1):
    p = ROOT / path
    text = p.read_text()
    found = text.count(old)
    if found < count:
        raise SystemExit(
            f"Android11 patch anchor not found in {path}: wanted {count}, found {found}: {old[:160]!r}"
        )
    p.write_text(text.replace(old, new, count))


# The Android9 implementation passed the embedded OpenJDK JavaVM to FFmpeg's
# MediaCodec JNI glue. That VM cannot resolve android.media.MediaFormat. The
# launcher now publishes the process' real Android ART JavaVM pointer through
# KIRAZIUM_ANDROID_ART_VM before it creates HotSpot. Prefer that ART VM here so
# FFmpeg's h264_mediacodec can resolve Android framework classes and reach the
# platform hardware decoder.
replace(
    "native/lav/src/lib.rs",
    '''#[cfg(target_os = "android")]
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
    '''#[cfg(target_os = "android")]
fn kirazium_android_art_vm() -> Option<*mut c_void> {
    let raw = std::env::var("KIRAZIUM_ANDROID_ART_VM").ok()?;
    let digits = raw.strip_prefix("0x").unwrap_or(raw.as_str());
    let address = usize::from_str_radix(digits, 16).ok()?;
    if address == 0 { None } else { Some(address as *mut c_void) }
}

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut c_void, _reserved: *mut c_void) -> i32 {
    const JNI_VERSION_1_6: i32 = 0x0001_0006;
    dreamdisplays_logging::init();

    let art_vm = kirazium_android_art_vm();
    let media_vm = art_vm.unwrap_or(vm);
    let rc = unsafe { av_jni_set_java_vm(media_vm, std::ptr::null_mut()) };
    if rc < 0 {
        log::error!("Could not register Android JavaVM with FFmpeg MediaCodec (error {rc}).");
        return -1;
    }

    if art_vm.is_some() {
        log::info!("Registered Kirazium Android ART VM with FFmpeg MediaCodec.");
    } else {
        log::warn!(
            "Kirazium Android ART VM bridge was unavailable; registered embedded JVM fallback. "
            "Hardware MediaCodec may be unavailable."
        );
    }
    JNI_VERSION_1_6
}
''',
)

# Make the build identity explicit so a phone log can never be confused with the
# working-but-software-decoding Android10 package.
gp = ROOT / "gradle.properties"
text = gp.read_text()
old = "version=1.9.5-kirazium-android9"
new = "version=1.9.5-kirazium-android11-hwdecode"
if old in text:
    gp.write_text(text.replace(old, new, 1))
elif new not in text:
    raise SystemExit("Expected android9 version marker before Android11 hardware-decode patch")

print("Applied Kirazium Android11 ART-VM MediaCodec hardware decode patch.")
