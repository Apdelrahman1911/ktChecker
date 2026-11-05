package dev.ktchecker.compiler.live

import dev.ktchecker.providers.runtime.OwnedApplicationDeadline

/**
 * Exact host-side attempt ownership. This is the sole per-attempt state machine, not a second
 * transport/session. A ticket belongs to exactly one retained CompilerAttemptPermit in production.
 * No owner hook, cancellation I/O, compiler operation or result renderer runs under [lock].
 */
internal class CompilerApplicationLifecycle(
    private val generationDeadline: OwnedApplicationDeadline,
    private val cancelGeneration: () -> Unit,
) {
    private val lock = Any()
    private var closed = false
    private var ready = false
    private var cancelRequested = false
    private var active: Ticket? = null

    internal enum class State { NEW, ACTIVE, ACCEPTED, REJECTED, REVOKED }

    internal class Ticket internal constructor(
        internal val owner: CompilerApplicationLifecycle,
        internal val originalDeadline: OwnedApplicationDeadline,
    ) {
        // Read/written only by the owning controller under its lock. No independent permit flags.
        internal var state = State.NEW
    }

    fun newTicket(originalDeadline: OwnedApplicationDeadline): Ticket = Ticket(this, originalDeadline)

    /**
     * One-way readiness after actual generation binding. Charge the exact ingress clock and present
     * caller interrupt at this gate, after all callbacks; no callback or refreshed budget under lock.
     */
    fun openAdmission() = synchronized(lock) {
        liveCheck(!closed, "compiler_live_application_closed")
        liveCheck(!ready, "compiler_live_application_already_ready")
        liveCheck(!generationDeadline.isExpired(), "compiler_live_generation_deadline")
        liveCheck(!Thread.currentThread().isInterrupted, "compiler_live_interrupted")
        ready = true
    }

    fun checkOpen() = synchronized(lock) {
        liveCheck(!closed, "compiler_live_application_closed")
        liveCheck(ready, "compiler_live_application_not_ready")
    }

    fun isLive(ticket: Ticket): Boolean = synchronized(lock) {
        ticket.owner === this && !closed && ready && (ticket.state == State.NEW || ticket.state == State.ACTIVE)
    }

    fun isActive(ticket: Ticket): Boolean = synchronized(lock) {
        ticket.owner === this && !closed && ready && active === ticket && ticket.state == State.ACTIVE
    }

    fun checkTicketLive(ticket: Ticket) = synchronized(lock) {
        requireOwn(ticket)
        liveCheck(ticket.state != State.REVOKED, "compiler_live_attempt_revoked")
        liveCheck(ticket.state == State.NEW || ticket.state == State.ACTIVE, "compiler_live_attempt_permit_used")
        liveCheck(!closed, "compiler_live_application_closed")
        liveCheck(ready, "compiler_live_application_not_ready")
    }

    fun requireActive(ticket: Ticket) = synchronized(lock) {
        requireOwn(ticket)
        requireActiveLocked(ticket)
    }

    /** Reject before any materialization. Replaying the active ticket MUST NOT terminalize it. */
    fun admit(ticket: Ticket) = synchronized(lock) {
        requireOwn(ticket)
        liveCheck(ticket.state != State.REVOKED, "compiler_live_attempt_revoked")
        liveCheck(ticket.state == State.NEW, "compiler_live_attempt_permit_used")
        ticket.state = State.REJECTED // Only this fresh ticket is consumed on a not-ready/closed/busy rejection.
        liveCheck(!closed, "compiler_live_application_closed")
        liveCheck(ready, "compiler_live_application_not_ready")
        liveCheck(active == null, "compiler_live_concurrent_attempt")
        ticket.state = State.ACTIVE
        active = ticket
    }

    /**
     * Publication linearization only: the already-complete Accepted.value is never transformed.
     * Revoke-first makes this fail; accept-first detaches A before any B admission or late A revoke.
     * Fixed original-clock and present-interrupt reads are inside the gate, after the last callback;
     * an expired/interrupted caller cannot detach its active ticket or publish a staged value.
     */
    fun finishAccepted(ticket: Ticket) = synchronized(lock) {
        requireOwn(ticket)
        requireActiveLocked(ticket)
        liveCheck(!generationDeadline.isExpired(), "compiler_live_generation_deadline")
        liveCheck(!ticket.originalDeadline.isExpired(), "compiler_live_request_deadline")
        liveCheck(!Thread.currentThread().isInterrupted, "compiler_live_interrupted")
        ticket.state = State.ACCEPTED
        active = null
    }

    /** Never-admitted, rejected and completed tickets can invalidate only themselves. */
    fun revoke(ticket: Ticket) {
        val cancel = synchronized(lock) {
            requireOwn(ticket)
            val ownsActive = active === ticket && ticket.state == State.ACTIVE
            ticket.state = State.REVOKED
            if (ownsActive) closeActiveLocked() else false
        }
        if (cancel) cancelGeneration()
    }

    /** An admitted failure cancels only its own active scope, never a later request. */
    fun failActive(ticket: Ticket) {
        val cancel = synchronized(lock) {
            requireOwn(ticket)
            if (active !== ticket || ticket.state != State.ACTIVE) false else {
                ticket.state = State.REJECTED
                closeActiveLocked()
            }
        }
        if (cancel) cancelGeneration()
    }

    /** Explicit owner cancellation remains generation-wide, even while idle. */
    fun cancel() {
        val cancel = synchronized(lock) {
            active?.state = State.REVOKED
            closeActiveLocked()
        }
        if (cancel) cancelGeneration()
    }

    /**
     * The generation-permit callback owns subsequent transport cancellation. Close only state here:
     * calling cancelGeneration would recurse through that same permit. This is the same lock as
     * finishAccepted; completed-before-commit generation revocation suppresses staged publication.
     */
    fun closeForGenerationRevocation() = synchronized(lock) {
        closed = true
        active?.state = State.REVOKED
        active = null
        cancelRequested = true
    }

    /** Explicit retirement closes host admission; runtime alone owns EOF/cleanup disposition. */
    fun closeForRetirement() = synchronized(lock) {
        closed = true
        active?.state = State.REVOKED
        active = null
    }

    private fun requireOwn(ticket: Ticket) {
        liveCheck(ticket.owner === this, "compiler_live_attempt_binding_mismatch")
    }

    private fun requireActiveLocked(ticket: Ticket) {
        liveCheck(ticket.state != State.REVOKED, "compiler_live_attempt_revoked")
        liveCheck(!closed && ready && active === ticket && ticket.state == State.ACTIVE, "compiler_live_attempt_no_longer_active")
    }

    private fun closeActiveLocked(): Boolean {
        closed = true
        active = null
        if (cancelRequested) return false
        cancelRequested = true
        return true
    }
}
