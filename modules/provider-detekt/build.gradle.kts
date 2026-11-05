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

// Closed selector mirrored by the corpus registry; authoring tests check exact membership.
val detektCorpusCohortNames = setOf(
    "exceptions-core-v1", "iteration-core-v1", "value-core-v1", "opt-in-policy-v1",
    "performance-shapes-v1", "public-contract-policy-v1",
)

tasks.register<Test>("corpusTest") {
    group = "verification"
    description = "Validate separate corpus controls; execute only one explicitly selected bounded syntax cohort."
    inputs.files(rootProject.fileTree("providers/detekt-fixtures/corpus") { include("**/*.json", "**/*.md") })
        .withPropertyName("detektCorpusAuthoredInputs").withPathSensitivity(PathSensitivity.RELATIVE)
    val nativeCorpus = providers.gradleProperty("detektCorpusIntegration").orElse("false")
    outputs.upToDateWhen { nativeCorpus.get() != "true" }
    outputs.doNotCacheIf("Explicit native corpus runs require fresh actual process outcomes") { nativeCorpus.get() == "true" }
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
            require(providers.gradleProperty("detektCorpusCohort").orNull in detektCorpusCohortNames) {
                "Real corpus requires one explicit -PdetektCorpusCohort from $detektCorpusCohortNames; integration alone never selects any cohort or all sixteen rules"
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("ktchecker.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty("ktchecker.detekt.integration", providers.gradleProperty("detektIntegration").getOrElse("false"))
    systemProperty("ktchecker.detekt.model.integration", providers.gradleProperty("detektModelIntegration").getOrElse("false"))
    providers.gradleProperty("libraryFixtureEvidenceRoot").orNull?.let {
        systemProperty("ktchecker.libraryFixtureEvidenceRoot", rootProject.file(it).absolutePath)
    }
}

tasks.named<Test>("test") {
    // Missing -> installed/changed planning artifacts must not reuse a prior skipped control.
    inputs.files(rootProject.fileTree(".plan-deps/detekt") { include("*.jar") })
        .withPropertyName("detektModelPlanningArtifacts").withPathSensitivity(PathSensitivity.RELATIVE)
    val modelNative = providers.gradleProperty("detektModelIntegration").orElse("false")
    outputs.upToDateWhen { modelNative.get() != "true" }
    outputs.doNotCacheIf("Explicit native model LIGHT adapter checks require actual process outcomes") { modelNative.get() == "true" }
}
