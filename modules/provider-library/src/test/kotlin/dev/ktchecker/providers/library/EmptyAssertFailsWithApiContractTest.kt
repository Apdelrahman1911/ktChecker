package dev.ktchecker.providers.library

import kotlin.test.Test
import kotlin.test.assertFailsWith as actualAssertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * REAL linked kotlin-test API semantics, not forged facts. These calibration tests are themselves
 * legitimate passing tests and must NOT be enrolled as direct ordinary-empty positives. They do
 * not prove FIR range shape, artifact-owner receipts or end-to-end provider integration; the
 * separate resource fixtures are inputs for that prospective exact compiler+plugin entrypoint.
 */
class EmptyAssertFailsWithApiContractTest {
    @Test fun anEmptyBlockReallyFailsEvenWhenThrowableIsExpected() {
        actualAssertFailsWith<AssertionError> { actualAssertFailsWith<IllegalArgumentException> { } }
        actualAssertFailsWith<AssertionError> { actualAssertFailsWith<Throwable> { /* still empty */ } }
    }

    @Test fun throwingTheExpectedSubtypeReallyPassesAndReturnsThatException() {
        val expected = IllegalArgumentException("fixture")
        val actual = actualAssertFailsWith<RuntimeException> { throw expected }
        assertSame(expected, actual)
    }

    @Test fun aPotentiallyThrowingOperationMustNotBeClassifiedAsEmpty() {
        fun operation(): Nothing = throw IllegalArgumentException("fixture")
        actualAssertFailsWith<IllegalArgumentException> { operation() }
    }

    @Test fun caughtAndNestedCalibrationFailuresAreLegitimatePassingContexts() {
        var caught = false
        try { actualAssertFailsWith<IllegalArgumentException> { } }
        catch (_: AssertionError) { caught = true }
        assertTrue(caught)
        actualAssertFailsWith<AssertionError> { actualAssertFailsWith<IllegalArgumentException> { } }
    }

    @Test fun anyResultAndKClassFormsUseTheRealApiButAreOutsideTheInitialDetectorBodyGrammar() {
        val action: () -> Any? = { 7 }
        actualAssertFailsWith<AssertionError> { actualAssertFailsWith<IllegalArgumentException>(block = action) }
        actualAssertFailsWith<AssertionError> { actualAssertFailsWith(IllegalArgumentException::class) { } }
        actualAssertFailsWith<AssertionError> { actualAssertFailsWith<IllegalArgumentException> { Unit } }
    }

    @Test fun aSourceHomonymCanLegitimatelyHaveDifferentSemantics() {
        assertTrue(assertFailsWith<IllegalArgumentException> { })
    }

    private fun <T : Throwable> assertFailsWith(block: () -> Any?): Boolean { block(); return true }
}
