package fixtures.overloads_local

@JvmOverloads fun read(value: Int = 1): Int = value
fun use(): Int = read()
