# kotlinx.serialization (bundled JSON assets and API payloads)
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.laksh.forge.**$$serializer { *; }
-keepclassmembers class com.laksh.forge.** { *** Companion; }
-keepclasseswithmembers class com.laksh.forge.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / Okio optional platform integrations
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# PDFBox-Android loads fonts, filters and resources reflectively; its JPEG2000 decoder is optional.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**
