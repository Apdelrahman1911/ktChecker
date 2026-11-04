package fixtures.non_public_call_from_public_inline

private fun hiddenValue(): Int = 1
inline fun read(action: (Int) -> Int): Int = action(hiddenValue())
