package dev.ktchecker.kernel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.serialization.encodeToString

/** One connection per short transaction: also safe across independent service JVMs. */
internal class Ledger(val root: Path, config: KernelConfig) {
    private val database = root.resolve("ledger.sqlite")
    internal val metrics = LedgerMetrics()

    init {
        privateDirectory(root)
        if (Files.isSymbolicLink(database)) fail("unsafe_store", "Ledger must not be a symbolic link")
        Class.forName("org.sqlite.JDBC")
        connection().use { c ->
            c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        }
        transaction { c ->
            SCHEMA.forEach { sql -> c.createStatement().use { it.execute(sql) } }
            val storedVersion = c.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) }
            if (storedVersion != null && storedVersion != "1") fail("unsupported_schema", "Unsupported preview ledger schema")
            c.execute("INSERT OR IGNORE INTO metadata(key,value) VALUES('schema_version','1')")
            // Scheduling/coverage policy is one durable authority, not a per-process mutable preference.
            val sharedPolicy = config.copy(workerCount = 0, pollMillis = 1, heartbeatMillis = 1, leaseMillis = 3)
            val digest = Digests.sha256(ContractJson.encodeToString(sharedPolicy))
            val existing = c.one("SELECT value FROM metadata WHERE key='policy_digest'") { it.getString(1) }
            if (existing != null && existing != digest) fail("policy_mismatch", "Service configuration differs from durable ledger policy")
            c.execute("INSERT OR IGNORE INTO metadata(key,value) VALUES('policy_digest',?)", digest)
            c.execute("INSERT OR IGNORE INTO metadata(key,value) VALUES('dispatch_counter','0')")
        }
        privateFile(database)
        listOf("ledger.sqlite-wal", "ledger.sqlite-shm").forEach { file ->
            val p = root.resolve(file)
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) privateFile(p)
        }
    }

    private fun connection(): Connection {
        val started = System.nanoTime()
        try {
            return DriverManager.getConnection("jdbc:sqlite:$database").also { c ->
                try {
                    c.createStatement().use { s ->
                        s.execute("PRAGMA busy_timeout=15000")
                        s.execute("PRAGMA foreign_keys=ON")
                        s.execute("PRAGMA synchronous=FULL")
                    }
                } catch (t: Throwable) {
                    c.close()
                    throw t
                }
            }
        } finally {
            metrics.connection.record(System.nanoTime() - started)
        }
    }

    fun <T> read(block: (Connection) -> T): T = connection().use { c ->
        val started = System.nanoTime()
        c.createStatement().use { it.execute("BEGIN") }
        try {
            val result = block(c)
            c.createStatement().use { it.execute("COMMIT") }
            result
        } catch (t: Throwable) {
            runCatching { c.createStatement().use { it.execute("ROLLBACK") } }
            throw t
        } finally {
            metrics.read.record(System.nanoTime() - started)
        }
    }

    fun <T> transaction(block: (Connection) -> T): T = connection().use { c ->
        val waiting = System.nanoTime()
        try {
            c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        } finally {
            metrics.writerWait.record(System.nanoTime() - waiting)
        }
        val started = System.nanoTime()
        try {
            val result = block(c)
            c.createStatement().use { it.execute("COMMIT") }
            result
        } catch (t: Throwable) {
            runCatching { c.createStatement().use { it.execute("ROLLBACK") } }
            throw t
        } finally {
            metrics.writerHold.record(System.nanoTime() - started)
        }
    }

    companion object {
        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
            """CREATE TABLE IF NOT EXISTS clients(
                client_id TEXT PRIMARY KEY,binding_json TEXT NOT NULL,client_hash TEXT NOT NULL UNIQUE,
                adapter_hash TEXT NOT NULL UNIQUE,epoch INTEGER NOT NULL DEFAULT 0,
                next_review INTEGER NOT NULL DEFAULT 1,next_event INTEGER NOT NULL DEFAULT 1,
                gate_state TEXT NOT NULL DEFAULT 'open',active_cut_id TEXT)""",
            """CREATE TABLE IF NOT EXISTS instances(
                instance_id TEXT PRIMARY KEY,pid INTEGER NOT NULL,process_start TEXT,
                heartbeat INTEGER NOT NULL,closed INTEGER NOT NULL DEFAULT 0)""",
            "CREATE TABLE IF NOT EXISTS fair_queues(fair_key TEXT PRIMARY KEY,last_served INTEGER NOT NULL DEFAULT 0)",
            """CREATE TABLE IF NOT EXISTS fair_clients(
                client_id TEXT PRIMARY KEY,fair_key TEXT NOT NULL,last_served INTEGER NOT NULL,
                FOREIGN KEY(client_id) REFERENCES clients(client_id),
                FOREIGN KEY(fair_key) REFERENCES fair_queues(fair_key))""",
            "CREATE INDEX IF NOT EXISTS fair_clients_queue ON fair_clients(fair_key,last_served)",
            """CREATE TABLE IF NOT EXISTS work(
                work_key TEXT PRIMARY KEY,input_json TEXT NOT NULL,frozen_json TEXT NOT NULL,
                provider_key TEXT NOT NULL,fair_key TEXT NOT NULL,state TEXT NOT NULL,
                owner_instance TEXT,attempt INTEGER NOT NULL DEFAULT 0,lease_until INTEGER,deadline INTEGER,
                result_json TEXT,validation_json TEXT NOT NULL DEFAULT '[]',progress_limited INTEGER NOT NULL DEFAULT 0,archive_bytes INTEGER NOT NULL,
                created_at INTEGER NOT NULL,FOREIGN KEY(fair_key) REFERENCES fair_queues(fair_key))""",
            "CREATE INDEX IF NOT EXISTS work_queue ON work(state,provider_key,fair_key,created_at)",
            """CREATE TABLE IF NOT EXISTS reviews(
                review_id TEXT PRIMARY KEY,client_id TEXT NOT NULL,patch_id TEXT NOT NULL,
                idempotency_key TEXT NOT NULL,request_hash TEXT NOT NULL,request_json TEXT NOT NULL,
                input_json TEXT NOT NULL,work_key TEXT NOT NULL,sequence INTEGER NOT NULL,
                cancelled INTEGER NOT NULL DEFAULT 0,effective_result_json TEXT,
                policy_validation_json TEXT NOT NULL DEFAULT '[]',policy_certificate TEXT,created_at INTEGER NOT NULL,
                UNIQUE(client_id,idempotency_key),UNIQUE(client_id,patch_id),UNIQUE(client_id,sequence),
                FOREIGN KEY(client_id) REFERENCES clients(client_id),FOREIGN KEY(work_key) REFERENCES work(work_key))""",
            "CREATE INDEX IF NOT EXISTS reviews_work ON reviews(work_key)",
            """CREATE TABLE IF NOT EXISTS work_progress(
                work_key TEXT NOT NULL,payload_digest TEXT NOT NULL,payload_json TEXT NOT NULL,
                sequence INTEGER NOT NULL,byte_count INTEGER NOT NULL,
                PRIMARY KEY(work_key,payload_digest),FOREIGN KEY(work_key) REFERENCES work(work_key))""",
            """CREATE TABLE IF NOT EXISTS work_certificates(
                work_key TEXT PRIMARY KEY,input_key TEXT NOT NULL,effective_key TEXT NOT NULL,
                result_digest TEXT NOT NULL,provider_key TEXT NOT NULL,certificate_id TEXT NOT NULL,
                FOREIGN KEY(work_key) REFERENCES work(work_key))""",
            """CREATE TABLE IF NOT EXISTS events(
                event_id TEXT PRIMARY KEY,client_id TEXT NOT NULL,sequence INTEGER NOT NULL,
                review_id TEXT NOT NULL,patch_id TEXT NOT NULL,snapshot_id TEXT NOT NULL,
                kind TEXT NOT NULL,delivery_required INTEGER NOT NULL,payload_digest TEXT NOT NULL,
                payload TEXT NOT NULL,generation INTEGER NOT NULL,created_at INTEGER NOT NULL,
                UNIQUE(client_id,sequence),FOREIGN KEY(client_id) REFERENCES clients(client_id),
                FOREIGN KEY(review_id) REFERENCES reviews(review_id))""",
            """CREATE TABLE IF NOT EXISTS receipts(
                client_id TEXT NOT NULL,kind TEXT NOT NULL,receipt_id TEXT NOT NULL,request_json TEXT NOT NULL,
                PRIMARY KEY(client_id,kind,receipt_id),FOREIGN KEY(client_id) REFERENCES clients(client_id))""",
            """CREATE TABLE IF NOT EXISTS acknowledgements(
                event_id TEXT PRIMARY KEY,client_id TEXT NOT NULL,receipt_id TEXT NOT NULL,
                context_digest TEXT NOT NULL,context_item_id TEXT NOT NULL,inserted_at TEXT NOT NULL,
                receipt_json TEXT NOT NULL,FOREIGN KEY(event_id) REFERENCES events(event_id))""",
            """CREATE TABLE IF NOT EXISTS observations(
                event_id TEXT NOT NULL,client_id TEXT NOT NULL,observation_id TEXT NOT NULL,
                model_request_id TEXT NOT NULL,context_digest TEXT NOT NULL,receipt_json TEXT NOT NULL,
                PRIMARY KEY(event_id,observation_id),FOREIGN KEY(event_id) REFERENCES events(event_id))""",
            """CREATE TABLE IF NOT EXISTS gate_cuts(
                cut_id TEXT PRIMARY KEY,client_id TEXT NOT NULL,cut_json TEXT NOT NULL,
                input_json TEXT,check_json TEXT,consumed INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(client_id) REFERENCES clients(client_id))""",
        )
    }
}

internal fun fail(code: String, message: String): Nothing = throw KernelException(code, message)
internal fun Connection.execute(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { s ->
    s.bind(args)
    s.executeUpdate()
}
internal fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { s ->
        s.bind(args)
        s.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
    }
internal fun <T> Connection.one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { s ->
        s.bind(args)
        s.executeQuery().use { rows -> if (rows.next()) map(rows) else null }
    }
internal fun Connection.count(sql: String, vararg args: Any?): Long = one(sql, *args) { it.getLong(1) } ?: 0
private fun PreparedStatement.bind(args: Array<out Any?>) = args.forEachIndexed { i, value -> setObject(i + 1, value) }

internal fun privateDirectory(path: Path) {
    if (Files.isSymbolicLink(path)) fail("unsafe_store", "Store directory must not be a symbolic link")
    Files.createDirectories(path)
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) fail("unsafe_store", "Expected an owner-controlled directory")
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }
}
internal fun privateFile(path: Path) {
    if (Files.isSymbolicLink(path)) fail("unsafe_store", "Store file must not be a symbolic link")
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }
}
