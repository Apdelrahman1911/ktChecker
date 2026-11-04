

package fixtures.named_parameter_not_found

fun use(x: Int = 0) = x
val result = use(z = 1)
