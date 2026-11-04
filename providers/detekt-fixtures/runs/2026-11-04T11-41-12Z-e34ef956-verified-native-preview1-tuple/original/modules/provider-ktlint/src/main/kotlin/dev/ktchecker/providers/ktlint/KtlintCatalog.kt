package dev.ktchecker.providers.ktlint

import dev.ktchecker.contracts.ArtifactIdentity
import dev.ktchecker.contracts.Digests
import dev.ktchecker.providers.runtime.ExecutionProblem
import dev.ktchecker.providers.runtime.fileDigest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val KTLINT_VERSION = "1.8.0"
const val KTLINT_EMBEDDED_KOTLIN = "2.2.21"
const val KTLINT_COMPOSE_RULES_VERSION = "0.6.8"

@Serializable
data class KtlintRuleMapping(
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
    val executionProperty: String get() = "ktlint_" + upstream_id.replace(':', '_')
}

@Serializable
private data class MappingManifest(
    val schema_version: Int,
    val provider_id: String,
    val provider_version: String,
    val mappings: List<KtlintRuleMapping>,
)

@Serializable
data class KtlintArtifactPin(
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
    val ktlint_version: String,
    val embedded_kotlin_version: String,
    val compose_rules_version: String,
    val compatibility_qualified: Boolean,
    val artifacts: List<KtlintArtifactPin>,
)

/** All registrations are retained, but no mapping grants default activation or qualification. */
object KtlintCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    val mappings: List<KtlintRuleMapping> by lazy {
        val manifest = json.decodeFromString<MappingManifest>(resource("ktlint-rule-map.json"))
        check(manifest.schema_version == 1 && manifest.provider_id == "ktlint" && manifest.provider_version == KTLINT_VERSION)
        check(manifest.mappings.size == 137 && manifest.mappings.map { it.rule_id }.distinct().size == 137)
        check(manifest.mappings.map { it.upstream_id }.distinct().size == 137)
        check(manifest.mappings.all { it.relation in setOf("exact", "excluded_alias", "requires_refinement") && (!it.exact || it.depth == "syntax") })
        check(manifest.mappings.count { it.exact } == 131)
        manifest.mappings
    }
    val byId: Map<String, KtlintRuleMapping> by lazy { mappings.associateBy { it.rule_id } }
    val byUpstreamId: Map<String, KtlintRuleMapping> by lazy { mappings.associateBy { it.upstream_id } }
    val pins: List<KtlintArtifactPin> by lazy {
        val manifest = json.decodeFromString<ArtifactManifest>(resource("ktlint-artifacts.json"))
        check(manifest.schema_version == 1 && manifest.ktlint_version == KTLINT_VERSION)
        check(manifest.embedded_kotlin_version == KTLINT_EMBEDDED_KOTLIN && manifest.compose_rules_version == KTLINT_COMPOSE_RULES_VERSION)
        check(!manifest.compatibility_qualified)
        check(manifest.artifacts.size == 3 && manifest.artifacts.map { it.filename }.distinct().size == 3)
        check(manifest.artifacts.all { it.filename.matches(Regex("[A-Za-z0-9._-]+\\.jar")) && Digests.valid("sha256:" + it.sha256) })
        manifest.artifacts
    }

    /** Pinned external JARs are verified on every call, not loaded into the host classloader. */
    fun verifyArtifacts(directory: Path): List<ArtifactIdentity> = pins.map { pin ->
        val file = directory.toAbsolutePath().normalize().resolve(pin.filename)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file) ||
            Files.size(file) != pin.size || fileDigest(file) != "sha256:" + pin.sha256) {
            throw ExecutionProblem("ktlint_artifact_digest_mismatch", "Missing/modified pinned Ktlint artifact: ${pin.coordinate}")
        }
        ArtifactIdentity(pin.coordinate, "sha256:" + pin.sha256, file.toString())
    }

    private fun resource(name: String): String = KtlintCatalog::class.java.getResourceAsStream("/$name")
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("Missing bundled Ktlint descriptor $name")
}
