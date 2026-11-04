package dev.ktchecker.providers.lint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Path

data class LintRegistryArtifact(val registry: String, val artifact: ArtifactIdentity)

/** Owner-selected execution metadata; source/configuration cannot create this plan or its authority. */
data class LintRunPlan(
    val lintVersion: String,
    val registries: List<LintRegistryArtifact>,
    val checkedRuleIds: Set<String>,
    val reportPath: String = "lint-results.xml",
    val expectedClientRevision: String = when (lintVersion) {
        "32.3.1" -> "9.3.1"
        "32.5.0" -> "9.5.0"
        else -> lintVersion
    },
    val sourceSets: Map<String, String> = emptyMap(),
    val excludedPathPrefixes: Set<String> = setOf("build/", "src/test/", "src/androidTest/"),
    val explicitlyIncludedPaths: Set<String> = emptySet(),
    val disabledRuleIds: Set<String> = emptySet(),
    val requiredTaskPrefixes: Set<String> = emptySet(),
)

class AndroidLintProvider(
    private val authority: ExecutionAuthority = ExecutionAuthority.DENY,
    plan: LintRunPlan? = null,
    private val runner: IsolatedProcessRunner = IsolatedProcessRunner(),
) : AnalysisProvider {
    private val plan = plan?.let { selected -> selected.copy(registries = selected.registries.toList(), checkedRuleIds = selected.checkedRuleIds.toSet(),
        sourceSets = selected.sourceSets.toMap(), excludedPathPrefixes = selected.excludedPathPrefixes.toSet(),
        explicitlyIncludedPaths = selected.explicitlyIncludedPaths.toSet(), disabledRuleIds = selected.disabledRuleIds.toSet(),
        requiredTaskPrefixes = selected.requiredTaskPrefixes.toSet()) }
    override val id = "android-lint"
    override val version: String get() = plan?.lintVersion ?: "32.3.1/32.5.0"
    override val supportedRuleIds: Set<String> get() = LintCatalog.ruleIds

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val requested = input.requiredRuleIds.takeIf { it.isNotEmpty() }?.toSet() ?: supportedRuleIds
        val activePlan = plan
        fun unavailable(reason: String, status: String = "unavailable", metrics: AnalysisMetrics = AnalysisMetrics()) =
            ProviderResult(id, status, coverage = requested.map { coverage(it, input, status, reason) },
                reasons = listOf(reason), metrics = metrics, providerVersion = version)
        if (input.context.scope.target != "android") return unavailable("android_compilation_required")
        if (activePlan == null) return unavailable("lint_trusted_plan_unavailable")
        if (activePlan.lintVersion !in setOf("32.3.1", "32.5.0")) return unavailable("lint_version_unavailable")
        if (!input.context.fullProject || listOf(input.context.modelDigest, input.context.dependencyDigest,
                input.context.toolchainDigest, input.context.configurationDigest).any { it == null || !Digests.valid(it) }) {
            return unavailable("lint_resolved_frozen_model_required")
        }
        try {
            safeRelativePath(activePlan.reportPath)
            if (activePlan.registries.isEmpty() || activePlan.registries.map { it.registry }.toSet().size != activePlan.registries.size) {
                throw ExecutionProblem("lint_registry_plan_invalid", "Exact artifact registry set is required")
            }
            activePlan.registries.forEach { verifyArtifact(it, activePlan.lintVersion) }
            if (!supportedRuleIds.containsAll(activePlan.checkedRuleIds) ||
                !supportedRuleIds.containsAll(activePlan.disabledRuleIds)) {
                throw ExecutionProblem("lint_rule_plan_unknown", "Unknown planned issue identity")
            }
        } catch (e: Exception) { return unavailable(problemCode(e, "lint_plan_invalid")) }

        val selected = requested.intersect(activePlan.checkedRuleIds).filter { ruleId ->
            val rule = LintCatalog.byRuleId[ruleId]
            rule != null && ruleId !in activePlan.disabledRuleIds &&
                rule.versions.any { it.lintVersion == activePlan.lintVersion } &&
                activePlan.registries.any { it.registry == rule.registry }
        }.toSet()
        if (selected.isEmpty()) return unavailable("lint_no_executable_selected_rules")
        val attempt = runner.run(input, authority, cancellation)
        if (!attempt.successfulExecution) {
            val reason = attempt.reasons.firstOrNull() ?: "lint_execution_failed"
            return unavailable(reason, attempt.status, attempt.metrics).copy(reasons = attempt.reasons.ifEmpty { listOf(reason) })
        }
        val findings = mutableListOf<Finding>()
        val raw = mutableListOf<RawDiagnostic>()
        val errors = mutableListOf<String>()
        var reportDigest: String? = null
        try {
            activePlan.registries.forEach { verifyArtifact(it, activePlan.lintVersion) }
            val bytes = attempt.report(activePlan.reportPath)
            reportDigest = Digests.sha256(bytes)
            val report = LintXmlParser.parse(bytes, activePlan.expectedClientRevision)
            val sources = SnapshotSources(input)
            for (issue in report.issues) {
                val rule = try { LintCatalog.resolve(issue.upstreamId, activePlan.lintVersion, activePlan.registries.map { it.registry }.toSet()) }
                catch (e: ExecutionProblem) {
                    errors += e.code
                    raw += RawDiagnostic(issue.message, issue.severity, issue.upstreamId)
                    continue
                }
                if (rule.ruleId !in selected) {
                    errors += "lint_unselected_issue:${rule.ruleId}"
                    raw += RawDiagnostic(issue.message, issue.severity, issue.upstreamId)
                    continue
                }
                if ((issue.includedVariants.isNotEmpty() && input.context.scope.compilation !in issue.includedVariants) ||
                    input.context.scope.compilation in issue.excludedVariants) {
                    errors += "lint_variant_binding_mismatch:${rule.ruleId}"
                    continue
                }
                try {
                    if (issue.locations.isEmpty()) throw ExecutionProblem("lint_location_unavailable", "Provider did not supply a captured source location")
                    val locations = issue.locations.map { sources.location(it.file, it.line, it.column, it.endLine, it.endColumn) }
                    val primary = locations.first()
                    if (input.context.compilationFiles?.let { primary.path !in it } == true) {
                        throw ExecutionProblem("lint_location_outside_compilation", "Captured file is not a member of the selected Android model")
                    }
                    if (primary.path !in activePlan.explicitlyIncludedPaths &&
                        activePlan.excludedPathPrefixes.any { primary.path.startsWith(it) }) {
                        errors += "lint_excluded_source_reported:${primary.path}"
                        raw += RawDiagnostic(issue.message, issue.severity, issue.upstreamId, primary)
                        continue
                    }
                    val set = activePlan.sourceSets.entries.filter { primary.path.startsWith(it.key) }
                        .maxByOrNull { it.key.length }?.value ?: input.context.scope.sourceSet
                    val scope = input.context.scope.copy(sourceSet = set)
                    val artifact = activePlan.registries.single { it.registry == rule.registry }.artifact
                    findings += Finding(
                        rule.ruleId, rule.title, issue.message,
                        issue.explanation.ifBlank { "Provider evidence is not per-ID precision qualification." }, primary,
                        Digests.sha256(listOf(rule.ruleId, primary.path, primary.fileDigest, primary.line.toString(),
                            primary.column.toString(), scope.id, artifact.digest).joinToString("\u0000")),
                        id, scope, issue.severity, rule.kind, rule.certainty,
                        evidence = listOf(
                            Evidence("android_lint_report", "${report.by}; immutable snapshot ${input.snapshot.snapshotId}",
                                activePlan.reportPath, reportDigest),
                            Evidence("provider_artifact", "${artifact.coordinate}; ${rule.registry}; upstream ${issue.upstreamId}",
                                artifact.coordinate, artifact.digest),
                        ),
                        fixHint = "Inspect the issue-specific provider evidence; re-run the same frozen Android variant after the repair.",
                        verification = listOf(Verification(expected = "No ${issue.upstreamId} in the same Android variant", command = attempt.command)),
                        upstreamId = issue.upstreamId, relatedLocations = locations.drop(1),
                    )
                } catch (e: Exception) {
                    errors += problemCode(e, "lint_location_invalid")
                    raw += RawDiagnostic(issue.message, issue.severity, issue.upstreamId)
                }
            }
        } catch (e: Exception) { errors += problemCode(e, "lint_report_invalid") }
        if (attempt.exitCode !in setOf(0, 1)) errors += "lint_provider_exit:${attempt.exitCode}"
        if (attempt.exitCode == 1 && findings.none { it.severity == "error" } && raw.none { it.severity == "error" }) {
            errors += "lint_nonzero_without_source_error"
        }
        if (attempt.stderr.contains("Exception in thread") || attempt.stderr.contains("Could not initialize class")) {
            errors += "lint_provider_crashed"
        }
        if (activePlan.requiredTaskPrefixes.isNotEmpty()) {
            val tasks = Regex("(?m)^> Task (:[^ ]+)(?: (UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE|FAILED))?\\s*$")
                .findAll(attempt.stdout).map { it.groupValues[1].substringAfterLast(':') to it.groupValues[2] }.toList()
            if (tasks.none { (name, outcome) -> name != "lint" && activePlan.requiredTaskPrefixes.any { name.startsWith(it) } &&
                    outcome in setOf("", "FAILED") }) errors += "lint_task_execution_unverified"
            if (tasks.any { (name, outcome) -> outcome == "FAILED" && activePlan.requiredTaskPrefixes.none { name.startsWith(it) } }) {
                errors += "lint_unrelated_task_failure"
            }
        }
        val rows = requested.map { ruleId ->
            val rule = LintCatalog.byRuleId[ruleId]
            val reason = when {
                rule == null -> "lint_rule_unknown"
                rule.versions.none { it.lintVersion == activePlan.lintVersion } -> "lint_issue_version_unavailable"
                activePlan.registries.none { it.registry == rule.registry } -> "lint_provider_artifact_unavailable"
                ruleId in activePlan.disabledRuleIds -> "lint_rule_disabled_by_provider_configuration"
                ruleId !in selected -> "lint_rule_not_selected"
                errors.isNotEmpty() -> "lint_output_unreliable"
                else -> "per_id_precision_and_corpus_qualification_pending"
            }
            coverage(ruleId, input, if (ruleId in selected) "incomplete" else "unavailable", reason,
                executed = ruleId in selected, certificate = if (ruleId in selected && errors.isEmpty()) reportDigest else null)
        }
        return ProviderResult(id, if (errors.isEmpty()) "incomplete" else "failed", findings.distinctBy { it.fingerprint }, rows,
            (errors + rows.mapNotNull { it.reason }).distinct(), raw, attempt.metrics, version)
    }

    private fun verifyArtifact(registry: LintRegistryArtifact, lintVersion: String) {
        val expected = LintCatalog.rules.filter { it.registry == registry.registry }
            .flatMap { it.versions }.filter { it.lintVersion == lintVersion }.distinctBy { it.coordinate to it.binaryDigest }
        if (expected.size != 1 || registry.artifact.coordinate != expected.single().coordinate ||
            registry.artifact.digest != expected.single().binaryDigest ||
            fileDigest(Path.of(registry.artifact.path)) != registry.artifact.digest) {
            throw ExecutionProblem("lint_provider_artifact_mismatch", "Selected provider artifact is unavailable or differs from frozen registry")
        }
    }

    private fun coverage(ruleId: String, input: AnalysisInput, state: String, reason: String,
                         executed: Boolean = false, certificate: String? = null): Coverage {
        val rule = LintCatalog.byRuleId[ruleId]
        return Coverage(ruleId, input.context.scope, state, reason, depth = rule?.depth ?: "build",
            provenance = if (executed) "executed" else "not_executed", certificateId = certificate,
            implemented = rule != null,
            // Real XML controls exercise two IDs, not the six case types and independent
            // corpus review required by the release evidence gate for any exact context.
            tested = false,
            qualified = false)
    }
}
