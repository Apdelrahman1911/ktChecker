package fixture

import androidx.compose.runtime.Composable

@Suppress("ComposableParamOrder")
@Composable
fun SuppressedExample(optional: String = "", required: Int) {
    println(optional + required)
}

@Composable
fun AdjacentExample(optional: String = "", required: Int) {
    println(optional + required)
}
