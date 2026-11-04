@file:Suppress("OVERLOADS_INTERFACE")
package fixtures.overloads_interface

interface Api { @JvmOverloads fun use(x: Int = 1): Int }
