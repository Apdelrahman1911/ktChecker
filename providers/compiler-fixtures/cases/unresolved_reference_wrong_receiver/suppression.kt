@file:Suppress("UNRESOLVED_REFERENCE_WRONG_RECEIVER")
package fixtures.unresolved_reference_wrong_receiver

fun String.measure(): Int = length
val result = 1.measure()
