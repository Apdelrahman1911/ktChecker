package dev.ktchecker.compiler

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.serialization.encodeToString

/** Trusted installation descriptor; compiler jars never enter ktChecker's classloader. */
class KotlinJvmToolchain(javaExecutable: Path, compilerClasspath: List<ArtifactIdentity>) {
    val javaExecutable: Path = javaExecutable.toRealPath()
    val compilerClasspath: List<ArtifactIdentity> = Collections.unmodifiableList(compilerClasspath.map { it.copy() })
    val executableDigest: String = fileDigest(this.javaExecutable)
    val digest: String = Digests.sha256(this.compilerClasspath.sortedBy { it.coordinate }.joinToString("\n") {
        "${it.coordinate}:${it.digest}"
    } + "\njava:$executableDigest")

    init {
        require(this.compilerClasspath.any {
            Path.of(it.path).fileName.toString().endsWith("kotlin-compiler-embeddable-2.4.21.jar")
        }) { "An explicit Kotlin2.4.21 compiler installation is required" }
        require(this.compilerClasspath.map { it.path }.toSet().size == this.compilerClasspath.size)
        verify()
    }

    fun verify(checkpoint: () -> Unit = {}) {
        checkpoint()
        require(Files.isExecutable(javaExecutable) && fileDigest(javaExecutable) == executableDigest) {
            "Pinned Java executable changed"
        }
        CompilerSnapshot.classpath(compilerClasspath, checkpoint)
    }
}

/** Qualification is owner-supplied evidence for an exact tuple, never inferred from a registry. */
data class CompilerQualification(
    val ruleId: String,
    val contextId: String,
    val compilerVersion: String,
    val toolchainDigest: String,
    val configurationDigest: String,
    val certificateId: String,
    val modelDigest: String = "",
    val dependencyDigest: String = "",
    val compilerArgumentsDigest: String = "",
)

