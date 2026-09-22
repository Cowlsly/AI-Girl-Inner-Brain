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

