package dev.ktchecker.fixture

expect fun platformFamily(): String

data class Envelope(val label: String, val retries: Int)

fun envelope(label: String?, retries: Int): Envelope {
    val normalized = requireNotNull(label) { "A label is required" }.trim()
    require(normalized.isNotEmpty()) { "A label must not be blank" }
    require(retries >= 0) { "Retries must not be negative" }
    return Envelope(normalized, retries)
}

fun checkedIncrement(value: Int): Int {
    require(value < Int.MAX_VALUE) { "Increment would overflow" }
    return value + 1
}
