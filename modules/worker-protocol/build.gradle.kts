// Shared detached application DTOs/framing only; the production JAR depends on the JDK alone.
plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// Additive model DTO profile. The original main JAR and its raw inventory stay unchanged.
val compilerModelWire = sourceSets.create("modelWire")
val compilerModelWireTest = sourceSets.create("modelWireTest")
val compilerModelOriginalWireJar = tasks.named<Jar>("jar")
val compilerModelWireTestLibraries = configurations.create("compilerModelWireTestLibraries") {
    isCanBeConsumed = false; isCanBeResolved = true
}
compilerModelWire.compileClasspath = files(compilerModelOriginalWireJar.flatMap { it.archiveFile })
compilerModelWire.runtimeClasspath = files(compilerModelWire.output, compilerModelOriginalWireJar.flatMap { it.archiveFile })
compilerModelWireTest.compileClasspath = files(
    compilerModelWire.output, compilerModelOriginalWireJar.flatMap { it.archiveFile }, compilerModelWireTestLibraries,
)
compilerModelWireTest.runtimeClasspath = files(compilerModelWireTest.output, compilerModelWireTest.compileClasspath)
dependencies.add(compilerModelWireTestLibraries.name, "org.junit.jupiter:junit-jupiter-api:5.11.4")
dependencies.add(compilerModelWireTestLibraries.name, "org.junit.jupiter:junit-jupiter-engine:5.11.4")
dependencies.add(compilerModelWireTestLibraries.name, "org.junit.platform:junit-platform-launcher:1.11.4")

val compilerModelWireControls = tasks.register<Test>("modelWireTest") {
    description = "Pure detached model-wire controls; no compiler/provider/native execution."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = compilerModelWireTest.output.classesDirs
    classpath = compilerModelWireTest.runtimeClasspath
    dependsOn(compilerModelWire.classesTaskName, compilerModelWireTest.classesTaskName)
}
tasks.named("test") { dependsOn(compilerModelWireControls) }
tasks.named("check") { dependsOn(compilerModelWireControls) }

val compilerModelWireFamily = "dev/ktchecker/workerprotocol/CompilerModelWire"
fun compilerModelWireClass(path: String): Boolean =
    path == "$compilerModelWireFamily.class" ||
        (path.startsWith("${compilerModelWireFamily}\$") && path.endsWith(".class") &&
            path.indexOf('/', compilerModelWireFamily.length) == -1 &&
            path.indexOf('\\', compilerModelWireFamily.length) == -1)
val compilerModelWireJar = tasks.register<Jar>("modelWireJar") {
    description = "Package only the isolated JDK-only CompilerModelWire DTO family; never alter the original wire JAR."
    dependsOn(compilerModelWire.classesTaskName)
    archiveFileName.set("ktchecker-compiler-model-wire-preview-1.jar")
    destinationDirectory.set(layout.buildDirectory.dir("model-wire"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL
    inputs.files(compilerModelWire.output).withPropertyName("unfilteredModelWireOutput")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("reviewedModelWireFamily", compilerModelWireFamily)
    from(compilerModelWire.output) { include { it.isDirectory || compilerModelWireClass(it.path) } }
    doFirst {
        val names = mutableListOf<String>()
        compilerModelWire.output.asFileTree.visit {
            if (!isDirectory) {
                require(compilerModelWireClass(relativePath.pathString)) {
                    "Unallowlisted model wire output: ${relativePath.pathString}"
                }
                names += relativePath.pathString
            }
        }
        require(names.size == names.toSet().size) { "Duplicate model wire class" }
        require("$compilerModelWireFamily.class" in names) { "Missing reviewed model wire entry class" }
    }
}
val modelWireElements = configurations.create("modelWireElements") {
    isCanBeConsumed = true; isCanBeResolved = false; isTransitive = false
}
artifacts.add(modelWireElements.name, compilerModelWireJar)
