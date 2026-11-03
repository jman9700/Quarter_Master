package io.github.jman9700.quartermaster.desktop

import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopImageStoreTest {
    private val dir = Files.createTempDirectory("qm-images").toFile().apply { deleteOnExit() }
    private val store = DesktopImageStore(dir)

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it)
        it.toByteArray()
    }

    @Test
    fun saveDownscalesToJpegWithin512() = runTest {
        val name = store.save(png(2000, 1000))
        assertTrue(name.endsWith(".jpg"))
        val stored = ImageIO.read(File(dir, name))
        assertEquals(512, stored.width)
        assertEquals(256, stored.height)
    }

    @Test
    fun smallImagesKeepTheirSize() = runTest {
        val stored = ImageIO.read(File(dir, store.save(png(100, 40))))
        assertEquals(100, stored.width)
        assertEquals(40, stored.height)
    }

    @Test
    fun rejectsNonImages() = runTest {
        assertFailsWith<IllegalArgumentException> { store.save("not an image".encodeToByteArray()) }
    }

    @Test
    fun namesCannotEscapeTheFolder() = runTest {
        store.write("..\\..\\evil.jpg", byteArrayOf(1, 2, 3))
        store.write("../other.jpg", byteArrayOf(4))
        assertFalse(File(dir.parentFile, "evil.jpg").exists())
        assertContentEquals(byteArrayOf(1, 2, 3), store.read("evil.jpg"))
        assertTrue(store.exists("other.jpg"))
        assertNull(store.read("missing.jpg"))
    }
}
