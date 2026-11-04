/** Pure fixture argument/reservation tests. No CLI, compiler, app-server, or model is started. */
import assert from 'node:assert/strict';
import { link, lstat, mkdtemp, readFile, rename, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test, type TestContext } from 'node:test';
import type { RpcOptions } from '@ktchecker/mcp-preview/rpc';
import { HostError } from '../src/journal.js';
import { ActualCliFixture, ActualFaultBroker, actualDemoCleanupEvidence, parseActualDemoArgs, reserveActualDemoEvidence } from './actual-cli-fixture.js';

const launcher = resolve('not-executed launcher'), compilerJavaHome = resolve('not-executed JDK 21 共有');
const fixtureDirectory = resolve('not-created fixture state');
function argumentsFor(evidence = 'not-created evidence.json'): string[] {
  return ['--allow-inference', '--cli', launcher, '--compiler-java-home', compilerJavaHome, '--evidence', evidence, '--max-runtime-ms', '600000'];
}
function fixture(): ActualCliFixture { return new ActualCliFixture(launcher, fixtureDirectory, compilerJavaHome); }
async function directory(t: TestContext): Promise<string> {
  const path = await mkdtemp(join(tmpdir(), 'ktchecker-fixture-arguments-'));
  t.after(() => rm(path, { recursive: true, force: true }));
  return path;
}
function assertOption(args: readonly string[], name: string, value: string): void {
  assert.equal(args.filter(arg => arg === name).length, 1);
  assert.equal(args[args.indexOf(name) + 1], value);
}

test('actual demo requires independent explicit inference, launcher, compiler JDK, NEW evidence, and finite runtime selections', () => {
  const all = argumentsFor();
  for (const [flag, code] of [
    ['--allow-inference', 'actual_demo_inference_approval_required'],
    ['--cli', 'actual_demo_launcher_required'],
    ['--compiler-java-home', 'actual_demo_compiler_java_home_required'],
    ['--evidence', 'actual_demo_evidence_required'],
    ['--max-runtime-ms', 'actual_demo_max_runtime_required'],
  ]) {
    const args = [...all], index = args.indexOf(flag!);
    args.splice(index, flag === '--allow-inference' ? 1 : 2);
    assert.throws(() => parseActualDemoArgs(args), { code });
  }
});

test('actual demo parses explicit selections without mutating argv or serializing ambient defaults', () => {
  const args = ['--evidence', 'new evidence 共有.json', '--compiler-java-home', 'owner JDK 21 共有', '--allow-inference', '--cli', 'owner launcher', '--max-runtime-ms', '600000'];
  const original = [...args], parsed = parseActualDemoArgs(args);
  assert.deepEqual(parsed, { launcher: resolve('owner launcher'), compilerJavaHome: resolve('owner JDK 21 共有'), evidence: resolve('new evidence 共有.json'), maxRuntimeMs: 600000 });
  assert(Object.isFrozen(parsed)); assert.deepEqual(args, original);
});

test('actual demo rejects repeated selections even when their values match', () => {
  for (const suffix of [
    ['--allow-inference'], ['--cli', launcher], ['--cli', 'conflicting launcher'],
    ['--compiler-java-home', compilerJavaHome], ['--compiler-java-home', 'conflicting JDK'],
    ['--evidence', 'not-created evidence.json'], ['--evidence', 'other evidence.json'],
    ['--max-runtime-ms', '600000'], ['--max-runtime-ms', '120000'],
  ]) assert.throws(() => parseActualDemoArgs([...argumentsFor(), ...suffix]), { code: 'actual_demo_duplicate_option' });
});

test('actual demo rejects unknown options, equals aliases, and missing values', () => {
  for (const extra of ['--unknown', '--compiler-java-home=other', '--max-runtime-ms=1000', '--allow-inference=true', '--', 'positional']) {
    assert.throws(() => parseActualDemoArgs([...argumentsFor(), extra]), { code: 'actual_demo_unknown_option' });
  }
  for (const flag of ['--cli', '--compiler-java-home', '--evidence', '--max-runtime-ms']) {
    assert.throws(() => parseActualDemoArgs(['--allow-inference', flag]), { code: 'actual_demo_option_value_required' });
    assert.throws(() => parseActualDemoArgs([flag, '--allow-inference']), { code: 'actual_demo_option_value_required' });
  }
});

test('actual demo rejects blank, control-containing, and oversized paths without echoing them', () => {
  for (const value of ['', '  ', 'private\0path', 'private\npath', 'private\u0085path', 'x'.repeat(16_385)]) {
    assert.throws(() => parseActualDemoArgs(['--allow-inference', '--cli', value]), error =>
      error instanceof HostError && error.code === 'actual_demo_invalid_path' && error.message === error.code);
  }
});

