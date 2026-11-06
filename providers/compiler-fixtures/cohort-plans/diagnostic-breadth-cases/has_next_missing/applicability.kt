class Cursor { operator fun next(): Int = 1 }
fun direct(cursor: Cursor): Int = cursor.next()
