package dev.ktchecker.kernel

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Actual JDBC/WAL files; durable legacy history is retained, transient SHM is not byte-immutable. */
class KernelSchema2Test {
    @TempDir lateinit var temp: Path
    private val config = KernelConfig(workerCount = 0)

    private fun database(root: Path, readOnly: Boolean = false): Connection {
        if (!readOnly) Files.createDirectories(root)
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${root.resolve("ledger.sqlite").toUri()}?mode=${if (readOnly) "ro" else "rwc"}")
    }

    // Only quiet fixture phases with NO open JDBC connection use raw byte comparisons: no
    // background scheduler, checkpoint or concurrent writer. A raw DB fd closed in the same JVM
    // as an active SQLite transaction can release its POSIX locks and invalidate the test itself.
    // SHM/read marks and newly created empty WAL infrastructure are not durable history.
    private fun files(root: Path): Map<String, ByteArray> = Files.list(root).use { entries ->
        entries.filter { it.fileName.toString() == "ledger.sqlite" ||
            (it.fileName.toString() == "ledger.sqlite-wal" && Files.size(it) > 0) }
            .toList().associate { it.fileName.toString() to Files.readAllBytes(it) }
    }

    private fun unchanged(root: Path, before: Map<String, ByteArray>) {
        val after = files(root)
        assertEquals(before.keys, after.keys, "Refusal must retain the existing durable database/WAL")
        before.forEach { (path, bytes) -> assertContentEquals(bytes, after.getValue(path), "Durable old bytes changed: $path") }
    }

    private fun rejected(block: () -> Unit) {
        val failure = assertFailsWith<KernelException>(block = block)
        assertEquals("unsupported_schema", failure.code)
    }

