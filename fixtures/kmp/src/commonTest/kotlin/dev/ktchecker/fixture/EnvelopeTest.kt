package dev.ktchecker.fixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnvelopeTest {
    @Test fun nullLabelIsRejected() {
        assertFailsWith<IllegalArgumentException> { envelope(null, 0) }
    }

    @Test fun blankLabelIsRejected() {
        assertFailsWith<IllegalArgumentException> { envelope(" \t\n", 0) }
    }

    @Test fun unicodeLabelIsTrimmedWithoutLosingContent() {
        assertEquals(Envelope("Δ fixture", 0), envelope("  Δ fixture  ", 0))
    }

    @Test fun negativeRetriesAreRejected() {
        assertFailsWith<IllegalArgumentException> { envelope("fixture", -1) }
    }

    @Test fun largestRetryCountIsPreserved() {
        assertEquals(Int.MAX_VALUE, envelope("fixture", Int.MAX_VALUE).retries)
    }

    @Test fun overflowingIncrementIsRejected() {
        assertFailsWith<IllegalArgumentException> { checkedIncrement(Int.MAX_VALUE) }
    }

    @Test fun incrementBoundaryIsExact() {
        assertEquals(Int.MAX_VALUE, checkedIncrement(Int.MAX_VALUE - 1))
        assertEquals(Int.MIN_VALUE + 1, checkedIncrement(Int.MIN_VALUE))
    }
}
