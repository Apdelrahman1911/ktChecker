@file:Suppress("CAST_NEVER_SUCCEEDS")
package fixtures.cast_never_succeeds

fun deliberatelyNull(x: String) = x as? Int
