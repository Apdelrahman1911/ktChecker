

package fixtures.spread_of_nullable

fun use(vararg xs: Int) = xs.size
fun broken(xs: IntArray?) = use(*xs)
