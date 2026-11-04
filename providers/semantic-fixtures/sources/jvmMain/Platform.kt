package fixture.semantic

actual class Identity {
    actual fun token(): String = "jvm"
}

class RealName(val value: String)

actual typealias AliasName = RealName

fun platformUse(): String {
    val alias: AliasName = AliasName("JVM")
    return commonUse(listOf(alias.value)) + Identity().token()
}
