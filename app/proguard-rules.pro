-dontobfuscate
-dontoptimize

# Xray core (gomobile / JNI)
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# kotlinx.serialization: VpsConfig (DataStore) и DTO API (Retrofit)
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.v2rayez.app.data.vps.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.v2rayez.app.data.vps.** { *** Companion; }
-keepclasseswithmembers class com.v2rayez.app.data.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.v2rayez.app.data.api.** { *** Companion; }
-keepclassmembers enum com.v2rayez.app.data.api.Plan { *; }

# Retrofit (интерфейс с suspend-методами)
-keep interface com.v2rayez.app.data.api.ApiService { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

-keep class com.v2rayez.app.data.service.** { *; }
