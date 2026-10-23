# Ktor ContentNegotiation loads kotlinx JSON via ServiceLoader — must keep provider + SPI.
-keep class io.ktor.serialization.kotlinx.** { *; }
-keep class io.ktor.serialization.** { *; }
-keepnames class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keepclassmembers class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

-keep class io.ktor.client.** { *; }
-keep class io.ktor.http.** { *; }
-keep class io.ktor.websocket.** { *; }
-keep class io.ktor.utils.io.** { *; }

-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.servermaintance.**$$serializer { *; }
-keepclassmembers class com.servermaintance.** {
    *** Companion;
}
-keepclasseswithmembers class com.servermaintance.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

-dontwarn org.slf4j.**
-dontwarn kotlinx.atomicfu.**
