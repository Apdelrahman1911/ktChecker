package fixture.coroutines

import java.lang.Thread as PlatformThread

// Actual JDK callable identity through an alias, not a token match.
suspend fun blockingSuspend() {
    PlatformThread.sleep(10)
}
