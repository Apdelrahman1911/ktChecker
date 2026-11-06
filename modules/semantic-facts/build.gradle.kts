import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.testing.Test

dependencies {
    implementation(project(":contracts"))
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
    testImplementation(project(":snapshot"))
    testImplementation(project(":provider-runtime"))
}

kotlin.compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")

val compilerToolchain by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { compilerToolchain("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21") }

// Separate, closed JVM COMPILER inputs. This configuration does not discover or execute tests,
// and its resolved metadata is not a production artifact/model/test-policy receipt.
val emptyAssertionFixtureLibraries by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm)
    }
}
dependencies {
    emptyAssertionFixtureLibraries("org.jetbrains.kotlin:kotlin-test:2.4.21") { version { strictly("2.4.21") } }
    emptyAssertionFixtureLibraries("org.jetbrains.kotlin:kotlin-test-junit5:2.4.21") { version { strictly("2.4.21") } }
    emptyAssertionFixtureLibraries("org.jetbrains.kotlin:kotlin-stdlib:2.4.21") { version { strictly("2.4.21") } }
    emptyAssertionFixtureLibraries("org.junit.jupiter:junit-jupiter-api:5.11.4") { version { strictly("5.11.4") } }
}

tasks.named<Test>("test") {
    // Enables an exact source-copy equality test; not a search for a checkout or test-source role.
    systemProperty("ktchecker.semantic.fixture.checkout", rootProject.layout.projectDirectory.asFile.absolutePath)
}

val pluginJar by tasks.registering(Jar::class) {
    description = "Version-isolated FIR fact plugin; deliberately contains no compiler implementation."
    archiveClassifier.set("kotlin-2.4.21-plugin")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    manifest.attributes("Implementation-Title" to "ktChecker FIR semantic facts spike",
        "Implementation-Version" to "kotlin-2.4.21/v1")
}

tasks.register<JavaExec>("semanticFixtures") {
    description = "Execute selected, exact compiler+plugin fixtures; does not qualify custom rules."
    group = "verification"
    dependsOn(tasks.named("testClasses"), pluginJar)
    classpath = sourceSets.test.get().runtimeClasspath
    // Closed feature selector: a successful default/core run does not exercise opt-in facts.
    val vocabulary = providers.gradleProperty("semanticVocabulary").getOrElse("core")
    mainClass.set(when (vocabulary) {
        "core" -> "dev.ktchecker.semantic.SemanticFixtureRunner"
        "direct-int-literal-v1" -> "dev.ktchecker.semantic.DirectIntLiteralFixtureRunner"
        "empty-assertion-v1" -> "dev.ktchecker.semantic.EmptyAssertionFixtureRunner"
        else -> throw GradleException("Unsupported semanticVocabulary '$vocabulary'; expected core, direct-int-literal-v1 or empty-assertion-v1")
    })
    maxHeapSize = "384m"
    doFirst {
        require(vocabulary == "core" ||
            (providers.gradleProperty("semanticMode").getOrElse("jvm") == "jvm" &&
                providers.gradleProperty("semanticTarget").getOrElse("jvm") == "jvm")) {
            "semanticVocabulary '$vocabulary' supports only the exact JVM fixture mode/target"
        }
        if (vocabulary == "empty-assertion-v1") {
            // Coordinates come from Gradle's selected module identities, NEVER from JAR filenames.
            // Include the complete resolved compile-artifact vector, including required transitives.
            val selected = emptyAssertionFixtureLibraries.resolvedConfiguration.resolvedArtifacts.map { artifact ->
                require(artifact.type == "jar" && artifact.extension == "jar" && artifact.classifier.isNullOrEmpty()) {
                    "Empty-assertion compiler inputs require unclassified JVM JAR artifacts"
                }
                val module = artifact.moduleVersion.id
                val coordinate = "${module.group}:${module.name}:${module.version}"
                val path = artifact.file.canonicalFile.absolutePath
                require(listOf(coordinate, path).none { value -> value.any(Char::isISOControl) }) {
                    "Empty-assertion artifact metadata cannot contain control characters"
                }
                "$coordinate\t$path"
            }.sorted()
            require(selected.size in 4..8 && selected.distinct().size == selected.size)
            systemProperty("ktchecker.semantic.emptyAssertion.libraries", selected.joinToString("\n"))
        }
        systemProperty("ktchecker.semantic.compiler.classpath", compilerToolchain.asPath)
        systemProperty("ktchecker.semantic.java", javaLauncher.get().executablePath.asFile.absolutePath)
        systemProperty("ktchecker.semantic.plugin", pluginJar.get().archiveFile.get().asFile.absolutePath)
        args = listOf(
            "--fixtures", rootProject.file("providers/semantic-fixtures").absolutePath,
            "--mode", providers.gradleProperty("semanticMode").getOrElse("jvm"),
            "--target", providers.gradleProperty("semanticTarget").getOrElse("jvm"),
            "--result", providers.gradleProperty("semanticResult")
                .getOrElse(layout.buildDirectory.file("reports/semantic-fixtures/results.json").get().asFile.absolutePath),
        ) + providers.gradleProperty("semanticNativeHome").orNull?.let { listOf("--native-home", it) }.orEmpty() +
            providers.gradleProperty("semanticJavaHome").orNull?.let { listOf("--java-home", it) }.orEmpty() +
            providers.gradleProperty("semanticJvmBinaryOrigins").orNull?.let { listOf("--jvm-binary-origins", it) }.orEmpty()
    }
}
