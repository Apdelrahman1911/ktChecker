package dev.ktchecker.compiler

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.AnalysisMetrics
import dev.ktchecker.contracts.AnalysisProvider
import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Private documentary observations only: none of these states grants execution or certifies a review. */
enum class CompilerObservationCallState { WAITING, ACTIVE, SETTLED, SETUP_FAILED, PUBLICATION_FAILED }
enum class CompilerObservationCaptureState { NOT_REACHED, CAPTURED, CAPTURE_FAILED }

data class CompilerObservationSlot<out T> internal constructor(
    val state: CompilerObservationCaptureState,
    val value: T?,
)

/** Every retained identity string is either null or exactly one validated 71-character SHA256 digest.
 * analysisKey is the already-computed key of the actual frozen provider request, including its profile.
 * No source/input/profile graph, path, argument or environment value is retained by this receipt. */
data class CompilerObservedIdentity internal constructor(
    val analysisKey: String?,
    val snapshotId: String?,
    val configurationDigest: String?,
    val modelDigest: String?,
    val dependencyDigest: String?,
    val declaredToolchainDigest: String?,
    val installedToolchainDigest: String?,
    val executableDigest: String?,
    val mappingResourceDigest: String?,
    val compilerHeapMiB: Int,
    val timeoutMillis: Long,
    val maxOutputBytes: Int,
    val profileEnvironmentEntries: Int,
    /** Invalid/oversize non-null identities are omitted, never truncated or replaced by caller keys. */
    val identityFieldsOmitted: Boolean,
)

data class CompilerObservedReasons internal constructor(
    val originalCount: Int,
    val inspectedCount: Int,
    val omittedInspectedCount: Int,
    val values: List<String>,
) {
    val uninspectedCount: Int get() = originalCount - inspectedCount
    val exact: Boolean get() = uninspectedCount == 0 && omittedInspectedCount == 0
}

/** Original nullable metrics, not inferred child CPU/RSS or a new public AnalysisMetrics channel. */
data class CompilerObservedMetrics internal constructor(
    val elapsedMillis: Long,
    val cpuMillis: Long?,
    val peakRssBytes: Long?,
    val outputBytes: Long,
)

data class CompilerObservedProcessOutcome internal constructor(
    /** Only fixed public statuses are retained; null means an unknown status was omitted. */
    val status: String?,
    val reasons: CompilerObservedReasons,
    val exitCode: Int?,
    val cancelled: Boolean,
    val timedOut: Boolean,
    val truncated: Boolean,
    val executionConfirmed: Boolean,
    val metrics: CompilerObservedMetrics,
) {
    val fieldsComplete: Boolean get() = status != null && reasons.exact
}

data class CompilerObservedProviderResult internal constructor(
    val providerIdentityMatches: Boolean,
    val status: String?,
    val reasons: CompilerObservedReasons,
    val findingCount: Int,
    val coverageCount: Int,
    val rawDiagnosticCount: Int,
    val metrics: CompilerObservedMetrics,
) {
    val fieldsComplete: Boolean get() = providerIdentityMatches && status != null && reasons.exact
}

/** The existing fixed phase labels are projected as strings, never supplied by an owner callback. */
data class CompilerObservedPhaseInterval internal constructor(
    val sequence: Int,
    val phase: String,
    val occurrence: Int,
    val parentSequence: Int?,
    val verificationOccurrence: Int?,
    val startNanos: Long?,
    val endNanos: Long?,
    val elapsedNanos: Long?,
    val actionReturnedNormally: Boolean?,
)

data class CompilerObservedPhases internal constructor(
    val capacity: Int,
    val droppedPhaseCount: Long,
    val clockValid: Boolean,
    val nestingValid: Boolean,
    val allRecordedActionsFinished: Boolean,
    val intervals: List<CompilerObservedPhaseInterval>,
) {
    val observationsComplete: Boolean get() = droppedPhaseCount == 0L && clockValid && nestingValid && allRecordedActionsFinished
}

data class CompilerCallObservationReceipt internal constructor(
    /** Ordinal within this one-shot handle, not a kernel attempt ID. */
    val callOrdinal: Int,
    /** Normal return includes refusal/failure; this is never an execution-success bit. */
    val providerReturnedNormally: Boolean,
    val identity: CompilerObservationSlot<CompilerObservedIdentity>,
    val runnerReturnedOutcome: CompilerObservationSlot<CompilerObservedProcessOutcome>,
    val providerAdjustedOutcome: CompilerObservationSlot<CompilerObservedProcessOutcome>,
    val providerReturn: CompilerObservationSlot<CompilerObservedProviderResult>,
    val phases: CompilerObservedPhases,
)

