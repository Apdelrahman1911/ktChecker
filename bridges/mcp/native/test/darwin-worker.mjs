// Directly owned, bounded native test child. No broker, Codex, account, model,
// provider, compiler, old attempt discovery, or external owner state is used.
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { isAbsolute, join } from 'node:path';
import { darwinPrivateStateBackend } from '../../dist/src/private-state.js';
import { PrivateDatabase } from '../../../reference-host/dist/src/private-database.js';

assert.equal(process.platform, 'darwin');
assert.equal(process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL, '1');
const [control, directory] = process.argv.slice(2);
assert.ok(directory && isAbsolute(directory) && directory.includes('/.ktc-darwin-native-'));
const backend = darwinPrivateStateBackend();
assert.ok(backend);
if (control === 'namespace-busy') {
  assert.throws(() => backend.openStore(directory, 'inbox.sqlite'), /consumer_controller_busy/);
} else if (control === 'sqlite-busy' || control === 'sqlite-free') {
  const db = new DatabaseSync(join(directory, 'inbox.sqlite'), { allowExtension: false });
  try {
    db.exec('PRAGMA temp_store=MEMORY'); db.exec('PRAGMA busy_timeout=0');
    if (control === 'sqlite-busy') assert.throws(() => db.exec('BEGIN IMMEDIATE'), /locked|busy/i);
    else { db.exec('BEGIN IMMEDIATE'); db.exec('ROLLBACK'); }
  } finally { db.close(); }
} else if (control === 'uncertain-close') {
  let db = new PrivateDatabase(directory, 'inbox.sqlite');
  const close = DatabaseSync.prototype.close;
  try {
    DatabaseSync.prototype.close = () => { throw new Error('injected_close_uncertainty'); };
    assert.throws(() => db.close(), /injected_close_uncertainty/);
  } finally { DatabaseSync.prototype.close = close; }
  db = null;
  assert.equal(typeof globalThis.gc, 'function'); globalThis.gc(); globalThis.gc();
  assert.throws(() => backend.openStore(directory, 'inbox.sqlite'), /consumer_controller_busy/);
} else if (control === 'abandoned-capability') {
  let capability = backend.openStore(directory, 'inbox.sqlite');
  backend.verifyStore(capability); capability = null;
  assert.equal(typeof globalThis.gc, 'function'); globalThis.gc(); globalThis.gc();
  assert.throws(() => backend.openStore(directory, 'inbox.sqlite'), /consumer_controller_busy/);
} else if (control === 'crash-wal') {
  const db = new PrivateDatabase(directory, 'inbox.sqlite');
  db.exec('CREATE TABLE IF NOT EXISTS session (id INTEGER PRIMARY KEY CHECK(id=1), state TEXT NOT NULL)');
  db.prepare('INSERT INTO session(id,state) VALUES(1,?)').run('synthetic-crash-committed');
  // ONLY this newly spawned synthetic child. No parent/descendant guessing.
  process.kill(process.pid, 'SIGKILL');
  throw new Error('owned_crash_control_did_not_exit');
} else throw new Error('unknown_native_test_control');
process.stdout.write(JSON.stringify({ control, passed: true }) + '\n');
