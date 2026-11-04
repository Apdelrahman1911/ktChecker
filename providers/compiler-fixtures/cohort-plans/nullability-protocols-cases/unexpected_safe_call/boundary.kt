package fixtures.nullability_protocols.unexpected_safe_call

open class SafeCallBase {
    fun score(): Int = 1
}

class SafeCallDerived : SafeCallBase() {
    fun exercise(): Int = this.score()
}
