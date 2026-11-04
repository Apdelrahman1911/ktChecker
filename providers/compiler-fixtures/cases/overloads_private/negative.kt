package fixtures.overloads_private

@JvmOverloads fun read(value: Int = 1): Int = value
fun use(): Int = read()
