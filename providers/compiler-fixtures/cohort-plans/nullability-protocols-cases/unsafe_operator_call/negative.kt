package fixtures.nullability_protocols.unsafe_operator_call

class OperatorValue(val value: Int) {
    operator fun plus(delta: Int): Int = value + delta
}

fun exercise(receiver: OperatorValue): Int = receiver + 1
