# Règles R8 de la version release. Les bibliothèques (kotlinx.serialization, Ktor, OkHttp,
# Room, ML Kit, CameraX, Compose) apportent les leurs ; s'ajoutent ici, par prudence, celles
# des classes de l'app que kotlinx.serialization lit sans réflexion visible : modèles JSON de
# l'API, session enregistrée et routes de navigation.

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature

-keep,includedescriptorclasses class eu.leblancsimon.woolstock.**$$serializer { *; }
-keepclassmembers class eu.leblancsimon.woolstock.** {
    *** Companion;
}
-keepclasseswithmembers class eu.leblancsimon.woolstock.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor choisit son moteur (OkHttp) par ServiceLoader.
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer
