fun delegatedWrites(): Int {
    var value by kotlin.properties.Delegates.observable(0) { _, _, _ -> }
    value = 1
    value = 2
    return value
}
