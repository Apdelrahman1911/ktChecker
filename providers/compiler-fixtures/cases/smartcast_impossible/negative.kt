package fixtures.smartcast_impossible

class Reader(var text: String?)
fun length(reader: Reader): Int { val text = reader.text; return if (text != null) text.length else 0 }
