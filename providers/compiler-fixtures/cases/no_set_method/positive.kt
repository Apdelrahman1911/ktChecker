package fixtures.no_set_method

class Values { operator fun get(index: Int): Int = index }
fun write(values: Values) { values[0] = 1 }
