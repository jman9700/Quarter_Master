package io.github.jman9700.quartermaster.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.jman9700.quartermaster.domain.BarcodeHint
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraBarcodeScanner(onScanned: (String, BarcodeHint) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        denied = !granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraPreview(onScanned = onScanned, onCamera = { camera = it })
            // Aiming guide
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.8f)
                    .height(160.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp)),
            )
            Text(
                "Point the camera at a barcode",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(top = 220.dp),
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (denied) "Quarter Master needs camera access to scan barcodes. You can still type codes in by hand."
                    else "Waiting for camera permission…",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                if (denied) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }
        }

        Box(Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp)) {
            val buttonColors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White)
            IconButton(onClick = onClose, colors = buttonColors, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.Default.Close, contentDescription = "Close scanner")
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(
                    onClick = {
                        torchOn = !torchOn
                        camera?.cameraControl?.enableTorch(torchOn)
                    },
                    colors = buttonColors,
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(if (torchOn) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn, contentDescription = "Toggle flashlight")
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (String, BarcodeHint) -> Unit, onCamera: (Camera) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val currentOnScanned by rememberUpdatedState(onScanned)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8)
                .build(),
        )
        val delivered = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val analyzer = StableReadAnalyzer(scanner) { raw, hint ->
            if (!delivered.getAndSet(true)) main.post { currentOnScanned(raw, hint) }
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            provider.unbindAll()
            onCamera(provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis))
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Only reports a code after reading the same value in two consecutive frames, to filter misreads. */
private class StableReadAnalyzer(
    private val scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    private val onRead: (String, BarcodeHint) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastValue: String? = null

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val code = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                val value = code?.rawValue
                if (value != null && value == lastValue) onRead(value, code.format.toHint())
                lastValue = value
            }
            .addOnCompleteListener { proxy.close() }
    }
}

private fun Int.toHint() = when (this) {
    Barcode.FORMAT_UPC_A -> BarcodeHint.UPC_A
    Barcode.FORMAT_UPC_E -> BarcodeHint.UPC_E
    Barcode.FORMAT_EAN_13 -> BarcodeHint.EAN_13
    Barcode.FORMAT_EAN_8 -> BarcodeHint.EAN_8
    else -> BarcodeHint.UNKNOWN
}
