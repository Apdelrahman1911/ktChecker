package dev.ktchecker.cli

import dev.ktchecker.config.ConfigBinding
import dev.ktchecker.config.ConfigStore
import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Archive unit controls only; no provider, execution certificate or power-loss qualification. */
class WorkbenchPolicyCaptureArchiveTest {
    private class Fixture {
        val state = PrivateState(Files.createTempDirectory("ktchecker-policy-archive-"))
        val configs = ConfigStore(state.namespace("configuration"))
        val original = configs.exportSnapshot(configs.capture(ConfigBinding(state.ownerId, "project", "tree", "lineage")))
        val other = original.copy(capturedAtEpochMillis = original.capturedAtEpochMillis + 1)
        val archive = WorkbenchPolicyCaptureArchive(state)
        val root: Path get() = state.root.resolve("policy-captures")
        fun path(id: String): Path = root.resolve(id.removePrefix("sha256:") + ".json")
        fun bytes(): Map<String, String> = if (!Files.exists(root)) emptyMap() else Files.list(root).use { entries ->
            entries.toList().associate { it.fileName.toString() to Digests.sha256(Files.readAllBytes(it)) }
        }
    }

    @Test fun capturesAreContentAddressedExactIdempotentAndRestartReadable() {
        val f = Fixture()
        val ids = f.archive.retain(f.original, f.other)
        assertNotEquals(ids.first, ids.second, "Exact capture time is retained, not normalized away")
        assertTrue(Digests.valid(ids.first) && Digests.valid(ids.second))
        val before = f.bytes()
        assertEquals(2, before.size)
        assertEquals(ids, f.archive.retain(f.original, f.other))
        assertEquals(before, f.bytes())
        val reopened = WorkbenchPolicyCaptureArchive(PrivateState(f.state.root))
        assertEquals(f.original, reopened.restore(ids.first))
        assertEquals(f.other, reopened.restore(ids.second))
        val single = reopened.retain(f.original, f.original)
        assertEquals(ids.first to ids.first, single)
        assertEquals(before, f.bytes(), "Equal captures cannot churn or multiply retained history")
    }

    @Test fun missingRestoreDoesNotCreateAnArchiveOrSubstituteTheCurrentPolicy() {
        val f = Fixture()
        assertFalse(Files.exists(f.root))
        assertEquals("policy_archive_missing", assertFailsWith<CliProblem> {
            f.archive.restore(Digests.sha256("absent-record"))
        }.code)
        assertFalse(Files.exists(f.root))
        assertEquals(f.original.revisionToken, f.configs.capture(f.original.binding).revisionToken)
    }

    @Test fun pairQuotaFailureWritesNeitherNewMemberAndNeverEvictsOldCaptures() {
        val f = Fixture()
        val constrained = WorkbenchPolicyCaptureArchive(f.state, PolicyCaptureArchiveLimits(maxDocuments = 1))
        assertEquals("policy_archive_limit", assertFailsWith<CliProblem> { constrained.retain(f.original, f.other) }.code)
        assertTrue(f.bytes().isEmpty(), "Preflight accounts for both new members before either write")
        val ids = constrained.retain(f.original, f.original)
        val before = f.bytes()
        assertEquals("policy_archive_limit", assertFailsWith<CliProblem> { constrained.retain(f.original, f.other) }.code)
        assertEquals(before, f.bytes())
        assertEquals(f.original, constrained.restore(ids.first))
    }

    @Test fun tamperedOrTruncatedCaptureIsNotReplacedByAnIdenticalRetainRequest() {
        val f = Fixture()
        val ids = f.archive.retain(f.original, f.other)
        Files.writeString(f.path(ids.second), "{}")
        val before = f.bytes()
        assertEquals("policy_archive_identity_mismatch", assertFailsWith<CliProblem> { f.archive.restore(ids.second) }.code)
        assertEquals("policy_archive_identity_mismatch", assertFailsWith<CliProblem> { f.archive.retain(f.original, f.other) }.code)
        assertEquals(before, f.bytes(), "Corruption is preserved rather than silently repaired")
        assertEquals(f.original, f.archive.restore(ids.first))
    }

    @Test fun totalByteQuotaPreflightsThePairEvenWhenEachIndividualCaptureFits() {
        val f = Fixture()
        val measured = f.archive.retain(f.original, f.other)
        val firstSize = Files.size(f.path(measured.first))
        val secondSize = Files.size(f.path(measured.second))
        val budget = firstSize + secondSize - 1
        assertTrue(firstSize <= budget && secondSize <= budget)
        // Preserve the measurement records in this private fixture, leaving a fresh namespace.
        val reference = f.state.root.resolve("retained-measurement-archive")
        Files.move(f.root, reference)
        val bounded = WorkbenchPolicyCaptureArchive(f.state, PolicyCaptureArchiveLimits(maxBytes = budget))
        assertEquals("policy_archive_limit", assertFailsWith<CliProblem> { bounded.retain(f.original, f.other) }.code)
        assertTrue(f.bytes().isEmpty(), "A pair that does not fit must not install its first member")
        val single = bounded.retain(f.original, f.original)
        val before = f.bytes()
        assertEquals("policy_archive_limit", assertFailsWith<CliProblem> { bounded.retain(f.original, f.other) }.code)
        assertEquals(before, f.bytes())
        assertEquals(f.original, bounded.restore(single.first))
        assertEquals(2, Files.list(reference).use { it.count().toInt() })
    }

    @Test fun ownerAndDocumentByteBoundsRejectBeforeAnyCaptureIsWritten() {
        val f = Fixture()
        val tiny = WorkbenchPolicyCaptureArchive(f.state, PolicyCaptureArchiveLimits(maxDocumentBytes = 1))
        assertEquals("policy_archive_document_limit", assertFailsWith<CliProblem> { tiny.retain(f.original, f.original) }.code)
        val foreign = f.original.copy(binding = f.original.binding.copy(ownerId = "another-owner"))
        assertEquals("permission_denied", assertFailsWith<CliProblem> { f.archive.retain(f.original, foreign) }.code)
        assertFalse(Files.exists(f.root))
    }

    @Test fun unexpectedArchiveEntriesFailClosedRatherThanBypassAccounting() {
        val f = Fixture()
        val ids = f.archive.retain(f.original, f.original)
        Files.writeString(f.root.resolve("unaccounted-policy.txt"), "retained unknown artifact")
        val before = f.bytes()
        assertEquals("policy_archive_unavailable", assertFailsWith<CliProblem> { f.archive.retain(f.original, f.other) }.code)
        assertEquals(before, f.bytes())
        assertEquals(f.original, f.archive.restore(ids.first), "An unrelated inventory problem does not rewrite an exact historical capture")
    }
}
