# Metro IntelliTrip release rules (R8 / ProGuard)
#
# This app is small and almost entirely Kotlin, so the default rules plus the entries
# below are enough. The rule that matters most is the one about reflection: none of the
# Atlas SDK uses it, so any entry added here needs a reason, and a release that fails at
# runtime with NoSuchMethodError is discovered by users rather than by CI.
#
# The instrumentation tests run against the debug build, so a shrinker regression shows
# up there only after a release build is produced. Keep this file in the loop of
# "does assembleRelease still start".

# MapLibre Native ships a JNI layer reached from generated code and from Kotlin.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.android.**
# MapLibre loads its native libraries by name at runtime.
-keepclasseswithmembernames class * { native <methods>; }

# Hilt / Dagger generate code that references members reflectively at startup.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keepclasseswithmembernames class * { @javax.inject.Inject <init>(...); }
-keepclasseswithmembernames class * { @javax.inject.Inject <fields>; }
-dontwarn dagger.hilt.**

# Retrofit builds its API implementation from the interface's generic signatures, which
# are only present in the bytecode signature attribute.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations
-keep,allowobfuscation interface com.intellitrip.contracts.**
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx.serialization and friends keep companion serializers on their class.
-keepclassmembers class **$Companion { *; }

# java.time is desugared into the app; the desugared runtime must not be stripped.
-keep class com.android.tools.r8.** { *; }
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.**

# Keep crash reports readable: line numbers and the original file name are what make a
# stack trace actionable, and both are stripped by default.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile