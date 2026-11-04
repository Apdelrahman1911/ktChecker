package fixtures.nullability_protocols.unsafe_operator_call

class OperatorValue(val value: Int) {
    operator fun plus(delta: Int): Int = value + delta
}

typealias OperatorAlias = OperatorValue

fun exercise(receiver: OperatorAlias?): Int {
    if (receiver == null) return 0
    return receiver + 1
}
