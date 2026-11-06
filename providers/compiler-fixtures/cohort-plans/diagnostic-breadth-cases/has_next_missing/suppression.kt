@file:Suppress("HAS_NEXT_MISSING")
class Cursor { operator fun next(): Int = 1 }
class Range { operator fun iterator(): Cursor = Cursor() }
fun inspect(range: Range) {
    for (item in range) println(item)
}
