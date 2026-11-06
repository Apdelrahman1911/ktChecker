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
        else -> throw GradleException("Unsupported semanticVocabulary '$vocabulary'; expected core or direct-int-literal-v1")
    })
    maxHeapSize = "384m"
    doFirst {
        require(vocabulary == "core" ||
            (providers.gradleProperty("semanticMode").getOrElse("jvm") == "jvm" &&
                providers.gradleProperty("semanticTarget").getOrElse("jvm") == "jvm")) {
            "semanticVocabulary '$vocabulary' supports only the exact JVM fixture mode/target"
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
