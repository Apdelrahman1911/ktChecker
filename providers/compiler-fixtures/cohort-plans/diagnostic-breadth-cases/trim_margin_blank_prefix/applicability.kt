class Text {
    fun trimMargin(prefix: String): String = prefix
}
fun trim(text: Text): String = text.trimMargin(" ")
