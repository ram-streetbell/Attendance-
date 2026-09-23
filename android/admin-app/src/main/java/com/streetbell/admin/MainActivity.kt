package com.streetbell.admin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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

private data class Employee(val id:String,val code:String,val name:String,val department:String?,val enrolled:Boolean)
private data class Attendance(val employee:String,val code:String,val status:String,val time:String,val device:String,val photo:String?)
private data class Device(val id:String,val name:String,val status:String,val code:String?,val assigned:Int)

class MainActivity:ComponentActivity(){
 private val http=OkHttpClient();private val executor=Executors.newCachedThreadPool();private val baseUrl="https://attendance-backend-rkny.onrender.com";private var token:String?=null;private var selectedPhoto:Uri?=null
 private val picker=registerForActivityResult(ActivityResultContracts.GetContent()){selectedPhoto=it}
 override fun onCreate(b:Bundle?){super.onCreate(b);token=getSharedPreferences("admin",0).getString("token",null);setContent{App()}}
 @Composable private fun App(){var logged by remember{mutableStateOf(token!=null)};if(!logged)Login{logged=true}else Dashboard{getSharedPreferences("admin",0).edit().remove("token").apply();token=null;logged=false}}
 @Composable private fun Login(ok:()->Unit){var email by remember{mutableStateOf("")};var pass by remember{mutableStateOf("")};var msg by remember{mutableStateOf("")};Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("ATTEND ADMIN",fontSize=30.sp);Spacer(Modifier.height(20.dp));OutlinedTextField(email,{email=it},label={Text("Email")},modifier=Modifier.fillMaxWidth());OutlinedTextField(pass,{pass=it},label={Text("Password")},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(12.dp));Button(onClick={login(email,pass){s,m->msg=m;if(s)ok()}},modifier=Modifier.fillMaxWidth()){Text("LOGIN")};Text(msg)}}
 @Composable private fun Dashboard(logout:()->Unit){var tab by remember{mutableStateOf(0)};var refresh by remember{mutableStateOf(0)};var employees by remember{mutableStateOf(emptyList<Employee>())};var attendance by remember{mutableStateOf(emptyList<Attendance>())};var devices by remember{mutableStateOf(emptyList<Device>())};LaunchedEffect(refresh){loadEmployees{employees=it};loadAttendance{attendance=it};loadDevices{devices=it}};Column(Modifier.fillMaxSize().padding(16.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("ATTEND ADMIN",fontSize=25.sp);Row{TextButton({refresh++}){Text("SYNC")};TextButton(logout){Text("LOG OUT")}}};Spacer(Modifier.height(10.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("STAFF","ATTENDANCE","DEVICES").forEachIndexed{i,n->Button(onClick={tab=i},modifier=Modifier.weight(1f)){Text(n)}}};Spacer(Modifier.height(12.dp));when(tab){0->Staff(employees){refresh++};1->AttendanceList(attendance);2->DeviceList(devices)}}}
 @Composable private fun Staff(list:List<Employee>,reload:()->Unit){var show by remember{mutableStateOf(false)};Column{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Staff",fontSize=24.sp);Button({show=true}){Text("+ ADD")}};Text("${list.size} employees");LazyColumn{items(list){e->Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Column(Modifier.padding(14.dp)){Text(e.name,fontSize=18.sp);Text("${e.code} • ${e.department.orEmpty()}");Text(if(e.enrolled)"Face enrolled (FaceNet)"else"Face not enrolled");if(!e.enrolled)Button({show=true}){Text("ENROLL FACE")}}}}};if(show)EnrollDialog(null,close={show=false},reload=reload)}}
 @Composable private fun AttendanceList(list:List<Attendance>){Column{Text("Attendance",fontSize=24.sp);LazyColumn{items(list){a->Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Column(Modifier.padding(12.dp)){Text(a.employee,fontSize=17.sp);Text("${a.code} • ${a.status} • ${a.time}");Text(a.device);if(!a.photo.isNullOrBlank())Text("PHOTO ✓")}}}}}}
 @Composable private fun DeviceList(list:List<Device>){Column{Text("Devices",fontSize=24.sp);LazyColumn{items(list){d->Card(Modifier.fillMaxWidth().padding(vertical=4.dp)){Column(Modifier.padding(12.dp)){Text(d.name,fontSize=18.sp);Text("${d.status.uppercase()} • ${d.assigned} assigned");d.code?.let{Text("PAIRING CODE: $it",fontSize=20.sp)}}}}}}}
 @Composable private fun EnrollDialog(existing:Employee?,close:()->Unit,reload:()->Unit){var status by remember{mutableStateOf("")};var photo by remember{mutableStateOf<Uri?>(null)};AlertDialog(onDismissRequest=close,title={Text(if(existing==null)"Add staff"else"Enroll face")},text={Column{if(existing==null){Text("Create the employee in the admin system first, then enroll the face.");Text("Use the backend employee API or existing employee record.")};OutlinedButton(onClick={picker.launch("image/*")}){Text(if(photo==null)"Select face photo"else"Photo selected")};Text(status)}},confirmButton={Button(onClick={if(photo!=null){status="Processing…";if(existing!=null)enrollFace(existing.id,photo!!){ok,msg->status=msg;if(ok){reload();close()}}else status="Select an existing employee to enroll"}}){Text("ENROLL")}},dismissButton={TextButton(close){Text("CANCEL")}})}
 private fun login(email:String,password:String,done:(Boolean,String)->Unit)=executor.execute{try{val body=JSONObject().put("email",email).put("password",password).toString().toRequestBody("application/json".toMediaType());http.newCall(Request.Builder().url("$baseUrl/api/v1/admin/login").post(body).build()).execute().use{r->if(!r.isSuccessful)error("Login failed (${r.code})");token=JSONObject(r.body?.string()?:("{}" )).getString("token")};getSharedPreferences("admin",0).edit().putString("token",token).apply();runOnUiThread{done(true,"Logged in")}}catch(e:Exception){runOnUiThread{done(false,e.message?:"Login failed")}}}
 private fun auth(path:String):Request.Builder= Request.Builder().url(baseUrl+path).header("Authorization","Bearer ${token?:""}")
 private fun loadEmployees(done:(List<Employee>)->Unit)=executor.execute{try{val a=JSONObject(auth("/api/v1/admin/employees").get().build().let{http.newCall(it).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");r.body?.string()?:"{}"}}).getJSONArray("items");val o=List(a.length()){i->val x=a.getJSONObject(i);Employee(x.getString("id"),x.getString("employee_code"),x.getString("name"),x.optString("department").ifBlank{null},x.optBoolean("face_enrolled"))};runOnUiThread{done(o)}}catch(_:Exception){runOnUiThread{done(emptyList())}}}
 private fun loadAttendance(done:(List<Attendance>)->Unit)=executor.execute{try{val s=auth("/api/v1/admin/attendance").get().build().let{http.newCall(it).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");r.body?.string()?:"{}"}};val a=JSONObject(s).getJSONArray("items");val o=List(a.length()){i->val x=a.getJSONObject(i);Attendance(x.optString("employee_name"),x.optString("employee_code"),x.optString("status"),x.optString("captured_at"),x.optString("device_name"),x.optString("photo_secure_url"))};runOnUiThread{done(o)}}catch(_:Exception){runOnUiThread{done(emptyList())}}}
 private fun loadDevices(done:(List<Device>)->Unit)=executor.execute{try{val s=auth("/api/v1/admin/devices").get().build().let{http.newCall(it).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");r.body?.string()?:"{}"}};val a=JSONObject(s).getJSONArray("items");val o=List(a.length()){i->val x=a.getJSONObject(i);Device(x.getString("id"),x.getString("name"),x.optString("status"),x.optString("pairing_code").ifBlank{null},x.optInt("assigned_employees"))};runOnUiThread{done(o)}}catch(_:Exception){runOnUiThread{done(emptyList())}}}
 private fun enrollFace(id:String,uri:Uri,done:(Boolean,String)->Unit)=executor.execute{try{val bmp=contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}?:error("Invalid image");val detector=FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build());detector.process(InputImage.fromBitmap(bmp,0)).addOnSuccessListener{faces->if(faces.size!=1){done(false,"Use a photo containing exactly one face");return@addOnSuccessListener};val emb=runCatching{FaceNetEmbedder(this@MainActivity).use{it.embed(crop(bmp,faces[0].boundingBox))}}.getOrElse{done(false,"FaceNet error: ${it.message}");return@addOnSuccessListener};val arr=JSONArray();emb.forEach{arr.put(it.toDouble())};val body=JSONObject().put("faceTemplate",arr).toString().toRequestBody("application/json".toMediaType());executor.execute{try{http.newCall(auth("/api/v1/admin/employees/$id/face").put(body).build()).execute().use{r->if(!r.isSuccessful)error("Update failed (${r.code})")};runOnUiThread{done(true,"Face enrolled")}}catch(e:Exception){runOnUiThread{done(false,e.message?:"Update failed")}}}}.addOnFailureListener{done(false,"Face detection failed: ${it.message}")}}catch(e:Exception){runOnUiThread{done(false,e.message?:"Photo error")}}}
 private fun crop(src:Bitmap,box:Rect):Bitmap{val p=(box.width().coerceAtLeast(box.height())*.35f).toInt();val l=(box.left-p).coerceIn(0,src.width-1);val t=(box.top-p).coerceIn(0,src.height-1);val r=(box.right+p).coerceIn(l+1,src.width);val b=(box.bottom+p).coerceIn(t+1,src.height);return Bitmap.createBitmap(src,l,t,r-l,b-t)}
}