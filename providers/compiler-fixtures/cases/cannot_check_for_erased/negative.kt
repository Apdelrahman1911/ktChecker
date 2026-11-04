package fixtures.cannot_check_for_erased

inline fun <reified T> matches(value: Any): Boolean = value is T
