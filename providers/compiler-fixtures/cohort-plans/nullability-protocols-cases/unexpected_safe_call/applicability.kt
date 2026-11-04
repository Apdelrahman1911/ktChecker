package fixtures.nullability_protocols.unexpected_safe_call

open class SafeCallBase {
    fun score(): Int = 1
}

fun exercise(receiver: SafeCallBase?): Int = receiver?.score() ?: 0
