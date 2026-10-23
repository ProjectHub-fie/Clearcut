# Media3 Transformer / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.projecthub.clearcut.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.projecthub.clearcut.**$$serializer { *; }
-keepclassmembers class com.projecthub.clearcut.** { *** Companion; }
-keepclasseswithmembers class com.projecthub.clearcut.** { kotlinx.serialization.KSerializer serializer(...); }
# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
