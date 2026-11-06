package dev.ktchecker.cli

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import kotlin.test.*

/** Clock interval DATA + actual local challenge ledger only, never export/execution authority. */
class OwnerModelTransportClocksTest {
    @Test fun denialMathCanOnlyNarrowTheSameCapturedStartAcrossEveryFixedWidth() {
        for (width in listOf(120_000L, 300_000L, 900_000L)) {
            val first = ownerModelTightenedDenialNanos(width * 1_000_000, width - 10, width)
            assertEquals((width - 10) * 1_000_000, first)
            assertEquals(first, ownerModelTightenedDenialNanos(first, width, width))
            assertEquals(0L, ownerModelTightenedDenialNanos(first, 0, width))
            assertEquals(0L, ownerModelTightenedDenialNanos(0, width, width))
        }
    }

    @Test fun captureWriteAndReplyDelaysAreSubtractedNotTurnedIntoANewReceiptOrigin() {
        // SYNTHETIC elapsed DATA, not a fake JVM/Node epoch or a nominal owner clock.
        val captured = 1_000_000_000_000L
        val wrote = captured + 5_000_000_000L
        val received = wrote + 5_000_000_000L
        val allowance = ownerModelTightenedDenialNanos(120_000_000_000L, 100_000, 120_000)
        assertTrue(ownerModelBeforeCapturedDenial(received - captured, allowance))
        assertTrue(ownerModelBeforeCapturedDenial(allowance - 1, allowance))
        assertFalse(ownerModelBeforeCapturedDenial(allowance, allowance))
        // receive+remaining would improperly accept these final10s; SAME start rejects them.
        assertFalse(ownerModelBeforeCapturedDenial(received + allowance - captured - 1, allowance))
        val lateAllowance = ownerModelTightenedDenialNanos(120_000_000_000L, 2_000, 120_000)
        assertFalse(ownerModelBeforeCapturedDenial(received - captured, lateAllowance))
        assertFalse(ownerModelBeforeCapturedDenial(-1, allowance))
    }

    @Test fun relativeIntervalsRemainSafeAcrossSignedNanoTimeWrapWithoutAbsoluteAddition() {
        val start = Long.MAX_VALUE - 500
        val after = Long.MIN_VALUE + 499
        fun elapsedBetween(afterValue: Long, startValue: Long): Long = afterValue - startValue
        val elapsed = elapsedBetween(after, start) // JVM two's-complement subtraction, elapsed1000ns.
        assertEquals(1_000L, elapsed)
        assertTrue(ownerModelBeforeCapturedDenial(elapsed, 1_001))
        assertFalse(ownerModelBeforeCapturedDenial(elapsed, 1_000))
        assertFalse(ownerModelBeforeCapturedDenial(elapsed, 0))
    }

    @Test fun scalarBoundsDoNotWidenOrInventAPhaseWidth() {
        for (width in listOf(0L, 119_999, 120_001, 300_001, Long.MAX_VALUE)) {
            assertFailsWith<CliProblem> { ownerModelTightenedDenialNanos(0, 0, width) }
        }
        assertFailsWith<CliProblem> { ownerModelTightenedDenialNanos(-1, 0, 120_000) }
        assertFailsWith<CliProblem> { ownerModelTightenedDenialNanos(120_000_000_001, 1, 120_000) }
        assertFailsWith<CliProblem> { ownerModelTightenedDenialNanos(120_000_000_000, -1, 120_000) }
        assertFailsWith<CliProblem> { ownerModelTightenedDenialNanos(120_000_000_000, 120_001, 120_000) }
    }

    @Test fun aFenceIsUnusableUntilOneExactMatchedReplyAndDuplicateReplyPoisonsIt() {
        val (state, request) = transportTestActive()
        val phase = state.challenge(request, OwnerModelTransportClockKind.OVERALL, 0, "b".repeat(64))
        assertFalse(phase.accepted)
        assertEquals("owner_transport_clock_unanswered", assertFailsWith<CliProblem> { phase.checkpoint() }.code)
        state.receive(transportTestReply(phase)); state.awaitClock(phase)
        assertTrue(phase.accepted); phase.checkpoint()
        assertFailsWith<CliProblem> { state.receive(transportTestReply(phase)) }
        assertTrue(state.poisoned); assertFailsWith<CliProblem> { phase.checkpoint() }
    }

