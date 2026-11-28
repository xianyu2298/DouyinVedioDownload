# ===== 体积优化相关的 keep 规则 =====
# 目标：让 R8 尽量裁剪，同时保证 Jackson / ffmpeg-kit / OkHttp 在 release 下正常工作。

# 注解与泛型签名：Jackson 与 Serializable 依赖
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# ---------- Jackson ----------
# 项目只使用树模型（ObjectMapper.readTree / JsonNode），但 Jackson 内部仍通过反射
# 加载序列化器与模块，因此保留 databind 与 core 的类名，避免运行期 NoSuchMethod。
-keep class com.fasterxml.jackson.databind.** { *; }
-keep class com.fasterxml.jackson.core.** { *; }
-keep class com.fasterxml.jackson.annotation.** { *; }
-dontwarn com.fasterxml.jackson.**

# ---------- ffmpeg-kit ----------
# 原生层通过 JNI 以类名/方法名回调 Java 层，混淆后会导致命令回调丢失。
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.**

# ---------- OkHttp ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
