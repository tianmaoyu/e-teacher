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
