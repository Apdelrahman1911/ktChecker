@file:Suppress("UPPER_BOUND_VIOLATED")
package fixtures.upper_bound_violated

class Holder<T : CharSequence>
val broken = Holder<Int>()
