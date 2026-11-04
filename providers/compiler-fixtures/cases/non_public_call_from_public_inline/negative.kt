package fixtures.non_public_call_from_public_inline

@PublishedApi internal fun hiddenValue(): Int = 1
inline fun read(action: (Int) -> Int): Int = action(hiddenValue())
