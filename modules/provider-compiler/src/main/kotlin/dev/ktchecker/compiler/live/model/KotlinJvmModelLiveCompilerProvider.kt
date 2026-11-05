package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.compiler.KotlinJvmToolchain
import dev.ktchecker.compiler.KotlinJvmModelBinding
import dev.ktchecker.compiler.live.*
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationJobResult
import dev.ktchecker.providers.runtime.OwnedApplicationReason
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerModelWire as ModelWire
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*

/**
 * Fixed owner-installed JVM-model route. Inputs never select a toolchain, application, profile,
 * authority or fallback. One exact retained attempt capability selects its actual application.
 * No installation option or ordinary --authorize-execution flag automatically enables this route.
 */
class KotlinJvmModelLiveCompilerProvider(
    private val installation: KotlinJvmToolchain,
    private val authority: KotlinJvmModelApplicationAuthority = KotlinJvmModelApplicationAuthority.DENY,
    private val producedPreview: KotlinJvmProducedOutputPreview? = null,
) : AnalysisProvider {
    override val id: String = PROVIDER_ID
    override val version: String = VERSION
    override val supportedRuleIds: Set<String> = MODEL_REGISTRY.jvmRuleIds

    internal fun usesInstallation(selected: KotlinJvmToolchain): Boolean = installation === selected
    internal fun usesBinding(binding: KotlinJvmModelBinding): Boolean = binding.producedResolution?.preview === producedPreview &&
        (producedPreview == null || producedPreview.installation === installation)

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val started = System.nanoTime() // Observation only; never a replacement request budget.
        val request = try {
            ModelCompilerAttemptRequest(input).also {
                requireModelLiveStructure(it.input, producedPreview != null)
                liveCheck(it.input.outputRoot != null, "compiler_model_live_archive_attempt_required")
            }
        } catch (failure: CompilerApplicationProblem) {
            return unavailableModelResult(input, listOf(failure.code), started)
        } catch (_: Exception) {
            return unavailableModelResult(input, listOf("compiler_model_live_input_invalid"), started)
        }
        try {
            if (cancellation.isCancelled()) return unavailableModelResult(request.input,
                listOf("compiler_model_live_cancelled"), started, "cancelled")
        } catch (_: Exception) {
            return unavailableModelResult(request.input, listOf("compiler_model_live_cancellation_check_failed"), started)
        }
        val permit = try { authority.authorizeAttempt(this, request) }
        catch (_: Exception) { return unavailableModelResult(request.input, listOf("compiler_model_live_attempt_authority_failed"), started) }
            ?: return unavailableModelResult(request.input, listOf("compiler_model_live_attempt_not_authorized"), started)
        if (!permit.matches(this, request)) {
            return unavailableModelResult(request.input, listOf("compiler_model_live_attempt_binding_mismatch"), started)
        }
        return try {
            // Only the exact retained permit can supply original-argv execution semantics.
            requireModelLiveShape(request.input, permit.prepared.binding)
            when (val result = permit.application.compile(permit, cancellation, started)) {
                // All parsing, diagnostics, coverage and pre-publication metrics were built while
                // ARM was active. Never mutate/copy Accepted.value to add full-return timings here.
                is OwnedApplicationJobResult.Accepted -> result.value
                is OwnedApplicationJobResult.Unavailable -> unavailableModelResult(request.input,
                    listOf("compiler_model_live_application_unavailable") + result.reasons.map { it.code }, started,
                    if (OwnedApplicationReason.CANCELLED in result.reasons) "cancelled" else "unavailable")
            }
        } catch (_: CancellationException) {
            unavailableModelResult(request.input, listOf("compiler_model_live_cancelled"), started, "cancelled")
        } catch (failure: CompilerApplicationProblem) {
            unavailableModelResult(request.input, listOf(failure.code), started)
        } catch (_: Exception) {
            unavailableModelResult(request.input, listOf("compiler_model_live_application_failed"), started)
        }
    }

    companion object {
        const val PROVIDER_ID = "kotlin-jvm-live-model-preview"
        const val VERSION = "2.4.21/live-model-preview-1"
    }
}

private val MODEL_REGISTRY: CompilerRuleRegistry by lazy { CompilerRuleRegistry.load() }
private val MODEL_DIAGNOSTIC_NAME = Regex("[A-Z][A-Z0-9_]{1,255}")
private val MODEL_SUPPRESSION = Regex("@(?:file\\s*:\\s*)?(?:kotlin\\.)?Suppress\\s*\\(")

