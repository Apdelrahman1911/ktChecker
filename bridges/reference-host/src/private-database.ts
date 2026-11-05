import { DatabaseSync, type SQLInputValue, type SQLOutputValue, type StatementResultingChanges } from 'node:sqlite';
import { closeSync, lstatSync, mkdirSync, openSync } from 'node:fs';
import { join } from 'node:path';
import { assertPrivateStatePlatform, darwinPrivateStateBackend, nativePrivateStatePath,
  type DarwinPrivateStateBackend, type DarwinStoreCapability } from '@ktchecker/mcp-preview/private-state';

export class HostError extends Error { constructor(readonly code: string) { super(code); this.name = 'HostError'; } }
export function privatePath(path: string, directory: boolean): void {
  const backend = darwinPrivateStateBackend();
  if (backend) { backend.verifyPath(nativePrivateStatePath(path), directory); return; }
  const stat = lstatSync(path);
  if (stat.isSymbolicLink() || (directory ? !stat.isDirectory() : !stat.isFile())) throw new HostError('unsafe_state_path');
  if ((stat.mode & 0o077) !== 0 || (process.getuid && stat.uid !== process.getuid())) throw new HostError('state_permissions');
}

const sqliteVersion = '3.53.4';
const sqliteSourceId = '2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc';

// The source-reviewed confinement is for THIS finite host SQL surface, not an
// arbitrary SQLite API. New SQL requires review. No ATTACH/VACUUM/backup/session,
// extensions, functions, caller SQL, alternate VFS, proxy, or temp-dir setting.
const execSql = new Set([
  'BEGIN IMMEDIATE', 'COMMIT', 'ROLLBACK',
  'CREATE TABLE IF NOT EXISTS journal (id INTEGER PRIMARY KEY CHECK(id=1), state TEXT NOT NULL)',
  'CREATE TABLE IF NOT EXISTS session (id INTEGER PRIMARY KEY CHECK(id=1), state TEXT NOT NULL)',
  'CREATE TABLE IF NOT EXISTS controller (id INTEGER PRIMARY KEY CHECK(id=1), owner TEXT NOT NULL, expires INTEGER NOT NULL)',
]);
const statementSql = new Set([
  'SELECT state FROM journal WHERE id=1', 'SELECT state FROM session WHERE id=1',
  'SELECT owner,expires FROM controller WHERE id=1', 'SELECT expires FROM controller WHERE id=1',
  'SELECT owner FROM controller WHERE id=1',
  'INSERT INTO journal(id,state) VALUES(1,?)', 'INSERT INTO session(id,state) VALUES(1,?)',
  'INSERT INTO controller(id,owner,expires) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET owner=excluded.owner,expires=excluded.expires',
  'UPDATE journal SET state=? WHERE id=1', 'UPDATE session SET state=? WHERE id=1',
  'UPDATE controller SET expires=? WHERE id=1 AND owner=?', 'DELETE FROM controller WHERE id=1 AND owner=?',
]);
interface Lease {
  backend: DarwinPrivateStateBackend;
  capability: DarwinStoreCapability;
  database?: DatabaseSync;
  uncertain: boolean;
}
// A process/isolate-lifetime STRONG ledger, not a WeakMap/finalizer. Native also
// retains abandoned capabilities through environment teardown. Removing a
// record requires confirmed SQLite close followed by confirmed native close.
const leases = new Set<Lease>();
interface PrivateStatement {
  get(...parameters: SQLInputValue[]): Record<string, SQLOutputValue> | undefined;
  run(...parameters: SQLInputValue[]): StatementResultingChanges;
}

/** Internal fixed-SQL wrapper. It never opens/closes extra DB/WAL/SHM file FDs. */
export class PrivateDatabase {
  readonly path: string;
  readonly #raw: DatabaseSync;
  readonly #lease: Lease | null;
  #closed = false;
  #problem = false;

