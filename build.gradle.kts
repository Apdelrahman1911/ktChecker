import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
}
allprojects {
    group = "dev.ktchecker"
    version = "0.1.0-dev.1"
    repositories { mavenCentral() }
}
subprojects {
    // The shared worker wire is JDK-only: never inject Kotlin/compiler/runtime libraries.
    if (name != "worker-protocol") {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
        extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(21) }
        dependencies {
            add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            add("testImplementation", "org.jetbrains.kotlin:kotlin-test-junit5:2.4.21")
            add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine:5.11.4")
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.11.4")
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxParallelForks = 1
        maxHeapSize = "384m"
        testLogging { events("failed", "skipped"); showStandardStreams = false }
    }
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
