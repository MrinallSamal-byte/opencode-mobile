# Keep OkHttp platform internals used on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep kotlinx.serialization classes and generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt

-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

-keepclassmembers class * {
    *** Companion;
}

-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,allowobfuscation,allowshrinking class * extends kotlinx.serialization.internal.GeneratedSerializer {
    <init>(...);
}

# Keep project data models serialized to JSON
-keep class com.opencode.mobile.data.** { *; }
-keep class com.opencode.mobile.network.** { *; }
