package dev.ktchecker.semantic

import dev.ktchecker.contracts.Digests
import kotlinx.serialization.Serializable
import java.util.Collections

const val JVM_BINARY_ORIGIN_OBSERVER_ID = "kotlin-fir-jvm-container-2.4.21/v1"

/**
 * Optional compiler observation, NOT an extraction, artifact, JDK, model or permission receipt.
 * Null on an old/default shard means NOT OBSERVED, never an empty verified binary domain.
 * The ordinary bundle's completeForSupportedVocabulary remains about its original fact vocabulary.
 */
@Serializable
data class JvmBinaryOriginSurvey(
    val observations: List<JvmBinaryOriginObservation>,
    val observerId: String = JVM_BINARY_ORIGIN_OBSERVER_ID,
    val schemaVersion: Int = 1,
)

/** One survey entry per original structural symbol ID. Unknown entries carry no alleged origin. */
@Serializable
data class JvmBinaryOriginObservation(
    val symbolId: String,
    val state: String,
    val route: String? = null,
    val container: JvmBinaryContainerObservation? = null,
    val reason: String? = null,
)

/**
 * PRIVATE physical coordinates from the resolved declaration's actual compiler VFS object.
 * classByteDigest describes a bounded read AT OBSERVATION TIME, not the compiler's earlier cache
 * load. jar: containerPath is its actual archive; jrt: the actual VFS JDK home and module are kept.
 * No coordinate/artifact/version is inferred from names, no whole-JAR/JDK identity is fabricated.
 * A trusted owner must separately join these observations to exact invocation/output/source and
 * immutable class/JAR/whole-JDK custody before any consumer may treat them as ownership evidence.
 */
@Serializable
data class JvmBinaryContainerObservation(
    val protocol: String,
    val vfsPath: String,
    val containerPath: String,
    val entryPath: String,
    val binaryClassId: String,
    val classByteSize: Long,
    val classByteDigest: String,
    val jdkModule: String? = null,
)

internal const val JVM_ORIGIN_MAX_CLASS_BYTES = 4_194_304
internal const val JVM_ORIGIN_MAX_READ_BYTES = 33_554_432L

internal data class JvmBinaryLocation(val containerPath: String, val entryPath: String, val jdkModule: String?)

/** VFS-format decoding only. Never takes a class name, searches a classpath, or reads a file. */
internal fun jvmBinaryLocation(protocol: String, path: String): JvmBinaryLocation? {
    if (protocol !in setOf("jar", "jrt") || !boundedOriginText(path)) return null
    val separator = path.indexOf("!/")
    if (separator <= 0 || separator != path.lastIndexOf("!/")) return null
    val container = path.substring(0, separator)
    if (!absoluteOriginPath(container)) return null
    var entry = path.substring(separator + 2)
    if (protocol == "jar") {
        return if (classEntry(entry)) JvmBinaryLocation(container, entry, null) else null
    }
    // CoreJrtVirtualFile appends a JrtPath, which can be absolute. Keep vfsPath verbatim;
    // remove at most that one root separator solely to decode its physical modules domain.
    if (entry.startsWith('/')) entry = entry.substring(1)
    if (!entry.startsWith("modules/")) return null
    entry = entry.removePrefix("modules/")
    val moduleEnd = entry.indexOf('/')
    if (moduleEnd <= 0) return null
    val module = entry.substring(0, moduleEnd)
    if (module.length > 256 || module in setOf(".", "..") ||
        module.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_') }) return null
    val classPath = entry.substring(moduleEnd + 1)
    return if (classEntry(classPath)) JvmBinaryLocation(container, classPath, module) else null
}

private fun absoluteOriginPath(path: String): Boolean {
    val prefix = when {
        path.startsWith('/') && !path.startsWith("//") -> 1
        path.length >= 4 && (path[0] in 'A'..'Z' || path[0] in 'a'..'z') && path[1] == ':' && path[2] == '/' -> 3
        else -> return false
    }
    return path.length > prefix && path.substring(prefix).split('/').all {
        it.isNotEmpty() && it !in setOf(".", "..") && ':' !in it
    }
}

private fun classEntry(path: String): Boolean = path.length in 7..4_096 && path.endsWith(".class") &&
    path.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") && ':' !in it }

internal fun boundedOriginText(value: String): Boolean {
    if (value.length !in 1..4_096 || value.any { it == '\\' || it.isISOControl() }) return false
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (character.isHighSurrogate()) {
            if (index >= value.length || !value[index++].isLowSurrogate()) return false
        } else if (character.isLowSurrogate()) return false
    }
    return true
}

internal fun unknownJvmBinaryOrigin(symbolId: String, reason: String): JvmBinaryOriginObservation =
    JvmBinaryOriginObservation(symbolId, "unknown", reason = reason)

