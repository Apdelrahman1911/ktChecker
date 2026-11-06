import java.nio.file.Path

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":provider-runtime"))
    implementation(project(":project-model"))
    // Reuse the live whole-JDK identity helper; no compiler execution or 2.4.21 role adapter.
    implementation(project(":provider-compiler"))
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
    "protocol-and-catch-boundaries-v1", // Explicit mixed-cohort selection includes the generic-catch opt-in policy.
    "exception-observability-policy-v1", // Explicit selection acknowledges all three opt-in exception policies.
    "local-declaration-use-v1", "guarded-expression-policy-v1", "constant-and-body-policy-v1", // Each includes explicit opt-in policy.
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
    // Separate Compose owner/API admission is reachable ONLY through its explicit one-rule task.
    exclude("**/ComposeStateEffectsCorpusTest*")
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
                "Real corpus requires one explicit -PdetektCorpusCohort from $detektCorpusCohortNames; integration alone never selects any cohort or all thirty-one rules"
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
    // FULL native execution is available ONLY through the separately consented closed task.
    exclude("**/DetektFullSemanticIntegrationTest*")
    useJUnitPlatform { excludeTags("detekt-full-native") }
    mapOf("detektFullCompiler" to "ktchecker.detekt.full.compiler", "detektFullStdlib" to "ktchecker.detekt.full.stdlib")
        .forEach { (property, key) ->
            val selected = providers.gradleProperty(property).orNull
            if (selected != null) {
                systemProperty(key, selected)
                inputs.file(selected).withPropertyName(property + "PlanningIdentity").withPathSensitivity(PathSensitivity.ABSOLUTE)
            }
        }
    // Installing previously missing real compiler/stdlib bytes must invalidate a skipped
    // planning-only control. These inputs are only read/hash data, never implicitly executed.
    listOf("kotlin-compiler-embeddable", "kotlin-stdlib").forEach { module ->
        inputs.files(fileTree("${System.getProperty("user.home")}/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/$module/2.4.10") { include("**/*.jar") })
            .withPropertyName("detektFullPlanning_" + module.replace('-', '_')).withPathSensitivity(PathSensitivity.ABSOLUTE)
    }
    // Missing -> installed/changed planning artifacts must not reuse a prior skipped control.
    inputs.files(rootProject.fileTree(".plan-deps/detekt") { include("*.jar") })
        .withPropertyName("detektModelPlanningArtifacts").withPathSensitivity(PathSensitivity.RELATIVE)
    val modelNative = providers.gradleProperty("detektModelIntegration").orElse("false")
    outputs.upToDateWhen { modelNative.get() != "true" }
    outputs.doNotCacheIf("Explicit native model LIGHT adapter checks require actual process outcomes") { modelNative.get() == "true" }
}

// No ordinary test/check or generic Detekt integration flag depends on this task.
tasks.register<Test>("fullSemanticTest") {
    group = "verification"
    description = "Exactly seven real Detekt FULL observations on synthetic exact2.4.10 models; never release qualification."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/DetektFullSemanticIntegrationTest*")
    useJUnitPlatform { includeTags("detekt-full-native") }
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Actual FULL process observations cannot reuse cached or up-to-date results") { true }
    systemProperty("ktchecker.detekt.full.task", "fullSemanticTest")
    systemProperty("ktchecker.detekt.full.maxInvocations", "7")
    mapOf(
        "detektFullConsent" to "ktchecker.detekt.full.consent",
        "detektFullJavaHome" to "ktchecker.detekt.full.jdkHome",
        "detektFullCompiler" to "ktchecker.detekt.full.compiler",
        "detektFullStdlib" to "ktchecker.detekt.full.stdlib",
        "detektFullAggregateMillis" to "ktchecker.detekt.full.aggregateMillis",
        "detektFullChildMillis" to "ktchecker.detekt.full.childMillis",
    ).forEach { (property, key) -> providers.gradleProperty(property).orNull?.let { systemProperty(key, it) } }
    doFirst {
        require(providers.gradleProperty("detektFullConsent").orNull == "missing-use-call-seven-2.4.10") {
            "Explicit -PdetektFullConsent=missing-use-call-seven-2.4.10 is required; generic Detekt flags cannot select FULL."
        }
        for (property in listOf("detektFullJavaHome", "detektFullCompiler", "detektFullStdlib")) {
            val raw = providers.gradleProperty(property).orNull ?: error("Explicit -P$property=<absolute owner-selected path> is required; no native fallback")
            val path = Path.of(raw)
            require(path.isAbsolute && path.normalize().toString() == raw) { "$property must be an exact normalized absolute path" }
        }
    }
}

