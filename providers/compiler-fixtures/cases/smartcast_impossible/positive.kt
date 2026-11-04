package fixtures.smartcast_impossible

class Reader(var text: String?)
fun length(reader: Reader): Int = if (reader.text != null) reader.text.length else 0
