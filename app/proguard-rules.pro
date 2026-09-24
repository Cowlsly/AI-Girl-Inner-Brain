# Maskan ProGuard Rules

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.maskan.chat.**serializer { *; }
-keepclassmembers class app.maskan.chat.** { *** Companion; }
-keepclasseswithmembers class app.maskan.chat.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Suppress missing Google Error Prone annotations (compile-time only, used by Tink/security-crypto)
-dontwarn com.google.errorprone.annotations.**

# PdfBox-Android names an OPTIONAL JPEG-2000 decoder (com.gemalto.jp2:jp2-android) that this
# app does not ship: JPXFilter is for decoding JPEG-2000 IMAGES inside a PDF, and Maskan reads
# text. R8 refuses to finish while the reference is unresolved, which fails the RELEASE build
# only - the debug build has no R8 and says nothing. Found by building a release to measure the
# APK delta; without this line session 6's release build would have failed on Linux instead.
-dontwarn com.gemalto.jp2.**

# MediaPipe's LLM option classes are generated with Google AutoValue, whose annotations have
# CLASS retention and are never present at runtime. R8 still refuses to finish while it cannot
# resolve them.
#
# Session 5 recorded R8 as "clean, no new -dontwarn" with this same dependency, and that was
# true of that build and misleading about this one: nothing in src/main referenced the library
# then, so R8 shrank it away before it ever read these classes. The moment the engine moved to
# src/main and the provider called it, the release build failed. Worth remembering as a shape:
# a dependency measured while unreachable has not been measured.
-dontwarn com.google.auto.value.**

# MediaPipe ships NO consumer rules, and the -dontwarn above only lets R8 finish; it keeps
# nothing. 2.6.0 shipped like that, and the Play build failed to load the on-device model on a
# Redmi Note 15 Pro; an R8 build of the same commit failed identically there, and nothing in the
# failure depends on the phone:
#
#   RuntimeException: Field modelPath_ for a3.g not found
#
# The load options are a protobuf-lite message, and protobuf-lite finds its fields BY NAME at
# runtime; R8 had renamed them. The debug build has no R8, so every on-device test passed on
# debug and the store build never ran once. MediaPipe's native side also calls back into its Java
# classes by name (session callbacks, LlmTaskRunner), so the whole package is kept, not only the
# class that failed first.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class com.google.mediapipe.** { *; }
# Keeping the whole package makes R8 read LlmTaskRunner.createImage, which names MPImage classes
# that live in a separate artifact tasks-genai does not pull in. Maskan never hands the on-device
# model an image (setMaxNumImages(0), vision modality off), so that path is never reached.
-dontwarn com.google.mediapipe.framework.image.**

# Keep Retrofit interfaces
-keep,allowobfuscation interface app.maskan.chat.data.remote.OpenAiCompatibleService
-keep,allowobfuscation interface app.maskan.chat.data.remote.AnthropicService
-keep,allowobfuscation interface app.maskan.chat.data.remote.GeminiService

# Strip all android.util.Log calls from release builds (defense-in-depth: no
# stray debug logging ever ships, regardless of BuildConfig.DEBUG guards).
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

