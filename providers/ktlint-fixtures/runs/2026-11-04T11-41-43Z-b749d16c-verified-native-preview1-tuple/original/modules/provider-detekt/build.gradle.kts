plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":provider-runtime"))
    testImplementation(project(":snapshot"))
}

// Independently authored corpus: opt-in only, never part of ordinary test/check.
val corpusTestSources = sourceSets.create("corpusTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
kotlin.sourceSets.named(corpusTestSources.name) {
    kotlin.srcDir("src/corpusTest/kotlin")
}
configurations[corpusTestSources.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[corpusTestSources.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("corpusTest") {
    group = "verification"
    description = "Run the separate Detekt syntax corpus when explicitly enabled."
    testClassesDirs = corpusTestSources.output.classesDirs
    classpath = corpusTestSources.runtimeClasspath
    systemProperty("ktchecker.detekt.corpus.integration", providers.gradleProperty("detektCorpusIntegration").getOrElse("false"))
}

tasks.withType<Test>().configureEach {
    systemProperty("ktchecker.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty("ktchecker.detekt.integration", providers.gradleProperty("detektIntegration").getOrElse("false"))
    providers.gradleProperty("libraryFixtureEvidenceRoot").orNull?.let {
        systemProperty("ktchecker.libraryFixtureEvidenceRoot", rootProject.file(it).absolutePath)
    }
}
