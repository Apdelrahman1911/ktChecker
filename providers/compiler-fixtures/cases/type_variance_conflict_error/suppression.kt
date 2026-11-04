@file:Suppress("TYPE_VARIANCE_CONFLICT_ERROR")
package fixtures.type_variance_conflict_error

class CovariantBox<out T>(var value: T)
