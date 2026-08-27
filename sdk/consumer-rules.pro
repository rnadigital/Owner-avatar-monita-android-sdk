# Copyright RNA Digital PTY LTD
# Consumer rules for the Monita Android SDK.

# Public entry points, referenced from manifests, androidx.startup, and woven bytecode.
-keep class ai.monita.sdk.Monita { *; }
-keep class ai.monita.sdk.MonitaConfig { *; }
-keep class ai.monita.sdk.MonitaConfig$Builder { *; }
-keep class ai.monita.sdk.MonitaInterceptor { *; }
-keep class ai.monita.sdk.MonitaInstrumentation { *; }
-keep class ai.monita.sdk.MonitaInitializer { *; }

# kotlinx.serialization: keep serializers for the SDK's own config model.
-keepclassmembers class ai.monita.sdk.internal.** {
    *** Companion;
}
-keepclasseswithmembers class ai.monita.sdk.internal.** {
    kotlinx.serialization.KSerializer serializer(...);
}
