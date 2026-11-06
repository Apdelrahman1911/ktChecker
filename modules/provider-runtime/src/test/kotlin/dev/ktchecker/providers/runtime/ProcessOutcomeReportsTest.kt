package dev.ktchecker.providers.runtime

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileTime
import java.util.Comparator
import kotlin.test.*

/**
 * Real temporary filesystem DATA controls with deliberately synthetic outcomes. No subprocess,
 * model, compiler, successful execution, immutable output custody, or semantic authority is proved.
 * Open-channel decorators cause deterministic drift; these are not background race/ABA proofs.
 */
class ProcessOutcomeReportsTest {
    @Test fun `complete sorted inventory includes unknown names and exact binary and empty bytes`() = withReports { f ->
        val binary = byteArrayOf(0, 0xff.toByte(), 0xc3.toByte(), 0x28)
        Files.write(f.reports.resolve("z.json"), byteArrayOf(9))
        Files.write(f.reports.resolve("a-unrecognized.bin"), binary)
        Files.write(f.reports.resolve("empty.txt"), byteArrayOf())
        val result = f.outcome.readReports("reports")
        assertEquals(listOf("a-unrecognized.bin", "empty.txt", "z.json"), result.keys.toList())
        assertContentEquals(binary, result.getValue("a-unrecognized.bin"))
        assertContentEquals(byteArrayOf(), result.getValue("empty.txt"))
        assertContentEquals(byteArrayOf(9), result.getValue("z.json"))
    }

    @Test fun `declared nested directory uses immediate leaf keys only`() = withReports { f ->
        val nested = Files.createDirectory(f.reports.resolve("shards"))
        Files.write(nested.resolve("one.json"), byteArrayOf(1, 2))
        val result = f.outcome.readReports("reports/shards")
        assertEquals(setOf("one.json"), result.keys)
        assertContentEquals(byteArrayOf(1, 2), result.getValue("one.json"))
    }

    @Test fun `empty actual directory is data and not missing output`() = withReports { f ->
        assertTrue(f.outcome.readReports("reports").isEmpty())
        assertEquals("report_collection_missing_or_unsafe", failure { f.outcome.readReports("missing") })
    }

    @Test fun `exact count per file and aggregate limits succeed without truncation`() = withReports { f ->
        Files.write(f.reports.resolve("one"), byteArrayOf(1, 2))
        Files.write(f.reports.resolve("two"), byteArrayOf(3, 4))
        val result = f.outcome.readReports("reports", maxFiles = 2, maxFileBytes = 2, maxTotalBytes = 4)
        assertEquals(listOf("one", "two"), result.keys.toList())
        assertContentEquals(byteArrayOf(1, 2), result.getValue("one"))
        assertContentEquals(byteArrayOf(3, 4), result.getValue("two"))
    }

    @Test fun `invalid bounds are refused before even a missing root is inspected`() {
        val absent = ProcessOutcome("failed")
        val bounds = listOf(
            Triple(0, 1, 1), Triple(-1, 1, 1), Triple(1025, 1, 1), Triple(Int.MAX_VALUE, 1, 1),
            Triple(1, 0, 1), Triple(1, -1, 1), Triple(1, 16_777_217, 1), Triple(1, Int.MAX_VALUE, 1),
            Triple(1, 1, 0), Triple(1, 1, -1), Triple(1, 1, 16_777_217), Triple(1, 1, Int.MAX_VALUE),
        )
        for ((count, each, total) in bounds) {
            assertEquals("report_collection_limit", failure { absent.readReports("reports", count, each, total) })
        }
    }

    @Test fun `directory grammar depth size and malformed UTF16 fail before root IO`() {
        val absent = ProcessOutcome("failed")
        val directories = listOf(
            "", " ", ".", "..", "/reports", "../reports", "a/../b", "a/./b", "a//b", "a/", "a\\b", "a:b",
            "a/\u0000/b", "a/\u001f/b", "a/\u007f/b", "a".repeat(256), "a/".repeat(64) + "a", "a".repeat(4097),
            "\uD800", "x\uDC00", "\uD800x", "\uD800\uD800", "\uDC00\uD800",
        )
        for (directory in directories) {
            assertEquals("relative_path_invalid", failure { absent.readReports(directory) })
        }
    }

    @Test fun `no output root is unavailable rather than an empty collection`() {
        assertEquals("report_unavailable", failure { ProcessOutcome("failed").readReports("reports") })
    }

