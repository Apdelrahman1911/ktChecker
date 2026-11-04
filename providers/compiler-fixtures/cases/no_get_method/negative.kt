package fixtures.no_get_method

class Values { operator fun get(index: Int): Int = index }
fun read(values: Values): Int = values[0]
