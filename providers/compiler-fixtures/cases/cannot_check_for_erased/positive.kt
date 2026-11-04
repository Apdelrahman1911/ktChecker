package fixtures.cannot_check_for_erased

fun <T> matches(value: Any): Boolean = value is T
