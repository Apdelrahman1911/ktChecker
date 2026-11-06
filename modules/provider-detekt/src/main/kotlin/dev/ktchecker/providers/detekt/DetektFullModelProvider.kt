package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

const val DETEKT_FULL_MISSING_USE_CALL = "ktc.kotlin.detekt.potential-bugs.missing-use-call"

/** Closed owner configuration for the initial FULL representative, never project-supplied argv. */
data class DetektFullSettings(
    val enabled: Boolean = true,
    val ignoreClasses: List<String> = listOf("java.io.ByteArrayInputStream", "java.io.ByteArrayOutputStream"),
)

/**
 * Explicit experimental FULL adapter, NOT installed by the ordinary controller/CLI route.
 * Only strict modeled Kotlin2.4.10/Linux/JDK21 inputs can obtain its separate live permit.
 * LIGHT, compiler2.4.21, the 1,663 catalog obligations and all release gaps remain unchanged.
 * MissingUseCall is an initial type-aware validation milestone, not a reduced release scope.
 */
class DetektFullModelProvider(
    private val installation: DetektFullInstallation? = null,
    private val authority: ExecutionAuthority = ExecutionAuthority.DENY,
    settings: DetektFullSettings = DetektFullSettings(),
    private val runner: IsolatedProcessRunner = IsolatedProcessRunner(),
    private val modelAuthority: DetektFullModelAuthority = DetektFullModelAuthority.DENY,
) : AnalysisProvider {
    private val settings = settings.copy(ignoreClasses = immutableDetektList(settings.ignoreClasses))
    override val id = "detekt"
    override val version = DETEKT_VERSION + "/full-model-2.4.10-experiment-1"
    override val supportedRuleIds = setOf(DETEKT_FULL_MISSING_USE_CALL)
    val mappedRuleIds: Set<String> get() = DetektCatalog.byId.keys

    init {
        require(this.settings.ignoreClasses.size <= 64 && this.settings.ignoreClasses.distinct().size == this.settings.ignoreClasses.size)
        require(this.settings.ignoreClasses.all {
            it.length <= 512 && Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+").matches(it)
        }) { "Only bounded exact fully qualified ignoreClass values are supported" }
        val mapping = DetektCatalog.byId.getValue(DETEKT_FULL_MISSING_USE_CALL)
        check(mapping.exact && mapping.depth == "semantic" && mapping.upstream_id == "potential-bugs:MissingUseCall" &&
            mapping.catalog_record_sha256 == "ef64734fa392b6fa35c9d0512de725cfa4b84bac5064be9d3ffa3b755ac713d3")
    }

    internal fun selectedInstallation(): DetektFullInstallation? = installation
    internal fun usesInstallation(selected: DetektFullInstallation): Boolean = installation === selected
    internal fun activeRuleIds(selected: List<String>): Set<String> =
        if (settings.enabled && DETEKT_FULL_MISSING_USE_CALL in selected) setOf(DETEKT_FULL_MISSING_USE_CALL) else emptySet()

    /** Host binding-only planner. The identity is a live typed relation, not a caller profile tag. */
    internal fun boundExecutionPlan(input: AnalysisInput, binding: DetektFullBinding,
                                    timeoutMillis: Long, maxOutputBytes: Int): DetektExecutionPlan {
        fullCheck(binding.provider === this && usesInstallation(binding.installation) &&
            input.context.compilerVersion == DETEKT_EMBEDDED_KOTLIN &&
            input.context.compilerArguments == binding.arguments.originalArguments &&
            activeRuleIds(input.requiredRuleIds) == setOf(DETEKT_FULL_MISSING_USE_CALL),
            "detekt_full_plan_mismatch", "The fixed FULL model/provider/argv/selection relation is required.")
        val installed = binding.installation
        val artifacts = installed.engineArtifacts
        val cli = artifacts.single { it.coordinate == "dev.detekt:detekt-cli:" + DETEKT_VERSION + ":all" }.path
        val plugins = artifacts.filterNot { it.path == cli }.joinToString(":") { it.path }
        val configuration = configuration()
        val configurationDigest = Digests.sha256(configuration)
        val projection = binding.arguments
        val arguments = mutableListOf(
            "-Xmx384m", DetektVerifiedFullModelBinding.PROFILE_PROPERTY + binding.identity.digest,
            "-Djava.io.tmpdir={output}/tmp",
            "-Dktchecker.detekt.fullInstallation=" + installed.digest,
            "-Dktchecker.detekt.configurationSha256=" + configurationDigest,
            "-jar", cli,
            "--input", projection.sourceFiles.joinToString(":") { "{snapshot}/" + it.path },
            "--base-path", "{snapshot}", "--analysis-mode", "full",
            "--config", "{output}/detekt.yml", "--plugins", plugins,
            "--report", "sarif:{output}/detekt.sarif", "--fail-on-severity", "Never",
            "--classpath", input.context.classpath.indices.joinToString(":") { "{snapshot}/../classpath/artifact-" + it + ".jar" },
            "--language-version", projection.languageVersion, "--api-version", projection.apiVersion,
            "--jvm-target", projection.jvmTarget, "--jdk-home", installed.jdkHome.toString(),
            // Genuine CLI freeCompilerArgs are parsed by the embedded compiler. No arbitrary
            // request tail is accepted: the closed total role projection supplies every token.
            "--",
        )
        arguments += projection.freeCompilerArguments
        return DetektExecutionPlan(
            freezeDetektProfile(TrustedExecution(installed.javaExecutable.toString(), arguments, approved = true,
                executableDigest = installed.executableDigest, timeoutMillis = timeoutMillis, maxOutputBytes = maxOutputBytes)),
            configuration, configurationDigest, immutableDetektList(artifacts), setOf(DETEKT_FULL_MISSING_USE_CALL), "full",
        )
    }

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult =
        analyzeFrozen(freezeDetektInput(input), cancellation)

    private fun analyzeFrozen(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val requested = input.requiredRuleIds.distinct()
        fun denied(reason: String, state: String = "unavailable", executed: Boolean = false,
                   diagnostics: List<RawDiagnostic> = emptyList(), metrics: AnalysisMetrics = AnalysisMetrics()): ProviderResult =
            ProviderResult(id, if (state == "cancelled") "cancelled" else "partial",
                coverage = requested.map { abstention(it, input.context.scope) ?: Coverage(it, input.context.scope,
                    state, reason, depth = "semantic", provenance = if (executed) "executed" else "not_executed") },
                reasons = listOf(reason), rawDiagnostics = diagnostics, metrics = metrics, providerVersion = version)
        if (requested.isEmpty()) return denied("no_explicit_detekt_rules_requested")
        if (cancellation.isCancelled()) return denied("cancelled_before_execution", "cancelled")
        if (requested.all { abstention(it, input.context.scope) != null }) return denied("detekt_full_no_enabled_semantic_rule")
        if (input.context.compilerVersion != DETEKT_EMBEDDED_KOTLIN) return denied("detekt_full_compiler_unsupported")
        val modelPermit = try { modelAuthority.authorize(input) } catch (_: Exception) { null }
            ?: return denied("detekt_full_model_not_authorized")
        if (!modelPermit.matches(this, input)) return denied("detekt_full_attempt_mismatch")
        val permit = try { authority.authorize(input) } catch (_: Exception) { null }
            ?: return denied("execution_not_authorized")
        if (permit !== modelPermit.executionPermit) return denied("detekt_full_execution_grant_mismatch")
        if (input.execution != permit.execution || input.analysisKey() != permit.expectedAnalysisKey ||
            input.snapshot.snapshotId != permit.snapshotId || Path.of(input.materializedRoot) != permit.materializedRoot) {
            return denied("detekt_full_snapshot_binding_mismatch")
        }
        val plan = modelPermit.plan
        try { modelPermit.verify(this, input) }
        catch (problem: Exception) { return denied(problemCode(problem, "detekt_full_binding_unavailable")) }
        val ownerFailure = AtomicReference<String?>()
        val wholeAttemptCancellation = CancellationToken {
            when {
                cancellation.isCancelled() -> true
                !modelPermit.matches(this, input) -> {
                    ownerFailure.compareAndSet(null, "detekt_full_attempt_mismatch"); true
                }
                else -> try { modelPermit.checkOwner(); false } catch (problem: Exception) {
                    ownerFailure.compareAndSet(null, problemCode(problem, "detekt_full_owner_checkpoint_failed")); true
                }
            }
        }
        val checkedAuthority = ExecutionAuthority { candidate ->
            if (candidate != input || candidate.execution != plan.execution || permit.execution != plan.execution ||
                !modelPermit.matches(this, candidate)) return@ExecutionAuthority null
            ExecutionPermit(permit.execution, permit.snapshotId, permit.materializedRoot, permit.expectedAnalysisKey, permit.verifySnapshot) { output ->
                permit.prepareOutput(output)
                Files.writeString(output.resolve("detekt.yml"), plan.configuration, Charsets.UTF_8)
                modelPermit.verify(this, input)
            }
        }
        val outcome = runner.run(input, checkedAuthority, wholeAttemptCancellation)
        val diagnostics = processDiagnostics(outcome)
        if (!outcome.successfulExecution) {
            return denied(ownerFailure.get() ?: outcome.reasons.joinToString(","),
                if (outcome.cancelled) "cancelled" else "unavailable", outcome.executed, diagnostics, outcome.metrics)
        }
        val reportBytes: ByteArray
        val report: DetektReport
        try {
            fullCheck(outcome.exitCode == 0, "detekt_full_analysis_failed",
                "A failed engine exit cannot supply a FULL observation.")
            modelPermit.verify(this, input)
            fullCheck(Digests.sha256(outcome.report("detekt.yml")) == plan.configurationDigest,
                "detekt_full_configuration_changed", "The executed configuration differs from the retained exact plan.")
            reportBytes = outcome.report("detekt.sarif")
            report = DetektReportParser().parse(reportBytes, input)
            requireReportProjection(plan, report)
            modelPermit.verify(this, input)
            wholeAttemptCancellation.throwIfCancelled()
        } catch (problem: Exception) {
            return denied(ownerFailure.get() ?: problemCode(problem, "detekt_full_report_unreliable"),
                "unknown", executed = true, diagnostics = diagnostics, metrics = outcome.metrics)
        }
        return try {
            val projected = projectReport(input, plan, report, Digests.sha256(reportBytes),
                requireNotNull(outcome.outputDirectory).resolve("detekt.sarif").toString(), diagnostics, outcome.metrics)
            // Projection is not a capability. Revocation, the ORIGINAL owner deadline, or
            // byte drift during projection must still discard the observation before return.
            modelPermit.verify(this, input)
            wholeAttemptCancellation.throwIfCancelled()
            projected
        } catch (problem: Exception) {
            denied(ownerFailure.get() ?: problemCode(problem, "detekt_full_projection_unreliable"),
                "unknown", executed = true, diagnostics = diagnostics, metrics = outcome.metrics)
        }
    }

    /** Pure projection controls may call this; doing so is NEVER Detekt/type-resolution evidence. */
    internal fun projectReport(input: AnalysisInput, plan: DetektExecutionPlan, report: DetektReport,
                               reportDigest: String, reportPath: String,
                               diagnostics: List<RawDiagnostic> = emptyList(),
                               metrics: AnalysisMetrics = AnalysisMetrics()): ProviderResult {
        requireReportProjection(plan, report)
        fullCheck(input.context.compilerVersion == DETEKT_EMBEDDED_KOTLIN && input.context.fullProject &&
            input.requiredRuleIds.distinct().size == input.requiredRuleIds.size &&
            activeRuleIds(input.requiredRuleIds) == plan.activeRuleIds,
            "detekt_full_projection_context_mismatch", "This FULL observation must retain the exact compatible-version strict model prerequisite.")
        val mapping = DetektCatalog.byId.getValue(DETEKT_FULL_MISSING_USE_CALL)
        val findings = report.diagnostics.map { diagnostic ->
            val fingerprint = Digests.sha256(listOf(mapping.rule_id, diagnostic.location.path,
                diagnostic.signature ?: (diagnostic.location.line.toString() + ":" + diagnostic.location.column + ":" + diagnostic.message),
                input.context.scope.id).joinToString("\u0000"))
            Finding(mapping.rule_id, mapping.title, diagnostic.message, mapping.rationale, diagnostic.location,
                fingerprint, id, input.context.scope, severity = "warning", kind = "advisory", certainty = "low",
                evidence = listOf(Evidence("upstream_sarif",
                    "Pinned Detekt2.0.0-alpha.6 FULL / embedded Kotlin2.4.10 Analysis API observation; modeled compiler2.4.10 only; no complete type-resolution, compatibility or release qualification.",
                    reportPath, reportDigest)),
                verification = listOf(Verification(expected = "Re-run the SAME exact FULL model/installation and inspect the resource closure.")),
                upstreamId = mapping.upstream_id, relatedLocations = diagnostic.relatedLocations)
        }
        val coverage = input.requiredRuleIds.distinct().map { ruleId ->
            abstention(ruleId, input.context.scope) ?: Coverage(ruleId, input.context.scope,
                if (findings.isEmpty()) "unknown" else "findings",
                "exact_full_upstream_observation_only_complete_resolution_and_qualification_unproven",
                depth = "semantic", provenance = "executed", implemented = true, tested = false, qualified = false)
        }
        val raw = diagnostics + report.diagnostics.map {
            RawDiagnostic(it.message, it.severity, it.sarifId, it.location)
        }
        return ProviderResult(id, "partial", findings, coverage,
            listOf("detekt_full_2.4.10_experimental_semantic_observation_only",
                "detekt_compatibility_tuple_unqualified", "detekt_complete_type_resolution_not_proven",
                "detekt_full_raw_compiler_arguments_retained_role_projected",
                "detekt_full_compiler_emission_not_executed", "detekt_full_initial_rule_slice_not_release_scope") +
                coverage.mapNotNull { it.reason }.distinct(),
            raw, metrics, version)
    }

    private fun abstention(ruleId: String, context: RuleContext): Coverage? {
        val mapping = DetektCatalog.byId[ruleId]
        val (state, reason) = when {
            mapping == null -> "unsupported" to "no_detekt_mapping"
            mapping.relation == "excluded_alias" -> "excluded" to "catalog_rule_explicitly_excluded"
            !mapping.exact -> "unsupported" to "catalog_refinement_not_implemented_raw_upstream_is_not_equivalent"
            ruleId != DETEKT_FULL_MISSING_USE_CALL -> "unavailable" to "detekt_full_rule_outside_initial_validation_slice"
            !settings.enabled -> "unavailable" to "rule_disabled_by_owner_selected_configuration"
            else -> return null
        }
        return Coverage(ruleId, context, state, reason, depth = mapping?.depth ?: "semantic",
            provenance = "not_executed", implemented = false, tested = false, qualified = false)
    }

    private fun requireReportProjection(plan: DetektExecutionPlan, report: DetektReport) {
        val expected = setOf(DetektCatalog.byId.getValue(DETEKT_FULL_MISSING_USE_CALL).sarifId)
        fullCheck(settings.enabled && plan.analysisMode == "full" && plan.activeRuleIds == setOf(DETEKT_FULL_MISSING_USE_CALL) &&
            report.enabledSarifIds == expected && report.diagnostics.all { it.sarifId in expected },
            "detekt_full_report_projection_mismatch",
            "FULL requires the exact enabled semantic descriptor even for a negative; no LIGHT, disabled or foreign result may be relabeled.")
    }

    private fun configuration(): String {
        val text = StringBuilder("config:\n  validation: true\n  warningsAsErrors: true\nprocessors:\n  active: false\nconsole-reports:\n  active: false\n")
        val sets = (DetektCatalog.mappings.map { it.upstream_id.substringBefore(':') } + "coroutines").distinct().sorted()
        for (set in sets) {
            text.append(set).append(":\n  active: ").append(set == "potential-bugs").append('\n')
            if (set == "potential-bugs") {
                text.append("  MissingUseCall:\n    active: true\n    ignoreClass: ")
                    .append(JsonArray(settings.ignoreClasses.map(::JsonPrimitive)).toString()).append('\n')
            }
        }
        return text.toString()
    }

    private fun processDiagnostics(outcome: ProcessOutcome): List<RawDiagnostic> =
        listOf(outcome.stdout to "info", outcome.stderr to "error").filter { it.first.isNotBlank() }
            .map { RawDiagnostic(it.first.take(8192), it.second) }
}
