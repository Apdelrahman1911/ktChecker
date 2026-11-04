@file:Suppress("CONFLICTING_UPPER_BOUNDS")
package fixtures.conflicting_upper_bounds

class Impossible<T> where T : String, T : Number
