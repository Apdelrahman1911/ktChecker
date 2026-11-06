package dev.ktchecker.cli

import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.contracts.*
import dev.ktchecker.projectmodel.*
import java.nio.file.Path

/** A NEW deliberately no-test experiment, NOT a reduction/repair of an earlier main+test attempt.
 * The real public compilation container is configured before capture/export. Test sourceSets,
 * orphan tasks, associations and every residual exporter/importer gap remain actual raw facts.
 */
internal object RealModelFixture {
    const val CASE = "ktc_live_model_no_test_feasibility"
    const val COMPILATION = ":||main"
    const val TASK = ":compileKotlin"
    const val RULE = "ktc.compiler.common.unresolved_reference"
    const val USE = "src/main/kotlin/Use.kt"
    const val DECLARATIONS = "src/main/kotlin/Declarations.kt"
    const val USE_TEXT = "package feasibility\n\nfun useAcrossFiles(): String = declaredAcrossFiles()\n"
    const val BEFORE_TEXT = "package feasibility\n\nfun unrelatedDeclaration(): String = \"unrelated\"\n"
    const val AFTER_TEXT = "package feasibility\n\nfun declaredAcrossFiles(): String = \"fixed-across-files\"\n"
    private val settings = """
        pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
        rootProject.name = 'ktc_live_model_no_test_feasibility'
    """.trimIndent() + "\n"
    private val build = """
        import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
        import org.jetbrains.kotlin.gradle.dsl.JvmTarget
        plugins { id 'org.jetbrains.kotlin.jvm' version '2.4.21' }
        repositories { mavenCentral() }
        kotlin { jvmToolchain(21) }
        // NEW no-test feasibility input. Never mutate an earlier fixture/export or its issues.
        def actualCompilations = kotlin.target.compilations
        def actualTestCompilation = actualCompilations.getByName('test')
        if (!actualCompilations.remove(actualTestCompilation)) {
            throw new GradleException('No-test feasibility compilation removal was rejected')
        }
        if (actualCompilations.toList().collect { it.name }.sort() != ['main']) {
            throw new GradleException('No-test feasibility compilation domain was not established')
        }
        tasks.withType(KotlinCompile).configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
            compilerOptions.moduleName.set('ktc_live_model_no_test_feasibility')
        }
        gradle.projectsEvaluated {
            if (actualCompilations.toList().collect { it.name }.sort() != ['main']) {
                throw new GradleException('No-test feasibility compilation domain changed')
            }
            // Actual public task configuration, not a report filter or guessed scripting property.
            tasks.withType(KotlinCompile).configureEach {
                pluginClasspath.setFrom([])
                pluginClasspath.disallowChanges()
            }
        }
    """.trimIndent() + "\n"
    private val properties = "org.gradle.java.installations.auto-detect=false\norg.gradle.java.installations.auto-download=false\n"

    fun files(phase: Int): Map<String, String> {
        realExpect(phase in 1..2, "real_model_fixture_phase")
        return sortedMapOf("settings.gradle" to settings, "build.gradle" to build, "gradle.properties" to properties,
            USE to USE_TEXT, DECLARATIONS to if (phase == 1) BEFORE_TEXT else AFTER_TEXT)
    }
    fun manifest(phase: Int): List<ModelFile> = files(phase).map { (path, text) ->
        val bytes = text.toByteArray(Charsets.UTF_8); ModelFile(path, Digests.sha256(bytes), bytes.size.toLong())
    }
    fun verifySnapshot(snapshot: SnapshotManifest, phase: Int) {
        realExpect(snapshot.files.map { ModelFile(it.path, it.digest, it.size) } == manifest(phase) &&
            snapshot.scopeExclusions.isEmpty() && snapshot.unknownInputs.isEmpty() && snapshot.declaredRoots == listOf("project") &&
            snapshot.captureKind == "immutable_overlay", "real_model_fixture_capture_changed")
    }

