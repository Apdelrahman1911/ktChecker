// Native component controls, not a macOS qualification certificate. Intentionally
// FAIL (never skip) on another platform, absent artifacts, or missing consent.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, copyFileSync, existsSync, linkSync, lstatSync, mkdirSync, mkdtempSync,
  readFileSync, readdirSync, renameSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { darwinPrivateStateBackend, assertPrivateStatePlatform } from '../../dist/src/private-state.js';
import { InboxJournal } from '../../../reference-host/dist/src/journal.js';
import { CodexStore } from '../../../reference-host/dist/src/codex-store.js';
import { PrivateDatabase } from '../../../reference-host/dist/src/private-database.js';

assert.equal(process.platform, 'darwin');
assert.notEqual(process.getuid(), 0, 'backend calls must run as an ordinary user');
assert.equal(process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL, '1');
assert.equal(process.env.KTC_DARWIN_NATIVE_PRIVILEGED_FIXTURES, '1', 'isolated chown fixture consent is required, not silently skipped');
const backend = darwinPrivateStateBackend();
assert.ok(backend && backend.abi === 1);
const nativeRoot = fileURLToPath(new URL('..', import.meta.url));
const require = createRequire(import.meta.url);
const client = id => ({ ownerId: 'native-fixture-owner', workspaceId: 'workspace', worktreeId: 'tree',
  clientId: id, lineageId: 'lineage-' + id, generation: 1 });
const binding = id => ({ client: client(id), hostKind: 'native-store-component-test', hostThreadId: 'synthetic-thread-' + id });
function command(path, args) {
  const result = spawnSync(path, args, { encoding: 'utf8', timeout: 10000, maxBuffer: 65536, stdio: ['ignore', 'pipe', 'pipe'] });
  assert.equal(result.error, undefined, 'owned fixture command failed');
  assert.equal(result.status, 0, 'owned fixture command returned failure');
}
function fixture(t) {
  const root = mkdtempSync(join(homedir(), '.ktc-darwin-native-'));
  chmodSync(root, 0o700);
  const aclPaths = new Set();
  t.after(() => {
    for (const path of aclPaths) if (existsSync(path) && !lstatSync(path).isSymbolicLink()) command('/bin/chmod', ['-N', path]);
    rmSync(root, { recursive: true, force: true });
  });
  return {
    root,
    dir(name) { const path = join(root, name); mkdirSync(path, { mode: 0o700 }); return path; },
    acl(path, entry) { command('/bin/chmod', ['+a', entry, path]); aclPaths.add(path); },
    clearAcl(path) { command('/bin/chmod', ['-N', path]); aclPaths.delete(path); },
  };
}
function privateFile(path, contents = '') { writeFileSync(path, contents, { flag: 'wx', mode: 0o600 }); }
function child(control, directory) {
  const result = spawnSync(process.execPath, ['--expose-gc', fileURLToPath(new URL('darwin-worker.mjs', import.meta.url)), control, directory],
    { encoding: 'utf8', env: { ...process.env }, timeout: 15000, maxBuffer: 65536, stdio: ['ignore', 'pipe', 'pipe'] });
  assert.equal(result.error, undefined, 'bounded owned native child failed');
  if (control === 'crash-wal') { assert.equal(result.signal, 'SIGKILL'); return; }
  assert.equal(result.status, 0, 'native component child failed');
  assert.deepEqual(JSON.parse(result.stdout), { control, passed: true });
}