test('JDK dot segments are rejected before resolution by both parser and fixture construction', () => {
  for (const selected of ['.', '..', './jdk', '../jdk', 'a/./jdk', 'link/../jdk', 'a/.', 'a/..',
    '/absolute/link/../jdk', 'C:\\link\\..\\jdk', 'C:\\parent\\.\\jdk']) {
    const args = argumentsFor(); args[args.indexOf('--compiler-java-home') + 1] = selected;
    assert.throws(() => parseActualDemoArgs(args), { code: 'actual_compiler_java_home_dot_segments' });
    assert.throws(() => new ActualCliFixture(launcher, fixtureDirectory, selected), { code: 'actual_compiler_java_home_dot_segments' });
  }
  // Ordinary relative explicit names remain supported; only complete dot components are denied.
  for (const selected of ['owner/.jdk-21', 'owner/..jdk-21', 'owner/.../jdk-21']) {
    const args = argumentsFor(); args[args.indexOf('--compiler-java-home') + 1] = selected;
    assert.equal(parseActualDemoArgs(args).compilerJavaHome, resolve(selected));
    assert.equal(new ActualCliFixture(launcher, fixtureDirectory, selected).compilerJavaHome, resolve(selected));
  }
});

test('fixture construction also requires an explicit JDK at runtime rather than JAVA_HOME', t => {
  const previous = process.env.JAVA_HOME;
  t.after(() => { if (previous === undefined) delete process.env.JAVA_HOME; else process.env.JAVA_HOME = previous; });
  process.env.JAVA_HOME = resolve('ambient JDK must not authorize anything');
  assert.throws(() => Reflect.construct(ActualCliFixture, [launcher, fixtureDirectory]), { code: 'actual_cli_compiler_java_home_required' });
  for (const absent of [undefined, null, '', ' ', 'bad\nJDK', 21]) {
    assert.throws(() => new ActualCliFixture(launcher, fixtureDirectory, absent as string), { code: 'actual_cli_compiler_java_home_required' });
  }
  assert.throws(() => parseActualDemoArgs(['--allow-inference', '--cli', launcher, '--evidence', 'new.json']),
    { code: 'actual_demo_compiler_java_home_required' });
  const owner = fixture();
  process.env.JAVA_HOME = resolve('changed ambient JDK');
  assert.equal(owner.compilerJavaHome, compilerJavaHome);
  assertOption(owner.ownerArguments(['register']), '--compiler-java-home', compilerJavaHome);
  assertOption(owner.rpcOptions().args, '--compiler-java-home', compilerJavaHome);
});

test('all owner-command argument forms carry the identical explicit JDK, state, and zero-worker selection', () => {
  const owner = fixture();
  for (const command of [
    ['register', '--client', 'owner-test-client'],
    ['suppressions', 'inspect', '--client', 'owner-test-client'],
    ['suppressions', 'enable', '--rule', 'ktc.compiler.test', '--owner-action'],
    ['overlay', '--request', resolve('not-read overlay.json')],
    ['prepare', '--request', resolve('not-read request.json'), '--authorize-execution'],
  ]) {
    const requested = Object.freeze([...command]), args = owner.ownerArguments(requested);
    assert.deepEqual(args.slice(0, requested.length), requested);
    assertOption(args, '--compiler-java-home', compilerJavaHome);
    assertOption(args, '--state', join(fixtureDirectory, 'kernel'));
    assertOption(args, '--workers', '0');
    assert.deepEqual(requested, command);
  }
});

test('owner commands cannot override or alias the captured fixture runtime selections', async () => {
  const owner = fixture();
  for (const option of ['--compiler-java-home', '--state', '--workers']) {
    for (const suffix of [[option, 'other'], [option + '=other']]) {
      assert.throws(() => owner.ownerArguments(['register', ...suffix]), { code: 'actual_cli_fixture_option_conflict' });
      // Validation happens before the nonexistent executable could be invoked.
      await assert.rejects(owner.call(['register', ...suffix]), { code: 'actual_cli_fixture_option_conflict' });
    }
  }
});

test('RPC argv is independently allocated, bounded, and pinned to the same owner JDK', () => {
  const owner = fixture(), zero = owner.rpcOptions(0), one = owner.rpcOptions(1);
  for (const [options, workers] of [[zero, '0'], [one, '1']] as const) {
    assert.equal(options.command, launcher); assert.equal(options.timeoutMs, 60_000);
    assert.equal(options.args[0], 'rpc');
    assertOption(options.args, '--state', owner.state);
    assertOption(options.args, '--workers', workers);
    assertOption(options.args, '--compiler-java-home', compilerJavaHome);
  }
  zero.args.fill('mutated-returned-array');
  assertOption(one.args, '--compiler-java-home', compilerJavaHome);
  assert.deepEqual(owner.rpcOptions(), one);
  for (const value of [-1, 2, 0.5, Number.NaN, Number.POSITIVE_INFINITY, '1', null, true]) {
    assert.throws(() => owner.rpcOptions(value as 0 | 1), { code: 'actual_cli_invalid_worker_count' });
  }
});