    /** Exact independently authored source/config domain; no successful compiler/rule inference.
     * No model object is rebuilt here and every original issue/raw compilation survives import.
     * A remnant test compilation may import as unavailable; it cannot execute or disappear.
     */
    fun verifyGraph(snapshot: SnapshotManifest, phase: Int, graph: GradleProjectExport,
        inputs: RealModelOwnerInputs, installation: KotlinJvmToolchain, output: Path) {
        verifySnapshot(snapshot, phase)
        realExpect(graph.gradleVersion == "9.6.1" && graph.modules.size == 1, "real_model_fixture_modules_changed")
        val module = graph.modules.single()
        realExpect(module.id == ":" && module.directory.isEmpty() && module.buildFile == "build.gradle" &&
            "org.jetbrains.kotlin.jvm" in module.pluginIds && module.pluginIds.none {
                it == "org.jetbrains.kotlin.multiplatform" || it == "org.jetbrains.kotlin.android" || it.startsWith("com.android.")
            }, "real_model_fixture_modules_changed")
        val captured = manifest(phase).associateBy { it.path }
        realExpect(graph.configurationInputs == captured.values.filter { !it.path.endsWith(".kt") }, "real_model_fixture_configuration_changed")
        realExpect(graph.includedBuilds.isEmpty() && graph.generatedInputs.isEmpty(), "real_model_fixture_external_or_generated_domain")
        realExpect(graph.sourceSets.map { it.name }.distinct().size == graph.sourceSets.size &&
            graph.sourceSets.any { it.name == "main" } && graph.sourceSets.all { it.name in setOf("main", "test") },
            "real_model_fixture_source_sets_changed")
        graph.sourceSets.forEach { set ->
            realExpect(set.id == ":|${set.name}" && set.moduleId == ":" && set.dependsOn.isEmpty() &&
                set.sourceRoots == listOf("src/${set.name}/kotlin") && set.resourceRoots == listOf("src/${set.name}/resources") &&
                set.resources.isEmpty() && set.sources == (if (set.name == "main") listOf(captured.getValue(DECLARATIONS), captured.getValue(USE)) else emptyList()),
                "real_model_fixture_source_domain_changed")
        }
        realExpect(graph.compilations.isNotEmpty() && graph.compilations.any { it.id == COMPILATION }, "real_model_fixture_main_missing")
        graph.compilations.forEach { compilation ->
            realExpect(compilation.moduleId == ":" && compilation.target.isEmpty() && compilation.platform == "jvm" &&
                compilation.name in setOf("main", "test") && compilation.id == ":||${compilation.name}" &&
                compilation.defaultSourceSetId == ":|${compilation.name}" && compilation.sourceSetIds == listOf(":|${compilation.name}") &&
                compilation.sourceFiles == (if (compilation.name == "main") listOf(captured.getValue(DECLARATIONS), captured.getValue(USE)) else emptyList()),
                "real_model_fixture_compilation_domain_changed")
            val chain = compilation.toolchain
            // Missing toolchain remains an importer gap rather than a fabricated selection.
            if (chain != null) {
                realExpect(chain.kind == "jvm" && chain.compilerVersion == "2.4.21", "real_model_fixture_toolchain_changed")
                realExpect(chain.runtimeArtifacts.map { it.path }.sorted() == inputs.jdkPaths, "real_model_fixture_whole_jdk_mismatch")
                realExpect(chain.compilerArtifacts.map { ArtifactIdentity(it.coordinate, requireNotNull(it.digest), it.path) } == installation.compilerClasspath &&
                    chain.compilerArtifacts.map { it.size } == RealModelRuntimeRoster.compilerArtifacts.map { it.bytes }, "real_model_fixture_real14_mismatch")
            }
            if (compilation.id == COMPILATION) {
                realExpect(compilation.compileTaskPath == TASK && compilation.dependencyConfiguration == "compileClasspath" &&
                    compilation.classpath == listOf(inputs.selectedDependency(8), inputs.selectedDependency(9)).map { artifact ->
                        // Task classpath export labels are resolved:<filename>; roster Maven labels describe the compiler closure.
                        artifact.copy(coordinate = "resolved:${Path.of(artifact.path).fileName}")
                    }, "real_model_fixture_main_classpath_changed")
            }
        }
        val external = inputs.exporterArtifact(output)
        realExpect(graph.externalConfigurationArtifacts.size == 1 && graph.externalConfigurationArtifacts.single().let {
            it.path == external.path && it.digest == external.digest && it.size == external.size
        }, "real_model_fixture_external_configuration_changed")
        graph.buildscriptArtifacts.filter { it.digest != null }.forEach(inputs::verifyArtifact)
    }
}
