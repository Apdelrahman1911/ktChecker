package fixtures.argument_type_mismatch

fun needInt(x: Int) = x
val result = needInt("text")
