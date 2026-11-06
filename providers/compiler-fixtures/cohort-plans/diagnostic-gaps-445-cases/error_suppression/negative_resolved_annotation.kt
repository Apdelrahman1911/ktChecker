package diagnosticgaps.userdefined

annotation class Suppress(vararg val names: String)

@Suppress("INAPPLICABLE_OPERATOR_MODIFIER")
fun safeValue(): Int = 1
