package com.streetbell.attendance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.sqrt

private data class Person(val id: String, val name: String, val template: FloatArray)

class MainActivity : ComponentActivity() {
    private val http = OkHttpClient()
    private val executor = Executors.newSingleThreadExecutor()
    private var capture: ImageCapture? = null
    private var people = emptyList<Person>()
    private val baseUrl = "https://attendance-backend-rkny.onrender.com"
    private var deviceToken: String? = null
    private var busy = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { setUi(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceToken = getSharedPreferences("attendance", Context.MODE_PRIVATE).getString("deviceToken", null)
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setUi(allowed)
        if (!allowed) permission.launch(Manifest.permission.CAMERA)
    }

    private fun setUi(camera: Boolean) { setContent { App(camera) } }

    @Composable
    private fun App(cameraEnabled: Boolean) {
        var paired by remember { mutableStateOf(deviceToken != null) }
        var code by remember { mutableStateOf("") }
        var message by remember { mutableStateOf(if (paired) "Syncing employees..." else "Pair this attendance device") }
        var detected by remember { mutableStateOf(false) }
        var person by remember { mutableStateOf<Person?>(null) }
        var score by remember { mutableStateOf(0f) }
        var lastStatus by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(paired) { if (paired) refreshManifest { message = it } }

        if (!paired) {
            Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Attendance Device", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Pairing code") }, singleLine = true)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { pair(code) { ok, text -> message = text; if (ok) paired = true } }) { Text("PAIR DEVICE") }
                Spacer(Modifier.height(12.dp))
                Text(message)
            }
            return
        }

        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Attendance", style = MaterialTheme.typography.headlineMedium)
            Text(message, modifier = Modifier.padding(8.dp))
            if (cameraEnabled) {
                AndroidView(factory = { context -> PreviewView(context).also { startCamera(it) { d, p, s -> detected = d; person = p; score = s } } }, modifier = Modifier.fillMaxWidth().height(420.dp))
            } else Text("Camera permission required", modifier = Modifier.padding(32.dp))
            Spacer(Modifier.height(12.dp))
            Text(when { person != null -> "${person!!.name} • ${(score * 100).toInt()}%"; detected -> "Unknown employee"; else -> "Stand in front of the camera" })
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = person != null && !busy, onClick = { person?.let { submit("IN", it.id, score) { text -> lastStatus = "IN"; message = text } } }, modifier = Modifier.weight(1f)) { Text("IN") }
                Button(enabled = person != null && !busy, onClick = { person?.let { submit("OUT", it.id, score) { text -> lastStatus = "OUT"; message = text } } }, modifier = Modifier.weight(1f)) { Text("OUT") }
            }
            lastStatus?.let { Text("Selected: $it", modifier = Modifier.padding(8.dp)) }
        }
    }

    private fun pair(code: String, done: (Boolean, String) -> Unit) = executor.execute {
        try {
            require(code.trim().length >= 4) { "Enter a valid pairing code" }
            val body = JSONObject().put("pairingCode", code.trim()).put("deviceName", "Attendance Kiosk").put("appVersion", "1.4.1").toString().toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url("$baseUrl/api/v1/device/pair").post(body).build()).execute().use { response ->
                if (!response.isSuccessful) error("Pairing failed (${response.code})")
                deviceToken = JSONObject(response.body?.string() ?: error("Empty pairing response")).getString("deviceToken")
            }
            getSharedPreferences("attendance", MODE_PRIVATE).edit().putString("deviceToken", deviceToken).apply()
            runOnUiThread { done(true, "Device paired") }
        } catch (e: Exception) { runOnUiThread { done(false, e.message ?: "Pairing failed") } }
    }

    private fun refreshManifest(done: (String) -> Unit) = executor.execute {
        try {
            val response = http.newCall(auth(Request.Builder().url("$baseUrl/api/v1/device/manifest").get())).execute()
            response.use {
                if (!it.isSuccessful) error("Manifest error ${it.code}")
                val employees = JSONObject(it.body?.string() ?: error("Empty manifest")).getJSONArray("employees")
                val list = mutableListOf<Person>()
                for (i in 0 until employees.length()) {
                    val e = employees.getJSONObject(i)
                    val a = e.optJSONArray("faceTemplate") ?: continue
                    if (a.length() != 1024) continue
                    list += Person(e.getString("id"), e.getString("name"), FloatArray(1024) { a.getDouble(it).toFloat() })
                }
                people = list
            }
            runOnUiThread { done("Ready • ${people.size} employees") }
        } catch (e: Exception) { runOnUiThread { done("Sync failed: ${e.message}") } }
    }

    private fun startCamera(view: PreviewView, onMatch: (Boolean, Person?, Float) -> Unit) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                var last = 0L
                analysis.setAnalyzer(executor) { proxy ->
                    if (System.currentTimeMillis() - last < 400) { proxy.close(); return@setAnalyzer }
                    last = System.currentTimeMillis()
                    val image = proxy.image
                    if (image == null) { proxy.close(); return@setAnalyzer }
                    val rotation = proxy.imageInfo.rotationDegrees
                    detector.process(InputImage.fromMediaImage(image, rotation)).addOnSuccessListener { faces ->
                        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        if (face == null) { onMatch(false, null, 0f); return@addOnSuccessListener }
                        // ML Kit returns the face rectangle in the rotated InputImage coordinate system.
                        // Rotate the camera bitmap by the same amount before cropping, otherwise the
                        // rectangle is applied to the wrong axes and every employee becomes unknown.
                        val bitmap = yuvToBitmap(proxy, rotation)
                        val template = FaceTemplate.from(bitmap, face.boundingBox)
                        var best: Person? = null
                        var bestScore = 0f
                        if (template != null) {
                            people.forEach { p ->
                                val s = FaceTemplate.similarity(template, p.template)
                                if (s > bestScore) { bestScore = s; best = p }
                            }
                        }
                        onMatch(true, if (bestScore >= 0.68f) best else null, bestScore)
                    }.addOnCompleteListener { proxy.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis, capture)
            } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun submit(kind: String, employeeId: String, score: Float, done: (String) -> Unit) {
        if (busy) return
        busy = true
        val imageCapture = capture ?: run { busy = false; done("Camera is not ready"); return }
        executor.execute {
            val file = File.createTempFile("attendance_", ".jpg", cacheDir)
            imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor, object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) { busy = false; runOnUiThread { done("Photo capture failed") } }
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    try {
                        val sig = http.newCall(auth(Request.Builder().url("$baseUrl/api/v1/cloudinary/signature").get())).execute().use { r ->
                            if (!r.isSuccessful) error("Cloudinary authorization failed")
                            JSONObject(r.body?.string() ?: error("Empty signature"))
                        }
                        val upload = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                            .addFormDataPart("api_key", sig.getString("apiKey"))
                            .addFormDataPart("timestamp", sig.getString("timestamp"))
                            .addFormDataPart("signature", sig.getString("signature"))
                            .addFormDataPart("folder", sig.getString("folder"))
                            .build()
                        val cloud = http.newCall(Request.Builder().url("https://api.cloudinary.com/v1_1/${sig.getString("cloudName")}/image/upload").post(upload).build()).execute().use { r ->
                            if (!r.isSuccessful) error("Photo upload failed")
                            JSONObject(r.body?.string() ?: error("Empty upload response"))
                        }
                        val event = JSONObject().put("clientEventId", UUID.randomUUID().toString()).put("employeeId", employeeId).put("status", kind).put("capturedAt", Instant.now().toString()).put("faceMatchScore", score).put("cloudinaryPublicId", cloud.optString("public_id")).put("cloudinaryAssetId", cloud.optString("asset_id")).put("photoSecureUrl", cloud.optString("secure_url"))
                        http.newCall(auth(Request.Builder().url("$baseUrl/api/v1/device/attendance").post(event.toString().toRequestBody("application/json".toMediaType())))).execute().use { r ->
                            if (!r.isSuccessful) error(if (r.code == 409) "Same status was already recorded recently" else "Attendance submission failed (${r.code})")
                        }
                        file.delete(); busy = false
                        runOnUiThread { done("Attendance recorded: $kind") }
                    } catch (e: Exception) { file.delete(); busy = false; runOnUiThread { done(e.message ?: "Attendance failed") } }
                }
            })
        }
    }

    private fun auth(builder: Request.Builder): Request = builder.header("x-device-token", deviceToken ?: "").build()

    private object FaceTemplate {
        fun from(source: Bitmap, box: Rect): FloatArray? {
            val p = (box.width() * .22f).toInt()
            val l = (box.left - p).coerceIn(0, source.width - 1)
            val t = (box.top - p).coerceIn(0, source.height - 1)
            val r = (box.right + p).coerceIn(l + 1, source.width)
            val b = (box.bottom + p).coerceIn(t + 1, source.height)
            val crop = Bitmap.createBitmap(source, l, t, r - l, b - t)
            val small = Bitmap.createScaledBitmap(crop, 32, 32, true)
            val v = FloatArray(1024)
            var mean = 0f
            var i = 0
            for (y in 0 until 32) for (x in 0 until 32) {
                val px = small.getPixel(x, y)
                val q = (.299f * ((px shr 16) and 255) + .587f * ((px shr 8) and 255) + .114f * (px and 255)) / 255f
                v[i++] = q
                mean += q
            }
            mean /= 1024f
            var norm = 0f
            for (j in v.indices) { v[j] -= mean; norm += v[j] * v[j] }
            norm = sqrt(norm).coerceAtLeast(.0001f)
            for (j in v.indices) v[j] /= norm
            return v
        }

        fun similarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in 0 until minOf(a.size, b.size)) dot += a[i] * b[i]
            return ((dot + 1f) / 2f).coerceIn(0f, 1f)
        }
    }

    private fun yuvToBitmap(proxy: ImageProxy, rotation: Int): Bitmap {
        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]
        val y = yPlane.buffer.duplicate()
        val u = uPlane.buffer.duplicate()
        val v = vPlane.buffer.duplicate()
        val yb = ByteArray(y.remaining()).also { y.get(it) }
        val ub = ByteArray(u.remaining()).also { u.get(it) }
        val vb = ByteArray(v.remaining()).also { v.get(it) }
        val w = proxy.width
        val h = proxy.height
        val nv21 = ByteArray(w * h + w * h / 2)
        System.arraycopy(yb, 0, nv21, 0, minOf(yb.size, w * h))
        var pos = w * h
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            val ui = row * uPlane.rowStride + col * uPlane.pixelStride
            val vi = row * vPlane.rowStride + col * vPlane.pixelStride
            if (ui < ub.size && vi < vb.size && pos + 1 < nv21.size) { nv21[pos++] = vb[vi]; nv21[pos++] = ub[ui] }
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 80, out)
        val raw = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) ?: error("Unable to decode camera frame")
        if (rotation == 0) return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    }
}