data class CompilerCallObservationSnapshot internal constructor(
    val schema: String,
    val ownerAttached: Boolean,
    /** Documentary code identity supplied by the trusted composition, not loaded-code attestation. */
    val ownerCodeDigest: String?,
    val ownerCodeDigestOmitted: Boolean,
    val state: CompilerObservationCallState,
    /** Independently sampled count at this read, not final Workbench/extra-call completeness. */
    val extraCallsAtRead: Long,
    val call: CompilerCallObservationReceipt?,
    val snapshotFailed: Boolean,
)

/**
 * Final, nonserializable owner-installed handle. No input/JSON/RPC/environment option, callback,
 * predicate, mutable profile, recorder, capacity or clock is exposed. It retains at most ONE call:
 * <=64 phase rows, three outcome slots with <=64 public reason strings of <=256 chars each, nine
 * <=71-char input-identity digests, one <=71-char code digest, and fixed scalar/state fields.
 * Whole AnalysisInput/ProcessOutcome/ProviderResult graphs and exception objects are never retained.
 * Captured bounded scalars live until the handle is released; detached snapshots belong to the owner.
 *
 * snapshot() never inspects an active recorder. SETTLED means only that the provider call settled.
 * State and extra count are independent atomic reads, not a multi-field linearizable transaction.
 * The owner must read AFTER independently establishing final Workbench settlement and stopping new
 * submissions, then check extras and exact identity/outcome/phase joins. There is intentionally no
 * eligibility/final-success bit, cached acceptance, rearm, automatic retry, export or file I/O.
 */
class CompilerCallObservation {
    private data class OwnerIdentity(val digest: String?, val omitted: Boolean)
    private sealed interface Call {
        object Waiting : Call
        object Active : Call
        object SetupFailed : Call
        object PublicationFailed : Call
        class Settled(val capture: CompilerCallCapture) : Call
    }

    // Resolve lazy object sentinels during owner construction, never in a call's allocation-failure path.
    private val activeState = Call.Active
    private val setupFailedState = Call.SetupFailed
    private val publicationFailedState = Call.PublicationFailed
    private val owner = AtomicReference<OwnerIdentity?>(null)
    private val call = AtomicReference<Call>(Call.Waiting)
    private val extras = AtomicLong(0)

    /** One trusted ordinary composition only; this wrapper cannot generate or authorize a profile. */
    fun observedProvider(provider: KotlinJvmCompilerProvider, ownerCodeDigest: String): AnalysisProvider {
        require(provider.inputMode == KotlinJvmInputMode.LEGACY_EXPLICIT_INPUT) {
            "Compiler call observations are only installed on the ordinary provider route"
        }
        val digest = observationDigest(ownerCodeDigest)
        check(owner.compareAndSet(null, OwnerIdentity(digest, digest == null))) {
            "A compiler observation handle belongs to one owner composition and cannot be reattached"
        }
        return object : AnalysisProvider {
            override val id: String get() = provider.id
            override val version: String get() = provider.version
            override val supportedRuleIds: Set<String> get() = provider.supportedRuleIds
            override fun analyze(input: AnalysisInput, cancellation: CancellationToken): ProviderResult =
                this@CompilerCallObservation.observeCall(provider.supportedRuleIds) { capture ->
                    if (capture == null) provider.analyze(input, cancellation)
                    else provider.analyzeObserved(input, cancellation, capture)
                }
        }
    }

    /** Compiler-module-only exactly-once adapter; the transient action is never stored or public. */
    internal fun observeCall(supportedRuleIds: Set<String>, action: (CompilerCallCapture?) -> ProviderResult): ProviderResult {
        val capture = claim()
        // Setup failure or an extra call reaches the default delegate ONCE, not after a failed attempt.
        if (capture == null) return action(null)
        var returnedNormally = false
        try {
            val result = action(capture)
            returnedNormally = true
            capture.captureProviderResult(result, supportedRuleIds)
            return result
        } finally {
            // analyze's original result/exception is never caught, wrapped, suppressed or retried.
            capture.finish(returnedNormally)
            publish(capture)
        }
    }

    private fun claim(): CompilerCallCapture? {
        if (!call.compareAndSet(Call.Waiting, activeState)) {
            while (true) {
                val before = extras.get()
                if (before == Long.MAX_VALUE || extras.compareAndSet(before, before + 1)) break
            }
            return null
        }
        return try { CompilerCallCapture() } catch (_: Throwable) {
            call.set(setupFailedState)
            null
        }
    }

    private fun publish(capture: CompilerCallCapture) {
        try { call.set(Call.Settled(capture)) }
        catch (_: Throwable) { call.set(publicationFailedState) }
    }

