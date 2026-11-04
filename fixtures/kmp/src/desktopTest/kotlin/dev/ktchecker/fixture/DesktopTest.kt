package dev.ktchecker.fixture

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTest {
    @Test fun desktopActualIsSelected() {
        assertEquals("desktop-jvm", platformFamily())
    }
}
