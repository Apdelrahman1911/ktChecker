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
    description = "Validate separate corpus controls; execute only the explicitly selected bounded exceptions-core-v1 cohort."
    testClassesDirs = corpusTestSources.output.classesDirs
    classpath = corpusTestSources.runtimeClasspath
    systemProperty("ktchecker.detekt.corpus.integration", providers.gradleProperty("detektCorpusIntegration").getOrElse("false"))
    mapOf(
        "detektCorpusCohort" to "ktchecker.detekt.corpus.cohort",
        "detektCorpusMaxInvocations" to "ktchecker.detekt.corpus.maxInvocations",
        "detektCorpusAggregateMillis" to "ktchecker.detekt.corpus.aggregateMillis",
        "detektCorpusChildMillis" to "ktchecker.detekt.corpus.childMillis",
    ).forEach { (property, key) -> providers.gradleProperty(property).orNull?.let { systemProperty(key, it) } }
    doFirst {
        if (providers.gradleProperty("detektCorpusIntegration").getOrElse("false") == "true") {
            require(providers.gradleProperty("detektCorpusCohort").orNull == "exceptions-core-v1") {
                "Real corpus requires -PdetektCorpusCohort=exceptions-core-v1; integration alone never selects all ten rules"
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("ktchecker.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty("ktchecker.detekt.integration", providers.gradleProperty("detektIntegration").getOrElse("false"))
    providers.gradleProperty("libraryFixtureEvidenceRoot").orNull?.let {
        systemProperty("ktchecker.libraryFixtureEvidenceRoot", rootProject.file(it).absolutePath)
    }
}
