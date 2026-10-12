# Règles ProGuard/R8 Nyx
# lazysodium + JNA : conserver les liaisons natives
-keep class com.goterl.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { <fields>; <methods>; }
# JNA embarque des branches desktop (java.awt) jamais chargées sur Android :
# ne pas faire échouer R8 dessus (règles générées par R8, cf. missing_rules.txt).
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window

# kotlinx.serialization : conserver les serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class net.nyx.messenger.** {
    *** Companion;
}
-keepclasseswithmembers class net.nyx.messenger.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLCipher
-keep class net.sqlcipher.** { *; }

# OkHttp (déjà optimisé, silence)
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
