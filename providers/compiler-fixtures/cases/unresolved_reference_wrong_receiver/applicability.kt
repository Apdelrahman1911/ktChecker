package fixtures.unresolved_reference_wrong_receiver

fun String.measure(): Int = length
fun Int.measure(): Int = this
val result = 1.measure()
