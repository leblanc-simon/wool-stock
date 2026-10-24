import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Module Kotlin pur : aucune dépendance Android, testable sur la JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    val reportDir = layout.buildDirectory.dir("reports/label-parser")
    outputs.dir(reportDir)
    systemProperty("labelReportDir", reportDir.get().asFile.absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}
