package dev.ktchecker.providers.ktlint

import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Owner-selected external installation, never an execution permission. */
data class KtlintInstallation(val javaExecutable: Path, val artifactDirectory: Path, val jdkHome: Path)

/** Real EditorConfig property values; no shell/formatter/plugin argument escape hatch. */
data class KtlintSettings(
    val disabledRuleIds: Set<String> = emptySet(),
    val properties: Map<String, String> = emptyMap(),
    val codeStyle: String = "ktlint_official",
)

data class KtlintExecutionPlan(
    val execution: TrustedExecution,
    val configuration: String,
    val configurationDigest: String,
    val artifacts: List<ArtifactIdentity>,
    val activeRuleIds: Set<String>,
    val possiblyScopeDisabledRuleIds: Set<String>,
    val compilationFiles: List<String>,
)

/**
 * Syntax-only, owner-selected opt-in adapter. Ktlint's Kotlin2.2.21 parser is not
 * the project's Kotlin2.4.21 compiler; that project profile deliberately abstains.
 * Even the tested JVM syntax tuple is partial/unqualified, not a release certificate.
 */
class KtlintProvider(
    private val installation: KtlintInstallation? = null,
    private val authority: ExecutionAuthority = ExecutionAuthority.DENY,
    private val settings: KtlintSettings = KtlintSettings(),
    private val runner: IsolatedProcessRunner = IsolatedProcessRunner(),
) : AnalysisProvider {
    override val id = "ktlint"
    override val version = KTLINT_VERSION
    override val supportedRuleIds = KtlintCatalog.mappings.filter { it.exact }.map { it.rule_id }.toSet()
    val mappedRuleIds: Set<String> = KtlintCatalog.byId.keys

    fun executionProfile(input: AnalysisInput): TrustedExecution = executionPlan(input).execution

    /** Trusted host helper: resulting transport data still needs an exact injected permit. */
    fun executionPlan(input: AnalysisInput, timeoutMillis: Long = 60_000, maxOutputBytes: Int = 1_048_576): KtlintExecutionPlan {
        requireSupportedTuple(input)
        val installed = installation ?: throw ExecutionProblem("ktlint_installation_unavailable", "No pinned Ktlint installation selected")
        val files = compilationFiles(input)
        SnapshotSources(input).verify()
        val active = input.requiredRuleIds.filter { KtlintCatalog.byId[it]?.exact == true && it !in settings.disabledRuleIds }.toSet()
        val possiblyDisabled = validateCapturedConfiguration(input, active)
        val configuration = configuration(active)
        val configurationDigest = Digests.sha256(configuration)
        val tool = KtlintCatalog.verifyArtifacts(installed.artifactDirectory)
        val jdk = verifyJdk(installed)
        val cli = tool.single { it.coordinate == "com.pinterest.ktlint:ktlint-cli:$KTLINT_VERSION:all" }.path
        val plugins = tool.filterNot { it.path == cli }.joinToString(",") { it.path }
        if (listOf(plugins, input.outputRoot.orEmpty()).any { '\n' in it || '\r' in it } ||
            ',' in installed.artifactDirectory.toString() || ',' in input.outputRoot.orEmpty()) {
            throw ExecutionProblem("ktlint_option_path_invalid", "Ktlint reporter/ruleset paths must not contain option delimiters")
        }
        val arguments = listOf(
            "-Xmx256m", "-XX:ActiveProcessorCount=1", "-Djava.io.tmpdir={output}/tmp", "-Duser.home={output}/home",
            // Bind generated settings bytes, not just their reused output filename.
            "-Dktchecker.ktlint.configurationSha256=$configurationDigest",
            "-Dktchecker.ktlint.artifactsSha256=${Digests.sha256((tool + jdk).sortedBy { it.coordinate }.joinToString("\u0000") { "${it.coordinate}:${it.digest}" })}", "-jar", cli,
            "--relative", "--log-level=debug", "--editorconfig={output}/ktlint.editorconfig",
            "--ruleset=$plugins", "--reporter=json,output={output}/ktlint.json", "--",
        ) + files
        val executable = installed.javaExecutable.toAbsolutePath().normalize()
        return KtlintExecutionPlan(TrustedExecution(executable.toString(), arguments, true, fileDigest(executable),
            timeoutMillis, maxOutputBytes), configuration, configurationDigest, tool + jdk, active, possiblyDisabled, files)
    }

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val requested = input.requiredRuleIds.distinct()
        if (requested.isEmpty()) return ProviderResult(id, "partial", reasons = listOf("no_explicit_opt_in_ktlint_rules_requested"), providerVersion = version)
        fun unavailable(reason: String, state: String = "unavailable") = ProviderResult(
            id, if (state == "cancelled") "cancelled" else "partial",
            coverage = requested.map { Coverage(it, input.context.scope, state, reason, provenance = "not_executed") },
            reasons = listOf(reason), providerVersion = version,
        )
        if (cancellation.isCancelled()) return unavailable("cancelled_before_execution", "cancelled")
        try { requireSupportedTuple(input) } catch (problem: Exception) { return unavailable(problemCode(problem, "ktlint_tuple_unsupported"), "unsupported") }
        // No artifact, project config or input-selected classpath I/O before authority.
        val selected = try { authority.authorize(input) } catch (_: Exception) { null }
            ?: return unavailable("execution_not_authorized")
        if (input.execution != selected.execution || input.analysisKey() != selected.expectedAnalysisKey ||
            input.snapshot.snapshotId != selected.snapshotId || Path.of(input.materializedRoot).toAbsolutePath().normalize() != selected.materializedRoot.toAbsolutePath().normalize()) {
            return unavailable("snapshot_binding_mismatch")
        }
        val plan = try {
            executionPlan(input, selected.execution.timeoutMillis, selected.execution.maxOutputBytes)
        } catch (problem: Exception) { return unavailable(problemCode(problem, "ktlint_plan_unavailable")) }
        val checked = ExecutionAuthority { candidate ->
            if (candidate.execution != plan.execution || selected.execution != plan.execution) return@ExecutionAuthority null
            ExecutionPermit(selected.execution, selected.snapshotId, selected.materializedRoot, selected.expectedAnalysisKey, selected.verifySnapshot) { output ->
                selected.prepareOutput(output)
                Files.createDirectories(output.resolve("home"))
                Files.writeString(output.resolve("ktlint.editorconfig"), plan.configuration, Charsets.UTF_8)
            }
        }
        val outcome = runner.run(input, checked, cancellation)
        if (!outcome.successfulExecution) return ProviderResult(id, if (outcome.cancelled) "cancelled" else "partial",
            coverage = requested.map { Coverage(it, input.context.scope, if (outcome.cancelled) "cancelled" else "unavailable",
                outcome.reasons.joinToString(","), provenance = if (outcome.executed) "executed" else "not_executed") },
            reasons = outcome.reasons, rawDiagnostics = processDiagnostics(outcome), metrics = outcome.metrics, providerVersion = version)
        val bytes: ByteArray
        val report: KtlintReport
        try {
            KtlintCatalog.verifyArtifacts(installation!!.artifactDirectory)
            check(verifyJdk(installation) == plan.artifacts.filter { it.coordinate.startsWith("ktlint-jdk21:") })
            check(fileDigest(outcome.outputDirectory!!.resolve("ktlint.editorconfig")) == plan.configurationDigest)
            bytes = outcome.report("ktlint.json")
            val parser = KtlintReportParser()
            report = parser.parse(bytes, input)
            parser.verifyCompletion(outcome.stdout, outcome.stderr, plan.compilationFiles.size, report.diagnostics.size, outcome.exitCode)
        } catch (problem: Exception) {
            val reason = problemCode(problem, "ktlint_report_unreliable")
            return ProviderResult(id, "partial", coverage = requested.map { Coverage(it, input.context.scope, "unknown", reason, provenance = "executed") },
                reasons = listOf(reason), rawDiagnostics = processDiagnostics(outcome), metrics = outcome.metrics, providerVersion = version)
        }
        val raw = processDiagnostics(outcome).toMutableList()
        val findings = mutableListOf<Finding>()
        val reasons = mutableListOf("ktlint_syntax_tuple_unqualified", "ktlint_lint_only_no_source_rewrites", "ktlint_is_not_semantic_compiler_or_library_qualification")
        for (diagnostic in report.diagnostics) {
            val mapping = KtlintCatalog.byUpstreamId[diagnostic.upstreamId]
            if (mapping?.exact != true || mapping.rule_id !in plan.activeRuleIds) {
                raw += RawDiagnostic(diagnostic.message, "warning", diagnostic.upstreamId, diagnostic.location)
                reasons += "unmapped_excluded_or_unrequested_upstream_diagnostic"
                continue
            }
            val fingerprint = Digests.sha256(listOf(mapping.rule_id, diagnostic.location.path, diagnostic.location.line,
                diagnostic.location.column, diagnostic.message, input.context.scope.id).joinToString("\u0000"))
            findings += Finding(mapping.rule_id, mapping.title, diagnostic.message, mapping.rationale, diagnostic.location,
                fingerprint, id, input.context.scope, mapping.severity, mapping.kind, mapping.certainty,
                evidence = listOf(Evidence("upstream_json", "Pinned Ktlint $version lint-only syntax profile, compatibility unqualified",
                    outcome.outputDirectory!!.resolve("ktlint.json").toString(), Digests.sha256(bytes))),
                verification = listOf(Verification(expected = "Re-run the exact owner-selected profile and verify the syntax/style diagnostic is absent; no formatting is applied.")),
                upstreamId = mapping.upstream_id)
        }
        val coverage = requested.map { ruleId ->
            val mapping = KtlintCatalog.byId[ruleId]
            val reason = when {
                mapping == null -> "no_ktlint_mapping"
                mapping.relation == "excluded_alias" -> "catalog_rule_explicitly_excluded_noop_or_infrastructure_alias"
                mapping.relation == "requires_refinement" -> "semantic_catalog_alias_not_implemented_by_ktlint_psi_frontend"
                ruleId in settings.disabledRuleIds -> "rule_disabled_by_owner_selected_configuration"
                ruleId in plan.possiblyScopeDisabledRuleIds -> "rule_activity_is_scoped_by_captured_editorconfig"
                else -> "exact_upstream_syntax_run_but_compatibility_precision_and_release_qualification_missing"
            }
            val state = when {
                mapping == null -> "unsupported"
                mapping.relation == "excluded_alias" -> "excluded"
                !mapping.exact -> "unsupported"
                findings.any { it.ruleId == ruleId } -> "findings"
                else -> "unknown"
            }
            Coverage(ruleId, input.context.scope, state, reason, depth = mapping?.depth ?: "syntax", provenance = "executed",
                implemented = mapping?.exact == true && ruleId in plan.activeRuleIds && ruleId !in plan.possiblyScopeDisabledRuleIds,
                tested = false, qualified = false)
        }
        return ProviderResult(id, "partial", findings, coverage, reasons.distinct(), raw, outcome.metrics, version)
    }

    private fun requireSupportedTuple(input: AnalysisInput) {
        if (input.context.scope.target != "jvm" || input.context.compilerVersion != KTLINT_EMBEDDED_KOTLIN) {
            throw ExecutionProblem("ktlint_parser_or_platform_tuple_unsupported", "Only the pinned Kotlin2.2.21 JVM syntax fixture tuple is exercised; newer/unknown parser profiles and other targets abstain")
        }
        if (input.context.compilerArguments.isNotEmpty() || input.snapshot.unknownInputs.isNotEmpty()) {
            throw ExecutionProblem("ktlint_configuration_inputs_unknown", "Unknown source/configuration inputs and extra compiler arguments are not a known Ktlint syntax profile")
        }
    }

    private fun compilationFiles(input: AnalysisInput): List<String> {
        val files = input.context.compilationFiles ?: throw ExecutionProblem("ktlint_compilation_members_unavailable", "No exact owner-imported compilation membership")
        val captured = input.snapshot.files.map { it.path }.toSet()
        if (files.isEmpty() || files.size > 4096 || files.distinct().size != files.size || files.any { path ->
                path !in captured || (!path.endsWith(".kt") && !path.endsWith(".kts")) ||
                    path.any { it in "*?[]{}!\\\u0000\n\r" } || path.startsWith('-') || path.split('/').any { it.startsWith('.') }
            }) throw ExecutionProblem("ktlint_compilation_members_invalid", "Members must be unique captured non-hidden Kotlin files without CLI glob syntax")
        files.forEach(::safeRelativePath)
        return files.sorted()
    }

    /** Parse only safe INI policy boundaries; the actual upstream ec4j resolves style inheritance. */
    private fun validateCapturedConfiguration(input: AnalysisInput, activeIds: Set<String>): Set<String> {
        val root = Path.of(input.materializedRoot).toAbsolutePath().normalize()
        val configurations = input.snapshot.files.filter { it.path.substringAfterLast('/') == ".editorconfig" }
        if (configurations.none { it.path == ".editorconfig" }) throw ExecutionProblem("ktlint_editorconfig_root_unavailable", "Capture an explicit root=true .editorconfig; ambient parent config is not accepted")
        val properties = KtlintCatalog.mappings.associateBy { it.executionProperty }
        val disabled = mutableSetOf<String>()
        for (configuration in configurations) {
            val text = strictUtf8(Files.readAllBytes(root.resolve(safeRelativePath(configuration.path))))
            var section = false
            var roots = 0
            for (line in text.lineSequence()) {
                val entry = line.trim()
                if (entry.isEmpty() || entry.startsWith('#') || entry.startsWith(';')) continue
                if (entry.startsWith('[') && entry.endsWith(']')) { section = true; continue }
                val parts = entry.split('=', limit = 2)
                if (parts.size != 2) throw ExecutionProblem("ktlint_editorconfig_policy_invalid", "Malformed captured EditorConfig policy line")
                val key = parts[0].trim().lowercase()
                val value = parts[1].trim().lowercase()
                if (key == "root" && !section) {
                    roots++
                    if (configuration.path == ".editorconfig" && value != "true") throw ExecutionProblem("ktlint_editorconfig_root_unavailable", "Captured root must stop all ambient parent inheritance")
                }
                if (key in setOf("disabled_rules", "ktlint_disabled_rules")) throw ExecutionProblem("ktlint_obsolete_rule_configuration", "Obsolete activation aliases must not silently bypass owner-selected IDs")
                val mapping = properties[key]
                if (mapping != null || key in setOf("ktlint", "ktlint_standard", "ktlint_compose", "ktlint_experimental", "ktlint_internal") ||
                    (key.startsWith("ktlint_") && value in setOf("enabled", "disabled"))) {
                    if (value !in setOf("enabled", "disabled", "unset")) throw ExecutionProblem("ktlint_editorconfig_activation_invalid", "Invalid activation value")
                    if (value == "enabled" && (mapping?.exact != true || mapping.rule_id !in activeIds)) {
                        throw ExecutionProblem("ktlint_project_activation_outside_owner_selection", "Project config cannot activate an excluded, unknown, group or unselected rule")
                    }
                    if (value == "disabled" && (mapping?.upstream_id == "internal:ktlint-suppression" || key == "ktlint_internal")) {
                        throw ExecutionProblem("ktlint_internal_suppression_cannot_be_disabled", "Upstream suppression infrastructure is mandatory, not a selectable application detector")
                    }
                    if (value == "disabled" && mapping != null) disabled += mapping.rule_id
                    if (value == "disabled" && key == "ktlint") disabled += activeIds
                }
            }
            if (configuration.path == ".editorconfig" && roots != 1) throw ExecutionProblem("ktlint_editorconfig_root_unavailable", "Capture exactly one root=true boundary before sections")
        }
        return disabled
    }

    private fun configuration(activeIds: Set<String>): String {
        require(settings.codeStyle in setOf("ktlint_official", "intellij_idea", "android_studio"))
        val text = StringBuilder("root = true\n\n[*]\nktlint_code_style = ${settings.codeStyle}\nktlint_standard = disabled\nktlint_compose = disabled\nktlint_experimental = disabled\nktlint_internal_ktlint-suppression = enabled\n")
        for (mapping in KtlintCatalog.mappings.sortedBy { it.executionProperty }) {
            if (mapping.upstream_id.startsWith("internal:")) continue
            text.append(mapping.executionProperty).append(" = ").append(if (mapping.rule_id in activeIds) "enabled" else "disabled").append('\n')
        }
        val activation = KtlintCatalog.mappings.map { it.executionProperty }.toSet() + setOf("root", "ktlint", "ktlint_code_style", "ktlint_standard", "ktlint_compose", "ktlint_experimental", "ktlint_internal", "disabled_rules", "ktlint_disabled_rules")
        for ((key, value) in settings.properties.toSortedMap()) {
            require(key.matches(Regex("[a-z][a-z0-9_.-]*")) && key !in activation)
            require(value.isNotBlank() && value.none { it in "\n\r\u0000" })
            require(!(key.startsWith("ktlint_") && value.trim().lowercase() in setOf("enabled", "disabled")))
            text.append(key).append(" = ").append(value).append('\n')
        }
        return text.toString()
    }

    private fun verifyJdk(installation: KtlintInstallation): List<ArtifactIdentity> {
        val home = installation.jdkHome.toAbsolutePath().normalize()
        val executable = installation.javaExecutable.toAbsolutePath().normalize()
        if (executable != home.resolve("bin/java")) throw ExecutionProblem("ktlint_jdk21_unavailable", "Java executable must belong to the explicitly selected JDK21")
        val library = if (System.getProperty("os.name").lowercase().let { "mac" in it || "darwin" in it }) "libjvm.dylib" else "libjvm.so"
        val files = listOf(executable, home.resolve("release"), home.resolve("lib/modules"), home.resolve("lib/server/$library"))
        if (files.any { !Files.isRegularFile(it, NOFOLLOW_LINKS) || Files.isSymbolicLink(it) }) throw ExecutionProblem("ktlint_jdk21_unavailable", "Complete regular selected JDK inputs are required")
        val release = strictUtf8(Files.readAllBytes(home.resolve("release")))
        if (!Regex("(?m)^JAVA_VERSION=\"21(?:[.\"+]|-)").containsMatchIn(release)) throw ExecutionProblem("ktlint_jdk21_unavailable", "Only an explicitly selected JDK21 syntax execution tuple is exercised")
        return files.map { ArtifactIdentity("ktlint-jdk21:" + home.relativize(it).toString().replace('\\', '/'), fileDigest(it), it.toString()) }
    }

    private fun processDiagnostics(outcome: ProcessOutcome): List<RawDiagnostic> = listOf(outcome.stdout to "info", outcome.stderr to "error")
        .filter { it.first.isNotBlank() }.map { RawDiagnostic(it.first.take(8192), it.second) }
}
