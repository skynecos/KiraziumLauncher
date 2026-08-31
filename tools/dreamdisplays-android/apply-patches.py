#!/usr/bin/env python3
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[3] if "tools" in Path(__file__).parts else Path.cwd()

def replace(path, old, new):
    p = ROOT / path
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"Patch anchor not found in {path}: {old[:80]!r}")
    p.write_text(s.replace(old, new, 1))

# 1) Prefer the Android helper supplied by Kirazium/Mojo instead of downloading a glibc Linux FFmpeg.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/process/FFmpegBinary.kt",
    """    private fun resolve(): String? {
        val p = detectPlatform() ?: run {
""",
    """    private fun resolve(): String? {
        val pojavFfmpeg = System.getenv("POJAV_FFMPEG_PATH")?.takeIf { it.isNotBlank() }
        if (pojavFfmpeg != null) {
            val supplied = File(pojavFfmpeg)
            if (supplied.isFile && supplied.length() > 0L) {
                logger.info("Using Android launcher FFmpeg: $pojavFfmpeg.")
                return supplied.absolutePath
            }
            logger.warn("POJAV_FFMPEG_PATH points to an unusable file: $pojavFfmpeg.")
        }

        val p = detectPlatform() ?: run {
"""
)

# 2) Android is reported as Linux, but VAAPI is a desktop Linux API. Never select it there.
replace(
    "media/player/src/main/kotlin/com/dreamdisplays/media/player/process/HwAccelBackend.kt",
    """        fun detectDefault(): HwAccelBackend = when {
            OsInfo.isMac -> VIDEOTOOLBOX
            OsInfo.isWindows -> D3D11VA
            OsInfo.isLinux -> VAAPI
            else -> NONE
        }
""",
    """        fun detectDefault(): HwAccelBackend = when {
            System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true -> NONE
            OsInfo.isMac -> VIDEOTOOLBOX
            OsInfo.isWindows -> D3D11VA
            OsInfo.isLinux -> VAAPI
            else -> NONE
        }
"""
)

# 3) OpenLTW/GLES does not expose the desktop GL4 persistent-PBO barrier path reliably.
#    On Android, upload decoded frames directly from RAM to the texture and bypass PBO/fence/GL42.
p = ROOT / "platform/client/common/src/main/kotlin/com/dreamdisplays/platform/client/render/AsyncTextureUploader.kt"
s = p.read_text()
anchor = """class AsyncTextureUploader(private val stateCache: Boolean) : TextureUploaderService {
"""
insert = """class AsyncTextureUploader(private val stateCache: Boolean) : TextureUploaderService {
    /** Kirazium/Mojo Android runtime marker. */
    private val androidCompat: Boolean = System.getenv("POJAV_FFMPEG_PATH")?.isNotBlank() == true
"""
if anchor not in s:
    raise SystemExit("AsyncTextureUploader class anchor not found")
s = s.replace(anchor, insert, 1)

anchor = """    fun upload(textureId: Int, src: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat = UploadPixelFormat.RGB24) {
        val size = w * h * format.bytesPerPixel
        if (size <= 0 || src.remaining() < size) return
"""
insert = """    fun upload(textureId: Int, src: ByteBuffer, w: Int, h: Int, format: UploadPixelFormat = UploadPixelFormat.RGB24) {
        val size = w * h * format.bytesPerPixel
        if (size <= 0 || src.remaining() < size) return

        if (androidCompat) {
            val view = src.duplicate()
            view.limit(view.position() + size)
            bindTexture(textureId)
            pixelStore(GL11.GL_UNPACK_ALIGNMENT, format.unpackAlignment)
            pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
            pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
            pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0, w, h,
                format.glFormat, GL11.GL_UNSIGNED_BYTE, view,
            )
            pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
            return
        }
"""
if anchor not in s:
    raise SystemExit("AsyncTextureUploader.upload anchor not found")
s = s.replace(anchor, insert, 1)

anchor = """        val total = ySize + uSize + vSize
        if (total <= 0 || src.remaining() < total) return

        val slot = slots[ringIndex]
"""
insert = """        val total = ySize + uSize + vSize
        if (total <= 0 || src.remaining() < total) return

        if (androidCompat) {
            val base = src.position()
            fun plane(offset: Int, size: Int): ByteBuffer {
                val v = src.duplicate()
                v.position(base + offset)
                v.limit(base + offset + size)
                return v.slice()
            }
            pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
            pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
            pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
            pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
            val glFormat = UploadPixelFormat.R8.glFormat
            bindTexture(yId)
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, yW, yH, glFormat, GL11.GL_UNSIGNED_BYTE, plane(0, ySize))
            bindTexture(uId)
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, uW, uH, glFormat, GL11.GL_UNSIGNED_BYTE, plane(ySize, uSize))
            bindTexture(vId)
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, vW, vH, glFormat, GL11.GL_UNSIGNED_BYTE, plane(ySize + uSize, vSize))
            pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
            return
        }

        val slot = slots[ringIndex]
"""
if anchor not in s:
    raise SystemExit("AsyncTextureUploader.uploadPlanar anchor not found")
s = s.replace(anchor, insert, 1)
p.write_text(s)

# Mark custom build version.
gp = ROOT / "gradle.properties"
g = gp.read_text()
for old in ("version=1.9.5-dev", "version=1.9.5", "version=1.10.0-dev"):
    if old in g:
        g = g.replace(old, "version=1.9.5-kirazium-android1", 1)
        break
gp.write_text(g)

# Select the user's current Minecraft version.
vp = ROOT / "versions.json"
data = json.loads(vp.read_text())
data["active"] = "26.1.2"
vp.write_text(json.dumps(data, indent=2) + "\n")

print("Applied Kirazium Android compatibility patches.")
