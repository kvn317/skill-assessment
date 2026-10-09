plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kvn317.gamemaps"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kvn317.gamemaps"
        minSdk = 26
        targetSdk = 35
        // Play rejects re-used version codes, so every CI build gets a new one.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 0)
        versionName = "2.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } // phones only; keeps MapLibre's native libs small
    }
    // Upload key for Google Play, supplied by CI from repository secrets (never committed: this repo is public).
    val keystore = System.getenv("UPLOAD_KEYSTORE")
    signingConfigs {
        create("upload") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = "upload"
                keyPassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
    implementation("org.maplibre.gl:android-sdk:11.5.2")
}
