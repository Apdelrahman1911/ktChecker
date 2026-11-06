package dev.ktchecker.contracts

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** SOURCE-authored regression controls; synthetic DTOs, not provider or transport qualification. */
class FindingRetrievalAttachmentTest {
    private val sourceDigest = Digests.sha256("source")
    private val reportDigest = Digests.sha256("complete report bytes")
    private val displayFingerprint = Digests.sha256("display fingerprint")
    private fun evidence(kind: String = "upstream_sarif", digest: String = reportDigest) =
        Evidence(kind, "Complete upstream report", digest = digest)
    private fun attachment(locator: String = "/outputs/attempt-1/provider-a/detekt.sarif") =
        EvidenceRetrievalAttachment("upstream_sarif", reportDigest, locator)
    private fun finding(material: List<Evidence> = emptyList(), attachments: List<EvidenceRetrievalAttachment> = emptyList()) = Finding(
        "test/rule", "Title", "Diagnosis", "Why", SourceLocation("Main.kt", sourceDigest), displayFingerprint,
        "synthetic-test", evidence = material, retrievalAttachments = attachments,
    )

    @Test fun defaultEmptyWireRemainsExactlyTheLegacyFindingWire() {
        // Explicit old DTO bytes, not an expectation synthesized by the new serializer.
        val legacy = """{"ruleId":"test/rule","title":"Title","diagnosis":"Diagnosis","whyItMatters":"Why","location":{"path":"Main.kt","fileDigest":"$sourceDigest","line":1,"column":0,"endLine":1,"endColumn":0,"excerpt":""},"fingerprint":"$displayFingerprint","providerId":"synthetic-test","context":{"target":"jvm","sourceSet":"main","module":":main","compilation":"main"},"severity":"warning","kind":"probable_defect","certainty":"medium","evidence":[],"fixHint":null,"verification":[],"upstreamId":null,"symbolId":null,"semanticDigest":null,"relatedLocations":[],"disposition":"open"}"""
        assertTrue(ContractJson.configuration.encodeDefaults)
        assertEquals(legacy, ContractJson.encodeToString(finding()))
        val decoded = ContractJson.decodeFromString<Finding>(legacy)
        assertTrue(decoded.retrievalAttachments.isEmpty())
        assertEquals(legacy, ContractJson.encodeToString(decoded))
        assertFalse("retrievalAttachments" in ContractJson.encodeToJsonElement(decoded).jsonObject)
    }

    @Test fun linkedLocatorsAndDuplicateEntriesRoundTripWithoutTrimmingOrDeduplication() {
        val exact = attachment("  /outputs/attempt-2/provider-b/detekt.sarif  ")
        val original = finding(listOf(evidence()), listOf(exact, exact))
        val json = ContractJson.encodeToString(original)
        val decoded = ContractJson.decodeFromString<Finding>(json)
        assertEquals(original, decoded)
        assertEquals(listOf(exact, exact), decoded.retrievalAttachments)
        assertEquals(exact.artifactRef, decoded.retrievalAttachments.first().artifactRef)
        assertEquals(json, ContractJson.encodeToString(decoded))
        val moved = decoded.copy(retrievalAttachments = listOf(attachment("/outputs/attempt-3/provider-c/detekt.sarif")))
        assertEquals(original.evidence, moved.evidence)
        assertNotEquals(Digests.sha256(json), Digests.sha256(ContractJson.encodeToString(moved)))
    }

    @Test fun constructionCopyAndDecodeRejectMalformedOrUnlinkedAttachments() {
        data class Case(val label: String, val value: EvidenceRetrievalAttachment, val material: List<Evidence>)
        val malformed = listOf(
            attachment().copy(kind = ""), attachment().copy(kind = " \t\n"), attachment().copy(kind = "\u00a0"),
            attachment().copy(kind = "upstream\u0000sarif"), attachment().copy(artifactRef = ""),
            attachment().copy(artifactRef = " \t\n"), attachment().copy(artifactRef = "\u2007"),
            attachment().copy(artifactRef = "/report\u0000.sarif"), attachment().copy(digest = "sha256:not-a-digest"),
            attachment().copy(digest = reportDigest + "\n"), attachment().copy(digest = reportDigest.uppercase()),
        ).mapIndexed { index, value ->
            // Link the malformed values exactly so these cases isolate field validation.
            Case("malformed-$index", value, listOf(evidence(value.kind, value.digest)))
        }
        val unlinked = listOf(
            Case("missing evidence", attachment(), emptyList()),
            Case("different kind", attachment(), listOf(evidence("other_kind"))),
            Case("different digest", attachment(), listOf(evidence(digest = Digests.sha256("other report")))),
            Case("kind and digest on separate entries", attachment(), listOf(
                evidence(digest = Digests.sha256("other report")), evidence("other_kind"))),
            Case("null evidence digest", attachment(), listOf(Evidence("upstream_sarif", "No report digest"))),
        )
        val valid = finding(listOf(evidence()), listOf(attachment()))
        assertEquals(valid, ContractJson.decodeFromString<Finding>(ContractJson.encodeToString(valid)))
        for (case in malformed + unlinked) {
            assertFailsWith<IllegalArgumentException>(case.label) { finding(case.material, listOf(case.value)) }
            assertFailsWith<IllegalArgumentException>(case.label) {
                valid.copy(evidence = case.material, retrievalAttachments = listOf(case.value))
            }
            val objectFields = ContractJson.encodeToJsonElement(valid).jsonObject.toMutableMap()
            objectFields["evidence"] = ContractJson.encodeToJsonElement(case.material)
            objectFields["retrievalAttachments"] = ContractJson.encodeToJsonElement(listOf(case.value))
            assertFails(case.label) { ContractJson.decodeFromString<Finding>(JsonObject(objectFields).toString()) }
        }
    }

    @Test fun callerOwnedCollectionMutationIsRejectedAtCopyOrDecodeNotMadeImmutableByConstruction() {
        for (changeEvidence in listOf(true, false)) {
            val material = mutableListOf(evidence())
            val locators = mutableListOf(attachment())
            val original = finding(material, locators)
            val result = ProviderResult("synthetic-test", "partial", listOf(original))
            assertSame(material, original.evidence)
            assertSame(locators, original.retrievalAttachments)
            // Mutate AFTER both valid constructors; return/serialize the original object.
            if (changeEvidence) material.clear()
            else locators[0] = attachment().copy(digest = Digests.sha256("unlinked report"))
            assertSame(original, result.findings.single())
            assertFailsWith<IllegalArgumentException> { original.copy() }
            val json = ContractJson.encodeToString(result)
            assertTrue(ContractJson.parseToJsonElement(json) is JsonObject, "Serialization alone is not publication validation")
            assertFails { ContractJson.decodeFromString<ProviderResult>(json) }
        }
    }
}
