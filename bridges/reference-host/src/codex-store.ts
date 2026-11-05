import { DatabaseSync } from 'node:sqlite';
import { mkdirSync, openSync, closeSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { canonical, HostError, privatePath } from './journal.js';
import type { ClientBinding, ContextItem, InsertedContext, ModelRequest } from './types.js';
import type { ApplicationRequestEvidence } from './codex-app-server.js';

export interface CodexInvocation {
  request: ModelRequest;
  /** Missing only in legacy low-effort records; never infer a different owner selection. */
  effort?: string;
  applicationRequest: ApplicationRequestEvidence;
  turnId: string | null;
  status: 'uncertain' | 'verified' | 'rejected';
  publicResponseDigest: string | null;
  publicItemId: string | null;
}
export interface CodexState {
  version: 1;
  client: ClientBinding;
  cwd: string;
  model: string;
  /** Missing version-1 fields retain the historical exact low-effort interpretation. */
  effort?: string;
  creationStarted: boolean;
  threadId: string | null;
  contexts: Record<string, { item: ContextItem; receiptId: string; attempts: number; inserted: InsertedContext | null }>;
  invocations: Record<string, CodexInvocation>;
}

/** Private, leased controller metadata. No model-facing endpoint can choose the thread or edit receipts. */
export class CodexStore {
  private readonly db: DatabaseSync;
  private readonly owner = randomUUID();
  private readonly heartbeat: NodeJS.Timeout;
  private closed = false;
  private problem = false;
  constructor(directory: string, client: ClientBinding, cwd: string, model: string, effort = 'low') {
    if (typeof effort !== 'string' || !/^[a-z][a-z0-9_-]{0,31}$/.test(effort)) throw new HostError('invalid_codex_effort');
    mkdirSync(directory, { recursive: true, mode: 0o700 }); privatePath(directory, true);
    const path = join(directory, 'codex.sqlite');
    try { closeSync(openSync(path, 'wx', 0o600)); } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
    }
    privatePath(path, false);
    this.db = new DatabaseSync(path);
    try {
      this.db.exec('PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA trusted_schema=OFF; PRAGMA busy_timeout=5000;');
      this.db.exec('CREATE TABLE IF NOT EXISTS session (id INTEGER PRIMARY KEY CHECK(id=1), state TEXT NOT NULL); CREATE TABLE IF NOT EXISTS controller (id INTEGER PRIMARY KEY CHECK(id=1), owner TEXT NOT NULL, expires INTEGER NOT NULL); BEGIN IMMEDIATE;');
      const saved = this.db.prepare('SELECT state FROM session WHERE id=1').get() as { state: string } | undefined;
      if (saved) {
        const state = JSON.parse(saved.state) as CodexState;
        if (state.version !== 1 || canonical(state.client) !== canonical(client) || state.cwd !== cwd || state.model !== model ||
            (state.effort === undefined ? 'low' : state.effort) !== effort ||
            Object.values(state.invocations).some(value => (value.effort === undefined ? 'low' : value.effort) !== effort)) {
          throw new HostError('codex_session_binding_mismatch');
        }
      } else {
        const state: CodexState = { version: 1, client, cwd, model, effort, creationStarted: false, threadId: null, contexts: {}, invocations: {} };
        this.db.prepare('INSERT INTO session(id,state) VALUES(1,?)').run(JSON.stringify(state));
      }
      const lease = this.db.prepare('SELECT expires FROM controller WHERE id=1').get() as { expires: number } | undefined;
      if (lease && lease.expires > Date.now()) throw new HostError('codex_controller_busy');
      this.db.prepare('INSERT INTO controller(id,owner,expires) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET owner=excluded.owner,expires=excluded.expires').run(this.owner, Date.now() + 15_000);
      this.db.exec('COMMIT');
    } catch (error) { try { this.db.exec('ROLLBACK'); } catch {} this.db.close(); throw error; }
    this.heartbeat = setInterval(() => { try { this.edit(() => {}); } catch { this.problem = true; } }, 5000);
    this.heartbeat.unref();
  }
  read(): CodexState {
    if (this.closed || this.problem) throw new HostError('codex_controller_lease_lost');
    const lease = this.db.prepare('SELECT owner FROM controller WHERE id=1').get() as { owner: string } | undefined;
    if (lease?.owner !== this.owner) throw new HostError('codex_controller_lease_lost');
    return JSON.parse((this.db.prepare('SELECT state FROM session WHERE id=1').get() as { state: string }).state) as CodexState;
  }
  edit(operation: (state: CodexState) => void): void {
    if (this.closed || this.problem) throw new HostError('codex_controller_lease_lost');
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const state = this.read(); operation(state);
      const bytes = JSON.stringify(state);
      if (Buffer.byteLength(bytes) > 16 * 1024 * 1024 || Object.keys(state.contexts).length > 4096 || Object.keys(state.invocations).length > 256) throw new HostError('codex_store_capacity');
      this.db.prepare('UPDATE session SET state=? WHERE id=1').run(bytes);
      this.db.prepare('UPDATE controller SET expires=? WHERE id=1 AND owner=?').run(Date.now() + 15_000, this.owner);
      this.db.exec('COMMIT');
    } catch (error) { this.db.exec('ROLLBACK'); throw error; }
  }
  close(): void {
    if (this.closed) return; clearInterval(this.heartbeat);
    try { this.db.prepare('DELETE FROM controller WHERE id=1 AND owner=?').run(this.owner); }
    finally { this.closed = true; this.db.close(); }
  }
}
