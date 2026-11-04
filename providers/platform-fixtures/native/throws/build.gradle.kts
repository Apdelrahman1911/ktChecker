import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("multiplatform") version "2.4.21"
}

// This is an explicit owner fixture, not a generic project importer or download task.
val fixtureCase = providers.gradleProperty("fixtureNegativeCase").get()
require(fixtureCase in setOf(
    "empty-list", "empty-list-nonempty", "empty-list-omitted",
    "override-filter", "override-filter-matching", "override-filter-inherited", "override-filter-reordered", "override-filter-subtype",
    "inherited-filter", "inherited-filter-matching", "inherited-filter-diamond",
    "suspend-cancellation", "suspend-cancellation-exact", "suspend-cancellation-superclass", "suspend-cancellation-nonsuspend",
    "suspend-implicit-override", "suspend-implicit-inherited",
    "override-filter-nowarn", "override-filter-suppression", "override-filter-demotion-denied",
)) { "Unsupported authored Native throws case" }
require(System.getProperty("java.specification.version") == "25") { "Nested fixture Gradle requires the explicit JDK 25" }
val nativeHome = providers.gradleProperty("kotlin.native.home").get()
require(file(nativeHome).isDirectory && file("$nativeHome/bin/konanc").isFile) { "An explicit provisioned Native home is required" }
providers.gradleProperty("fixtureBuildDir").orNull?.let { layout.buildDirectory.set(file(it)) }

kotlin {
    // These compile Kotlin/Native KLIBs only. There is no JVM or Android substitute,
    // framework link, simulator launch, cinterop header or Swift execution here.
    jvmToolchain(25)
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_4)
        apiVersion.set(KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs.add("-Xrender-internal-diagnostic-names")
        when (fixtureCase) {
            "override-filter-nowarn" -> freeCompilerArgs.add("-nowarn")
            "override-filter-demotion-denied" ->
                freeCompilerArgs.add("-Xwarning-level=INCOMPATIBLE_THROWS_OVERRIDE:warning")
        }
    }
    sourceSets {
        getByName("commonMain").kotlin.setSrcDirs(listOf("src/commonMain/kotlin"))
        getByName("iosMain").kotlin.setSrcDirs(listOf("cases/$fixtureCase/iosMain"))
    }
}
