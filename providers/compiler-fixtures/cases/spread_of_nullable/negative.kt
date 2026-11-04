package fixtures.spread_of_nullable

fun use(vararg xs: Int) = xs.size
fun fixed(xs: IntArray?) = if (xs == null) 0 else use(*xs)
