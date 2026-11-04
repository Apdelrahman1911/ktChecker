package dev.ktchecker.config

import dev.ktchecker.contracts.AnalysisInput
import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Coverage
import dev.ktchecker.contracts.Digests
import dev.ktchecker.contracts.Evidence
import dev.ktchecker.contracts.Finding
import dev.ktchecker.contracts.ProviderResult
import dev.ktchecker.contracts.RawDiagnostic
import dev.ktchecker.contracts.SnapshotFile
import dev.ktchecker.contracts.SourceLocation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic matching controls only; no compiler/provider execution or qualification. */
class MatchingScopeRegressionTest {
    @Test fun exactAcceptanceBindsEveryRelatedSourceDimensionInBothLayers() {
        for (layer in ConfigLayer.entries) ConfigFixture().use { fixture ->
            val finding = fixture.finding().copy(relatedLocations = listOf(related()))
            val before = fixture.store.capture(fixture.binding)
            val exact = fixture.store.findingSelector(before, boundInput(fixture, before, finding), finding)
            val captured = fixture.suppress(selector = exact, layer = layer)
            assertEquals(PolicyDisposition.ACCEPTED,
                fixture.store.explain(captured, boundInput(fixture, captured, finding), finding).disposition)

            val original = finding.relatedLocations.single()
            val variants = listOf(
                "path" to listOf(original.copy(path = "src/Other.kt")),
                "raw bytes" to listOf(original.copy(fileDigest = Digests.sha256("materially changed other declaration"))),
                "line" to listOf(original.copy(line = 2, endLine = 2)),
                "column" to listOf(original.copy(column = 1)),
                "end line" to listOf(original.copy(endLine = 2)),
                "end column" to listOf(original.copy(endColumn = 4)),
                "duplicate occurrence" to listOf(original, original),
                "removed occurrence" to emptyList(),
                "added occurrence" to listOf(original, related(path = "src/Additional.kt")),
            )
            for ((dimension, locations) in variants) {
                val current = finding.copy(relatedLocations = locations)
                val input = boundInput(fixture, captured, current)
                // Nothing in the primary source, editable display fingerprint or ordinary
                // evidence changed: only the related cause differs.
                assertEquals(finding.location, current.location, dimension)
                assertEquals(finding.fingerprint, current.fingerprint, dimension)
                assertEquals(finding.evidence, current.evidence, dimension)
                assertNotEquals(exact.fingerprint, fixture.store.findingSelector(captured, input, current).fingerprint, dimension)
                val explanation = fixture.store.explain(captured, input, current)
                assertEquals(PolicyDisposition.OPEN, explanation.disposition, dimension)
                assertEquals("fingerprint_changed", explanation.candidates.single().state, dimension)
            }
            assertEquals(captured.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
        }
    }

    @Test fun relatedOrderingAndExcerptsAreDisplayOnlyButDuplicatesRemainMaterial(): Unit = ConfigFixture().use { fixture ->
        val first = related()
        val second = related(path = "src/Second.kt", code = "fun second() = 2")
        val finding = fixture.finding().copy(
            evidence = listOf(Evidence("static_rule", "First", digest = Digests.sha256("first")),
                Evidence("related_rule", "Second", digest = Digests.sha256("second"))),
            relatedLocations = listOf(first, first, second),
        )
        val before = fixture.store.capture(fixture.binding)
        val exact = fixture.store.findingSelector(before, boundInput(fixture, before, finding), finding)
        val captured = fixture.suppress(selector = exact)
        val reordered = finding.copy(ruleId = "upstream/demo", fingerprint = "different editable fingerprint",
            evidence = finding.evidence.reversed(), location = finding.location.copy(excerpt = "new primary excerpt"),
            relatedLocations = finding.relatedLocations.reversed().map { it.copy(excerpt = "new related excerpt") })
        val reorderedInput = boundInput(fixture, captured, reordered)
        assertEquals(exact.fingerprint, fixture.store.findingSelector(captured, reorderedInput, reordered).fingerprint)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(captured, reorderedInput, reordered).disposition)

        val fewerOccurrences = finding.copy(relatedLocations = listOf(first, second))
        val fewerInput = boundInput(fixture, captured, fewerOccurrences)
        assertNotEquals(exact.fingerprint, fixture.store.findingSelector(captured, fewerInput, fewerOccurrences).fingerprint)
        assertEquals(PolicyDisposition.OPEN, fixture.store.explain(captured, fewerInput, fewerOccurrences).disposition)
    }

