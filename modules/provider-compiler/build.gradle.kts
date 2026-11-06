import java.nio.file.Path
import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":worker-protocol"))
    implementation(project(path = ":worker-protocol", configuration = "modelWireElements"))
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

// Additive model application profile. The persistent M0 block below remains byte-for-byte unchanged.
// All model production Java sources use only the JDK and two explicitly selected wire artifacts.
// Host-only model controls use the real owner catalog; nothing here enters a worker raw output or classpath.
dependencies.add("testImplementation", project(":review-controller"))
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processTestResources") {
    from(rootProject.file("data")) {
        include("rules-core.json", "rules-libraries.json", "rules-platform.json", "rules-ecosystem.json")
        into("model-live-control-catalog")
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }
}
val applicationModelWorker = sourceSets.create("modelWorker")
val applicationModelWorkerTest = sourceSets.create("modelWorkerTest")
val applicationModelOriginalWireBridge = configurations.create("applicationModelOriginalWireBridge") {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val applicationModelWireBridge = configurations.create("applicationModelWireBridge") {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val applicationModelWorkerTestLibraries = configurations.create("applicationModelWorkerTestLibraries") {
    isCanBeConsumed = false; isCanBeResolved = true
}
dependencies.add(applicationModelOriginalWireBridge.name, project(":worker-protocol"))
dependencies.add(applicationModelWireBridge.name, dependencies.project(mapOf("path" to ":worker-protocol", "configuration" to "modelWireElements")))
dependencies.add(applicationModelWorkerTestLibraries.name, "org.junit.jupiter:junit-jupiter-api:5.11.4")
dependencies.add(applicationModelWorkerTestLibraries.name, "org.junit.jupiter:junit-jupiter-engine:5.11.4")
dependencies.add(applicationModelWorkerTestLibraries.name, "org.junit.platform:junit-platform-launcher:1.11.4")
applicationModelWorker.compileClasspath = files(applicationModelOriginalWireBridge, applicationModelWireBridge)
applicationModelWorker.runtimeClasspath = files(applicationModelWorker.output, applicationModelWorker.compileClasspath)
applicationModelWorkerTest.compileClasspath = files(
    applicationModelWorker.output, applicationModelOriginalWireBridge, applicationModelWireBridge, applicationModelWorkerTestLibraries,
)
applicationModelWorkerTest.runtimeClasspath = files(applicationModelWorkerTest.output, applicationModelWorkerTest.compileClasspath)
// KGP creates custom Kotlin compilations automatically. They must have neither sources nor inherited host libraries.
kotlin.sourceSets.named(applicationModelWorker.name) { kotlin.setSrcDirs(emptyList<String>()) }
kotlin.sourceSets.named(applicationModelWorkerTest.name) { kotlin.setSrcDirs(emptyList<String>()) }
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileModelWorkerKotlin") {
    enabled = false
    libraries.setFrom(applicationModelWorker.compileClasspath)
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileModelWorkerTestKotlin") {
    enabled = false
    libraries.setFrom(applicationModelWorkerTest.compileClasspath)
}
tasks.named<org.gradle.api.tasks.compile.JavaCompile>(applicationModelWorker.compileJavaTaskName) {
    classpath = applicationModelWorker.compileClasspath
}
tasks.named<org.gradle.api.tasks.compile.JavaCompile>(applicationModelWorkerTest.compileJavaTaskName) {
    classpath = applicationModelWorkerTest.compileClasspath
}
val applicationModelWorkerControls = tasks.register<Test>("modelWorkerTest") {
    description = "Pure model-worker protocol/lifecycle/filesystem controls; never a live compiler execution gate."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = applicationModelWorkerTest.output.classesDirs
    classpath = applicationModelWorkerTest.runtimeClasspath
    dependsOn(applicationModelWorker.classesTaskName, applicationModelWorkerTest.classesTaskName)
}
tasks.named("test") { dependsOn(applicationModelWorkerControls) }
tasks.named("check") { dependsOn(applicationModelWorkerControls) }

val compilerModelOriginalWireFamilies = setOf("dev/ktchecker/workerprotocol/ApplicationWire", "dev/ktchecker/workerprotocol/CompilerJobWire")
val compilerModelIncludedOriginalWireFamilies = setOf("dev/ktchecker/workerprotocol/ApplicationWire")
val compilerModelWireFamilies = setOf("dev/ktchecker/workerprotocol/CompilerModelWire")
val compilerModelWorkerFamilies = setOf(
    "dev/ktchecker/compilerworker/live/CompilerModelApplicationMain",
    "dev/ktchecker/compilerworker/live/PinnedKotlinModelCompilerAdapter",
)
val compilerModelBridgeFamilies = compilerModelIncludedOriginalWireFamilies + compilerModelWireFamilies + compilerModelWorkerFamilies
fun compilerModelBridgeClass(path: String, families: Set<String>): Boolean = families.any { family ->
    path == "${family}.class" || (path.startsWith("${family}\$") && path.endsWith(".class") &&
        path.indexOf('/', family.length) == -1 && path.indexOf('\\', family.length) == -1)
}
fun compilerModelArchiveClasses(archive: java.io.File, families: Set<String>, label: String): List<String> {
    val seen = mutableSetOf<String>()
    val names = mutableListOf<String>()
    ZipFile(archive).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            require(name.isNotEmpty() && !name.startsWith("/") && '\\' !in name &&
                name.removeSuffix("/").split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                "Unsafe $label archive entry: $name"
            }
            require(seen.add(name)) { "Duplicate $label archive entry: $name" }
            if (!entry.isDirectory && name != "META-INF/MANIFEST.MF") {
                require(compilerModelBridgeClass(name, families)) { "Unallowlisted $label output: $name" }
                names += name
            }
        }
    }
    require("META-INF/MANIFEST.MF" in seen) { "Missing $label manifest" }
    require(families.all { "${it}.class" in names }) { "Missing reviewed $label entry class" }
    return names
}
tasks.register<Jar>("compilerModelApplicationWorkerJar") {
    description = "Package exactly the four reviewed JDK-only model families; never widen the persistent M0 bridge."
    dependsOn(applicationModelWorker.classesTaskName, applicationModelOriginalWireBridge, applicationModelWireBridge)
    archiveFileName.set("ktchecker-compiler-model-application-preview-1.jar")
    destinationDirectory.set(layout.buildDirectory.dir("model-worker-application"))
    manifest { attributes["Main-Class"] = "dev.ktchecker.compilerworker.live.CompilerModelApplicationMain" }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    val originalWireClasses = providers.provider { zipTree(applicationModelOriginalWireBridge.singleFile) }
    val modelWireClasses = providers.provider { zipTree(applicationModelWireBridge.singleFile) }
    // Three complete unfiltered raw inputs: policy filtering cannot hide unknown input bytes from reuse keys.
    inputs.files(applicationModelWorker.output).withPropertyName("unfilteredModelWorkerOutput")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(applicationModelOriginalWireBridge).withPropertyName("unfilteredOriginalWireArchive")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(applicationModelWireBridge).withPropertyName("unfilteredModelWireArchive")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("reviewedOriginalWireFamilies", compilerModelOriginalWireFamilies.sorted())
    inputs.property("reviewedModelBridgeFamilies", compilerModelBridgeFamilies.sorted())
    from(applicationModelWorker.output) { include { it.isDirectory || compilerModelBridgeClass(it.path, compilerModelWorkerFamilies) } }
    from(originalWireClasses) { include { it.isDirectory || compilerModelBridgeClass(it.path, compilerModelIncludedOriginalWireFamilies) } }
    from(modelWireClasses) { include { it.isDirectory || compilerModelBridgeClass(it.path, compilerModelWireFamilies) } }
    doFirst {
        val names = mutableListOf<String>()
        applicationModelWorker.output.asFileTree.visit {
            if (!isDirectory) {
                require(compilerModelBridgeClass(relativePath.pathString, compilerModelWorkerFamilies)) {
                    "Unallowlisted model worker output: ${relativePath.pathString}"
                }
                names += relativePath.pathString
            }
        }
        require(compilerModelWorkerFamilies.all { "${it}.class" in names }) { "Missing reviewed model worker entry class" }
        // Validate the WHOLE unchanged original inventory BEFORE excluding its one known M0-only peer.
        val originalNames = compilerModelArchiveClasses(
            applicationModelOriginalWireBridge.singleFile, compilerModelOriginalWireFamilies, "original wire",
        )
        names += originalNames.filter { compilerModelBridgeClass(it, compilerModelIncludedOriginalWireFamilies) }
        names += compilerModelArchiveClasses(applicationModelWireBridge.singleFile, compilerModelWireFamilies, "model wire")
        require(names.size == names.toSet().size) { "Duplicate model bridge class" }
        require(compilerModelBridgeFamilies.all { "${it}.class" in names }) { "Missing reviewed model bridge entry class" }
    }
}

