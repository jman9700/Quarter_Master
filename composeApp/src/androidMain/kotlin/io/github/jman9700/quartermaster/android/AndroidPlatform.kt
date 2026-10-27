package io.github.jman9700.quartermaster.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.jman9700.quartermaster.data.ImageStore
import io.github.jman9700.quartermaster.domain.BarcodeHint
import io.github.jman9700.quartermaster.platform.BackupFileResult
import io.github.jman9700.quartermaster.platform.BackupFiles
import io.github.jman9700.quartermaster.platform.ImagePicker
import io.github.jman9700.quartermaster.platform.PlatformServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPlatform(private val appContext: Context) : PlatformServices {

    override val imageStore: ImageStore = AndroidImageStore(appContext)

    override val hasBarcodeScanner: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    @Composable
    override fun BarcodeScanner(onScanned: (raw: String, hint: BarcodeHint) -> Unit, onClose: () -> Unit) =
        CameraBarcodeScanner(onScanned, onClose)

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
        androidx.activity.compose.BackHandler(enabled, onBack)

    @Composable
    override fun rememberImagePicker(onPicked: (ByteArray) -> Unit): ImagePicker {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val currentOnPicked by rememberUpdatedState(onPicked)

        fun deliver(uri: Uri) = scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes != null) currentOnPicked(bytes)
        }

        val photoUri = remember {
            val file = File(context.cacheDir, "camera/capture.jpg").apply { parentFile?.mkdirs() }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::deliver) }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) deliver(photoUri) }
        // The app declares CAMERA for the scanner, so Android requires it to be granted before taking a photo too.
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) camera.launch(photoUri)
        }

        return remember(gallery, camera, permission) {
            object : ImagePicker {
                override val canTakePhoto = hasBarcodeScanner

                override fun takePhoto() {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) camera.launch(photoUri) else permission.launch(Manifest.permission.CAMERA)
                }

                override fun pickFromGallery() =
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }

    @Composable
    override fun rememberBackupFiles(onImportRead: (String) -> Unit, onResult: (BackupFileResult) -> Unit): BackupFiles {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val currentOnImportRead by rememberUpdatedState(onImportRead)
        val currentOnResult by rememberUpdatedState(onResult)
        var pendingExport by remember { mutableStateOf<String?>(null) }

        val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val content = pendingExport
            pendingExport = null
            if (uri == null || content == null) {
                currentOnResult(BackupFileResult.Cancelled)
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
                            ?: error("Couldn't open the file")
                    }
                }
                currentOnResult(result.fold({ BackupFileResult.Exported }, { BackupFileResult.Failed(it.message ?: "Export failed") }))
            }
        }
        val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                currentOnResult(BackupFileResult.Cancelled)
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
                }
                if (text != null) currentOnImportRead(text) else currentOnResult(BackupFileResult.Failed("Couldn't read the file"))
            }
        }

        return remember(createDoc, openDoc) {
            object : BackupFiles {
                override fun export(suggestedName: String, content: String) {
                    pendingExport = content
                    createDoc.launch(suggestedName)
                }

                override fun import() = openDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        }
    }
}
