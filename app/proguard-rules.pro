# 保留调试用的行号信息
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Java-WebSocket ────────────────────────────────────────────────────────────
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# ── Opus JNI（本地库入口不能被混淆）────────────────────────────────────────────
-keep class com.lhht.xiaozhi.audio.OpusUtils { *; }

# ── JSON（org.json 是 Android SDK 自带，但以防万一）──────────────────────────
-keep class org.json.** { *; }

# ── Android 标准保留 ─────────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
