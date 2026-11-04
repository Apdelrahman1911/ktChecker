package fixture

fun emptyCatch() {
    try {
        error("failure")
    } catch (failure: Exception) {
    }
}
