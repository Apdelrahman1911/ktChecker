package dev.ktchecker.providers.native

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Path

/** Actual K/N toolchain identities and target tasks are supplied by the trusted model importer. */
data class NativeRunPlan(
    val target: String,
    val compilerArtifacts: List<ArtifactIdentity>,
    val kotlinVersion: String = "2.4.21",
    val mode: String = "gradle",
    val requiredTasks: Set<String> = setOf("compileCommonMainKotlinMetadata", when (target) {
        "iosArm64" -> "compileKotlinIosArm64"
        "iosSimulatorArm64" -> "compileKotlinIosSimulatorArm64"
        else -> "unsupportedNativeCompilation"
    }),
    val sourceSets: Map<String, String> = mapOf("src/commonMain/" to "commonMain", "src/iosMain/" to "iosMain",
        "src/iosArm64Main/" to "iosArm64Main", "src/iosSimulatorArm64Main/" to "iosSimulatorArm64Main"),
)

class KotlinNativeProvider(
    private val authority: ExecutionAuthority = ExecutionAuthority.DENY,
    plan: NativeRunPlan? = null,
    private val runner: IsolatedProcessRunner = IsolatedProcessRunner(),
) : AnalysisProvider {
    private val plan = plan?.let { selected -> selected.copy(compilerArtifacts = selected.compilerArtifacts.toList(), requiredTasks = selected.requiredTasks.toSet(),
        sourceSets = selected.sourceSets.toMap()) }
    override val id = "kotlin-native"
    override val version = "2.4.21"
    override val supportedRuleIds: Set<String> get() = NativeCatalog.byId.keys

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
        try {
            if (activePlan.compilerArtifacts.isEmpty()) throw ExecutionProblem("native_toolchain_artifacts_unavailable", "Native version string is not toolchain identity")
            activePlan.compilerArtifacts.forEach(::verifyArtifact)
            val args = input.execution?.arguments ?: emptyList()
            when (activePlan.mode) {
                "gradle" -> {
                    if (activePlan.requiredTasks.isEmpty() || activePlan.requiredTasks.any { name -> args.none { it.substringAfterLast(':') == name } }) {
                        throw ExecutionProblem("native_required_task_not_selected", "Actual common and iOS tasks must be selected")
                    }
                }
                "native_cli" -> {
                    val target = if (activePlan.target == "iosArm64") "ios_arm64" else "ios_simulator_arm64"
                    val index = args.indexOf("-target")
                    if (index < 0 || args.getOrNull(index + 1) != target ||
                        "-Xmulti-platform" !in args || "-Xrender-internal-diagnostic-names" !in args ||
                        args.none { it.startsWith("-Xcommon-sources=") }) {
                        throw ExecutionProblem("native_cli_target_binding_invalid", "Native common/expect-actual and iOS target arguments are required")
                    }
                }
                else -> throw ExecutionProblem("native_execution_mode_unsupported", "No JVM substitution for Native semantics")
            }
        } catch (e: Exception) { return unavailable(problemCode(e, "native_plan_invalid")) }
        val attempt = runner.run(input, authority, cancellation)
        if (!attempt.successfulExecution) return unavailable(attempt.reasons.firstOrNull() ?: "native_execution_failed",
            attempt.status, attempt.metrics).copy(reasons = attempt.reasons)
        val errors = mutableListOf<String>()
        val raw = mutableListOf<RawDiagnostic>()
        val findings = mutableListOf<Finding>()
        var parsed: NativeOutput? = null
        try {
            activePlan.compilerArtifacts.forEach(::verifyArtifact)
            val output = NativeDiagnosticParser.parse(attempt.stdout, attempt.stderr)
            parsed = output
            val sources = SnapshotSources(input)
            for (diagnostic in output.diagnostics) {
                val rule = diagnostic.name?.let { NativeCatalog.byName[it] }
                if (rule == null) {
                    errors += if (diagnostic.name == null) "native_unnamed_diagnostic" else "native_unknown_diagnostic:${diagnostic.name}"
                    raw += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.name)
                    continue
                }
                try {
                    val path = diagnostic.path ?: throw ExecutionProblem("native_diagnostic_location_unavailable", "Native diagnostic has no captured source position")
                    val location = sources.location(path, diagnostic.line, diagnostic.column)
                    if (input.context.compilationFiles?.let { location.path !in it } == true) {
                        throw ExecutionProblem("native_location_outside_compilation", "Captured file is not a member of the selected Native compilation")
                    }
                    val set = activePlan.sourceSets.entries.filter { location.path.startsWith(it.key) }
                        .maxByOrNull { it.key.length }?.value
                        ?: throw ExecutionProblem("native_source_set_unavailable", "Native diagnostic source set is absent from the selected model")
                    val scope = input.context.scope.copy(sourceSet = set)
                    findings += Finding(rule.ruleId, rule.title, diagnostic.message,
                        "The Kotlin/Native compiler reported ${diagnostic.name} while compiling common sources in the ${activePlan.target} expect/actual context.",
                        location, Digests.sha256(listOf(rule.ruleId, location.path, location.fileDigest,
                            location.line.toString(), location.column.toString(), scope.id, input.context.toolchainDigest).joinToString("\u0000")),
                        id, scope, diagnostic.severity, "compile_failure", "high",
                        evidence = listOf(
                            Evidence("native_compiler_diagnostic", "Kotlin/Native $version; actual target ${activePlan.target}; snapshot ${input.snapshot.snapshotId}",
                                digest = Digests.sha256(attempt.stdout + "\n" + attempt.stderr)),
                            Evidence("native_toolchain", activePlan.compilerArtifacts.joinToString { it.coordinate },
                                digest = input.context.toolchainDigest),
                        ),
                        fixHint = "Repair the common/iOS declaration contract and compile the same Native target again; JVM success is not a substitute.",
                        verification = listOf(Verification(expected = "Native target compiles without ${diagnostic.name}", command = attempt.command)),
                        upstreamId = diagnostic.name, semanticDigest = input.context.modelDigest,
                    )
                } catch (e: Exception) {
                    errors += problemCode(e, "native_location_invalid")
                    raw += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.name)
                }
            }
        } catch (e: Exception) { errors += problemCode(e, "native_output_invalid") }
        if (attempt.exitCode != 0 && findings.none { it.severity == "error" }) errors += "native_nonzero_without_named_source_error"
        if (attempt.exitCode !in setOf(0, 1)) errors += "native_provider_exit:${attempt.exitCode}"
        val taskGaps = if (activePlan.mode == "gradle") activePlan.requiredTasks.filter { name ->
            parsed?.tasks?.any { it.name == name && it.actuallyExecuted } != true
        }.map { "native_task_not_executed:$it" } else emptyList()
        if (activePlan.mode == "gradle" && attempt.exitCode == 0 && parsed?.buildSuccessful != true) errors += "native_gradle_completion_unverified"
        if (activePlan.mode == "gradle" && parsed?.tasks?.any {
                it.outcome == "FAILED" && it.name !in activePlan.requiredTasks
            } == true) errors += "native_unrelated_task_failure"
        val certificate = Digests.sha256(input.analysisKey() + "\n" + attempt.stdout + "\n" + attempt.stderr)
        val rows = requested.map { ruleId ->
            val reason = when {
                ruleId !in supportedRuleIds -> "native_rule_unknown"
                errors.isNotEmpty() -> "native_output_unreliable"
                taskGaps.isNotEmpty() -> taskGaps.first()
                else -> "native_per_id_apple_precision_qualification_pending"
            }
            coverage(ruleId, input, if (ruleId in supportedRuleIds) "incomplete" else "unavailable", reason, ruleId in supportedRuleIds,
                if (ruleId in supportedRuleIds && errors.isEmpty() && taskGaps.isEmpty()) certificate else null)
        }
        return ProviderResult(id, if (errors.isEmpty()) "incomplete" else "failed", findings.distinctBy { it.fingerprint }, rows,
            (errors + taskGaps + rows.mapNotNull { it.reason }).distinct(), raw, attempt.metrics, version)
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