// Separate explicit amendment: not a member/expansion of the closed legacy corpus cohorts.
val detektComposeStateEffectsRuleNames = setOf(
    "ktc.compose.pack.RememberMissing",
    "ktc.compose.pack.RememberContentMissing",
    "ktc.compose.pack.LambdaParameterInRestartableEffect",
    "ktc.compose.pack.ContentSlotReused",
    "ktc.compose.pack.MutableStateAutoboxing",
    "ktc.compose.pack.StateParam",
    "ktc.compose.pack.MutableStateParam",
    "ktc.compose.pack.UnstableCollections",
)

// Never attached to test/check/corpusTest or enabled by generic integration flags.
tasks.register<Test>("composeStateEffectsCorpusTest") {
    group = "verification"
    description = "One explicitly selected Compose rule: exact owner-bound API facets or visible unavailable guards; no release qualification."
    testClassesDirs = corpusTestSources.output.classesDirs
    classpath = corpusTestSources.runtimeClasspath
    include("**/ComposeStateEffectsCorpusTest*")
    useJUnitPlatform { includeTags("detekt-compose-state-effects") }
    filter {
        includeTestsMatching("dev.ktchecker.providers.detekt.ComposeStateEffectsCorpusTest")
        isFailOnNoMatchingTests = true
    }
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Compose source/API controls require fresh owner binding and actual selected process outcomes") { true }
    inputs.files(rootProject.fileTree("providers/detekt-fixtures/corpus/compose-state-effects-v1") {
        include("**/*.json", "**/*.md")
    }).withPropertyName("composeStateEffectsAuthoredInputs").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("ktchecker.detekt.compose.task", "composeStateEffectsCorpusTest")
    mapOf(
        "detektComposeConsent" to "consent",
        "detektComposeRule" to "rule",
        "detektComposeJavaHome" to "jdkHome",
        "detektComposeArtifacts" to "artifacts",
        "detektComposeOwnerCompilation" to "ownerCompilation",
        "detektComposeOwnerCompilationSha256" to "ownerCompilationSha256",
        "detektComposeEvidenceRoot" to "evidenceRoot",
        "detektComposeMaxInvocations" to "maxInvocations",
        "detektComposeAggregateMillis" to "aggregateMillis",
        "detektComposeChildMillis" to "childMillis",
    ).forEach { (property, suffix) ->
        providers.gradleProperty(property).orNull?.let { selected ->
            systemProperty("ktchecker.detekt.compose.$suffix", selected)
            inputs.property(property, selected)
        }
    }
    providers.gradleProperty("detektComposeOwnerCompilation").orNull?.let {
        inputs.file(it).withPropertyName("composeOwnerCompilationDescriptor").withPathSensitivity(PathSensitivity.ABSOLUTE)
    }
    doFirst {
        fun required(property: String): String = providers.gradleProperty(property).orNull
            ?: error("Explicit -P$property is required; this task never assumes, skips or uses a native fallback")
        require(required("detektComposeConsent") == "compose-state-effects-v1-one-rule") {
            "Explicit -PdetektComposeConsent=compose-state-effects-v1-one-rule is required; other integration flags confer no authority"
        }
        val rule = required("detektComposeRule")
        require(rule in detektComposeStateEffectsRuleNames) { "Select exactly one full detektComposeRule ID from $detektComposeStateEffectsRuleNames" }
        listOf("detektComposeJavaHome", "detektComposeArtifacts", "detektComposeOwnerCompilation", "detektComposeEvidenceRoot").forEach { property ->
            val raw = required(property)
            val path = Path.of(raw)
            require(path.isAbsolute && path.normalize().toString() == raw) { "$property must be an exact normalized absolute owner-selected path" }
        }
        require(required("detektComposeOwnerCompilationSha256").matches(Regex("sha256:[0-9a-f]{64}"))) {
            "The complete owner compilation descriptor needs an independently selected SHA256, not a request library tag"
        }
        val maximum = required("detektComposeMaxInvocations").toInt()
        val expected = when (rule) {
            "ktc.compose.pack.LambdaParameterInRestartableEffect", "ktc.compose.pack.MutableStateParam" -> 0
            "ktc.compose.pack.StateParam", "ktc.compose.pack.UnstableCollections" -> 7
            else -> 8
        }
        require(maximum in expected..8) { "Bound must cover this selected rule ($expected native calls), at most8; no aggregate/all-rule selection" }
        val aggregate = required("detektComposeAggregateMillis").toLong()
        val child = required("detektComposeChildMillis").toLong()
        require(aggregate in 1_000..300_000 && child in 1..60_000 && child <= aggregate)
        val evidence = Path.of(required("detektComposeEvidenceRoot"))
        val build = rootProject.layout.buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        require(evidence.startsWith(build) && evidence != build) { "Use a dedicated private evidence subdirectory under this workspace build/; never overwrite a live corpus sidecar" }
    }
}
