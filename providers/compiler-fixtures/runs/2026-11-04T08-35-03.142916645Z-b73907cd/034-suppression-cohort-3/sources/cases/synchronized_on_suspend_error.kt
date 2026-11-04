@file:Suppress("SYNCHRONIZED_ON_SUSPEND_ERROR")
package fixtures.synchronized_on_suspend_error

@Synchronized suspend fun use() = 1
