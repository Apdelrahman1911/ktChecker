package fixtures.synchronized_on_inline.unsuppressed

@Synchronized inline fun use(action: () -> Unit) { action() }
