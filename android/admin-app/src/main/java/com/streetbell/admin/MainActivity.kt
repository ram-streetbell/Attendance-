package com.streetbell.admin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
private data class Device(val id: String, val name: String, val status: String, val pairingCode: String?, val lastSeen: String?)
private data class Attendance(val employee: String, val status: String, val time: String, val device: String, val photo: String?)

class MainActivity : ComponentActivity() {
    private val http = OkHttpClient()
    private val executor = Executors.newCachedThreadPool()
    private val baseUrl = "https://attendance-backend-rkny.onrender.com"
    private var token: String? = null
    private var selectedPhoto: Uri? = null
    private var photoChanged: (() -> Unit)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> selectedPhoto = uri; photoChanged?.invoke() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        token = getSharedPreferences("admin", 0).getString("token", null)
        setContent { App() }
    }

    @Composable private fun App() {
        var logged by remember { mutableStateOf(token != null) }
        var email by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        if (!logged) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("ATTEND", style = MaterialTheme.typography.displaySmall)
                Text("Admin Console", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp))
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { login(email, pass) { ok, text -> msg = text; if (ok) logged = true } }, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp)) { Text("LOGIN") }
                if (msg.isNotBlank()) Text(msg, Modifier.padding(8.dp))
            }
        } else AdminHome()
    }

    @Composable private fun AdminHome() {
        var tab by remember { mutableStateOf("Dashboard") }
        var employees by remember { mutableStateOf(emptyList<Employee>()) }
        var devices by remember { mutableStateOf(emptyList<Device>()) }
        var events by remember { mutableStateOf(emptyList<Attendance>()) }
        var message by remember { mutableStateOf("Loading…") }
        var refresh by remember { mutableStateOf(0) }
        LaunchedEffect(refresh) {
            loadEmployees { employees = it }
            loadDevices { devices = it }
            loadAttendance { events = it; message = "Synced" }
        }
        Row(Modifier.fillMaxSize().padding(16.dp)) {
            NavigationRail {
                listOf("Dashboard", "Employees", "Devices", "Attendance").forEach { item ->
                    NavigationRailItem(selected = tab == item, onClick = { tab = item }, icon = { Text(item.first().toString()) }, label = { Text(item) })
                }
            }
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("ATTEND", style = MaterialTheme.typography.headlineLarge)
                Text("Admin Console", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                when (tab) {
                    "Dashboard" -> Dashboard(employees, devices, events)
                    "Employees" -> Employees(employees, message) { refresh++ }
                    "Devices" -> Devices(devices, employees, message) { refresh++ }
                    "Attendance" -> AttendanceList(events, message) { refresh++ }
                }
            }
        }
    }

    @Composable private fun Dashboard(e: List<Employee>, d: List<Device>, a: List<Attendance>) {
        Text("Overview", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Employees", e.size.toString())
            StatCard("Online devices", d.count { it.status == "online" }.toString())
            StatCard("Attendance events", a.size.toString())
        }
        Spacer(Modifier.height(24.dp))
        Text("Quick actions", style = MaterialTheme.typography.titleLarge)
        Text("Register staff, create kiosks, assign staff to kiosks, and review attendance photos.")
    }

    @Composable private fun StatCard(title: String, value: String) {
        Card(Modifier.widthIn(min = 110.dp)) { Column(Modifier.padding(18.dp)) { Text(title); Text(value, style = MaterialTheme.typography.headlineMedium) } }
    }

    @Composable private fun Employees(list: List<Employee>, message: String, reload: () -> Unit) {
        var open by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Employees", style = MaterialTheme.typography.headlineSmall); Button(onClick = { open = true }) { Text("+ ADD EMPLOYEE") } }
            Text("${list.size} registered employees")
            Spacer(Modifier.height(8.dp))
            LazyColumn { items(list) { e -> Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(14.dp)) { Text(e.name, style = MaterialTheme.typography.titleMedium); Text(e.code + if (e.department.isNullOrBlank()) "" else " • ${e.department}") } } } }
            Text(message)
        }
        if (open) AddEmployeeDialog({ open = false }, reload)
    }

    @Composable private fun AddEmployeeDialog(close: () -> Unit, reload: () -> Unit) {
        var code by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var dept by remember { mutableStateOf("") }; var status by remember { mutableStateOf("") }; var selected by remember { mutableStateOf<Uri?>(null) }
        photoChanged = { selected = selectedPhoto }
        AlertDialog(onDismissRequest = close, title = { Text("Add employee") }, text = {
            Column {
                OutlinedTextField(code, { code = it }, label = { Text("Employee code") }, singleLine = true)
                OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true)
                OutlinedTextField(dept, { dept = it }, label = { Text("Department (optional)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { picker.launch("image/*") }) { Text(if (selected == null) "Choose face photo" else "Face photo selected") }
                if (status.isNotBlank()) Text(status)
            }
        }, confirmButton = { Button(enabled = code.isNotBlank() && name.isNotBlank() && selected != null, onClick = { status = "Creating…"; createEmployee(code.trim(), name.trim(), dept.trim(), selected!!) { status = "Employee added"; selectedPhoto = null; reload(); close() } }) { Text("ADD") } }, dismissButton = { TextButton(onClick = close) { Text("CANCEL") } })
    }

    @Composable private fun Devices(list: List<Device>, employees: List<Employee>, message: String, reload: () -> Unit) {
        var createOpen by remember { mutableStateOf(false) }; var assignDevice by remember { mutableStateOf<Device?>(null) }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Devices", style = MaterialTheme.typography.headlineSmall); Button(onClick = { createOpen = true }) { Text("+ ADD DEVICE") } }
            Text("Create a kiosk, then use its pairing code in the Employee Attendance app.")
            Spacer(Modifier.height(8.dp))
            LazyColumn { items(list) { d -> Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(14.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(d.name, style = MaterialTheme.typography.titleMedium); Text("${d.status.uppercase()} • ${d.id.take(8)}") }; Button(onClick = { assignDevice = d }) { Text("ASSIGN EMPLOYEES") } }; if (!d.pairingCode.isNullOrBlank()) { Text("PAIRING CODE", style = MaterialTheme.typography.labelMedium); Text(d.pairingCode, style = MaterialTheme.typography.headlineSmall) }; if (!d.lastSeen.isNullOrBlank()) Text("Last seen: ${d.lastSeen}") } } } }
            Text(message)
        }
        if (createOpen) CreateDeviceDialog({ createOpen = false }, reload)
        assignDevice?.let { device -> AssignDialog(device, employees, { assignDevice = null }, reload) }
    }

    @Composable private fun CreateDeviceDialog(close: () -> Unit, reload: () -> Unit) {
        var name by remember { mutableStateOf("") }; var result by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = close, title = { Text("Add attendance device") }, text = { Column { Text("Give this kiosk a name."); OutlinedTextField(name, { name = it }, label = { Text("Device name") }); if (result.isNotBlank()) Text(result) } }, confirmButton = { Button(enabled = name.isNotBlank(), onClick = { result = "Creating…"; createDevice(name.trim()) { code -> result = "Pairing code: $code"; reload() } }) { Text("CREATE") } }, dismissButton = { TextButton(onClick = close) { Text("CLOSE") } })
    }

    @Composable private fun AssignDialog(device: Device, employees: List<Employee>, close: () -> Unit, reload: () -> Unit) {
        var selected by remember { mutableStateOf(setOf<String>()) }; var status by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = close, title = { Text("Assign to ${device.name}") }, text = { Column { Text("Select employees allowed to use this kiosk."); LazyColumn(Modifier.heightIn(max = 360.dp)) { items(employees) { e -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = selected.contains(e.id), onCheckedChange = { checked -> selected = if (checked) selected + e.id else selected - e.id }); Text("${e.name} • ${e.code}") } } }; if (status.isNotBlank()) Text(status) } }, confirmButton = { Button(onClick = { status = "Saving…"; assign(device.id, selected.toList()) { status = "Saved"; reload(); close() } }) { Text("SAVE ASSIGNMENT") } }, dismissButton = { TextButton(onClick = close) { Text("CANCEL") } })
    }

    @Composable private fun AttendanceList(list: List<Attendance>, message: String, reload: () -> Unit) {
        Column(Modifier.fillMaxSize()) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Attendance", style = MaterialTheme.typography.headlineSmall); Button(onClick = reload) { Text("REFRESH") } }; Spacer(Modifier.height(8.dp)); LazyColumn { items(list) { a -> Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(14.dp)) { Text("${a.employee} • ${a.status}", style = MaterialTheme.typography.titleMedium); Text("${a.time} • ${a.device}"); if (!a.photo.isNullOrBlank()) Text("Photo: ${a.photo}") } } } }; Text(message) }
    }

    private fun login(email: String, password: String, done: (Boolean, String) -> Unit) = executor.submit {
        try { val body = JSONObject().put("email", email).put("password", password).toString().toRequestBody("application/json".toMediaType()); http.newCall(Request.Builder().url("$baseUrl/api/v1/admin/login").post(body).build()).execute().use { r -> if (!r.isSuccessful) error("Login failed (${r.code})"); token = JSONObject(r.body?.string() ?: error("Empty response")).getString("token") }; getSharedPreferences("admin", 0).edit().putString("token", token).apply(); runOnUiThread { done(true, "Logged in") } } catch (e: Exception) { runOnUiThread { done(false, e.message ?: "Login failed") } }
    }

    private fun loadEmployees(done: (List<Employee>) -> Unit) = executor.submit { try { val a = JSONObject(call("/api/v1/admin/employees")).getJSONArray("items"); val out = List(a.length()) { i -> val e = a.getJSONObject(i); Employee(e.getString("id"), e.getString("employee_code"), e.getString("name"), e.optString("department").ifBlank { null }) }; runOnUiThread { done(out) } } catch (_: Exception) {} }
    private fun loadDevices(done: (List<Device>) -> Unit) = executor.submit { try { val a = JSONObject(call("/api/v1/admin/devices")).getJSONArray("items"); val out = List(a.length()) { i -> val e = a.getJSONObject(i); Device(e.getString("id"), e.getString("name"), e.getString("status"), if (e.isNull("pairing_code")) null else e.getString("pairing_code"), e.optString("last_seen_at").ifBlank { null }) }; runOnUiThread { done(out) } } catch (_: Exception) {} }
    private fun loadAttendance(done: (List<Attendance>) -> Unit) = executor.submit { try { val a = JSONObject(call("/api/v1/admin/attendance")).getJSONArray("items"); val out = List(a.length()) { i -> val e = a.getJSONObject(i); Attendance(e.getString("employee_name"), e.getString("status"), e.getString("captured_at"), e.getString("device_name"), e.optString("photo_secure_url").ifBlank { null }) }; runOnUiThread { done(out) } } catch (_: Exception) {} }

    private fun call(path: String): String { val r = http.newCall(Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $token").get().build()).execute(); if (!r.isSuccessful) throw Exception("Request failed ${r.code}"); return r.body?.string() ?: "{}" }

    private fun createDevice(name: String, done: (String) -> Unit) = executor.submit { try { val body = JSONObject().put("name", name).toString().toRequestBody("application/json".toMediaType()); http.newCall(Request.Builder().url("$baseUrl/api/v1/admin/devices").header("Authorization", "Bearer $token").post(body).build()).execute().use { r -> if (!r.isSuccessful) error("Create failed ${r.code}"); val d = JSONObject(r.body?.string() ?: error("Empty response")).getJSONObject("device"); runOnUiThread { done(d.getString("pairing_code")) } } } catch (e: Exception) { runOnUiThread { done(e.message ?: "Create failed") } } }

    private fun assign(deviceId: String, ids: List<String>, done: () -> Unit) = executor.submit { try { val arr = JSONArray(); ids.forEach { arr.put(it) }; val body = JSONObject().put("employeeIds", arr).toString().toRequestBody("application/json".toMediaType()); http.newCall(Request.Builder().url("$baseUrl/api/v1/admin/devices/$deviceId/assign").header("Authorization", "Bearer $token").post(body).build()).execute().use { r -> if (!r.isSuccessful) error("Assign failed ${r.code}") }; runOnUiThread(done) } catch (_: Exception) {} }

    private fun createEmployee(code: String, name: String, dept: String, uri: Uri, done: () -> Unit) {
        executor.submit {
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: error("Invalid image")
                val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
                detector.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { faces ->
                    val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return@addOnSuccessListener
                    val t = template(bitmap, face.boundingBox) ?: return@addOnSuccessListener
                    executor.submit {
                        try {
                            val arr = JSONArray(); t.forEach { arr.put(it.toDouble()) }
                            val body = JSONObject().put("employeeCode", code).put("name", name).put("department", dept).put("faceTemplate", arr).toString().toRequestBody("application/json".toMediaType())
                            http.newCall(Request.Builder().url("$baseUrl/api/v1/admin/employees").header("Authorization", "Bearer $token").post(body).build()).execute().use { r -> if (!r.isSuccessful) error("Create failed ${r.code}") }
                            runOnUiThread(done)
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun template(src: Bitmap, box: Rect): FloatArray? {
        val p = (box.width() * .18f).toInt(); val l = (box.left - p).coerceAtLeast(0); val t = (box.top - p).coerceAtLeast(0); val r = (box.right + p).coerceAtMost(src.width); val b = (box.bottom + p).coerceAtMost(src.height)
        if (r <= l || b <= t) return null
        val s = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, l, t, r - l, b - t), 32, 32, true); val a = FloatArray(1024); var k = 0; var m = 0f
        for (y in 0..31) for (x in 0..31) { val c = s.getPixel(x, y); val v = (.299f * ((c shr 16) and 255) + .587f * ((c shr 8) and 255) + .114f * (c and 255)) / 255f; a[k++] = v; m += v }
        m /= 1024f; var n = 0f; for (i in a.indices) { a[i] -= m; n += a[i] * a[i] }; n = sqrt(n).coerceAtLeast(.0001f); for (i in a.indices) a[i] /= n; return a
    }
}
