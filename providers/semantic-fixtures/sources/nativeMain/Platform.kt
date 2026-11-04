package fixture.semantic

actual class Identity {
    actual fun token(): String = "native"
}

class RealName(val value: String)

actual typealias AliasName = RealName

fun platformUse(): String {
    val alias: AliasName = AliasName("Native")
    return commonUse(listOf(alias.value)) + Identity().token()
}
