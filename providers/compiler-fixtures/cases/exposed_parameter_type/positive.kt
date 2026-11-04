package fixtures.exposed_parameter_type

private class Hidden
fun read(value: Hidden): Int = value.hashCode()
