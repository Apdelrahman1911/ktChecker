package fixture.semantic

import fixture.dependency.Resource
import fixture.dependency.libraryCall as dependencyCall
import fixture.external.choose as externalPick

annotation class Important

expect fun platformName(): String

expect class Identity {
    fun token(): String
}

expect class AliasName(value: String) {
    val value: String
}

class Shared<T>(val value: T)

fun choose(value: Int): String = value.toString()

fun choose(value: String): String = value

@Important
fun commonUse(values: List<String>): String {
    val integerChoice = choose(7)
    val textChoice = choose("seven")
    val external = externalPick("external")
    val library = dependencyCall(Resource("dependency").value)
    val nested = Shared(values.first()).value
    return "$integerChoice:$textChoice:$external:$library:$nested:${platformName()}"
}

fun localShadow(): String {
    fun choose(value: Long): String = value.toString()
    return choose(1L)
}
