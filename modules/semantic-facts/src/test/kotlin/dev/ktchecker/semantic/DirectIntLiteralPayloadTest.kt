package dev.ktchecker.semantic

import dev.ktchecker.semantic.adapters.kotlin2421.directIntLiteralSignedValue
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Payload decoder controls only; these values are not authenticated compiler observations. */
class DirectIntLiteralPayloadTest {
    @Test fun exactBoxedIntsNeedNoConversion() {
        for (value in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            assertEquals(value, directIntLiteralSignedValue(value))
        }
    }

    @Test fun exactLongPayloadWithinBothSignedBoundsDecodesWithoutWrapping() {
        for (value in listOf(Int.MIN_VALUE.toLong(), -1L, 0L, 1L, Int.MAX_VALUE.toLong())) {
            assertEquals(value.toInt(), directIntLiteralSignedValue(value))
        }
    }

    @Test fun adjacentOverflowAndLongExtremesRemainAbsent() {
        for (value in listOf(Long.MIN_VALUE, Int.MIN_VALUE.toLong() - 1L,
            Int.MAX_VALUE.toLong() + 1L, Long.MAX_VALUE)) assertNull(directIntLiteralSignedValue(value))
    }

    @Test fun unsignedPayloadsAreNeverReinterpretedAsSigned() {
        for (value in listOf<Any>(0u, 1u, Int.MAX_VALUE.toUInt(), UInt.MAX_VALUE,
            0uL, 1uL, Int.MAX_VALUE.toULong(), ULong.MAX_VALUE)) assertNull(directIntLiteralSignedValue(value))
    }

    @Test fun otherBoxedNumbersAndScalarLookalikesAreNotCoerced() {
        for (value in listOf(null, 0.toByte(), 1.toShort(), 1.0f, 1.0, Float.NaN, Double.POSITIVE_INFINITY,
            BigInteger.ONE, BigDecimal.ONE, "1", '1', true)) assertNull(directIntLiteralSignedValue(value))
    }

    @Test fun customNumberConversionsAreNeverInvoked() {
        val value = object : Number() {
            override fun toByte(): Byte = error("No numeric coercion is permitted")
            override fun toShort(): Short = error("No numeric coercion is permitted")
            override fun toInt(): Int = error("No numeric coercion is permitted")
            override fun toLong(): Long = error("No numeric coercion is permitted")
            override fun toFloat(): Float = error("No numeric coercion is permitted")
            override fun toDouble(): Double = error("No numeric coercion is permitted")
        }
        assertNull(directIntLiteralSignedValue(value))
    }
}
