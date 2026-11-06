package fixture.literal.arguments

// Real Kotlin input for the separately opted-in, exact JVM compiler fixture path.
// No serialization library/detector, descriptor count, data-flow proof or runtime claim is involved.
private const val STATIC_INDEX = 1

fun takeZero(index: Int) {}
fun takeOne(index: Int) {}
fun takeMaximum(index: Int) {}
fun takeMinimum(index: Int) {}
fun takeAboveIntMaximum(index: Long) {}
fun takeBelowIntMinimum(index: Long) {}
fun takeUnsignedMaximum(index: UInt) {}
fun takeLong(index: Long) {}
fun takeUnsigned(index: UInt) {}
fun takeString(index: String) {}
fun takeBoolean(index: Boolean) {}
fun takeChar(index: Char) {}
fun takeNull(index: Int?) {}
fun takeVariable(index: Int) {}
fun takeConstant(index: Int) {}
fun takeCall(index: Int) {}
fun takeArithmetic(index: Int) {}
fun takeUnary(index: Int) {}
fun takeParenthesized(index: Int) {}
fun takeNamed(index: Int) {}
fun takeNamedOrder(first: Int, second: Int) {}

private fun produceIndex(): Int = 1

fun exerciseDirectIntLiteralArguments() {
    val localIndex = 1
    takeZero(0)
    takeOne(1)
    takeMaximum(2147483647)
    // Minimum follows the actual compiler's unary normalization; no fixture-side evaluator.
    takeMinimum(-2147483648)
    takeAboveIntMaximum(2147483648L)
    takeBelowIntMinimum(-2147483649L)
    takeUnsignedMaximum(4294967295u)
    takeLong(1L)
    takeUnsigned(1u)
    takeString("1")
    takeBoolean(true)
    takeChar('1')
    takeNull(null)
    takeVariable(localIndex)
    takeConstant(STATIC_INDEX)
    takeCall(produceIndex())
    takeArithmetic(1 + 2)
    // No unary/wrapper evaluator is provided. Actual mapped FIR shape determines observation.
    takeUnary(-1)
    takeParenthesized((4))
    takeNamed(index = 2)
    takeNamedOrder(second = 6, first = 5)
}
