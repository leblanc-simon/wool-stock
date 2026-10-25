import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Module Kotlin multiplateforme, sans dépendance Android ni Apple : le même parseur sert l'app
// Android (cible JVM) et l'app iOS. Les tests tournent sur la JVM.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// brands.json devient une constante Kotlin : le dictionnaire voyage avec le code, sans fichier à
// retrouver à l'exécution (iOS n'a pas de classpath). Ajouter une marque reste une entrée JSON.
val generateBrands = tasks.register("generateBrands") {
    val input = layout.projectDirectory.file("brands.json")
    val output = layout.buildDirectory.dir("generated/brands/kotlin")
    inputs.file(input)
    outputs.dir(output)
    doLast {
        val json = input.asFile.readText().replace("$", "\${'$'}")
        val file = output.get().file("eu/leblancsimon/woolstock/parser/BrandsJson.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "// Généré depuis label-parser/brands.json : ne pas modifier.\n" +
                "package eu.leblancsimon.woolstock.parser\n\n" +
                "internal const val BRANDS_JSON: String = \"\"\"" + json + "\"\"\"\n",
        )
    }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBrands)
            dependencies {
                api(libs.kotlinx.serialization.json)
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    val reportDir = layout.buildDirectory.dir("reports/label-parser")
    outputs.dir(reportDir)
    systemProperty("labelReportDir", reportDir.get().asFile.absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}
