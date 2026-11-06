class Counter { var value: Int = 0 }
fun memberWrites(counter: Counter): Int {
    counter.value = 1
    counter.value = 2
    return counter.value
}
