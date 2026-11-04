package fixture.coroutines

// A regular function is not a suspension context.
fun legitimateThreadBoundary() {
    java.lang.Thread.sleep(10)
}

class LocalThread {
    fun sleep(duration: Long) {
        require(duration >= 0)
    }
}

// Same callable spelling, different resolved owner. This must not be reported.
suspend fun localOwnerIsNotPlatformThread() {
    LocalThread().sleep(10)
}
