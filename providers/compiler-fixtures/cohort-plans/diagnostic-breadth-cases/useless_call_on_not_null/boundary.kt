fun afterCheck(values: List<Int>?): List<Int> {
    if (values == null) return emptyList()
    return values.orEmpty()
}
