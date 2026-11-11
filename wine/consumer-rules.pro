# The Wine engine's native libraries call back into these classes by name (JNI), and parts of it
# load classes reflectively, so R8 must neither rename nor strip them.
-keep class com.winlator.** { *; }
-keep class app.gamenative.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# zstd-jni and xz: native code reads their fields by name.
-keep class com.github.luben.zstd.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn com.samsung.sdk.sperf.**
-dontwarn horizon.**
-dontwarn com.meta.horizon.**
