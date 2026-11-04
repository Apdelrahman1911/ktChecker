package fixtures.nullability_protocols.iterator_on_nullable

class RangeValue {
    operator fun iterator(): RangeCursor = RangeCursor()
}

class RangeCursor {
    operator fun hasNext(): Boolean = false
    operator fun next(): Int = 1
}

fun exercise(range: RangeValue): Int {
    var sum = 0
    for (value in range) sum += value
    return sum
}
