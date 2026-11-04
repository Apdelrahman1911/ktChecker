package fixtures.unresolved_reference_wrong_receiver

fun String.measure(): Int = length
val result = "word".measure()
