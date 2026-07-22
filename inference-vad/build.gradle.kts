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
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
