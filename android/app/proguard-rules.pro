# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.walkietalkie.**$$serializer { *; }
-keepclassmembers class com.walkietalkie.** {
    *** Companion;
}
-keepclasseswithmembers class com.walkietalkie.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
