package dev.ktchecker.kernel

import dev.ktchecker.contracts.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.jupiter.api.Test

/**
 * Independently authored ASCII transport control shared literally with the MCP/reference-host
 * SOURCE tests. It is NOT a kernel-produced/admitted/certified policy event: the retained required
 * unknown coverage and incomplete result deliberately make it ineligible for kernel publication.
 * These tests exercise DTO serialization and checksum self-consistency, not authority, provider
 * execution, real-host delivery, concurrency, currentness or completion permission.
 */
class PolicyReassessmentWireContractTest {
    private fun view(): PolicyReassessmentView = ContractJson.decodeFromString(WIRE)
    private fun encoded(value: PolicyReassessmentView): String =
        Digests.canonical(ContractJson.encodeToJsonElement(value))
    private fun checksum(value: PolicyReassessmentView): String =
        Digests.sha256(encoded(value.copy(certificateDigest = "")))
    private fun objectValue(): JsonObject = ContractJson.parseToJsonElement(WIRE).jsonObject

    @Test fun handAuthoredAsciiControlHasItsExactCanonicalBytesAndChecksum() {
        assertEquals(4997, WIRE.toByteArray(Charsets.UTF_8).size)
        assertTrue(WIRE.all { it.code in 0..127 })
        assertEquals(WIRE_DIGEST, Digests.sha256(WIRE))
        assertEquals(WIRE, Digests.canonical(objectValue()))
        assertEquals(CERTIFICATE_DIGEST, view().certificateDigest)
        assertEquals(CERTIFICATE_DIGEST, checksum(view()))
    }

    @Test fun kotlinSerializerRoundTripsTheFullLiteralWithoutDroppingDefaultsOrNulls() {
        val decoded = view()
        assertEquals(WIRE, encoded(decoded))
        assertEquals(decoded, ContractJson.decodeFromString<PolicyReassessmentView>(encoded(decoded)))
        assertEquals("policy_reassessed", decoded.state)
        assertEquals(PolicyReassessmentView.FORMAT, decoded.format)
        assertEquals(PolicyReassessmentCapture.FORMAT, decoded.capture.format)
        assertEquals(decoded.request.reviewId, decoded.reviewId)
        assertEquals("preview", decoded.result.providerVersion)
        assertNull(decoded.result.metrics.cpuMillis)
        assertNull(decoded.result.metrics.peakRssBytes)
        val finding = decoded.result.findings.single()
        assertNull(finding.fixHint)
        assertNull(finding.symbolId)
        assertNull(finding.semanticDigest)
        assertNull(finding.evidence.first().artifactRef)
        assertNull(finding.evidence.first().digest)
        assertEquals(emptyList(), finding.verification.first().command)
        assertEquals("not_run", finding.verification.first().state)
        assertEquals(RuleContext(), finding.context)
    }

    @Test fun missingDefaultFieldsDecodeButDoNotMatchTheExactStoredCanonicalRepresentation() {
        val full = objectValue()
        val variants = listOf(
            JsonObject(full - setOf("reviewId", "state", "format")),
            JsonObject(full + ("capture" to JsonObject(full.getValue("capture").jsonObject - "format"))),
        )
        for (sparse in variants) {
            val sparseBytes = Digests.canonical(sparse)
            val decoded = ContractJson.decodeFromString<PolicyReassessmentView>(sparseBytes)
            assertEquals(view(), decoded)
            assertEquals(WIRE, encoded(decoded))
            assertNotEquals(sparseBytes, encoded(decoded))
            assertNotEquals(WIRE_DIGEST, Digests.sha256(sparseBytes))
        }
        // Canonical equality is enforced by the ledger/transport, not by DTO construction alone.
    }

    @Test fun ownerDocumentsCredentialsAndExtraNestedFieldsAreOutsideThisClosedDto() {
        val full = objectValue()
        val unknowns = listOf(
            JsonObject(full + ("configuration" to JsonObject(emptyMap()))),
            JsonObject(full + ("adapterCredential" to JsonNull)),
            JsonObject(full + ("raw" to JsonObject(emptyMap()))),
            JsonObject(full + ("capture" to JsonObject(full.getValue("capture").jsonObject + ("documents" to JsonArray(emptyList()))))),
            JsonObject(full + ("certified" to JsonObject(full.getValue("certified").jsonObject + ("permit" to JsonNull)))),
        )
        assertEquals(WIRE, encoded(view())) // The unmodified control is decodable.
        for (unknown in unknowns) {
            assertFailsWith<SerializationException> {
                ContractJson.decodeFromString<PolicyReassessmentView>(Digests.canonical(unknown))
            }
        }
    }

