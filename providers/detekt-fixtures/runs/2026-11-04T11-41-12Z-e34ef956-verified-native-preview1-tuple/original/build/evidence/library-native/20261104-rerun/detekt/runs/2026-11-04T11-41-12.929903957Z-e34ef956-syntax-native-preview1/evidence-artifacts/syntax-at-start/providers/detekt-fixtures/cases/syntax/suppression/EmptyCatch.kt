package fixture

@Suppress("EmptyCatchBlock")
fun suppressedCatch() {
    try {
        error("failure")
    } catch (failure: Exception) {
    }
}

fun adjacentCatch() {
    try {
        error("failure")
    } catch (failure: Exception) {
    }
}
