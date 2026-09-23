plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val faceNetUrl = "https://raw.githubusercontent.com/shubham0204/FaceRecognition_With_FaceNet_Android/master/app/src/main/assets/facenet.tflite"
val faceNetFile = layout.projectDirectory.dir("src/main/assets").file("facenet.tflite").asFile
val downloadFaceNet = tasks.register("downloadFaceNet") {
    outputs.file(faceNetFile)
    doLast {
        if (!faceNetFile.exists() || faceNetFile.length() < 1_000_000L) {
            faceNetFile.parentFile.mkdirs()
            java.net.URL(faceNetUrl).openStream().use { input ->
                faceNetFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        check(faceNetFile.length() > 1_000_000L) { "FaceNet model download failed" }
    }
}
tasks.named("preBuild") { dependsOn(downloadFaceNet) }

android {
    namespace = "com.streetbell.admin"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.streetbell.admin"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