/** Fixed renderer, called only inside the armed validation callback. Synthetic tests identify themselves. */
internal fun renderModelObservation(
    input: AnalysisInput,
    sourceTexts: Map<String, String>,
    materializedSourceRoot: Wire.Root,
    installationDigest: String,
    modelBindingDigest: String,
    result: Wire.Result,
    observation: ModelWire.Observation,
    providerStartedNanos: Long,
    checkpoint: () -> Unit = {},
    binding: KotlinJvmModelBinding? = null,
): ProviderResult {
    checkpoint(); requireModelLiveShape(input, binding); ModelWire.requireSealed(observation)
    liveCheck(result.state() == Wire.ResultState.APPLICATION_SEALED && result.originalKey() == input.analysisKey() &&
        observation.sourceRoot() == materializedSourceRoot,
        "compiler_model_live_result_identity_mismatch")
    val selected = requireNotNull(input.context.compilationFiles)
    liveCheck(sourceTexts.keys == selected.toSet(), "compiler_model_live_render_source_mismatch")
    val files = input.snapshot.files.associateBy { it.path }
    selected.forEach { path ->
        checkpoint()
        val text = sourceTexts.getValue(path)
        requireModelSelectedText(text, checkpoint)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val file = files.getValue(path)
        liveCheck(bytes.toString(Charsets.UTF_8) == text && bytes.size.toLong() == file.size && Digests.sha256(bytes) == file.digest,
            "compiler_model_live_render_source_mismatch")
    }
    val physicalPaths = selected.associateBy { livePath(materializedSourceRoot.path()).resolve(it).toString() }
    val required = input.requiredRuleIds
    val findings = ArrayList<Finding>()
    val raw = ArrayList<RawDiagnostic>()
    val reasons = linkedSetOf("compiler_model_live_preview_not_qualified", "compiler_model_live_semantic_extraction_not_authorized",
        "compiler_model_live_elapsed_measured_to_armed_render_not_call_completion", "compiler_model_live_cpu_rss_unavailable")
    if (binding?.producedResolution != null) reasons += "compiler_model_live_produced_output_preview_not_qualified"
    if (observation.compilerCode() == 1) reasons += "compiler_model_live_api_diagnostic_failure"
    if (sourceTexts.values.any { MODEL_SUPPRESSION.containsMatchIn(it) }) reasons += "compiler_model_live_suppression_scope_not_certified"
    if ("-nowarn" in input.context.compilerArguments) reasons += "compiler_model_live_warnings_disabled_under_selected_configuration"
    for (diagnostic in observation.diagnostics()) {
        checkpoint(); ModelWire.validateDiagnostic(diagnostic)
        val severity = when (diagnostic.severity()) {
            "ERROR" -> "error"
            "WARNING", "STRONG_WARNING", "MANDATORY_WARNING" -> "warning"
            "INFO", "LOGGING" -> "info"
            else -> liveFailure("compiler_model_live_diagnostic_severity_invalid")
        }
        val reported = diagnostic.path()
        val location = if (reported.isEmpty()) {
            liveCheck(diagnostic.line() == -1 && diagnostic.column() == -1, "compiler_model_live_diagnostic_location_invalid")
            null // Global ERROR (-Werror, for example) is kept raw rather than made clean.
        } else {
            val logical = if (reported in selected) reported else physicalPaths[reported]
                ?: liveFailure("compiler_model_live_diagnostic_source_mismatch")
            val text = sourceTexts.getValue(logical)
            liveCheck(validModelPoint(text, diagnostic.line(), diagnostic.column(), checkpoint), "compiler_model_live_diagnostic_location_invalid")
            val file = files.getValue(logical)
            SourceLocation(logical, file.digest, diagnostic.line(), diagnostic.column() - 1,
                diagnostic.line(), diagnostic.column() - 1)
        }
        val upstream = diagnostic.diagnosticId().takeIf { MODEL_DIAGNOSTIC_NAME.matches(it) }
        val mapping = upstream?.let { MODEL_REGISTRY.byUpstreamId[it] }
        if (mapping == null || !mapping.jvmApplicable || mapping.ruleId !in required || location == null || severity == "info") {
            val message = if (diagnostic.severity() == "LOGGING") {
                reasons += "compiler_model_live_informational_logging_redacted"
                "Compiler informational logging was observed; private log text is not published."
            } else diagnostic.message()
            raw += RawDiagnostic(message, severity, upstream, location)
            reasons += when {
                location == null && severity == "error" -> "compiler_model_live_global_error_unresolved"
                mapping == null -> "compiler_model_live_unmapped_diagnostic"
                !mapping.jvmApplicable -> "compiler_model_live_non_jvm_diagnostic"
                mapping.ruleId !in required -> "compiler_model_live_diagnostic_outside_required_rules"
                else -> "compiler_model_live_unlocated_or_informational_diagnostic"
            }
            continue
        }
        val fingerprint = Wire.digest("ktchecker/compiler-live-model-finding/1", Digests.canonical(buildJsonObject {
            put("rule", mapping.ruleId); put("context", input.context.scope.id); put("path", location.path)
            put("source", location.fileDigest); put("line", location.line); put("column", location.column)
            put("message", diagnostic.message())
        }).toByteArray(Charsets.UTF_8))
        findings += Finding(mapping.ruleId, "Compiler $upstream", diagnostic.message(),
            "Actual compiler diagnostic for the admitted JVM model and exact settings; emission is not complete rule coverage.",
            location, fingerprint, KotlinJvmModelLiveCompilerProvider.PROVIDER_ID, input.context.scope, severity,
            mapping.kind, mapping.certainty, evidence = Collections.unmodifiableList(listOf(
                Evidence("compiler-application-emission", "Pinned K2 JVM API diagnostic; bounded live-model preview, qualification=none",
                    digest = observation.diagnosticsDigest()),
                Evidence("application-request", "Whole P/K/C/E inputs, full snapshot, ordered compilation/classpath/options and original deadline bound",
                    digest = result.requestDigest()),
                Evidence("model-binding", "Live importer relation to the SAME selected compiler/JDK; not a model JSON claim or extraction authorization",
                    digest = modelBindingDigest),
                Evidence("snapshot", "Exact compiled member of the complete preserved snapshot; other model obligations remain independent", digest = location.fileDigest),
                Evidence("compiler-installation", "Owner-verified ordered compiler/JDK bytes; not OS/JNI attestation or arbitrary JVM reset proof", digest = installationDigest),
                Evidence("upstream-factory", mapping.source),
                Evidence("location-precision", "Reported point: one-based line, zero-based UTF-16 column; no inferred end span"),
            )), verification = Collections.unmodifiableList(listOf(Verification(expected =
                "Re-admit the edited model to this explicitly installed route; no verification command was run."))),
            upstreamId = upstream, semanticDigest = input.analysisKey())
    }
    if (raw.isNotEmpty()) reasons += "compiler_model_live_raw_diagnostics_unresolved"
    val coverage = required.map { rule ->
        val mapping = MODEL_REGISTRY.byRuleId[rule]
        if (mapping?.jvmApplicable != true) Coverage(rule, input.context.scope, "unsupported", "compiler_model_live_no_jvm_mapping")
        else Coverage(rule, input.context.scope, "unqualified", "compiler_model_live_preview_not_qualified", depth = "build",
            provenance = "executed", implemented = findings.any { it.ruleId == rule }, tested = false, qualified = false)
    }
    checkpoint()
    return ProviderResult(KotlinJvmModelLiveCompilerProvider.PROVIDER_ID, if (observation.compilerCode() == 1) "failed" else "partial",
        Collections.unmodifiableList(findings), Collections.unmodifiableList(coverage), Collections.unmodifiableList(reasons.toList()),
        Collections.unmodifiableList(raw), AnalysisMetrics(elapsedMillis = modelElapsedMillis(providerStartedNanos), cpuMillis = null,
            peakRssBytes = null, outputBytes = Wire.encode(result).size.toLong()), KotlinJvmModelLiveCompilerProvider.VERSION)
}

