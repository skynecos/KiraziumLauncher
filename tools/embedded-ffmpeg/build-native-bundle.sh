#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 /absolute/path/to/FFmpegPlugin /absolute/output/arm64-v8a"
  exit 2
fi

plugin_dir="$(cd "$1" && pwd)"
out_dir="$2"
ffmpeg_kit_dir="${plugin_dir}/ffmpeg-kit"

if [[ ! -f "${ffmpeg_kit_dir}/android.sh" ]]; then
  echo "FFmpegPlugin/ffmpeg-kit source is missing under ${plugin_dir}."
  exit 1
fi
if [[ -z "${ANDROID_SDK_ROOT:-}" ]] || [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  echo "ANDROID_SDK_ROOT and ANDROID_NDK_ROOT must be set."
  exit 1
fi

cd "${ffmpeg_kit_dir}"

# Keep this feature set aligned with the already phone-tested Kirazium helper.
# HTTPS/CDN access needs GnuTLS + zlib. MediaCodec/JNI stays compiled in for
# compatibility with the existing native LAV bridge, while DreamDisplays' current
# stable Android route still explicitly selects software decode.
./android.sh \
  --api-level=24 \
  --speed \
  --enable-gnutls \
  --enable-android-zlib \
  --enable-android-media-codec \
  --disable-arm-v7a \
  --disable-arm-v7a-neon \
  --disable-x86 \
  --disable-x86-64

rm -rf "${out_dir}"
mkdir -p "${out_dir}"

cp prebuilt/android-arm64/ffmpeg/bin/ffmpeg "${out_dir}/libffmpeg.so"
cp prebuilt/android-arm64/ffmpeg/bin/ffprobe "${out_dir}/libffprobe.so"
cp prebuilt/android-arm64/ffmpeg/lib/*.so "${out_dir}/"

# Include every external shared dependency produced by ffmpeg-kit (GnuTLS,
# nettle, hogweed, GMP, iconv, etc.) in the same Android nativeLibraryDir.
find prebuilt/android-arm64 -type f -name '*.so' -not -path '*/ffmpeg/lib/*' -print0 | while IFS= read -r -d '' lib; do
  cp -f "$lib" "${out_dir}/$(basename "$lib")"
done

if [[ -f android/libs/arm64-v8a/libc++_shared.so ]]; then
  cp -f android/libs/arm64-v8a/libc++_shared.so "${out_dir}/"
fi

# Hard guards: never build a launcher that silently lacks the helper or HTTPS stack.
test -s "${out_dir}/libffmpeg.so"
test -s "${out_dir}/libavformat.so"
test -s "${out_dir}/libavcodec.so"
test -s "${out_dir}/libavdevice.so"

printf 'Embedded FFmpeg bundle contains %s native libraries.\n' "$(find "${out_dir}" -maxdepth 1 -type f -name '*.so' | wc -l)"
