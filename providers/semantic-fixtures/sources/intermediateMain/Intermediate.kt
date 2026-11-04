package fixture.semantic

actual fun platformName(): String = "intermediate actual"

fun intermediateUse(): String = platformName()
