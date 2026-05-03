plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.autonion.automationcompanion"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.autonion.automationcompanion"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 👇 REQUIRED for native build
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    // 👇 REQUIRED to enable CMake
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Use Java 17 for Compose
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    androidResources {
        noCompress += listOf("tflite", "onnx")
    }
}

dependencies {
    implementation(libs.java.websocket)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.runtime)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material3)

    // Compose UI libraries (no explicit versions because BOM or explicit coordinates below)
    implementation(libs.androidx.ui) // adjust if you have BOM or catalog entry
    //implementation("androidx.compose.material3:material3:1.4.0")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.litert.support.api)

    // Optional: helpful tooling for preview and debug
    debugImplementation(libs.androidx.ui.tooling)

    //Location Based Trigger
    implementation(libs.play.services.location)
    implementation(libs.androidx.biometric)
    implementation(libs.osmdroid.android)
    implementation(libs.androidx.preference)
    implementation(libs.osmdroid.mapsforge)
    implementation(libs.mapsforge.map.android)
    implementation(libs.mapsforge.map.reader)
    implementation(libs.mapsforge.themes)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    //kapt(libs.androidx.room.compiler)
    ksp(libs.androidx.room.compiler)

    implementation(libs.compose.icons)

    //Gesture Recording Playback
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.savedstate)



    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // TensorFlow Lite
    // LiteRT (formerly TensorFlow Lite)
    implementation(libs.tensorflow.lite)
    implementation(libs.litert.gpu.api)
    implementation(libs.litert.gpu)
    implementation(libs.litert.support.api)
    
    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.13.2")
    implementation(libs.okhttp)

    // ML Kit Text Recognition (OCR)
    implementation(libs.mlkit.text.recognition)

    // ONNX Runtime (for MiniLM sentence embedding)
    implementation(libs.onnxruntime.android)

    // MediaPipe GenAI (for On-Device LLM / Gemma)
    implementation(libs.mediapipe.tasks.genai)
    
    // Retrofit (for Local Server LLM integration later)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    
    // Langchain4j (for chat memory and Ollama integration)
    // Exclude opennlp-tools: uses MethodHandle.invoke (requires API 26+), not needed for our use case
    implementation(libs.langchain4j) {
        exclude(group = "org.apache.opennlp")
    }
    implementation(libs.dev.langchain4j.ollama) {
        exclude(group = "org.apache.opennlp")
    }
    implementation(libs.langchain4j.open.ai) {
        exclude(group = "org.apache.opennlp")
    }

    // Security — Encrypted SharedPreferences for API key storage
    implementation(libs.androidx.security.crypto)
}