    @Test fun wrongSessionRequestNonceKindOrdinalAndNoncanonicalOrZeroRepliesAreStickyDenial() {
        val changes = listOf("session" to JsonPrimitive("c".repeat(64)), "requestId" to JsonPrimitive(2),
            "challenge" to JsonPrimitive("d".repeat(64)), "phase" to JsonPrimitive("review"), "ordinal" to JsonPrimitive(1),
            "remainingMillis" to JsonPrimitive(0), "remainingMillis" to JsonPrimitive(900_001),
            "remainingMillis" to JsonPrimitive(-1), "remainingMillis" to JsonPrimitive("900000"),
            "remainingMillis" to JsonPrimitive(900_000.0), "extra" to JsonNull)
        for ((key, value) in changes) {
            val (state, request) = transportTestActive()
            val phase = state.challenge(request, OwnerModelTransportClockKind.OVERALL, 0, "b".repeat(64))
            assertFailsWith<CliProblem> { state.receive(transportTestChange(transportTestReply(phase), key, value)) }
            assertTrue(state.poisoned); assertFalse(phase.accepted)
            assertFailsWith<CliProblem> { state.receive(transportTestReply(phase)) }
            assertFailsWith<CliProblem> { phase.checkpoint() }
        }
    }

    @Test fun aLateNarrowingReplyCannotStartItsRemainingMillisOnReceipt() {
        val (state, request) = transportTestActive()
        val phase = state.challenge(request, OwnerModelTransportClockKind.EXPORT, 1, "b".repeat(64))
        Thread.sleep(20) // Actual local delay, no virtual clock injected into the nominal fence.
        assertFailsWith<CliProblem> { state.receive(transportTestReply(phase, 1)) }
        assertTrue(state.poisoned); assertFalse(phase.accepted)
    }

    @Test fun nonceAndPhaseOrdinalCannotBeReusedEvenAfterTheirOneReplyWasConsumed() {
        for (sameNonce in listOf(false, true)) {
            val (state, request) = transportTestActive()
            val phase = state.challenge(request, OwnerModelTransportClockKind.EXPORT, 1, "b".repeat(64))
            state.receive(transportTestReply(phase))
            assertFailsWith<CliProblem> {
                state.challenge(request, OwnerModelTransportClockKind.EXPORT, if (sameNonce) 2 else 1,
                    if (sameNonce) "b".repeat(64) else "c".repeat(64))
            }
        }
        for (nonce in listOf(transportTestSession, "short", "B".repeat(64))) {
            val (state, request) = transportTestActive()
            assertFailsWith<CliProblem> { state.challenge(request, OwnerModelTransportClockKind.EXPORT, 1, nonce) }
        }
    }

    @Test fun replyWithoutAPendingChallengeCannotBecomeClockAuthority() {
        val (source, request) = transportTestActive()
        val phase = source.challenge(request, OwnerModelTransportClockKind.EXPORT, 1, "b".repeat(64))
        val other = transportTestState()
        assertFailsWith<CliProblem> { other.receive(transportTestReply(phase)) }
        assertTrue(other.poisoned); assertFalse(phase.accepted)
    }

    @Test fun startupCapRemainsUntilOpenResponseEvenAfterUnansweredOverallObjectExists() {
        // Inert transport's shortened *additional denial* cap, never passed to an owner/model.
        val (state, request) = transportTestActive(startupMillis = 1_000)
        state.challenge(request, OwnerModelTransportClockKind.OVERALL, 0, "b".repeat(64))
        Thread.sleep(1_050)
        assertEquals("owner_model_original_deadline_expired", assertFailsWith<CliProblem> { state.observeDeadlines() }.code)
    }

    @Test fun completedOperationNoLongerAppliesItsOldReviewFenceToLaterAuditTraffic() {
        val (state, request) = transportTestActive()
        val phase = state.challenge(request, OwnerModelTransportClockKind.REVIEW, 1, "b".repeat(64))
        state.receive(transportTestReply(phase))
        request.returned.set(Any())
        ownerModelTransportRespond(state, request, ByteArrayOutputStream(), JsonObject(emptyMap()))
        state.receive(transportTestRequest(2, "review.events"))
        val next = state.take()
        assertTrue(next.fences.isEmpty()); assertTrue(phase.accepted)
        // Production attaches overall only here; original TS active300 is a separate wait fence.
        assertEquals(2L, next.id)
    }
}
