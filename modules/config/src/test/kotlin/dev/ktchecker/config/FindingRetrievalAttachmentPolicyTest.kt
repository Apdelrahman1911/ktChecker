package dev.ktchecker.config

import dev.ktchecker.contracts.*
import kotlinx.serialization.encodeToString
import kotlin.test.*

/** Raw-policy regression controls only; no native Detekt execution or semantic-equivalence proof. */
class FindingRetrievalAttachmentPolicyTest {
    private val reportDigest = Digests.sha256("complete upstream report bytes")
    private val firstPath = "/outputs/work-a/attempt-1/provider-a/detekt.sarif"
    private val secondPath = "/outputs/work-b/attempt-2/provider-b/detekt.sarif"
    private fun attached(fixture: ConfigFixture, locator: String = firstPath): Finding = fixture.finding().copy(
        evidence = listOf(Evidence("upstream_sarif", "Complete upstream report", digest = reportDigest)),
        retrievalAttachments = listOf(EvidenceRetrievalAttachment("upstream_sarif", reportDigest, locator)),
    )

    private fun forEachOwnerReason(block: (ConfigFixture, SuppressionReason, PolicyDisposition) -> Unit) {
        val cases = listOf(
            SuppressionReason.ACCEPTED_RISK to PolicyDisposition.ACCEPTED,
            SuppressionReason.REPORTED_FALSE_POSITIVE to PolicyDisposition.SUPPRESSED,
        )
        for ((reason, disposition) in cases) ConfigFixture().use { fixture -> block(fixture, reason, disposition) }
    }

    @Test fun locatorOnlyRerouteDoesNotReopenRawAcceptanceButFullFindingBytesChange(): Unit = forEachOwnerReason { fixture, reason, disposition ->
        val original = attached(fixture)
        val before = fixture.store.capture(fixture.binding)
        val selector = fixture.store.findingSelector(before, fixture.input(before, original), original)
        assertNull(selector.anchor.verifiedIdentity, "This is the conservative raw identity branch")
        val accepted = fixture.suppress(selector = selector, reason = reason)
        val rerouted = original.copy(retrievalAttachments = listOf(original.retrievalAttachments.single().copy(artifactRef = secondPath)))
        val originalInput = fixture.input(accepted, original).let { input -> input.copy(
            context = input.context.copy(compilationFiles = listOf(original.location.path)), outputRoot = "/outputs/work-a/attempt-1",
        ) }
        val changedInput = originalInput.copy(snapshot = originalInput.snapshot.copy(
            snapshotId = Digests.sha256("captured unrelated file changed"),
            files = originalInput.snapshot.files + SnapshotFile("unrelated.txt", Digests.sha256("unrelated"), 9),
        ), outputRoot = "/outputs/work-b/attempt-2")
        assertNotEquals(originalInput.analysisKey(), changedInput.analysisKey())
        assertEquals(original.evidence, rerouted.evidence)
        assertNotEquals(ContractJson.encodeToString(original), ContractJson.encodeToString(rerouted))
        assertEquals(disposition, fixture.store.explain(accepted, originalInput, original).disposition)
        assertEquals(disposition, fixture.store.explain(accepted, changedInput, rerouted).disposition)
        assertEquals(selector.fingerprint, fixture.store.findingSelector(accepted, changedInput, rerouted).fingerprint)
        // Retrieval duplicates/order carry delivery bytes, never deduplicate material Evidence.
        val duplicatedLocators = rerouted.copy(retrievalAttachments = listOf(
            rerouted.retrievalAttachments.single(), rerouted.retrievalAttachments.single(), original.retrievalAttachments.single()))
        assertEquals(selector.fingerprint, fixture.store.findingSelector(accepted, changedInput, duplicatedLocators).fingerprint)
    }

