package dev.ktchecker.compiler

import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Tiny byte accounting and owned filesystem controls only; no process or provider execution. */
class CompilerFixtureEvidenceTest {
    @Test fun normalWritesCannotConsumeReservedTerminalHeadroom() {
        val quota = CompilerFixtureEvidenceQuota(100, 20)
        quota.beginWrite(80).commit()
        assertEquals(80L, quota.retainedBytes)
        assertEquals(CompilerFixtureStopReason.EVIDENCE_BUDGET,
            assertFailsWith<CompilerFixtureStopped> { quota.beginWrite(1) }.reason)
        quota.beginWrite(20, terminal = true).commit()
        assertEquals(100L, quota.retainedBytes)
        assertFailsWith<CompilerFixtureStopped> { quota.beginWrite(1, terminal = true) }
    }

    @Test fun jobReservationPreflightsRawCapacityAndCannotBeSpentByOtherJobsOrOrdinaryWrites() {
        val quota = CompilerFixtureEvidenceQuota(100, 20)
        val job = quota.reserveJob(60)
        assertFailsWith<CompilerFixtureStopped> { quota.reserveJob(21) }
        assertFailsWith<CompilerFixtureStopped> { quota.beginWrite(21) }
        quota.beginWrite(35, reservation = job).commit()
        assertEquals(35L, quota.retainedBytes)
        assertEquals(25L, quota.reservedBytes)
        assertFailsWith<CompilerFixtureStopped> { quota.beginWrite(26, reservation = job) }
        val other = CompilerFixtureEvidenceQuota(100, 20)
        assertFailsWith<CompilerFixtureStopped> { other.beginWrite(1, reservation = job) }
        job.close()
        assertEquals(0L, quota.reservedBytes)
        assertFailsWith<CompilerFixtureStopped> { quota.beginWrite(1, reservation = job) }
    }

    @Test fun atomicReplacementAccountsOldAndTemporaryBytesBeforeReleasingOldFile() {
        val quota = CompilerFixtureEvidenceQuota(100, 20)
        quota.includeExisting(10)
        quota.beginWrite(70).commit(replacedBytes = 10)
        assertEquals(70L, quota.retainedBytes)
        assertFailsWith<CompilerFixtureStopped> { quota.beginWrite(30) }
        quota.beginWrite(30, terminal = true).commit(replacedBytes = 70)
        assertEquals(30L, quota.retainedBytes)
        val incomplete = quota.beginWrite(50)
        incomplete.abort()
        assertEquals(0L, quota.reservedBytes)
        assertEquals(30L, quota.retainedBytes)
    }

    @Test fun partialWriteAccountingKeepsUnremovedReservedUpperBoundInsteadOfHidingBytes() {
        val quota = CompilerFixtureEvidenceQuota(100, 20)
        val job = quota.reserveJob(60)
        quota.beginWrite(40, reservation = job).commit() // Accounting control, not a fabricated raw file or execution.
        job.close()
        assertEquals(40L, quota.retainedBytes)
        assertEquals(0L, quota.reservedBytes)
        assertFailsWith<CompilerFixtureStopped> { quota.ensureRoom(Long.MAX_VALUE) }
    }

    @Test fun immutableCheckpointReferencesStayValidAcrossLatestConvenienceReplacement() = withRoot { repository ->
        val (store, run, latest) = store(repository)
        val first = run.resolve("checkpoints/0001-first/provenance-check.json")
        val second = run.resolve("checkpoints/0002-second/provenance-check.json")
        val original = "{\"passed\":true}\n".toByteArray()
        store.writeNew(first, original)
        val reference = store.reference(first)
        store.replaceLatest(latest, "first latest\n".toByteArray())
        store.writeNew(second, "{\"passed\":false}\n".toByteArray())
        store.replaceLatest(latest, "second latest\n".toByteArray())
        assertEquals(reference, store.reference(first))
        assertEquals(Digests.sha256(original).removePrefix("sha256:"), reference["sha256"])
        assertContentEquals(original, Files.readAllBytes(first))
        assertFails { store.writeNew(first, "overwritten".toByteArray()) }
        assertFails { store.reference(latest) }
        assertFails { store.replaceLatest(first, "not allowed".toByteArray()) }
        assertTrue(store.immutableFilesUnder(run).containsAll(listOf(first, second)))
    }

