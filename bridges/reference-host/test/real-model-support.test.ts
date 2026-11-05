/** Pure/data and newly-owned temporary-file controls only. NEVER launch RealModelOwnerMain. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, linkSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, truncateSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { canonical, digest } from '../src/journal.js';
import { absoluteReal, hashRealFile, jsonReal, loadRealSelection, makeFreshRealDirectory, parseRealModelArgs, readRealFile,
  realRunBoundary, writeNewRealJson, REAL_MODEL_CASE, REAL_MODEL_TREE_BYTES } from './real-model-run-support.js';
import { loadRealCodexSelection, preflightRealCodex, REAL_CODEX_EFFORT, REAL_CODEX_ELF_BYTES, REAL_CODEX_ELF_DIGEST,
  REAL_CODEX_MODEL, REAL_CODEX_SCHEMA_AGGREGATE, REAL_CODEX_SCHEMA_DIGEST } from './real-model-codex-selection.js';

function directory(t: { after(fn: () => void): void }): string {
  const path = mkdtempSync(join(tmpdir(), 'ktchecker-real-model-unit-'));
  t.after(() => rmSync(path, { recursive: true, force: true })); return path;
}
const sha = 'sha256:' + 'a'.repeat(64);
const args = ['--selection', '/tmp/synthetic-selection', '--selection-sha256', sha,
  '--codex-selection', '/tmp/synthetic-codex', '--codex-selection-sha256', sha, '--approve-real-no-test-feasibility'];
// The companion deliberately requires Linux self-fd anchoring and pinned Linux
// executable selections. Unsupported hosts must not pass these positive I/O
// controls by repeatedly exercising only that guard. Skips remain explicit,
// machine-readable unavailable coverage, never native/release qualification.
const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_proc_self_fd_secure_io_required' : false };

if (process.platform !== 'linux') {
  test('unsupported platforms reject Linux-only real-model support before filesystem side effects', t => {
    const root = directory(t), selected = join(root, 'missing-selection.json'), fresh = join(root, 'new-run');
    const operations = [
      () => absoluteReal(fresh),
      () => parseRealModelArgs(args),
      () => loadRealSelection(selected, sha),
      () => loadRealCodexSelection(selected, sha),
      () => writeNewRealJson(join(root, 'new-evidence.json'), { synthetic: true }),
      () => readRealFile(selected, 1024),
      () => hashRealFile(selected),
      () => makeFreshRealDirectory(fresh),
      () => realRunBoundary(root),
    ];
    assert.deepEqual(readdirSync(root), []);
    for (const operation of operations) {
      assert.throws(operation, { code: 'real_model_absolute_path_required' });
      assert.deepEqual(readdirSync(root), []);
    }
  });
}

test('live fixture entry requires every explicit fixed selection and approval, with no defaults', linuxOnly, () => {
  assert.deepEqual(parseRealModelArgs(args), { selectionPath: '/tmp/synthetic-selection', selectionDigest: sha,
    codexPath: '/tmp/synthetic-codex', codexDigest: sha });
  for (const bad of [[], args.slice(0, -1), [...args, '--retry'], args.map(value => value === sha ? 'not-a-digest' : value),
    args.map(value => value === '--selection' ? '--selection-path' : value)]) assert.throws(() => parseRealModelArgs(bad));
});
test('live fixture paths reject aliases/traversal/control data before filesystem admission', linuxOnly, () => {
  for (const path of ['relative', '/', '/tmp/../tmp/x', '/tmp//x', '/tmp/x/', '/tmp/with\\separator', '/tmp/line\nfeed'])
    assert.throws(() => absoluteReal(path));
  assert.equal(absoluteReal('/tmp/new-owner-run'), '/tmp/new-owner-run');
});
test('strict selection digest and field set are checked without launching any selected executable', linuxOnly, t => {
  const root = directory(t), path = join(root, 'selection.json');
  const selection = { schemaVersion: 1, fixture: REAL_MODEL_CASE, runRoot: join(root, 'fresh'), ownerInputs: '/not-opened/inputs.json',
    ownerJava: '/not-executed/jdk/bin/java', ownerClasspath: '/not-executed/classes:/not-executed/dependency.jar',
    modelWorkerBridge: '/not-opened/bridge.jar', modelWorkerBridgeDigest: sha, modelWorkerBridgeBytes: 123 };
  const file = writeNewRealJson(path, selection);
  assert.deepEqual(loadRealSelection(path, file.digest).selection, selection);
  assert.throws(() => loadRealSelection(path, sha), /real_model_selection_changed/);
  const changed = writeNewRealJson(join(root, 'extra.json'), { ...selection, retry: true });
  assert.throws(() => loadRealSelection(changed.path, changed.digest), /real_model_unexpected_fields/);
  const nested = writeNewRealJson(join(root, 'nested.json'), { ...selection, modelWorkerBridge: join(selection.runRoot, 'bridge.jar') });
  assert.throws(() => loadRealSelection(nested.path, nested.digest), /real_model_inputs_inside_run_forbidden/);
});
test('explicit Codex location is required while executable bytes, model, effort and schema stay pinned', linuxOnly, t => {
  const root = directory(t);
  const executable = join(root, 'synthetic-selected-codex');
  writeFileSync(executable, 'SYNTHETIC DATA ONLY, never executed', { mode: 0o600 });
  const selection = { schemaVersion: 1, model: REAL_CODEX_MODEL, effort: REAL_CODEX_EFFORT, executable,
    executableDigest: REAL_CODEX_ELF_DIGEST, executableBytes: REAL_CODEX_ELF_BYTES,
    schemaManifest: '/not-opened/schema-manifest.json', schemaManifestDigest: REAL_CODEX_SCHEMA_DIGEST,
    schemaAggregateDigest: REAL_CODEX_SCHEMA_AGGREGATE };
  const file = writeNewRealJson(join(root, 'codex.json'), selection);
  const loaded = loadRealCodexSelection(file.path, file.digest).selection;
  assert.deepEqual(loaded, selection);
  assert.throws(() => preflightRealCodex(loaded), /real_model_codex_executable_changed/);
  const relocated = writeNewRealJson(join(root, 'relocated.json'), { ...selection, executable: join(root, 'another-explicit-location') });
  assert.equal(loadRealCodexSelection(relocated.path, relocated.digest).selection.executable, join(root, 'another-explicit-location'));
  const missingFields: Record<string, unknown> = { ...selection }; delete missingFields.executable;
  const missing = writeNewRealJson(join(root, 'missing-executable.json'), missingFields);
  assert.throws(() => loadRealCodexSelection(missing.path, missing.digest), /real_model_unexpected_fields/);
  for (const [index, change] of [{ effort: 'low' }, { model: 'another-model' }, { executable: 'codex' }, { executable: '/tmp/../tmp/codex' },
    { executable: null }, { executableDigest: sha }, { executableBytes: REAL_CODEX_ELF_BYTES - 1 },
    { schemaAggregateDigest: sha }, { fallback: true }].entries()) {
    const value = writeNewRealJson(join(root, 'bad-' + index + '.json'), { ...selection, ...change });
    assert.throws(() => loadRealCodexSelection(value.path, value.digest));
  }
});
test('evidence files are new-only, privately readable, bounded and never overwritten', linuxOnly, t => {
  const root = directory(t), path = join(root, 'evidence.json'), value = { state: 'synthetic-control-only' };
  const first = writeNewRealJson(path, value);
  assert.equal(first.digest, digest(canonical(value) + '\n'));
  assert.deepEqual(jsonReal(readRealFile(path, 1024).bytes), value);
  assert.throws(() => writeNewRealJson(path, { state: 'replacement' }));
  assert.equal(hashRealFile(path).digest, first.digest);
  assert.throws(() => readRealFile(path, 4), /real_model_regular_file_bound/);
  chmodSync(path, 0o644); assert.throws(() => readRealFile(path, 1024), /real_model_private_file_required/);
});
test('file pins reject symlinks and multi-link aliasing and fresh directories reject prior attempts', linuxOnly, t => {
  const root = directory(t), source = join(root, 'source'); writeFileSync(source, 'synthetic', { mode: 0o600 });
  symlinkSync(source, join(root, 'alias')); assert.throws(() => hashRealFile(join(root, 'alias')));
  linkSync(source, join(root, 'hardlink')); assert.throws(() => hashRealFile(source));
  const fresh = join(root, 'new'); makeFreshRealDirectory(fresh); assert.throws(() => makeFreshRealDirectory(fresh));
});
test('boundary traversal counts whole newly-owned tree without a continuous-quota claim', linuxOnly, t => {
  const root = directory(t); mkdirSync(join(root, 'sub')); writeFileSync(join(root, 'sub/file'), '12345');
  assert.deepEqual(realRunBoundary(root), { entries: 3, bytes: 5, continuousQuota: false });
  symlinkSync(join(root, 'sub/file'), join(root, 'alias')); assert.throws(() => realRunBoundary(root), /real_model_run_nonregular/);
});
test('one-GiB logical file boundary rejects before accepting an oversized sparse file', linuxOnly, t => {
  const root = directory(t), path = join(root, 'sparse'); writeFileSync(path, ''); truncateSync(path, REAL_MODEL_TREE_BYTES + 1);
  assert.throws(() => realRunBoundary(root), /real_model_run_byte_boundary/);
});
test('boundary directory depth is finite even when the root path itself is short', linuxOnly, t => {
  const root = directory(t); let path = root;
  for (let index = 0; index < 128; index++) { path = join(path, 'd'); mkdirSync(path); }
  assert.throws(() => realRunBoundary(root), /real_model_run_entry_bound/);
});
test('JSON boundary rejects malformed UTF-8 and BOM instead of replacing public/model bytes', () => {
  assert.throws(() => jsonReal(Buffer.from([0x22, 0xff, 0x22])));
  assert.throws(() => jsonReal(Buffer.from('\ufeff{}')), /real_model_json_bom/);
  assert.deepEqual(jsonReal(Buffer.from('{"literal":"😀"}')), { literal: '😀' });
});
