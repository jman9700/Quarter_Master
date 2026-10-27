package io.github.jman9700.quartermaster.platform

import androidx.compose.runtime.Composable
import io.github.jman9700.quartermaster.data.ImageStore
import io.github.jman9700.quartermaster.domain.BarcodeHint

/**
 * Everything the shared app needs from the device. Android implements it now; the Windows and
 * Linux ports provide their own (e.g. webcam or USB scanner, native file dialogs).
 */
interface PlatformServices {
    val imageStore: ImageStore

    /** Whether a camera scanner is available; when it isn't, the app offers typed entry only. */
    val hasBarcodeScanner: Boolean

    /** Full-screen live scanner. Calls [onScanned] once with the first retail barcode it reads. */
    @Composable
    fun BarcodeScanner(onScanned: (raw: String, hint: BarcodeHint) -> Unit, onClose: () -> Unit)

    /** System back gesture / button handling for full-screen overlays (a no-op on desktop). */
    @Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit)

    @Composable
    fun rememberImagePicker(onPicked: (ByteArray) -> Unit): ImagePicker

    @Composable
    fun rememberBackupFiles(onImportRead: (String) -> Unit, onResult: (BackupFileResult) -> Unit): BackupFiles
}

interface ImagePicker {
    val canTakePhoto: Boolean
    fun takePhoto()
    fun pickFromGallery()
}

interface BackupFiles {
    fun export(suggestedName: String, content: String)
    fun import()
}

sealed interface BackupFileResult {
    data object Exported : BackupFileResult
    data object Cancelled : BackupFileResult
    data class Failed(val message: String) : BackupFileResult
}
