import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.hark.shiguang"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    defaultConfig {
        applicationId = "com.hark.shiguang"
        minSdk = 26
        targetSdk = 34
        versionCode = 30
        versionName = "1.3.4"
        // GitHub Actions passes -PciRun=<run number>; the in-app updater compares it when versionCode is equal
        buildConfigField("int", "CI_RUN", ((project.findProperty("ciRun") as String?)?.toIntOrNull() ?: 0).toString())
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
            // 1.1.0: R8 代码压缩 + 资源压缩（规则见 proguard-rules.pro；CI 每次 assembleRelease 都会验证）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // 纯逻辑单元测试（app/src/test）：android.jar 里的桩方法返回默认值，不抛 "Stub!"
    testOptions { unitTests.isReturnDefaultValues = true }
    // lint 先只出报告不阻断（CI 里上传 lint 报告），等存量问题清完再改成阻断
    lint { abortOnError = false; checkReleaseBuilds = false }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs {
            pickFirsts += "**/libc++_shared.so" // several native deps (FFmpeg decoder, TFLite) may ship it
            useLegacyPackaging = true // compress .so in the APK (smaller download; was needed for libvlc.so)
        }
    }
}
// Room 表结构导出到 app/schemas（进 git，便于以后写迁移）
ksp { arg("room.schemaLocation", "$projectDir/schemas"); arg("room.incremental", "true") }
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
    // 用户 2026-10-23 反馈（清晰度）：飞牛影视服务器转码输出 HLS（play_link …/preset.m3u8），版本必须与其它 media3 一致
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    // 1.0.3 #4: 音乐播放器（后台播放、通知栏/锁屏/蓝牙控制），版本必须与其它 media3 一致
    implementation("androidx.media3:media3-session:1.3.1")
    // 1.0.9: FFmpeg audio decoders (DTS / TrueHD / AC3 / EAC3 …) for media3 1.3.1, built by Jellyfin
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+2")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    // 1.1.0: libVLC removed (≈45 MB of libvlc.so per ABI). Formats media3 can't open → 「用其他播放器打开」 (ExternalPlayer).
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("com.google.mlkit:face-detection:16.1.7")
    // 1.0.1 #8: on-device photo labels, BUNDLED model (works offline, no Google Play services). Not play-services-mlkit-image-labeling.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    // 1.1.0 图中文字搜索：ML Kit 文字识别 v2 中文版，BUNDLED（模型打包进 APK，离线可用，不依赖 Google Play 服务）。
    // 不要换成 play-services-mlkit-text-recognition-chinese（那个要通过 Play 下载模型，国内手机用不了）。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    // 1.1.0 阶段三：照片索引改 Room（增量写入、可续扫）+ Paging 3（大列表按页读）
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.paging:paging-runtime-ktx:3.3.0")
    implementation("androidx.paging:paging-compose:3.3.0")
    testImplementation("junit:junit:4.13.2")
    // JVM 单元测试里 android.jar 的 XmlPullParser 只是桩，用 kxml2 提供真实实现（WebDAV PROPFIND 解析测试）
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // JVM 单元测试里 android.jar 的 org.json 只是桩，用真实实现（飞牛影视转码档位解析测试）
    testImplementation("org.json:json:20231013")
}
