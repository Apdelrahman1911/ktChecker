package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Test

/** Independently authored old schema3 field inventories, not output of the new serializer.
 * Pure wire/identity controls; these fixtures grant no model/compiler/execution authority. */
class ObservedExportWireCompatibilityTest {
    private fun legacy(name: String, sha256: String): String {
        val bytes = assertNotNull(javaClass.getResourceAsStream("/observed-export-legacy-schema3/$name.json")).use { it.readBytes() }
        assertEquals("sha256:$sha256", Digests.sha256(bytes))
        return bytes.toString(Charsets.UTF_8).removeSuffix("\n").also { encoded ->
            assertEquals(encoded, Digests.canonical(ContractJson.parseToJsonElement(encoded)))
            assertFalse("observedExport" in ContractJson.parseToJsonElement(encoded).jsonObject)
        }
    }
    private fun declaration() = legacy("declaration", "44ce51ab4155bb4a72cf0bb4d23b487247067012e30bc330dab3595347283fbe")
    private fun certificate() = legacy("certificate", "d5901dcb01a68c800dffcf7f0d87d3b64c889fa71a52bf619299fff622ee84ae")
    private fun publication() = legacy("publication", "9671f52b78a90cdcdb472eea512cb147a829781f6a86e9d55de98534b5a5c264")

    @Test fun legacyDeclarationAndProfileEncodeToExactlyTheSameCanonicalBytes() {
        val original = declaration()
        val decoded = ContractJson.decodeFromString<JvmProducedPlanDeclaration>(original)
        assertNull(decoded.observedExport)
        assertEquals(original, canonical(decoded))
        assertEquals("sha256:afd275f101e13cf91deff16504d2add808647699676f7865b9a3d4ea40fa8ce2", Digests.sha256(canonical(decoded)))
    }

    @Test fun legacyExecutionCertificatePreservesItsCanonicalDigestAndWorkKey() {
        val original = certificate()
        val decoded = ContractJson.decodeFromString<PlanExecutionCertificate>(original)
        assertNull(decoded.observedExport)
        assertEquals(original, canonical(decoded))
        assertEquals("sha256:d4928e39738dfb71c11b2f720002c71f5b38cc08401505491eb31df0b7f92a23", decoded.digest())
        assertEquals("sha256:53798f6cf12a90d8450f08db22bc0771724690bc6d722a4585a6531503805a44", decoded.workKey())
    }

    @Test fun legacyPublicationPreservesItsExactCanonicalBytesAndDigest() {
        val original = publication()
        val decoded = ContractJson.decodeFromString<ProducedWorkPublication>(original)
        assertNull(decoded.observedExport)
        assertEquals(original, canonical(decoded))
        assertEquals("sha256:ff835f6eb803e07e6fe9af72babc2eef99f8d1e7d6cee9a0166ef6a46ddec810", decoded.digest())
    }

    @Test fun explicitNullAdditionIsNotCanonicalAndCannotSilentlyRewriteAnOldStoredRelation() {
        for (original in listOf(declaration(), certificate(), publication())) {
            val altered = Digests.canonical(JsonObject(ContractJson.parseToJsonElement(original).jsonObject + ("observedExport" to JsonNull)))
            assertNotEquals(original, altered)
            val profile = ContractJson.parseToJsonElement(original).jsonObject.getValue("profile").jsonPrimitive.content
            val roundTrip = when {
                profile == "ktchecker/produced-work-publication/1" -> canonical(ContractJson.decodeFromString<ProducedWorkPublication>(altered))
                "planId" in ContractJson.parseToJsonElement(original).jsonObject -> canonical(ContractJson.decodeFromString<PlanExecutionCertificate>(altered))
                else -> canonical(ContractJson.decodeFromString<JvmProducedPlanDeclaration>(altered))
            }
            // Durable loaders compare canonical(value) == stored bytes. An explicit null graft
            // fails that equality; absence remains exactly the old schema rather than a migration.
            assertEquals(original, roundTrip)
            assertNotEquals(altered, roundTrip)
        }
    }

    @Test fun everyObservedOwnerSessionNonceAndEvidenceFieldBindsAllThreeWireIdentities() {
        val binding = ClientBinding("owner", "project", "worktree", "client", "lineage", 1, "session")
        val base = ObservedExportPlanEvidence(binding, "fresh-invocation", Digests.sha256("observed-input-evidence"))
        val variants = listOf(base,
            base.copy(binding = binding.copy(ownerId = "other-owner")),
            base.copy(binding = binding.copy(workspaceId = "other-project")),
            base.copy(binding = binding.copy(worktreeId = "other-worktree")),
            base.copy(binding = binding.copy(clientId = "other-client")),
            base.copy(binding = binding.copy(lineageId = "other-lineage")),
            base.copy(binding = binding.copy(generation = 2)),
            base.copy(binding = binding.copy(sessionId = "other-session")),
            base.copy(invocationId = "another-fresh-invocation"),
            base.copy(evidenceDigest = Digests.sha256("different-evidence")),
            base.copy(assurance = "not-the-supported-assurance"))
        assertEquals(variants.size, variants.map { it.digest() }.distinct().size)
        val d = ContractJson.decodeFromString<JvmProducedPlanDeclaration>(declaration())
        val c = ContractJson.decodeFromString<PlanExecutionCertificate>(certificate())
        val p = ContractJson.decodeFromString<ProducedWorkPublication>(publication())
        assertEquals(variants.size, variants.map { Digests.sha256(canonical(d.copy(observedExport = it))) }.distinct().size)
        assertEquals(variants.size, variants.map { c.copy(observedExport = it).workKey() }.distinct().size)
        assertEquals(variants.size, variants.map { p.copy(observedExport = it).digest() }.distinct().size)
        for (evidence in variants) {
            assertEquals(OBSERVED_EXPORT_POLICY_KEYS, evidence.policies().keys)
            assertEquals(evidence.digest(), evidence.policies()["ktchecker.observed-export-evidence"])
            assertEquals(OBSERVED_EXPORT_CONFIGURATION_GAP, evidence.policies()["ktchecker.observed-export-closure"])
            assertEquals(evidence, ContractJson.decodeFromString<JvmProducedPlanDeclaration>(canonical(d.copy(observedExport = evidence))).observedExport)
            assertEquals(evidence, ContractJson.decodeFromString<PlanExecutionCertificate>(canonical(c.copy(observedExport = evidence))).observedExport)
            assertEquals(evidence, ContractJson.decodeFromString<ProducedWorkPublication>(canonical(p.copy(observedExport = evidence))).observedExport)
        }
    }
}
