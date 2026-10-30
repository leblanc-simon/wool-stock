# Règles R8 de la version release. Les bibliothèques (kotlinx.serialization, Ktor, OkHttp,
# Room, ML Kit, CameraX, Compose) apportent les leurs ; s'ajoutent ici, par prudence, celles
# des classes de l'app que kotlinx.serialization lit sans réflexion visible (modèles JSON de
# l'API, session enregistrée et routes de navigation), puis ce qui manque aux bibliothèques.

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature

-keep,includedescriptorclasses class io.leblanc.woolstock.**$$serializer { *; }
-keepclassmembers class io.leblanc.woolstock.** {
    *** Companion;
}
-keepclasseswithmembers class io.leblanc.woolstock.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# La navigation retrouve l'enum d'un argument de route (ScanMode de ConfirmRoute) par son nom
# complet, avec Class.forName, puis ses constantes : renommé, il fait planter l'app au lancement.
# Tous les enums de l'app sont gardés, pour qu'un futur argument de route ne casse pas qu'en release.
-keep enum io.leblanc.woolstock.** { *; }

# ML Kit lit ses composants dans le manifeste (MlKitComponentDiscoveryService) et crée chaque
# ComponentRegistrar par réflexion, avec son constructeur sans argument. R8 gardait leur nom
# mais retirait ce constructeur : aucun composant n'était déclaré, et BarcodeScanning.getClient
# plantait à l'ouverture de la caméra.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}

# Ktor choisit son moteur (OkHttp) par ServiceLoader.
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer
