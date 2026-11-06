@DslMarker annotation class Mark
@Mark class Outer { fun outerOnly(): Int = 1 }
@Mark class Inner { fun innerOnly(): Int = 2 }
fun inspect(outer: Outer, inner: Inner): Int = outer.outerOnly() + inner.innerOnly()