    @Test fun `missing and unequal original root identities cannot read data`() = withReports { f ->
        Files.write(f.reports.resolve("one.json"), byteArrayOf(1))
        assertEquals("report_root_replaced", failure { f.outcome.copy(outputIdentity = null).readReports("reports") })
        assertEquals("report_root_replaced", failure {
            f.outcome.copy(outputIdentity = f.outcome.outputIdentity + "-other").readReports("reports")
        })
        assertEquals("report_root_invalid", failure {
            f.outcome.copy(outputDirectory = f.work.resolve("absent")).readReports("reports")
        })
    }

    @Test fun `replaced output pathname cannot borrow its original directory identity`() = withReports { f ->
        Files.write(f.reports.resolve("one.json"), byteArrayOf(1))
        Files.move(f.root, f.work.resolve("held-original"))
        val replacement = Files.createDirectories(f.root.resolve("reports"))
        Files.write(replacement.resolve("one.json"), byteArrayOf(1))
        assertEquals("report_root_replaced", failure { f.outcome.readReports("reports") })
    }

    @Test fun `root symlink back to original directory is still refused`() = withReports { f ->
        val held = Files.move(f.root, f.work.resolve("held-original"))
        Files.createSymbolicLink(f.root, held)
        assertEquals("report_root_invalid", failure { f.outcome.readReports("reports") })
    }

    @Test fun `root replaced by a regular file is refused`() = withReports { f ->
        Files.move(f.root, f.work.resolve("held-original"))
        Files.write(f.root, byteArrayOf(1))
        assertEquals("report_root_invalid", failure { f.outcome.readReports("reports") })
    }

    @Test fun `symlink at selected or intermediate directory component is refused`() = withReports { f ->
        val outside = Files.createDirectory(f.work.resolve("outside"))
        val nested = Files.createDirectory(outside.resolve("nested"))
        Files.write(nested.resolve("one.json"), byteArrayOf(1))
        Files.createSymbolicLink(f.reports.resolve("alias"), outside)
        assertEquals("report_collection_directory_invalid", failure { f.outcome.readReports("reports/alias") })
        assertEquals("report_collection_directory_invalid", failure { f.outcome.readReports("reports/alias/nested") })
    }

    @Test fun `a regular file cannot be used as a report directory`() = withReports { f ->
        Files.write(f.reports.resolve("one.json"), byteArrayOf(1))
        assertEquals("report_collection_directory_invalid", failure { f.outcome.readReports("reports/one.json") })
    }

    @Test fun `symlink report member is never followed or silently omitted`() = withReports { f ->
        Files.write(f.reports.resolve("actual.json"), byteArrayOf(1))
        Files.createSymbolicLink(f.reports.resolve("alias.json"), Path.of("actual.json"))
        assertEquals("report_collection_member_invalid", failure { f.outcome.readReports("reports") })
    }

    @Test fun `nested member directory refuses the whole collection instead of recursing or filtering`() = withReports { f ->
        Files.write(f.reports.resolve("actual.json"), byteArrayOf(1))
        Files.createDirectory(f.reports.resolve("nested"))
        assertEquals("report_collection_member_invalid", failure { f.outcome.readReports("reports") })
    }

    @Test fun `unsafe member names refuse the whole collection`() {
        for (name in listOf("bad:name", "bad\\name", "bad\nname", " ")) withReports { f ->
            Files.write(f.reports.resolve("actual.json"), byteArrayOf(1))
            Files.write(f.reports.resolve(name), byteArrayOf(2))
            assertEquals("report_collection_member_invalid", failure { f.outcome.readReports("reports") })
        }
    }

    @Test fun `well formed supplementary Unicode names are preserved without rewriting`() = withReports { f ->
        val directory = "supplementary-\uD83D\uDE00"
        val name = "report-\uD83D\uDE00.json"
        val nested = Files.createDirectory(f.reports.resolve(directory))
        Files.write(nested.resolve(name), byteArrayOf(7))
        val result = f.outcome.readReports("reports/$directory")
        assertEquals(setOf(name), result.keys)
        assertContentEquals(byteArrayOf(7), result.getValue(name))
    }

