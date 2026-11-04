package fixtures.overloads_local

fun use(): Int { @JvmOverloads fun read(value: Int = 1): Int = value; return read() }
