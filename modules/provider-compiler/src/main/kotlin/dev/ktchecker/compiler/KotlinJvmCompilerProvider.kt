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
import kotlinx.serialization.json.*

/** Trusted explicit link-free JDK21 installation; compiler jars never enter ktChecker's classloader. */
class KotlinJvmToolchain(javaExecutable: Path, compilerClasspath: List<ArtifactIdentity>,
    runtimeLimits: KotlinJvmRuntimeLimits = KotlinJvmRuntimeLimits()) {
    val runtimeImage: KotlinJvmRuntimeImage = KotlinJvmRuntimeImage.capture(javaExecutable, runtimeLimits)
    val javaExecutable: Path = runtimeImage.javaExecutable
    val compilerClasspath: List<ArtifactIdentity> = Collections.unmodifiableList(compilerClasspath.map { it.copy() })
    private val compilerSizes: List<Long> = this.compilerClasspath.map { Files.size(Path.of(it.path)) }
    val executableDigest: String = runtimeImage.executableDigest
    // The compiler sees this exact ordered classpath. JSON frames arbitrary
    // coordinates/paths without delimiter collisions; sorting would change semantics.
    val digest: String = Digests.sha256(Digests.canonical(buildJsonObject {
        put("schema", "ktchecker/kotlin-jvm-toolchain-3")
        put("compilerClasspath", ContractJson.encodeToJsonElement(this@KotlinJvmToolchain.compilerClasspath))
        put("javaExecutable", this@KotlinJvmToolchain.javaExecutable.toString())
        put("javaExecutableDigest", executableDigest)
        put("javaRuntimeImageDigest", runtimeImage.digest)
    }))

    init {
        require(this.compilerClasspath.any {
            Path.of(it.path).fileName.toString().endsWith("kotlin-compiler-embeddable-2.4.21.jar")
        }) { "An explicit Kotlin2.4.21 compiler installation is required" }
        require(this.compilerClasspath.map { it.path }.toSet().size == this.compilerClasspath.size)
        verify()
    }

    /** Captured typed data only; callers still need this exact live installation and verify(). */
    fun installationView(): KotlinJvmInstallationView = KotlinJvmInstallationView(digest, runtimeImage.view(),
        compilerClasspath.mapIndexed { index, artifact -> InstalledCompilerJarView(index, artifact, compilerSizes[index]) })

    fun verify(checkpoint: () -> Unit = {}) = verifyObserved(checkpoint, null)

    /** Private phase collection does not replace any byte/size check or authority checkpoint. */
    internal fun verifyObserved(checkpoint: () -> Unit, timings: CompilerPhaseTimings?) {
        checkpoint()
        timings.observe(CompilerPhase.JDK_RUNTIME_VERIFICATION) { runtimeImage.verify(checkpoint) }
        timings.observe(CompilerPhase.COMPILER_CLASSPATH_VERIFICATION) {
            CompilerSnapshot.classpath(compilerClasspath, checkpoint)
            require(compilerClasspath.map { Files.size(Path.of(it.path)) } == compilerSizes) { "Installed compiler JAR sizes changed" }
        }
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
    val inputMode: KotlinJvmInputMode = KotlinJvmInputMode.LEGACY_EXPLICIT_INPUT,
    private val modelAuthority: KotlinJvmModelAuthority = KotlinJvmModelAuthority.DENY,
) : AnalysisProvider {
    override val id = CompilerRuleRegistry.PROVIDER_ID
    private val compilerVersion = CompilerRuleRegistry.VERSION
    // Mode is owner-installed code/routing identity, not a mutable input-policy discriminator.
    override val version = if (inputMode == KotlinJvmInputMode.LEGACY_EXPLICIT_INPUT) compilerVersion
        else "$compilerVersion/verified-model-1"
    override val supportedRuleIds: Set<String> = registry.jvmRuleIds
    val availableMappings: List<CompilerRuleMapping> get() = registry.mappings
    private val qualifications = Collections.unmodifiableList(qualifications.toList())
    private val parser = CompilerDiagnosticParser()

    init {
        require(compilerHeapMiB in 128..2_048) { "Compiler heap must be explicitly bounded" }
        require(this.qualifications.all { it.ruleId in supportedRuleIds }) { "A JVM provider cannot accept a Native qualification" }
        require(inputMode == KotlinJvmInputMode.VERIFIED_MODEL || modelAuthority === KotlinJvmModelAuthority.DENY) {
            "A model authority cannot be installed on a legacy/fallback provider route"
        }
    }

    internal fun usesInstallation(selected: KotlinJvmToolchain): Boolean = toolchain === selected

    /**
     * Host configuration can select this exact argv and grant a nonserializable
     * ExecutionPermit over its registered snapshot. Returning approved=true here
     * grants nothing: analyze still denies execution without that exact authority.
     */
    fun executionProfile(input: AnalysisInput, timeoutMillis: Long = 60_000, maxOutputBytes: Int = 1_048_576): TrustedExecution {
        if (inputMode != KotlinJvmInputMode.LEGACY_EXPLICIT_INPUT || modelBearingInput(input)) {
            throw KotlinJvmModelException("compiler_model_binding_required", "A model-only route requires its live checked model binding, never metadata-selected fallback.")
        }
        return generatedProfile(input, timeoutMillis, maxOutputBytes, null)
    }

    /** Original owner input only. Archived attempts must use the retained prepared profile/permit. */
    fun executionProfile(input: AnalysisInput, binding: KotlinJvmModelBinding,
        timeoutMillis: Long = 60_000, maxOutputBytes: Int = 1_048_576): TrustedExecution {
        binding.requireProvider(this)
        binding.requireOriginalInput(input)
        return generatedProfile(input, timeoutMillis, maxOutputBytes, binding.identity.digest)
    }

    private fun generatedProfile(input: AnalysisInput, timeoutMillis: Long, maxOutputBytes: Int,
        modelBindingDigest: String?): TrustedExecution {
        val flags = CompilerFlags.validate(input.context.compilerArguments)
        // Installed compiler identities are intrinsic to the approved profile,
        // even for limited requests without an owner-imported toolchain digest.
        val arguments = mutableListOf("-Xmx${compilerHeapMiB}m", "-Dktchecker.compiler.toolchain=${toolchain.digest}",
            "-Dktchecker.compiler.mapping=${registry.mappingIdentity.contractId}:${registry.mappingIdentity.resourceDigest}",
            "-Dfile.encoding=UTF-8", "-Duser.language=en",
            "-Duser.country=US", "-Djava.io.tmpdir={output}/tmp", "-cp")
        modelBindingDigest?.let { arguments.add(1, KotlinJvmModelBinding.PROFILE_PROPERTY + it) }
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

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult =
        analyzeWithTimings(input, cancellation, null)

    /** Trusted test-host observations only; neither input JSON nor AnalysisMetrics enables them. */
    internal fun analyzeObserved(input: AnalysisInput, cancellation: CancellationToken,
        timings: CompilerPhaseTimings): ProviderResult = analyzeWithTimings(input, cancellation, timings)

    /** Owner handle only; the existing test-recorder entry and default path remain separate. */
    internal fun analyzeObserved(input: AnalysisInput, cancellation: CancellationToken,
        capture: CompilerCallCapture): ProviderResult = analyzeWithTimings(input, cancellation, capture.timings, capture)

    private fun analyzeWithTimings(input: AnalysisInput, cancellation: CancellationToken,
        timings: CompilerPhaseTimings?, capture: CompilerCallCapture? = null): ProviderResult {
        val started = System.nanoTime()
        return timings.observe(CompilerPhase.PROVIDER_CALL) { analyzeImpl(input, cancellation, started, timings, capture) }
    }

    private fun analyzeImpl(input: AnalysisInput, cancellation: CancellationToken, started: Long,
        timings: CompilerPhaseTimings?, capture: CompilerCallCapture?): ProviderResult {
        val request = freeze(input)
        val required = request.requiredRuleIds.distinct()
        fun unavailable(reason: String, status: String = "unavailable"): ProviderResult = ProviderResult(id, status,
            coverage = Collections.unmodifiableList(required.map { Coverage(it, request.context.scope, "unsupported", reason) }),
            reasons = listOf(reason), providerVersion = version)
        if (cancellation.isCancelled()) return unavailable("cancelled_before_execution", "cancelled")
        val modelPermit = if (inputMode == KotlinJvmInputMode.VERIFIED_MODEL) {
            val selected = runCatching { modelAuthority.authorize(request) }.getOrNull()
                ?: return unavailable("compiler_model_not_authorized")
            if (!selected.matches(this, request)) return unavailable("compiler_model_attempt_mismatch")
            selected
        } else {
            if (modelBearingInput(request)) return unavailable("compiler_model_route_required")
            null
        }
        // A model-only route stays model-only even when metadata is removed/digests are replaced.
        if (modelPermit == null && request.context.scope.target.lowercase() !in JVM_TARGETS) return unavailable("target_not_jvm")
        if (request.context.compilerVersion != compilerVersion) return unavailable("compiler_version_not_pinned_2_4_21")
        if (modelPermit == null && request.context.toolchainDigest != null && request.context.toolchainDigest != toolchain.digest) {
            return unavailable("compiler_toolchain_identity_mismatch")
        }
        val permit = runCatching { authority.authorize(request) }.getOrNull()
            ?: return unavailable("execution_not_authorized")
        val execution = request.execution ?: return unavailable("execution_profile_missing")
        val actualKey = request.analysisKey()
        if (permit.expectedAnalysisKey != actualKey || permit.snapshotId != request.snapshot.snapshotId ||
            permit.materializedRoot.toAbsolutePath().normalize() != Path.of(request.materializedRoot).toAbsolutePath().normalize()) {
            return unavailable("execution_identity_binding_mismatch")
        }
        val expectedProfile = runCatching {
            generatedProfile(request, execution.timeoutMillis, execution.maxOutputBytes, modelPermit?.bindingIdentity?.digest)
        }.getOrElse { return unavailable("compiler_settings_unsupported") }
        if (execution != expectedProfile || permit.execution != execution) return unavailable("execution_profile_mismatch")
        val deadline = started + execution.timeoutMillis.coerceIn(1, 3_600_000) * 1_000_000
        val boundedCancellation = CancellationToken { cancellation.isCancelled() || System.nanoTime() >= deadline }
        val checkpoint = {
            cancellation.throwIfCancelled()
            if (System.nanoTime() >= deadline) throw ExecutionProblem("compiler_preparation_timeout", "Compiler preparation deadline elapsed")
        }
        capture?.captureIdentity(actualKey, request, toolchain.digest, registry.mappingIdentity.resourceDigest, compilerHeapMiB)
        val sources: List<SealedCompilerSource>
        try {
            sources = timings.observe(CompilerPhase.INITIAL_VERIFICATION) {
                checkpoint()
                timings.observe(CompilerPhase.SNAPSHOT_VERIFICATION) { permit.verifySnapshot() }
                if (modelPermit == null) toolchain.verifyObserved(checkpoint, timings) else
                    timings.observe(CompilerPhase.MODEL_BINDING_VERIFICATION) { modelPermit.verify(this, request, checkpoint) }
                timings.observe(CompilerPhase.DEPENDENCY_CLASSPATH_VERIFICATION) {
                    CompilerSnapshot.classpath(request.context.classpath, checkpoint)
                }
                require((toolchain.compilerClasspath + request.context.classpath).sumOf { Files.size(Path.of(it.path)) } <=
                    CompilerSnapshot.MAX_TOTAL_ARTIFACT_BYTES) { "Combined compiler/dependency artifacts exceed job byte bound" }
                timings.observe(CompilerPhase.SEALED_SOURCE_READING) { CompilerSnapshot.read(request, checkpoint) }
            }
        } catch (_: CancellationException) {
            return unavailable("cancelled_during_compiler_preparation", "cancelled")
        } catch (problem: ExecutionProblem) {
            return unavailable(problem.code)
        } catch (problem: KotlinJvmRuntimeException) {
            return unavailable(problem.code)
        } catch (problem: KotlinJvmModelException) {
            return unavailable(problem.code)
        } catch (_: Exception) {
            return unavailable("sealed_input_or_toolchain_verification_failed")
        }
        val wrappedPermit = ExecutionPermit(execution, permit.snapshotId, permit.materializedRoot, permit.expectedAnalysisKey, {
            timings.observe(CompilerPhase.WRAPPED_PERMIT_VERIFICATION) {
                checkpoint()
                timings.observe(CompilerPhase.SNAPSHOT_VERIFICATION) { permit.verifySnapshot() }
                // The runner invokes this both before and after the child. Preserve
                // full-runtime drift codes without discarding actual exit/output.
                try {
                    if (modelPermit == null) toolchain.verifyObserved(checkpoint, timings) else
                        timings.observe(CompilerPhase.MODEL_BINDING_VERIFICATION) { modelPermit.verify(this, request, checkpoint) }
                } catch (problem: KotlinJvmRuntimeException) {
                    throw ExecutionProblem(problem.code, problem.message ?: "Selected Java runtime verification failed")
                } catch (problem: KotlinJvmModelException) {
                    throw ExecutionProblem(problem.code, problem.message ?: "Live model attempt verification failed")
                }
                timings.observe(CompilerPhase.DEPENDENCY_CLASSPATH_VERIFICATION) {
                    CompilerSnapshot.classpath(request.context.classpath, checkpoint)
                }
            }
        }, { output ->
            timings.observe(CompilerPhase.OUTPUT_PREPARATION) {
                checkpoint()
                timings.observe(CompilerPhase.OWNER_OUTPUT_PREPARATION) { permit.prepareOutput(output) }
                Files.createDirectories(output.resolve("classes"))
                timings.observe(CompilerPhase.SOURCE_COPYING) {
                    sources.forEach { source ->
                        checkpoint()
                        val destination = output.resolve("sources").resolve(source.relativePath)
                        Files.createDirectories(destination.parent)
                        Files.writeString(destination, source.text, Charsets.UTF_8, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                        check(fileDigest(destination) == source.digest) { "Source copy differs from sealed UTF-8 bytes" }
                    }
                }
                timings.observe(CompilerPhase.COMPILER_ARTIFACT_COPYING) {
                    copyArtifacts(toolchain.compilerClasspath, output.resolve("compiler"), checkpoint)
                }
                timings.observe(CompilerPhase.DEPENDENCY_ARTIFACT_COPYING) {
                    copyArtifacts(request.context.classpath, output.resolve("dependencies"), checkpoint)
                }
            }
        })
        val ran = timings.observe(CompilerPhase.RUNNER_CALL) {
            runner.run(request, ExactExecutionAuthority(wrappedPermit), boundedCancellation)
        }
        val outcome = if (System.nanoTime() >= deadline && !cancellation.isCancelled()) ran.copy(
            status = "failed", reasons = (ran.reasons.filterNot { it == "execution_cancelled" } + "compiler_total_timeout").distinct(),
            cancelled = false, timedOut = true) else ran
        capture?.captureOutcomes(ran, outcome, supportedRuleIds)
        timings.observe(CompilerPhase.EXECUTION_OBSERVER) { executionObserver(request, outcome) }
        return timings.observe(CompilerPhase.RESULT_NORMALIZATION) {
            normalize(request, required, sources, outcome, cancellation, started, modelPermit)
        }
    }

    private fun normalize(request: AnalysisInput, required: List<String>, sources: List<SealedCompilerSource>,
        outcome: ProcessOutcome, cancellation: CancellationToken, started: Long,
        modelPermit: KotlinJvmModelPermit?): ProviderResult {
        val sourcePaths = outcome.outputDirectory?.let { output -> sources.associate { source ->
            output.resolve("sources").resolve(source.relativePath) to
                CompilerSourceIdentity(source.relativePath, source.digest, source.text)
        } } ?: emptyMap()
        val parsed = parser.parse(outcome.stderr + "\n" + outcome.stdout, sourcePaths)
        val reasons = outcome.reasons.toMutableList()
        val raw = parsed.rawDiagnostics.toMutableList()
        val findings = mutableListOf<Finding>()
        val trustedEmission = outcome.successfulExecution && parsed.reportedVersion == compilerVersion
        if (parsed.reportedVersion != compilerVersion) reasons += "compiler_version_transport_mismatch"
        if (inputMode == KotlinJvmInputMode.VERIFIED_MODEL) reasons += "compiler_model_binding_not_rule_qualification"
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
                mapping.kind, mapping.certainty, fixHint = CompilerDiagnosticParser.fixHint(diagnostic),
                evidence = Collections.unmodifiableList(listOf(
                    Evidence("compiler-emission", "Kotlin$compilerVersion JVM exact diagnostic; phase tag=" +
                        (diagnostic.renderedPhase ?: "not rendered; not inferred"), digest = outputDigest),
                    Evidence("snapshot", "Registered immutable ${request.snapshot.snapshotId}; source batch, not inferred Gradle scope",
                        digest = diagnostic.location.fileDigest),
                    Evidence("compiler-options", "Actual explicit flags/classpath/context bound to $key", digest = key),
                    publicToolchainEvidence(toolchain),
                    Evidence("upstream-factory", mapping.source),
                    Evidence("location-precision", "Reported point: one-based line, zero-based UTF-16 column; no inferred end span"),
                )), verification = CompilerVerification.recompile(request, diagnostic.upstreamId, toolchain.digest,
                    sources.map { it.relativePath }), upstreamId = diagnostic.upstreamId, semanticDigest = key)
        }
        if (raw.any { it.severity != "info" } || parsed.malformed) reasons += "raw_task_diagnostics_prevent_complete_coverage"
        val completeModel = request.context.fullProject && request.context.compilationFiles != null &&
            request.snapshot.scopeExclusions.isEmpty() && request.snapshot.unknownInputs.isEmpty() &&
            listOf(request.context.modelDigest,
            request.context.dependencyDigest, request.context.configurationDigest).all { it != null && Digests.valid(it) } &&
            (modelPermit != null || request.context.toolchainDigest == toolchain.digest)
        if (!completeModel) reasons += "resolved_compilation_context_incomplete"
        val coverage = required.map { ruleId ->
            val mapping = registry.byRuleId[ruleId]
            val qualification = qualificationFor(ruleId, request)
            val state: String
            val reason: String?
            when {
                mapping == null || !mapping.jvmApplicable -> { state = "unsupported"; reason = "no_jvm_mapping_for_rule" }
                !trustedEmission -> { state = "failed"; reason = "compiler_execution_or_version_untrusted" }
                incompleteCompilerTask(outcome.exitCode, parsed, raw) -> { state = "failed"; reason = "failed_or_incomplete_compiler_task" }
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

    /** Bridge verification is not inherited rule evidence, even if every legacy digest matches. */
    internal fun qualificationFor(ruleId: String, input: AnalysisInput): CompilerQualification? {
        if (inputMode != KotlinJvmInputMode.LEGACY_EXPLICIT_INPUT || modelBearingInput(input) ||
            legacyDeclaredSingleCompilation(input)) return null
        return qualifications.find { it.ruleId == ruleId && it.contextId == input.context.scope.id &&
            it.compilerVersion == compilerVersion && it.toolchainDigest == toolchain.digest &&
            it.configurationDigest == input.context.configurationDigest && it.modelDigest == input.context.modelDigest &&
            it.dependencyDigest == input.context.dependencyDigest &&
            it.compilerArgumentsDigest == Digests.sha256(ContractJson.encodeToString(input.context.compilerArguments)) &&
            listOf(it.certificateId, it.modelDigest, it.dependencyDigest, it.compilerArgumentsDigest).all(Digests::valid) }
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
        // The caller supplies its post-normalization raw list. Clearing this one
        // barrier says nothing about execution trust, scope or qualification.
        internal fun incompleteCompilerTask(exitCode: Int?, parsed: CompilerParseResult,
            raw: List<RawDiagnostic>): Boolean = exitCode != 0 || parsed.malformed || raw.isNotEmpty()

        /** Public finding evidence carries identities, never owner-private installation paths.
         * The exact selected path stays bound in the private runtime image and execution profile.
         */
        internal fun publicToolchainEvidence(toolchain: KotlinJvmToolchain): Evidence = Evidence(
            "toolchain", "Pinned ordered isolated compiler artifacts and whole selected link-free JDK21 image " +
                "(${toolchain.runtimeImage.digest}); disk identity, not loaded-code or OS/JNI attestation",
            digest = toolchain.digest,
        )

        private val JVM_TARGETS = setOf("jvm", "android", "desktop", "desktop-jvm", "desktopjvm", "androidjvm")
        private val SOURCE_SUPPRESSION = Regex("@(?:file\\s*:\\s*)?(?:kotlin\\.)?Suppress\\s*\\(")
    }
}
