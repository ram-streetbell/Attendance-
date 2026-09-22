package com.streetbell.attendance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        setContent { KioskScreen(cameraEnabled = granted) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setContent { KioskScreen(cameraEnabled = true) }
        } else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    @Composable private fun KioskScreen(cameraEnabled: Boolean) {
        var faceDetected by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Attendance", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            if (cameraEnabled) {
                AndroidView(factory = { context ->
                    PreviewView(context).also { previewView ->
                        startCamera(previewView) { detected -> faceDetected = detected }
                    }
                }, modifier = Modifier.fillMaxWidth().weight(1f))
            } else Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("Camera permission is required") }
            Text(if (faceDetected) "Face detected — identify employee" else "Stand in front of the camera")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(enabled = faceDetected, onClick = { selected = "IN" }, modifier = Modifier.weight(1f)) { Text("IN") }
                Button(enabled = faceDetected, onClick = { selected = "OUT" }, modifier = Modifier.weight(1f)) { Text("OUT") }
            }
            selected?.let { Text("Selected: $it", modifier = Modifier.padding(12.dp)) }
        }
    }

    private fun startCamera(view: PreviewView, onFace: (Boolean) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        val executor = Executors.newSingleThreadExecutor()
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@setAnalyzer }
                detector.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { onFace(it.isNotEmpty()) }
                    .addOnFailureListener { onFace(false) }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }
}
