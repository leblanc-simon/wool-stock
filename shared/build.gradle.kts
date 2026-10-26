import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Écrans, navigation, accès au serveur et cache local, communs à Android et iOS (Compose
// Multiplatform). Seuls la caméra (CameraX + ML Kit, AVFoundation + Vision), le partage de
// fichiers, les réglages enregistrés et le thème diffèrent d'une plateforme à l'autre.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    compilerOptions {
        // La base Room déclare son constructeur en expect, que KSP implémente par plateforme.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "eu.leblancsimon.woolstock.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        androidResources {
            enable = true
        }
        // Les tests du code commun tournent sur la JVM, sans appareil.
        withHostTest {}
    }

    // Le framework « Shared » est lié par Xcode (iosApp) ; l'édition de liens demande un Mac.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":label-parser"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.core)
            implementation(libs.compose.components.resources)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.navigation.compose)
            implementation(libs.androidx.room.runtime)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.androidx.camera.mlkit.vision)
            // Modèles embarqués dans l'APK : aucun téléchargement via Play Services.
            implementation(libs.mlkit.barcode.scanning)
            implementation(libs.mlkit.text.recognition)
            implementation(libs.androidx.sqlite.framework)
            // OkHttp parle HTTP/2, comme le navigateur ; HttpURLConnection s'en tient à HTTP/1.1.
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.ktor.client.darwin)
        }
    }
}

compose.resources {
    packageOfResClass = "eu.leblancsimon.woolstock.resources"
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
