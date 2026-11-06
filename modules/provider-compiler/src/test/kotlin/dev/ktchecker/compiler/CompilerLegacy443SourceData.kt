package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Immutable SOURCE test DATA only. Never called by a registry loader, runner or provider. */
internal object CompilerLegacy443SourceData {
    private const val DIRECTORY = "mapping-contracts/legacy443-data/"
    private val metadata = mapOf(
        "test_nullability_protocols.py" to "796f6d39d32527185855de0b4647e4966e5e7d6631108a42aeab98ec99836b2f",
        "test_diagnostic_breadth.py" to "93de1bdd142c939864a1a72b39ccbbfc4161663c18e8e85db71633c5e5dedbe2",
        "test_diagnostic_breadth_hookup.py" to "4b18f75ae9067b75e4177345a9aa3f33d2e62e34ba629be271511d5ce7c86c09",
    )

    private val unchangedMetadata = setOf(
        "cohort-plans/nullability-protocols-v1.json",
        "cohort-plans/nullability-protocols-source-evidence.json",
        "cohort-plans/nullability-protocols-gap-inventory.json",
        "nullability_protocols.py",
        "cohort-plans/nullability-protocols-README.md",
        "cohort-plans/diagnostic-breadth-v1.json",
        "cohort-plans/diagnostic-breadth-source-evidence.json",
        "cohort-plans/diagnostic-breadth-gap-inventory.json",
        "cohort-plans/diagnostic-breadth-README.md",
    )

    fun mappings(fixtureRoot: Path): List<JsonObject> {
        val bytes = Files.readAllBytes(fixtureRoot.resolve(DIRECTORY + "compiler-rule-map.json.txt"))
        check(Digests.sha256(bytes) == CompilerFixtureMappingContract.LEGACY443.resourceDigest)
        // JSON metadata, never CompilerRuleRegistry.parse or an executable443 provider.
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.getValue("mappings").jsonArray
            .map { it.jsonObject }.also { check(it.size == 443) }
    }

    fun metadataText(fixtureRoot: Path, relative: String): String {
        require(relative in metadata || relative in unchangedMetadata) { "Not a closed legacy SOURCE metadata input" }
        val expected = metadata[relative] ?: return Files.readString(fixtureRoot.resolve(relative))
        val bytes = Files.readAllBytes(fixtureRoot.resolve(DIRECTORY + relative + ".txt"))
        check(Digests.sha256(bytes) == "sha256:$expected")
        return bytes.toString(Charsets.UTF_8)
    }
}
