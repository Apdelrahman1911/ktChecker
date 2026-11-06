fun choose(code: Int): String = when {
    code > 7 -> "large"
    code > 0 -> "positive"
    else -> "other"
}
