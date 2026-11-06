class Token(val number: Int)
fun Token.toInt(): Int = number
fun convert(value: Token): Int = value.toInt()
