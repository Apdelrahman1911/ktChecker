package dev.ktchecker.fixture

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidHostTest {
    // Executes Android's actual on the JVM; makes no Android device-runtime claim.
    @Test fun androidActualIsSelected() {
        assertEquals("android", platformFamily())
    }
}
