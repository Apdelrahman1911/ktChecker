package fixtures.synchronized_on_inline

@Synchronized fun use(action: () -> Unit) { action() }