    fun snapshot(): CompilerCallObservationSnapshot {
        val installed = owner.get()
        val state = call.get()
        var failed = false
        // Active state carries NO recorder reference; a snapshot cannot race its mutable arrays.
        val receipt = if (state is Call.Settled) try { state.capture.snapshot() }
            catch (_: Throwable) { failed = true; null } else null
        return CompilerCallObservationSnapshot("ktchecker/compiler-call-observation/1", installed != null,
            installed?.digest, installed?.omitted ?: false, when (state) {
                Call.Waiting -> CompilerObservationCallState.WAITING
                Call.Active -> CompilerObservationCallState.ACTIVE
                Call.SetupFailed -> CompilerObservationCallState.SETUP_FAILED
                Call.PublicationFailed -> CompilerObservationCallState.PUBLICATION_FAILED
                is Call.Settled -> CompilerObservationCallState.SETTLED
            }, extras.get(), receipt, failed)
    }
}

/** Per-call mutable storage is compiler-internal and is published only after the call finishes. */
internal class CompilerCallCapture(val timings: CompilerPhaseTimings = CompilerPhaseTimings()) {
    private var returnedNormally: Boolean? = null
    private var identityState = CompilerObservationCaptureState.NOT_REACHED
    private var identity: CompilerObservedIdentity? = null
    private var runnerState = CompilerObservationCaptureState.NOT_REACHED
    private var runner: CompilerObservedProcessOutcome? = null
    private var adjustedState = CompilerObservationCaptureState.NOT_REACHED
    private var adjusted: CompilerObservedProcessOutcome? = null
    private var resultState = CompilerObservationCaptureState.NOT_REACHED
    private var result: CompilerObservedProviderResult? = null

    /** Reuses the actual request key already computed for the original permit comparison.
     * The request is a transient parameter only; no graph or new hash sampling is retained/added. */
    fun captureIdentity(actualKey: String, request: AnalysisInput, installedToolchainDigest: String,
        mappingResourceDigest: String, compilerHeapMiB: Int) {
        identityState = CompilerObservationCaptureState.CAPTURE_FAILED
        try {
            var omitted = false
            fun digest(value: String?): String? = observationDigest(value).also {
                if (value != null && it == null) omitted = true
            }
            val execution = checkNotNull(request.execution)
            identity = CompilerObservedIdentity(digest(actualKey), digest(request.snapshot.snapshotId),
                digest(request.context.configurationDigest), digest(request.context.modelDigest),
                digest(request.context.dependencyDigest), digest(request.context.toolchainDigest),
                digest(installedToolchainDigest), digest(execution.executableDigest), digest(mappingResourceDigest),
                compilerHeapMiB, execution.timeoutMillis, execution.maxOutputBytes, execution.environment.size, omitted)
            identityState = CompilerObservationCaptureState.CAPTURED
        } catch (_: Throwable) { identity = null }
    }

    /** Called only after the original provider total-timeout decision and before executionObserver.
     * Both copies are independently fault-isolated; no mutable reason-list alias or outcome graph escapes. */
    fun captureOutcomes(raw: ProcessOutcome, normalized: ProcessOutcome, supportedRuleIds: Set<String>) {
        runnerState = CompilerObservationCaptureState.CAPTURE_FAILED
        try { runner = processOutcome(raw, supportedRuleIds); runnerState = CompilerObservationCaptureState.CAPTURED }
        catch (_: Throwable) { runner = null }
        adjustedState = CompilerObservationCaptureState.CAPTURE_FAILED
        try { adjusted = processOutcome(normalized, supportedRuleIds); adjustedState = CompilerObservationCaptureState.CAPTURED }
        catch (_: Throwable) { adjusted = null }
    }

    fun captureProviderResult(value: ProviderResult, supportedRuleIds: Set<String>) {
        resultState = CompilerObservationCaptureState.CAPTURE_FAILED
        try {
            result = CompilerObservedProviderResult(value.providerId == CompilerRuleRegistry.PROVIDER_ID &&
                value.providerVersion == CompilerRuleRegistry.VERSION, publicStatus(value.status),
                publicReasons(value.reasons, supportedRuleIds), value.findings.size, value.coverage.size,
                value.rawDiagnostics.size, metrics(value.metrics))
            resultState = CompilerObservationCaptureState.CAPTURED
        } catch (_: Throwable) { result = null }
    }

    fun finish(normally: Boolean) { returnedNormally = normally }

