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

# --- sherpa-onnx（语音识别引擎）--------------------------------------------
# 上游 AAR 里的 proguard.txt 是**空文件**（0 字节），也就是它不发 consumer rules，
# 而它的 Java 类全是 JNI 壳：方法体就是 native 调用，另有一部分绑定是在
# `JNI_OnLoad` 里按「类名 + 方法名 + 签名」注册的。
#
# R8 在 release 里默认会改名、也会把「只有 native 声明、没人调用」的成员删掉，
# 两种情况的结果都是原生侧找不到实现：
#   java.lang.UnsatisfiedLinkError: No implementation found for
#     void com.k2fsa.sherpa.onnx.Vad.acceptWaveform(float[])
# 而 debug 包（未混淆）一切正常 —— 这是典型的「只有 release 崩」的缺陷，
# 装机测试时最容易漏掉。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# --- LiteRT-LM（本地翻译推理引擎）------------------------------------------
# 与 sherpa-onnx 同一类问题，而且更彻底：这个 AAR 里**没有** aar-metadata.properties、
# 也**没有** proguard.txt（实测 classes.jar 78 个 class，rules 一条都不带），
# 于是混淆器对它没有任何约定。
#
# 它的绑定方式是「Java 壳 + JNI_OnLoad 里按类名/方法名注册」，并且引擎配置
# 走的是反射读注解（`@ExperimentalApi` 那一层）。R8 改名或裁剪之后的症状
# 同样是只有 release 包才会出现的 `UnsatisfiedLinkError` / `NoSuchMethodError`。
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# --- Koin -------------------------------------------------------------------
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# --- 保留行号，便于崩溃定位 ------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
