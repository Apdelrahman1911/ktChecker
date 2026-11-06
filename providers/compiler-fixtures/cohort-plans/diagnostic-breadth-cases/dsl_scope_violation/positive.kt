@DslMarker annotation class Mark
@Mark class Outer { fun outerOnly(): Int = 1 }
@Mark class Inner { fun innerOnly(): Int = 2 }
fun inspect(): Int = Outer().run {
    Inner().run {
        outerOnly()
    }
}
