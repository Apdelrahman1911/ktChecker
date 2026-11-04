package dev.ktchecker.providers.detekt

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.fileDigest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val DETEKT_VERSION = "2.0.0-alpha.6"
const val DETEKT_EMBEDDED_KOTLIN = "2.4.10"
const val COMPOSE_RULES_VERSION = "0.6.8"

@Serializable
data class DetektRuleMapping(
    val rule_id: String,
    val upstream_id: String,
    val relation: String,
    val depth: String,
    val severity: String,
    val kind: String,
    val certainty: String,
    val title: String,
    val problem: String,
    val rationale: String,
    val default_activation: String,
    val catalog_record_sha256: String,
    val gaps: List<String> = emptyList(),
) {
    val exact: Boolean get() = relation == "exact" && default_activation != "excluded"
    val sarifId: String get() = "detekt." + upstream_id.replace(':', '.')
}

@Serializable
private data class MappingManifest(
    val schema_version: Int,
    val provider_id: String,
    val provider_version: String,
    val mappings: List<DetektRuleMapping>,
)

@Serializable
data class DetektArtifactPin(
    val coordinate: String,
    val filename: String,
    val url: String,
    val upstream_checksum_algorithm: String,
    val upstream_checksum: String,
    val sha256: String,
    val size: Long,
)

@Serializable
private data class ArtifactManifest(
    val schema_version: Int,
    val detekt_version: String,
    val embedded_kotlin_version: String,
    val compose_rules_version: String,
    val compatibility_qualified: Boolean,
    val artifacts: List<DetektArtifactPin>,
)

/** Frozen identities are availability/mapping metadata, never test certificates. */
object DetektCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    val mappings: List<DetektRuleMapping> by lazy {
        val manifest = json.decodeFromString<MappingManifest>(resource("detekt-rule-map.json"))
        check(manifest.schema_version == 1 && manifest.provider_id == "detekt" && manifest.provider_version == DETEKT_VERSION)
        check(manifest.mappings.size == 276 && manifest.mappings.map { it.rule_id }.distinct().size == 276)
        check(manifest.mappings.all { it.relation in setOf("exact", "requires_refinement", "excluded_alias") })
        manifest.mappings
    }
    val byId: Map<String, DetektRuleMapping> by lazy { mappings.associateBy { it.rule_id } }
    val bySarifId: Map<String, List<DetektRuleMapping>> by lazy { mappings.groupBy { it.sarifId } }
    val pins: List<DetektArtifactPin> by lazy {
        val manifest = json.decodeFromString<ArtifactManifest>(resource("detekt-artifacts.json"))
        check(manifest.schema_version == 1 && manifest.detekt_version == DETEKT_VERSION)
        check(manifest.embedded_kotlin_version == DETEKT_EMBEDDED_KOTLIN && manifest.compose_rules_version == COMPOSE_RULES_VERSION)
        check(!manifest.compatibility_qualified)
        check(manifest.artifacts.size == 5 && manifest.artifacts.map { it.filename }.distinct().size == 5)
        check(manifest.artifacts.all { it.filename.matches(Regex("[A-Za-z0-9._-]+\\.jar")) && Digests.valid("sha256:" + it.sha256) })
        manifest.artifacts
    }

    /** Every byte is checked on every invocation, including cached plugin JARs. */
    fun verifyArtifacts(directory: Path): List<ArtifactIdentity> = pins.map { pin ->
        val file = directory.toAbsolutePath().normalize().resolve(pin.filename)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file) ||
            Files.size(file) != pin.size || fileDigest(file) != "sha256:" + pin.sha256) {
            throw ExecutionProblem("detekt_artifact_digest_mismatch", "Missing/modified pinned Detekt artifact: ${pin.coordinate}")
        }
        ArtifactIdentity(pin.coordinate, "sha256:" + pin.sha256, file.toString())
    }

    private fun resource(name: String): String = DetektCatalog::class.java.getResourceAsStream("/$name")
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("Missing bundled Detekt descriptor $name")
}
