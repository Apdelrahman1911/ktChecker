package dev.ktchecker.compiler.live

import dev.ktchecker.compiler.CompilerRuleRegistry
import dev.ktchecker.contracts.*
import dev.ktchecker.providers.runtime.OwnedApplicationJobResult
import dev.ktchecker.providers.runtime.OwnedApplicationReason
import dev.ktchecker.workerprotocol.ApplicationWire as Wire
import dev.ktchecker.workerprotocol.CompilerJobWire as CompilerWire
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*

/**
 * Distinct dormant route. The owner must retain a new application-attempt grant for THIS provider
 * OBJECT. A decoded profile, old CLI permit or otherwise-equal provider instance grants nothing.
 */
class KotlinJvmLiveCompilerProvider(
    private val application: KotlinJvmCompilerApplication,
    private val authority: KotlinJvmApplicationAuthority = KotlinJvmApplicationAuthority.DENY,
) : AnalysisProvider {
    override val id: String = PROVIDER_ID
    override val version: String = VERSION
    override val supportedRuleIds: Set<String> = LIVE_REGISTRY.jvmRuleIds

    internal fun uses(selected: KotlinJvmCompilerApplication): Boolean = application === selected

    override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult {
        val request = try {
            CompilerAttemptRequest(application, input).also { validateLiveOriginal(application.request, it.input) }
        } catch (failure: CompilerApplicationProblem) {
            return unavailableLiveResult(input, listOf(failure.code))
        } catch (_: Exception) {
            return unavailableLiveResult(input, listOf("compiler_live_input_invalid"))
        }
        try {
            if (cancellation.isCancelled()) {
                return unavailableLiveResult(request.input, listOf("compiler_live_cancelled"), "cancelled")
            }
        } catch (_: Exception) {
            return unavailableLiveResult(request.input, listOf("compiler_live_cancellation_check_failed"))
        }
        val permit = try { authority.authorizeAttempt(this, request) } catch (_: Exception) {
            return unavailableLiveResult(request.input, listOf("compiler_live_attempt_authority_failed"))
        } ?: return unavailableLiveResult(request.input, listOf("compiler_live_attempt_not_authorized"))
        if (!permit.matches(this, request)) {
            return unavailableLiveResult(request.input, listOf("compiler_live_attempt_binding_mismatch"))
        }
        return try {
            when (val result = application.compile(permit, cancellation)) {
                // NO parsing, mapping, suppression, row construction, clocks or promotion after acceptance.
                is OwnedApplicationJobResult.Accepted -> result.value
                is OwnedApplicationJobResult.Unavailable -> unavailableLiveResult(request.input,
                    listOf("compiler_live_application_unavailable") + result.reasons.map { it.code },
                    if (OwnedApplicationReason.CANCELLED in result.reasons) "cancelled" else "unavailable")
            }
        } catch (_: CancellationException) {
            unavailableLiveResult(request.input, listOf("compiler_live_cancelled"), "cancelled")
        } catch (failure: CompilerApplicationProblem) {
            unavailableLiveResult(request.input, listOf(failure.code))
        } catch (_: Exception) {
            unavailableLiveResult(request.input, listOf("compiler_live_application_failed"))
        }
    }

    companion object {
        const val PROVIDER_ID = "kotlin-jvm-live-preview"
        const val VERSION = "2.4.21/live-preview-1"
    }
}

private val LIVE_REGISTRY: CompilerRuleRegistry by lazy { CompilerRuleRegistry.load() }
private val LIVE_DIAGNOSTIC_NAME = Regex("[A-Z][A-Z0-9_]{1,255}")
private val LIVE_SUPPRESSION = Regex("@(?:file\\s*:\\s*)?(?:kotlin\\.)?Suppress\\s*\\(")

