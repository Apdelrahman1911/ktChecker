package fixtures.no_else_in_when

fun fixed(x: Boolean): Int = when (x) { true -> 1; false -> 0 }
