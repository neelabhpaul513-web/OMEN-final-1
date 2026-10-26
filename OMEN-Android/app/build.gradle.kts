plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.neelabh.omen"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.neelabh.omen"
        minSdk = 26
        targetSdk = 34
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "9.$run"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("omen-debug.keystore")
            storePassword = "omen123"
            keyAlias = "omen"
            keyPassword = "omen123"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
