package fixtures.non_vararg_spread

fun use(xs: IntArray) = xs.size
val result = use(*intArrayOf(1))
