import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// L'application Android : l'activité, le conteneur de dépendances et les ressources du
// lanceur. Les écrans vivent dans :shared, communs avec l'app iOS.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Paquet Kotlin et classes générées (R, BuildConfig) ; l'identifiant de l'app est applicationId.
    namespace = "io.leblanc.woolstock"
    compileSdk = 37

    defaultConfig {
        // Le même que le bundle ID iOS ; il figure aussi dans le User-Agent des appels au serveur.
        applicationId = "io.leblanc.woolstock"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.10.0"

        // Serveur par défaut : celui de production. Modifiable dans les réglages de l'app, ou au
        // build avec -Pwoolstock.apiUrl=http://127.0.0.1:8080 (poste de dev joint par `adb reverse`).
        val apiUrl = providers.gradleProperty("woolstock.apiUrl").getOrElse("https://stock-laine.carin.family")
        buildConfigField("String", "DEFAULT_API_URL", "\"$apiUrl\"")
    }

    buildTypes {
        release {
            // R8 retire le code inutilisé des bibliothèques (Ktor, Compose, ML Kit…) et le
            // renomme ; le fichier de correspondance part avec l'AAB pour lire les plantages
            // dans la Play Console. À essayer sur un téléphone avant chaque publication.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "fr")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}
