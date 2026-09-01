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


# Android8 goals:
# - do not queue enormous raw frames on Android when one GPU upload misses its interval;
# - batch the Y/U/V command-encoder writes into one encoder;
# - stop seek-bar thumbnails from competing with live Android software decode.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/pipeline/FramePrebuffer.kt",
    "        private const val DEFAULT_PREBUFFER_MS = 400L\n",
    """        /**
         * A raw 3152 x 1080 I420 cinema frame is about 5 MiB. On Android/OpenLTW the
         * command-encoder upload can occasionally miss one source interval; buffering
         * 400 ms then turns that single miss into a 14-frame FIFO and multi-second A/V
         * latency. Publish only the newest frame there instead. This preserves the source
         * resolution and cadence; it merely prevents obsolete frames blocking the reader.
         * Desktop keeps the normal jitter cushion.
         */
        private val DEFAULT_PREBUFFER_MS: Long =
            if (System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true) 0L else 400L
""",
)

replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/TextureUploadUtil.kt",
    """        var offset = 0
        for (texture in arrayOf(y, u, v)) {
            val planeBytes = texture.getWidth(0) * texture.getHeight(0)
            val view = src.duplicate()
            view.position(src.position() + offset).limit(src.position() + offset + planeBytes)
            writeToTexture(
                texture,
                view,
                texture.getWidth(0),
                texture.getHeight(0),
                UploadPixelFormat.R8.nativeImageFormat
            )
            offset += planeBytes
        }
""",
    """        // Android/OpenLTW takes this backend-neutral branch. Keep all three plane
        // writes in one command encoder so the render backend submits one batch rather
        // than constructing one encoder per YUV plane every displayed frame.
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        var offset = 0
        for (texture in arrayOf(y, u, v)) {
            val planeBytes = texture.getWidth(0) * texture.getHeight(0)
            val view = src.duplicate()
            view.position(src.position() + offset).limit(src.position() + offset + planeBytes)
            writeToTexture(
                encoder,
                texture,
                view,
                texture.getWidth(0),
                texture.getHeight(0),
                UploadPixelFormat.R8.nativeImageFormat
            )
            offset += planeBytes
        }
""",
)

replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/TextureUploadUtil.kt",
    """    /** Write to a Minecraft texture using the `writeToTexture` method. */
    private fun writeToTexture(texture: GpuTexture, pixels: ByteBuffer, w: Int, h: Int, format: NativeImage.Format) {
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val encoderClass = encoder.javaClass

        try {
            encoderClass
                .getMethod(
                    "writeToTexture",
                    GpuTexture::class.java,
                    ByteBuffer::class.java,
                    NativeImage.Format::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invokeOrThrowTarget(encoder, texture, pixels, format, 0, 0, 0, 0, w, h)
            return
        } catch (_: NoSuchMethodException) {
        }

        encoderClass
            .getMethod(
                "writeToTexture",
                GpuTexture::class.java,
                ByteBuffer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .invokeOrThrowTarget(encoder, texture, pixels, 0, 0, 0, 0, w, h)
    }
""",
    """    /** Write to a Minecraft texture using a fresh command encoder. */
    private fun writeToTexture(texture: GpuTexture, pixels: ByteBuffer, w: Int, h: Int, format: NativeImage.Format) =
        writeToTexture(RenderSystem.getDevice().createCommandEncoder(), texture, pixels, w, h, format)

    /**
     * Adds one texture write to [encoder]. The Android planar path calls this three
     * times against one encoder, retaining exact pixel bytes and dimensions while
     * avoiding separate encoder construction/submission for each plane.
     */
    private fun writeToTexture(
        encoder: Any,
        texture: GpuTexture,
        pixels: ByteBuffer,
        w: Int,
        h: Int,
        format: NativeImage.Format,
    ) {
        val encoderClass = encoder.javaClass

        try {
            encoderClass
                .getMethod(
                    "writeToTexture",
                    GpuTexture::class.java,
                    ByteBuffer::class.java,
                    NativeImage.Format::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invokeOrThrowTarget(encoder, texture, pixels, format, 0, 0, 0, 0, w, h)
            return
        } catch (_: NoSuchMethodException) {
        }

        encoderClass
            .getMethod(
                "writeToTexture",
                GpuTexture::class.java,
                ByteBuffer::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .invokeOrThrowTarget(encoder, texture, pixels, 0, 0, 0, 0, w, h)
    }
""",
)

replace(
    "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/ScrubPreview.kt",
    """        val firstParty = MediaHosts.isFirstParty(sourceUrl)
        val samples = if (firstParty) SAMPLE_COUNT else THIRD_PARTY_SAMPLE_COUNT
        val concurrency = if (firstParty) EXTRACT_CONCURRENCY else THIRD_PARTY_CONCURRENCY
""",
    """        val firstParty = MediaHosts.isFirstParty(sourceUrl)
        val samples = if (firstParty) SAMPLE_COUNT else THIRD_PARTY_SAMPLE_COUNT
        // A thumbnail sweep runs separate FFmpeg decoders. On Android those are software
        // decoders and can contend with a live cinema stream, so serialise previews there.
        // Their frame count and image resolution remain unchanged.
        val androidRuntime = System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true
        val concurrency = if (androidRuntime) 1 else if (firstParty) EXTRACT_CONCURRENCY else THIRD_PARTY_CONCURRENCY
""",
)

gp = ROOT / "gradle.properties"
g = gp.read_text()
if "version=1.9.5-kirazium-android7" not in g:
    raise SystemExit("Expected android7 version marker before android8 patch")
gp.write_text(g.replace("version=1.9.5-kirazium-android7", "version=1.9.5-kirazium-android8", 1))

print("Applied Kirazium Android8 low-latency frame-upload patch for Dream Displays 1.9.5 / MC 26.1.2.")
