# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ===== Xposed 入口必须保留（xposed_init 里是类名字符串，混淆后会对不上）=====
-keep class io.github.proify.lyricon.qmprovider.xposed.HookEntry { *; }
-keep class io.github.proify.lyricon.qmprovider.xposed.QQMusic { *; }
-keepnames class io.github.proify.lyricon.qmprovider.xposed.HookEntry
-keepnames class io.github.proify.lyricon.qmprovider.xposed.QQMusic
-keepclassmembers class io.github.proify.lyricon.qmprovider.xposed.** { *; }

-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, SourceFile, LineNumberTable

# YukiHookAPI / KavaRef
-keep class com.highcapable.yukihookapi.** { *; }
-keep class com.highcapable.kavaref.** { *; }
-dontwarn com.highcapable.**

# Kotlin / 序列化
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**

# 反射用到的歌词引擎类（运行时按类名找）
-keep class com.lyricengine.** { *; }
-dontwarn com.lyricengine.**