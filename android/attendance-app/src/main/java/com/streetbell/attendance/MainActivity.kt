package com.streetbell.attendance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
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
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.sqrt

private data class Person(val id:String,val name:String,val template:FloatArray)
private data class CloudUpload(val publicId:String,val assetId:String,val secureUrl:String)

class MainActivity : ComponentActivity() {
    private val http = OkHttpClient()
    private val executor = Executors.newSingleThreadExecutor()
    private var capture: ImageCapture? = null
    private var people = listOf<Person>()
    private var baseUrl = "http://10.0.2.2:8080"
    private var deviceToken: String? = null
    private var deviceId: String? = null
    private var busy = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        setContent { App(cameraEnabled = granted) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("attendance", Context.MODE_PRIVATE)
        baseUrl = p.getString("baseUrl", baseUrl) ?: baseUrl
        deviceToken = p.getString("deviceToken", null)
        deviceId = p.getString("deviceId", null)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            setContent { App(true) } else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    @Composable private fun App(cameraEnabled:Boolean) {
        var paired by remember { mutableStateOf(deviceToken != null) }
        var code by remember { mutableStateOf("") }
        var url by remember { mutableStateOf(baseUrl) }
        var message by remember { mutableStateOf(if(paired) "Loading employees…" else "Pair this attendance device") }
        var face by remember { mutableStateOf<Person?>(null) }
        var detected by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        var score by remember { mutableStateOf(0f) }

        LaunchedEffect(paired) { if (paired) refreshManifest { message = it } }
        if (!paired) {
            Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                Text("Attendance Device",style=MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(18.dp)); OutlinedTextField(url,{url=it},label={Text("Backend URL")},singleLine=true)
                Spacer(Modifier.height(10.dp)); OutlinedTextField(code,{code=it.uppercase()},label={Text("Pairing code")},singleLine=true)
                Spacer(Modifier.height(14.dp)); Button(onClick={baseUrl=url.trimEnd('/'); pair(code){ok,msg->message=msg;if(ok)paired=true}}){Text("PAIR DEVICE")}
                Spacer(Modifier.height(12.dp)); Text(message)
            }
            return
        }
        Column(Modifier.fillMaxSize().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text("Attendance",style=MaterialTheme.typography.headlineMedium)
            Text(message,modifier=Modifier.padding(8.dp))
            if(cameraEnabled) AndroidView(factory={ctx->PreviewView(ctx).also{v->startCamera(v){d,p,s->detected=d;face=p;score=s}}},modifier=Modifier.fillMaxWidth().weight(1f))
            else Box(Modifier.weight(1f),contentAlignment=Alignment.Center){Text("Camera permission required")}
            val name = face?.name ?: if(detected) "Unknown employee" else "Stand in front of the camera"
            Text(if(face!=null) "$name  •  ${(score*100).toInt()}%" else name)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(enabled=face!=null&&!busy,onClick={submit("IN",face!!.id,score){message=it}},modifier=Modifier.weight(1f)){Text("IN")}
                Button(enabled=face!=null&&!busy,onClick={submit("OUT",face!!.id,score){message=it}},modifier=Modifier.weight(1f)){Text("OUT")}
            }
            status?.let{Text("Selected: $it",Modifier.padding(8.dp))}
        }
    }

    private fun pair(code:String, done:(Boolean,String)->Unit) { executor.execute { try {
        if(code.length<4){runOnUiThread{done(false,"Enter a valid pairing code")};return@execute}
        val body="{\"pairingCode\":\"${code.trim()}\",\"deviceName\":\"Attendance Kiosk\",\"appVersion\":\"1.0.0\"}".toRequestBody("application/json".toMediaType())
        val r=http.newCall(Request.Builder().url("$baseUrl/api/v1/device/pair").post(body).build()).execute()
        if(!r.isSuccessful) throw Exception("Pairing failed (${r.code})")
        val j=JSONObject(r.body!!.string()); deviceToken=j.getString("deviceToken");deviceId=j.getString("deviceId")
        getSharedPreferences("attendance",0).edit().putString("baseUrl",baseUrl).putString("deviceToken",deviceToken).putString("deviceId",deviceId).apply()
        runOnUiThread{done(true,"Device paired")}; refreshManifest{}
    }catch(e:Exception){runOnUiThread{done(false,e.message?:("Pairing failed"))}} } }

    private fun refreshManifest(done:(String)->Unit={}) { executor.execute { try {
        val r=http.newCall(auth(Request.Builder().url("$baseUrl/api/v1/device/manifest").get())).execute();if(!r.isSuccessful)throw Exception("Manifest error ${r.code}")
        val arr=JSONObject(r.body!!.string()).getJSONArray("employees");val list=mutableListOf<Person>()
        for(i in 0 until arr.length()){val e=arr.getJSONObject(i);val a=e.optJSONArray("faceTemplate")?:continue;val t=FloatArray(a.length()){a.getDouble(it).toFloat()};if(t.size==1024)list.add(Person(e.getString("id"),e.getString("name"),t))}
        people=list;runOnUiThread{done("Ready • ${people.size} employees")}
    }catch(e:Exception){runOnUiThread{done("Sync failed: ${e.message}")}} } }

    private fun startCamera(view:PreviewView,onMatch:(Boolean,Person?,Float)->Unit) {
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({try{
            val provider=future.get();val preview=Preview.Builder().build().also{it.surfaceProvider=view.surfaceProvider}
            capture=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val detector=FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
            val analysis=ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            var last=0L
            analysis.setAnalyzer(executor){proxy->
                val now=System.currentTimeMillis();if(now-last<350){proxy.close();return@setAnalyzer};last=now
                val media=proxy.image;if(media==null){proxy.close();return@setAnalyzer}
                detector.process(InputImage.fromMediaImage(media,proxy.imageInfo.rotationDegrees)).addOnSuccessListener{faces->
                    val f=faces.maxByOrNull{it.boundingBox.width()*it.boundingBox.height()}
                    if(f==null){onMatch(false,null,0f);return@addOnSuccessListener}
                    val bitmap=yuvToBitmap(proxy);val template=FaceTemplate.from(bitmap,f.boundingBox);var best:Person?=null;var bestScore=0f
                    if(template!=null) for(p in people){val s=FaceTemplate.similarity(template,p.template);if(s>bestScore){bestScore=s;best=p}}
                    val accepted=best!=null&&bestScore>=0.82f;onMatch(true,if(accepted)best else null,bestScore)
                }.addOnCompleteListener{proxy.close()}
            }
            provider.unbindAll();provider.bindToLifecycle(this,CameraSelector.DEFAULT_FRONT_CAMERA,preview,analysis,capture)
        }catch(_:Exception){}},ContextCompat.getMainExecutor(this))
    }

    private fun submit(kind:String,employeeId:String,score:Float,done:(String)->Unit){if(busy)return;busy=true;executor.execute{try{
        val file=File.createTempFile("attendance_",".jpg",cacheDir);val output=ImageCapture.OutputFileOptions.Builder(file).build()
        capture?.takePicture(output,executor,object:ImageCapture.OnImageSavedCallback{override fun onError(e:ImageCaptureException){busy=false;runOnUiThread{done("Photo capture failed")}}
            override fun onImageSaved(o:ImageCapture.OutputFileResults){try{
                val sig=http.newCall(auth(Request.Builder().url("$baseUrl/api/v1/cloudinary/signature").get())).execute();if(!sig.isSuccessful)throw Exception("Cloudinary authorization failed")
                val s=JSONObject(sig.body!!.string());val upload=MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file",file.name,file.asRequestBody("image/jpeg".toMediaType())).addFormDataPart("api_key",s.getString("apiKey")).addFormDataPart("timestamp",s.getString("timestamp")).addFormDataPart("signature",s.getString("signature")).addFormDataPart("folder",s.getString("folder")).build()
                val ur=http.newCall(Request.Builder().url("https://api.cloudinary.com/v1_1/${s.getString("cloudName")}/image/upload").post(upload).build()).execute();if(!ur.isSuccessful)throw Exception("Photo upload failed")
                val u=JSONObject(ur.body!!.string());val event=JSONObject().put("clientEventId",UUID.randomUUID().toString()).put("employeeId",employeeId).put("status",kind).put("capturedAt",java.time.Instant.now().toString()).put("faceMatchScore",score).put("cloudinaryPublicId",u.optString("public_id")).put("cloudinaryAssetId",u.optString("asset_id")).put("photoSecureUrl",u.optString("secure_url"))
                val ar=http.newCall(auth(Request.Builder().url("$baseUrl/api/v1/device/attendance").post(event.toString().toRequestBody("application/json".toMediaType())))).execute();if(!ar.isSuccessful)throw Exception(if(ar.code==409)"Same status was already recorded recently" else "Attendance submission failed (${ar.code})")
                file.delete();busy=false;runOnUiThread{done("Attendance recorded: $kind")}
            }catch(e:Exception){busy=false;runOnUiThread{done(e.message?:"Attendance failed")}} }} )
    }catch(e:Exception){busy=false;runOnUiThread{done(e.message?:"Attendance failed")}}}}

    private fun auth(b:Request.Builder)=b.header("x-device-token",deviceToken? : "")

    private object FaceTemplate {
        fun from(source:Bitmap,box:Rect):FloatArray? { val pad=(box.width()*0.18f).toInt();val l=(box.left-pad).coerceAtLeast(0);val t=(box.top-pad).coerceAtLeast(0);val r=(box.right+pad).coerceAtMost(source.width);val b=(box.bottom+pad).coerceAtMost(source.height);if(r<=l||b<=t)return null;val crop=Bitmap.createBitmap(source,l,t,r-l,b-t);val small=Bitmap.createScaledBitmap(crop,32,32,true);val a=FloatArray(1024);var k=0;var mean=0f
            for(y in 0 until 32)for(x in 0 until 32){val c=small.getPixel(x,y);val v=(0.299f*((c shr 16)and 255)+0.587f*((c shr 8)and 255)+0.114f*(c and 255))/255f;a[k++]=v;mean+=v};mean/=1024f;var norm=0f;for(i in a.indices){a[i]-=mean;norm+=a[i]*a[i]};norm=sqrt(norm).coerceAtLeast(0.0001f);for(i in a.indices)a[i]/=norm;return a }
        fun similarity(a:FloatArray,b:FloatArray):Float {var s=0f;for(i in 0 until minOf(a.size,b.size))s+=a[i]*b[i];return ((s+1f)/2f).coerceIn(0f,1f)}
    }

    private fun yuvToBitmap(proxy:ImageProxy):Bitmap {val y=proxy.planes[0].buffer;val u=proxy.planes[1].buffer;val v=proxy.planes[2].buffer;val yb=ByteArray(y.remaining());y.get(yb);val ub=ByteArray(u.remaining());u.get(ub);val vb=ByteArray(v.remaining());v.get(vb);val w=proxy.width;val h=proxy.height;val nv=ByteArray(w*h+w*h/2);System.arraycopy(yb,0,nv,0,minOf(yb.size,w*h));var p=w*h;val us=proxy.planes[1].pixelStride;val vs=proxy.planes[2].pixelStride;for(row in 0 until h/2){for(col in 0 until w/2){val ui=row*proxy.planes[1].rowStride+col*us;val vi=row*proxy.planes[2].rowStride+col*vs;if(p+1<nv.size){nv[p++]=vb[vi];nv[p++]=ub[ui]}}};val yuv=android.graphics.YuvImage(nv,android.graphics.ImageFormat.NV21,w,h,null);val out=ByteArrayOutputStream();yuv.compressToJpeg(Rect(0,0,w,h),75,out);return BitmapFactory.decodeByteArray(out.toByteArray(),0,out.size())}
}
