fun delegated(input: Int): Int {
    var answer by kotlin.properties.Delegates.observable(input) { _, _, _ -> }
    return answer
}
