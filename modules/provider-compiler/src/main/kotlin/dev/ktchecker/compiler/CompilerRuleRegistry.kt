package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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

/** Exact loaded bytes identify a mapping contract, not execution or qualification authority. */
data class CompilerMappingIdentity(val contractId: String, val resourceDigest: String) {
    init { require(contractId.isNotBlank() && Digests.valid(resourceDigest)) }
}

class CompilerRuleRegistry private constructor(
    mappings: List<CompilerRuleMapping>,
    val mappingIdentity: CompilerMappingIdentity,
) {
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
        require(mappings.size == 445) { "Expected the reviewed 445 exact compiler mappings" }
        require(byUpstreamId.size == mappings.size && byRuleId.size == mappings.size) {
            "Duplicate compiler ID or normalized rule owner"
        }
        require(mappings.groupingBy { it.backend }.eachCount() ==
            mapOf("common" to 294, "jvm" to 99, "native" to 43, "ir" to 9)) {
            "Compiler mapping disposition counts changed without review"
        }
    }

    companion object {
        const val VERSION = "2.4.21"
        const val PROVIDER_ID = "kotlin-compiler-jvm"
        const val MAPPING_CONTRACT = "kotlin-compiler-2.4.21-common445-u2-v1"

        fun load(): CompilerRuleRegistry {
            val bytes = checkNotNull(CompilerRuleRegistry::class.java.getResourceAsStream("/compiler-rule-map.json")) {
                "Missing exact compiler rule mapping resource"
            }.use { it.readBytes() }
            return parse(bytes)
        }

        internal fun parse(text: String): CompilerRuleRegistry = parse(text.toByteArray(Charsets.UTF_8))

        internal fun parse(bytes: ByteArray): CompilerRuleRegistry {
            val frozen = bytes.copyOf()
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frozen)).toString()
            val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Compiler map must be an object")
            require(root["schema_version"]?.jsonPrimitive?.int == 1) { "Unsupported compiler mapping schema" }
            require(root.string("provider_id") == PROVIDER_ID)
            require(root.string("provider_version") == VERSION)
            require(root.string("mapping_contract") == MAPPING_CONTRACT) { "Unsupported compiler mapping contract" }
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
            return CompilerRuleRegistry(mappings, CompilerMappingIdentity(MAPPING_CONTRACT, Digests.sha256(frozen)))
        }

        private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: error("Missing compiler map $key")
    }
}
