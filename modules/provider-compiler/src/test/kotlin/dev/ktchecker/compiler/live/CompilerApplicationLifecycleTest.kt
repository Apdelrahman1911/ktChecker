package dev.ktchecker.compiler.live

import dev.ktchecker.contracts.CancellationToken
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** The actual host controller with opaque tickets only: no compiler, session, DTO or fake Accepted. */
class CompilerApplicationLifecycleTest {
    @Test fun expiredOriginalGenerationDeadlineCannotCommitReadiness() {
        val generation = OwnedApplicationDeadline.afterMillis(1)
        val cancellations = AtomicInteger()
        val lifecycle = CompilerApplicationLifecycle(generation) { cancellations.incrementAndGet(); Unit }
        awaitControlExpiry(generation)
        assertEquals("compiler_live_generation_deadline", assertFailsWith<CompilerApplicationProblem> { lifecycle.openAdmission() }.code)
        assertEquals("compiler_live_application_not_ready", assertFailsWith<CompilerApplicationProblem> { lifecycle.checkOpen() }.code)
        assertEquals(0, cancellations.get())
    }

    @Test fun expiredOriginalRequestDeadlineCannotCommitAcceptance() {
        val f = LifecycleControl()
        val original = OwnedApplicationDeadline.afterMillis(1)
        val active = f.lifecycle.newTicket(original)
        f.lifecycle.admit(active)
        awaitControlExpiry(original)
        assertRefusedControlAcceptance(f, active, "compiler_live_request_deadline")
    }

    @Test fun expiredOriginalGenerationDeadlineCannotCommitAcceptance() {
        val generation = expiringControlDeadline()
        val f = LifecycleControl(generation)
        val active = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        awaitControlExpiry(generation)
        assertRefusedControlAcceptance(f, active, "compiler_live_generation_deadline")
    }

    @Test fun cancellationCallbackReturningFalseCannotHideRequestExpiryAtCommit() {
        val f = LifecycleControl()
        val original = expiringControlDeadline()
        val active = f.lifecycle.newTicket(original)
        f.lifecycle.admit(active)
        assertFalse(original.isExpired())
        // Model the last cooperative cancellation callback after an earlier checkpoint, not runtime Accepted.
        val cancellation = CancellationToken { awaitControlExpiry(original); false }
        assertFalse(cancellation.isCancelled()) // The callback returns false only after spending the original budget.
        assertRefusedControlAcceptance(f, active, "compiler_live_request_deadline")
    }

    @Test fun cancellationCallbackReturningFalseCannotHideGenerationExpiryAtCommit() {
        val generation = expiringControlDeadline()
        val f = LifecycleControl(generation)
        val active = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        assertFalse(generation.isExpired())
        val cancellation = CancellationToken { awaitControlExpiry(generation); false }
        assertFalse(cancellation.isCancelled())
        assertRefusedControlAcceptance(f, active, "compiler_live_generation_deadline")
    }

    @Test fun cancellationCallbackReturningFalseCannotHideGenerationExpiryAtReadiness() {
        val generation = expiringControlDeadline()
        val cancellations = AtomicInteger()
        val lifecycle = CompilerApplicationLifecycle(generation) { cancellations.incrementAndGet(); Unit }
        assertFalse(generation.isExpired())
        val cancellation = CancellationToken { awaitControlExpiry(generation); false }
        assertFalse(cancellation.isCancelled())
        assertEquals("compiler_live_generation_deadline", assertFailsWith<CompilerApplicationProblem> { lifecycle.openAdmission() }.code)
        assertEquals("compiler_live_application_not_ready", assertFailsWith<CompilerApplicationProblem> { lifecycle.checkOpen() }.code)
        assertEquals(0, cancellations.get())
    }

