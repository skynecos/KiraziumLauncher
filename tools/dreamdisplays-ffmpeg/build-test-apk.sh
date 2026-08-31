#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /absolute/path/to/FFmpegPlugin"
  exit 2
fi

plugin_dir="$(cd "$1" && pwd)"
ffmpeg_kit_dir="${plugin_dir}/ffmpeg-kit"

if [[ ! -f "${plugin_dir}/gradlew" ]] || [[ ! -f "${ffmpeg_kit_dir}/android.sh" ]]; then
  echo "FFmpegPlugin or ffmpeg-kit source is missing under ${plugin_dir}."
  exit 1
fi

if [[ -z "${ANDROID_SDK_ROOT:-}" ]] || [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  echo "ANDROID_SDK_ROOT and ANDROID_NDK_ROOT must be set."
  exit 1
fi

cd "${ffmpeg_kit_dir}"

# Dream Displays receives YouTube and CDN stream URLs over HTTPS. The stock
# helper has no TLS backend, so FFmpeg exits before it can emit its first frame.
# GnuTLS supplies HTTPS; zlib handles compressed manifests/responses.
#
# Do NOT enable Android MediaCodec in the standalone FFmpeg executable build.
# FFmpeg's MediaCodec decoder requires a registered JavaVM/native window when
# used from Android JNI. Dream Displays currently launches FFmpeg as a child
# executable, so h264_mediacodec fails before the first frame with
# "No Java virtual machine has been registered". Keep the CLI helper on the
# software decoder path for reliable playback. Hardware MediaCodec will be
# integrated separately through an in-process/JNI path rather than the CLI.
./android.sh \
  --api-level=24 \
  --speed \
  --enable-gnutls \
  --enable-android-zlib \
  --disable-arm-v7a \
  --disable-arm-v7a-neon \
  --disable-x86 \
  --disable-x86-64

native_dir="${plugin_dir}/app/libs/lib/arm64-v8a"
rm -rf "${plugin_dir}/app/libs/lib"
mkdir -p "${native_dir}"

cp prebuilt/android-arm64/ffmpeg/bin/ffmpeg "${native_dir}/libffmpeg.so"
cp prebuilt/android-arm64/ffmpeg/bin/ffprobe "${native_dir}/libffprobe.so"
cp prebuilt/android-arm64/ffmpeg/lib/*.so "${native_dir}/"

if [[ -f android/libs/arm64-v8a/libc++_shared.so ]]; then
  cp android/libs/arm64-v8a/libc++_shared.so "${native_dir}/"
fi

cd "${plugin_dir}/app/libs"
rm -f libraries.jar
zip -q -r libraries.jar lib/

cd "${plugin_dir}"
sed -i 's/versionCode 3/versionCode 5/' app/build.gradle
sed -i 's/versionName "1.2"/versionName "1.4-kirazium-dd-swdecode"/' app/build.gradle
sed -i 's/MojoLauncher FFmpeg Plugin/Kirazium Dream Displays FFmpeg/' \
  app/src/main/res/values/strings.xml

chmod +x gradlew
./gradlew assembleDebug --stacktrace

