package dev.ktchecker.kernel

import dev.ktchecker.contracts.ContractJson
import dev.ktchecker.contracts.Digests
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
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
        if (!Files.exists(database, LinkOption.NOFOLLOW_LINKS) && Files.list(root).use { it.anyMatch { p -> p != database } }) {
            fail("missing_ledger_requires_reconciliation", "Selected broker namespace is nonempty but its ledger is missing; preserve retained artifacts and obtain offline owner reconciliation, never recreate or select another store automatically")
        }
        Class.forName("org.sqlite.JDBC")
        // Reserve only a genuinely new file. An existing empty/unversioned store (including a
        // first opener that has not committed yet) is NOT permission to initialize or repair it.
        // Its owner can retry after that opener commits; abandoned stores need offline review.
        val fresh = try { Files.createFile(database); true } catch (_: FileAlreadyExistsException) { false }
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) fail("unsafe_store", "Ledger must be a regular private file")
        if (fresh) privateFile(database)
        else {
            // A normal read-only WAL snapshot is mandatory: immutable=1 would ignore locks
            // while other instances checkpoint the main file. Never open a separate raw DB
            // descriptor either: closing it can release this process's SQLite POSIX locks.
            // SQLite may maintain transient SHM/read marks or empty sidecars for this read;
            // rejected durable database/WAL contents and obligations are not rewritten.
            read(readOnly = true) { c -> verifyRequiredTables(c); verifyProviderFairness(c) }
        }
        // No writer connection, WAL mode change, DDL or migration runs against a rejected v1/v2/v3
        // store. Schema4 has NO migration/reset path and never discharges historical obligations.
        write(checkVersion = false) { c ->
            if (fresh) {
                if (c.count("SELECT COUNT(*) FROM sqlite_schema") != 0L) unsupportedVersion()
                SCHEMA.forEach { sql -> c.createStatement().use { it.execute(sql) } }
                c.execute("INSERT INTO metadata(key,value) VALUES('schema_version',?)", SCHEMA_VERSION)
            } else { verifyVersion(c); verifyRequiredTables(c); verifyProviderFairness(c) }
            // Scheduling/coverage policy is one durable authority, not a per-process mutable preference.
            val sharedPolicy = config.copy(workerCount = 0, pollMillis = 1, heartbeatMillis = 1, leaseMillis = 3)
            val digest = Digests.sha256(ContractJson.encodeToString(sharedPolicy))
            val existing = c.one("SELECT value FROM metadata WHERE key='policy_digest'") { it.getString(1) }
            if (fresh) {
                c.execute("INSERT INTO metadata(key,value) VALUES('policy_digest',?)", digest)
                c.execute("INSERT INTO metadata(key,value) VALUES('dispatch_counter','0')")
            } else {
                if (existing != digest) fail("policy_mismatch", "Service configuration differs from durable ledger policy")
                if (c.count("SELECT COUNT(*) FROM metadata WHERE key='dispatch_counter'") != 1L) {
                    fail("ledger_corrupt", "Durable scheduling metadata is unavailable")
                }
            }
        }
        connection().use { c ->
            verifyVersion(c)
            c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            // Read after enabling WAL so even a fresh DELETE-mode store attaches this
            // connection to WAL. Keep it open through permission checks: otherwise another
            // instance's last close can remove a sidecar between exists() and chmod().
            // Permission/symlink failures still propagate, and use closes on every failure.
            verifyVersion(c)
            privateFile(database)
            listOf("ledger.sqlite-wal", "ledger.sqlite-shm").forEach { file ->
                val p = root.resolve(file)
                if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) privateFile(p)
            }
        }
    }

    private fun connection(readOnly: Boolean = false): Connection {
        val started = System.nanoTime()
        try {
            // All reads retain SQLite's live WAL/locking guarantees. rw never recreates a
            // removed store; ro preflight cannot write or migrate an unsupported ledger.
            val url = "jdbc:sqlite:${database.toUri()}?mode=${if (readOnly) "ro" else "rw"}"
            return DriverManager.getConnection(url).also { c ->
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

    fun <T> read(block: (Connection) -> T): T = read(readOnly = false, block = block)

    private fun <T> read(readOnly: Boolean, block: (Connection) -> T): T = connection(readOnly).use { c ->
        val started = System.nanoTime()
        c.createStatement().use { it.execute("BEGIN") }
        try {
            verifyVersion(c)
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

    fun <T> transaction(block: (Connection) -> T): T = write(checkVersion = true, block = block)

    private fun <T> write(checkVersion: Boolean, block: (Connection) -> T): T = connection().use { c ->
        val waiting = System.nanoTime()
        try {
            c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        } finally {
            metrics.writerWait.record(System.nanoTime() - waiting)
        }
        val started = System.nanoTime()
        try {
            if (checkVersion) verifyVersion(c)
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

    /**
     * Only first admission may create an age. Call before adding this admission's work, review,
     * or produced-admission relation, under the same writer transaction. Retained relations are
     * the evidence of prior membership; their missing ages require owner reconciliation, not
     * INSERT OR IGNORE restoration. Aggregate ages cannot reconstruct provider-local history.
     */
    internal fun admitProviderFairness(c: Connection, providerKey: String, fairKey: String, clientId: String) {
        val treePresent = c.count("SELECT COUNT(*) FROM fair_queues WHERE fair_key=?", fairKey) == 1L
        val clientTree = c.one("SELECT fair_key FROM fair_clients WHERE client_id=?", clientId) { it.getString(1) }
        val providerTreePresent = c.count("SELECT COUNT(*) FROM provider_fair_queues WHERE provider_key=? AND fair_key=?", providerKey, fairKey) == 1L
        val providerClientPresent = c.count("SELECT COUNT(*) FROM provider_fair_clients WHERE provider_key=? AND client_id=?", providerKey, clientId) == 1L
        if ((!treePresent && (providerTreePresent || c.count("SELECT EXISTS(SELECT 1 FROM ($FAIR_TREE_DOMAINS) WHERE fair_key=?)", fairKey) != 0L)) ||
            (clientTree == null && (providerClientPresent || c.count("SELECT EXISTS(SELECT 1 FROM reviews WHERE client_id=?)", clientId) != 0L ||
                c.count("SELECT EXISTS(SELECT 1 FROM ($FAIR_CLIENT_DOMAINS) WHERE client_id=?)", clientId) != 0L)) ||
            (clientTree != null && clientTree != fairKey) ||
            (!providerTreePresent && (providerClientPresent || c.count("SELECT EXISTS(SELECT 1 FROM ($FAIR_TREE_DOMAINS) WHERE provider_key=? AND fair_key=?)", providerKey, fairKey) != 0L)) ||
            (!providerClientPresent && c.count("SELECT EXISTS(SELECT 1 FROM ($FAIR_CLIENT_DOMAINS) WHERE provider_key=? AND client_id=?)", providerKey, clientId) != 0L)) {
            missingProviderFairness()
        }
        // Both a new physical queue and a new provider membership join the current round.
        // Existing physical/provider rows retain their exact durable age, including on reopen.
        c.execute("""INSERT OR IGNORE INTO fair_queues(fair_key,last_served)
            VALUES(?,(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'))""", fairKey)
        c.execute("""INSERT OR IGNORE INTO fair_clients(client_id,fair_key,last_served)
            VALUES(?,?,(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'))""", clientId, fairKey)
        c.execute("""INSERT OR IGNORE INTO provider_fair_queues(provider_key,fair_key,last_served)
            VALUES(?,?,(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'))""", providerKey, fairKey)
        c.execute("""INSERT OR IGNORE INTO provider_fair_clients(provider_key,client_id,last_served)
            VALUES(?,?,(SELECT CAST(value AS INTEGER) FROM metadata WHERE key='dispatch_counter'))""", providerKey, clientId)
        requireProviderFairness(c, providerKey, fairKey, clientId)
    }

    /** Validation only: resolution/reopen/claim never initializes or resets a scheduling age. */
    internal fun requireProviderFairness(c: Connection, providerKey: String, fairKey: String, clientId: String) {
        if (c.count("""SELECT COUNT(*) FROM provider_fair_queues q
            JOIN fair_queues physical_tree ON physical_tree.fair_key=q.fair_key
            JOIN fair_clients physical_client ON physical_client.fair_key=q.fair_key AND physical_client.client_id=?
            JOIN provider_fair_clients client_queue ON client_queue.provider_key=q.provider_key AND client_queue.client_id=physical_client.client_id
            WHERE q.provider_key=? AND q.fair_key=?""", clientId, providerKey, fairKey) != 1L) missingProviderFairness()
    }

    internal fun verifyProviderFairness(c: Connection) {
        // Produced admissions carry their own persisted provider/tree, even with no ready work.
        // Never infer those identities from the provider of a broker reopening the store.
        if (c.count("""SELECT EXISTS(SELECT 1 FROM ($FAIR_TREE_DOMAINS) domain
            LEFT JOIN fair_queues physical_tree ON physical_tree.fair_key=domain.fair_key
            LEFT JOIN provider_fair_queues q ON q.provider_key=domain.provider_key AND q.fair_key=domain.fair_key
            WHERE physical_tree.fair_key IS NULL OR q.provider_key IS NULL)""") != 0L ||
            c.count("""SELECT EXISTS(SELECT 1 FROM ($FAIR_CLIENT_DOMAINS) domain
                LEFT JOIN fair_clients physical_client ON physical_client.client_id=domain.client_id
                LEFT JOIN provider_fair_clients client_queue ON client_queue.provider_key=domain.provider_key AND client_queue.client_id=domain.client_id
                LEFT JOIN provider_fair_queues q ON q.provider_key=domain.provider_key AND q.fair_key=domain.fair_key
                WHERE physical_client.client_id IS NULL OR physical_client.fair_key<>domain.fair_key OR client_queue.client_id IS NULL OR q.provider_key IS NULL)""") != 0L ||
            c.count("""SELECT EXISTS(SELECT 1 FROM provider_fair_clients client_queue
                LEFT JOIN fair_clients physical_client ON physical_client.client_id=client_queue.client_id
                LEFT JOIN provider_fair_queues q ON q.provider_key=client_queue.provider_key AND q.fair_key=physical_client.fair_key
                WHERE physical_client.client_id IS NULL OR q.provider_key IS NULL)""") != 0L ||
            c.count("""SELECT EXISTS(SELECT 1 FROM provider_fair_queues q
                LEFT JOIN fair_queues physical_tree ON physical_tree.fair_key=q.fair_key
                WHERE physical_tree.fair_key IS NULL)""") != 0L) missingProviderFairness()
    }

    private fun missingProviderFairness(): Nothing = fail("ledger_corrupt",
        "Required schema4 provider scheduling ages or physical mappings are missing or inconsistent; offline owner reconciliation is required, not automatic restoration")

    private fun verifyVersion(c: Connection) {
        if (c.count("SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name='metadata'") != 1L) unsupportedVersion()
        val version = c.one("SELECT value FROM metadata WHERE key='schema_version'") { it.getString(1) }
        if (version != SCHEMA_VERSION) unsupportedVersion()
    }

    private fun verifyRequiredTables(c: Connection) {
        val present = c.query("SELECT name FROM sqlite_schema WHERE type='table'") { it.getString(1) }.toSet()
        if (!present.containsAll(REQUIRED_TABLES) || c.count("SELECT COUNT(*) FROM sqlite_schema WHERE type='view' AND name='review_executions'") != 1L) fail("ledger_corrupt",
            "Required schema4 ledger tables are missing; offline owner reconciliation is required, not automatic repair")
    }

    private fun unsupportedVersion(): Nothing = fail("unsupported_schema",
        "This broker requires NEW ledger schema4 for provider-local scheduling ages; schema1/2/3 stores are NOT migrated or reset. Preserve the old store and stop all old brokers. Schema3-to-4 requires owner-reviewed quiescent reconciliation of every accepted obligation and an explicit initial provider-age cutover; old aggregate ages cannot reconstruct provider history. Never select a new store automatically")

    companion object {
        private const val SCHEMA_VERSION = "4"
        private val REQUIRED_TABLES = setOf(
            "metadata", "clients", "instances", "fair_queues", "fair_clients", "provider_fair_queues", "provider_fair_clients", "work", "reviews",
            "parent_plans", "plan_slots", "routing_batches", "routing_members", "work_progress",
            "work_certificates", "produced_plan_admissions", "plan_slot_resolution_claims", "plan_slot_resolutions", "work_publications", "events", "receipts", "acknowledgements", "observations", "gate_cuts",
        )
        // These immutable admission/execution relations retain membership after completion,
        // cancellation or loss of a live produced grant. They are not a migration age source.
        private val FAIR_TREE_DOMAINS = """SELECT provider_key,fair_key FROM work
            UNION ALL SELECT provider_key,fair_key FROM produced_plan_admissions"""
        private val FAIR_CLIENT_DOMAINS = """SELECT w.provider_key,r.client_id,w.fair_key
            FROM review_executions r JOIN work w ON w.work_key=r.work_key
            UNION ALL SELECT a.provider_key,p.client_id,a.fair_key FROM produced_plan_admissions a
                JOIN parent_plans p ON p.plan_id=a.plan_id"""
        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
            """CREATE TABLE IF NOT EXISTS clients(
                client_id TEXT PRIMARY KEY,binding_json TEXT NOT NULL,client_hash TEXT NOT NULL UNIQUE,
                adapter_hash TEXT NOT NULL UNIQUE,epoch INTEGER NOT NULL DEFAULT 0,
                next_review INTEGER NOT NULL DEFAULT 1,next_event INTEGER NOT NULL DEFAULT 1,
                gate_state TEXT NOT NULL DEFAULT 'open',active_cut_id TEXT,
                routed_obligations INTEGER NOT NULL DEFAULT 0 CHECK(routed_obligations IN (0,1)))""",
            """CREATE TABLE IF NOT EXISTS instances(
                instance_id TEXT PRIMARY KEY,pid INTEGER NOT NULL,process_start TEXT,
                heartbeat INTEGER NOT NULL,closed INTEGER NOT NULL DEFAULT 0)""",
            "CREATE TABLE IF NOT EXISTS fair_queues(fair_key TEXT PRIMARY KEY,last_served INTEGER NOT NULL DEFAULT 0,produced_last_served INTEGER NOT NULL DEFAULT 0)",
            """CREATE TABLE IF NOT EXISTS fair_clients(
                client_id TEXT PRIMARY KEY,fair_key TEXT NOT NULL,last_served INTEGER NOT NULL,
                FOREIGN KEY(client_id) REFERENCES clients(client_id),
                FOREIGN KEY(fair_key) REFERENCES fair_queues(fair_key))""",
            "CREATE INDEX IF NOT EXISTS fair_clients_queue ON fair_clients(fair_key,last_served)",
            """CREATE TABLE provider_fair_queues(
                provider_key TEXT NOT NULL,fair_key TEXT NOT NULL,last_served INTEGER NOT NULL CHECK(last_served>=0),
                PRIMARY KEY(provider_key,fair_key),FOREIGN KEY(fair_key) REFERENCES fair_queues(fair_key))""",
            """CREATE TABLE provider_fair_clients(
                provider_key TEXT NOT NULL,client_id TEXT NOT NULL,last_served INTEGER NOT NULL CHECK(last_served>=0),
                PRIMARY KEY(provider_key,client_id),FOREIGN KEY(client_id) REFERENCES fair_clients(client_id))""",
            """CREATE TABLE IF NOT EXISTS work(
                work_key TEXT PRIMARY KEY,input_json TEXT NOT NULL,frozen_json TEXT NOT NULL,
                provider_key TEXT NOT NULL,fair_key TEXT NOT NULL,state TEXT NOT NULL,
                owner_instance TEXT,attempt INTEGER NOT NULL DEFAULT 0,lease_until INTEGER,deadline INTEGER,
                result_json TEXT,validation_json TEXT NOT NULL DEFAULT '[]',progress_faults_json TEXT NOT NULL DEFAULT '[]',progress_limited INTEGER NOT NULL DEFAULT 0,archive_bytes INTEGER NOT NULL,
                created_at INTEGER NOT NULL,FOREIGN KEY(fair_key) REFERENCES fair_queues(fair_key))""",
            "CREATE INDEX IF NOT EXISTS work_queue ON work(state,provider_key,fair_key,created_at)",
            """CREATE TABLE IF NOT EXISTS reviews(
                review_id TEXT PRIMARY KEY,client_id TEXT NOT NULL,patch_id TEXT NOT NULL,
                idempotency_key TEXT NOT NULL,request_hash TEXT NOT NULL,request_json TEXT NOT NULL,
                input_json TEXT NOT NULL,work_key TEXT,sequence INTEGER NOT NULL,
                kind TEXT NOT NULL DEFAULT 'ordinary' CHECK(kind IN ('ordinary','produced')),
                cancelled INTEGER NOT NULL DEFAULT 0,effective_result_json TEXT,
                policy_validation_json TEXT NOT NULL DEFAULT '[]',policy_certificate TEXT,created_at INTEGER NOT NULL,
                CHECK((kind='ordinary' AND work_key IS NOT NULL) OR (kind='produced' AND work_key IS NULL)),
                UNIQUE(client_id,idempotency_key),UNIQUE(client_id,patch_id),UNIQUE(client_id,sequence),
                FOREIGN KEY(client_id) REFERENCES clients(client_id),FOREIGN KEY(work_key) REFERENCES work(work_key))""",
            "CREATE INDEX IF NOT EXISTS reviews_work ON reviews(work_key)",
            """CREATE TABLE IF NOT EXISTS parent_plans(
                plan_id TEXT PRIMARY KEY,client_id TEXT NOT NULL,patch_id TEXT NOT NULL,idempotency_key TEXT NOT NULL,
                request_hash TEXT NOT NULL,definition_json TEXT NOT NULL,plan_digest TEXT NOT NULL,
                review_frontier INTEGER NOT NULL,cancelled INTEGER NOT NULL DEFAULT 0,routing_id TEXT,
                UNIQUE(client_id,patch_id),UNIQUE(client_id,idempotency_key),
                FOREIGN KEY(client_id) REFERENCES clients(client_id))""",
            """CREATE TABLE IF NOT EXISTS plan_slots(
                plan_id TEXT NOT NULL,slot_id TEXT NOT NULL,review_id TEXT NOT NULL UNIQUE,
                PRIMARY KEY(plan_id,slot_id),FOREIGN KEY(plan_id) REFERENCES parent_plans(plan_id),
                FOREIGN KEY(review_id) REFERENCES reviews(review_id))""",
            """CREATE TABLE produced_plan_admissions(
                plan_id TEXT PRIMARY KEY,origin_instance TEXT NOT NULL,authority_identity TEXT NOT NULL,provider_key TEXT NOT NULL,
                producer_slot_id TEXT NOT NULL,consumer_slot_id TEXT NOT NULL,fair_key TEXT NOT NULL,
                archives_json TEXT NOT NULL,archive_bytes INTEGER NOT NULL,admission_digest TEXT NOT NULL,
                FOREIGN KEY(plan_id) REFERENCES parent_plans(plan_id),FOREIGN KEY(origin_instance) REFERENCES instances(instance_id))""",
            """CREATE TABLE plan_slot_resolution_claims(
                plan_id TEXT NOT NULL,slot_id TEXT NOT NULL,origin_instance TEXT NOT NULL,claim_nonce TEXT NOT NULL UNIQUE,
                reserved_archive_bytes INTEGER NOT NULL CHECK(reserved_archive_bytes>=0),
                PRIMARY KEY(plan_id,slot_id),FOREIGN KEY(plan_id,slot_id) REFERENCES plan_slots(plan_id,slot_id),
                FOREIGN KEY(origin_instance) REFERENCES instances(instance_id))""",
            """CREATE TABLE plan_slot_resolutions(
                plan_id TEXT NOT NULL,slot_id TEXT NOT NULL,review_id TEXT NOT NULL UNIQUE,
                state TEXT NOT NULL CHECK(state IN ('ready','unavailable')),record_json TEXT NOT NULL,record_digest TEXT NOT NULL,
                certificate_id TEXT,resolved_input_json TEXT,work_key TEXT UNIQUE,reasons_json TEXT NOT NULL,
                PRIMARY KEY(plan_id,slot_id),FOREIGN KEY(plan_id,slot_id) REFERENCES plan_slots(plan_id,slot_id),
                FOREIGN KEY(review_id) REFERENCES reviews(review_id),FOREIGN KEY(work_key) REFERENCES work(work_key),
                CHECK((state='ready' AND certificate_id IS NOT NULL AND resolved_input_json IS NOT NULL AND work_key IS NOT NULL)
                    OR (state='unavailable' AND certificate_id IS NULL AND resolved_input_json IS NULL AND work_key IS NULL)))""",
            """CREATE TABLE work_publications(
                work_key TEXT PRIMARY KEY,attempt INTEGER NOT NULL,origin_instance TEXT NOT NULL,
                record_json TEXT NOT NULL,certificate_id TEXT NOT NULL,
                FOREIGN KEY(work_key) REFERENCES work(work_key),FOREIGN KEY(origin_instance) REFERENCES instances(instance_id))""",
            // D remains in reviews forever; only an append-only, checked D→P relation selects P.
            // Explicit rowid supports the existing bounded round-robin policy recovery cursor.
            """CREATE VIEW review_executions AS SELECT r.rowid AS rowid,r.review_id,r.client_id,r.patch_id,
                r.idempotency_key,r.request_hash,r.request_json,r.sequence,r.cancelled,r.effective_result_json,
                r.policy_validation_json,r.policy_certificate,r.created_at,r.kind,
                r.input_json AS input_json,r.input_json AS declaration_input_json,r.work_key AS direct_work_key,
                COALESCE(x.resolved_input_json,r.input_json) AS execution_input_json,COALESCE(x.work_key,r.work_key) AS work_key,
                x.certificate_id AS execution_certificate,COALESCE(x.state,CASE WHEN r.kind='produced' THEN 'deferred' ELSE 'ordinary' END) AS resolution_state,
                COALESCE(x.reasons_json,'[]') AS resolution_reasons_json
                FROM reviews r LEFT JOIN plan_slot_resolutions x ON x.review_id=r.review_id AND r.kind='produced'
                    AND EXISTS(SELECT 1 FROM plan_slots s JOIN produced_plan_admissions a ON a.plan_id=s.plan_id
                        WHERE s.plan_id=x.plan_id AND s.slot_id=x.slot_id AND s.review_id=r.review_id)""",
            """CREATE TABLE IF NOT EXISTS routing_batches(
                routing_id TEXT PRIMARY KEY,origin_client_id TEXT NOT NULL,origin_plan_id TEXT NOT NULL UNIQUE,
                definition_json TEXT NOT NULL,routing_digest TEXT NOT NULL,
                FOREIGN KEY(origin_client_id) REFERENCES clients(client_id),
                FOREIGN KEY(origin_plan_id) REFERENCES parent_plans(plan_id))""",
            """CREATE TABLE IF NOT EXISTS routing_members(
                routing_id TEXT NOT NULL,client_id TEXT NOT NULL,plan_id TEXT NOT NULL UNIQUE,
                PRIMARY KEY(routing_id,client_id),FOREIGN KEY(routing_id) REFERENCES routing_batches(routing_id),
                FOREIGN KEY(client_id) REFERENCES clients(client_id),FOREIGN KEY(plan_id) REFERENCES parent_plans(plan_id))""",
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
