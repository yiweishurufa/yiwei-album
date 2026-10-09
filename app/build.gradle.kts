import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.hark.shiguang"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    defaultConfig {
        applicationId = "com.hark.shiguang"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "1.0.3"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    // Signing secrets live in keystore.properties (git-ignored): storeFile/storePassword/keyAlias/keyPassword
    val ksFile = rootProject.file("keystore.properties")
    val ks = Properties().also { p -> if (ksFile.exists()) ksFile.inputStream().use { p.load(it) } }
    signingConfigs {
        if (ks.getProperty("storeFile") != null) create("rel") {
            storeFile = rootProject.file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("rel")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // required by the Jellyfin FFmpeg decoder AAR
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "tflite" }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs {
            pickFirsts += "**/libc++_shared.so" // libVLC and other native deps may both ship it
            useLegacyPackaging = true // 1.0.10: compress .so in the APK (libvlc.so is ~45 MB uncompressed per ABI)
        }
    }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    // 1.0.3 #4: 音乐播放器（后台播放、通知栏/锁屏/蓝牙控制），版本必须与其它 media3 一致
    implementation("androidx.media3:media3-session:1.3.1")
    // 1.0.9: FFmpeg audio decoders (DTS / TrueHD / AC3 / EAC3 …) for media3 1.3.1, built by Jellyfin
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+2")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    // 1.0.10: libVLC fallback player for rmvb/rm/wmv/asf (+ files media3 can't open). 3.7.2 = newest 3.x whose POM does not
    // pull kotlin-stdlib 2.2 (3.7.3+ do; this project compiles with Kotlin 1.9.24). Adds ~2 ABIs of libvlc.so.
    implementation("org.videolan.android:libvlc-all:3.7.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    // 1.0.1 #8: on-device photo labels, BUNDLED model (works offline, no Google Play services). Not play-services-mlkit-image-labeling.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
