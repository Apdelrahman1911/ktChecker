package fixtures.no_set_method

class Values { operator fun get(index: Int): Int = index
    operator fun set(index: Int, value: Int) { println(index + value) }
}
fun write(values: Values) { values[0] = 1 }
