package io.github.jman9700.quartermaster.desktop

import io.github.jman9700.quartermaster.data.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

class DesktopImageStore(private val dir: File) : ImageStore {
    init {
        dir.mkdirs()
    }

    override suspend fun save(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val name = "${UUID.randomUUID()}.jpg"
        File(dir, name).writeBytes(shrink(bytes))
        name
    }

    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        File(dir, safeName(name)).writeBytes(bytes)
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        File(dir, safeName(name)).takeIf { it.isFile }?.readBytes()
    }

    override fun exists(name: String) = File(dir, safeName(name)).isFile

    override fun model(name: String): Any? = File(dir, safeName(name)).takeIf { it.isFile }

    /** Backups come from outside the app, so never let a name escape the images folder. */
    private fun safeName(name: String) = File(name.replace('\\', '/')).name

    internal companion object {
        const val MAX_EDGE = 512

        /** Downscales to at most [MAX_EDGE] px on the long edge and re-encodes as JPEG (transparency becomes white). */
        fun shrink(bytes: ByteArray): ByteArray {
            val source = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()
                ?: throw IllegalArgumentException("Not an image")

            val scale = minOf(1f, MAX_EDGE.toFloat() / max(source.width, source.height))
            val width = max(1, (source.width * scale).roundToInt())
            val height = max(1, (source.height * scale).roundToInt())
            val result = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            result.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(source, 0, 0, width, height, Color.WHITE, null)
                dispose()
            }

            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            return ByteArrayOutputStream().use { out ->
                ImageIO.createImageOutputStream(out).use { stream ->
                    writer.output = stream
                    val params = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = 0.85f
                    }
                    writer.write(null, IIOImage(result, null, null), params)
                    writer.dispose()
                }
                out.toByteArray()
            }
        }
    }
}
