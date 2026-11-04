package fixtures.cannot_check_for_erased

fun matchesCollection(value: Any): Boolean = value is List<*>