    @Test fun `returned inventory cannot be structurally mutated and bytes are detached on every call`() = withReports { f ->
        val original = byteArrayOf(1, 2, 3)
        val report = Files.write(f.reports.resolve("one.json"), original)
        val first = f.outcome.readReports("reports")
        assertFailsWith<UnsupportedOperationException> { (first as MutableMap<String, ByteArray>).clear() }
        first.getValue("one.json")[0] = 99
        assertContentEquals(original, Files.readAllBytes(report))
        val second = f.outcome.readReports("reports")
        assertContentEquals(original, second.getValue("one.json"))
        assertNotSame(first.getValue("one.json"), second.getValue("one.json"))
    }

    @Test fun `data reading never confirms a synthetic failed action or grants execution evidence`() = withReports { f ->
        Files.write(f.reports.resolve("one.json"), byteArrayOf(1))
        assertFalse(f.outcome.executed)
        assertFalse(f.outcome.successfulExecution)
        f.outcome.readReports("reports")
        assertEquals("failed", f.outcome.status)
        assertEquals(listOf("synthetic_data_only"), f.outcome.reasons)
        assertNull(f.outcome.exitCode)
        assertNull(f.outcome.supervisorIdentity)
        assertFalse(f.outcome.executionConfirmed)
        assertFalse(f.outcome.executed)
        assertFalse(f.outcome.successfulExecution)
    }

    @Test fun `interruption refuses collection and preserves the callers interrupt flag`() = withReports { f ->
        Files.write(f.reports.resolve("one.json"), byteArrayOf(1))
        assertFalse(Thread.currentThread().isInterrupted)
        Thread.currentThread().interrupt()
        try {
            assertEquals("report_collection_interrupted", failure { f.outcome.readReports("reports") })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // Clear only the flag this test set, before owned temporary cleanup.
        }
    }

    @Test fun `count overflow including empty files is rejected before any channel opens`() = withReports { f ->
        repeat(3) { Files.write(f.reports.resolve("$it.json"), byteArrayOf()) }
        withAnchor(f.reports) { directory ->
            var opens = 0
            val controlled = onReportOpen(directory) { _, channel -> opens++; channel }
            assertEquals("report_collection_limit", failure { collectAnchoredReports(controlled, 2, 1, 1) })
            assertEquals(0, opens)
        }
    }

    @Test fun `per file overflow is rejected before any channel opens`() = withReports { f ->
        Files.write(f.reports.resolve("one.json"), byteArrayOf(1, 2, 3, 4))
        withAnchor(f.reports) { directory ->
            var opens = 0
            val controlled = onReportOpen(directory) { _, channel -> opens++; channel }
            assertEquals("report_not_regular_or_oversized", failure { collectAnchoredReports(controlled, 4, 3, 16) })
            assertEquals(0, opens)
        }
    }

    @Test fun `aggregate overflow is rejected before any channel opens`() = withReports { f ->
        Files.write(f.reports.resolve("one.json"), byteArrayOf(1, 2, 3))
        Files.write(f.reports.resolve("two.json"), byteArrayOf(4, 5, 6))
        withAnchor(f.reports) { directory ->
            var opens = 0
            val controlled = onReportOpen(directory) { _, channel -> opens++; channel }
            assertEquals("report_collection_limit", failure { collectAnchoredReports(controlled, 4, 3, 5) })
            assertEquals(0, opens)
        }
    }

    @Test fun `new member after initial inventory refuses a prefix result`() = withReports { f ->
        Files.write(f.reports.resolve("one.json"), byteArrayOf(1))
        withAnchor(f.reports) { directory ->
            val controlled = onReportOpen(directory) { _, channel ->
                Files.write(f.reports.resolve("unexpected-extra"), byteArrayOf(2))
                channel
            }
            assertEquals("report_collection_changed", failure { collectAnchoredReports(controlled, 4, 16, 64) })
        }
    }

