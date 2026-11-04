import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    kotlin("multiplatform") version "2.4.21"
    id("com.android.kotlin.multiplatform.library") version "9.3.1"
    id("com.android.lint") version "9.3.1"
}

val negativeCase = providers.gradleProperty("fixtureNegativeCase").orElse("none").get()
require(negativeCase in setOf("none", "missing-actual", "wrong-actual", "new-api")) {
    "Unknown fixtureNegativeCase: $negativeCase"
}

// The provider harness puts outputs outside its immutable captured source tree.
providers.gradleProperty("fixtureBuildDir").orNull?.let {
    layout.buildDirectory.set(file(it))
}

kotlin {
    // Project/compiler JVM is separate from the checker's JVM-21 runtime.
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
    }

    android {
        namespace = "dev.ktchecker.fixture"
        compileSdk = 37
        buildToolsVersion = "36.0.0"
        minSdk = 24
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        withHostTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            isIncludeAndroidResources = false
            isReturnDefaultValues = false
        }
        lint {
            // This is a NewApi adapter control, not qualification of all Lint IDs.
            checkOnly.add("NewApi")
            checkDependencies = false
            abortOnError = true
        }
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        testRuns["test"].executionTask.configure { useJUnit() }
    }

    iosArm64 {
        binaries.framework {
            baseName = "KtCheckerFixture"
            isStatic = true
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "KtCheckerFixture"
            isStatic = true
        }
    }

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("desktopTest").dependencies { implementation(kotlin("test-junit")) }
        getByName("androidHostTest").dependencies { implementation(kotlin("test-junit")) }

        when (negativeCase) {
            "missing-actual" -> getByName("iosMain").kotlin.setSrcDirs(emptyList<String>())
            "wrong-actual" -> getByName("iosMain").kotlin.setSrcDirs(
                listOf("negative/wrong-actual/iosMain")
            )
            "new-api" -> getByName("androidMain").kotlin.srcDir("negative/new-api/androidMain")
        }
    }
}

// AGP 9.3 generates reports through variant artifacts, not the removed report DSL.
// This fixture has one Android variant and checks only this component. Preserve
// its explicitly selected XML report at the adapter's stable output location.
var boundLintVariant: String? = null
extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    onVariants(selector().all()) { variant ->
        check(boundLintVariant == null) { "The fixture requires exactly one Android Lint variant" }
        boundLintVariant = variant.name
        val selectedReport = variant.artifacts.get(SingleArtifact.LINT_XML_REPORT)
        val retainedReport = layout.buildDirectory.file("reports/lint-results.xml")
        val reportTaskName = variant.computeTaskName("lintReport", "")
        var reportPrepared = false
        val retainReport = tasks.register("retainFixtureLintReport") {
            group = "verification"
            description = "Retain the selected AGP Lint XML artifact, including a negative control."
            // No input/dependsOn edge to the producer: a failed producer must
            // not prevent its finalizer from inspecting the newly written file.
            doLast {
                val destination = retainedReport.get().asFile.toPath()
                val source = selectedReport.get().asFile.toPath()
                check(source != destination) { "The retained report must not replace its selected artifact" }
                Files.deleteIfExists(destination)
                check(reportPrepared) { "The selected Lint report producer did not start freshly" }
                check(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    "The selected Lint XML artifact is absent or not a regular file"
                }
                check(Files.size(source) in 1L..(8L * 1024 * 1024)) {
                    "The selected Lint XML artifact is empty or exceeds the fixture report bound"
                }
                Files.createDirectories(destination.parent)
                val pending = destination.resolveSibling("${destination.fileName}.tmp")
                Files.deleteIfExists(pending)
                try {
                    Files.copy(source, pending)
                    Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(pending)
                }
            }
        }
        tasks.matching { task -> task.name == reportTaskName }.configureEach {
            doFirst {
                reportPrepared = false
                val destination = retainedReport.get().asFile.toPath()
                val source = selectedReport.get().asFile.toPath()
                check(source != destination) { "The retained report must not replace its selected artifact" }
                Files.deleteIfExists(destination)
                Files.deleteIfExists(source)
                reportPrepared = true
            }
            finalizedBy(retainReport)
        }
    }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    maxHeapSize = "256m"
    testLogging { events("failed", "skipped") }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    // deviceId was removed from the supported DSL; device is a Property<String>.
    providers.gradleProperty("fixtureSimulatorDevice").orNull?.let { device.set(it) }
}

// AGP owns the concrete host-test task name. Never let an empty alias pass.
val androidHostTests = tasks.withType<Test>().matching { task ->
    task.name.contains("android", ignoreCase = true)
}
tasks.register("fixtureAndroidHostTest") {
    group = "verification"
    description = "Execute the Android KMP host-side tests (not device tests)."
    dependsOn(androidHostTests)
    doFirst {
        check(!androidHostTests.isEmpty()) { "AGP did not create an Android host-test task" }
    }
}
