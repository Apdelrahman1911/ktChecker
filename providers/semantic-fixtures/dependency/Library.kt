package fixture.dependency

class Resource<T>(val value: T)

fun libraryCall(value: String): String = value

fun libraryCall(value: Int): String = value.toString()
