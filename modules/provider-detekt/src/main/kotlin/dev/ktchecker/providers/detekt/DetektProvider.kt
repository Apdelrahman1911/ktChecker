package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Owner-selected installation; this type itself grants no execution authority. */
data class DetektInstallation(val javaExecutable: Path, val artifactDirectory: Path, val jdkHome: Path)

/** Options are rendered as JSON flow values in YAML, not concatenated executable arguments. */
data class DetektSettings(
    val disabledRuleIds: Set<String> = emptySet(),
    val options: Map<String, Map<String, JsonElement>> = emptyMap(),
    val languageVersion: String = "2.4",
    val apiVersion: String = "2.4",
    val jvmTarget: String = "21",
    val rawGuidanceForRefinements: Boolean = true,
)

data class DetektExecutionPlan(
    val execution: TrustedExecution,
    val configuration: String,
    val configurationDigest: String,
    val artifacts: List<ArtifactIdentity>,
    val activeRuleIds: Set<String>,
    val analysisMode: String,
    /** Inert facet metadata, never a projection/execution capability. */
    val composeApplicability: DetektComposeApplicability = DetektComposeApplicability(),
)

/**
 * An isolated adapter to the pinned upstream engine. An injected host authority
 * and exact plan are required. All compatibility tuples remain unqualified:
 * embedded Kotlin2.4.10 is not the project's Kotlin2.4.21 compiler.
 */