    @Test fun fullEvidenceAndBothPolicyReferencesBindTheChecksumWithoutBecomingAuthority() {
        val original = view()
        val first = original.result.findings.single()
        val changed = listOf(
            original.copy(sequence = original.sequence + 1),
            original.copy(certified = original.certified.copy(reviewSequence = 4)),
            original.copy(request = original.request.copy(idempotencyKey = "another-request")),
            original.copy(capture = original.capture.copy(currentCaptureId = original.capture.originalCaptureId)),
            original.copy(capture = original.capture.copy(analysisAuthorityDigest = original.capture.currentPolicyDigest)),
            original.copy(result = original.result.copy(findings = listOf(first.copy(disposition = "open")))),
            original.copy(result = original.result.copy(findings = listOf(first.copy(evidence = first.evidence.dropLast(1))))),
            original.copy(result = original.result.copy(rawDiagnostics = original.result.rawDiagnostics.dropLast(1))),
            original.copy(result = original.result.copy(coverage = original.result.coverage.dropLast(1))),
            original.copy(auditOnly = false, reasons = emptyList()),
        )
        assertEquals(CERTIFICATE_DIGEST, checksum(original))
        for (modified in changed) assertNotEquals(CERTIFICATE_DIGEST, checksum(modified))
        assertEquals(changed.size, changed.map(::checksum).distinct().size)
        // Recomputing an unkeyed checksum is intentionally possible; it does NOT certify the subject.
        val selfConsistentButUntrusted = changed.first().let { it.copy(certificateDigest = checksum(it)) }
        assertEquals(selfConsistentButUntrusted.certificateDigest, checksum(selfConsistentButUntrusted))
        assertFalse(selfConsistentButUntrusted.result.complete())
    }

    @Test fun policyDeliverySequenceIsSeparateFromTheOriginalReviewSequenceAndHasExactPayload() {
        val policy = view()
        val event = ReviewEvent(policy.viewId, policy.clientId, policy.sequence, policy.reviewId,
            policy.patchId, policy.sourceSnapshotId, policy.state, true, WIRE_DIGEST, WIRE, policy.generation)
        val eventBytes = Digests.canonical(ContractJson.encodeToJsonElement(event))
        val decoded = ContractJson.decodeFromString<ReviewEvent>(eventBytes)
        assertEquals(event, decoded)
        assertTrue(decoded.deliveryRequired)
        assertEquals(PREVIEW_CONTRACT, decoded.contractVersion)
        assertEquals(7L, decoded.sequence)
        assertEquals(3L, policy.certified.reviewSequence)
        assertNotEquals(decoded.sequence, policy.certified.reviewSequence)
        assertEquals(WIRE, decoded.payload)
        assertEquals(Digests.sha256(decoded.payload), decoded.payloadDigest)
        assertEquals(policy.viewId, decoded.eventId)
        assertEquals(policy.generation, decoded.generation)
        // This envelope is synthetic: no event insertion, acknowledgement or witness is performed.
    }

    @Test fun kotlinLongIdentityFieldsDoNotSilentlyRoundToJavascriptNumbers() {
        val original = view()
        val outsideJsSafeInteger = 9_007_199_254_740_993L
        val wide = original.copy(sequence = outsideJsSafeInteger, generation = Long.MAX_VALUE,
            request = original.request.copy(generation = Long.MAX_VALUE),
            certified = original.certified.copy(reviewSequence = outsideJsSafeInteger - 1))
        val wideBytes = encoded(wide)
        val decoded = ContractJson.decodeFromString<PolicyReassessmentView>(wideBytes)
        val tree = ContractJson.parseToJsonElement(wideBytes).jsonObject
        assertEquals(outsideJsSafeInteger, decoded.sequence)
        assertEquals(Long.MAX_VALUE, decoded.generation)
        assertEquals(Long.MAX_VALUE, decoded.request.generation)
        assertEquals(outsideJsSafeInteger - 1, decoded.certified.reviewSequence)
        assertEquals(outsideJsSafeInteger.toString(), tree.getValue("sequence").jsonPrimitive.content)
        assertEquals(Long.MAX_VALUE.toString(), tree.getValue("generation").jsonPrimitive.content)
        assertNotEquals(WIRE, wideBytes)
        // The TypeScript boundary must reject these values, never coerce/round their identities.
        // This is serialization only, not a claim these values can be admitted into the ledger.
    }

    @Test fun anAcceptedFindingDoesNotPromoteUnknownCoverageOrAnIncompleteExecution() {
        val original = view()
        assertTrue(original.auditOnly)
        assertEquals("accepted", original.result.findings.single().disposition)
        assertEquals("incomplete", original.result.status)
        assertFalse(original.result.complete())
        val unknown = original.result.coverage.single { it.ruleId == "fixture.unknown" }
        assertTrue(unknown.required)
        assertEquals("unknown", unknown.state)
        assertEquals("not_executed", unknown.provenance)
        assertNull(unknown.certificateId)
        assertFalse(unknown.implemented)
        assertFalse(unknown.tested)
        assertFalse(unknown.qualified)
        assertFalse(unknown.successful())
        assertEquals(listOf("original_execution_incomplete"), original.result.reasons)
        assertEquals(2, original.result.rawDiagnostics.size)
        assertEquals("error", original.result.rawDiagnostics.first().severity)
        assertEquals(original.result, ContractJson.decodeFromString<PolicyReassessmentView>(encoded(original)).result)
    }

