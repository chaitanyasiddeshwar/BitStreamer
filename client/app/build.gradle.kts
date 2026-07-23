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
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.media3:media3-extractor:$media3") // DtsUtil for core extraction
    implementation("com.suyashbelekar:exoplayerhdrutils:0.3.0")
    testImplementation("junit:junit:4.13.2")
}

// After an assemble, copy the built APK to the shared repo-root dist/ folder as
// client.apk, where the server serves it at /client.apk for the Fire TV
// "Downloader" app. Runs automatically for both debug and release builds
// (Android Studio's Run also triggers assembleDebug).
val distDir = rootProject.layout.projectDirectory.dir("../dist")
listOf("Release", "Debug").forEach { variant ->
    val copyTask = tasks.register<Copy>("copy${variant}ApkToDist") {
        from(layout.buildDirectory.dir("outputs/apk/${variant.lowercase()}")) {
            include("*.apk")
        }
        into(distDir)
        rename { "client.apk" }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    tasks.matching { it.name == "assemble$variant" }.configureEach {
        finalizedBy(copyTask)
    }
}
