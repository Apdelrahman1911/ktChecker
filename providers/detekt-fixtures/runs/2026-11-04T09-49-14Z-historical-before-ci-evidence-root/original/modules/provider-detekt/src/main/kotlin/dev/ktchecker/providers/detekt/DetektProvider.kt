package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
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
)

/**
 * An isolated adapter to the pinned upstream engine. An injected host authority
 * and exact plan are required. All compatibility tuples remain unqualified:
 * embedded Kotlin2.4.10 is not the project's Kotlin2.4.21 compiler.
 */
class DetektProvider(
    private val installation: DetektInstallation? = null,
    private val authority: ExecutionAuthority = ExecutionAuthority.DENY,
    private val settings: DetektSettings = DetektSettings(),
    private val runner: IsolatedProcessRunner = IsolatedProcessRunner(),
) : AnalysisProvider {
    override val id: String = "detekt"
    override val version: String = DETEKT_VERSION
    override val supportedRuleIds: Set<String> = DetektCatalog.mappings.filter { it.exact }.map { it.rule_id }.toSet()
    val mappedRuleIds: Set<String> = DetektCatalog.byId.keys

    fun executionProfile(input: AnalysisInput): TrustedExecution = executionPlan(input).execution

    /** Host helper only. A serialized approved=true plan is deliberately insufficient. */
    fun executionPlan(input: AnalysisInput, timeoutMillis: Long = 60_000, maxOutputBytes: Int = 1_048_576): DetektExecutionPlan {
        val installed = installation ?: throw ExecutionProblem("detekt_installation_unavailable", "No pinned Detekt installation selected")
        val files = compilationFiles(input)
        val artifacts = DetektCatalog.verifyArtifacts(installed.artifactDirectory)
        val semantic = semanticPrerequisites(input)
        val active = input.requiredRuleIds.toSet().filter { ruleId ->
            val rule = DetektCatalog.byId[ruleId]
            rule != null && (rule.exact || (rule.relation == "requires_refinement" && settings.rawGuidanceForRefinements)) &&
                ruleId !in settings.disabledRuleIds && (rule.depth == "syntax" || semantic)
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
        if (mode == "full") {
            verifyClasspath(input)
            arguments += listOf("--classpath", input.context.classpath.joinToString(File.pathSeparator) { Path.of(it.path).toAbsolutePath().normalize().toString() },
                "--language-version", settings.languageVersion, "--api-version", settings.apiVersion,
                "--jvm-target", settings.jvmTarget, "--jdk-home", installed.jdkHome.toAbsolutePath().normalize().toString())
        }
        val executable = installed.javaExecutable.toAbsolutePath().normalize()
        return DetektExecutionPlan(
            TrustedExecution(executable.toString(), arguments, approved = true, executableDigest = fileDigest(executable),
                timeoutMillis = timeoutMillis, maxOutputBytes = maxOutputBytes),
            configuration, configurationDigest, artifacts, active, mode,
        )
    }

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val requested = input.requiredRuleIds.distinct()
        if (requested.isEmpty()) return ProviderResult(id, "partial", reasons = listOf("no_explicit_detekt_rules_requested"), providerVersion = version)
        fun unavailable(reason: String, state: String = "unavailable") = ProviderResult(
            id, if (state == "cancelled") "cancelled" else "partial",
            coverage = requested.map { Coverage(it, input.context.scope, state, reason, provenance = "not_executed") },
            reasons = listOf(reason), providerVersion = version,
        )
        if (cancellation.isCancelled()) return unavailable("cancelled_before_execution", "cancelled")
        if (input.context.scope.target !in setOf("jvm", "android", "desktop")) {
            return unavailable("detekt_jvm_provider_does_not_qualify_common_or_native_targets", "unsupported")
        }
        val selectedPermit = try { authority.authorize(input) } catch (_: Exception) { null }
            ?: return unavailable("execution_not_authorized")
        if (input.execution != selectedPermit.execution || input.analysisKey() != selectedPermit.expectedAnalysisKey ||
            input.snapshot.snapshotId != selectedPermit.snapshotId ||
            Path.of(input.materializedRoot).toAbsolutePath().normalize() != selectedPermit.materializedRoot.toAbsolutePath().normalize()) {
            return unavailable("snapshot_binding_mismatch")
        }
        val plan = try {
            val execution = input.execution
            executionPlan(input, execution?.timeoutMillis ?: 60_000, execution?.maxOutputBytes ?: 1_048_576)
        } catch (problem: Exception) {
            return unavailable(problemCode(problem, "detekt_plan_unavailable"))
        }
        val checkedAuthority = ExecutionAuthority { candidate ->
            val permit = selectedPermit
            if (candidate.execution != plan.execution || permit.execution != plan.execution) return@ExecutionAuthority null
            ExecutionPermit(permit.execution, permit.snapshotId, permit.materializedRoot, permit.expectedAnalysisKey, permit.verifySnapshot) { output ->
                permit.prepareOutput(output)
                Files.writeString(output.resolve("detekt.yml"), plan.configuration, Charsets.UTF_8)
            }
        }
        val outcome = runner.run(input, checkedAuthority, cancellation)
        if (!outcome.successfulExecution) {
            return ProviderResult(id, if (outcome.cancelled) "cancelled" else "partial",
                coverage = requested.map { Coverage(it, input.context.scope, if (outcome.cancelled) "cancelled" else "unavailable",
                    outcome.reasons.joinToString(","), provenance = if (outcome.executed) "executed" else "not_executed") },
                reasons = outcome.reasons, rawDiagnostics = processDiagnostics(outcome), metrics = outcome.metrics, providerVersion = version)
        }
        val reportBytes: ByteArray
        val report: DetektReport
        try {
            require(outcome.exitCode == 0) { "detekt_exit_${outcome.exitCode}" }
            DetektCatalog.verifyArtifacts(installation!!.artifactDirectory)
            if (plan.analysisMode == "full") verifyClasspath(input)
            require(fileDigest(outcome.outputDirectory!!.resolve("detekt.yml")) == plan.configurationDigest)
            reportBytes = outcome.report("detekt.sarif")
            report = DetektReportParser().parse(reportBytes, input)
        } catch (problem: Exception) {
            val reason = problemCode(problem, if (outcome.exitCode != 0) "detekt_analysis_failed_exit_${outcome.exitCode}" else "detekt_report_unreliable")
            return ProviderResult(id, "partial", coverage = requested.map { Coverage(it, input.context.scope, "unknown", reason, provenance = "executed") },
                reasons = listOf(reason), rawDiagnostics = processDiagnostics(outcome), metrics = outcome.metrics, providerVersion = version)
        }
        val reportDigest = Digests.sha256(reportBytes)
        val findings = mutableListOf<Finding>()
        val raw = processDiagnostics(outcome).toMutableList()
        val reasons = mutableListOf("detekt_compatibility_tuple_unqualified", "detekt_embedded_kotlin_$DETEKT_EMBEDDED_KOTLIN")
        if (input.context.compilerVersion != DETEKT_EMBEDDED_KOTLIN) reasons += "project_compiler_${input.context.compilerVersion ?: "unknown"}_differs_from_detekt_embedded_compiler"
        for (diagnostic in report.diagnostics) {
            val mappings = DetektCatalog.bySarifId[diagnostic.sarifId].orEmpty()
            val exact = mappings.filter { it.rule_id in plan.activeRuleIds && it.exact }
            if (exact.isEmpty()) {
                raw += RawDiagnostic(diagnostic.message, diagnostic.severity, diagnostic.sarifId, diagnostic.location)
                reasons += if (mappings.any { it.rule_id in plan.activeRuleIds && it.relation == "requires_refinement" })
                    "raw_upstream_guidance_is_not_catalog_refinement_equivalence" else "unmapped_or_unrequested_upstream_diagnostic"
            }
            for (mapping in exact) {
                val fingerprint = Digests.sha256(listOf(mapping.rule_id, diagnostic.location.path,
                    diagnostic.signature ?: "${diagnostic.location.line}:${diagnostic.location.column}:${diagnostic.message}", input.context.scope.id).joinToString("\u0000"))
                findings += Finding(mapping.rule_id, mapping.title, diagnostic.message, mapping.rationale,
                    diagnostic.location, fingerprint, id, input.context.scope, mapping.severity, mapping.kind, mapping.certainty,
                    evidence = listOf(Evidence("upstream_sarif", "Pinned isolated Detekt $version; compatibility unqualified",
                        outcome.outputDirectory!!.resolve("detekt.sarif").toString(), reportDigest)),
                    verification = listOf(Verification(expected = "Re-run this exact provider/context and confirm the diagnostic is absent.")),
                    upstreamId = mapping.upstream_id, relatedLocations = diagnostic.relatedLocations)
            }
        }
        val coverage = requested.map { ruleId ->
            val mapping = DetektCatalog.byId[ruleId]
            val enabled = mapping?.sarifId in report.enabledSarifIds && ruleId in plan.activeRuleIds
            val reason = when {
                mapping == null -> "no_detekt_mapping"
                mapping.relation == "excluded_alias" -> "catalog_rule_explicitly_excluded"
                mapping.relation == "requires_refinement" -> "catalog_refinement_not_implemented_raw_upstream_is_not_equivalent"
                ruleId in settings.disabledRuleIds -> "rule_disabled_by_owner_selected_configuration"
                mapping.depth != "syntax" && !semanticPrerequisites(input) -> "full_jvm_semantic_prerequisites_unavailable"
                !enabled -> "upstream_rule_not_reported_active"
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
                provenance = "executed", implemented = enabled && mapping?.exact == true, tested = false, qualified = false)
        }
        reasons += coverage.mapNotNull { it.reason }.filterNot { it.startsWith("exact_upstream_executed") }
        return ProviderResult(id, "partial", findings, coverage, reasons.distinct(), raw, outcome.metrics, version)
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

    private fun processDiagnostics(outcome: ProcessOutcome): List<RawDiagnostic> =
        listOf(outcome.stdout to "info", outcome.stderr to "error").filter { it.first.isNotBlank() }
            .map { RawDiagnostic(it.first.take(8192), it.second) }
}
