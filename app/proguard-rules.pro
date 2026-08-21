# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class fr.velo.cadence.** {
    *** Companion;
}
-keepclasseswithmembers class fr.velo.cadence.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class fr.velo.cadence.**$$serializer { *; }

# MapLibre
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
