

package fixtures.uninitialized_variable

fun broken(): Int { val x: Int; return x }
