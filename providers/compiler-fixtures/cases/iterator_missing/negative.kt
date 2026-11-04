package fixtures.iterator_missing

class Values { operator fun iterator(): Iterator<Int> = listOf(1).iterator() }
fun read(values: Values) { for (value in values) println(value) }
