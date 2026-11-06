private const val aliasCode = 7
// Retained literal-only blind spot, not a safe alternative or a coverage claim.
fun choose(code: Int): String = when (code) {
    7 -> "first"
    aliasCode -> "again"
    else -> "other"
}
