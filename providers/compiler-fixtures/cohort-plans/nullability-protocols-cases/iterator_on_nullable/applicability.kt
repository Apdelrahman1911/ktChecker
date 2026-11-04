package fixtures.nullability_protocols.iterator_on_nullable

class NullableRangeValue

class RangeCursor {
    operator fun hasNext(): Boolean = false
    operator fun next(): Int = 1
}

operator fun NullableRangeValue?.iterator(): RangeCursor = RangeCursor()

fun exercise(range: NullableRangeValue?): Int {
    var sum = 0
    for (value in range) sum += value
    return sum
}