    @Test fun `replacement after anchored open cannot borrow the old file channel`() = withReports { f ->
        val report = Files.write(f.reports.resolve("one.json"), byteArrayOf(1, 2, 3))
        val held = f.work.resolve("held-original-report")
        withAnchor(f.reports) { directory ->
            val controlled = onReportOpen(directory) { _, channel ->
                Files.move(report, held) // Retaining the inode prevents an accidental key-reuse control.
                Files.write(report, byteArrayOf(4, 5, 6))
                channel
            }
            assertEquals("report_collection_changed", failure { collectAnchoredReports(controlled, 4, 16, 64) })
        }
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(held))
        assertContentEquals(byteArrayOf(4, 5, 6), Files.readAllBytes(report))
    }

    @Test fun `growth before first read is detected by opened channel size`() = withReports { f ->
        val report = Files.write(f.reports.resolve("one.json"), byteArrayOf(1, 2, 3))
        withAnchor(f.reports) { directory ->
            val controlled = onReportOpen(directory) { _, channel ->
                Files.write(report, byteArrayOf(4), APPEND)
                channel
            }
            assertEquals("report_collection_changed", failure { collectAnchoredReports(controlled, 4, 16, 64) })
        }
    }

    @Test fun `growth at the byte boundary is refused rather than allocating an unbounded stream`() = withReports { f ->
        val report = Files.write(f.reports.resolve("one.json"), byteArrayOf(1, 2, 3))
        withAnchor(f.reports) { directory ->
            val controlled = onReportOpen(directory) { _, channel ->
                var changed = false
                object : SeekableByteChannel by channel {
                    override fun read(dst: ByteBuffer): Int {
                        val count = channel.read(dst)
                        if (!changed) { changed = true; Files.write(report, byteArrayOf(4), APPEND) }
                        return count
                    }
                }
            }
            assertEquals("report_collection_changed", failure { collectAnchoredReports(controlled, 4, 16, 64) })
        }
        assertEquals(4L, Files.size(report))
    }

    @Test fun `early EOF and zero progress cannot produce short reports or busy loops`() = withReports { f ->
        Files.write(f.reports.resolve("one.json"), byteArrayOf(1, 2, 3))
        for (returnValue in listOf(-1, 0)) withAnchor(f.reports) { directory ->
            var reads = 0
            val controlled = onReportOpen(directory) { _, channel ->
                object : SeekableByteChannel by channel {
                    override fun read(dst: ByteBuffer): Int { reads++; return returnValue }
                }
            }
            assertEquals("report_collection_changed", failure { collectAnchoredReports(controlled, 4, 16, 64) })
            assertEquals(1, reads)
        }
    }

    @Test fun `same size metadata drift is refused without relying on timestamp granularity`() = withReports { f ->
        val report = Files.write(f.reports.resolve("one.json"), byteArrayOf(1, 2, 3))
        val originalTime = Files.getLastModifiedTime(report, NOFOLLOW_LINKS)
        withAnchor(f.reports) { directory ->
            val controlled = onReportOpen(directory) { _, channel ->
                Files.setLastModifiedTime(report, FileTime.fromMillis(originalTime.toMillis() + 60_000))
                channel
            }
            assertEquals("report_collection_changed", failure { collectAnchoredReports(controlled, 4, 16, 64) })
        }
    }

    private fun failure(block: () -> Unit): String = assertFailsWith<ExecutionProblem>(block = block).code

    private data class ReportFixture(val work: Path, val root: Path, val reports: Path, val outcome: ProcessOutcome)

    private fun withReports(block: (ReportFixture) -> Unit) {
        val work = Files.createTempDirectory("ktchecker-report-collection-data-control-").toRealPath()
        try {
            val root = Files.createDirectory(work.resolve("output"))
            val reports = Files.createDirectory(root.resolve("reports"))
            val anchored = Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> }
            if (System.getProperty("os.name") == "Linux") {
                assertTrue(anchored, "Supported Linux filesystem requires anchored I/O; this is not a successful skip")
            }
            assumeTrue(anchored, "Anchored report I/O unavailable on this filesystem; no positive data-reader coverage is claimed")
            val key = assertNotNull(Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
            val outcome = ProcessOutcome("failed", reasons = listOf("synthetic_data_only"),
                outputDirectory = root, outputIdentity = key.toString())
            block(ReportFixture(work, root, reports, outcome))
        } finally {
            // Only this test's private temporary tree; Files.walk does not follow symbolic links.
            Files.walk(work).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun withAnchor(path: Path, block: (SecureDirectoryStream<Path>) -> Unit) {
        Files.newDirectoryStream(path).use { stream ->
            val directory = assertIs<SecureDirectoryStream<Path>>(stream)
            block(directory)
        }
    }

    private fun onReportOpen(directory: SecureDirectoryStream<Path>,
                             action: (Path, SeekableByteChannel) -> SeekableByteChannel): SecureDirectoryStream<Path> =
        object : SecureDirectoryStream<Path> by directory {
            override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>,
                                        vararg attrs: FileAttribute<*>): SeekableByteChannel {
                val channel = directory.newByteChannel(path, options, *attrs)
                return try { action(path, channel) }
                catch (problem: Throwable) { channel.close(); throw problem }
            }
        }
}
