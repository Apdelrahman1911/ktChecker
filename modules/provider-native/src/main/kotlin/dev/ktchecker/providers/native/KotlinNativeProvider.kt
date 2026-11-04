package dev.ktchecker.providers.native

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Path

/** Actual K/N toolchain, exact task paths and file ownership come from the trusted model importer. */
data class NativeRunPlan(
    val target: String,
    val compilerArtifacts: List<ArtifactIdentity>,
    val kotlinVersion: String = "2.4.21",
    val mode: String = "gradle",
    val requiredTasks: Set<String> = emptySet(),
    val compilationModel: ExactCompilationModel? = null,
    val cliSelection: NativeCliSelection? = null,
)

/** Pure model/argv admission helper; it never substitutes a JVM host for Native execution. */
internal fun requireNativeTaskSelection(plan: NativeRunPlan, arguments: List<String>, module: String) {
    ExactGradleTaskProof.requireSelection(arguments, plan.requiredTasks, module)
    val prefix = module.removeSuffix(":") + ":"
    val targetTask = when (plan.target) {
        "iosArm64" -> "compileKotlinIosArm64"
        "iosSimulatorArm64" -> "compileKotlinIosSimulatorArm64"
        else -> throw ExecutionProblem("native_ios_target_model_required", "Actual iOS compilation is required")
    }
    if (!plan.requiredTasks.containsAll(setOf(prefix + "compileCommonMainKotlinMetadata", prefix + targetTask))) {
        throw ExecutionProblem("native_required_task_model_incomplete", "Imported common and selected iOS compilation tasks are required")
    }
}

internal data class NativeNormalizedDiagnostics(
    val findings: List<Finding>,
    val rawDiagnostics: List<RawDiagnostic>,
    val errors: List<String>,
    val selectionGaps: List<String>,
) {
    val exactSelectedOutput: Boolean get() = errors.isEmpty() && selectionGaps.isEmpty()
    val hasReportedError: Boolean get() = findings.any { it.severity == "error" } || rawDiagnostics.any { it.severity == "error" }
}

/** Pure model/selection normalization with a captured-location resolver, not execution evidence. */
internal class NativeFindingFactory(private val input: AnalysisInput, plan: NativeRunPlan) {
    private val compilationSources = ExactCompilationSources(input, plan.compilationModel)
    // Preserve the legacy empty-request catalogue fallback; it does not authorize execution or activation.
    private val requested = input.requiredRuleIds.takeIf { it.isNotEmpty() }?.toSet() ?: NativeCatalog.byId.keys
    private val target = plan.target
    private val compilerVersion = plan.kotlinVersion
    private val artifactCoordinates = plan.compilerArtifacts.joinToString { it.coordinate }

    fun create(rule: NativeRule, diagnostic: NativeDiagnostic, location: SourceLocation,
               outputDigest: String, command: List<String>): Finding {
        if (rule.ruleId !in requested) {
            throw ExecutionProblem("native_unselected_diagnostic:${rule.ruleId}", "Compiler diagnostic is outside the exact requested rule set")
        }
        val sourceSet = compilationSources.sourceSet(location.path)
        // Imported per-file ownership is evidence, not a replacement for the selected rule context.
        val scope = input.context.scope
        return Finding(rule.ruleId, rule.title, diagnostic.message,
            "The Kotlin/Native compiler reported ${diagnostic.name} in the selected $target expect/actual compilation context.",
            location, Digests.sha256(listOf(rule.ruleId, location.path, location.fileDigest,
                location.line.toString(), location.column.toString(), scope.id, input.context.toolchainDigest).joinToString("\u0000")),
            "kotlin-native", scope, diagnostic.severity, "compile_failure", "high",
            evidence = listOf(
                Evidence("native_compiler_diagnostic", "Kotlin/Native $compilerVersion; actual target $target; snapshot ${input.snapshot.snapshotId}",
                    digest = outputDigest),
                Evidence("native_toolchain", artifactCoordinates, digest = input.context.toolchainDigest),
                Evidence("compilation_source_membership",
                    "Imported source set $sourceSet; selected analysis context ${scope.id}; immutable snapshot ${input.snapshot.snapshotId}; informational ownership only, not cross-context suppression authority.",
                    location.path, input.context.modelDigest),
            ),
            fixHint = "Repair the common/iOS declaration contract and compile the same Native target again; JVM success is not a substitute.",
            verification = listOf(Verification(expected = "Native target compiles without ${diagnostic.name}", command = command)),
            upstreamId = diagnostic.name, semanticDigest = input.analysisKey(),
        )
    }

