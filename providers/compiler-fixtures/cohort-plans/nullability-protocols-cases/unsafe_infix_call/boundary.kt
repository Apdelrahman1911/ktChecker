package fixtures.nullability_protocols.unsafe_infix_call

class InfixValue(val value: Int) {
    infix fun add(delta: Int): Int = value + delta
}

fun exercise(receiver: InfixValue?): Int {
    if (receiver == null) return 0
    return receiver add 1
}
