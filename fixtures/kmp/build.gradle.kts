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
        compilerOptions.configure { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            isIncludeAndroidResources = false
            isReturnDefaultValues = false
        }
        lint {
            // This is a NewApi adapter control, not qualification of all Lint IDs.
            checkOnly.add("NewApi")
            abortOnError = true
            xmlReport = true
            xmlOutput = layout.buildDirectory.file("reports/lint-results.xml").get().asFile
            htmlReport = false
            textReport = false
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
        desktopTest.dependencies { implementation(kotlin("test-junit")) }
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
val androidHostTests = tasks.withType<Test>().matching {
    name.contains("android", ignoreCase = true)
}
tasks.register("fixtureAndroidHostTest") {
    group = "verification"
    description = "Execute the Android KMP host-side tests (not device tests)."
    dependsOn(androidHostTests)
    doFirst {
        check(!androidHostTests.isEmpty()) { "AGP did not create an Android host-test task" }
    }
}
