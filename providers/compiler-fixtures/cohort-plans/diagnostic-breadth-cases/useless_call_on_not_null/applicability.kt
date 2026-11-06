class Basket(val values: List<Int>)
fun Basket.orEmpty(): List<Int> = values
fun copy(basket: Basket): List<Int> = basket.orEmpty()
