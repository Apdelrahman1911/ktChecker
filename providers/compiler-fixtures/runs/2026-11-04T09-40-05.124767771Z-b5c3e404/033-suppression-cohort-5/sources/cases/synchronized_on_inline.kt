@file:Suppress("SYNCHRONIZED_ON_INLINE")
package fixtures.synchronized_on_inline

@Synchronized inline fun use(action: () -> Unit) { action() }
