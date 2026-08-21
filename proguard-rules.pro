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

# osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
