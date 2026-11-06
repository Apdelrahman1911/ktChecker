@Suppress("DUPLICATE_BRANCH_CONDITION_IN_WHEN")
fun suppressed(code: Int): String = when (code) {
    7 -> "first"
    7 -> "again"
    else -> "other"
}
fun control(code: Int): String = when (code) {
    7 -> "first"
    7 -> "again"
    else -> "other"
}
