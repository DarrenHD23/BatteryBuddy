plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.fiskertemp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.fiskertemp"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // The CAN reader (libcanbridge.so) is a plain executable that the app starts itself, so it must
        // be a real file on disk in the app's native library folder, not mapped from inside the APK.
        jniLibs.useLegacyPackaging = true
    }
}
