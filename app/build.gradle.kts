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
        // the GitHub build number (-PbuildNumber=...) is the version, so the app can tell when a newer release exists
        val bn = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0
        versionCode = if (bn > 0) bn else 2
        versionName = if (bn > 0) "build $bn" else "1.1"
    }
    signingConfigs {
        // one fixed key for every build, so a new build installs over the old one and keeps its settings
        getByName("debug") {
            storeFile = file("debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
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
