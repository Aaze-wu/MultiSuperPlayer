# --- Media3 / ExoPlayer ---------------------------------------------------
# Media3 自带 consumer proguard 规则，这里只补充反射相关的保险。
-dontwarn androidx.media3.**

# --- Kotlinx Serialization -------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.multisuperplayer.**$$serializer { *; }
-keepclassmembers class com.multisuperplayer.** {
    *** Companion;
}
-keepclasseswithmembers class com.multisuperplayer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Koin -------------------------------------------------------------------
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# --- 保留行号，便于崩溃定位 ------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
