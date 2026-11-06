@DslMarker annotation class OuterMark
@DslMarker annotation class InnerMark
@OuterMark class Outer { fun outerOnly(): Int = 1 }
@InnerMark class Inner { fun innerOnly(): Int = 2 }
fun inspect(): Int = Outer().run {
    Inner().run {
        outerOnly()
    }
}
