class Cursor {
    operator fun next(): Int = 1
    operator fun hasNext(): Boolean = false
}
class Range { operator fun iterator(): Cursor = Cursor() }
fun inspect(range: Range) {
    for (item in range) println(item)
}
