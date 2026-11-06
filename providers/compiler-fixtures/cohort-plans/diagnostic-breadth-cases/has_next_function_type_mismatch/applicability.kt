class Counter { fun hasNext(): Int = 1 }
fun direct(counter: Counter): Int = counter.hasNext()