    fun normalize(diagnostics: List<NativeDiagnostic>, outputDigest: String, command: List<String>,
                  resolveLocation: (NativeDiagnostic) -> SourceLocation): NativeNormalizedDiagnostics {
        val findings = mutableListOf<Finding>()
        val raw = mutableListOf<RawDiagnostic>()
        val errors = mutableListOf<String>()
        val gaps = mutableListOf<String>()
        for (diagnostic in diagnostics) {
            val rule = diagnostic.name?.let { NativeCatalog.byName[it] }
            if (rule == null) {
                errors += if (diagnostic.name == null) "native_unnamed_diagnostic" else "native_unknown_diagnostic:${diagnostic.name}"
                raw += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.name)
                continue
            }
            if (rule.ruleId !in requested) {
                // The compiler inherently emits unrelated IDs. Preserve their failure influence,
                // but never publish an out-of-plan finding or certify selected coverage from them.
                gaps += "native_unselected_diagnostic:${rule.ruleId}"
                raw += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.name)
                continue
            }
            try {
                findings += create(rule, diagnostic, resolveLocation(diagnostic), outputDigest, command)
            } catch (e: Exception) {
                errors += problemCode(e, "native_location_invalid")
                raw += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.name)
            }
        }
        return NativeNormalizedDiagnostics(findings.toList(), raw.toList(), errors.toList(), gaps.distinct())
    }
}