    fun snapshot(): CompilerCallObservationReceipt {
        val normal = checkNotNull(returnedNormally) { "An active compiler capture cannot be projected" }
        val phase = timings.snapshot()
        val projected = CompilerObservedPhases(phase.capacity, phase.droppedPhaseCount, phase.clockValid,
            phase.nestingValid, phase.allRecordedActionsFinished, Collections.unmodifiableList(phase.intervals.map {
                CompilerObservedPhaseInterval(it.sequence, it.phase.label, it.occurrence, it.parentSequence,
                    it.verificationOccurrence, it.startNanos, it.endNanos, it.elapsedNanos, it.actionReturnedNormally)
            }))
        return CompilerCallObservationReceipt(1, normal, CompilerObservationSlot(identityState, identity),
            CompilerObservationSlot(runnerState, runner), CompilerObservationSlot(adjustedState, adjusted),
            CompilerObservationSlot(resultState, result), projected)
    }
}

private fun observationDigest(value: String?): String? {
    if (value == null || value.length != 71 || !value.startsWith("sha256:")) return null
    for (index in 7 until 71) if (value[index] !in '0'..'9' && value[index] !in 'a'..'f') return null
    return value
}

private fun metrics(value: AnalysisMetrics) = CompilerObservedMetrics(value.elapsedMillis, value.cpuMillis,
    value.peakRssBytes, value.outputBytes)

private fun publicStatus(value: String): String? = when (value) {
    "executed", "unavailable", "failed", "cancelled", "partial", "complete" -> value
    else -> null
}

private fun processOutcome(value: ProcessOutcome, supportedRuleIds: Set<String>) = CompilerObservedProcessOutcome(
    publicStatus(value.status), publicReasons(value.reasons, supportedRuleIds), value.exitCode, value.cancelled,
    value.timedOut, value.truncated, value.executionConfirmed, metrics(value.metrics))

/** Finite public vocabulary. Exception messages/codes are NOT automatically safe because they are short. */
private fun approvedReason(value: String, supportedRuleIds: Set<String>): Boolean {
    if (value.length > 256) return false
    when (value) {
        "cancelled_before_execution", "cancelled_during_compiler_preparation", "target_not_jvm",
        "compiler_version_not_pinned_2_4_21", "compiler_toolchain_identity_mismatch", "compiler_model_route_required",
        "execution_not_authorized", "execution_profile_missing", "execution_identity_binding_mismatch",
        "compiler_settings_unsupported", "execution_profile_mismatch", "compiler_preparation_timeout",
        "sealed_input_or_toolchain_verification_failed", "compiler_total_timeout",
        "execution_cancelled", "execution_timeout", "process_supervisor_control_failed",
        "snapshot_binding_mismatch", "execution_limits_invalid", "executable_unavailable",
        "executable_digest_mismatch", "output_root_required", "output_source_overlap",
        "output_identity_unavailable", "execution_arguments_invalid", "snapshot_verification_failed",
        "analysis_descriptor_changed", "executable_changed", "snapshot_changed_during_execution",
        "process_output_invalid_utf8", "snapshot_root_invalid", "snapshot_provenance_unavailable",
        "snapshot_duplicate_path", "snapshot_content_mismatch", "relative_path_invalid",
        "compiler_version_transport_mismatch", "unparsed_or_malformed_compiler_output",
        "compiler_source_suppression_requires_scoped_coverage_evidence",
        "raw_task_diagnostics_prevent_complete_coverage", "resolved_compilation_context_incomplete" -> return true
    }
    val colon = value.indexOf(':')
    if (colon < 0) return false
    val prefix = value.substring(0, colon)
    val suffix = value.substring(colon + 1)
    if (prefix == "compiler_build_failed") return suffix == "not_exited" ||
        suffix.toIntOrNull()?.toString() == suffix
    return when (prefix) {
        "no_jvm_mapping_for_rule", "compiler_execution_or_version_untrusted",
        "failed_or_incomplete_compiler_task", "compiler_source_suppression_requires_scoped_coverage_evidence",
        "resolved_compilation_context_incomplete", "exact_rule_context_tuple_not_qualified",
        "mapped_diagnostic_outside_selected_rules" -> suffix in supportedRuleIds
        else -> false
    }
}

private fun publicReasons(source: List<String>, supportedRuleIds: Set<String>): CompilerObservedReasons {
    val count = source.size
    check(count >= 0)
    val inspected = minOf(count, 64)
    val values = ArrayList<String>(inspected)
    var omitted = 0
    for (index in 0 until inspected) {
        val reason = source[index]
        if (approvedReason(reason, supportedRuleIds)) values += reason else omitted++
    }
    return CompilerObservedReasons(count, inspected, omitted, Collections.unmodifiableList(values))
}
