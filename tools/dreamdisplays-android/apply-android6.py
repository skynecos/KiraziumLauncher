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


# Android6 goal:
# Android5 successfully isolates the Dream Displays OpenAL context from Minecraft,
# but the audio pacer polls SourceDataLine.available() while waiting for queued
# PCM to drain. AndroidOpenALLine cached availableBytes and only reclaimed
# processed OpenAL buffers from write(). Once the pacer entered its wait loop,
# no more write() calls happened, so available() never increased and the audio
# pump deadlocked after the first ~100 ms of PCM.
#
# Reclaim processed buffers from available() as well. AudioSink calls available()
# from the same MediaPlayer-audio owner thread, so OpenAL calls remain on the
# thread that owns the thread-local context.

replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/AndroidOpenALLine.kt",
    '''    override fun available(): Int = availableBytes.get().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
''',
    '''    override fun available(): Int {
        if (opened.get() && onOwnerThread()) {
            reclaimProcessed()
            ensurePlaying()
        }
        return availableBytes.get().coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
''',
)

# Version marker.
gp = ROOT / "gradle.properties"
g = gp.read_text()
if "version=1.9.5-kirazium-android5" not in g:
    raise SystemExit("Expected android5 version marker before android6 patch")
gp.write_text(g.replace("version=1.9.5-kirazium-android5", "version=1.9.5-kirazium-android6", 1))

print("Applied Kirazium Android6 OpenAL pacing fix for Dream Displays 1.9.5 / MC 26.1.2.")