test('fault-broker disconnect/restart plumbing retains the same explicit JDK without starting a process', async () => {
  class RecordingFixture extends ActualCliFixture {
    readonly selections: RpcOptions[] = [];
    override rpcOptions(workers: 0 | 1 = 1): RpcOptions {
      const options = super.rpcOptions(workers); this.selections.push(options); return options;
    }
  }
  const owner = new RecordingFixture(launcher, fixtureDirectory, compilerJavaHome), broker = new ActualFaultBroker(owner);
  try {
    // No request is made: these are lazy transports, not fabricated kernel or restart evidence.
    await broker.disconnect(); await broker.disconnect(0); await broker.disconnect(1);
    assert.equal(broker.disconnects, 3); assert.equal(broker.calls.length, 0);
    assert.equal(owner.selections.length, 4);
    for (const [index, options] of owner.selections.entries()) {
      assertOption(options.args, '--compiler-java-home', compilerJavaHome);
      assertOption(options.args, '--state', owner.state);
      assertOption(options.args, '--workers', index % 2 === 0 ? '0' : '1');
    }
  } finally { await broker.close(); }
});

test('run-state retention is unconditional across zero/confirmed/failed thread cleanup and delivery outcomes', () => {
  const outcomes = [
    null,
    { allConfirmed: true, knownOwnedThreads: 0, confirmedDeleted: 0 },
    { allConfirmed: true, knownOwnedThreads: 2, confirmedDeleted: 2 },
    { allConfirmed: false, knownOwnedThreads: 2, confirmedDeleted: 1 },
    { allConfirmed: false, knownOwnedThreads: 0, confirmedDeleted: 0, unknownCreationOutcomes: 1 },
  ];
  for (const threadCleanup of outcomes) for (const verified of [false, true]) {
    const cleanup = actualDemoCleanupEvidence(threadCleanup);
    const report = { verified, releaseQualified: false, ...cleanup };
    assert.equal(report.verified, verified, 'Retention does not invent or erase delivery verification');
    assert.equal(report.releaseQualified, false);
    assert.equal(report.ownedDisposableThreadsDeleted, threadCleanup?.allConfirmed ?? null);
    assert.equal(report.privateRecoveryStateRetained, true);
    assert.equal(report.runtimeCleanup.state, 'unverified');
    assert.equal(report.runtimeCleanup.nativeQuiescenceObserved, false);
    assert.equal(report.runtimeCleanup.automaticRunDirectoryDeletionAuthorized, false);
    assert.equal(report.runtimeCleanup.retainedScope, 'entire_private_and_public_run_directory');
    assert.equal(report.runtimeCleanup.ownerActionRequired, 'manual_owner_reconciliation_before_explicit_cleanup');
    assert(Object.isFrozen(cleanup)); assert(Object.isFrozen(cleanup.runtimeCleanup));
  }
});

test('NEW evidence is reserved privately, written once through its handle, and closed idempotently', async t => {
  const path = join(await directory(t), 'new parent', 'new.json'), reservation = await reserveActualDemoEvidence(path);
  const contents = '{"verified":false,"fixture":"reservation_unit_test"}\n';
  try {
    assert.equal(reservation.path, path);
    const stat = await lstat(path); assert.equal(stat.size, 0);
    if (process.platform !== 'win32') assert.equal(stat.mode & 0o777, 0o600);
    await reservation.write(contents);
    await assert.rejects(reservation.write('must-not-overwrite'), { code: 'actual_demo_evidence_already_used' });
  } finally { await reservation.close(); }
  await reservation.close();
  await assert.rejects(reservation.write('must-not-reopen'), { code: 'actual_demo_evidence_already_used' });
  assert.equal(await readFile(path, 'utf8'), contents);
});

test('an existing evidence file blocks work and preserves its original bytes', async t => {
  const path = join(await directory(t), 'historical.json'), original = '{"historical":"do-not-rewrite"}\n';
  await writeFile(path, original, { flag: 'wx' });
  let workStarted = false;
  await assert.rejects((async () => {
    const reservation = await reserveActualDemoEvidence(path);
    workStarted = true; await reservation.close();
  })(), { code: 'actual_demo_evidence_exists' });
  assert.equal(workStarted, false); assert.equal(await readFile(path, 'utf8'), original);
});

