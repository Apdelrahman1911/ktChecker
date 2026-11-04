@file:Suppress("UNINITIALIZED_VARIABLE")
package fixtures.uninitialized_variable

fun broken(): Int { val x: Int; return x }
