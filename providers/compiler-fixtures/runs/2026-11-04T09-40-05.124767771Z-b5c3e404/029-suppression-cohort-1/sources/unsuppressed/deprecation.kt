package fixtures.deprecation.unsuppressed

@Deprecated("Use another") fun old() = 1
val result = old()
