package io.github.jman9700.quartermaster.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.jman9700.quartermaster.data.ImageStore
import io.github.jman9700.quartermaster.domain.BarcodeHint
import io.github.jman9700.quartermaster.platform.BackupFileResult
import io.github.jman9700.quartermaster.platform.BackupFiles
import io.github.jman9700.quartermaster.platform.ImagePicker
import io.github.jman9700.quartermaster.platform.PlatformServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Windows / Linux. USB barcode scanners type like a keyboard, so with [hasBarcodeScanner] false the
 * app goes straight to the typed-entry dialog and the scanner fills it in.
 */
class DesktopPlatform(dataDir: File, private val window: () -> Frame?) : PlatformServices {

    override val imageStore: ImageStore = DesktopImageStore(File(dataDir, "images"))

    override val hasBarcodeScanner: Boolean = false

    @Composable
    override fun BarcodeScanner(onScanned: (raw: String, hint: BarcodeHint) -> Unit, onClose: () -> Unit) {
        // Never shown: the shared UI only opens the scanner when hasBarcodeScanner is true.
    }

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

    @Composable
    override fun rememberImagePicker(onPicked: (ByteArray) -> Unit): ImagePicker {
        val scope = rememberCoroutineScope()
        val currentOnPicked by rememberUpdatedState(onPicked)
        return remember {
            object : ImagePicker {
                override val canTakePhoto = false

                override fun takePhoto() = Unit

                override fun pickFromGallery() {
                    val file = chooseFile("Choose an image", FileDialog.LOAD, IMAGE_EXTENSIONS) ?: return
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
                        if (bytes != null) currentOnPicked(bytes)
                    }
                }
            }
        }
    }

    @Composable
    override fun rememberBackupFiles(onImportRead: (String) -> Unit, onResult: (BackupFileResult) -> Unit): BackupFiles {
        val scope = rememberCoroutineScope()
        val currentOnImportRead by rememberUpdatedState(onImportRead)
        val currentOnResult by rememberUpdatedState(onResult)
        return remember {
            object : BackupFiles {
                override fun export(suggestedName: String, content: String) {
                    val chosen = chooseFile("Export backup", FileDialog.SAVE, listOf("json"), suggestedName)
                    if (chosen == null) {
                        currentOnResult(BackupFileResult.Cancelled)
                        return
                    }
                    val file = if (chosen.extension.isEmpty()) File(chosen.path + ".json") else chosen
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { file.writeText(content) } }
                        currentOnResult(result.fold({ BackupFileResult.Exported }, { BackupFileResult.Failed(it.message ?: "Export failed") }))
                    }
                }

                override fun import() {
                    val file = chooseFile("Import backup", FileDialog.LOAD, listOf("json"))
                    if (file == null) {
                        currentOnResult(BackupFileResult.Cancelled)
                        return
                    }
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
                        if (text != null) currentOnImportRead(text) else currentOnResult(BackupFileResult.Failed("Couldn't read the file"))
                    }
                }
            }
        }
    }

    /** Native open / save dialog. Blocks the UI thread like any modal dialog; returns null when cancelled. */
    private fun chooseFile(title: String, mode: Int, extensions: List<String>, suggestedName: String? = null): File? {
        val dialog = FileDialog(window(), title, mode)
        dialog.setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
        when {
            suggestedName != null -> dialog.file = suggestedName
            // Windows ignores the filename filter, but a wildcard file name filters the list instead.
            isWindows -> dialog.file = extensions.joinToString(";") { "*.$it" }
        }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }

    private companion object {
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "gif", "bmp")
    }
}
