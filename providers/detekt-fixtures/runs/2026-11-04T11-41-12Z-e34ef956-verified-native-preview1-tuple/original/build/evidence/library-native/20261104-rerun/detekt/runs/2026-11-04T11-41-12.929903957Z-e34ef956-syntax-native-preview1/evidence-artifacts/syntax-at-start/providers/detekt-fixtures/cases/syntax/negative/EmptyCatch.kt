package fixture

fun handledCatch() {
    try {
        error("failure")
    } catch (failure: Exception) {
        println(failure.message)
    }
}

fun deliberatelyIgnoredCatch() {
    try {
        error("failure")
    } catch (ignored: Exception) {
    }
}