    @Test fun completeReportMaterialEvidenceAndSourceChangesStillReopenRawAcceptance(): Unit = forEachOwnerReason { fixture, reason, disposition ->
        val original = attached(fixture)
        val before = fixture.store.capture(fixture.binding)
        val selector = fixture.store.findingSelector(before, fixture.input(before, original), original)
        val accepted = fixture.suppress(selector = selector, reason = reason)
        assertEquals(disposition, fixture.store.explain(accepted, fixture.input(accepted, original), original).disposition)
        val changedReport = Digests.sha256("complete report bytes changed outside the selected diagnostic")
        val variants = listOf(
            "whole report churn is intentionally material" to original.copy(
                evidence = listOf(original.evidence.single().copy(digest = changedReport)),
                retrievalAttachments = listOf(original.retrievalAttachments.single().copy(digest = changedReport)),
            ),
            "evidence summary" to original.copy(evidence = listOf(original.evidence.single().copy(summary = "Changed material description"))),
            "duplicate material evidence" to original.copy(evidence = original.evidence + original.evidence),
            "primary source bytes" to original.copy(location = original.location.copy(fileDigest = Digests.sha256("changed source"))),
            "primary source range" to original.copy(location = original.location.copy(column = 1)),
            "related source evidence" to original.copy(relatedLocations = listOf(original.location.copy(path = "src/Related.kt"))),
        )
        for ((label, current) in variants) {
            // Bind every represented source first: rejection must be the raw fingerprint
            // change, not an invalid manifest for the related-location control.
            val files = (listOf(current.location) + current.relatedLocations).distinctBy { it.path }
                .map { SnapshotFile(it.path, it.fileDigest, 20) }
            val input = fixture.input(accepted, current).let { captured -> captured.copy(
                snapshot = captured.snapshot.copy(snapshotId = Digests.sha256(ContractJson.encodeToString(files)), files = files),
            ) }
            assertNotEquals(selector.fingerprint, fixture.store.findingSelector(accepted, input, current).fingerprint, label)
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(accepted, input, current).disposition, label)
        }
    }

    @Test fun arbitraryEvidenceArtifactRefsRemainMaterialNoGlobalExclusion(): Unit = forEachOwnerReason { fixture, reason, disposition ->
        val additional = Evidence("material_artifact", "Owner selected material", artifactRef = "material/a", digest = Digests.sha256("material"))
        val original = attached(fixture).let { it.copy(evidence = it.evidence + additional) }
        val before = fixture.store.capture(fixture.binding)
        val selector = fixture.store.findingSelector(before, fixture.input(before, original), original)
        val accepted = fixture.suppress(selector = selector, reason = reason)
        assertEquals(disposition, fixture.store.explain(accepted, fixture.input(accepted, original), original).disposition)
        val changed = original.copy(evidence = listOf(original.evidence.first(), additional.copy(artifactRef = "material/b")))
        assertEquals(original.retrievalAttachments, changed.retrievalAttachments)
        assertNotEquals(selector.fingerprint, fixture.store.findingSelector(accepted, fixture.input(accepted, changed), changed).fingerprint)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(accepted, fixture.input(accepted, changed), changed).disposition)
    }

    @Test fun oldLocatorBearingExactAcceptanceReopensOnceAndTheRecordIsNotRebound(): Unit = forEachOwnerReason { fixture, reason, disposition ->
        // Previous Detekt shape under the SAME selected raw policy fingerprint schema.
        val legacy = fixture.finding().copy(evidence = listOf(Evidence(
            "upstream_sarif", "Complete upstream report", artifactRef = firstPath, digest = reportDigest,
        )))
        val before = fixture.store.capture(fixture.binding)
        val selector = fixture.store.findingSelector(before, fixture.input(before, legacy), legacy)
        val accepted = fixture.suppress(selector = selector, reason = reason)
        val retained = ContractJson.encodeToString(accepted.shared.suppressions)
        assertEquals(disposition, fixture.store.explain(accepted, fixture.input(accepted, legacy), legacy).disposition)
        for (locator in listOf(firstPath, secondPath)) {
            val current = attached(fixture, locator)
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(accepted, fixture.input(accepted, current), current).disposition)
            assertNotEquals(selector.fingerprint, fixture.store.findingSelector(accepted, fixture.input(accepted, current), current).fingerprint)
        }
        val after = fixture.store.capture(fixture.binding)
        assertEquals(accepted.revisionToken, after.revisionToken)
        assertEquals(retained, ContractJson.encodeToString(after.shared.suppressions))
        assertEquals(selector, after.shared.suppressions.single().spec.selector)
    }
}