    @Test fun presentInterruptWithoutExpiryCannotCommitReadiness() {
        val generation = controlDeadline()
        val cancellations = AtomicInteger()
        val lifecycle = CompilerApplicationLifecycle(generation) { cancellations.incrementAndGet(); Unit }
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            Thread.currentThread().interrupt()
            assertFalse(generation.isExpired())
            assertEquals("compiler_live_interrupted", assertFailsWith<CompilerApplicationProblem> { lifecycle.openAdmission() }.code)
            assertTrue(Thread.currentThread().isInterrupted) // The gate observes, never clears, the caller flag.
            assertEquals("compiler_live_application_not_ready", assertFailsWith<CompilerApplicationProblem> { lifecycle.checkOpen() }.code)
            assertEquals(0, cancellations.get())
        } finally { Thread.interrupted() }
    }

    @Test fun presentInterruptWithoutExpiryCannotCommitAcceptance() {
        val f = LifecycleControl()
        val original = controlDeadline()
        val active = f.lifecycle.newTicket(original)
        f.lifecycle.admit(active)
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            Thread.currentThread().interrupt()
            assertFalse(f.generationDeadline.isExpired())
            assertFalse(original.isExpired())
            assertRefusedControlAcceptance(f, active, "compiler_live_interrupted")
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun cancellationCallbackReturningFalseCannotHidePresentInterruptAtCommit() {
        val f = LifecycleControl()
        val original = controlDeadline()
        val active = f.lifecycle.newTicket(original)
        f.lifecycle.admit(active)
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            val cancellation = CancellationToken { Thread.currentThread().interrupt(); false }
            assertFalse(cancellation.isCancelled())
            assertFalse(f.generationDeadline.isExpired())
            assertFalse(original.isExpired())
            assertRefusedControlAcceptance(f, active, "compiler_live_interrupted")
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun admissionStaysFrozenUntilReadinessAndCannotBeOpenedTwice() {
        val cancellations = AtomicInteger()
        val lifecycle = CompilerApplicationLifecycle(controlDeadline()) { cancellations.incrementAndGet(); Unit }
        val premature = lifecycle.newTicket(controlDeadline())
        assertEquals("compiler_live_application_not_ready", assertFailsWith<CompilerApplicationProblem> { lifecycle.checkOpen() }.code)
        assertFalse(lifecycle.isLive(premature))
        assertEquals("compiler_live_application_not_ready", assertFailsWith<CompilerApplicationProblem> { lifecycle.admit(premature) }.code)
        lifecycle.openAdmission()
        assertFalse(lifecycle.isLive(premature)) // Premature fresh admission was refused and consumed.
        assertEquals("compiler_live_application_already_ready", assertFailsWith<CompilerApplicationProblem> { lifecycle.openAdmission() }.code)
        val active = lifecycle.newTicket(controlDeadline())
        lifecycle.admit(active)
        lifecycle.finishAccepted(active)
        assertEquals(0, cancellations.get())
    }

    @Test fun generationRevocationBeforeReadinessCannotBeUndoneByOpeningAdmission() {
        val cancellations = AtomicInteger()
        val lifecycle = CompilerApplicationLifecycle(controlDeadline()) { cancellations.incrementAndGet(); Unit }
        lifecycle.closeForGenerationRevocation()
        assertEquals("compiler_live_application_closed", assertFailsWith<CompilerApplicationProblem> { lifecycle.openAdmission() }.code)
        assertFalse(lifecycle.isLive(lifecycle.newTicket(controlDeadline())))
        lifecycle.cancel()
        assertEquals(0, cancellations.get()) // The generation callback owns transport cancellation.
    }

    @Test fun generationRevocationClosesTheSameGateBeforeStagedAcceptance() {
        val f = LifecycleControl()
        val active = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        f.lifecycle.closeForGenerationRevocation()
        assertEquals("compiler_live_attempt_revoked", assertFailsWith<CompilerApplicationProblem> { f.lifecycle.finishAccepted(active) }.code)
        assertEquals("compiler_live_application_closed", assertFailsWith<CompilerApplicationProblem> { f.lifecycle.checkOpen() }.code)
        assertFalse(f.lifecycle.isActive(active))
        assertEquals(0, f.cancellations.get()) // State-only closure must not recurse into the permit.
    }

    @Test fun generationRevocationAfterAcceptanceStillClosesALaterActiveTicket() {
        val f = LifecycleControl()
        val first = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(first)
        f.lifecycle.finishAccepted(first)
        val second = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(second)
        f.lifecycle.closeForGenerationRevocation()
        assertEquals("compiler_live_attempt_revoked", assertFailsWith<CompilerApplicationProblem> { f.lifecycle.finishAccepted(second) }.code)
        assertFalse(f.lifecycle.isActive(second))
        assertEquals(0, f.cancellations.get())
    }

    @Test fun generationRevocationRacingAcceptanceHasOneHostGateWinner() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(32) {
                val f = LifecycleControl()
                val active = f.lifecycle.newTicket(controlDeadline())
                f.lifecycle.admit(active)
                val start = CountDownLatch(1)
                val accepted = pool.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    try { f.lifecycle.finishAccepted(active); true }
                    catch (failure: CompilerApplicationProblem) {
                        assertEquals("compiler_live_attempt_revoked", failure.code)
                        false
                    }
                }
                val revoked = pool.submit<Unit> {
                    check(start.await(5, TimeUnit.SECONDS))
                    f.lifecycle.closeForGenerationRevocation()
                }
                start.countDown()
                val won = accepted.get(5, TimeUnit.SECONDS)
                revoked.get(5, TimeUnit.SECONDS)
                assertEquals(if (won) "compiler_live_attempt_permit_used" else "compiler_live_attempt_revoked",
                    assertFailsWith<CompilerApplicationProblem> { f.lifecycle.checkTicketLive(active) }.code)
                assertFailsWith<CompilerApplicationProblem> { f.lifecycle.checkOpen() }
                assertFailsWith<CompilerApplicationProblem> { f.lifecycle.admit(f.lifecycle.newTicket(controlDeadline())) }
                assertEquals(0, f.cancellations.get())
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun neverAdmittedRevokeInvalidatesOnlyItsOwnTicketWhileAnotherIsActive() {
        val f = LifecycleControl()
        val unused = f.lifecycle.newTicket(controlDeadline())
        val active = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        f.lifecycle.revoke(unused)
        assertFalse(f.lifecycle.isLive(unused))
        assertTrue(f.lifecycle.isActive(active))
        assertEquals("compiler_live_attempt_revoked", assertFailsWith<CompilerApplicationProblem> { f.lifecycle.admit(unused) }.code)
        assertEquals(0, f.cancellations.get())
        f.lifecycle.finishAccepted(active)
    }

    @Test fun completedRevokeCannotCancelOrInvalidateTheNextActiveTicket() {
        val f = LifecycleControl()
        val completed = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(completed)
        f.lifecycle.finishAccepted(completed)
        val active = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        f.lifecycle.revoke(completed)
        assertTrue(f.lifecycle.isActive(active))
        assertEquals(0, f.cancellations.get())
        f.lifecycle.finishAccepted(active)
    }

    @Test fun duplicateOfActiveTicketDoesNotTerminalizeTheOriginalAdmission() {
        val f = LifecycleControl()
        val active = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        assertEquals("compiler_live_attempt_permit_used", assertFailsWith<CompilerApplicationProblem> { f.lifecycle.admit(active) }.code)
        assertTrue(f.lifecycle.isActive(active))
        assertEquals(0, f.cancellations.get())
        f.lifecycle.finishAccepted(active)
    }

    @Test fun replayOfCompletedTicketCannotCancelCurrentOwner() {
        val f = LifecycleControl()
        val completed = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(completed)
        f.lifecycle.finishAccepted(completed)
        val active = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        assertEquals("compiler_live_attempt_permit_used", assertFailsWith<CompilerApplicationProblem> { f.lifecycle.admit(completed) }.code)
        assertTrue(f.lifecycle.isActive(active))
        assertEquals(0, f.cancellations.get())
    }

    @Test fun concurrentFreshRejectionConsumesOnlyTheRejectedTicketAndCannotRetry() {
        val f = LifecycleControl()
        val active = f.lifecycle.newTicket(controlDeadline())
        val rejected = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        assertEquals("compiler_live_concurrent_attempt", assertFailsWith<CompilerApplicationProblem> { f.lifecycle.admit(rejected) }.code)
        assertTrue(f.lifecycle.isActive(active))
        assertFalse(f.lifecycle.isLive(rejected))
        f.lifecycle.revoke(rejected)
        assertTrue(f.lifecycle.isActive(active))
        f.lifecycle.finishAccepted(active)
        assertFailsWith<CompilerApplicationProblem> { f.lifecycle.admit(rejected) }
        f.lifecycle.admit(f.lifecycle.newTicket(controlDeadline()))
        assertEquals(0, f.cancellations.get())
    }

    @Test fun wrongControllerDoesNotConsumeAForeignTicketOrTouchEitherActiveScope() {
        val owner = LifecycleControl()
        val other = LifecycleControl()
        val foreign = owner.lifecycle.newTicket(controlDeadline())
        val otherActive = other.lifecycle.newTicket(controlDeadline())
        other.lifecycle.admit(otherActive)
        assertEquals("compiler_live_attempt_binding_mismatch", assertFailsWith<CompilerApplicationProblem> {
            other.lifecycle.admit(foreign)
        }.code)
        assertTrue(owner.lifecycle.isLive(foreign))
        assertTrue(other.lifecycle.isActive(otherActive))
        owner.lifecycle.admit(foreign) // The mistaken call did not steal the true owner's grant.
        assertEquals(0, owner.cancellations.get())
        assertEquals(0, other.cancellations.get())
    }

    @Test fun staleFailureAfterTerminalDetachmentCannotCancelTheNextTicket() {
        val f = LifecycleControl()
        val completed = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(completed)
        f.lifecycle.finishAccepted(completed)
        val active = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        f.lifecycle.failActive(completed)
        assertTrue(f.lifecycle.isActive(active))
        assertEquals(0, f.cancellations.get())
    }

    @Test fun revokeWinningBeforeAcceptanceSuppressesTerminalAcceptanceAndClosesAdmission() {
        val f = LifecycleControl()
        val active = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        f.lifecycle.revoke(active)
        assertEquals("compiler_live_attempt_revoked", assertFailsWith<CompilerApplicationProblem> {
            f.lifecycle.finishAccepted(active)
        }.code)
        assertFalse(f.lifecycle.isActive(active))
        assertFailsWith<CompilerApplicationProblem> { f.lifecycle.admit(f.lifecycle.newTicket(controlDeadline())) }
        f.lifecycle.failActive(active)
        f.lifecycle.revoke(active)
        assertEquals(1, f.cancellations.get())
    }

    @Test fun acceptanceWinningBeforeRevokeDetachesWhileIdleAndAllowsAFreshNextTicket() {
        val f = LifecycleControl()
        val completed = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(completed)
        f.lifecycle.finishAccepted(completed)
        f.lifecycle.revoke(completed)
        f.lifecycle.checkOpen()
        val next = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(next)
        assertTrue(f.lifecycle.isActive(next))
        assertEquals(0, f.cancellations.get())
    }

    @Test fun revokeRacingAcceptanceHasOneLinearizedWinnerAndNoCollateralNextCancellation() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(32) {
                val f = LifecycleControl()
                val active = f.lifecycle.newTicket(controlDeadline())
                f.lifecycle.admit(active)
                val start = CountDownLatch(1)
                val accepted = pool.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    try { f.lifecycle.finishAccepted(active); true }
                    catch (failure: CompilerApplicationProblem) {
                        assertEquals("compiler_live_attempt_revoked", failure.code)
                        false
                    }
                }
                val revoked = pool.submit<Unit> {
                    check(start.await(5, TimeUnit.SECONDS))
                    f.lifecycle.revoke(active)
                }
                start.countDown()
                val won = accepted.get(5, TimeUnit.SECONDS)
                revoked.get(5, TimeUnit.SECONDS)
                if (won) {
                    assertEquals(0, f.cancellations.get())
                    val next = f.lifecycle.newTicket(controlDeadline())
                    f.lifecycle.admit(next)
                    f.lifecycle.revoke(active)
                    assertTrue(f.lifecycle.isActive(next))
                } else {
                    assertEquals(1, f.cancellations.get())
                    assertFailsWith<CompilerApplicationProblem> { f.lifecycle.checkOpen() }
                }
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun activeFailureClosesAdmissionBeforeItInvokesCancellation() {
        lateinit var lifecycle: CompilerApplicationLifecycle
        lateinit var active: CompilerApplicationLifecycle.Ticket
        val cancellations = AtomicInteger()
        lifecycle = CompilerApplicationLifecycle(controlDeadline()) {
            assertFalse(lifecycle.isLive(active))
            assertFalse(lifecycle.isActive(active))
            assertFailsWith<CompilerApplicationProblem> { lifecycle.checkOpen() }
            cancellations.incrementAndGet()
        }
        lifecycle.openAdmission()
        active = lifecycle.newTicket(controlDeadline())
        lifecycle.admit(active)
        lifecycle.failActive(active)
        lifecycle.failActive(active)
        assertEquals(1, cancellations.get())
        assertFailsWith<CompilerApplicationProblem> { lifecycle.finishAccepted(active) }
    }

    @Test fun cancellationCallbackRunsOutsideControllerLock() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            lateinit var lifecycle: CompilerApplicationLifecycle
            lateinit var active: CompilerApplicationLifecycle.Ticket
            val callbacks = AtomicInteger()
            lifecycle = CompilerApplicationLifecycle(controlDeadline()) {
                // A different thread must acquire the controller lock while this callback is running.
                val observed = pool.submit<Boolean> { !lifecycle.isLive(active) && !lifecycle.isActive(active) }
                assertTrue(observed.get(5, TimeUnit.SECONDS))
                callbacks.incrementAndGet()
            }
            lifecycle.openAdmission()
            active = lifecycle.newTicket(controlDeadline())
            lifecycle.admit(active)
            lifecycle.revoke(active)
            assertEquals(1, callbacks.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun explicitGenerationCancellationRemainsIdempotentWhileIdleOrActive() {
        val idle = LifecycleControl()
        idle.lifecycle.cancel()
        idle.lifecycle.cancel()
        assertEquals(1, idle.cancellations.get())
        assertFailsWith<CompilerApplicationProblem> { idle.lifecycle.checkOpen() }
        val active = LifecycleControl()
        val ticket = active.lifecycle.newTicket(controlDeadline())
        active.lifecycle.admit(ticket)
        active.lifecycle.cancel()
        active.lifecycle.revoke(ticket)
        assertEquals(1, active.cancellations.get())
        assertFalse(active.lifecycle.isActive(ticket))
    }

    @Test fun explicitRetirementDetachesWithoutInventingACancellationOrCleanupReceipt() {
        val f = LifecycleControl()
        val active = f.lifecycle.newTicket(controlDeadline())
        f.lifecycle.admit(active)
        f.lifecycle.closeForRetirement()
        f.lifecycle.revoke(active)
        assertEquals(0, f.cancellations.get())
        assertFailsWith<CompilerApplicationProblem> { f.lifecycle.finishAccepted(active) }
        assertFailsWith<CompilerApplicationProblem> { f.lifecycle.checkOpen() }
    }
}

private class LifecycleControl(val generationDeadline: OwnedApplicationDeadline = controlDeadline()) {
    val cancellations = AtomicInteger()
    val lifecycle = CompilerApplicationLifecycle(generationDeadline) { cancellations.incrementAndGet(); Unit }.also { it.openAdmission() }
}

// Test ingress clocks only; production never creates a clock or renews a budget in the controller.
private fun controlDeadline(): OwnedApplicationDeadline = OwnedApplicationDeadline.afterMillis(120_000)
private fun expiringControlDeadline(): OwnedApplicationDeadline = OwnedApplicationDeadline.afterMillis(500)

private fun awaitControlExpiry(deadline: OwnedApplicationDeadline) {
    val waitStarted = System.nanoTime() // Bounds test waiting only; not an application deadline replacement.
    while (!deadline.isExpired()) {
        check(System.nanoTime() - waitStarted < TimeUnit.SECONDS.toNanos(5)) { "Synthetic control deadline did not expire" }
        Thread.sleep(1)
    }
}

private fun assertRefusedControlAcceptance(
    control: LifecycleControl,
    active: CompilerApplicationLifecycle.Ticket,
    code: String,
) {
    assertEquals(code, assertFailsWith<CompilerApplicationProblem> { control.lifecycle.finishAccepted(active) }.code)
    assertTrue(control.lifecycle.isActive(active)) // Rejection did not publish/detach; admitted cleanup retains exact ownership.
    assertEquals(0, control.cancellations.get()) // Budget/interrupt reads invoke no callback under the gate.
    control.lifecycle.failActive(active)
    assertFalse(control.lifecycle.isActive(active))
    assertEquals(1, control.cancellations.get())
}
