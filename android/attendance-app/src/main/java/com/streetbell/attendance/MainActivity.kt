package com.streetbell.attendance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
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

private data class Person(
    val id: String,
    val name: String,
    val template: FloatArray
)

class MainActivity : ComponentActivity() {
    private val http = OkHttpClient()
    private val executor = Executors.newSingleThreadExecutor()
    private var capture: ImageCapture? = null
    private var people = listOf<Person>()
    private var baseUrl = "http://10.0.2.2:8080"
    private var deviceToken: String? = null
    private var deviceId: String? = null
    private var busy = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        setContent { App(granted) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("attendance", Context.MODE_PRIVATE)
        baseUrl = prefs.getString("baseUrl", baseUrl) ?: baseUrl
        deviceToken = prefs.getString("deviceToken", null)
        deviceId = prefs.getString("deviceId", null)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setContent { App(true) }
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    @Composable
    private fun App(cameraEnabled: Boolean) {
        var paired by remember { mutableStateOf(deviceToken != null) }
        var code by remember { mutableStateOf("") }
                var message by remember {
            mutableStateOf(if (paired) "Loading employees..." else "Pair this attendance device")
        }
        var face by remember { mutableStateOf<Person?>(null) }
        var detected by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        var score by remember { mutableStateOf(0f) }

        LaunchedEffect(paired) {
            if (paired) {
                refreshManifest { message = it }
            }
        }

        if (!paired) {
            Column(
                modifier = Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Attendance Device", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    label = { Text("Pairing code") },
                    singleLine = true
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        pair(code) { ok, msg ->
                            message = msg
                            if (ok) paired = true
                        }
                    }
                ) {
                    Text("PAIR DEVICE")
                }
                Spacer(Modifier.height(12.dp))
                Text(message)
            }
            return
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Attendance", style = MaterialTheme.typography.headlineMedium)
            Text(message, modifier = Modifier.padding(8.dp))

            if (cameraEnabled) {
                AndroidView(
                    factory = { context ->
                        PreviewView(context).also { previewView ->
                            startCamera(previewView) { isDetected, person, matchScore ->
                                detected = isDetected
                                face = person
                                score = matchScore
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Camera permission required")
                }
            }

            Text(
                when {
                    face != null -> "${face!!.name}  •  ${(score * 100).toInt()}%"
                    detected -> "Unknown employee"
                    else -> "Stand in front of the camera"
                }
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    enabled = face != null && !busy,
                    onClick = {
                        val employee = face ?: return@Button
                        submit("IN", employee.id, score) {
                            status = "IN"
                            message = it
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("IN")
                }

                Button(
                    enabled = face != null && !busy,
                    onClick = {
                        val employee = face ?: return@Button
                        submit("OUT", employee.id, score) {
                            status = "OUT"
                            message = it
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("OUT")
                }
            }

            status?.let {
                Text("Selected: $it", modifier = Modifier.padding(8.dp))
            }
        }
    }

    private fun pair(code: String, done: (Boolean, String) -> Unit) {
        executor.execute {
            try {
                if (code.trim().length < 4) {
                    runOnUiThread { done(false, "Enter a valid pairing code") }
                    return@execute
                }

                val body = JSONObject()
                    .put("pairingCode", code.trim())
                    .put("deviceName", "Attendance Kiosk")
                    .put("appVersion", "1.0.0")
                    .toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl/api/v1/device/pair")
                    .post(body)
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Pairing failed (${response.code})")
                    }
                    val responseBody = response.body ?: throw Exception("Empty pairing response")
                    val json = JSONObject(responseBody.string())
                    deviceToken = json.getString("deviceToken")
                    deviceId = json.getString("deviceId")
                }

                getSharedPreferences("attendance", MODE_PRIVATE)
                    .edit()
                    .putString("baseUrl", baseUrl)
                    .putString("deviceToken", deviceToken)
                    .putString("deviceId", deviceId)
                    .apply()

                runOnUiThread { done(true, "Device paired") }
                refreshManifest()
            } catch (e: Exception) {
                runOnUiThread { done(false, e.message ?: "Pairing failed") }
            }
        }
    }

    private fun refreshManifest(done: (String) -> Unit = {}) {
        executor.execute {
            try {
                val request = auth(
                    Request.Builder()
                        .url("$baseUrl/api/v1/device/manifest")
                        .get()
                )

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Manifest error ${response.code}")
                    }
                    val body = response.body ?: throw Exception("Empty manifest response")
                    val employees = JSONObject(body.string()).getJSONArray("employees")
                    val list = mutableListOf<Person>()

                    for (i in 0 until employees.length()) {
                        val employee = employees.getJSONObject(i)
                        val templateArray = employee.optJSONArray("faceTemplate") ?: continue
                        if (templateArray.length() != 1024) continue

                        val template = FloatArray(1024) { index ->
                            templateArray.getDouble(index).toFloat()
                        }
                        list += Person(
                            id = employee.getString("id"),
                            name = employee.getString("name"),
                            template = template
                        )
                    }

                    people = list
                }

                runOnUiThread { done("Ready • ${people.size} employees") }
            } catch (e: Exception) {
                runOnUiThread { done("Sync failed: ${e.message}") }
            }
        }
    }

    private fun startCamera(
        view: PreviewView,
        onMatch: (Boolean, Person?, Float) -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = view.surfaceProvider
                }

                capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val detector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .build()
                )

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var lastAnalysis = 0L
                analysis.setAnalyzer(executor) { proxy ->
                    val now = System.currentTimeMillis()
                    if (now - lastAnalysis < 350L) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    lastAnalysis = now

                    val mediaImage = proxy.image
                    if (mediaImage == null) {
                        proxy.close()
                        return@setAnalyzer
                    }

                    detector.process(
                        InputImage.fromMediaImage(
                            mediaImage,
                            proxy.imageInfo.rotationDegrees
                        )
                    ).addOnSuccessListener { faces ->
                        val detectedFace = faces.maxByOrNull {
                            it.boundingBox.width() * it.boundingBox.height()
                        }

                        if (detectedFace == null) {
                            onMatch(false, null, 0f)
                            return@addOnSuccessListener
                        }

                        val bitmap = yuvToBitmap(proxy)
                        val template = FaceTemplate.from(bitmap, detectedFace.boundingBox)
                        var bestPerson: Person? = null
                        var bestScore = 0f

                        if (template != null) {
                            for (person in people) {
                                val similarity = FaceTemplate.similarity(template, person.template)
                                if (similarity > bestScore) {
                                    bestScore = similarity
                                    bestPerson = person
                                }
                            }
                        }

                        val accepted = bestPerson != null && bestScore >= 0.82f
                        onMatch(true, if (accepted) bestPerson else null, bestScore)
                    }.addOnCompleteListener {
                        proxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis,
                    capture
                )
            } catch (_: Exception) {
                // Camera startup failure is surfaced by the UI as no detected face.
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun submit(
        kind: String,
        employeeId: String,
        score: Float,
        done: (String) -> Unit
    ) {
        if (busy) return
        busy = true

        val imageCapture = capture
        if (imageCapture == null) {
            busy = false
            done("Camera is not ready")
            return
        }

        executor.execute {
            try {
                val file = File.createTempFile("attendance_", ".jpg", cacheDir)
                val output = ImageCapture.OutputFileOptions.Builder(file).build()

                imageCapture.takePicture(
                    output,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exception: ImageCaptureException) {
                            busy = false
                            runOnUiThread { done("Photo capture failed") }
                        }

                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            try {
                                val signatureRequest = auth(
                                    Request.Builder()
                                        .url("$baseUrl/api/v1/cloudinary/signature")
                                        .get()
                                )

                                val signatureJson = http.newCall(signatureRequest).execute().use { response ->
                                    if (!response.isSuccessful) {
                                        throw Exception("Cloudinary authorization failed")
                                    }
                                    val body = response.body ?: throw Exception("Empty Cloudinary response")
                                    JSONObject(body.string())
                                }

                                val upload = MultipartBody.Builder()
                                    .setType(MultipartBody.FORM)
                                    .addFormDataPart(
                                        "file",
                                        file.name,
                                        file.asRequestBody("image/jpeg".toMediaType())
                                    )
                                    .addFormDataPart("api_key", signatureJson.getString("apiKey"))
                                    .addFormDataPart("timestamp", signatureJson.getString("timestamp"))
                                    .addFormDataPart("signature", signatureJson.getString("signature"))
                                    .addFormDataPart("folder", signatureJson.getString("folder"))
                                    .build()

                                val uploadRequest = Request.Builder()
                                    .url("https://api.cloudinary.com/v1_1/${signatureJson.getString("cloudName")}/image/upload")
                                    .post(upload)
                                    .build()

                                val uploaded = http.newCall(uploadRequest).execute().use { response ->
                                    if (!response.isSuccessful) {
                                        throw Exception("Photo upload failed")
                                    }
                                    val body = response.body ?: throw Exception("Empty upload response")
                                    JSONObject(body.string())
                                }

                                val event = JSONObject()
                                    .put("clientEventId", UUID.randomUUID().toString())
                                    .put("employeeId", employeeId)
                                    .put("status", kind)
                                    .put("capturedAt", Instant.now().toString())
                                    .put("faceMatchScore", score)
                                    .put("cloudinaryPublicId", uploaded.optString("public_id"))
                                    .put("cloudinaryAssetId", uploaded.optString("asset_id"))
                                    .put("photoSecureUrl", uploaded.optString("secure_url"))

                                val attendanceRequest = auth(
                                    Request.Builder()
                                        .url("$baseUrl/api/v1/device/attendance")
                                        .post(event.toString().toRequestBody("application/json".toMediaType()))
                                )

                                http.newCall(attendanceRequest).execute().use { response ->
                                    if (!response.isSuccessful) {
                                        if (response.code == 409) {
                                            throw Exception("Same status was already recorded recently")
                                        }
                                        throw Exception("Attendance submission failed (${response.code})")
                                    }
                                }

                                file.delete()
                                busy = false
                                runOnUiThread { done("Attendance recorded: $kind") }
                            } catch (e: Exception) {
                                file.delete()
                                busy = false
                                runOnUiThread { done(e.message ?: "Attendance failed") }
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                busy = false
                runOnUiThread { done(e.message ?: "Attendance failed") }
            }
        }
    }

    private fun auth(builder: Request.Builder): Request {
        return builder
            .header("x-device-token", deviceToken ?: "")
            .build()
    }

    private object FaceTemplate {
        fun from(source: Bitmap, box: Rect): FloatArray? {
            val padding = (box.width() * 0.18f).toInt()
            val left = (box.left - padding).coerceAtLeast(0)
            val top = (box.top - padding).coerceAtLeast(0)
            val right = (box.right + padding).coerceAtMost(source.width)
            val bottom = (box.bottom + padding).coerceAtMost(source.height)

            if (right <= left || bottom <= top) return null

            val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
            val small = Bitmap.createScaledBitmap(crop, 32, 32, true)
            val values = FloatArray(1024)
            var index = 0
            var mean = 0f

            for (y in 0 until 32) {
                for (x in 0 until 32) {
                    val pixel = small.getPixel(x, y)
                    val value = (
                        0.299f * ((pixel shr 16) and 255) +
                            0.587f * ((pixel shr 8) and 255) +
                            0.114f * (pixel and 255)
                        ) / 255f
                    values[index++] = value
                    mean += value
                }
            }

            mean /= 1024f
            var norm = 0f
            for (i in values.indices) {
                values[i] -= mean
                norm += values[i] * values[i]
            }

            norm = sqrt(norm).coerceAtLeast(0.0001f)
            for (i in values.indices) {
                values[i] /= norm
            }
            return values
        }

        fun similarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            for (i in 0 until minOf(a.size, b.size)) {
                dot += a[i] * b[i]
            }
            return ((dot + 1f) / 2f).coerceIn(0f, 1f)
        }
    }

    private fun yuvToBitmap(proxy: ImageProxy): Bitmap {
        val yBuffer = proxy.planes[0].buffer
        val uBuffer = proxy.planes[1].buffer
        val vBuffer = proxy.planes[2].buffer

        val yBytes = ByteArray(yBuffer.remaining()).also { yBuffer.get(it) }
        val uBytes = ByteArray(uBuffer.remaining()).also { uBuffer.get(it) }
        val vBytes = ByteArray(vBuffer.remaining()).also { vBuffer.get(it) }

        val width = proxy.width
        val height = proxy.height
        val nv21 = ByteArray(width * height + width * height / 2)
        System.arraycopy(yBytes, 0, nv21, 0, minOf(yBytes.size, width * height))

        var position = width * height
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]

        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                if (uIndex < uBytes.size && vIndex < vBytes.size && position + 1 < nv21.size) {
                    nv21[position++] = vBytes[vIndex]
                    nv21[position++] = uBytes[uIndex]
                }
            }
        }

        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val output = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 75, output)
        val bytes = output.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Unable to decode camera frame")
    }
}
