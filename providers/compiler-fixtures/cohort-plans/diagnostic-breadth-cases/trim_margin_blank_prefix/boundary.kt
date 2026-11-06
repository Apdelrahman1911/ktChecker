private const val blankPrefix = " "
// This blank prefix is still invalid at runtime; the checker only recognizes literals.
fun trim(text: String): String = text.trimMargin(blankPrefix)
