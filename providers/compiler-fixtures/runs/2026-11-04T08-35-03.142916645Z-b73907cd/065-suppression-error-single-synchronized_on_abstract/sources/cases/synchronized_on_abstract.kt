@file:Suppress("SYNCHRONIZED_ON_ABSTRACT")
package fixtures.synchronized_on_abstract

abstract class Api { @Synchronized abstract fun use() }
