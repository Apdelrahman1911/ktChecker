@file:Suppress("NO_ELSE_IN_WHEN")
package fixtures.no_else_in_when

fun broken(x: Boolean): Int = when (x) { true -> 1 }
