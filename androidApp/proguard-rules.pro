# Add project specific ProGuard rules here.

# Keep Koin
-keep class org.koin.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data classes
-keepclassmembers class app.s4h.nisafone.** {
    <init>(...);
    <fields>;
}

# Keep Sherpa-ONNX JNI bindings — the native libsherpa-onnx-jni.so looks up
# these classes/members by name; the AAR ships no consumer rules, so R8
# stripping or renaming them crashes release builds only
-keep class com.k2fsa.sherpa.onnx.** { *; }
