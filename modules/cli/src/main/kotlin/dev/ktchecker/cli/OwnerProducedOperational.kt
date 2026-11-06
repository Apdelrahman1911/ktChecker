package dev.ktchecker.cli

import dev.ktchecker.compiler.live.model.KotlinJvmModelLiveCompilerProvider
import dev.ktchecker.contracts.*
import dev.ktchecker.kernel.ObservedExportPlanEvidence
import dev.ktchecker.kernel.OwnerFullCatalog1665
import dev.ktchecker.projectmodel.*
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline

/**
 * Pure fixed-shape classification, NOT compiler/output authority. The caller separately requires
 * the actual archive-bound compiler return and (for a producer) its under-ARM accepted output
 * token. Never strip failures/reasons and ask whether the remainder looks complete. Both child
 * and exact coordinator projection are checked; any normalization/substitution or unknown raw
 * diagnostic fails. Every full-catalog missing/disabled row remains visible and unqualified.
 */
internal fun ownerProducedParentFaults(catalog: Catalog, coordinator: AnalysisProvider, modelProvider: AnalysisProvider,
    input: AnalysisInput, childInput: AnalysisInput, child: ProviderResult, callElapsedMillis: Long,
    deadline: OwnedApplicationDeadline, parent: ProviderResult, observedModel: ObservedProjectModel? = null,
    observedEvidence: ObservedExportPlanEvidence? = null): List<String> {
    fun fault(code: String) = listOf(code)
    if ((observedModel == null) != (observedEvidence == null)) return fault("owner_observed_origin_mismatch")
    if (observedModel != null && observedEvidence != null) {
        val binding = observedEvidence.binding
        if (observedModel.namespace != ModelNamespace(binding.ownerId, binding.workspaceId, binding.worktreeId, binding.lineageId) ||
            observedModel.invocation.originalDeadline !== deadline ||
            observedEvidence != ObservedExportPlanEvidence(observedEvidence.binding, observedModel.invocation.identity, observedModel.evidenceIdentity) ||
            observedEvidence.policies().any { input.context.policies[it.key] != it.value } ||
            input.snapshot != observedModel.snapshot || input.context.modelDigest != observedModel.identity.modelDigest ||
            input.context.configurationDigest != observedModel.identity.configurationDigest || input.context.fullProject) {
            return fault("owner_observed_origin_mismatch")
        }
        // Never add the missing gap to a child result or infer it from input metadata. Actual
        // under-ARM typed-origin rendering must have supplied exactly this raw reason itself.
        if (CONFIGURATION_INPUT_CLOSURE_UNPROVEN !in child.reasons) return fault("owner_observed_raw_taint_missing")
    } else if (input.context.policies.keys.any { it == OBSERVED_ASSURANCE_POLICY || it.startsWith("ktchecker.observed-") }) return fault("owner_observed_origin_required")
    if (input.requiredRuleIds != catalog.ids.sorted() || !OwnerFullCatalog1665.accepts(input.requiredRuleIds) || input.context.fullProject ||
        coordinator.id != "ktchecker-coordinator" || modelProvider.id != KotlinJvmModelLiveCompilerProvider.PROVIDER_ID ||
        modelProvider.version != KotlinJvmModelLiveCompilerProvider.VERSION) return fault("owner_produced_operational_identity_mismatch")
    val activation = validatedActivation(input, catalog)
    val selected = input.requiredRuleIds.filter { activation[it]?.active == true && it in modelProvider.supportedRuleIds }.sorted()
    if (selected.isEmpty() || childInput != input.copy(requiredRuleIds = selected, execution = null) ||
        child.providerId != modelProvider.id || child.providerVersion != modelProvider.version || child.status != "partial") {
        return fault("owner_produced_compiler_return_not_accepted")
    }
    val snapshotFiles = input.snapshot.files.associateBy { it.path }
    if (child.findings.any { finding ->
        finding.providerId != modelProvider.id || finding.ruleId !in selected || finding.context != input.context.scope ||
            finding.disposition != "open" || finding.severity != "warning" || finding.upstreamId == null ||
            finding.semanticDigest != childInput.analysisKey() || !Digests.valid(finding.fingerprint) ||
            snapshotFiles[finding.location.path]?.digest != finding.location.fileDigest
    }) return fault("owner_produced_compiler_finding_unrecognized")
    val expectedChildCoverage = selected.map { rule -> Coverage(rule, input.context.scope, "unqualified",
        "compiler_model_live_preview_not_qualified", depth = "build", provenance = "executed",
        implemented = child.findings.any { it.ruleId == rule }, tested = false, qualified = false) }
    if (child.coverage != expectedChildCoverage) return fault("owner_produced_compiler_coverage_unrecognized")

    val mandatory = setOf("compiler_model_live_preview_not_qualified", "compiler_model_live_semantic_extraction_not_authorized",
        "compiler_model_live_elapsed_measured_to_armed_render_not_call_completion", "compiler_model_live_cpu_rss_unavailable",
        "compiler_model_live_produced_output_preview_not_qualified") +
        if (observedModel == null) emptySet() else setOf(CONFIGURATION_INPUT_CLOSURE_UNPROVEN)
    val suppression = "compiler_model_live_suppression_scope_not_certified"
    val warnings = "compiler_model_live_warnings_disabled_under_selected_configuration"
    val logging = setOf("compiler_model_live_informational_logging_redacted", "compiler_model_live_unmapped_diagnostic",
        "compiler_model_live_raw_diagnostics_unresolved")
    val reasons = child.reasons.toSet()
    val allowed = mandatory + setOf(suppression, warnings) + if (child.rawDiagnostics.isEmpty()) emptySet() else logging
    val redacted = RawDiagnostic("Compiler informational logging was observed; private log text is not published.",
        "info", null, null)
    if (child.reasons.size != reasons.size || !reasons.containsAll(mandatory) || reasons.any { it !in allowed } ||
        (warnings in reasons) != ("-nowarn" in childInput.context.compilerArguments) ||
        child.rawDiagnostics.any { it != redacted } ||
        child.rawDiagnostics.isNotEmpty() && !reasons.containsAll(logging)) {
        return fault("owner_produced_compiler_diagnostic_unrecognized")
    }
    val ageNanos = System.nanoTime() - deadline.startedAtNanos
    if (ageNanos < 0 || deadline.isExpired() || callElapsedMillis < 0 ||
        child.metrics.cpuMillis != null || child.metrics.peakRssBytes != null ||
        child.metrics.elapsedMillis !in 0..callElapsedMillis || child.metrics.outputBytes !in 1..1_048_576 ||
        parent.metrics.elapsedMillis < child.metrics.elapsedMillis || parent.metrics.elapsedMillis > ageNanos / 1_000_000 + 1 ||
        parent.metrics.elapsedMillis > deadline.durationMillis) return fault("owner_produced_metrics_unrecognized")

    // normalizeChild must be a no-op: selected coverage is total and every finding identity was
    // checked above. Reproduce the SINGLE selected model route and all missing catalog rows.
    val missing = (input.requiredRuleIds.toSet() - selected.toSet()).sorted().map { rule ->
        val decision = activation[rule]
        Coverage(rule, input.context.scope,
            if (decision?.reason == "disabled_by_explicit_owner_policy") "disabled" else "unavailable",
            when {
                decision == null -> "trusted_activation_plan_missing"
                !decision.active -> decision.reason
                else -> "no_compatible_installed_rule_implementation"
            }, depth = catalog.records.firstOrNull { it["id"]?.toString()?.trim('"') == rule }
                ?.get("depth")?.toString()?.trim('"') ?: "semantic")
    }
    val findings = child.findings.distinctBy {
        listOf(it.ruleId, it.context.id, it.location.path, it.location.fileDigest, it.location.line,
            it.location.column, it.location.endLine, it.location.endColumn, it.diagnosis)
    }
    val expected = ProviderResult(coordinator.id, "partial", findings, (child.coverage + missing).sortedBy { it.ruleId },
        reasons = (child.reasons.map { "${child.providerId}:$it" } +
            (if (missing.isEmpty()) emptyList() else listOf("required_rule_coverage_unavailable")) +
            "preview_not_release_qualified").distinct(), rawDiagnostics = child.rawDiagnostics,
        metrics = AnalysisMetrics(parent.metrics.elapsedMillis, null, null, child.metrics.outputBytes), providerVersion = coordinator.version)
    if (parent != expected) return fault("owner_produced_coordinator_return_substituted")
    return emptyList()
}
