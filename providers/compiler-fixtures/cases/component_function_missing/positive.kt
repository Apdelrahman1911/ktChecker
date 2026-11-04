package fixtures.component_function_missing

class PairLike(val first: Int, val second: Int)
fun read(value: PairLike): Int { val (first, second) = value; return first + second }