    @Test fun evidenceCapStopsOrdinaryPersistenceButStillPermitsSmallTruthfulTerminalReceipt() = withRoot { repository ->
        val run = Files.createDirectory(repository.resolve("run"))
        val store = CompilerFixtureEvidence(repository, run, null, maximumBytes = 4_216, terminalReserveBytes = 32)
        store.writeNew(run.resolve("raw.txt"), ByteArray(88) { 1 })
        assertFailsWith<CompilerFixtureStopped> { store.writeNew(run.resolve("more.txt"), ByteArray(10)) }
        store.writeNew(run.resolve("terminal.txt"), "stopped\n".toByteArray(), terminal = true)
        assertEquals(4_192L, store.quota.retainedBytes)
        assertFalse(Files.exists(run.resolve("more.txt")))
        assertTrue(Files.exists(run.resolve("terminal.txt")))
    }

    @Test fun corruptedOrReplacedEvidenceCannotKeepItsOldHashReference() = withRoot { repository ->
        val (store, run, _) = store(repository)
        val receipt = run.resolve("receipt.json")
        store.writeNew(receipt, "authored\n".toByteArray())
        Files.writeString(receipt, "changed!\n")
        assertFails { store.reference(receipt) }
        Files.move(receipt, run.resolve("retained-original-inode"))
        Files.writeString(receipt, "authored\n")
        assertFails { store.reference(receipt) }
    }

    @Test fun linkedRegisteredParentsAndReplacedRunRootsNeverRedirectWrites() = withRoot { repository ->
        assumeTrue(repository.fileSystem.supportedFileAttributeViews().contains("posix"), "Link controls require POSIX")
        val (store, run, _) = store(repository)
        val checkpoints = store.directory(run.resolve("checkpoints"))
        val outside = Files.createDirectory(repository.resolve("outside"))
        Files.writeString(outside.resolve("sentinel"), "must remain")
        Files.move(checkpoints, run.resolve("saved-checkpoints"))
        Files.createSymbolicLink(checkpoints, outside)
        assertFails { store.writeNew(checkpoints.resolve("receipt.json"), byteArrayOf(1)) }
        assertFalse(Files.exists(outside.resolve("receipt.json")))
        Files.move(run, repository.resolve("saved-run"))
        Files.createDirectory(run)
        assertFails { store.writeNew(run.resolve("new.json"), byteArrayOf(1)) }
        assertFalse(Files.exists(run.resolve("new.json")))
        assertEquals("must remain", Files.readString(outside.resolve("sentinel")))
    }

    @Test fun ownerPrivateMarkerIsCooperativeStopOnlyAndLinkedMarkersAreRejectedUnread() = withRoot { repository ->
        val (store, run, latest) = store(repository)
        store.directory(run.resolve("control"))
        assertFalse(store.ownerStopRequested())
        val stop = run.resolve("control/stop")
        Files.writeString(stop, "owner requests stop")
        assertTrue(store.ownerStopRequested())
        Files.delete(stop)
        assumeTrue(repository.fileSystem.supportedFileAttributeViews().contains("posix"), "Linked marker requires POSIX")
        val before = Files.readAllBytes(latest)
        Files.createSymbolicLink(stop, latest)
        assertFails { store.ownerStopRequested() }
        assertContentEquals(before, Files.readAllBytes(latest))
    }

    private fun store(repository: Path): Triple<CompilerFixtureEvidence, Path, Path> {
        val run = Files.createDirectory(repository.resolve("run"))
        val latest = repository.resolve("latest.json")
        Files.writeString(latest, "old\n")
        return Triple(CompilerFixtureEvidence(repository, run, latest), run, latest)
    }

    private fun withRoot(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("ktc-bounded-evidence-unit-").toRealPath()
        try { action(root) }
        finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