class KotlinNativeProvider(
    private val authority: ExecutionAuthority = ExecutionAuthority.DENY,
    plan: NativeRunPlan? = null,
    private val runner: IsolatedProcessRunner = IsolatedProcessRunner(),
) : AnalysisProvider {
    private val plan = plan?.let { selected -> selected.copy(compilerArtifacts = selected.compilerArtifacts.toList(), requiredTasks = selected.requiredTasks.toSet(),
        compilationModel = selected.compilationModel?.let { it.copy(sourceSetByFile = it.sourceSetByFile.toMap()) },
        cliSelection = selected.cliSelection?.frozenCopy()) }
    override val id = "kotlin-native"
    override val version = "2.4.21"
    override val supportedRuleIds: Set<String> get() = NativeCatalog.byId.keys

    /** Pure admission on the constructor-frozen plan; this neither authorizes nor runs a compiler. */
    internal fun requireCliSelection(input: AnalysisInput) = requireNativeCliSelection(
        plan ?: throw ExecutionProblem("native_trusted_plan_unavailable", "An owner-supplied Native plan is required"), input)

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val requested = input.requiredRuleIds.takeIf { it.isNotEmpty() }?.toSet() ?: supportedRuleIds
        fun unavailable(reason: String, status: String = "unavailable", metrics: AnalysisMetrics = AnalysisMetrics()) =
            ProviderResult(id, status, coverage = requested.map { coverage(it, input, status, reason) },
                reasons = listOf(reason), metrics = metrics, providerVersion = version)
        val activePlan = plan ?: return unavailable("native_trusted_plan_unavailable")
        if (input.context.scope.target !in setOf("iosArm64", "iosSimulatorArm64") || input.context.scope.target != activePlan.target) {
            return unavailable("native_ios_target_model_required")
        }
        val host = System.getProperty("os.name").lowercase()
        if (!(host.contains("mac") || host.contains("darwin"))) return unavailable("apple_native_execution_host_missing")
        if (activePlan.kotlinVersion != version || input.context.compilerVersion != version) return unavailable("native_compiler_version_unavailable")
        if (!input.context.fullProject || listOf(input.context.modelDigest, input.context.dependencyDigest,
                input.context.toolchainDigest, input.context.configurationDigest).any { it == null || !Digests.valid(it) }) {
            return unavailable("native_resolved_frozen_model_required")
        }
        if (cancellation.isCancelled()) return unavailable("cancelled_before_execution", "cancelled")
        if (try { authority.authorize(input) == null } catch (_: Exception) { true }) return unavailable("execution_not_authorized")
        val findingFactory: NativeFindingFactory
        try {
            findingFactory = NativeFindingFactory(input, activePlan)
            if (activePlan.compilerArtifacts.isEmpty()) throw ExecutionProblem("native_toolchain_artifacts_unavailable", "Native version string is not toolchain identity")
            activePlan.compilerArtifacts.forEach(::verifyArtifact)
            val args = input.execution?.arguments ?: emptyList()
            when (activePlan.mode) {
                "gradle" -> {
                    requireNativeTaskSelection(activePlan, args, input.context.scope.module)
                }
                "native_cli" -> {
                    requireCliSelection(input)
                }
                else -> throw ExecutionProblem("native_execution_mode_unsupported", "No JVM substitution for Native semantics")
            }
        } catch (e: Exception) { return unavailable(problemCode(e, "native_plan_invalid")) }
        val attempt = runner.run(input, authority, cancellation)
        if (!attempt.successfulExecution) return unavailable(attempt.reasons.firstOrNull() ?: "native_execution_failed",
            attempt.status, attempt.metrics).copy(reasons = attempt.reasons)
        val errors = mutableListOf<String>()
        val selectionGaps = mutableListOf<String>()
        val raw = mutableListOf<RawDiagnostic>()
        val findings = mutableListOf<Finding>()
        var parsed: NativeOutput? = null
        var exactSelectedOutput = false
        var hasReportedError = false
        try {
            activePlan.compilerArtifacts.forEach(::verifyArtifact)
            val output = NativeDiagnosticParser.parse(attempt.stdout, attempt.stderr)
            parsed = output
            val sources = SnapshotSources(input)
            val outputDigest = Digests.sha256(attempt.stdout + "\n" + attempt.stderr)
            val normalized = findingFactory.normalize(output.diagnostics, outputDigest, attempt.command) { diagnostic ->
                val path = diagnostic.path ?: throw ExecutionProblem("native_diagnostic_location_unavailable", "Native diagnostic has no captured source position")
                sources.location(path, diagnostic.line, diagnostic.column)
            }
            findings += normalized.findings
            raw += normalized.rawDiagnostics
            errors += normalized.errors
            selectionGaps += normalized.selectionGaps
            exactSelectedOutput = normalized.exactSelectedOutput
            hasReportedError = normalized.hasReportedError
        } catch (e: Exception) { errors += problemCode(e, "native_output_invalid") }
        if (attempt.exitCode != 0 && !hasReportedError) errors += "native_nonzero_without_named_source_error"
        if (attempt.exitCode !in setOf(0, 1)) errors += "native_provider_exit:${attempt.exitCode}"
        val taskGaps = if (activePlan.mode == "gradle") ExactGradleTaskProof.missingExecution(
            activePlan.requiredTasks, parsed?.tasks.orEmpty()).map { "native_task_not_executed:$it" } else emptyList()
        if (activePlan.mode == "gradle" && attempt.exitCode == 0 && parsed?.buildSuccessful != true) errors += "native_gradle_completion_unverified"
        if (activePlan.mode == "gradle" && ExactGradleTaskProof.hasUnrelatedFailure(
                activePlan.requiredTasks, parsed?.tasks.orEmpty())) errors += "native_unrelated_task_failure"
        val certificate = Digests.sha256(input.analysisKey() + "\n" + attempt.stdout + "\n" + attempt.stderr)
        val rows = requested.map { ruleId ->
            val reason = when {
                ruleId !in supportedRuleIds -> "native_rule_unknown"
                errors.isNotEmpty() -> "native_output_unreliable"
                selectionGaps.isNotEmpty() -> selectionGaps.first()
                taskGaps.isNotEmpty() -> taskGaps.first()
                else -> "native_per_id_apple_precision_qualification_pending"
            }
            coverage(ruleId, input, if (ruleId in supportedRuleIds) "incomplete" else "unavailable", reason, ruleId in supportedRuleIds,
                if (ruleId in supportedRuleIds && errors.isEmpty() && exactSelectedOutput && taskGaps.isEmpty()) certificate else null)
        }
        return ProviderResult(id, if (errors.isEmpty()) "incomplete" else "failed", findings.distinctBy { it.fingerprint }, rows,
            (errors + selectionGaps + taskGaps + rows.mapNotNull { it.reason }).distinct(), raw, attempt.metrics, version)
    }

    private fun verifyArtifact(artifact: ArtifactIdentity) {
        if (!Digests.valid(artifact.digest) || fileDigest(Path.of(artifact.path)) != artifact.digest) {
            throw ExecutionProblem("native_toolchain_artifact_mismatch", "Native toolchain differs from frozen identities")
        }
    }

    private fun coverage(ruleId: String, input: AnalysisInput, state: String, reason: String,
                         executed: Boolean = false, certificate: String? = null): Coverage = Coverage(
        ruleId, input.context.scope, state, reason, depth = "build",
        provenance = if (executed) "executed" else "not_executed", certificateId = certificate,
        implemented = ruleId in supportedRuleIds, tested = false, qualified = false,
    )
}
