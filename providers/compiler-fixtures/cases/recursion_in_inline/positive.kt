package fixtures.recursion_in_inline

inline fun repeatAction(action: () -> Unit) { action(); repeatAction(action) }
