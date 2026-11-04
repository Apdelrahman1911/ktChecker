package fixtures.unresolved_reference_wrong_receiver

fun Number.measure(): Int = toInt()
val integer = 1.measure()
val decimal = 1.5.measure()
