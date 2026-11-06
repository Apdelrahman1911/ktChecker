typealias Availability = Boolean
class Cursor {
    operator fun next(): Int = 1
    operator fun hasNext(): Availability = false
}
class Range { operator fun iterator(): Cursor = Cursor() }
fun inspect(range: Range) {
    for (item in range) println(item)
}
