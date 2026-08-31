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


# Android5 goals:
# - keep the standalone helper on software decode for now. The helper no longer
#   contains MediaCodec, so requesting it only causes a failed first attempt.
# - isolate Dream Displays audio from Minecraft's own OpenAL context. Android4
#   used alcMakeContextCurrent(), which is process-wide by default and caused the
#   game's SoundEngine to start emitting AL_INVALID_NAME / AL_INVALID_OPERATION.
#   OpenAL Soft exposes ALC_EXT_thread_local_context, so give the media thread a
#   private context without replacing Minecraft's process-wide context.

# 1) Do not request MediaCodec from the standalone FFmpeg helper.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/process/HwAccelBackend.kt",
    '            System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true -> MEDIACODEC\n',
    '            System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true -> NONE\n',
)

# 2) Use ALC_EXT_thread_local_context in AndroidOpenALLine.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AndroidOpenALLine.kt",
    "import org.lwjgl.openal.ALC10\n",
    "import org.lwjgl.openal.ALC10\nimport org.lwjgl.openal.EXTThreadLocalContext\n",
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AndroidOpenALLine.kt",
    '''        context = c
        if (!ALC10.alcMakeContextCurrent(c)) {
            ALC10.alcDestroyContext(c)
            ALC10.alcCloseDevice(d)
            context = 0L
            device = 0L
            throw LineUnavailableException("OpenAL context could not be made current")
        }
        AL.createCapabilities(alcCaps)
''',
    '''        context = c
        if (!alcCaps.ALC_EXT_thread_local_context) {
            ALC10.alcDestroyContext(c)
            ALC10.alcCloseDevice(d)
            context = 0L
            device = 0L
            throw LineUnavailableException("OpenAL thread-local context extension is unavailable")
        }
        if (!EXTThreadLocalContext.alcSetThreadContext(c)) {
            ALC10.alcDestroyContext(c)
            ALC10.alcCloseDevice(d)
            context = 0L
            device = 0L
            throw LineUnavailableException("OpenAL thread-local context could not be made current")
        }
        AL.createCapabilities(alcCaps)
''',
)

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AndroidOpenALLine.kt",
    '''        if (context != 0L) {
            runCatching { ALC10.alcMakeContextCurrent(0L) }
            runCatching { ALC10.alcDestroyContext(context) }
            context = 0L
        }
''',
    '''        if (context != 0L) {
            runCatching {
                EXTThreadLocalContext.alcSetThreadContext(0L)
                AL.setCurrentThread(null)
            }
            runCatching { ALC10.alcDestroyContext(context) }
            context = 0L
        }
''',
)

# Log successful Android audio initialization so device tests are unambiguous.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AudioSink.kt",
    '''            return runCatching {
                AndroidOpenALLine().also { it.open(fmt, LINE_BUFFER_BYTES) }
            }.getOrElse { e ->
''',
    '''            return runCatching {
                AndroidOpenALLine().also {
                    it.open(fmt, LINE_BUFFER_BYTES)
                    logger.info("$debugLabel Android OpenAL line opened with thread-local context.")
                }
            }.getOrElse { e ->
''',
)

# 3) Version marker.
gp = ROOT / "gradle.properties"
g = gp.read_text()
if "version=1.9.5-kirazium-android4" not in g:
    raise SystemExit("Expected android4 version marker before android5 patch")
gp.write_text(g.replace("version=1.9.5-kirazium-android4", "version=1.9.5-kirazium-android5", 1))

print("Applied Kirazium Android5 audio-context isolation patch for Dream Displays 1.9.5 / MC 26.1.2.")
