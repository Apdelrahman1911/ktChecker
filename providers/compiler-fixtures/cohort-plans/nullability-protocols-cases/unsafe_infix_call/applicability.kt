package fixtures.nullability_protocols.unsafe_infix_call

class NullableInfixValue(val value: Int)

infix fun NullableInfixValue?.addOrZero(delta: Int): Int = (this?.value ?: 0) + delta

fun exercise(receiver: NullableInfixValue?): Int = receiver addOrZero 1
