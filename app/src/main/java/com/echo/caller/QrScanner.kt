package com.echo.caller

import android.graphics.Rect
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Composable
fun QrScanner(modifier: Modifier = Modifier, onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnDecoded = rememberUpdatedState(onDecoded)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val disposed = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        disposed.set(false)
        onDispose {
            disposed.set(true)
            providerRef.getAndSet(null)?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val delivered = AtomicBoolean(false)
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener({
                if (!disposed.get()) {
                    try {
                        val provider = providerFuture.get()
                        providerRef.set(provider)
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysis.setAnalyzer(analysisExecutor) { image ->
                            try {
                                if (!disposed.get() && !delivered.get()) {
                                    val decoded = decodeQr(image)
                                    if (decoded != null && delivered.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(viewContext).execute {
                                            if (!disposed.get()) latestOnDecoded.value(decoded)
                                        }
                                    }
                                }
                            } finally {
                                image.close()
                            }
                        }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (_: Exception) {
                        // Camera binding failures are kept from crashing the pairing screen.
                    }
                }
            }, ContextCompat.getMainExecutor(viewContext))
            previewView
        }
    )
}

/**
 * Reads the Y (luminance) plane using its actual buffer position, row stride,
 * pixel stride and crop rectangle. These values vary between camera devices.
 */
private fun decodeQr(image: ImageProxy): String? {
    return try {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val basePosition = buffer.position()
        val limit = buffer.limit()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val crop: Rect = image.cropRect

        val left = crop.left.coerceAtLeast(0)
        val top = crop.top.coerceAtLeast(0)
        val right = crop.right.coerceAtMost(image.width)
        val bottom = crop.bottom.coerceAtMost(image.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null

        val luminance = ByteArray(width * height)
        for (y in 0 until height) {
            val sourceRow = basePosition + (top + y) * rowStride + left * pixelStride
            for (x in 0 until width) {
                val sourceIndex = sourceRow + x * pixelStride
                if (sourceIndex >= limit) return null
                luminance[y * width + x] = buffer.get(sourceIndex)
            }
        }

        val source = PlanarYUVLuminanceSource(
            luminance, width, height, 0, 0, width, height, false
        )
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        val reader = MultiFormatReader().apply { setHints(hints) }

        // Camera sensor orientation differs by device. Try each right-angle
        // orientation, then retry with inverted luminance for unusual previews.
        for (variant in 0..1) {
            var rotated = if (variant == 0) source else source.invert()
            repeat(4) {
                try {
                    return reader.decodeWithState(BinaryBitmap(HybridBinarizer(rotated))).text
                } catch (_: Exception) {
                    reader.reset()
                    try {
                        rotated = rotated.rotateCounterClockwise()
                    } catch (_: Exception) {
                        return@repeat
                    }
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}
