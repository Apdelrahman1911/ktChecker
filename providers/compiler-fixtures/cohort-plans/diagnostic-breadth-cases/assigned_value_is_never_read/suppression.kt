@Suppress("ASSIGNED_VALUE_IS_NEVER_READ")
fun suppressed(): Int {
    var value = 0
    val seed = value
    value = 1
    value = 2
    return seed + value
}
fun control(): Int {
    var value = 0
    val seed = value
    value = 1
    value = 2
    return seed + value
}