/** Fixed renderer used ONLY from the armed validateResult callback; tests pass synthetic DTOs explicitly. */
internal fun renderLiveObservation(original: AnalysisInput, sourceText: String, materializedSourceRoot: Wire.Root,
    installationDigest: String, result: Wire.Result, observation: CompilerWire.Observation): ProviderResult {
    CompilerWire.requireSealed(observation)
    liveCheck(result.state() == Wire.ResultState.APPLICATION_SEALED && result.originalKey() == original.analysisKey() &&
        observation.sourceRoot() == materializedSourceRoot && result.payload().contentEquals(CompilerWire.encodeObservation(observation)),
        "compiler_live_result_binding_mismatch")
    val source = original.snapshot.files.single()
    liveCheck(source.path == "Fixture.kt" && source.size == sourceText.toByteArray(Charsets.UTF_8).size.toLong() &&
        source.digest == Digests.sha256(sourceText.toByteArray(Charsets.UTF_8)), "compiler_live_render_source_mismatch")
    val physicalSource = Path.of(materializedSourceRoot.path()).resolve(source.path).toString()
    val required = original.requiredRuleIds.distinct()
    val findings = ArrayList<Finding>()
    val raw = ArrayList<RawDiagnostic>()
    val reasons = linkedSetOf("compiler_live_preview_not_qualified", "compiler_live_semantic_extraction_not_authorized")
    if (observation.compilerCode() == 1) reasons += "compiler_live_api_diagnostic_failure"
    if (LIVE_SUPPRESSION.containsMatchIn(sourceText)) reasons += "compiler_live_suppression_scope_not_certified"
    for (diagnostic in observation.diagnostics()) {
        CompilerWire.validateDiagnostic(diagnostic)
        val severity = when (diagnostic.severity()) {
            "ERROR" -> "error"
            "WARNING", "STRONG_WARNING", "MANDATORY_WARNING" -> "warning"
            "INFO", "LOGGING" -> "info"
            else -> liveFailure("compiler_live_diagnostic_severity_invalid")
        }
        val reportedPath = diagnostic.path()
        val location = if (reportedPath.isEmpty()) {
            liveCheck(severity != "error", "compiler_live_diagnostic_location_invalid")
            null
        } else {
            liveCheck(reportedPath == physicalSource || reportedPath == source.path, "compiler_live_diagnostic_source_mismatch")
            liveCheck(validLivePoint(sourceText, diagnostic.line(), diagnostic.column()), "compiler_live_diagnostic_location_invalid")
            SourceLocation(source.path, source.digest, diagnostic.line(), diagnostic.column() - 1,
                diagnostic.line(), diagnostic.column() - 1)
        }
        val upstream = diagnostic.diagnosticId().takeIf { LIVE_DIAGNOSTIC_NAME.matches(it) }
        val mapping = upstream?.let { LIVE_REGISTRY.byUpstreamId[it] }
        if (mapping == null || !mapping.jvmApplicable || mapping.ruleId !in required || location == null || severity == "info") {
            // Logging frequently includes owner-private installation paths; it is not a named source
            // diagnostic. Keep its explicit presence/digest, not the private log text, in public output.
            val message = if (diagnostic.severity() == "LOGGING") {
                reasons += "compiler_live_informational_logging_redacted"
                "Compiler informational logging was observed; private log text is not published."
            } else diagnostic.message()
            raw += RawDiagnostic(message, severity, upstream, location)
            reasons += when {
                mapping == null -> "compiler_live_unmapped_diagnostic"
                !mapping.jvmApplicable -> "compiler_live_non_jvm_diagnostic"
                mapping.ruleId !in required -> "compiler_live_diagnostic_outside_required_rules"
                else -> "compiler_live_unlocated_or_informational_diagnostic"
            }
            continue
        }
        val fingerprint = Wire.digest("ktchecker/compiler-live-finding/1", Digests.canonical(buildJsonObject {
            put("rule", mapping.ruleId); put("context", original.context.scope.id); put("path", location.path)
            put("source", location.fileDigest); put("line", location.line); put("column", location.column)
            put("message", diagnostic.message())
        }).toByteArray(Charsets.UTF_8))
        findings += Finding(mapping.ruleId, "Compiler $upstream", diagnostic.message(),
            "Actual compiler diagnostic under the admitted settings; an emission is not complete rule coverage.",
            location, fingerprint, KotlinJvmLiveCompilerProvider.PROVIDER_ID, original.context.scope, severity,
            mapping.kind, mapping.certainty, evidence = Collections.unmodifiableList(listOf(
                Evidence("compiler-application-emission", "Pinned K2 JVM API diagnostic; bounded live-preview observation, qualification=none",
                    digest = observation.diagnosticsDigest()),
                Evidence("application-request", "Exact original/effective inputs, job roots, ordered dependencies and options bound by the checked request",
                    digest = result.requestDigest()),
                Evidence("snapshot", "Registered immutable source; the single-source test profile is not a complete project", digest = source.digest),
                Evidence("compiler-installation", "Owner-verified ordered compiler/JDK installation; not OS/JNI attestation or semantic-extraction authority",
                    digest = installationDigest),
                Evidence("upstream-factory", mapping.source),
                Evidence("location-precision", "Reported point: one-based line, zero-based UTF-16 column; no inferred end span"),
            )), verification = Collections.unmodifiableList(listOf(Verification(expected =
                "Re-admit the edited input to this live-preview provider; no verification command was run."))),
            upstreamId = upstream, semanticDigest = original.analysisKey())
    }
    if (raw.isNotEmpty()) reasons += "compiler_live_raw_diagnostics_unresolved"
    val coverage = required.map { rule ->
        val mapping = LIVE_REGISTRY.byRuleId[rule]
        if (mapping?.jvmApplicable != true) Coverage(rule, original.context.scope, "unsupported", "compiler_live_no_jvm_mapping")
        else Coverage(rule, original.context.scope, "unqualified", "compiler_live_preview_not_qualified", depth = "build",
            provenance = "executed", implemented = findings.any { it.ruleId == rule }, tested = false, qualified = false)
    }
    return ProviderResult(KotlinJvmLiveCompilerProvider.PROVIDER_ID,
        if (observation.compilerCode() == 1) "failed" else "partial", Collections.unmodifiableList(findings),
        Collections.unmodifiableList(coverage), Collections.unmodifiableList(reasons.toList()), Collections.unmodifiableList(raw),
        AnalysisMetrics(outputBytes = Wire.encode(result).size.toLong()), KotlinJvmLiveCompilerProvider.VERSION)
}