    private companion object {
        const val WIRE_DIGEST = "sha256:58156bf9312cf4fefbfa4e272aa069ba8b55f342c53053d94f3791ccffb14535"
        const val CERTIFICATE_DIGEST = "sha256:f6ff5ab242366464c985d7c9ce48e1ff97aad654186a882fcf97ee0bd3d07881"
        const val WIRE = """{"auditOnly":true,"capture":{"analysisAuthorityDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","currentCaptureId":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","currentPolicyDigest":"sha256:2222222222222222222222222222222222222222222222222222222222222222","format":"ktchecker/policy-reassessment-capture/1","originalCaptureId":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","originalPolicyDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"certificateDigest":"sha256:f6ff5ab242366464c985d7c9ce48e1ff97aad654186a882fcf97ee0bd3d07881","certified":{"effectiveInputKey":"sha256:5555555555555555555555555555555555555555555555555555555555555555","inputKey":"sha256:3333333333333333333333333333333333333333333333333333333333333333","originalEffectiveResultDigest":"sha256:9999999999999999999999999999999999999999999999999999999999999999","originalPolicyCertificateDigest":"sha256:7777777777777777777777777777777777777777777777777777777777777777","rawResultDigest":"sha256:8888888888888888888888888888888888888888888888888888888888888888","reviewSequence":3,"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000000","workCertificateId":"sha256:6666666666666666666666666666666666666666666666666666666666666666","workKey":"sha256:4444444444444444444444444444444444444444444444444444444444444444"},"clientId":"client-a","evaluatorIdentity":"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","format":"ktchecker/policy-reassessment-view/1","generation":1,"issuerIdentity":"sha256:0000000000000000000000000000000000000000000000000000000000000000","patchId":"patch-original","reasons":["source_scope_incomplete"],"request":{"expectedPolicyDigest":"sha256:2222222222222222222222222222222222222222222222222222222222222222","generation":1,"idempotencyKey":"policy-request-1","reviewId":"review-original"},"result":{"coverage":[{"certificateId":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","context":{"compilation":"main","module":":main","sourceSet":"main","target":"jvm"},"depth":"build","implemented":true,"provenance":"executed","qualified":true,"reason":null,"required":true,"ruleId":"fixture.nullability","state":"findings","tested":true},{"certificateId":null,"context":{"compilation":"main","module":":main","sourceSet":"main","target":"jvm"},"depth":"build","implemented":false,"provenance":"not_executed","qualified":false,"reason":"Original unknown coverage is not promoted by policy.","required":true,"ruleId":"fixture.unknown","state":"unknown","tested":false}],"findings":[{"certainty":"medium","context":{"compilation":"main","module":":main","sourceSet":"main","target":"jvm"},"diagnosis":"Nullable name cannot be assigned to String.","disposition":"accepted","evidence":[{"artifactRef":null,"digest":null,"kind":"source","summary":"Original source excerpt."},{"artifactRef":"artifact:diagnostic-1","digest":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","kind":"compiler","summary":"Original compiler message."}],"fingerprint":"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","fixHint":null,"kind":"probable_defect","location":{"column":34,"endColumn":38,"endLine":1,"excerpt":"val label: String = nullable","fileDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","line":1,"path":"Label.kt"},"providerId":"fixture-provider","relatedLocations":[{"column":0,"endColumn":0,"endLine":2,"excerpt":"","fileDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","line":2,"path":"Usage.kt"}],"ruleId":"fixture.nullability","semanticDigest":null,"severity":"warning","symbolId":null,"title":"Nullable assignment","upstreamId":null,"verification":[{"command":[],"expected":"Compile the same immutable snapshot.","kind":"recheck","state":"not_run"},{"command":["compiler","--analysis-only"],"expected":"No mismatch in the target.","kind":"compile","state":"not_run"}],"whyItMatters":"A nullable value is not a non-null String."}],"metrics":{"cpuMillis":null,"elapsedMillis":5,"outputBytes":0,"peakRssBytes":null},"providerId":"fixture-provider","providerVersion":"preview","rawDiagnostics":[{"location":null,"message":"Original compiler output.","severity":"error","upstreamId":null},{"location":{"column":34,"endColumn":38,"endLine":1,"excerpt":"val label: String = nullable","fileDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","line":1,"path":"Label.kt"},"message":"At the original location.","severity":"warning","upstreamId":"UP-1"}],"reasons":["original_execution_incomplete"],"status":"incomplete"},"reviewId":"review-original","sequence":7,"sourceSnapshotId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","state":"policy_reassessed","viewId":"policy-sha256:1111111111111111111111111111111111111111111111111111111111111111"}"""
    }
}
