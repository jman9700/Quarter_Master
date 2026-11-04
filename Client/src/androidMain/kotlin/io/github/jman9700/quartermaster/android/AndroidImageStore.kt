package io.github.jman9700.quartermaster.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import io.github.jman9700.quartermaster.data.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

class AndroidImageStore(context: Context) : ImageStore {
    private val dir = File(context.filesDir, "images").apply { mkdirs() }

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
    private fun safeName(name: String) = File(name).name

    /** Downscales to at most [MAX_EDGE] px on the long edge, applies EXIF rotation, re-encodes as JPEG. */
    private fun shrink(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IllegalArgumentException("Not an image")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Not an image")

        val scale = MAX_EDGE.toFloat() / max(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            postRotate(exifRotation(bytes).toFloat())
        }
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        return ByteArrayOutputStream().use { out ->
            result.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    private fun exifRotation(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private companion object {
        const val MAX_EDGE = 512
    }
}
