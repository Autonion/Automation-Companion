plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("com.google.devtools.ksp")
    kotlin("kapt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.automationcompanion"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.automationcompanion"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }



    buildFeatures {
        viewBinding = true
        compose = true
    }

    composeOptions {
        // Pick a Compose compiler version compatible with your Kotlin plugin.
        // If you run into compatibility issues, I can adjust this to match your Kotlin plugin.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    namespace = "com.example.automationcompanion"
}

dependencies {
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

    // Optional: helpful tooling for preview and debug
    debugImplementation(libs.androidx.ui.tooling)

    //Location Based Trigger
    implementation(libs.play.services.location)
    implementation(libs.androidx.biometric)
    implementation(libs.osmdroid.android)
    implementation(libs.androidx.preference)
    implementation(libs.osmdroid.android)
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
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.savedstate:savedstate:1.2.0")



    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
