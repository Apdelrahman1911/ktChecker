package fixtures.cannot_check_for_erased

fun <T> matches(value: T, predicate: (T) -> Boolean): Boolean = predicate(value)
