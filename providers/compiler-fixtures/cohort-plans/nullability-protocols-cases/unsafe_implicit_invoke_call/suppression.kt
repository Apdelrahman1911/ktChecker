@file:kotlin.Suppress("UNSAFE_IMPLICIT_INVOKE_CALL")
package fixtures.nullability_protocols.unsafe_implicit_invoke_call

class CallbackBox(val action: (() -> Int)?)

fun exercise(box: CallbackBox): Int = box.action()