/** Join within one source-context shard only; structural IDs are not global binary identities. */
internal fun jvmBinaryOriginSurvey(observations: List<JvmBinaryOriginObservation>): JvmBinaryOriginSurvey =
    JvmBinaryOriginSurvey(observations.groupBy { it.symbolId }.toSortedMap().map { (id, values) ->
        // Distinct compiler objects may share an old structural ID. Never resolve a disagreement
        // by first/last observation order. Cross-shard joins remain the separate owner's job.
        values.distinct().singleOrNull() ?: unknownJvmBinaryOrigin(id, "ambiguous_structural_symbol_origin")
    })

/** Shared across the ENTIRE incoming shard vector, not reset for each attacker-supplied shard. */
internal class JvmBinaryOriginFreezeBudget {
    private var records = 0L
    private var characters = 0L
    fun record(value: JvmBinaryOriginObservation) {
        if (++records > 100_000) fail()
        fun text(value: String?) {
            if (value == null) return
            if (value.length > 4_096) fail()
            characters += value.length
            if (characters > 1_048_576) fail()
        }
        text(value.symbolId); text(value.state); text(value.route); text(value.reason)
        value.container?.let {
            text(it.protocol); text(it.vfsPath); text(it.containerPath); text(it.entryPath)
            text(it.binaryClassId); text(it.classByteDigest); text(it.jdkModule)
        }
    }
    fun header(value: JvmBinaryOriginSurvey) {
        if (value.observations.size > 20_000 || value.observerId.length > 256) fail()
        characters += value.observerId.length
        if (characters > 1_048_576) fail()
    }
    private fun fail(): Nothing = throw SemanticFactException("semantic_jvm_binary_origin_shape_limit",
        "Optional binary-origin observations exceed their bounded DTO vocabulary")
}

/** Structural checks only: even a valid observed DTO never proves that a compiler executed. */
internal object JvmBinaryOriginValidation {
    private val routes = setOf("kotlin_package_part", "kotlin_class", "java_method_class")
    private val reasons = setOf(
        "non_jvm_compiler_context", "source_declaration_not_binary", "local_declaration_not_binary",
        "non_callable_symbol", "callable_kind_unsupported", "callable_wrapper_unsupported",
        "java_enhancement_origin_unsupported", "kotlin_container_source_unavailable",
        "kotlin_binary_vfs_unavailable", "java_declaring_class_source_unavailable",
        "vfs_kind_or_path_unsupported", "vfs_class_not_readable", "class_byte_limit",
        "observation_byte_budget_exhausted", "vfs_class_changed_during_observation",
        "class_bytes_not_classfile", "binary_origin_observation_failed", "ambiguous_structural_symbol_origin",
    )

    fun freeze(value: JvmBinaryOriginSurvey?, budget: JvmBinaryOriginFreezeBudget): JvmBinaryOriginSurvey? {
        if (value == null) return null
        budget.header(value)
        return value.copy(observations = Collections.unmodifiableList(value.observations.map {
            budget.record(it)
            it.copy(container = it.container?.copy())
        }))
    }

    fun problems(requested: Boolean, value: JvmBinaryOriginSurvey?, symbols: List<SymbolFact>): List<String> {
        if (!requested) return if (value == null) emptyList() else listOf("semantic_jvm_binary_origin_not_requested")
        if (value == null) return listOf("semantic_jvm_binary_origin_survey_missing")
        if (value.observerId != JVM_BINARY_ORIGIN_OBSERVER_ID || value.schemaVersion != 1) {
            return listOf("semantic_jvm_binary_origin_observer_unsupported")
        }
        val byId = symbols.associateBy { it.id }
        val ids = value.observations.map { it.symbolId }
        if (ids.size != ids.distinct().size || ids.toSet() != byId.keys) {
            return listOf("semantic_jvm_binary_origin_symbol_binding_invalid")
        }
        if (value.observations.any { row ->
                !Digests.valid(row.symbolId) || when (row.state) {
                    "unknown" -> row.reason !in reasons || row.route != null || row.container != null
                    "observed" -> row.reason != null || row.route !in routes || row.container == null ||
                        byId.getValue(row.symbolId).let {
                            it.kind != "function" || it.local || it.source != null ||
                                it.origin != (if (row.route == "java_method_class") "Java(Library)" else "Library")
                        } ||
                        !validContainer(requireNotNull(row.container))
                    else -> true
                }
            }) return listOf("semantic_jvm_binary_origin_observation_invalid")
        return emptyList()
    }

    private fun validContainer(value: JvmBinaryContainerObservation): Boolean {
        if (!boundedOriginText(value.binaryClassId) || !Digests.valid(value.classByteDigest) ||
            value.classByteSize !in 8..JVM_ORIGIN_MAX_CLASS_BYTES.toLong()) return false
        val decoded = jvmBinaryLocation(value.protocol, value.vfsPath) ?: return false
        return decoded == JvmBinaryLocation(value.containerPath, value.entryPath, value.jdkModule)
    }
}