private fun validLivePoint(source: String, line: Int, column: Int): Boolean {
    if (line < 1 || column < 1) return false
    val row = source.split('\n').getOrNull(line - 1)?.removeSuffix("\r") ?: return false
    return column <= row.length + 1 // Kotlin String length and compiler columns are UTF-16 units.
}

/** No staged findings escape on failure. Oversized invalid plans retain missing/unresolved obligations. */
internal fun unavailableLiveResult(input: AnalysisInput, reasons: List<String>, status: String = "unavailable"): ProviderResult {
    val bounded = input.requiredRuleIds.size <= 1024 && input.requiredRuleIds.all { it.length in 1..256 &&
        it.none { c -> c == '\u0000' || c == '\r' || c == '\n' } } &&
        listOf(input.context.scope.target, input.context.scope.sourceSet, input.context.scope.module, input.context.scope.compilation)
            .all { it.length <= 4096 && '\u0000' !in it }
    val coverage = if (bounded) input.requiredRuleIds.distinct().map {
        Coverage(it, input.context.scope, "unsupported", reasons.firstOrNull() ?: "compiler_live_unavailable")
    } else emptyList()
    return ProviderResult(KotlinJvmLiveCompilerProvider.PROVIDER_ID, status,
        coverage = Collections.unmodifiableList(coverage),
        reasons = Collections.unmodifiableList((reasons + if (bounded) emptyList() else listOf("compiler_live_required_rows_unavailable")).distinct()),
        providerVersion = KotlinJvmLiveCompilerProvider.VERSION)
}
