@file:Suppress("ARGUMENT_PASSED_TWICE")
package fixtures.argument_passed_twice

fun use(x: Int) = x
val result = use(1, x = 2)
