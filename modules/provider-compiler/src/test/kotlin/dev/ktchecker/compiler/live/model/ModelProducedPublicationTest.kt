package dev.ktchecker.compiler.live.model

import dev.ktchecker.compiler.live.CompilerApplicationLifecycle
import dev.ktchecker.compiler.live.CompilerApplicationProblem
import dev.ktchecker.providers.runtime.OwnedApplicationDeadline
import kotlin.test.*

/** Actual production publication-order helper + real lifecycle tickets, with OPAQUE DATA only.
 * No compiler files, installation digests, runtime Accepted objects or successful-output tokens.
 * These cannot prove a real compilation; RealProducedJvmOwnerMain separately covers that route.
 */
class ModelProducedPublicationTest {
    @Test fun absentCandidateDoesNotMintAnythingEvenWhenTheRealLifecycleGateAccepts() {
        val control = Control(); val ticket = control.active()
        var publications = 0
        finishAcceptedModelOutput<Any>(null, { control.lifecycle.finishAccepted(ticket) }) { publications++ }
        assertEquals(0, publications)
        assertFalse(control.lifecycle.isLive(ticket))
        control.lifecycle.checkOpen()
    }

    @Test fun neverAdmittedAndFailedTicketsCannotPublishAPreconstructedOpaqueCandidate() {
        for (failed in listOf(false, true)) {
            val control = Control(); val ticket = control.lifecycle.newTicket(deadline())
            if (failed) { control.lifecycle.admit(ticket); control.lifecycle.failActive(ticket) }
            val candidate = Any(); var published: Any? = null
            assertFailsWith<CompilerApplicationProblem> {
                finishAcceptedModelOutput(candidate, { control.lifecycle.finishAccepted(ticket) }) { published = it }
            }
            assertNull(published)
        }
    }

    @Test fun originalRequestExpiryAfterCandidateConstructionStillRefusesPublication() {
        // Leave room for class initialization before the intentional bounded expiry wait.
        val control = Control(); val original = OwnedApplicationDeadline.afterMillis(500)
        val ticket = control.active(original); val candidate = Any(); var published: Any? = null
        awaitExpiry(original)
        val rejected = assertFailsWith<CompilerApplicationProblem> {
            finishAcceptedModelOutput(candidate, { control.lifecycle.finishAccepted(ticket) }) { published = it }
        }
        assertEquals("compiler_live_request_deadline", rejected.code)
        assertNull(published)
        assertTrue(control.lifecycle.isActive(ticket))
    }

    @Test fun originalGenerationExpiryAfterReadinessStillRefusesPublication() {
        val original = OwnedApplicationDeadline.afterMillis(500)
        val control = Control(original); val ticket = control.active(); val candidate = Any(); var published: Any? = null
        awaitExpiry(original)
        val rejected = assertFailsWith<CompilerApplicationProblem> {
            finishAcceptedModelOutput(candidate, { control.lifecycle.finishAccepted(ticket) }) { published = it }
        }
        assertEquals("compiler_live_generation_deadline", rejected.code)
        assertNull(published)
    }

    @Test fun presentInterruptAndGenerationRevocationCannotPublishStagedOpaqueData() {
        val interrupted = Control(); val ticket = interrupted.active(); var published: Any? = null
        assertFalse(Thread.currentThread().isInterrupted)
        try {
            Thread.currentThread().interrupt()
            assertEquals("compiler_live_interrupted", assertFailsWith<CompilerApplicationProblem> {
                finishAcceptedModelOutput(Any(), { interrupted.lifecycle.finishAccepted(ticket) }) { published = it }
            }.code)
            assertNull(published)
        } finally { Thread.interrupted() }
        val revoked = Control(); val other = revoked.active()
        revoked.lifecycle.closeForGenerationRevocation()
        assertFailsWith<CompilerApplicationProblem> {
            finishAcceptedModelOutput(Any(), { revoked.lifecycle.finishAccepted(other) }) { published = it }
        }
        assertNull(published)
    }

    @Test fun acceptedTicketCannotRepublishOrRevokeTheNextAttemptWhenItsOwnerClosesIt() {
        val control = Control(); val first = control.active(); val candidate = Any(); val published = mutableListOf<Any>()
        finishAcceptedModelOutput(candidate, { control.lifecycle.finishAccepted(first) }) { published += it }
        assertEquals(listOf(candidate), published)
        val second = control.active()
        assertFailsWith<CompilerApplicationProblem> {
            finishAcceptedModelOutput(Any(), { control.lifecycle.finishAccepted(first) }) { published += it }
        }
        control.lifecycle.revoke(first)
        assertEquals(listOf(candidate), published)
        assertTrue(control.lifecycle.isActive(second)); assertEquals(0, control.cancellations)
    }

    private class Control(original: OwnedApplicationDeadline = deadline()) {
        var cancellations = 0
        val lifecycle = CompilerApplicationLifecycle(original) { cancellations++ }.also { it.openAdmission() }
        fun active(original: OwnedApplicationDeadline = deadline()) = lifecycle.newTicket(original).also(lifecycle::admit)
    }
    companion object {
        private fun deadline() = OwnedApplicationDeadline.afterMillis(5_000)
        private fun awaitExpiry(original: OwnedApplicationDeadline) {
            val started = System.nanoTime()
            while (!original.isExpired()) {
                check(System.nanoTime() - started < 2_000_000_000L) { "bounded original-clock control wait" }
                Thread.sleep(1)
            }
        }
    }
}