class DetektProvider(
    private val installation: DetektInstallation? = null,
    private val authority: ExecutionAuthority = ExecutionAuthority.DENY,
    settings: DetektSettings = DetektSettings(),
    private val runner: IsolatedProcessRunner = IsolatedProcessRunner(),
    val inputMode: DetektInputMode = DetektInputMode.LEGACY_EXPLICIT_INPUT,
    private val modelAuthority: DetektModelAuthority = DetektModelAuthority.DENY,
    private val libraryAuthority: DetektLibraryAuthority = DetektLibraryAuthority.DENY,
) : AnalysisProvider {
    // Retained caller maps/JSON values cannot rewrite an already prepared model provider.
    private val settings = settings.copy(
        disabledRuleIds = Collections.unmodifiableSet(settings.disabledRuleIds.toSet()),
        options = Collections.unmodifiableMap(settings.options.mapValues { (_, options) ->
            Collections.unmodifiableMap(options.mapValues { (_, value) -> Json.parseToJsonElement(value.toString()) })
        }),
    )
    init {
        require(inputMode == DetektInputMode.VERIFIED_MODEL_LIGHT || modelAuthority === DetektModelAuthority.DENY) {
            "A model authority cannot be installed on a legacy/fallback route"
        }
    }
    override val id: String = "detekt"
    override val version: String = DETEKT_VERSION
    override val supportedRuleIds: Set<String> = DetektCatalog.mappings.filter {
        it.exact && (inputMode != DetektInputMode.VERIFIED_MODEL_LIGHT || it.depth == "syntax")
    }.map { it.rule_id }.toSet()
    val mappedRuleIds: Set<String> = DetektCatalog.byId.keys

    fun executionProfile(input: AnalysisInput): TrustedExecution = executionPlan(input).execution

    /** Host helper only. A serialized approved=true plan is deliberately insufficient. */
    fun executionPlan(input: AnalysisInput, timeoutMillis: Long = 60_000, maxOutputBytes: Int = 1_048_576): DetektExecutionPlan {
        if (inputMode != DetektInputMode.LEGACY_EXPLICIT_INPUT || detektModelBearingInput(input)) {
            throw ExecutionProblem("detekt_model_binding_required", "Model LIGHT planning requires the owner's live typed preparation, not request tags.")
        }
        return createExecutionPlan(freezeDetektInput(input), timeoutMillis, maxOutputBytes, null)
    }

    internal fun modelExecutionPlan(input: AnalysisInput, binding: DetektModelBindingIdentity,
                                    timeoutMillis: Long, maxOutputBytes: Int): DetektExecutionPlan {
        detektModelCheck(inputMode == DetektInputMode.VERIFIED_MODEL_LIGHT && Digests.valid(binding.digest),
            "detekt_model_provider_mismatch", "Only this fixed model route may create its exact LIGHT child profile.")
        return createExecutionPlan(input, timeoutMillis, maxOutputBytes, binding.digest)
    }

    private fun createExecutionPlan(input: AnalysisInput, timeoutMillis: Long, maxOutputBytes: Int,
                                    modelBindingDigest: String?): DetektExecutionPlan {
        val installed = installation ?: throw ExecutionProblem("detekt_installation_unavailable", "No pinned Detekt installation selected")
        val files = compilationFiles(input)
        val artifacts = DetektCatalog.verifyArtifacts(installed.artifactDirectory)
        val compose = selectCompose(input).applicability
        val semantic = modelBindingDigest == null && semanticPrerequisites(input)
        val active = input.requiredRuleIds.toSet().filter { ruleId ->
            val rule = DetektCatalog.byId[ruleId]
            rule != null && (if (modelBindingDigest != null) rule.exact && rule.depth == "syntax"
                else rule.exact || (rule.relation == "requires_refinement" && settings.rawGuidanceForRefinements)) &&
                ruleId !in settings.disabledRuleIds && (rule.depth == "syntax" || semantic) &&
                (!DetektComposeSurfaces.isCompose(ruleId) || compose.rules[ruleId]?.executableFacets == true)
        }.toSet()
        val mode = if (active.any { DetektCatalog.byId.getValue(it).depth != "syntax" }) "full" else "light"
        val configuration = configuration(active)
        val configurationDigest = Digests.sha256(configuration)
        val cli = artifacts.single { it.coordinate == "dev.detekt:detekt-cli:$DETEKT_VERSION:all" }.path
        val plugins = artifacts.filterNot { it.path == cli }.joinToString(File.pathSeparator) { it.path }
        val arguments = mutableListOf(
            "-Xmx384m", "-Djava.io.tmpdir={output}/tmp",
            // Fixed output names alone do not bind the owner-selected bytes.
            "-Dktchecker.detekt.configurationSha256=$configurationDigest",
            "-Dktchecker.detekt.artifactsSha256=${Digests.sha256(artifacts.sortedBy { it.coordinate }.joinToString("\u0000") { "${it.coordinate}:${it.digest}" })}",
            "-jar", cli,
            "--input", files.joinToString(File.pathSeparator) { "{snapshot}/$it" }, "--base-path", "{snapshot}",
            "--analysis-mode", mode, "--config", "{output}/detekt.yml",
            "--plugins", plugins, "--report", "sarif:{output}/detekt.sarif",
            "--fail-on-severity", "Never",
        )
        modelBindingDigest?.let { arguments.add(1, DetektVerifiedModelBinding.PROFILE_PROPERTY + it) }
        compose.bindingDigest?.let { arguments.add(1, DetektLibraryPermit.PROFILE_PROPERTY + it) }
        if (mode == "full") {
            verifyClasspath(input)
            arguments += listOf("--classpath", input.context.classpath.joinToString(File.pathSeparator) { Path.of(it.path).toAbsolutePath().normalize().toString() },
                "--language-version", settings.languageVersion, "--api-version", settings.apiVersion,
                "--jvm-target", settings.jvmTarget, "--jdk-home", installed.jdkHome.toAbsolutePath().normalize().toString())
        }
        val executable = installed.javaExecutable.toAbsolutePath().normalize()
        return DetektExecutionPlan(
            freezeDetektProfile(TrustedExecution(executable.toString(), arguments, approved = true, executableDigest = fileDigest(executable),
                timeoutMillis = timeoutMillis, maxOutputBytes = maxOutputBytes)),
            configuration, configurationDigest, immutableDetektList(artifacts), Collections.unmodifiableSet(active), mode, compose,
        )
    }

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult =
        analyzeFrozen(freezeDetektInput(input), cancellation)

    private fun analyzeFrozen(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val requested = input.requiredRuleIds.distinct()
        if (requested.isEmpty()) return ProviderResult(id, "partial", reasons = listOf("no_explicit_detekt_rules_requested"), providerVersion = version)
        fun unavailable(reason: String, state: String = "unavailable") = ProviderResult(
            id, if (state == "cancelled") "cancelled" else "partial",
            coverage = requested.map { modelAbstention(it, input.context.scope) ?:
                Coverage(it, input.context.scope, state, reason, provenance = "not_executed") },
            reasons = listOf(reason), providerVersion = version,
        )
        if (cancellation.isCancelled()) return unavailable("cancelled_before_execution", "cancelled")
        val compose = selectCompose(input)
        // No meaningful native launch when every requested rule is an unavailable Compose facet.
        // In a mixed request, genuine generic Kotlin/Detekt work still follows the existing route.
        if (requested.all { DetektComposeSurfaces.isCompose(it) } && requested.none {
                it !in settings.disabledRuleIds && compose.applicability.rules[it]?.executableFacets == true
            }) {
            val coverage = requested.map { modelAbstention(it, input.context.scope) ?:
                composeAbstention(it, input.context.scope, compose.applicability) ?:
                Coverage(it, input.context.scope, "unavailable", "rule_disabled_by_owner_selected_configuration") }
            return ProviderResult(id, "partial", coverage = coverage,
                reasons = coverage.mapNotNull { it.reason }.distinct(), providerVersion = version)
        }
        val modelPermit = if (inputMode == DetektInputMode.VERIFIED_MODEL_LIGHT) {
            val selected = try { modelAuthority.authorize(input) } catch (_: Exception) { null }
                ?: return unavailable("detekt_model_not_authorized")
            if (!selected.matches(this, input)) return unavailable("detekt_model_attempt_mismatch")
            selected
        } else {
            if (detektModelBearingInput(input)) return unavailable("detekt_model_route_required")
            null
        }
        if (modelPermit == null && input.context.scope.target !in setOf("jvm", "android", "desktop")) {
            return unavailable("detekt_jvm_provider_does_not_qualify_common_or_native_targets", "unsupported")
        }
        val selectedPermit = try { authority.authorize(input) } catch (_: Exception) { null }
            ?: return unavailable("execution_not_authorized")
        if (modelPermit != null && selectedPermit !== modelPermit.executionPermit) {
            return unavailable("detekt_model_execution_grant_mismatch")
        }
        if (input.execution != selectedPermit.execution || input.analysisKey() != selectedPermit.expectedAnalysisKey ||
            input.snapshot.snapshotId != selectedPermit.snapshotId ||
            Path.of(input.materializedRoot).toAbsolutePath().normalize() != selectedPermit.materializedRoot.toAbsolutePath().normalize()) {
            return unavailable("snapshot_binding_mismatch")
        }
        val plan = try {
            val execution = input.execution
            modelPermit?.plan ?: executionPlan(input, execution?.timeoutMillis ?: 60_000, execution?.maxOutputBytes ?: 1_048_576)
        } catch (problem: Exception) {
            return unavailable(problemCode(problem, "detekt_plan_unavailable"))
        }
        try { verifyComposePlan(input, plan, compose.permit) }
        catch (problem: Exception) { return unavailable(problemCode(problem, "detekt_compose_binding_changed")) }
        val checkedAuthority = ExecutionAuthority { candidate ->
            val permit = selectedPermit
            if (candidate.execution != plan.execution || permit.execution != plan.execution ||
                (modelPermit != null && !modelPermit.matches(this, candidate))) return@ExecutionAuthority null
            ExecutionPermit(permit.execution, permit.snapshotId, permit.materializedRoot, permit.expectedAnalysisKey, permit.verifySnapshot) { output ->
                permit.prepareOutput(output)
                Files.writeString(output.resolve("detekt.yml"), plan.configuration, Charsets.UTF_8)
                modelPermit?.verify(this, input)
                verifyComposePlan(input, plan, compose.permit)
            }
        }
        val wholeAttemptCancellation = CancellationToken {
            cancellation.isCancelled() || (compose.permit != null && !compose.permit.matches(this, input))
        }
        val outcome = runner.run(input, checkedAuthority, wholeAttemptCancellation)
        if (!outcome.successfulExecution) {
            return ProviderResult(id, if (outcome.cancelled) "cancelled" else "partial",
                coverage = requested.map { modelAbstention(it, input.context.scope) ?:
                    composeAbstention(it, input.context.scope, compose.applicability) ?:
                    Coverage(it, input.context.scope, if (outcome.cancelled) "cancelled" else "unavailable",
                        outcome.reasons.joinToString(","), provenance = if (outcome.executed) "executed" else "not_executed") },
                reasons = outcome.reasons, rawDiagnostics = processDiagnostics(input, outcome), metrics = outcome.metrics, providerVersion = version)
        }
        val reportBytes: ByteArray
        val report: DetektReport
        try {
            require(outcome.exitCode == 0) { "detekt_exit_${outcome.exitCode}" }
            DetektCatalog.verifyArtifacts(installation!!.artifactDirectory)
            verifyComposePlan(input, plan, compose.permit)
            if (plan.analysisMode == "full") verifyClasspath(input)
            require(fileDigest(outcome.outputDirectory!!.resolve("detekt.yml")) == plan.configurationDigest)
            reportBytes = outcome.report("detekt.sarif")
            report = DetektReportParser().parse(reportBytes, input)
            if (modelPermit != null) {
                requireModelLightReportProjection(plan, report)
                modelPermit.verify(this, input)
            }
        } catch (problem: Exception) {
            val reason = problemCode(problem, if (outcome.exitCode != 0) "detekt_analysis_failed_exit_${outcome.exitCode}" else "detekt_report_unreliable")
            return ProviderResult(id, "partial", coverage = requested.map { modelAbstention(it, input.context.scope) ?:
                Coverage(it, input.context.scope, "unknown", reason, provenance = "executed") },
                reasons = listOf(reason), rawDiagnostics = processDiagnostics(input, outcome), metrics = outcome.metrics, providerVersion = version)
        }
        // Only this successful, reverified native-observation path may project Compose facets.
        val result = projectReportObserved(input, plan, report, Digests.sha256(reportBytes),
            outcome.outputDirectory!!.resolve("detekt.sarif").toString(), processDiagnostics(input, outcome), outcome.metrics,
            compose.applicability)
        try { verifyComposePlan(input, plan, compose.permit) }
        catch (problem: Exception) { return unavailable(problemCode(problem, "detekt_compose_binding_changed"), "unknown") }
        if (modelPermit != null && !modelPermit.matches(this, input)) {
            val reason = "detekt_model_attempt_mismatch"
            return ProviderResult(id, "partial", coverage = requested.map { modelAbstention(it, input.context.scope) ?:
                Coverage(it, input.context.scope, "unknown", reason, provenance = "executed") },
                reasons = listOf(reason), rawDiagnostics = processDiagnostics(input, outcome), metrics = outcome.metrics, providerVersion = version)
        }
        return result
    }

    /** Pure generic SARIF projection; copied plans/SARIF cannot mint Compose observation authority. */
    internal fun projectReport(input: AnalysisInput, plan: DetektExecutionPlan, report: DetektReport,
                               reportDigest: String, reportPath: String, diagnostics: List<RawDiagnostic> = emptyList(),
                               metrics: AnalysisMetrics = AnalysisMetrics()): ProviderResult =
        projectReportObserved(input, plan, report, reportDigest, reportPath,
            if (input.requiredRuleIds.any(DetektComposeSurfaces::isCompose)) diagnostics.map {
                it.copy(message = "Compose diagnostic withheld: unattributed raw text cannot establish API-family applicability.")
            } else diagnostics, metrics, null)

    private fun projectReportObserved(input: AnalysisInput, plan: DetektExecutionPlan, report: DetektReport,
                                      reportDigest: String, reportPath: String, diagnostics: List<RawDiagnostic>,
                                      metrics: AnalysisMetrics,
                                      composeObservation: DetektComposeApplicability?): ProviderResult {
        val compose = composeObservation ?: DetektComposeSurfaces.unavailable(input,
            "detekt_compose_execution_observation_required")
        val requested = input.requiredRuleIds.distinct()
        val modelLight = inputMode == DetektInputMode.VERIFIED_MODEL_LIGHT
        if (modelLight) requireModelLightReportProjection(plan, report)
        val findings = mutableListOf<Finding>()
        val raw = diagnostics.toMutableList()
        val reasons = mutableListOf("detekt_compatibility_tuple_unqualified", "detekt_embedded_kotlin_$DETEKT_EMBEDDED_KOTLIN")
        if (input.context.compilerVersion != DETEKT_EMBEDDED_KOTLIN) reasons += "project_compiler_${input.context.compilerVersion ?: "unknown"}_differs_from_detekt_embedded_compiler"
        if (modelLight) reasons += listOf("detekt_verified_model_light_syntax_advisory_only",
            "detekt_model_raw_compiler_arguments_retained_not_executed", "detekt_model_semantic_rules_unavailable",
            "detekt_model_binding_is_not_parser_compatibility_or_rule_qualification")
        if (modelLight && !input.context.fullProject) reasons += "detekt_model_deferred_produced_output_remains_unavailable"
        if (requested.any(DetektComposeSurfaces::isCompose)) reasons += listOf(
            "detekt_compose_api_applicability_is_not_parser_qualification",
            "detekt_compose_heuristic_sites_are_not_resolved_api_identity",
            "detekt_compose_facet_execution_is_not_complete_43_rule_delivery")
        for (diagnostic in report.diagnostics) {
            val mappings = DetektCatalog.bySarifId[diagnostic.sarifId].orEmpty()
            val exact = mappings.filter { it.rule_id in plan.activeRuleIds && it.exact &&
                (!DetektComposeSurfaces.isCompose(it.rule_id) ||
                    DetektComposeSurfaces.allowsDiagnostic(it.rule_id, diagnostic.message, compose)) }
            val withheldCompose = mappings.any { DetektComposeSurfaces.isCompose(it.rule_id) } && exact.isEmpty()
            if (withheldCompose) {
                // Retained upstream SARIF is still evidence, but unsupported API advice must not
                // be laundered into a user-facing finding, fix hint, or raw recommendation.
                raw += RawDiagnostic("Compose diagnostic withheld: exact API-family applicability is unproven.",
                    "info", diagnostic.sarifId, diagnostic.location)
                reasons += "detekt_compose_unproven_diagnostic_api_family"
            }
            if (modelLight || exact.isEmpty()) {
                if (!withheldCompose) raw += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.sarifId, diagnostic.location)
                if (exact.isEmpty() && !withheldCompose) reasons += if (mappings.any { it.rule_id in plan.activeRuleIds && it.relation == "requires_refinement" })
                    "raw_upstream_guidance_is_not_catalog_refinement_equivalence" else "unmapped_or_unrequested_upstream_diagnostic"
            }
            for (mapping in exact) {
                val composeRule = DetektComposeSurfaces.isCompose(mapping.rule_id)
                val advisory = modelLight || composeRule
                val fingerprint = Digests.sha256(listOf(mapping.rule_id, diagnostic.location.path,
                    diagnostic.signature ?: "${diagnostic.location.line}:${diagnostic.location.column}:${diagnostic.message}", input.context.scope.id).joinToString("\u0000"))
                findings += Finding(mapping.rule_id, mapping.title, diagnostic.message, mapping.rationale,
                    diagnostic.location, fingerprint, id, input.context.scope,
                    if (advisory) "warning" else mapping.severity, if (advisory) "advisory" else mapping.kind,
                    if (advisory) "low" else mapping.certainty,
                    evidence = listOf(Evidence("upstream_sarif", if (modelLight)
                        "Pinned Detekt $version LIGHT syntax advisory; modeled Kotlin 2.4.21 / embedded parser $DETEKT_EMBEDDED_KOTLIN compatibility UNQUALIFIED; no semantic equivalence"
                        else "Pinned isolated Detekt $version; compatibility unqualified", digest = reportDigest)) +
                        if (composeRule) listOf(Evidence("compose_library_applicability",
                            "Exact owner-resolved compilation/library API facet; PSI/name-based heuristic only, not API symbol identity, safe fix, complete rule coverage or parser/release qualification.",
                            digest = compose.bindingDigest)) else emptyList(),
                    verification = listOf(Verification(expected = "Re-run this exact provider/context and confirm the diagnostic is absent.")),
                    upstreamId = mapping.upstream_id, relatedLocations = diagnostic.relatedLocations,
                    // Fresh output directories locate this report; they are not its material identity.
                    // Keep the full report digest above: any byte change still reopens raw acceptance.
                    retrievalAttachments = listOf(EvidenceRetrievalAttachment("upstream_sarif", reportDigest, reportPath)))
            }
        }
        val coverage = requested.map { ruleId ->
            modelAbstention(ruleId, input.context.scope)?.let { return@map it }
            composeAbstention(ruleId, input.context.scope, compose,
                observedFacets = composeObservation != null && ruleId in plan.activeRuleIds &&
                    DetektCatalog.byId[ruleId]?.sarifId in report.enabledSarifIds)?.let { return@map it }
            val mapping = DetektCatalog.byId[ruleId]
            val enabled = mapping?.sarifId in report.enabledSarifIds && ruleId in plan.activeRuleIds
            val reason = when {
                mapping == null -> "no_detekt_mapping"
                mapping.relation == "excluded_alias" -> "catalog_rule_explicitly_excluded"
                mapping.relation == "requires_refinement" -> "catalog_refinement_not_implemented_raw_upstream_is_not_equivalent"
                ruleId in settings.disabledRuleIds -> "rule_disabled_by_owner_selected_configuration"
                mapping.depth != "syntax" && !semanticPrerequisites(input) -> "full_jvm_semantic_prerequisites_unavailable"
                !enabled -> "upstream_rule_not_reported_active"
                modelLight -> "exact_syntax_upstream_executed_advisory_only_parser_compatibility_unqualified"
                else -> "exact_upstream_executed_but_compatibility_and_release_qualification_missing"
            }
            val hasFinding = findings.any { it.ruleId == ruleId }
            val state = when {
                mapping?.relation == "excluded_alias" -> "excluded"
                mapping == null || mapping.relation == "requires_refinement" -> "unsupported"
                hasFinding -> "findings"
                else -> "unknown"
            }
            Coverage(ruleId, input.context.scope, state, reason, depth = mapping?.depth ?: "semantic",
                provenance = if (enabled) "executed" else "not_executed",
                implemented = enabled && mapping?.exact == true, tested = false, qualified = false)
        }
        reasons += coverage.mapNotNull { it.reason }.filterNot {
            it.startsWith("exact_upstream_executed") || it.startsWith("exact_syntax_upstream_executed")
        }
        return ProviderResult(id, "partial", findings, coverage, reasons.distinct(), raw, metrics, version)
    }

    private data class ComposeSelection(val permit: DetektLibraryPermit?, val applicability: DetektComposeApplicability)

    private fun selectCompose(input: AnalysisInput): ComposeSelection {
        if (input.requiredRuleIds.none(DetektComposeSurfaces::isCompose))
            return ComposeSelection(null, DetektComposeApplicability())
        return try {
            val selected = libraryAuthority.authorize(input)
                ?: return ComposeSelection(null, DetektComposeSurfaces.unavailable(input, "detekt_compose_library_not_authorized"))
            ComposeSelection(selected, selected.verify(this, input))
        } catch (problem: Exception) {
            ComposeSelection(null, DetektComposeSurfaces.unavailable(input,
                problemCode(problem, "detekt_compose_owner_resolution_unavailable")))
        }
    }

    private fun verifyComposePlan(input: AnalysisInput, plan: DetektExecutionPlan, permit: DetektLibraryPermit?) {
        val current = selectCompose(input)
        if (current.permit !== permit || current.applicability != plan.composeApplicability ||
            plan.activeRuleIds.any { DetektComposeSurfaces.isCompose(it) &&
                current.applicability.rules[it]?.executableFacets != true }) {
            throw ExecutionProblem("detekt_compose_binding_changed", "The exact live compilation/library/facet plan changed or was rebound.")
        }
    }

    private fun composeAbstention(ruleId: String, context: RuleContext,
                                  applicability: DetektComposeApplicability, observedFacets: Boolean = false): Coverage? {
        val mapping = DetektCatalog.byId[ruleId] ?: return null
        if (!DetektComposeSurfaces.isCompose(ruleId)) return null
        if (mapping.relation == "excluded_alias") return Coverage(ruleId, context, "excluded",
            "catalog_rule_explicitly_excluded", depth = mapping.depth, provenance = "not_executed")
        if (ruleId in settings.disabledRuleIds) return Coverage(ruleId, context, "unavailable",
            "rule_disabled_by_owner_selected_configuration", depth = mapping.depth, provenance = "not_executed")
        val state = applicability.rules[ruleId]
        if (state?.completeApiSurface == true) return null
        // Useful supported facets may have findings, but the whole required rule is still
        // incomplete/unavailable. No certificate, delivered/implemented or clean-rule claim.
        return Coverage(ruleId, context, "unavailable", state?.reason ?: "detekt_compose_api_surface_unknown",
            depth = mapping.depth, provenance = if (observedFacets) "executed" else "not_executed",
            implemented = false, tested = false, qualified = false)
    }

    private fun modelAbstention(ruleId: String, context: RuleContext): Coverage? {
        if (inputMode != DetektInputMode.VERIFIED_MODEL_LIGHT) return null
        val mapping = DetektCatalog.byId[ruleId]
        val (state, reason) = when {
            mapping == null -> "unsupported" to "no_detekt_mapping"
            mapping.relation == "excluded_alias" -> "excluded" to "catalog_rule_explicitly_excluded"
            !mapping.exact -> "unsupported" to "catalog_refinement_not_implemented_raw_upstream_is_not_equivalent"
            mapping.depth != "syntax" -> "unavailable" to "detekt_model_light_does_not_execute_semantic_rules"
            ruleId in settings.disabledRuleIds -> "unavailable" to "rule_disabled_by_owner_selected_configuration"
            else -> return null
        }
        return Coverage(ruleId, context, state, reason, depth = mapping?.depth ?: "semantic", provenance = "not_executed",
            implemented = false, tested = false, qualified = false)
    }

    private fun requireModelLightReportProjection(plan: DetektExecutionPlan, report: DetektReport) {
        val selected = plan.activeRuleIds.map { ruleId ->
            val mapping = DetektCatalog.byId[ruleId]
            detektModelCheck(mapping?.exact == true && mapping.depth == "syntax" && ruleId !in settings.disabledRuleIds,
                "detekt_model_report_projection_mismatch", "Only the exact enabled syntax mapping can receive a LIGHT finding.")
            requireNotNull(mapping).sarifId
        }.toSet()
        detektModelCheck(plan.analysisMode == "light" && selected.isNotEmpty() && report.enabledSarifIds.all { it in selected } &&
            report.diagnostics.all { it.sarifId in selected && it.sarifId in report.enabledSarifIds },
            "detekt_model_report_projection_mismatch", "An enabled/unrequested or semantic upstream row cannot be relabeled as this LIGHT projection.")
    }

    private fun semanticPrerequisites(input: AnalysisInput): Boolean =
        input.context.fullProject && input.context.compilerVersion != null && input.context.classpath.isNotEmpty() &&
            listOf(input.context.modelDigest, input.context.dependencyDigest, input.context.toolchainDigest, input.context.configurationDigest).all { it != null && Digests.valid(it) } &&
            input.context.compilerArguments.isEmpty() && input.context.compilationFiles != null &&
            input.snapshot.unknownInputs.isEmpty() && input.snapshot.scopeExclusions.isEmpty()

    private fun compilationFiles(input: AnalysisInput): List<String> {
        val files = input.context.compilationFiles
            ?: throw ExecutionProblem("detekt_compilation_members_unavailable", "No exact owner-imported compilation file membership")
        val captured = input.snapshot.files.map { it.path }.toSet()
        if (files.isEmpty() || files.distinct().size != files.size || files.any {
                it !in captured || File.pathSeparator in it || (!it.endsWith(".kt") && !it.endsWith(".kts"))
            }) {
            throw ExecutionProblem("detekt_compilation_members_invalid", "Compilation members must be unique captured Kotlin files")
        }
        files.forEach(::safeRelativePath)
        return files.sorted()
    }

    private fun verifyClasspath(input: AnalysisInput) {
        val seen = mutableSetOf<String>()
        for (artifact in input.context.classpath) {
            val path = Path.of(artifact.path).toAbsolutePath().normalize()
            if (!seen.add(path.toString()) || !Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
                !Digests.valid(artifact.digest) || fileDigest(path) != artifact.digest) {
                throw ExecutionProblem("detekt_classpath_identity_unavailable", "Semantic classpath artifact bytes are not verified")
            }
        }
    }

    private fun configuration(activeIds: Set<String>): String {
        val active = activeIds.map { DetektCatalog.byId.getValue(it).upstream_id }.toSet()
        val ruleSets = (DetektCatalog.mappings.map { it.upstream_id.substringBefore(':') } + "coroutines").distinct().sorted()
        val text = StringBuilder("config:\n  validation: true\n  warningsAsErrors: true\nprocessors:\n  active: false\nconsole-reports:\n  active: false\n")
        for (set in ruleSets) {
            val rules = active.filter { it.substringBefore(':') == set }.sorted()
            text.append(set).append(":\n  active: ").append(rules.isNotEmpty()).append('\n')
            for (upstream in rules) {
                val rule = upstream.substringAfter(':')
                text.append("  ").append(rule).append(":\n    active: true\n")
                val optionSets = activeIds.filter { DetektCatalog.byId.getValue(it).upstream_id == upstream }
                    .map { settings.options[it].orEmpty() }.distinct()
                if (optionSets.size != 1) throw ExecutionProblem("detekt_conflicting_refinement_options", "Mappings for the same upstream rule have different owner-selected options")
                for ((key, value) in optionSets.single().toSortedMap()) {
                    require(key.matches(Regex("[A-Za-z][A-Za-z0-9_]*")) && key !in setOf("active", "autoCorrect"))
                    text.append("    ").append(key).append(": ").append(value.toString()).append('\n')
                }
            }
        }
        return text.toString()
    }

    /** Pure formatting only, never an execution observation. Unstructured process output has no
     * rule/family attribution, even on failure. Keep generic-only behavior, but do not leak an
     * unsupported Compose recommendation through stdout/stderr in a Compose or mixed request.
     * Owners may retain original ProcessOutcome bytes privately via the runner's own callback;
     * this formatter neither stores that evidence nor claims it exists.
     */
    internal fun processDiagnostics(input: AnalysisInput, outcome: ProcessOutcome): List<RawDiagnostic> {
        val composeRequested = input.requiredRuleIds.any(DetektComposeSurfaces::isCompose)
        return listOf(outcome.stdout to "info", outcome.stderr to "error").filter { it.first.isNotBlank() }
            .map { (message, severity) -> RawDiagnostic(if (composeRequested)
                "Detekt process text withheld: Compose diagnostic API-family applicability is unproven outside structured SARIF."
                else message.take(8192), severity) }
    }
}
