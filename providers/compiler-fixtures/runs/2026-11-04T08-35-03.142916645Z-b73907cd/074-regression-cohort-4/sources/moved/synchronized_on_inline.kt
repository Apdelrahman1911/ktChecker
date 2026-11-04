

package fixtures.synchronized_on_inline

@Synchronized inline fun use(action: () -> Unit) { action() }
