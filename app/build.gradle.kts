plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.noteapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noteapp"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-spike"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // sherpa-onnx and onnxruntime-android both package the 1.27 core
            // runtime. Their JNI clients share that ABI in the final process.
            pickFirsts += "lib/*/libonnxruntime.so"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
            // G0 evidence is extracted from app-private storage with adb run-as.
            // Native dependencies still resolve to the optimized release variant.
            isDebuggable = true
            versionNameSuffix = "-benchmark"
        }
    }
}

dependencies {
    implementation(project(":feature-recording"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(project(":core-audio"))
    debugImplementation(project(":core-security"))
    androidTestImplementation(project(":core-security"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
