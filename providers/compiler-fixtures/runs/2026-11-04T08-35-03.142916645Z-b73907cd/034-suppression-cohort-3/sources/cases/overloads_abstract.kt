@file:Suppress("OVERLOADS_ABSTRACT")
package fixtures.overloads_abstract

abstract class Api { @JvmOverloads abstract fun use(x: Int = 1): Int }
