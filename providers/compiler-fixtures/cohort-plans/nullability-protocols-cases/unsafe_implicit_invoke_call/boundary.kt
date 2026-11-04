package fixtures.nullability_protocols.unsafe_implicit_invoke_call

class CallbackBox(val action: (() -> Int)?)

fun exercise(box: CallbackBox): Int {
    val stableAction = box.action
    if (stableAction == null) return 0
    return stableAction()
}
