@file:Suppress("CANNOT_CHECK_FOR_ERASED")
package fixtures.cannot_check_for_erased

fun <T> matches(value: Any): Boolean = value is T