    @Test fun malformedMissingStaleOrAmbiguousRelatedSourcesCannotCreateSelectors(): Unit = ConfigFixture().use { fixture ->
        val captured = fixture.suppress()
        val finding = fixture.finding().copy(relatedLocations = listOf(related()))
        val valid = boundInput(fixture, captured, finding)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(captured, valid, finding).disposition)
        val other = finding.relatedLocations.single()
        val missing = valid.copy(snapshot = valid.snapshot.copy(files = valid.snapshot.files.filter { it.path != other.path }))
        val ambiguous = valid.copy(snapshot = valid.snapshot.copy(files = valid.snapshot.files + valid.snapshot.files.single { it.path == other.path }))
        val invalidSources = listOf(
            "missing related file" to (missing to finding),
            "ambiguous related file" to (ambiguous to finding),
            "stale related bytes" to (valid to finding.copy(relatedLocations = listOf(other.copy(fileDigest = Digests.sha256("not these frozen bytes"))))),
            "invalid related digest" to (valid to finding.copy(relatedLocations = listOf(other.copy(fileDigest = "not-a-digest")))),
            "relative traversal" to (valid to finding.copy(relatedLocations = listOf(other.copy(path = "../Outside.kt")))),
            "absolute related path" to (valid to finding.copy(relatedLocations = listOf(other.copy(path = "/Outside.kt")))),
            "invalid related line" to (valid to finding.copy(relatedLocations = listOf(other.copy(line = 0)))),
            "invalid related column" to (valid to finding.copy(relatedLocations = listOf(other.copy(column = -1)))),
            "reversed related range" to (valid to finding.copy(relatedLocations = listOf(other.copy(column = 4, endColumn = 3)))),
        )
        for ((problem, candidate) in invalidSources) {
            val (input, current) = candidate
            assertFailsWith<ConfigValidationException>(problem) { fixture.store.findingSelector(captured, input, current) }
            val explanation = fixture.store.explain(captured, input, current)
            assertEquals(PolicyDisposition.OPEN, explanation.disposition, problem)
            assertTrue(explanation.explanation.contains("source bytes/range"), problem)
        }
        assertEquals(captured.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
    }

    @Test fun staleRelatedSourcesCannotBeHiddenByRuleDisablingAndApplyPreservesFailures(): Unit = ConfigFixture().use { fixture ->
        val finding = fixture.finding().copy(relatedLocations = listOf(related()))
        val captured = fixture.mutate(ConfigAction.SetRuleEnabled(RULE, false))
        val valid = boundInput(fixture, captured, finding)
        assertEquals(PolicyDisposition.DISABLED, fixture.store.explain(captured, valid, finding).disposition)
        val incomplete = valid.copy(snapshot = valid.snapshot.copy(files = valid.snapshot.files.filter { it.path == finding.location.path }))
        val result = ProviderResult("test-static", "failed", listOf(finding.copy(disposition = "disabled")),
            listOf(Coverage(RULE, finding.context, "failed", "Synthetic failure remains a failure")),
            reasons = listOf("synthetic worker failure"), rawDiagnostics = listOf(RawDiagnostic("retained raw failure")))
        val applied = fixture.store.apply(captured, incomplete, result)
        assertEquals(PolicyDisposition.OPEN.wireValue, applied.findings.single().disposition)
        assertEquals(result.status, applied.status)
        assertEquals(result.coverage, applied.coverage)
        assertEquals(result.reasons, applied.reasons)
        assertEquals(result.rawDiagnostics, applied.rawDiagnostics)
        assertEquals(finding.relatedLocations, applied.findings.single().relatedLocations)
    }

