package com.hermes.messenger

import android.Manifest
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * QR code scanner for instant server setup.
 * Scans URL+token from QR code format: hermes://url?token=xxx
 */
@Composable
fun QrScanner(
    onScanned: (url: String, token: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }

    // Check permission
    LaunchedEffect(Unit) {
        hasCameraPermission = context.checkSelfPermission(Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    if (!hasCameraPermission) {
        // Show message and dismiss
        LaunchedEffect(Unit) {
            onDismiss()
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                if (scanned) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                processImage(ctx, imageProxy) { url, token ->
                                    if (!scanned) {
                                        scanned = true
                                        onScanned(url, token)
                                    }
                                }
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e("QrScanner", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Scan overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
        ) {
            // Scanner frame
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .align(Alignment.Center)
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp))
            )

            // Instructions
            Text(
                text = "Наведите камеру на QR-код",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
            )

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Text("✕", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processImage(
    context: Context,
    imageProxy: ImageProxy,
    onResult: (url: String, token: String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    val scanner = BarcodeScanning.getClient()

    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                val rawValue = barcode.rawValue ?: continue
                val config = parseQrConfig(rawValue)
                if (config != null) {
                    onResult(config.first, config.second)
                    break
                }
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

/**
 * Parse QR code content.
 * Supported formats:
 * - hermes://connect?url=...&token=...
 * - {"url":"...","token":"..."}
 * - url|token
 */
private fun parseQrConfig(raw: String): Pair<String, String>? {
    // Format 1: hermes://connect?url=...&token=...
    if (raw.startsWith("hermes://")) {
        try {
            val uri = android.net.Uri.parse(raw)
            val url = uri.getQueryParameter("url") ?: return null
            val token = uri.getQueryParameter("token") ?: return null
            return Pair(url, token)
        } catch (_: Exception) {}
    }

    // Format 2: JSON {"url":"...","token":"..."}
    if (raw.startsWith("{")) {
        try {
            val json = org.json.JSONObject(raw)
            val url = json.getString("url")
            val token = json.getString("token")
            return Pair(url, token)
        } catch (_: Exception) {}
    }

    // Format 3: url|token
    if (raw.contains("|")) {
        val parts = raw.split("|", limit = 2)
        if (parts.size == 2 && parts[0].startsWith("http")) {
            return Pair(parts[0], parts[1])
        }
    }

    return null
}