// Persistent application bridge: JDK-only isolated sources, not the historical finite fixture.
// Merely building/checking never opens this dormant, separately authorized provider route.
val applicationWorker = sourceSets.create("worker")
val applicationWorkerTest = sourceSets.create("workerTest")
val applicationWireBridge by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
// KGP can add stdlib to custom compilations: explicitly use this JDK-only bridge classpath.
applicationWorker.compileClasspath = files(applicationWireBridge)
applicationWorker.runtimeClasspath = files(applicationWorker.output, applicationWireBridge)
dependencies {
    add(applicationWorkerTest.implementationConfigurationName, project(":worker-protocol"))
    add(applicationWorkerTest.implementationConfigurationName, files(applicationWorker.output))
    add(applicationWorkerTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter-api:5.11.4")
    add(applicationWorkerTest.runtimeOnlyConfigurationName, "org.junit.jupiter:junit-jupiter-engine:5.11.4")
    add(applicationWorkerTest.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher:1.11.4")
    add(applicationWireBridge.name, project(":worker-protocol"))
}
val applicationWorkerControls = tasks.register<Test>("workerTest") {
    description = "Pure persistent-worker protocol/lifecycle controls; never an external compiler execution gate."
    group = "verification"
    testClassesDirs = applicationWorkerTest.output.classesDirs
    classpath = applicationWorkerTest.runtimeClasspath
    dependsOn(applicationWorker.classesTaskName, applicationWorkerTest.classesTaskName)
}
// Existing CI calls test rather than check: these authored controls must not be silently omitted.
tasks.named("test") { dependsOn(applicationWorkerControls) }
tasks.named("check") { dependsOn(applicationWorkerControls) }

val applicationBridgeClasses = setOf(
    "dev/ktchecker/workerprotocol/ApplicationWire",
    "dev/ktchecker/workerprotocol/CompilerJobWire",
    "dev/ktchecker/compilerworker/live/CompilerApplicationMain",
    "dev/ktchecker/compilerworker/live/PinnedKotlinCompilerAdapter",
)
fun applicationBridgeClass(path: String): Boolean = applicationBridgeClasses.any {
    path == "$it.class" || (path.startsWith("$it\$") && path.endsWith(".class"))
}
tasks.register<Jar>("compilerApplicationWorkerJar") {
    description = "Package exactly the reviewed JDK-only persistent compiler application bridge."
    dependsOn(applicationWorker.classesTaskName, applicationWireBridge)
    archiveFileName.set("ktchecker-compiler-application-preview-1.jar")
    destinationDirectory.set(layout.buildDirectory.dir("worker-application"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
    manifest { attributes["Main-Class"] = "dev.ktchecker.compilerworker.live.CompilerApplicationMain" }
    val wireClasses = providers.provider { zipTree(applicationWireBridge.singleFile) }
    // Do not let CopySpec filtering hide a new forbidden resource from up-to-date/cache keys.
    inputs.files(applicationWorker.output).withPropertyName("unfilteredWorkerOutput")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(applicationWireBridge).withPropertyName("unfilteredWireArchive")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    from(applicationWorker.output) { include { it.isDirectory || applicationBridgeClass(it.path) } }
    from(wireClasses) { include { it.isDirectory || applicationBridgeClass(it.path) } }
    doFirst {
        // Reject rather than silently drop an accidental host/test/third-party dependency or resource.
        val names = mutableListOf<String>()
        applicationWorker.output.asFileTree.visit {
            if (!isDirectory) {
                require(applicationBridgeClass(relativePath.pathString)) { "Unallowlisted worker output: ${relativePath.pathString}" }
                names += relativePath.pathString
            }
        }
        wireClasses.get().visit {
            if (!isDirectory && relativePath.pathString != "META-INF/MANIFEST.MF") {
                require(applicationBridgeClass(relativePath.pathString)) { "Unallowlisted wire output: ${relativePath.pathString}" }
                names += relativePath.pathString
            }
        }
        require(names.size == names.toSet().size) { "Duplicate bridge class" }
        require(applicationBridgeClasses.all { "$it.class" in names }) { "Missing reviewed bridge entry class" }
    }
}
// A separately explicit current445 observational route; never widen/reinterpret legacy443 tasks.
// Ordinary test/check may compile its source but cannot execute the native/compiler integration.
tasks.named<Test>("test") {
    exclude("**/CompilerCurrent445FixtureIntegrationTest.class")
    useJUnitPlatform { excludeTags("compiler-current445-native-integration") }
}
tasks.register<Test>("compilerCurrent445Fixtures") {
    description = "Explicit bounded nine-case current445 observations; no legacy fallback or rule qualification."
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/CompilerCurrent445FixtureIntegrationTest.class")
    useJUnitPlatform { includeTags("compiler-current445-native-integration") }
    maxParallelForks = 1
    maxHeapSize = "384m"
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst {
        val consent = "current445-nine-observations-not-qualification-v1"
        require(providers.gradleProperty("compilerCurrent445Consent").orNull == consent) {
            "Explicit -PcompilerCurrent445Consent=" + consent + " is required"
        }
        val binding = providers.gradleProperty("compilerCurrent445BindingSha256").orNull
            ?: error("Explicit owner-reviewed -PcompilerCurrent445BindingSha256=<execution-binding-file SHA256> required")
        require(binding.matches(Regex("[0-9a-f]{64}")))
        val selectedHome = Path.of(providers.gradleProperty("compilerFixtureJavaHome").orNull
            ?: error("Explicit -PcompilerFixtureJavaHome=<absolute owner-prepared link-free JDK21 home> required"))
        require(selectedHome.isAbsolute && selectedHome.normalize() == selectedHome && System.getProperty("os.name") == "Linux")
        val heap = providers.gradleProperty("compilerFixtureHeapMiB").orNull
        require(heap == null || heap == "512") { "Current445 compiler heap is fixed at512MiB" }
        fun bounded(name: String, default: String, min: Long, max: Long): String {
            val value = providers.gradleProperty(name).getOrElse(default)
            require(value.matches(Regex("[1-9][0-9]*")) && (value.toLongOrNull() ?: 0) in min..max)
            return value
        }
        systemProperty("ktchecker.compiler.current445.task", consent)
        systemProperty("ktchecker.compiler.current445.consent", consent)
        systemProperty("ktchecker.compiler.current445.bindingSha256", binding)
        systemProperty("ktchecker.compiler.current445.repository", rootProject.projectDir.absolutePath)
        systemProperty("ktchecker.compiler.current445.overallSeconds", bounded("compilerCurrent445OverallSeconds", "1200", 1, 1200))
        systemProperty("ktchecker.compiler.current445.perJobSeconds", bounded("compilerCurrent445PerJobSeconds", "90", 1, 90))
        systemProperty("ktchecker.compiler.current445.evidenceMiB", bounded("compilerCurrent445EvidenceMiB", "256", 24, 256))
        systemProperty("ktchecker.compiler.java", selectedHome.resolve("bin/java").toString())
        systemProperty("ktchecker.compiler.heapMiB", "512")
        systemProperty("ktchecker.compiler.classpath", compilerToolchain.asPath)
    }
}
