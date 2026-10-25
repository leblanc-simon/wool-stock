import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// L'application Android : l'activité, le conteneur de dépendances et les ressources du
// lanceur. Les écrans vivent dans :shared, communs avec l'app iOS.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "eu.leblancsimon.woolstock"
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.leblancsimon.woolstock"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.8.0"

        // Serveur par défaut : celui de production. Modifiable dans les réglages de l'app, ou au
        // build avec -Pwoolstock.apiUrl=http://127.0.0.1:8080 (poste de dev joint par `adb reverse`).
        val apiUrl = providers.gradleProperty("woolstock.apiUrl").getOrElse("https://stock-laine.carin.family")
        buildConfigField("String", "DEFAULT_API_URL", "\"$apiUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
