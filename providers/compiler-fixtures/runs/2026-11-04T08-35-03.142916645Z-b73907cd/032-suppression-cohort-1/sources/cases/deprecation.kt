@file:Suppress("DEPRECATION")
package fixtures.deprecation

@Deprecated("Use another") fun old() = 1
val result = old()