test('actual native artifact and exact runtime/source receipt are selected', () => {
  const receipt = JSON.parse(readFileSync(join(nativeRoot, `darwin-${process.arch}/manifest.json`), 'utf8'));
  assert.equal(receipt.status, 'experimental-unqualified');
  assert.equal(receipt.nodeVersion, process.versions.node);
  assert.equal(receipt.arch, process.arch);
  assert.equal(receipt.sourceSha256, backend.sourceSha256);
  assert.equal('testFilesecFaults' in backend, false);
});
test('default opt-out still denies after native loading', () => {
  delete process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL;
  try { assert.throws(() => assertPrivateStatePlatform(), /private_state_platform_unqualified/); }
  finally { process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL = '1'; }
});
test('empty and DENY-only ACL objects admit descriptor verification without ACL repair', t => {
  const f = fixture(t); const state = f.dir('state');
  f.acl(state, 'everyone deny delete');
  const capability = backend.openStore(state, 'inbox.sqlite');
  try { backend.verifyStore(capability); assert.equal(lstatSync(capability.databasePath).mode & 0o777, 0o600); }
  finally { backend.closeStore(capability); }
  const plain = backend.openStore(f.dir('plain'), 'codex.sqlite');
  backend.verifyStore(plain); backend.closeStore(plain);
});
test('every positive grant, including current-owner and inherit-only grants, is rejected before member creation', t => {
  const f = fixture(t);
  for (const [index, entry] of [`user:${process.getuid()} allow read`, 'everyone allow read',
    'everyone allow read,file_inherit,directory_inherit,only_inherit'].entries()) {
    const path = f.dir('allow-' + index); f.acl(path, entry);
    assert.throws(() => backend.openStore(path, 'inbox.sqlite'), /darwin_acl_allow_unqualified/);
    assert.deepEqual(readdirSync(path), []);
  }
});
test('inherited ALLOW on a child remains rejected after safe parent ACL removal', t => {
  const f = fixture(t); const parent = f.dir('parent');
  f.acl(parent, 'everyone allow read,file_inherit,directory_inherit');
  const state = join(parent, 'state'); mkdirSync(state, { mode: 0o700 });
  f.clearAcl(parent);
  // Record only our child for fixture ACL cleanup; no production ACL mutation.
  f.acl(state, 'everyone deny delete');
  assert.throws(() => backend.openStore(state, 'inbox.sqlite'), /darwin_acl_allow_unqualified/);
  assert.deepEqual(readdirSync(state), []);
});
test('group-writable and foreign-owned readonly ancestors reject before creating descendants', t => {
  const f = fixture(t); const group = f.dir('group'); chmodSync(group, 0o770);
  assert.throws(() => backend.openStore(join(group, 'uncreated'), 'inbox.sqlite'), /darwin_unsafe_ancestor/);
  assert.equal(existsSync(join(group, 'uncreated')), false);
  const foreign = f.dir('foreign'); chmodSync(foreign, 0o755);
  command('/usr/bin/sudo', ['-n', '/usr/sbin/chown', '1:1', foreign]);
  try {
    assert.notEqual(process.getuid(), 0);
    assert.throws(() => backend.openStore(join(foreign, 'uncreated'), 'inbox.sqlite'), /darwin_unsafe_ancestor/);
    assert.equal(existsSync(join(foreign, 'uncreated')), false);
  } finally { command('/usr/bin/sudo', ['-n', '/usr/sbin/chown', `${process.getuid()}:${process.getgid()}`, foreign]); }
});
test('protected ordinary-user readonly ancestors remain acceptable', t => {
  const f = fixture(t); const parent = f.dir('readonly'); const state = join(parent, 'state'); mkdirSync(state, { mode: 0o700 });
  chmodSync(parent, 0o500);
  try { const held = backend.openStore(state, 'inbox.sqlite'); backend.verifyStore(held); backend.closeStore(held); }
  finally { chmodSync(parent, 0o700); }
});
test('symlinks, hardlinks, unknown members and nonempty rollback journals are not modified or repaired', t => {
  const f = fixture(t); const target = join(f.root, 'sentinel'); privateFile(target, 'untouched');
  const symbolic = f.dir('symbolic'); symlinkSync(target, join(symbolic, 'inbox.sqlite'));
  assert.throws(() => backend.openStore(symbolic, 'inbox.sqlite'), /unsafe_state_path/);
  const hard = f.dir('hard'); linkSync(target, join(hard, 'inbox.sqlite'));
  assert.throws(() => backend.openStore(hard, 'inbox.sqlite'), /unsafe_state_path/);
  const unknown = f.dir('unknown'); privateFile(join(unknown, 'extra'), 'untouched');
  assert.throws(() => backend.openStore(unknown, 'inbox.sqlite'), /darwin_unproven_state_directory/);
  const journal = f.dir('journal'); privateFile(join(journal, 'inbox.sqlite-journal'), 'unqualified-super-journal-content');
  assert.throws(() => backend.openStore(journal, 'inbox.sqlite'), /darwin_rollback_recovery_unqualified/);
  assert.equal(existsSync(join(journal, 'inbox.sqlite')), false);
  assert.equal(readFileSync(target, 'utf8'), 'untouched');
  assert.equal(readFileSync(join(journal, 'inbox.sqlite-journal'), 'utf8'), 'unqualified-super-journal-content');
});
test('held namespace substitution is detected; replacement target remains untouched', t => {
  const f = fixture(t); const state = f.dir('state'); const replacement = f.dir('replacement');
  const held = backend.openStore(state, 'inbox.sqlite');
  try {
    renameSync(state, join(f.root, 'original')); symlinkSync(replacement, state);
    assert.throws(() => backend.verifyStore(held), /darwin_namespace_changed/);
    assert.deepEqual(readdirSync(replacement), []);
  } finally { backend.closeStore(held); }
});
test('config bytes use the bounded descriptor path, including empty and exact-bound files', t => {
  const f = fixture(t); const config = join(f.root, 'config'); privateFile(config, '');
  assert.deepEqual(backend.readConfig(config), Buffer.alloc(0));
  writeFileSync(config, Buffer.alloc(1048576, 0x61));
  assert.equal(backend.readConfig(config).length, 1048576);
  writeFileSync(config, Buffer.alloc(1048577, 0x61));
  assert.throws(() => backend.readConfig(config), /unsafe_host_configuration/);
  assert.equal(statSync(config).size, 1048577);
});
test('fresh filesec incomplete-success and scalar/API faults always reject (test-only native injection)', t => {
  const f = fixture(t); const state = f.dir('state');
  const fault = require(join(nativeRoot, `test-output/darwin-${process.arch}/filesec-fault.node`));
  assert.equal(fault.testFilesecFaults, true);
  try {
    for (const mode of ['incomplete', 'owner-missing', 'group-missing', 'mode-missing', 'owner-mismatch', 'api-failure']) {
      process.env.KTC_DARWIN_FILESEC_FAULT = mode;
      assert.throws(() => fault.openStore(state, 'inbox.sqlite'), /darwin_acl_api_failure/);
      assert.deepEqual(readdirSync(state), []);
    }
  } finally { delete process.env.KTC_DARWIN_FILESEC_FAULT; }
});
test('same-process, other-process and separate-addon admission/readers cannot close away SQLite locks', t => {
  const f = fixture(t); const state = f.dir('state'); const db = new PrivateDatabase(state, 'inbox.sqlite');
  try {
    db.exec('BEGIN IMMEDIATE');
    assert.throws(() => backend.openStore(state, 'inbox.sqlite'), /consumer_controller_busy/);
    assert.throws(() => backend.verifyPath(join(state, 'inbox.sqlite'), false), /darwin_live_database_namespace/);
    assert.throws(() => backend.readConfig(join(state, 'inbox.sqlite')), /darwin_live_database_namespace/);
    const copy = join(f.root, 'separate-addon.node'); copyFileSync(join(nativeRoot, `darwin-${process.arch}/private-state.node`), copy);
    const other = require(copy); assert.notEqual(other, backend);
    assert.throws(() => other.openStore(state, 'inbox.sqlite'), /consumer_controller_busy/);
    assert.throws(() => other.verifyPath(join(state, 'inbox.sqlite'), false), /darwin_live_database_namespace/);
    child('namespace-busy', state); child('sqlite-busy', state);
    db.exec('ROLLBACK');
  } finally { db.close(); }
  child('sqlite-free', state);
});
test('uncertain close and abandoned GC capabilities retain namespace until their own process exits', t => {
  const f = fixture(t);
  for (const control of ['uncertain-close', 'abandoned-capability']) {
    const state = f.dir(control); child(control, state);
    const held = backend.openStore(state, 'inbox.sqlite'); backend.verifyStore(held); backend.closeStore(held);
  }
});
test('actual WAL commit survives an owned abrupt child death and reopens durably', t => {
  const f = fixture(t); const state = f.dir('crash'); child('crash-wal', state);
  assert.ok(statSync(join(state, 'inbox.sqlite-wal')).size > 0);
  const db = new PrivateDatabase(state, 'inbox.sqlite');
  try { assert.equal(db.prepare('SELECT state FROM session WHERE id=1').get().state, 'synthetic-crash-committed'); }
  finally { db.close(); }
});
test('native Inbox/Codex stores persist separate consumer and exact binding/effort identities', t => {
  const f = fixture(t); const aPath = f.dir('inbox-a'); const bPath = f.dir('inbox-b');
  let a = new InboxJournal(aPath, binding('a')); const b = new InboxJournal(bPath, binding('b'));
  try { a.edit(state => { state.fetchCursor = 'durable-a'; }); assert.equal(b.read().fetchCursor, null); }
  finally { a.close(); b.close(); }
  a = new InboxJournal(aPath, binding('a')); assert.equal(a.read().fetchCursor, 'durable-a'); a.close();
  assert.throws(() => new InboxJournal(aPath, binding('b')), /session_generation_or_binding_mismatch/);
  const path = f.dir('codex'); let store = new CodexStore(path, client('a'), f.root, 'synthetic-model-no-provider', 'low');
  store.edit(state => { state.threadId = 'synthetic-persisted-thread'; }); store.close();
  store = new CodexStore(path, client('a'), f.root, 'synthetic-model-no-provider', 'low');
  assert.equal(store.read().threadId, 'synthetic-persisted-thread'); store.close();
  assert.throws(() => new CodexStore(path, client('a'), f.root, 'synthetic-model-no-provider', 'max'), /codex_session_binding_mismatch/);
});
test('finite Darwin SQL surface denies temp/VFS/ATTACH changes', t => {
  const f = fixture(t); const db = new PrivateDatabase(f.dir('state'), 'inbox.sqlite');
  try {
    for (const sql of ['PRAGMA temp_store=FILE', "ATTACH 'outside' AS another", 'VACUUM', 'PRAGMA lock_proxy_file=auto'])
      assert.throws(() => db.exec(sql), /darwin_sql_surface_unqualified/);
    assert.throws(() => db.prepare('SELECT load_extension(?)'), /darwin_sql_surface_unqualified/);
  } finally { db.close(); }
});
