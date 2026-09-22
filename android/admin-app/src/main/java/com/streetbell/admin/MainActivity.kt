package com.streetbell.admin

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.sqrt

private data class Employee(val id: String, val code: String, val name: String, val department: String?)
private data class Device(val id: String, val name: String, val status: String, val pairingCode: String?)
private data class Attendance(val employee: String, val status: String, val time: String, val device: String, val photo: String?)

class MainActivity : ComponentActivity() {
    private val http = OkHttpClient()
    private val executor = Executors.newCachedThreadPool()
    private var baseUrl = "http://10.0.2.2:8080"
    private var token: String? = null
    private var selectedPhoto: Uri? = null
    private var photoChanged: (() -> Unit)? = null

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedPhoto = uri
        photoChanged?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("admin", 0)
        baseUrl = prefs.getString("url", baseUrl) ?: baseUrl
        token = prefs.getString("token", null)
        setContent { App() }
    }

    @Composable
    private fun App() {
        var logged by remember { mutableStateOf(token != null) }
        var url by remember { mutableStateOf(baseUrl) }
        var email by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }

        if (!logged) {
            Column(
                Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Attendance Admin", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(url, { url = it }, label = { Text("Backend URL") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    baseUrl = url.trimEnd('/')
                    login(email, pass) { ok, msg ->
                        message = msg
                        if (ok) logged = true
                    }
                }) { Text("LOGIN") }
                Text(message)
            }
        } else {
            AdminHome()
        }
    }

    @Composable
    private fun AdminHome() {
        var tab by remember { mutableStateOf("Dashboard") }
        var employees by remember { mutableStateOf(emptyList<Employee>()) }
        var devices by remember { mutableStateOf(emptyList<Device>()) }
        var events by remember { mutableStateOf(emptyList<Attendance>()) }
        var message by remember { mutableStateOf("Loading…") }

        LaunchedEffect(Unit) {
            loadEmployees { employees = it }
            loadDevices { devices = it }
            loadAttendance { events = it; message = "Updated" }
        }

        Row(Modifier.fillMaxSize().padding(12.dp)) {
            NavigationRail {
                listOf("Dashboard", "Employees", "Devices", "Attendance").forEach { item ->
                    NavigationRailItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.take(1)) },
                        label = { Text(item) }
                    )
                }
            }
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Text("Attendance Admin", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                when (tab) {
                    "Dashboard" -> Dashboard(employees, devices, events)
                    "Employees" -> Employees(employees, message) { loadEmployees { employees = it } }
                    "Devices" -> Devices(devices, message)
                    "Attendance" -> AttendanceList(events, message) { loadAttendance { events = it } }
                }
            }
        }
    }

    @Composable
    private fun Dashboard(e: List<Employee>, d: List<Device>, a: List<Attendance>) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card { Column(Modifier.padding(18.dp)) { Text("Employees"); Text(e.size.toString(), style = MaterialTheme.typography.headlineMedium) } }
            Card { Column(Modifier.padding(18.dp)) { Text("Devices online"); Text(d.count { it.status == "online" }.toString(), style = MaterialTheme.typography.headlineMedium) } }
            Card { Column(Modifier.padding(18.dp)) { Text("Events"); Text(a.size.toString(), style = MaterialTheme.typography.headlineMedium) } }
        }
    }

    @Composable
    private fun Employees(list: List<Employee>, message: String, reload: () -> Unit) {
        var code by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var dept by remember { mutableStateOf("") }
        var changed by remember { mutableStateOf(0) }

        DisposableEffect(Unit) {
            photoChanged = { changed++ }
            onDispose { photoChanged = null }
        }

        Column {
            Text("Employees", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(code, { code = it }, label = { Text("Employee code") })
            OutlinedTextField(name, { name = it }, label = { Text("Name") })
            OutlinedTextField(dept, { dept = it }, label = { Text("Department") })
            Button(onClick = { picker.launch("image/*") }) {
                Text(if (selectedPhoto == null) "Choose face photo" else "Face photo selected")
            }
            Button(
                enabled = code.isNotBlank() && name.isNotBlank() && selectedPhoto != null,
                onClick = {
                    createEmployee(code, name, dept, selectedPhoto!!) {
                        selectedPhoto = null
                        reload()
                    }
                }
            ) { Text("ADD EMPLOYEE") }
            Text(message)
            Spacer(Modifier.height(10.dp))
            LazyColumn {
                items(list) { e ->
                    Text(
                        "${e.code} • ${e.name}${if (e.department.isNullOrBlank()) "" else " • ${e.department}"}",
                        Modifier.padding(8.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun Devices(list: List<Device>, message: String) {
        Column {
            Text("Devices", style = MaterialTheme.typography.titleLarge)
            Text(message)
            LazyColumn {
                items(list) { d ->
                    Card(Modifier.fillMaxWidth().padding(4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(d.name)
                            Text("Status: ${d.status}")
                            d.pairingCode?.let { Text("Pairing code: $it") }
                            Text("Device ID: ${d.id}")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AttendanceList(list: List<Attendance>, message: String, reload: () -> Unit) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Attendance", style = MaterialTheme.typography.titleLarge)
                Button(onClick = reload) { Text("REFRESH") }
            }
            Text(message)
            LazyColumn {
                items(list) { a ->
                    Card(Modifier.fillMaxWidth().padding(4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${a.employee} • ${a.status}")
                            Text("${a.time} • ${a.device}")
                            if (!a.photo.isNullOrBlank()) Text(a.photo)
                        }
                    }
                }
            }
        }
    }

    private fun login(email: String, password: String, done: (Boolean, String) -> Unit) = executor.submit {
        try {
            val body = JSONObject()
                .put("email", email)
                .put("password", password)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val r = http.newCall(Request.Builder().url("$baseUrl/api/v1/admin/login").post(body).build()).execute()
            if (!r.isSuccessful) throw Exception("Login failed (${r.code})")
            token = JSONObject(r.body!!.string()).getString("token")
            getSharedPreferences("admin", 0).edit().putString("url", baseUrl).putString("token", token).apply()
            runOnUiThread { done(true, "Logged in") }
        } catch (e: Exception) {
            runOnUiThread { done(false, e.message ?: "Login failed") }
        }
    }

    private fun loadEmployees(done: (List<Employee>) -> Unit) = executor.submit {
        try {
            val a = JSONObject(call("/api/v1/admin/employees")).getJSONArray("items")
            val out = List(a.length()) { i ->
                val e = a.getJSONObject(i)
                Employee(e.getString("id"), e.getString("employee_code"), e.getString("name"), e.optString("department"))
            }
            runOnUiThread { done(out) }
        } catch (_: Exception) { }
    }

    private fun loadDevices(done: (List<Device>) -> Unit) = executor.submit {
        try {
            val a = JSONObject(call("/api/v1/admin/devices")).getJSONArray("items")
            val out = List(a.length()) { i ->
                val e = a.getJSONObject(i)
                Device(e.getString("id"), e.getString("name"), e.getString("status"), if (e.isNull("pairing_code")) null else e.getString("pairing_code"))
            }
            runOnUiThread { done(out) }
        } catch (_: Exception) { }
    }

    private fun loadAttendance(done: (List<Attendance>) -> Unit) = executor.submit {
        try {
            val a = JSONObject(call("/api/v1/admin/attendance")).getJSONArray("items")
            val out = List(a.length()) { i ->
                val e = a.getJSONObject(i)
                Attendance(e.getString("employee_name"), e.getString("status"), e.getString("captured_at"), e.getString("device_name"), e.optString("photo_secure_url"))
            }
            runOnUiThread { done(out) }
        } catch (_: Exception) { }
    }

    private fun call(path: String): String {
        val r = http.newCall(Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token").get().build()).execute()
        if (!r.isSuccessful) throw Exception("Request failed ${r.code}")
        return r.body!!.string()
    }

    private fun createEmployee(code: String, name: String, dept: String, uri: Uri, done: () -> Unit) {
        executor.submit {
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    ?: throw Exception("Invalid image")
                val detector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .build()
                )
                detector.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { faces ->
                        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        val template = face?.let { template(bitmap, it.boundingBox) } ?: return@addOnSuccessListener
                        executor.submit {
                            try {
                                val arr = JSONArray()
                                template.forEach { arr.put(it.toDouble()) }
                                val body = JSONObject()
                                    .put("employeeCode", code)
                                    .put("name", name)
                                    .put("department", dept)
                                    .put("faceTemplate", arr)
                                    .toString()
                                    .toRequestBody("application/json".toMediaType())
                                val r = http.newCall(
                                    Request.Builder()
                                        .url("$baseUrl/api/v1/admin/employees")
                                        .header("Authorization", "Bearer $token")
                                        .post(body)
                                        .build()
                                ).execute()
                                if (!r.isSuccessful) throw Exception("Create failed ${r.code}")
                                runOnUiThread { done() }
                            } catch (_: Exception) { }
                        }
                    }
                    .addOnFailureListener { }
            } catch (_: Exception) { }
        }
    }

    private fun template(src: Bitmap, box: Rect): FloatArray? {
        val p = (box.width() * .18f).toInt()
        val l = (box.left - p).coerceAtLeast(0)
        val t = (box.top - p).coerceAtLeast(0)
        val r = (box.right + p).coerceAtMost(src.width)
        val b = (box.bottom + p).coerceAtMost(src.height)
        if (r <= l || b <= t) return null
        val s = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, l, t, r - l, b - t), 32, 32, true)
        val a = FloatArray(1024)
        var k = 0
        var m = 0f
        for (y in 0..31) for (x in 0..31) {
            val c = s.getPixel(x, y)
            val v = (.299f * ((c shr 16) and 255) + .587f * ((c shr 8) and 255) + .114f * (c and 255)) / 255f
            a[k++] = v
            m += v
        }
        m /= 1024f
        var n = 0f
        for (i in a.indices) {
            a[i] -= m
            n += a[i] * a[i]
        }
        n = sqrt(n).coerceAtLeast(.0001f)
        for (i in a.indices) a[i] /= n
        return a
    }
}
