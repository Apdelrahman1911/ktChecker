package dev.ktchecker.semantic

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Instant
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Explicit fixture task, not an arbitrary compiler/plugin execution endpoint. All jobs are serial. */
object SemanticFixtureRunner {
    @Serializable internal data class ExecutionEvidence(
        val label: String, val snapshotId: String, val analysisKey: String, val command: List<String>,
        val status: String, val exitCode: Int?, val reasons: List<String>, val metrics: AnalysisMetrics,
        val stdoutDigest: String, val stderrDigest: String,
    )
    @Serializable internal data class CaseEvidence(
        val name: String, val passed: Boolean, val assertions: Map<String, Boolean>, val bundle: SemanticFactBundle,
    )
    @Serializable internal data class SuiteEvidence(
        val adapterId: String = SEMANTIC_ADAPTER_ID,
        val state: String, val mode: String, val target: String, val observedAt: String,
        val testedCommit: String?, val runner: Map<String, String>, val artifacts: List<ArtifactIdentity>,
        val cases: List<CaseEvidence>, val executions: List<ExecutionEvidence>, val reasons: List<String>,
        val tupleQualified: Boolean = false, val customRulesQualified: Boolean = false,
        val limitations: List<String> = SEMANTIC_LIMITATIONS + listOf(
            "Executable synthetic M0 fixtures are not corpus, precision, platform, or release qualification.",
            "Only the retained artifact and immutable source digests identify this run; HEAD may include uncommitted changes.",
            "Trusted process groups/read-only copies are cooperative isolation, not an untrusted-code sandbox.",
        ),
    )

    private lateinit var mode: String
    private lateinit var target: String
    private lateinit var resultPath: Path
    private lateinit var resultDirectory: Path
    private lateinit var frozen: FixtureMaterialization
    private lateinit var outputRoot: Path
    private lateinit var java: Path
    private lateinit var javaArtifacts: List<ArtifactIdentity>
    private var projectJavaVersions: Map<String, String> = emptyMap()
    private lateinit var plugin: ArtifactIdentity
    private lateinit var compilerArtifacts: List<ArtifactIdentity>
    private lateinit var compilerClasspath: List<ArtifactIdentity>
    private var nativeHome: Path? = null
    private val executions = mutableListOf<ExecutionEvidence>()
    private val cases = mutableListOf<CaseEvidence>()
    private val artifacts = mutableListOf<ArtifactIdentity>()
    private val problems = mutableListOf<String>()

    @JvmStatic fun main(args: Array<String>) {
        val options = parseFixtureArguments(args)
        mode = options["mode"] ?: "jvm"
        target = options["target"] ?: "jvm"
        require(mode in setOf("jvm", "native"))
        require(if (mode == "jvm") target == "jvm" else target in setOf("iosArm64", "iosSimulatorArm64", "linuxX64"))
        val fixtureRoot = Path.of(requireNotNull(options["fixtures"])).toAbsolutePath().normalize()
        resultPath = Path.of(requireNotNull(options["result"])).toAbsolutePath().normalize()
        resultDirectory = resultPath.parent
        require(resultPath.fileName.toString() != "summary.json") { "Raw results and metadata-only summary must have distinct names" }
        Files.createDirectories(resultDirectory)
        try {
            selectTools(options)
            val work = Files.createTempDirectory("ktchecker-semantic-fixtures-")
            frozen = FixtureSnapshots.materialize(fixtureRoot, work)
            outputRoot = work.resolve("outputs")
            Files.createDirectories(outputRoot)
            val dependencies = compileDependency()
            positive(dependencies)
            negative("unresolved", listOf("negative/Unresolved.kt"), dependencies)
            negative("malformed", listOf("negative/Malformed.kt"), dependencies)
            negative("missing-dependency", positiveSources(), dependencies.filterNot { it.coordinate.startsWith("fixture-dependency:") })
        } catch (e: Exception) {
            problems += problemCode(e, "semantic_fixture_driver_failed")
            // Only a bounded class/message summary from this trusted fixture; no terminal dump.
            System.err.println("Semantic fixture: ${problems.last()}: ${e.message?.take(400)}")
        }
        val successful = problems.isEmpty() && cases.size == 4 && cases.all { it.passed }
        val report = SuiteEvidence(state = if (successful) "passed" else if (executions.isEmpty()) "unavailable" else "failed",
            mode = mode, target = target, observedAt = Instant.now().toString(),
            testedCommit = gitCommit(fixtureRoot.parent.parent), runner = runnerEvidence(), artifacts = artifacts.distinct(),
            cases = cases.toList(), executions = executions.toList(), reasons = problems.distinct())
        val privateEvidence = ContractJson.encodeToString(report) + "\n"
        Files.writeString(resultPath, privateEvidence)
        // CI uploads this explicit metadata projection, never raw commands, facts or source text.
        val summaryPath = resultDirectory.resolve("summary.json")
        Files.writeString(summaryPath, ContractJson.encodeToString(SemanticFixtureSummary.from(report,
            Digests.sha256(privateEvidence))) + "\n")
        println("Semantic facts $mode/$target: ${report.state}; ${cases.count { it.passed }}/${cases.size} cases; " +
            "${executions.size} actual compiler attempts; 0 qualified rules/tuples. Evidence: $resultPath")
        check(successful) { "Semantic fact fixture gate is not satisfied; inspect the structured evidence." }
    }

