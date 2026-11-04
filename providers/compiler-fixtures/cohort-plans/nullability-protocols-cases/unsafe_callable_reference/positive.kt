package fixtures.nullability_protocols.unsafe_callable_reference

class ReferenceValue(val value: Int) {
    fun score(delta: Int): Int = value + delta
}

fun exercise(receiver: ReferenceValue?): (Int) -> Int = receiver::score
