@file:Suppress("NULL_FOR_NONNULL_TYPE")
package fixtures.null_for_nonnull_type

fun needString(x: String) = x
val result = needString(null)
