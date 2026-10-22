# 一维相册 R8 规则（1.1.0 开启 isMinifyEnabled / isShrinkResources）
# 原则：库自带的 consumer rules 已覆盖大部分（OkHttp、Media3、Coil、ML Kit、Compose）；这里只补反射 / JNI / 无规则的库，
# 宁可多 keep 一点也不要线上闪退。出问题先看 CI 产物里的 mapping.txt。

# 崩溃日志里保留行号（诊断日志 / ApplicationExitInfo 用），文件名统一成 SourceFile
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# JNI：所有 native 方法及其类名不能改（FFmpeg 解码器、TFLite、ML Kit）
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Media3 + Jellyfin FFmpeg 扩展：渲染器通过反射按类名加载（DefaultRenderersFactory）
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-dontwarn androidx.media3.**

# TensorFlow Lite：Java 层被原生代码回调
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ML Kit（人脸 / 图像标注，打包模型版）：组件注册靠反射
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-dontwarn com.google.mlkit.**
# 1.1.0 文字识别（中文，打包模型版）：识别管线在 native 层，Java 侧的选项/结果类按名字回调
-keep class com.google.mlkit.vision.text.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }
-dontwarn com.google.android.gms.**

# osmdroid：瓦片源、缓存按类名创建
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# OkHttp / Okio：平台探测（Conscrypt、BouncyCastle、OpenJSSE 不存在时只是警告）
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# androidx.security-crypto（Tink）
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-keep class com.google.crypto.tink.proto.** { *; }

# 本应用：Manifest 里的组件 R8 会自动保留；Compose 不需要额外规则。
# 设置导入导出、更新信息都用 org.json 手动读写，没有反射序列化，所以不需要 keep 数据类。
# 桌面小部件 / 服务 / Receiver 以防万一按包保留类名（体积影响很小）
-keep class com.hark.shiguang.MemoryWidget* { *; }
-keep class com.hark.shiguang.MusicService { *; }
-keep class com.hark.shiguang.ScanService { *; }
