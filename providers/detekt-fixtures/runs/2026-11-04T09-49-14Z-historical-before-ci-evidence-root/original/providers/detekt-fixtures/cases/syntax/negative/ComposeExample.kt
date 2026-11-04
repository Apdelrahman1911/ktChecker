package fixture

import androidx.compose.runtime.Composable

@Composable
fun Example(required: Int, optional: String = "") {
    println(optional + required)
}
