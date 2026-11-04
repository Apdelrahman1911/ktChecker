package fixtures.concurrent_hash_map_contains_operator_error

fun hasKey(values: java.util.concurrent.ConcurrentHashMap<String, Int>, key: String): Boolean = key in values