    /** Keep WAL on disk without retaining a live connection during raw byte assertions. */
    private fun initializeWithReadOnlyLastClose(root: Path, initialize: (Connection) -> Unit) {
        val owner = database(root)
        try {
            initialize(owner)
            database(root, readOnly = true).use { reader ->
                reader.execute("BEGIN")
                assertNotNull(reader.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) })
                // The writable close cannot take EXCLUSIVE while this reader remains attached.
                // The final read-only close does not checkpoint/delete the retained WAL.
                owner.close()
            }
        } finally { owner.close() }
    }

    @Test fun schema1IsRefusedRepeatedlyWithoutRewritingDurableDatabaseWalOrRetainedObligations() {
        listOf("delete", "wal-only", "wal-checkpointed").forEach { mode ->
            val root = temp.resolve(mode)
            initializeWithReadOnlyLastClose(root) { old ->
                if (mode != "delete") old.createStatement().use { it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA wal_autocheckpoint=0") }
                old.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                old.execute("INSERT INTO metadata(key,value) VALUES('schema_version','1')")
                old.execute("CREATE TABLE retained_obligations(obligation TEXT PRIMARY KEY,review TEXT NOT NULL)")
                old.execute("INSERT INTO retained_obligations VALUES('old-required-obligation','must-not-be-discharged')")
                if (mode == "wal-checkpointed") {
                    old.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
                    old.execute("INSERT INTO retained_obligations VALUES('old-wal-obligation','also-retained')")
                }
            }
            val before = files(root)
            if (mode != "delete") assertTrue("ledger.sqlite-wal" in before)
            repeat(2) {
                rejected { Ledger(root, config) }
                unchanged(root, before)
            }
            database(root).use { old ->
                assertEquals(if (mode == "wal-checkpointed") 2L else 1L, old.count("SELECT COUNT(*) FROM retained_obligations"))
                assertEquals("1", old.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) })
                // Version rejection cannot stop an already-open old process. This write is the
                // fixture's old owner, NOT a schema3 write or permission for mixed binaries.
                // No raw byte snapshots occur while this connection is open.
                rejected { Ledger(root, config) }
                old.execute("INSERT INTO retained_obligations VALUES('old-owner-still-running','shutdown-required')")
                assertEquals(if (mode == "wal-checkpointed") 3L else 2L, old.count("SELECT COUNT(*) FROM retained_obligations"))
            }
        }
    }

    @Test fun newSchema3StateDoesNotCopyResetOrDischargeAnOldStore() {
        val oldRoot = temp.resolve("old")
        database(oldRoot).use { c ->
            c.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            c.execute("INSERT INTO metadata VALUES('schema_version','1')")
            c.execute("CREATE TABLE retained_obligations(id TEXT PRIMARY KEY)")
            c.execute("INSERT INTO retained_obligations VALUES('still-required')")
        }
        val original = files(oldRoot)
        rejected { Ledger(oldRoot, config) }
        val fresh = Ledger(temp.resolve("separately-owner-selected-new-state"), config)
        fresh.read { c ->
            assertEquals("3", c.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) })
            assertEquals(0L, c.count("SELECT COUNT(*) FROM reviews"))
            assertTrue("routing_id" in c.query("PRAGMA table_info(parent_plans)") { it.getString("name") })
            assertTrue("routed_obligations" in c.query("PRAGMA table_info(clients)") { it.getString("name") })
            assertEquals(0L, c.count("SELECT COUNT(*) FROM routing_batches"))
            assertEquals(0L, c.count("SELECT COUNT(*) FROM routing_members"))
            listOf("produced_plan_admissions", "plan_slot_resolution_claims", "plan_slot_resolutions", "work_publications").forEach { table ->
                assertEquals(1L, c.count("SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name=?", table))
            }
            assertEquals(1L, c.count("SELECT COUNT(*) FROM sqlite_schema WHERE type='view' AND name='review_executions'"))
        }
        unchanged(oldRoot, original)
        database(oldRoot).use { c -> assertEquals(1L, c.count("SELECT COUNT(*) FROM retained_obligations")) }
    }

    @Test fun readOnlyPreflightUsesLatestCommittedWalSchemaInsteadOfAStaleMainFile() {
        val root = temp.resolve("latest-wal-schema")
        Ledger(root, config)
        val originalMain = files(root).getValue("ledger.sqlite")
        initializeWithReadOnlyLastClose(root) { owner ->
            owner.createStatement().use { it.execute("PRAGMA wal_autocheckpoint=0") }
            owner.execute("UPDATE metadata SET value='1' WHERE key='schema_version'")
            owner.execute("INSERT INTO metadata VALUES('old-required-obligation','retained-in-wal')")
        }
        val before = files(root)
        assertContentEquals(originalMain, before.getValue("ledger.sqlite"), "Main file must still contain the previous schema3 image")
        assertTrue("ledger.sqlite-wal" in before)
        rejected { Ledger(root, config) }
        unchanged(root, before)
        database(root, readOnly = true).use { reader ->
            assertEquals("1", reader.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) })
            assertEquals("retained-in-wal", reader.one("SELECT value FROM metadata WHERE key='old-required-obligation'") { it.getString(1) })
        }
        unchanged(root, before)
    }

    @Test fun actualJdbcReadOnlyUriRejectsWritesWithoutDiscardingItsWalReadView() {
        val root = temp.resolve("readonly-uri")
        Ledger(root, config)
        database(root, readOnly = true).use { reader ->
            reader.execute("BEGIN")
            try {
                assertEquals("3", reader.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) })
                val denied = assertFailsWith<SQLException> {
                    reader.execute("INSERT INTO metadata VALUES('must-not-write','forbidden')")
                }
                assertEquals(8, denied.errorCode and 0xff, "SQLite must return SQLITE_READONLY, not merely a caller convention")
                assertEquals(0L, reader.count("SELECT COUNT(*) FROM metadata WHERE key='must-not-write'"))
                reader.execute("COMMIT")
            } catch (failure: Throwable) {
                runCatching { reader.execute("ROLLBACK") }
                throw failure
            }
        }
        Ledger(root, config).read { c -> assertEquals(0L, c.count("SELECT COUNT(*) FROM metadata WHERE key='must-not-write'")) }
    }

    @Test fun coldLegacyWalModeRefusalMayManageEmptySidecarsButNeverRewritesDurableHistory() {
        val root = temp.resolve("cold-legacy-wal")
        database(root).use { old ->
            old.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            old.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            old.execute("INSERT INTO metadata VALUES('schema_version','1')")
            old.execute("CREATE TABLE retained_obligations(id TEXT PRIMARY KEY)")
            old.execute("INSERT INTO retained_obligations VALUES('still-required')")
        }
        // The last writable connection checkpointed and closed before this byte snapshot.
        val before = files(root)
        assertEquals(setOf("ledger.sqlite"), before.keys)
        repeat(2) {
            rejected { Ledger(root, config) }
            unchanged(root, before)
        }
        database(root, readOnly = true).use { old ->
            assertEquals("1", old.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) })
            assertEquals(1L, old.count("SELECT COUNT(*) FROM retained_obligations"))
        }
        unchanged(root, before)
    }

    @Test fun normalReadOnlyAdmissionSnapshotsRemainConsistentDuringConcurrentWalCheckpoints() {
        val root = temp.resolve("checkpoint-reopens")
        val ledger = Ledger(root, config)
        ledger.transaction { it.execute("INSERT INTO metadata VALUES('retained-test','unchanged')") }
        val rounds = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val checkpoint = pool.submit(Callable {
                database(root).use { writer ->
                    writer.createStatement().use {
                        it.execute("PRAGMA busy_timeout=5000")
                        it.execute("PRAGMA wal_autocheckpoint=0")
                    }
                    val payload = "x".repeat(64 * 1024)
                    repeat(16) { round ->
                        rounds.await(10, TimeUnit.SECONDS)
                        writer.execute("INSERT INTO metadata VALUES(?,?)", "checkpoint-fixture-$round", payload)
                        assertNotNull(writer.one("PRAGMA wal_checkpoint(PASSIVE)") { it.getInt(1) })
                    }
                }
            })
            val reopen = pool.submit(Callable {
                repeat(16) {
                    rounds.await(10, TimeUnit.SECONDS)
                    Ledger(root, config).read { c ->
                        assertEquals("3", c.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) })
                        assertEquals("unchanged", c.one("SELECT value FROM metadata WHERE key='retained-test'") { it.getString(1) })
                    }
                }
            })
            listOf(checkpoint, reopen).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
        // Only normal SQLite connections observe the concurrently used DB; no raw DB/SHM fd.
        ledger.read { c ->
            assertEquals("ok", c.one("PRAGMA integrity_check") { it.getString(1) })
            assertEquals(16L, c.count("SELECT COUNT(*) FROM metadata WHERE key LIKE 'checkpoint-fixture-%'"))
            assertEquals("unchanged", c.one("SELECT value FROM metadata WHERE key='retained-test'") { it.getString(1) })
        }
    }

    @Test fun existingEmptyUnversionedAndUnknownVersionFilesAreNeverReinitialized() {
        val empty = temp.resolve("empty")
        Files.createDirectories(empty)
        Files.createFile(empty.resolve("ledger.sqlite"))
        val beforeEmpty = files(empty)
        rejected { Ledger(empty, config) }
        unchanged(empty, beforeEmpty)
        listOf<String?>(null, "0", "2", "4").forEachIndexed { index, version ->
            val root = temp.resolve("unrecognized-$index")
            database(root).use { c ->
                c.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                if (version != null) c.execute("INSERT INTO metadata VALUES('schema_version',?)", version)
                c.execute("CREATE TABLE old_bytes(value TEXT NOT NULL)")
                c.execute("INSERT INTO old_bytes VALUES('preserve')")
            }
            val before = files(root)
            rejected { Ledger(root, config) }
            unchanged(root, before)
        }
    }

    @Test fun everyOperationVerifiesTheCurrentSchemaVersionBeforeExecutingItsBlock() {
        val root = temp.resolve("ledger")
        val ledger = Ledger(root, config)
        ledger.transaction { it.execute("INSERT INTO metadata VALUES('retained-test','unchanged')") }
        listOf<String?>("1", "2", "4", null).forEach { version ->
            database(root).use { c ->
                c.execute("DELETE FROM metadata WHERE key='schema_version'")
                if (version != null) c.execute("INSERT INTO metadata VALUES('schema_version',?)", version)
            }
            var calls = 0
            rejected { ledger.read { calls++ } }
            rejected { ledger.transaction { calls++; it.execute("DELETE FROM metadata WHERE key='retained-test'") } }
            assertEquals(0, calls)
            database(root).use { c ->
                assertEquals("unchanged", c.one("SELECT value FROM metadata WHERE key='retained-test'") { it.getString(1) })
                c.execute("DELETE FROM metadata WHERE key='schema_version'")
                c.execute("INSERT INTO metadata VALUES('schema_version','3')")
            }
            assertEquals("3", ledger.read { c -> c.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) } })
        }
    }

    @Test fun missingLedgerInNonemptySelectedNamespaceRequiresReconciliationWithoutCreatingOrReplacingAnything() {
        // New isolated fixtures only. No real old broker/output attempt is inspected or touched.
        listOf("inputs/retained/input.json", "produced-output/retained/complete-output.jar", "ledger.sqlite-wal").forEachIndexed { index, name ->
            val root = temp.resolve("missing-ledger-$index")
            val retained = root.resolve(name)
            Files.createDirectories(retained.parent)
            val bytes = "synthetic-retained-obligation-$index".toByteArray()
            Files.write(retained, bytes)
            repeat(2) {
                val failure = assertFailsWith<KernelException> { Ledger(root, config) }
                assertEquals("missing_ledger_requires_reconciliation", failure.code)
                assertTrue(failure.message.orEmpty().contains("reconciliation"))
                assertFalse(Files.exists(root.resolve("ledger.sqlite")))
                assertContentEquals(bytes, Files.readAllBytes(retained))
            }
        }
    }
}
