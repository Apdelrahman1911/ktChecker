package dev.ktchecker.fixture

import kotlin.test.Test
import kotlin.test.assertEquals

class IosTest {
    @Test fun iosActualIsSelected() {
        assertEquals("ios", platformFamily())
    }
}