/** Exact UTF16 point; CR in CRLF is not a source column, and no invalid point is clipped. */
internal fun validModelPoint(source: String, line: Int, column: Int, checkpoint: () -> Unit = {}): Boolean {
    if (line < 1 || column < 1) return false
    var start = 0
    var current = 1
    while (current < line) {
        if ((current and 1_023) == 0) checkpoint()
        val next = source.indexOf('\n', start)
        if (next < 0) return false
        start = next + 1; current++
    }
    var end = source.indexOf('\n', start).takeIf { it >= 0 } ?: source.length
    if (end > start && source[end - 1] == '\r') end--
    return column <= end - start + 1
}

private fun modelElapsedMillis(started: Long): Long = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

/** Failure carries no staged findings; oversized invalid obligations remain explicitly unresolved. */
internal fun unavailableModelResult(input: AnalysisInput, reasons: List<String>, started: Long, status: String = "unavailable"): ProviderResult {
    val bounded = input.requiredRuleIds.size <= ModelLiveLimits.COMPILER_RULES && input.requiredRuleIds.all {
        it.length in 1..256 && it.none { c -> c == '\u0000' || c == '\r' || c == '\n' }
    } && listOf(input.context.scope.module, input.context.scope.compilation, input.context.scope.sourceSet, input.context.scope.target)
        .all { it.length <= 4_096 && '\u0000' !in it }
    val coverage = if (bounded) input.requiredRuleIds.distinct().map {
        Coverage(it, input.context.scope, "unsupported", reasons.firstOrNull() ?: "compiler_model_live_unavailable")
    } else emptyList()
    return ProviderResult(KotlinJvmModelLiveCompilerProvider.PROVIDER_ID, status,
        coverage = Collections.unmodifiableList(coverage), reasons = Collections.unmodifiableList((reasons +
            "compiler_model_live_elapsed_measured_to_failure_render_not_call_completion" + "compiler_model_live_cpu_rss_unavailable" +
            if (bounded) emptyList() else listOf("compiler_model_live_required_rows_unavailable")).distinct()),
        metrics = AnalysisMetrics(elapsedMillis = modelElapsedMillis(started), cpuMillis = null, peakRssBytes = null),
        providerVersion = KotlinJvmModelLiveCompilerProvider.VERSION)
}