  constructor(directory: string, basename: 'inbox.sqlite' | 'codex.sqlite') {
    assertPrivateStatePlatform();
    const backend = darwinPrivateStateBackend();
    if (backend) {
      const capability = backend.openStore(nativePrivateStatePath(directory), basename);
      this.path = capability.databasePath;
      const lease: Lease = { backend, capability, uncertain: false };
      this.#lease = lease;
      leases.add(lease);
      try {
        // Retain an actual object BEFORE attempting OS open. A throwing open is
        // quarantined: internal cleanup is not our positive close confirmation.
        this.#raw = new DatabaseSync(this.path, { open: false, allowExtension: false });
        lease.database = this.#raw;
      } catch (error) {
        try { backend.closeStore(capability); leases.delete(lease); }
        catch { lease.uncertain = true; }
        throw error;
      }
      try { this.#raw.open(); }
      catch (error) { lease.uncertain = true; this.#problem = true; throw error; }
    } else {
      this.#lease = null;
      mkdirSync(directory, { recursive: true, mode: 0o700 }); privatePath(directory, true);
      this.path = join(directory, basename);
      try { closeSync(openSync(this.path, 'wx', 0o600)); }
      catch (error) { if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error; }
      privatePath(this.path, false);
      this.#raw = new DatabaseSync(this.path);
    }
    try {
      if (this.#lease) this.#configureDarwin();
      else this.#raw.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA trusted_schema=OFF; PRAGMA busy_timeout=5000;');
    } catch (error) {
      try { this.close(); } catch { /* Native lease remains strongly quarantined. */ }
      throw error;
    }
  }
  #checked<T>(operation: () => T): T {
    if (this.#closed || this.#problem || this.#lease?.uncertain) throw new HostError('private_database_unavailable');
    const verify = () => {
      if (this.#lease) {
        try { this.#lease.backend.verifyStore(this.#lease.capability); }
        catch (error) { this.#problem = true; throw error; }
      }
    };
    verify();
    try { return operation(); }
    finally { verify(); }
  }
  #configureDarwin(): void {
    // MUST be the FIRST SQL on this newly opened connection. Unqualified
    // temp_store avoids opening the temp schema before the setting takes effect.
    this.#checked(() => this.#raw.exec('PRAGMA temp_store=MEMORY'));
    const scalar = (sql: string, name: string) => this.#checked(() => this.#raw.prepare(sql).get()?.[name]);
    if (scalar('PRAGMA temp_store', 'temp_store') !== 2) throw new HostError('darwin_sqlite_temp_unqualified');
    if (scalar('SELECT sqlite_version() AS version', 'version') !== sqliteVersion ||
        scalar('SELECT sqlite_source_id() AS sourceId', 'sourceId') !== sqliteSourceId)
      throw new HostError('darwin_sqlite_build_unqualified');
    const rows = this.#checked(() => this.#raw.prepare('PRAGMA compile_options').all());
    if (rows.length > 256 || rows.some(row => typeof row.compile_options !== 'string')) throw new HostError('darwin_sqlite_build_unqualified');
    const options = new Set(rows.map(row => row.compile_options as string));
    if (!options.has('TEMP_STORE=1') || [...options].some(option =>
      /^(?:OMIT_MEMORYDB|OMIT_PAGER_PRAGMAS|OMIT_WAL|SHM_DIRECTORY|LOCKPROXYDIR|PREFER_PROXY_LOCKING)(?:=|$)/.test(option)))
      throw new HostError('darwin_sqlite_build_unqualified');
    // Compile options are supplemental, NOT the binary-origin proof. The loader
    // separately binds the selected official Node release/build receipt.
    this.#checked(() => this.#raw.exec('PRAGMA trusted_schema=OFF'));
    if (scalar('PRAGMA trusted_schema', 'trusted_schema') !== 0) throw new HostError('darwin_sqlite_schema_unqualified');
    this.#checked(() => this.#raw.exec('PRAGMA journal_mode=WAL'));
    if (scalar('PRAGMA journal_mode', 'journal_mode') !== 'wal') throw new HostError('darwin_sqlite_wal_unqualified');
    this.#checked(() => this.#raw.exec('PRAGMA synchronous=FULL'));
    if (scalar('PRAGMA synchronous', 'synchronous') !== 2) throw new HostError('darwin_sqlite_sync_unqualified');
    this.#checked(() => this.#raw.exec('PRAGMA busy_timeout=5000'));
    if (scalar('PRAGMA busy_timeout', 'timeout') !== 5000) throw new HostError('darwin_sqlite_timeout_unqualified');
  }
  exec(sql: string): void {
    if (this.#lease && !execSql.has(sql)) throw new HostError('darwin_sql_surface_unqualified');
    this.#checked(() => this.#raw.exec(sql));
  }
  prepare(sql: string): PrivateStatement {
    if (this.#lease && !statementSql.has(sql)) throw new HostError('darwin_sql_surface_unqualified');
    const statement = this.#checked(() => this.#raw.prepare(sql));
    return {
      get: (...parameters) => this.#checked(() => statement.get(...parameters)),
      run: (...parameters) => this.#checked(() => statement.run(...parameters)),
    };
  }
  close(): void {
    if (this.#closed) return;
    if (this.#lease?.uncertain) throw new HostError('darwin_database_close_uncertain');
    try {
      // Node24.20.0 finalizes all tracked statements/deletes sessions before
      // sqlite3_close_v2; this wrapper exposes no backup/session API. Capability
      // stays alive and locked throughout SQLite cleanup, including failures.
      this.#raw.close();
      if (this.#raw.isOpen) throw new HostError('darwin_database_close_uncertain');
      if (this.#lease) {
        this.#lease.backend.closeStore(this.#lease.capability);
        leases.delete(this.#lease);
      }
      this.#closed = true;
    } catch (error) {
      this.#problem = true;
      if (this.#lease) this.#lease.uncertain = true;
      throw error;
    }
  }
}
