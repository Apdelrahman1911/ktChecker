package fixtures.type_variance_conflict_error

interface Sink<in T> { fun accept(value: T) }
