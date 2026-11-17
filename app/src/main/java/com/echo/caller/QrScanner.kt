package com.echo.caller

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.Preview
import androidx.compose.runtime.Composable
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

@Composable
fun QrScanner(modifier: Modifier = Modifier, onDecoded: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(modifier = modifier, factory = { ctx ->
        val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val executor = Executors.newSingleThreadExecutor()
        val delivered = AtomicBoolean(false)
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { image ->
                if (!delivered.get()) {
                    val decoded = decodeQr(image)
                    if (decoded != null && delivered.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(ctx).execute { onDecoded(decoded) }
                    }
                }
                image.close()
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(ctx))
        previewView
    })
}

private fun decodeQr(image: ImageProxy): String? {
    return try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bytes = ByteArray(width * height)
        val row = ByteArray(rowStride)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, minOf(rowStride, buffer.remaining()))
            for (x in 0 until width) bytes[y * width + x] = row[x * pixelStride]
        }
        val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
        val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        var decoded: String? = null
        var rotated = bitmap
        repeat(4) {
            if (decoded == null) {
                try { decoded = reader.decodeWithState(rotated).text } catch (_: Exception) { }
                reader.reset()
                rotated = rotated.rotateCounterClockwise()
            }
        }
        decoded
    } catch (_: Exception) { null }
}
