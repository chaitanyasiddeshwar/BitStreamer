plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bitstreamer.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bitstreamer.client"
        minSdk = 25 // Fire OS 6 (Fire TV Stick 4K 1st gen)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug signing so `assembleRelease` yields an installable APK
            // without keystore setup. Fine for personal sideloading; upgrades
            // require building on the same machine (same debug keystore).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val media3 = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
