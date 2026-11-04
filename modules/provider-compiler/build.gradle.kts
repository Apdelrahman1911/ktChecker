import java.nio.file.Path

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":provider-runtime"))
    implementation(project(":project-model"))
    testImplementation(project(":snapshot"))
}

val compilerToolchain by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    compilerToolchain("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
}

tasks.register<JavaExec>("compilerFixtures") {
    description = "Execute real, serial Kotlin/JVM compiler-provider fixtures; never a registry-count test."
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.ktchecker.compiler.CompilerFixtureRunner")
    maxHeapSize = "384m"
    doFirst {
        val selectedJavaHome = providers.gradleProperty("compilerFixtureJavaHome").orNull
            ?: error("Explicit -PcompilerFixtureJavaHome=<absolute owner-prepared link-free JDK21 home> is required; no system-JDK fallback")
        val selectedHome = Path.of(selectedJavaHome)
        require(selectedHome.isAbsolute) { "compilerFixtureJavaHome must be absolute" }
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        systemProperty("ktchecker.compiler.classpath", compilerToolchain.asPath)
        systemProperty("ktchecker.compiler.java", selectedHome.resolve("bin").resolve(javaName).toString())
        systemProperty("ktchecker.compiler.heapMiB", providers.gradleProperty("compilerFixtureHeapMiB").getOrElse("512"))
        args(rootProject.file("providers/compiler-fixtures").absolutePath)
    }
}

tasks.register<JavaExec>("compilerTimingProbe") {
    description = "Observe a fixed eight-call serial compiler timing schedule; private receipts only, not ordinary fixture completion."
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.ktchecker.compiler.CompilerFixtureTimingProbe")
    maxHeapSize = "384m"
    doFirst {
        val selectedJavaHome = providers.gradleProperty("compilerFixtureJavaHome").orNull
            ?: error("Explicit -PcompilerFixtureJavaHome=<absolute owner-prepared link-free JDK21 home> is required; no system-JDK fallback")
        val selectedHome = Path.of(selectedJavaHome)
        require(selectedHome.isAbsolute) { "compilerFixtureJavaHome must be absolute" }
        val requestedHeap = providers.gradleProperty("compilerFixtureHeapMiB").orNull
        require(requestedHeap == null || requestedHeap == "512") { "The fixed timing probe requires compilerFixtureHeapMiB=512" }
        val javaName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        systemProperty("ktchecker.compiler.classpath", compilerToolchain.asPath)
        systemProperty("ktchecker.compiler.java", selectedHome.resolve("bin").resolve(javaName).toString())
        systemProperty("ktchecker.compiler.heapMiB", "512")
        args(rootProject.file("providers/compiler-fixtures").absolutePath)
    }
}

// Private finite-generation experiment only. Neither check/test nor shipped launchers depend on it.
val compilerWarmWorkerBridge = tasks.register<Jar>("compilerWarmWorkerBridge") {
    description = "Package only the Java-only private warm-generation bridge, never host/JUnit/compiler dependencies."
    dependsOn(tasks.named("compileTestJava"))
    archiveFileName.set("ktchecker-compiler-warm-generation-preview-1.jar")
    destinationDirectory.set(layout.buildDirectory.dir("private-experiments"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(sourceSets["test"].output.classesDirs) {
        include("dev/ktchecker/compilerworker/CompilerWarmWorkerProtocol*.class")
        include("dev/ktchecker/compilerworker/PinnedKotlinCompilerAdapter*.class")
        include("dev/ktchecker/compilerworker/CompilerWarmWorkerMain*.class")
    }
}

tasks.register<JavaExec>("compilerWarmGeneration") {
    description = "Private explicitly consented four-job finite warm-JVM experiment; no ordinary fixture or production qualification."
    group = "verification"
    dependsOn(tasks.named("testClasses"), compilerWarmWorkerBridge)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.ktchecker.compiler.CompilerWarmWorkerFixtureRunner")
    maxHeapSize = "384m"
    doFirst {
        require(providers.gradleProperty("compilerWarmGenerationConsent").orNull == "finite-four-2.4.21") {
            "Explicit -PcompilerWarmGenerationConsent=finite-four-2.4.21 owner experiment consent is required"
        }
        val selectedHome = Path.of(providers.gradleProperty("compilerFixtureJavaHome").orNull
            ?: error("Explicit -PcompilerFixtureJavaHome=<absolute owner-prepared link-free JDK21 home> is required"))
        require(selectedHome.isAbsolute && System.getProperty("os.name") == "Linux") { "Private Linux/link-free JDK21 experiment only" }
        val heap = providers.gradleProperty("compilerFixtureHeapMiB").orNull
        require(heap == null || heap == "512") { "The private finite generation requires a fixed512MiB compiler heap" }
        systemProperty("ktchecker.compiler.classpath", compilerToolchain.asPath)
        systemProperty("ktchecker.compiler.java", selectedHome.resolve("bin/java").toString())
        systemProperty("ktchecker.compiler.warm.bridge", compilerWarmWorkerBridge.get().archiveFile.get().asFile.absolutePath)
        systemProperty("ktchecker.compiler.warm.consent", "finite-four-2.4.21")
        args(rootProject.file("providers/compiler-fixtures").absolutePath)
    }
}
