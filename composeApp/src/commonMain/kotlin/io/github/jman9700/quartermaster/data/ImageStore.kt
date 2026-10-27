package io.github.jman9700.quartermaster.data

/** Product photos, kept as small JPEG files in app storage. Implemented per platform. */
interface ImageStore {
    /** Shrinks the image to thumbnail size, stores it under a new name and returns that name. */
    suspend fun save(bytes: ByteArray): String

    /** Writes an already-processed image under a known name (used by backup import). */
    suspend fun write(name: String, bytes: ByteArray)

    suspend fun read(name: String): ByteArray?

    fun exists(name: String): Boolean

    /** Something Coil can load (a File on Android/desktop). */
    fun model(name: String): Any?
}