test('only one concurrent run can reserve a given NEW evidence pathname', async t => {
  const path = join(await directory(t), 'contended.json');
  const attempts = await Promise.allSettled(Array.from({ length: 8 }, () => reserveActualDemoEvidence(path)));
  const winners = attempts.filter(attempt => attempt.status === 'fulfilled');
  assert.equal(winners.length, 1);
  for (const attempt of attempts) if (attempt.status === 'rejected') {
    assert(attempt.reason instanceof HostError); assert.equal(attempt.reason.code, 'actual_demo_evidence_exists');
  }
  const reservation = winners[0]!.value;
  try { await reservation.write('{"verified":false,"single_reservation":true}\n'); }
  finally { await reservation.close(); }
  assert.equal(JSON.parse(await readFile(path, 'utf8')).single_reservation, true);
});

test('concurrent writes cannot reuse a reservation and close waits for its one write', async t => {
  const path = join(await directory(t), 'one-write.json'), reservation = await reserveActualDemoEvidence(path);
  const contents = '{"verified":false,"data":"' + 'x'.repeat(16_384) + '"}\n';
  const write = reservation.write(contents);
  await assert.rejects(reservation.write('second-write'), { code: 'actual_demo_evidence_already_used' });
  await reservation.close(); await write;
  assert.equal(await readFile(path, 'utf8'), contents);
});

test('existing and dangling evidence symlinks are never followed', async t => {
  const root = await directory(t), target = join(root, 'historical-target.json'), path = join(root, 'selected.json');
  await writeFile(target, 'historical bytes', { flag: 'wx' });
  try { await symlink(target, path); }
  catch (error) {
    if (process.platform === 'win32' && (error as NodeJS.ErrnoException).code === 'EPERM') { t.skip('Windows account cannot create a test symlink'); return; }
    throw error;
  }
  await assert.rejects(reserveActualDemoEvidence(path), { code: 'actual_demo_evidence_exists' });
  assert((await lstat(path)).isSymbolicLink()); assert.equal(await readFile(target, 'utf8'), 'historical bytes');
  await rm(target);
  await assert.rejects(reserveActualDemoEvidence(path), { code: 'actual_demo_evidence_exists' });
  assert((await lstat(path)).isSymbolicLink());
});

test('replacing a reserved pathname does not write into the replacement or silently publish elsewhere', async t => {
  const root = await directory(t), path = join(root, 'selected.json'), retained = join(root, 'moved-reservation.json');
  const reservation = await reserveActualDemoEvidence(path);
  try {
    await rename(path, retained); await writeFile(path, 'replacement bytes', { flag: 'wx' });
    await assert.rejects(reservation.write('must-not-publish'), { code: 'actual_demo_evidence_path_changed' });
    assert.equal(await readFile(path, 'utf8'), 'replacement bytes'); assert.equal(await readFile(retained, 'utf8'), '');
  } finally { await reservation.close(); }
});

test('externally changed or hardlinked reservations fail closed without overwriting their bytes', async t => {
  const root = await directory(t), changed = join(root, 'changed.json'), linked = join(root, 'linked.json');
  const first = await reserveActualDemoEvidence(changed), second = await reserveActualDemoEvidence(linked);
  try {
    await writeFile(changed, 'concurrent owner bytes', { flag: 'r+' });
    await link(linked, join(root, 'other-link.json'));
    await assert.rejects(first.write('must-not-overwrite'), { code: 'actual_demo_evidence_path_changed' });
    await assert.rejects(second.write('must-not-share'), { code: 'actual_demo_evidence_path_changed' });
    assert.equal(await readFile(changed, 'utf8'), 'concurrent owner bytes'); assert.equal(await readFile(linked, 'utf8'), '');
  } finally { await first.close(); await second.close(); }
});


test('finite maximum runtime is canonical decimal with strict independent bounds and no ambient fallback', () => {
  for (const value of ['1000', '1800000', '600000']) {
    const args = argumentsFor(); args[args.indexOf('--max-runtime-ms') + 1] = value;
    assert.equal(parseActualDemoArgs(args).maxRuntimeMs, Number(value));
  }
  for (const value of ['', '0', '999', '1800001', '9007199254740991', 'Infinity', 'NaN', '1e3', '1000.0',
    '0x3e8', '+1000', '-1000', ' 1000', '1000 ', '01000', '1_000', '１０００', '1000\n']) {
    const args = argumentsFor(); args[args.indexOf('--max-runtime-ms') + 1] = value;
    assert.throws(() => parseActualDemoArgs(args), { code: 'actual_demo_invalid_max_runtime' });
  }
});
