plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.abdo.barq"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.abdo.barq"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // One fixed key, so a new build installs over the old one without uninstalling.
    signingConfigs {
        create("barq") {
            storeFile = file("barq.keystore")
            storePassword = "android"
            keyAlias = "barq"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("barq")
        }
        debug {
            signingConfig = signingConfigs.getByName("barq")
        }
    }

    // One APK per processor type (the video tools are big): arm64 for most phones, armeabi-v7a for older boxes.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        // yt-dlp, Python and FFmpeg are run as programs: they must be unpacked on install.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/*.kotlin_module",
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // yt-dlp + Python + FFmpeg + QuickJS for videos and playlists
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // libtorrent for torrents and magnet links
    val lt = "2.1.0-38"
    implementation("org.libtorrent4j:libtorrent4j:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt")
}
