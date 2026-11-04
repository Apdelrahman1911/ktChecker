package dev.ktchecker.compiler

import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

/** Factory metadata is an addressable mapping, not tested rule or target coverage. */
data class CompilerRuleMapping(
    val ruleId: String,
    val upstreamId: String,
    val backend: String,
    val declaredSeverity: String,
    val kind: String,
    val certainty: String,
    val defaultActivation: String,
    val source: String,
    val sourceSection: String,
) {
    val jvmApplicable: Boolean get() = backend in setOf("common", "jvm", "ir")
}

class CompilerRuleRegistry private constructor(mappings: List<CompilerRuleMapping>) {
    val mappings: List<CompilerRuleMapping> = Collections.unmodifiableList(ArrayList(mappings))
    val byUpstreamId: Map<String, CompilerRuleMapping> = Collections.unmodifiableMap(
        mappings.associateBy { it.upstreamId },
    )
    val byRuleId: Map<String, CompilerRuleMapping> = Collections.unmodifiableMap(
        mappings.associateBy { it.ruleId },
    )
    val jvmRuleIds: Set<String> = Collections.unmodifiableSet(
        mappings.filter { it.jvmApplicable }.mapTo(linkedSetOf()) { it.ruleId },
    )

    init {
        require(mappings.size == 443) { "Expected the reviewed 443 exact compiler mappings" }
        require(byUpstreamId.size == mappings.size && byRuleId.size == mappings.size) {
            "Duplicate compiler ID or normalized rule owner"
        }
        require(mappings.groupingBy { it.backend }.eachCount() ==
            mapOf("common" to 292, "jvm" to 99, "native" to 43, "ir" to 9)) {
            "Compiler mapping disposition counts changed without review"
        }
    }

    companion object {
        const val VERSION = "2.4.21"
        const val PROVIDER_ID = "kotlin-compiler-jvm"

        fun load(): CompilerRuleRegistry {
            val text = checkNotNull(CompilerRuleRegistry::class.java.getResourceAsStream("/compiler-rule-map.json")) {
                "Missing exact compiler rule mapping resource"
            }.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(text)
        }

        internal fun parse(text: String): CompilerRuleRegistry {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Compiler map must be an object")
            require(root["schema_version"]?.jsonPrimitive?.int == 1) { "Unsupported compiler mapping schema" }
            require(root.string("provider_id") == PROVIDER_ID)
            require(root.string("provider_version") == VERSION)
            val rows = root["mappings"] as? JsonArray ?: error("Compiler map has no mappings")
            val mappings = rows.map { element ->
                val row = element as? JsonObject ?: error("Mapping is not an object")
                require(row.string("relation") == "exact") { "Nonexact rule cannot use exact-ID adapter" }
                val ruleId = row.string("rule_id")
                val upstreamId = row.string("upstream_id")
                require(ruleId.matches(Regex("ktc\\.compiler\\.[a-z]+\\.[a-z0-9_]+")))
                require(upstreamId.matches(Regex("[A-Z][A-Z0-9_]+")))
                CompilerRuleMapping(ruleId, upstreamId, row.string("backend"), row.string("declared_severity"),
                    row.string("kind"), row.string("certainty"), row.string("default_activation"),
                    row.string("source"), row.string("source_section"))
            }
            return CompilerRuleRegistry(mappings)
        }

        private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: error("Missing compiler map $key")
    }
}
