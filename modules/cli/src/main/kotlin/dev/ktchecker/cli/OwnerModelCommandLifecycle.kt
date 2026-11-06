package dev.ktchecker.cli

import dev.ktchecker.providers.runtime.OwnedApplicationCleanupDisposition
import dev.ktchecker.providers.runtime.OwnedSupervisorIdentity
import dev.ktchecker.providers.runtime.ProcessOutcome
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Exact original real runner call/return custody. Only the private OwnerObservedExport feeds this
 * holder in production; its data/state checks do NOT mint a model permit or a resource receipt.
 * Callback-only, substituted, failed and thrown returns can never become quiescent by polling or
 * retry. A successful return concerns the actual child group, NOT image/pipe/worker cleanup CLOSED.
 */
internal class OwnerModelExportReturnCustody {
    private val entered = AtomicBoolean()
    private val callback = AtomicReference<ProcessOutcome?>()
    private val returned = AtomicReference<ProcessOutcome?>()
    private val verificationAttempted = AtomicBoolean()
    private val quiescent = AtomicBoolean()

    val runnerEntered: Boolean get() = entered.get()
    val completed: ProcessOutcome? get() = callback.get()
    val originalReturned: ProcessOutcome? get() = returned.get()
    val childGroupQuiescent: Boolean get() = quiescent.get()
    val safeToClose: Boolean get() = !entered.get() || quiescent.get()

    fun enterRunner() {
        ownerModelRequire(entered.compareAndSet(false, true), "owner_model_export_runner_replayed")
    }
    fun retainCallback(actual: ProcessOutcome) {
        ownerModelRequire(entered.get() && callback.compareAndSet(null, actual), "owner_model_export_completion_replayed")
    }
    fun originalReturn(actual: ProcessOutcome, output: OwnerModelRootPin?, expectedSupervisor: OwnedSupervisorIdentity,
                       expectedCommand: () -> List<String>) {
        ownerModelRequire(verificationAttempted.compareAndSet(false, true), "owner_model_export_return_replayed")
        returned.set(actual) // BEFORE verification, including substituted/unverified actual returns.
        // Resolve potentially unavailable output/argv ONLY after retaining this original return
        // and consuming its one verification attempt. Failure cannot become callback-only DATA
        // or be retried later with a repaired expected tuple.
        verifyTuple(actual, output, expectedCommand(), expectedSupervisor)
        quiescent.set(true)
    }
    fun verifyOriginalReturn(output: OwnerModelRootPin?, expectedCommand: List<String>, expectedSupervisor: OwnedSupervisorIdentity) {
        ownerModelRequire(quiescent.get(), "owner_model_export_return_unverified")
        verifyTuple(requireNotNull(returned.get()), output, expectedCommand, expectedSupervisor)
    }
    private fun verifyTuple(actual: ProcessOutcome, output: OwnerModelRootPin?, expectedCommand: List<String>,
                            expectedSupervisor: OwnedSupervisorIdentity) {
        ownerModelRequire(entered.get() && callback.get() === actual && returned.get() === actual,
            "owner_model_export_outcome_substituted")
        val root = output ?: throw CliProblem("owner_model_export_output_unavailable", "No pre-registered export output exists.")
        root.verify()
        // The runtime's actual status=executed already requires native FINAL+EOF+helper0+
        // QUIESCENT and its pinned supervisor image. These scalar checks alone are not authority.
        ownerModelRequire(actual.successfulExecution && actual.executed && actual.exitCode == 0 && actual.reasons.isEmpty() &&
            !actual.cancelled && !actual.timedOut && !actual.truncated && actual.command == expectedCommand &&
            actual.outputDirectory == root.path && actual.supervisorIdentity == expectedSupervisor,
            "owner_model_export_failed_or_nonquiescent")
    }
}

/** One explicit ORIGINAL runtime retirement return, retained before validation. Never a retry. */
internal class OwnerModelGenerationRetirement(private val generationId: String, private val namespaceDigest: String) {
    private val requested = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val matched = AtomicBoolean()
    private val actual = AtomicReference<OwnerLiveGenerationStatus?>()
    val attempted: Boolean get() = requested.get()
    val threw: Boolean get() = failed.get()
    val returned: OwnerLiveGenerationStatus? get() = actual.get()
    val verified: Boolean get() = matched.get() && actual.get()?.retirement?.disposition == OwnedApplicationCleanupDisposition.CLOSED

    fun retire(openAttempted: Boolean, originalOperation: () -> OwnerLiveGenerationStatus): OwnerLiveGenerationStatus {
        ownerModelRequire(openAttempted && requested.compareAndSet(false, true), "owner_model_retirement_not_owned_or_replayed")
        return try {
            val value = originalOperation() // No monitor held across the real runtime/native operation.
            actual.set(value)
            ownerModelRequire(value.generationId == generationId && value.namespaceDigest == namespaceDigest,
                "owner_model_retirement_return_mismatch")
            matched.set(true)
            value
        } catch (failure: Throwable) { failed.set(true); throw failure }
    }
}

/**
 * The ACTUAL session quarantine callsite, allocated before execution. Every fence/retain/denial/
 * anchor action is inside this one protected path. Callback arguments are NOT allocated on an
 * unsafe call. Failure anywhere, including the first retain, idles on the owner-bearing stack;
 * no cleanup/close/signal/retry is attempted. Tests inject only a throwing idle sentinel.
 */
internal class OwnerModelLifetimeQuarantine(
    private val fence: () -> Unit,
    private val retain: () -> Unit,
    private val deny: () -> Unit,
    private val startAnchor: () -> Unit,
    private val idle: () -> Nothing,
) {
    fun enter() {
        try { fence(); retain(); deny(); startAnchor() }
        catch (_: Throwable) {
            // Keep this preallocated owner-bearing holder live across the nonreturning idle,
            // including a failed first retain; stack liveness must not depend on a JIT guess.
            try { idle() } finally { java.lang.ref.Reference.reachabilityFence(this) }
        }
    }
}
