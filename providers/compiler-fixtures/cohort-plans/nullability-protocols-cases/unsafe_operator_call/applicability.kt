package fixtures.nullability_protocols.unsafe_operator_call

class NullableOperatorValue(val value: Int)

operator fun NullableOperatorValue?.plus(delta: Int): Int = (this?.value ?: 0) + delta

fun exercise(receiver: NullableOperatorValue?): Int = receiver + 1
