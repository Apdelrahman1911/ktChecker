package fixture

import androidx.compose.runtime.Composable

@Composable
fun Example(optional: String = "", required: Int) {
    println(optional + required)
}
