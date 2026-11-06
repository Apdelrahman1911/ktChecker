@file:Suppress("HAS_NEXT_FUNCTION_TYPE_MISMATCH")
class Cursor {
    operator fun next(): Int = 1
    operator fun hasNext(): Int = 1
}
class Range { operator fun iterator(): Cursor = Cursor() }
fun inspect(range: Range) {
    for (item in range) println(item)
}
