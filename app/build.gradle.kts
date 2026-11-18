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
        // one fixed key for every build, so a new build installs over the old one and keeps its settings.
        // The key lives in GitHub secrets (the workflow decodes it and sets these variables); without it the usual debug key is used.
        val ksFile = System.getenv("SIGNING_KEYSTORE_FILE")
        if (ksFile != null && file(ksFile).exists()) {
            getByName("debug") {
                storeFile = file(ksFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
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