    @Test fun explicitBaselineDoesNotAcceptAChangedRelatedCause(): Unit = ConfigFixture().use { fixture ->
        val finding = fixture.finding().copy(relatedLocations = listOf(related()))
        val before = fixture.store.capture(fixture.binding)
        val exact = fixture.store.findingSelector(before, boundInput(fixture, before, finding), finding)
        val captured = fixture.mutate(ConfigAction.AddBaseline(BaselineSpec("related-baseline", exact,
            "Explicit synthetic owner baseline of this particular multi-location finding")))
        assertEquals(PolicyDisposition.BASELINED,
            fixture.store.explain(captured, boundInput(fixture, captured, finding), finding).disposition)
        val changed = finding.copy(relatedLocations = listOf(related(code = "fun related() = differentCause()")))
        assertEquals(PolicyDisposition.OPEN,
            fixture.store.explain(captured, boundInput(fixture, captured, changed), changed).disposition)
        assertEquals(captured.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
    }

    @Test fun ruleScopedPoliciesStillCoverValidRelatedChangesButNotUnboundEvidence() {
        for (scopeKind in listOf("project", "file", "range")) ConfigFixture().use { fixture ->
            val finding = fixture.finding().copy(relatedLocations = listOf(related()))
            val scope = when (scopeKind) {
                "file" -> SuppressionScope.File(finding.location.path, finding.location.fileDigest)
                "range" -> SuppressionScope.Range(finding.location.path, finding.location.fileDigest, 1, 0, 1, 3)
                else -> SuppressionScope.Project
            }
            val captured = fixture.suppress(scope = scope)
            val changed = finding.copy(relatedLocations = listOf(related(code = "fun related() = anotherCause()")))
            val valid = boundInput(fixture, captured, changed)
            // Rule-scoped acceptance intentionally covers every finding in the selected
            // scope; it is not an exact-finding acceptance.
            assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(captured, valid, changed).disposition, scopeKind)
            val unbound = valid.copy(snapshot = valid.snapshot.copy(files = valid.snapshot.files.filter { it.path == finding.location.path }))
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(captured, unbound, changed).disposition, scopeKind)
        }
    }

    @Test fun onlyFreshVerifiedEvidenceCanPreserveAnExactAcceptanceAcrossRelatedMovement() {
        var evidence = Digests.sha256("verified unchanged multi-location evidence")
        val verifier = FindingIdentityVerifier { binding, input, finding, rule ->
            testIdentity(binding, input, finding, rule, evidence = evidence)
        }
        ConfigFixture(identityVerifier = verifier).use { fixture ->
            val finding = fixture.finding().copy(relatedLocations = listOf(related()))
            val before = fixture.store.capture(fixture.binding)
            val exact = fixture.store.findingSelector(before, boundInput(fixture, before, finding), finding)
            assertNotNull(exact.anchor.verifiedIdentity)
            val captured = fixture.suppress(selector = exact)
            val moved = fixture.finding(path = "src/Moved.kt", code = "\nfun example() = 1", line = 2).copy(
                relatedLocations = listOf(related(path = "src/MovedRelated.kt", code = "\n\nfun related() = 1", line = 3)))
            val input = boundInput(fixture, captured, moved)
            // This injected test verifier independently certifies unchanged symbol/material/
            // evidence identity; path/line movement alone is not a material change.
            assertEquals(exact.fingerprint, fixture.store.findingSelector(captured, input, moved).fingerprint)
            assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(captured, input, moved).disposition)
            val missing = input.copy(snapshot = input.snapshot.copy(files = input.snapshot.files.filter { it.path == moved.location.path }))
            assertFailsWith<ConfigValidationException> { fixture.store.findingSelector(captured, missing, moved) }
            assertEquals(PolicyDisposition.OPEN, fixture.store.explain(captured, missing, moved).disposition)

            evidence = Digests.sha256("verified materially changed related cause")
            val changed = fixture.store.explain(captured, input, moved)
            assertEquals(PolicyDisposition.OPEN, changed.disposition)
            assertEquals("symbol_or_material_evidence_changed", changed.candidates.single().state)
        }
    }

    @Test fun primaryOnlyRawProjectionIncludesAVersionedEmptyRelatedList(): Unit = ConfigFixture().use { fixture ->
        val finding = fixture.finding()
        val before = fixture.store.capture(fixture.binding)
        val exact = fixture.store.findingSelector(before, fixture.input(before, finding), finding)
        val legacyEvidence = finding.evidence.map { ContractJson.encodeToJsonElement(it) }.sortedBy { Digests.canonical(it) }
        val currentEvidence = buildJsonObject {
            put("schema", "ktchecker/finding-policy-evidence-2")
            put("evidence", JsonArray(legacyEvidence))
            put("relatedLocations", JsonArray(emptyList()))
        }
        assertNotEquals(Digests.sha256(Digests.canonical(JsonArray(legacyEvidence))), exact.anchor.evidenceDigest)
        assertEquals(Digests.sha256(Digests.canonical(currentEvidence)), exact.anchor.evidenceDigest)
        val captured = fixture.suppress(selector = exact)
        assertEquals(exact.fingerprint, fixture.store.findingSelector(captured, fixture.input(captured, finding), finding).fingerprint)
        assertEquals(PolicyDisposition.ACCEPTED, fixture.store.explain(captured, fixture.input(captured, finding), finding).disposition)
    }

    @Test fun allLegacyRawExactAnchorsRemainInspectableButReopenEvenAfterRelatedRemoval() {
        for (hadRelated in listOf(false, true)) ConfigFixture().use { fixture ->
            val finding = fixture.finding().copy(relatedLocations = if (hadRelated) listOf(related()) else emptyList())
            val before = fixture.store.capture(fixture.binding)
            val legacy = legacyRawSelector(fixture, before, finding)
            // This is an authored synthetic legacy selector, not a rewritten historical receipt.
            val captured = fixture.suppress(selector = legacy)
            val restored = fixture.store.restoreSnapshot(fixture.store.exportSnapshot(captured), captured.revisionToken)
            assertEquals(captured.shared.suppressions, restored.shared.suppressions)
            assertEquals(captured.shared.auditEvents, restored.shared.auditEvents)
            assertEquals(legacy, restored.shared.suppressions.single().spec.selector)
            assertEquals(captured.revisionToken, restored.revisionToken)
            for (locations in listOf(emptyList(), listOf(related()), listOf(related(code = "fun related() = changedCause()")))) {
                val current = finding.copy(relatedLocations = locations)
                val explanation = fixture.store.explain(restored, boundInput(fixture, restored, current), current)
                assertEquals(PolicyDisposition.OPEN, explanation.disposition)
                assertEquals("fingerprint_changed", explanation.candidates.single().state)
            }
            assertEquals(captured.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
        }
    }

    @Test fun allLegacyRawBaselinesRemainInspectableButReopenPrimaryAndRelatedFindings() {
        for (hadRelated in listOf(false, true)) ConfigFixture().use { fixture ->
            val finding = fixture.finding().copy(relatedLocations = if (hadRelated) listOf(related()) else emptyList())
            val before = fixture.store.capture(fixture.binding)
            val legacy = legacyRawSelector(fixture, before, finding)
            val captured = fixture.mutate(ConfigAction.AddBaseline(BaselineSpec("legacy-baseline", legacy,
                "Synthetic legacy baseline is retained, not silently migrated into new consent")))
            val restored = fixture.store.restoreSnapshot(fixture.store.exportSnapshot(captured), captured.revisionToken)
            assertEquals(captured.shared.baselines, restored.shared.baselines)
            assertEquals(captured.shared.auditEvents, restored.shared.auditEvents)
            assertEquals(captured.revisionToken, restored.revisionToken)
            for (locations in listOf(emptyList(), listOf(related()), listOf(related(code = "fun related() = changedCause()")))) {
                val current = finding.copy(relatedLocations = locations)
                assertEquals(PolicyDisposition.OPEN,
                    fixture.store.explain(restored, boundInput(fixture, restored, current), current).disposition)
            }
            assertEquals(captured.revisionToken, fixture.store.capture(fixture.binding).revisionToken)
        }
    }

    private fun related(path: String = "src/Related.kt", code: String = "fun related() = 1", line: Int = 1) =
        SourceLocation(path, Digests.sha256(code), line, 0, line, 3, "related excerpt")

    private fun legacyRawSelector(fixture: ConfigFixture, snapshot: ConfigSnapshot, finding: Finding): PolicySelector.ExactFinding {
        val current = fixture.store.findingSelector(snapshot, boundInput(fixture, snapshot, finding), finding)
        val legacyEvidence = finding.evidence.map { ContractJson.encodeToJsonElement(it) }.sortedBy { Digests.canonical(it) }
        val legacyAnchor = current.anchor.copy(evidenceDigest = Digests.sha256(Digests.canonical(JsonArray(legacyEvidence))))
        val legacy = current.copy(anchor = legacyAnchor, fingerprint = fingerprint(legacyAnchor))
        assertNotEquals(current.fingerprint, legacy.fingerprint)
        return legacy
    }

    private fun boundInput(fixture: ConfigFixture, snapshot: ConfigSnapshot, finding: Finding): AnalysisInput {
        val base = fixture.input(snapshot, finding)
        val files = (listOf(finding.location) + finding.relatedLocations).distinctBy { it.path }
            .map { SnapshotFile(it.path, it.fileDigest, 20) }
        return base.copy(snapshot = base.snapshot.copy(
            snapshotId = Digests.sha256("synthetic multi-location snapshot:" + Digests.canonical(ContractJson.encodeToJsonElement(files))),
            files = files,
        ))
    }
}