    private fun selectTools(options: Map<String, String>) {
        val javaHome = options["java-home"]?.let { Path.of(it).toRealPath() }
            ?: Path.of(requireNotNull(System.getProperty("ktchecker.semantic.java"))).toRealPath().parent.parent
        java = javaHome.resolve("bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "").toRealPath()
        javaArtifacts = listOf(java, javaHome.resolve("release"), javaHome.resolve("lib/modules")).map {
            require(Files.isRegularFile(it, NOFOLLOW_LINKS)) { "Selected project JDK input unavailable: ${it.fileName}" }
            ArtifactIdentity("selected-semantic-jdk:${javaHome.relativize(it).toString().replace('\\', '/')}", fileDigest(it), it.toString())
        }
        val release = Properties().apply { Files.newBufferedReader(javaHome.resolve("release")).use { load(it) } }
        projectJavaVersions = mapOf("JAVA_VERSION" to "projectJavaVersion", "JAVA_RUNTIME_VERSION" to "projectJavaRuntimeVersion",
            "IMPLEMENTOR" to "projectJavaVendor").mapNotNull { (property, key) ->
            release.getProperty(property)?.trim('"')?.let { key to it }
        }.toMap()
        val pluginPath = Path.of(requireNotNull(System.getProperty("ktchecker.semantic.plugin"))).toRealPath()
        plugin = ArtifactIdentity("ktchecker:$SEMANTIC_ADAPTER_ID", fileDigest(pluginPath), pluginPath.toString())
        if (mode == "jvm") {
            compilerClasspath = requireNotNull(System.getProperty("ktchecker.semantic.compiler.classpath"))
                .split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it).toRealPath() }.map {
                    ArtifactIdentity("selected-kotlin-compiler:${it.fileName}", fileDigest(it), it.toString())
                }
            require(compilerClasspath.any { Path.of(it.path).fileName.toString() == "kotlin-compiler-embeddable-2.4.21.jar" })
            compilerArtifacts = compilerClasspath
        } else {
            val apple = target.startsWith("ios")
            val host = System.getProperty("os.name").lowercase()
            if (apple && !(host.contains("mac") || host.contains("darwin"))) {
                throw ExecutionProblem("apple_native_execution_host_missing", "No JVM substitution for iOS semantics")
            }
            if (target == "linuxX64" && !host.contains("linux")) throw ExecutionProblem("native_target_host_unavailable", "Exploratory Linux target requires Linux")
            nativeHome = Path.of(options["native-home"] ?: throw ExecutionProblem("semantic_native_home_required", "Select the exact Native 2.4.21 home")).toRealPath()
            val home = requireNotNull(nativeHome)
            // run_konan reads tools/env_blacklist, so script configuration belongs to the
            // selected artifact inventory along with the compiler, properties and target KLIBs.
            val roots = listOf(home.resolve("bin"), home.resolve("tools"), home.resolve("konan"), home.resolve("klib/common"),
                home.resolve("klib/platform/${konanTarget()}"))
            if (roots.any { !Files.isDirectory(it, NOFOLLOW_LINKS) }) {
                throw ExecutionProblem("semantic_native_distribution_incomplete", "Selected target compiler/standard/platform KLIB inputs are unavailable")
            }
            compilerArtifacts = roots.flatMap { root -> Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted().map {
                    ArtifactIdentity("selected-native-2.4.21:${home.relativize(it).toString().replace('\\', '/')}", fileDigest(it), it.toString())
                }.toList()
            } }
            require(compilerArtifacts.any { Path.of(it.path).fileName.toString() == "kotlin-native-compiler-embeddable.jar" })
            compilerClasspath = emptyList()
        }
        artifacts += compilerArtifacts + plugin + javaArtifacts
    }

    private fun standardDependencies(): List<ArtifactIdentity> = if (mode == "native") emptyList() else compilerClasspath.filter {
        Path.of(it.path).fileName.toString() == "kotlin-stdlib-2.4.21.jar" || Path.of(it.path).fileName.toString().startsWith("annotations-")
    }.also { require(it.any { artifact -> Path.of(artifact.path).fileName.toString() == "kotlin-stdlib-2.4.21.jar" }) }

    private fun compileDependency(): List<ArtifactIdentity> {
        val dependencies = standardDependencies()
        val outcome = execute("dependency", listOf("dependency/Library.kt"), dependencies, emptyList(), null)
        check(outcome.successfulExecution && outcome.exitCode == 0) { "Actual dependency compilation did not succeed" }
        val path = requireNotNull(outcome.outputDirectory).resolve(if (mode == "jvm") "compiled.jar" else "compiled.klib")
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Actual dependency artifact is unavailable" }
        val artifact = ArtifactIdentity("fixture-dependency:${if (mode == "jvm") "jar" else "klib"}:$target", fileDigest(path), path.toString())
        artifacts += artifact
        return dependencies + artifact
    }

    private fun positiveSources(): List<String> = listOf("sources/commonMain/Api.kt", "sources/commonMain/External.kt",
        "sources/commonMain/Coordinates.kt", "sources/intermediateMain/Intermediate.kt",
        "sources/${if (mode == "jvm") "jvmMain" else "nativeMain"}/Platform.kt")

    private fun positive(dependencies: List<ArtifactIdentity>) {
        val request = request("positive", positiveSources(), dependencies)
        val outcome = execute("positive", positiveSources(), dependencies, fragmentArguments(request), request)
        val bundle = bundle(request, outcome)
        val allSymbols = bundle.shards.flatMap { it.symbols }.associateBy { it.id }
        val allCalls = bundle.shards.flatMap { it.calls }
        val overloads = allCalls.mapNotNull { allSymbols[it.targetSymbolId] }.filter {
            it.qualifiedName == "fixture.semantic.choose" && !it.local
        }
        val relations = bundle.shards.flatMap { it.expectActual }
        val localGenerics = allSymbols.values.filter { it.local && it.kind == "function" && it.typeParameterCount == 1 &&
            it.parameters.singleOrNull()?.type?.kind == "type_parameter" }
        val assertions = linkedMapOf(
            "actual_compiler_succeeded" to (outcome.successfulExecution && outcome.exitCode == 0),
            "supported_fact_vocabulary_complete" to bundle.completeForSupportedVocabulary,
            "both_overloads_resolve_to_distinct_signatures" to (overloads.map { it.id }.toSet().size == 2 &&
                overloads.flatMap { it.parameters }.mapNotNull { it.type.classifierId }.toSet().containsAll(setOf("kotlin.Int", "kotlin.String"))),
            "import_alias_resolves_external_symbol" to allCalls.any { call ->
                allSymbols[call.targetSymbolId]?.qualifiedName == "fixture.external.choose" && sourceText(call.source).startsWith("externalPick(")
            },
            "local_shadow_is_not_top_level_overload" to allCalls.any { call ->
                allSymbols[call.targetSymbolId]?.let { it.local && it.parameters.singleOrNull()?.type?.classifierId == "kotlin.Long" } == true
            },
            "same_spelled_local_generic_owners_are_distinct" to (localGenerics.size == 2 &&
                localGenerics.map { it.parameters.single().type.parameterOwner }.toSet().size == 2 &&
                localGenerics.all { symbol -> allCalls.any { it.targetSymbolId == symbol.id } }),
            "separate_binary_dependency_call_resolved" to allSymbols.values.any {
                it.qualifiedName == "fixture.dependency.libraryCall" && it.source == null && it.parameters.singleOrNull()?.type?.classifierId == "kotlin.String"
            },
            "inferred_dependency_generic_type_resolved" to allCalls.any {
                it.resultType.classifierId == "fixture.dependency.Resource" &&
                    it.resultType.arguments.singleOrNull()?.type?.classifierId == "kotlin.String"
            },
            "intermediate_actual_matches_common_expect" to relations.any {
                it.matching == "matched_candidate" && it.actualSourceSet == "intermediateMain" && it.expectSourceSet == "commonMain" &&
                    allSymbols[it.actualSymbolId]?.qualifiedName == "fixture.semantic.platformName"
            },
            "actual_typealias_expansion_is_semantic" to allSymbols.values.any {
                it.kind == "type_alias" && it.qualifiedName == "fixture.semantic.AliasName" &&
                    it.aliasExpansion?.classifierId == "fixture.semantic.RealName" && "actual" in it.flags
            },
            "resolved_annotation_identity" to allSymbols.values.any {
                it.qualifiedName == "fixture.semantic.commonUse" && "fixture.semantic.Important" in it.annotationClassIds
            },
            "CRLF_UTF8_call_span_exact" to allCalls.any {
                it.source.path.endsWith("Coordinates.kt") && sourceText(it.source) == "choose(\"é🙂\")"
            },
            "native_is_not_jvm_substitution" to (mode != "native" || bundle.shards.any { it.compilerContext.platform.contains("Native", true) }),
            "facts_do_not_qualify_rules" to (!bundle.customRulesQualified && !bundle.tupleQualified),
        )
        recordCase("positive", assertions, bundle)
    }

    private fun negative(label: String, sources: List<String>, dependencies: List<ArtifactIdentity>) {
        val request = request(label, sources, dependencies)
        val outcome = execute(label, sources, dependencies, fragmentArguments(request), request)
        val bundle = bundle(request, outcome)
        val diagnostics = outcome.stdout + "\n" + outcome.stderr
        val assertions = linkedMapOf(
            "actual_compiler_rejected_source" to (outcome.successfulExecution && outcome.exitCode == 1),
            "compiler_failure_not_successful_facts" to (!bundle.completeForSupportedVocabulary && "semantic_compilation_failed" in bundle.reasons),
            "source_error_not_process_failure" to if (label == "malformed") diagnostics.contains("SYNTAX") else diagnostics.contains("UNRESOLVED_REFERENCE"),
            "no_guessed_missing_function_symbol" to bundle.shards.flatMap { it.symbols }.none { it.qualifiedName.contains("missingLibraryFunction") },
        )
        if (label == "unresolved") assertions["explicit_unknown_or_unvisited_scope"] =
            bundle.shards.any { shard -> shard.unknown.any { it.reason == "unresolved_or_error_reference" } } || "semantic_sources_not_visited" in bundle.reasons
        if (label == "missing-dependency") assertions["missing_binary_not_a_fabricated_resolved_target"] =
            bundle.shards.flatMap { it.symbols }.none { it.qualifiedName == "fixture.dependency.libraryCall" }
        recordCase(label, assertions, bundle)
    }

    private fun recordCase(label: String, assertions: Map<String, Boolean>, bundle: SemanticFactBundle) {
        val evidence = CaseEvidence(label, assertions.values.all { it }, assertions, bundle)
        cases += evidence
        Files.writeString(resultDirectory.resolve("$label-facts.json"), ContractJson.encodeToString(evidence) + "\n")
    }

    private fun request(label: String, paths: List<String>, dependencies: List<ArtifactIdentity>): SemanticFactsRequest {
        val leaf = if (mode == "jvm") "jvmMain" else "nativeMain"
        val positive = label in setOf("positive", "missing-dependency")
        val sets = if (positive) listOf(SemanticSourceSet("commonMain"), SemanticSourceSet("intermediateMain", listOf("commonMain")),
            SemanticSourceSet(leaf, listOf("intermediateMain"))) else listOf(SemanticSourceSet("negative"))
        val sourceSets = if (positive) mapOf("sources/commonMain/" to "commonMain", "sources/intermediateMain/" to "intermediateMain",
            "sources/$leaf/" to leaf) else mapOf("negative/" to "negative")
        val inputs = paths.map { path ->
            val entry = frozen.manifest.files.single { it.path == path }
            SemanticSource(path, entry.digest, entry.size, sourceSets.entries.single { path.startsWith(it.key) }.value)
        }
        val toolchainId = Digests.sha256(ContractJson.encodeToString(compilerArtifacts + javaArtifacts + plugin))
        val planId = Digests.sha256("$SEMANTIC_ADAPTER_ID|$target|$label|${frozen.manifest.snapshotId}|$toolchainId|" +
            ContractJson.encodeToString(sets) + ContractJson.encodeToString(dependencies) + commonArguments().joinToString("\u0000"))
        return SemanticFactsRequest(frozen.manifest.snapshotId, planId, toolchainId, compilerArtifacts, plugin,
            frozen.root.toString(), inputs, sets, if (positive) leaf else "negative", target, "semantic-fixture-$label",
            compilerArguments = commonArguments(), dependencyArtifacts = dependencies)
    }

    private fun fragmentArguments(request: SemanticFactsRequest): List<String> = listOf("-Xmulti-platform", "-Xexpect-actual-classes",
        "-Xfragments=" + request.sourceSets.joinToString(",") { it.id }) + request.sources.map {
        "-Xfragment-sources=${it.sourceSet}:{snapshot}/${it.path}"
    } + request.sourceSets.flatMap { set -> set.dependsOn.map { "-Xfragment-refines=${set.id}:$it" } }

    private fun commonArguments(): List<String> = (if (mode == "jvm") listOf("-version") else emptyList()) +
        listOf("-language-version", "2.4", "-api-version", "2.4", "-Xrender-internal-diagnostic-names")

    private fun execute(label: String, sources: List<String>, dependencies: List<ArtifactIdentity>,
                        fragmentArgs: List<String>, request: SemanticFactsRequest?): ProcessOutcome {
        val pluginArgs = request?.let { listOf("-Xplugin={output}/semantic-plugin.jar", "-P",
            "plugin:$SEMANTIC_PLUGIN_ID:request={output}/semantic-request.json", "-P",
            "plugin:$SEMANTIC_PLUGIN_ID:request-digest=${it.digest()}", "-P", "plugin:$SEMANTIC_PLUGIN_ID:output={output}/facts") }.orEmpty()
        val dependencyArgs = if (mode == "jvm") listOf("-classpath", dependencies.indices.joinToString(File.pathSeparator) { "{output}/dependencies/$it.jar" })
            else dependencies.indices.flatMap { listOf("-library", "{output}/dependencies/$it.klib") }
        val compilerArgs = commonArguments() + if (mode == "jvm") listOf("-no-stdlib", "-no-reflect", "-module-name", "semantic_fixture_$label",
            "-d", "{output}/compiled.jar") else listOf("-produce", "library", "-target", konanTarget(), "-module-name", "semantic_fixture_$label",
            "-o", "{output}/compiled", "-Xoverride-konan-properties=airplaneMode=true", "-Xcache-directory={output}/native-cache")
        val executable = if (mode == "jvm") java else requireNotNull(nativeHome).resolve("bin/konanc").toRealPath()
        val arguments = (if (mode == "jvm") listOf("-Xmx512m", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir={output}/tmp", "-cp",
            compilerClasspath.indices.joinToString(File.pathSeparator) { "{output}/compiler/$it.jar" }, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler") else emptyList()) +
            compilerArgs + dependencyArgs + fragmentArgs + pluginArgs + sources.map { "{snapshot}/$it" }
        val environment = listOf("DEVELOPER_DIR", "KONAN_DATA_DIR", "HOME").mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap() + mapOf(
            "JAVA_HOME" to java.parent.parent.toString(), "JAVA_OPTS" to "-Xmx512m", "LC_ALL" to "C", "LANG" to "C")
        val execution = TrustedExecution(executable.toString(), arguments, true, fileDigest(executable),
            timeoutMillis = if (mode == "native") 600_000 else 120_000, maxOutputBytes = 2_097_152, environment = environment)
        val context = AnalysisContext(RuleContext(target, request?.leafSourceSet ?: "dependency", ":semantic-fixture", label),
            SEMANTIC_COMPILER_VERSION, dependencies, compilerArgs + fragmentArgs,
            modelDigest = request?.analysisId ?: Digests.sha256("dependency-model:${frozen.manifest.snapshotId}"),
            dependencyDigest = Digests.sha256(ContractJson.encodeToString(dependencies)),
            toolchainDigest = request?.toolchainId ?: Digests.sha256(ContractJson.encodeToString(compilerArtifacts + javaArtifacts)),
            configurationDigest = request?.digest() ?: Digests.sha256("dependency-compile:$mode:$target"),
            fullProject = false, compilationFiles = sources)
        val input = AnalysisInput(frozen.manifest, frozen.root.toString(), context, execution = execution, outputRoot = outputRoot.toString())
        val selected = compilerArtifacts + javaArtifacts + dependencies + plugin
        val verify = {
            frozen.verify()
            selected.forEach { artifact ->
                if (fileDigest(Path.of(artifact.path)) != artifact.digest) throw ExecutionProblem("semantic_selected_artifact_changed", "Selected artifact changed")
            }
        }
        val authority = ExactExecutionAuthority(ExecutionPermit(execution, input.snapshot.snapshotId, frozen.root, input.analysisKey(), verify) { output ->
            fun copy(artifact: ArtifactIdentity, target: Path) {
                Files.createDirectories(target.parent)
                Files.copy(Path.of(artifact.path), target)
                check(fileDigest(target) == artifact.digest) { "Isolated artifact copy digest differs" }
            }
            compilerClasspath.forEachIndexed { index, artifact -> copy(artifact, output.resolve("compiler/$index.jar")) }
            dependencies.forEachIndexed { index, artifact -> copy(artifact, output.resolve("dependencies/$index.${if (mode == "jvm") "jar" else "klib"}")) }
            if (request != null) {
                copy(plugin, output.resolve("semantic-plugin.jar"))
                Files.writeString(output.resolve("semantic-request.json"), ContractJson.encodeToString(request), CREATE_NEW)
            }
            Files.createDirectory(output.resolve("native-cache"))
        })
        val outcome = IsolatedProcessRunner().run(input, authority)
        executions += ExecutionEvidence(label, input.snapshot.snapshotId, input.analysisKey(), outcome.command, outcome.status, outcome.exitCode,
            outcome.reasons, outcome.metrics, Digests.sha256(outcome.stdout), Digests.sha256(outcome.stderr))
        Files.writeString(resultDirectory.resolve("$label-stdout.txt"), outcome.stdout)
        Files.writeString(resultDirectory.resolve("$label-stderr.txt"), outcome.stderr)
        return outcome
    }

    private fun bundle(request: SemanticFactsRequest, outcome: ProcessOutcome): SemanticFactBundle {
        val directory = outcome.outputDirectory?.resolve("facts")
        val shards = if (directory != null && Files.isDirectory(directory, NOFOLLOW_LINKS)) Files.list(directory).use { paths ->
            val files = paths.sorted().limit((request.sources.size * 4 + 1).toLong()).toList()
            require(files.size <= request.sources.size * 4) { "Fact shard count exceeds request" }
            files.map { path -> ContractJson.decodeFromString<SemanticFactShard>(outcome.report("facts/${path.fileName}", 16_777_216).toString(Charsets.UTF_8)) }
        } else emptyList()
        return SemanticFactsValidation.assemble(request, shards, outcome.exitCode, outcome.successfulExecution, outcome.reasons) {
            Files.readAllBytes(frozen.root.resolve(it.path))
        }
    }

    private fun sourceText(span: SemanticSpan): String {
        val bytes = Files.readAllBytes(frozen.root.resolve(span.path))
        check(Digests.sha256(bytes) == span.digest)
        return bytes.copyOfRange(span.startByte, span.endByte).toString(Charsets.UTF_8)
    }

    private fun konanTarget(): String = when (target) {
        "iosArm64" -> "ios_arm64"
        "iosSimulatorArm64" -> "ios_simulator_arm64"
        "linuxX64" -> "linux_x64"
        else -> error("Not a selected Native target")
    }

    private fun runnerEvidence(): Map<String, String> = mapOf("os" to System.getProperty("os.name"),
        "architecture" to System.getProperty("os.arch"), "fixtureHostJava" to System.getProperty("java.version")) + projectJavaVersions +
        listOf("ImageOS", "ImageVersion", "RUNNER_OS", "RUNNER_ARCH", "DEVELOPER_DIR", "GITHUB_SHA", "GITHUB_RUN_ID")
            .mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap()

    private fun gitCommit(repository: Path): String? = runCatching {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(repository.toFile()).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); null }
        else process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 && it.matches(Regex("[0-9a-f]{40,64}")) }
    }.getOrNull()
}
