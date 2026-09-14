# ---------------------------------------------------------------
# SpeakMate R8 规则
# ---------------------------------------------------------------

# OkHttp：库自带 consumer rules，这里只兜住反射与可选依赖
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin 协程
-dontwarn kotlinx.coroutines.**

# 保留行号，线上崩溃栈可读
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WebSocket 回调由 OkHttp 线程反射调用，保留其方法签名
-keepclassmembers class * implements okhttp3.WebSocketListener {
    public *;
}

# Tink（security-crypto 传递依赖）引用的 errorprone 注解仅存在于其编译期，
# 运行时 classpath 没有，R8 需要显式豁免（规则由 R8 自动生成）
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
