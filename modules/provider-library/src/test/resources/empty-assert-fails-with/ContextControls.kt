package fixture.emptyassert.controls

import kotlin.test.Test
import kotlin.test.assertFailsWith as realAssertFailsWith
import kotlin.test.assertTrue

/** Real compiler inputs; no claim that these sources alone executed or established provenance. */
class ContextControls {
    @Test fun expectedThrow() { realAssertFailsWith<IllegalArgumentException> { throw IllegalArgumentException() } }
    @Test fun unknownEffect() { realAssertFailsWith<IllegalArgumentException> { operation() } }
    @Test fun nestedCalibration() { realAssertFailsWith<AssertionError> { realAssertFailsWith<IllegalArgumentException> { } } }
    @Test fun caughtCalibration() {
        var caught = false
        try { realAssertFailsWith<IllegalArgumentException> { } } catch (_: AssertionError) { caught = true }
        assertTrue(caught)
    }
    @Test fun sourceHomonym() { assertFailsWith<IllegalArgumentException> { } }
    // Still defective if required to pass, but explicitly UNKNOWN in this initial empty-only slice.
    @Test fun unitOnlyUnsupported() { realAssertFailsWith<IllegalArgumentException> { Unit } }
    @Test fun kClassUnsupported() { realAssertFailsWith(IllegalArgumentException::class) { } }
    // Not a discovered test entry; absent exact owner enrollment must stay UNKNOWN.
    fun notAnEnrolledTest() { realAssertFailsWith<IllegalArgumentException> { } }
    // Selected by an outer fixture/calibration driver, not an ordinary required-to-pass entry.
    fun approvedCalibration() { realAssertFailsWith<IllegalArgumentException> { } }
}

private fun operation(): Nothing = throw IllegalArgumentException("fixture")
private fun <T : Throwable> assertFailsWith(block: () -> Any?) { block() }
