package dev.ktchecker.kernel

import kotlin.test.*
import org.junit.jupiter.api.Test

/** Arithmetic/opaque-object controls only; no test clock mints a live model or admission grant. */
class ProducedPlanClockTest {
    @Test fun retainsTheExactOpaqueOriginalClockAndOriginalStartWithoutARefresh() {
        val ownerClock = Any()
        val clock = ProducedPlanClock(ownerClock, "original-clock", 1_234L, 300_000L)
        assertSame(ownerClock, clock.ownerClock)
        assertEquals("original-clock", clock.identity)
        assertEquals(1_234L, clock.startedAtNanos)
        assertEquals(300_000L, clock.durationMillis)
        assertEquals(300_000L, clock.remainingMillisAt(1_234L))
        assertEquals(299_999L, clock.remainingMillisAt(1_235L))
    }

    @Test fun finalFractionalMillisecondExpiresExactlyLikeTheOriginalOwnedBudget() {
        for (duration in listOf(1L, 2L, 300_000L, 3_600_000L)) {
            val start = 50L
            val clock = ProducedPlanClock(Any(), "clock-$duration", start, duration)
            val lastWholeMillisecond = start + (duration - 1) * 1_000_000
            assertFalse(clock.isExpiredAt(start))
            assertEquals(1L, clock.remainingMillisAt(lastWholeMillisecond))
            assertFalse(clock.isExpiredAt(lastWholeMillisecond))
            assertEquals(0L, clock.remainingMillisAt(lastWholeMillisecond + 1))
            assertTrue(clock.isExpiredAt(lastWholeMillisecond + 1))
            assertTrue(clock.isExpiredAt(start + duration * 1_000_000))
        }
    }

    @Test fun monotonicNanoTimeWrapDoesNotRenewOrPrematurelyExpireTheSameBudget() {
        val start = Long.MAX_VALUE - 50
        val clock = ProducedPlanClock(Any(), "wrap-clock", start, 10)
        assertFalse(clock.isExpiredAt(Long.MIN_VALUE + 49)) // Exactly 100ns later across the wrap.
        assertEquals(9L, clock.remainingMillisAt(Long.MIN_VALUE + 49))
        val edge = start + 9_000_000L
        assertEquals(1L, clock.remainingMillisAt(edge))
        assertFalse(clock.isExpiredAt(edge))
        assertTrue(clock.isExpiredAt(edge + 1))
    }

    @Test fun negativeElapsedTimeAndElapsedLongMaxAreUnavailableNotFreshAuthority() {
        val clock = ProducedPlanClock(Any(), "backwards-clock", 1_000L, 3_600_000L)
        assertTrue(clock.isExpiredAt(999L))
        assertEquals(0L, clock.remainingMillisAt(999L))
        val old = ProducedPlanClock(Any(), "old-clock", 0L, 3_600_000L)
        assertTrue(old.isExpiredAt(Long.MAX_VALUE))
        assertEquals(0L, old.remainingMillisAt(Long.MAX_VALUE))
    }

    @Test fun blankIdentityAndOutOfProfileDurationsAreRejected() {
        for (duration in listOf(Long.MIN_VALUE, -1L, 0L, 3_600_001L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ProducedPlanClock(Any(), "invalid-duration", 0L, duration) }
        }
        assertFailsWith<IllegalArgumentException> { ProducedPlanClock(Any(), " ", 0L, 1L) }
    }
}
