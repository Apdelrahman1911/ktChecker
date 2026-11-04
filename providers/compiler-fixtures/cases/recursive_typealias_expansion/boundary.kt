package fixtures.recursive_typealias_expansion

typealias TextRow = List<String>
typealias TextGrid = List<TextRow>
fun rows(value: TextGrid): Int = value.size
