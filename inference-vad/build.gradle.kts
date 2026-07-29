plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.noteapp.vad"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.android.vad.webrtc)
    implementation(libs.android.vad.silero)
    // Keep Silero's Java/JNI binding on the same ONNX Runtime ABI used by
    // sherpa-onnx. The VAD library otherwise resolves its older 1.22 runtime.
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