class KotlinJvmCompilerProvider(
    private val toolchain: KotlinJvmToolchain,
    private val authority: ExecutionAuthority = ExecutionAuthority.DENY,
    private val runner: IsolatedProcessRunner = IsolatedProcessRunner(),
    private val registry: CompilerRuleRegistry = CompilerRuleRegistry.load(),
    qualifications: List<CompilerQualification> = emptyList(),
    private val compilerHeapMiB: Int = 768,
    private val executionObserver: (AnalysisInput, ProcessOutcome) -> Unit = { _, _ -> },
) : AnalysisProvider {
    override val id = CompilerRuleRegistry.PROVIDER_ID
    override val version = CompilerRuleRegistry.VERSION
    override val supportedRuleIds: Set<String> = registry.jvmRuleIds
    val availableMappings: List<CompilerRuleMapping> get() = registry.mappings
    private val qualifications = Collections.unmodifiableList(qualifications.toList())
    private val parser = CompilerDiagnosticParser()

    init {
        require(compilerHeapMiB in 128..2_048) { "Compiler heap must be explicitly bounded" }
        require(this.qualifications.all { it.ruleId in supportedRuleIds }) { "A JVM provider cannot accept a Native qualification" }
    }

    /**
     * Host configuration can select this exact argv and grant a nonserializable
     * ExecutionPermit over its registered snapshot. Returning approved=true here
     * grants nothing: analyze still denies execution without that exact authority.
     */
    fun executionProfile(input: AnalysisInput, timeoutMillis: Long = 60_000, maxOutputBytes: Int = 1_048_576): TrustedExecution {
        val flags = CompilerFlags.validate(input.context.compilerArguments)
        val arguments = mutableListOf("-Xmx${compilerHeapMiB}m", "-Dfile.encoding=UTF-8", "-Duser.language=en",
            "-Duser.country=US", "-Djava.io.tmpdir={output}/tmp", "-cp")
        arguments += toolchain.compilerClasspath.indices.joinToString(File.pathSeparator) { "{output}/compiler/$it.jar" }
        arguments += listOf("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-version", "-Xrender-internal-diagnostic-names",
            "-no-stdlib", "-no-reflect", "-d", "{output}/classes")
        if (input.context.classpath.isNotEmpty()) {
            arguments += "-classpath"
            arguments += input.context.classpath.indices.joinToString(File.pathSeparator) { "{output}/dependencies/$it.jar" }
        }
        arguments += flags
        arguments += CompilerSnapshot.compilationPaths(input).map { "{output}/sources/$it" }
        return TrustedExecution(toolchain.javaExecutable.toString(), Collections.unmodifiableList(arguments), true,
            toolchain.executableDigest, timeoutMillis, maxOutputBytes)
    }

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val started = System.nanoTime()
        val request = freeze(input)
        val required = request.requiredRuleIds.distinct()
        fun unavailable(reason: String, status: String = "unavailable"): ProviderResult = ProviderResult(id, status,
            coverage = Collections.unmodifiableList(required.map { Coverage(it, request.context.scope, "unsupported", reason) }),
            reasons = listOf(reason), providerVersion = version)
        if (cancellation.isCancelled()) return unavailable("cancelled_before_execution", "cancelled")
        if (request.context.scope.target.lowercase() !in JVM_TARGETS) return unavailable("target_not_jvm")
        if (request.context.compilerVersion != version) return unavailable("compiler_version_not_pinned_2_4_21")
        val permit = runCatching { authority.authorize(request) }.getOrNull()
            ?: return unavailable("execution_not_authorized")
        val execution = request.execution ?: return unavailable("execution_profile_missing")
        if (permit.expectedAnalysisKey != request.analysisKey() || permit.snapshotId != request.snapshot.snapshotId ||
            permit.materializedRoot.toAbsolutePath().normalize() != Path.of(request.materializedRoot).toAbsolutePath().normalize()) {
            return unavailable("execution_identity_binding_mismatch")
        }
        val expectedProfile = runCatching {
            executionProfile(request, execution.timeoutMillis, execution.maxOutputBytes)
        }.getOrElse { return unavailable("compiler_settings_unsupported") }
        if (execution != expectedProfile || permit.execution != execution) return unavailable("execution_profile_mismatch")
        val deadline = started + execution.timeoutMillis.coerceIn(1, 3_600_000) * 1_000_000
        val boundedCancellation = CancellationToken { cancellation.isCancelled() || System.nanoTime() >= deadline }
        val checkpoint = {
            cancellation.throwIfCancelled()
            if (System.nanoTime() >= deadline) throw ExecutionProblem("compiler_preparation_timeout", "Compiler preparation deadline elapsed")
        }
        val sources: List<SealedCompilerSource>
        try {
            checkpoint()
            permit.verifySnapshot()
            toolchain.verify(checkpoint)
            CompilerSnapshot.classpath(request.context.classpath, checkpoint)
            require((toolchain.compilerClasspath + request.context.classpath).sumOf { Files.size(Path.of(it.path)) } <=
                CompilerSnapshot.MAX_TOTAL_ARTIFACT_BYTES) { "Combined compiler/dependency artifacts exceed job byte bound" }
            sources = CompilerSnapshot.read(request, checkpoint)
        } catch (_: CancellationException) {
            return unavailable("cancelled_during_compiler_preparation", "cancelled")
        } catch (problem: ExecutionProblem) {
            return unavailable(problem.code)
        } catch (_: Exception) {
            return unavailable("sealed_input_or_toolchain_verification_failed")
        }
        val wrappedPermit = ExecutionPermit(execution, permit.snapshotId, permit.materializedRoot, permit.expectedAnalysisKey, {
            checkpoint()
            permit.verifySnapshot()
            toolchain.verify(checkpoint)
            CompilerSnapshot.classpath(request.context.classpath, checkpoint)
        }, { output ->
            checkpoint()
            permit.prepareOutput(output)
            Files.createDirectories(output.resolve("classes"))
            sources.forEach { source ->
                checkpoint()
                val destination = output.resolve("sources").resolve(source.relativePath)
                Files.createDirectories(destination.parent)
                Files.writeString(destination, source.text, Charsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                check(fileDigest(destination) == source.digest) { "Source copy differs from sealed UTF-8 bytes" }
            }
            copyArtifacts(toolchain.compilerClasspath, output.resolve("compiler"), checkpoint)
            copyArtifacts(request.context.classpath, output.resolve("dependencies"), checkpoint)
        })
        val ran = runner.run(request, ExactExecutionAuthority(wrappedPermit), boundedCancellation)
        val outcome = if (System.nanoTime() >= deadline && !cancellation.isCancelled()) ran.copy(
            status = "failed", reasons = (ran.reasons.filterNot { it == "execution_cancelled" } + "compiler_total_timeout").distinct(),
            cancelled = false, timedOut = true) else ran
        executionObserver(request, outcome)
        val sourcePaths = outcome.outputDirectory?.let { output -> sources.associate { source ->
            output.resolve("sources").resolve(source.relativePath) to
                CompilerSourceIdentity(source.relativePath, source.digest, source.text)
        } } ?: emptyMap()
        val parsed = parser.parse(outcome.stderr + "\n" + outcome.stdout, sourcePaths)
        val reasons = outcome.reasons.toMutableList()
        val raw = parsed.rawDiagnostics.toMutableList()
        val findings = mutableListOf<Finding>()
        val trustedEmission = outcome.successfulExecution && parsed.reportedVersion == version
        if (parsed.reportedVersion != version) reasons += "compiler_version_transport_mismatch"
        if (parsed.malformed) reasons += "unparsed_or_malformed_compiler_output"
        if (outcome.exitCode != 0) reasons += "compiler_build_failed:${outcome.exitCode ?: "not_exited"}"
        val outputDigest = Digests.sha256(outcome.stderr + "\n" + outcome.stdout)
        val key = request.analysisKey()
        // The CLI reports emitted diagnostics, not the checked/suppressed scopes
        // of every absent diagnostic. Until a semantic scoped-suppression ledger
        // is supplied, source annotations conservatively forbid clean certificates.
        val sourceSuppressionObserved = sources.any { SOURCE_SUPPRESSION.containsMatchIn(it.text) }
        if (sourceSuppressionObserved) reasons += "compiler_source_suppression_requires_scoped_coverage_evidence"
        for (diagnostic in parsed.diagnostics) {
            val mapping = registry.byUpstreamId[diagnostic.upstreamId]
            if (!trustedEmission || mapping == null || !mapping.jvmApplicable || diagnostic.location == null) {
                raw += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.upstreamId, diagnostic.location)
                reasons += if (mapping == null) "unmapped_compiler_diagnostic:${diagnostic.upstreamId}" else
                    "untrusted_or_wrong_target_compiler_diagnostic:${diagnostic.upstreamId}"
                continue
            }
            if (mapping.ruleId !in required) {
                raw += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.upstreamId, diagnostic.location)
                reasons += "mapped_diagnostic_outside_selected_rules:${mapping.ruleId}"
                continue
            }
            val fingerprint = Digests.sha256(listOf(mapping.ruleId, request.context.scope.id,
                diagnostic.location.path, diagnostic.location.fileDigest, diagnostic.location.line,
                diagnostic.location.column, diagnostic.message).joinToString("\u0000"))
            findings += Finding(mapping.ruleId, "Compiler ${diagnostic.upstreamId}", diagnostic.message,
                "Actual compiler contract under the selected settings; a warning is not proof of unintended behavior.",
                diagnostic.location, fingerprint, id, request.context.scope, diagnostic.severity,
                mapping.kind, mapping.certainty, evidence = Collections.unmodifiableList(listOf(
                    Evidence("compiler-emission", "Kotlin$version JVM exact diagnostic; phase tag=" +
                        (diagnostic.renderedPhase ?: "not rendered; not inferred"), digest = outputDigest),
                    Evidence("snapshot", "Registered immutable ${request.snapshot.snapshotId}; source batch, not inferred Gradle scope",
                        digest = diagnostic.location.fileDigest),
                    Evidence("compiler-options", "Actual explicit flags/classpath/context bound to $key", digest = key),
                    Evidence("toolchain", "Pinned isolated compiler artifacts and Java executable ${toolchain.javaExecutable}; not loaded into host",
                        digest = toolchain.digest),
                    Evidence("upstream-factory", mapping.source),
                    Evidence("location-precision", "Reported point: one-based line, zero-based UTF-16 column; no inferred end span"),
                )), upstreamId = diagnostic.upstreamId, semanticDigest = key)
        }
        if (raw.any { it.severity != "info" } || parsed.malformed) reasons += "raw_task_diagnostics_prevent_complete_coverage"
        val completeModel = request.context.fullProject && request.context.compilationFiles != null &&
            request.snapshot.scopeExclusions.isEmpty() && request.snapshot.unknownInputs.isEmpty() &&
            listOf(request.context.modelDigest,
            request.context.dependencyDigest, request.context.configurationDigest).all { it != null && Digests.valid(it) } &&
            request.context.toolchainDigest == toolchain.digest
        if (!completeModel) reasons += "resolved_compilation_context_incomplete"
        val coverage = required.map { ruleId ->
            val mapping = registry.byRuleId[ruleId]
            val qualification = qualifications.find { it.ruleId == ruleId && it.contextId == request.context.scope.id &&
                it.compilerVersion == version && it.toolchainDigest == toolchain.digest &&
                it.configurationDigest == request.context.configurationDigest && it.modelDigest == request.context.modelDigest &&
                it.dependencyDigest == request.context.dependencyDigest &&
                it.compilerArgumentsDigest == Digests.sha256(ContractJson.encodeToString(request.context.compilerArguments)) &&
                listOf(it.certificateId, it.modelDigest, it.dependencyDigest, it.compilerArgumentsDigest).all(Digests::valid) }
            val state: String
            val reason: String?
            when {
                mapping == null || !mapping.jvmApplicable -> { state = "unsupported"; reason = "no_jvm_mapping_for_rule" }
                !trustedEmission -> { state = "failed"; reason = "compiler_execution_or_version_untrusted" }
                outcome.exitCode != 0 || raw.isNotEmpty() -> { state = "failed"; reason = "failed_or_incomplete_compiler_task" }
                sourceSuppressionObserved -> { state = "unknown"; reason = "compiler_source_suppression_requires_scoped_coverage_evidence" }
                CompilerFlags.activationGap(mapping, request.context.compilerArguments) != null -> {
                    state = "unknown"; reason = CompilerFlags.activationGap(mapping, request.context.compilerArguments)
                }
                !completeModel -> { state = "unknown"; reason = "resolved_compilation_context_incomplete" }
                qualification == null -> { state = "unqualified"; reason = "exact_rule_context_tuple_not_qualified" }
                else -> { state = if (findings.any { it.ruleId == ruleId }) "findings" else "passed"; reason = null }
            }
            if (reason != null) reasons += "$reason:$ruleId"
            Coverage(ruleId, request.context.scope, state, reason, depth = "build",
                provenance = if (trustedEmission && mapping?.jvmApplicable == true) "executed" else "not_executed",
                certificateId = if (state in setOf("passed", "findings"))
                    Digests.sha256("$id\u0000$key\u0000$outputDigest\u0000${qualification?.certificateId}") else null,
                implemented = mapping?.jvmApplicable == true && trustedEmission &&
                    (qualification != null || findings.any { it.ruleId == ruleId }),
                tested = qualification != null, qualified = qualification != null)
        }
        val status = when {
            outcome.cancelled || cancellation.isCancelled() -> "cancelled"
            !outcome.successfulExecution || outcome.exitCode != 0 -> "failed"
            reasons.isNotEmpty() || coverage.isEmpty() -> "partial"
            else -> "complete"
        }
        return ProviderResult(id, status, Collections.unmodifiableList(findings), Collections.unmodifiableList(coverage),
            Collections.unmodifiableList(reasons.distinct()), Collections.unmodifiableList(raw),
            outcome.metrics.copy(elapsedMillis = (System.nanoTime() - started) / 1_000_000), version)
    }

    private fun copyArtifacts(artifacts: List<ArtifactIdentity>, root: Path, checkpoint: () -> Unit) {
        Files.createDirectories(root)
        artifacts.forEachIndexed { index, artifact ->
            val destination = root.resolve("$index.jar")
            CompilerSnapshot.copyArtifact(artifact, destination, checkpoint)
        }
    }

    private fun freeze(input: AnalysisInput): AnalysisInput = input.copy(
        snapshot = input.snapshot.copy(files = Collections.unmodifiableList(input.snapshot.files.map { it.copy() }),
            declaredRoots = Collections.unmodifiableList(input.snapshot.declaredRoots.toList()),
            scopeExclusions = Collections.unmodifiableList(input.snapshot.scopeExclusions.toList()),
            unknownInputs = Collections.unmodifiableList(input.snapshot.unknownInputs.toList())),
        context = input.context.copy(classpath = Collections.unmodifiableList(input.context.classpath.map { it.copy() }),
            compilerArguments = Collections.unmodifiableList(input.context.compilerArguments.toList()),
            libraries = Collections.unmodifiableMap(input.context.libraries.toMap()),
            policies = Collections.unmodifiableMap(input.context.policies.toMap()),
            compilationFiles = input.context.compilationFiles?.let { Collections.unmodifiableList(it.toList()) }),
        requiredRuleIds = Collections.unmodifiableList(input.requiredRuleIds.toList()),
        changedPaths = Collections.unmodifiableList(input.changedPaths.toList()),
        execution = input.execution?.let { execution -> execution.copy(
            arguments = Collections.unmodifiableList(execution.arguments.toList()),
            environment = Collections.unmodifiableMap(execution.environment.toMap())) },
    )

    companion object {
        private val JVM_TARGETS = setOf("jvm", "android", "desktop", "desktop-jvm", "desktopjvm", "androidjvm")
        private val SOURCE_SUPPRESSION = Regex("@(?:file\\s*:\\s*)?(?:kotlin\\.)?Suppress\\s*\\(")
    }
}
