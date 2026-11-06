/** INERT byte/clock/schema controls only. No spawn, Java, file IO, owner, factory/context,
 * registration, provider, model or entry-module import. Synthetic frames never become authority.
 */
import assert from 'node:assert/strict';
import test from 'node:test';
import { PassThrough, Writable } from 'node:stream';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest, HostError } from '../src/journal.js';
import { REQUIRED_PARENT_ADAPTER } from './current-image-acceptance-runner.js';
import { captureOriginalOwnerEntry, OwnerSessionClocks } from './current-image-owner-clocks.js';
import { RetainedOwnerPipeChannel } from './current-image-owner-channel.js';
import { encodeOwnerFrame, OwnerFrameDecoder, OWNER_FRAME_BYTES, OWNER_SESSION_PROTOCOL } from './current-image-owner-wire.js';
import { PreownerOnlyWireProof } from './current-image-owner-preowner-proof.js';
import { PREOWNER_SENTINEL_TEXT, preownerJvmArguments, requiredPreownerSmokeModules,
  validatePreownerBuildRecord, validatePreownerSmokeSelection, type PreownerSmokeSelection } from './current-image-owner-preowner-inputs.js';
import { RealModelClock } from './real-model-run-support.js';

const session = 'a'.repeat(64), overallNonce = 'b'.repeat(64), exportNonce = 'c'.repeat(64);
const hello = (): JsonObject => ({ kind: 'hello', protocol: OWNER_SESSION_PROTOCOL, session, profile: REQUIRED_PARENT_ADAPTER, maxFrameBytes: OWNER_FRAME_BYTES });
const open = (): JsonObject => ({ kind: 'request', session, id: 1, method: 'owner.open', params: {} });
const challenge = (phase: 'overall' | 'export'): JsonObject => ({ kind: 'clock.challenge', session, requestId: 1,
  challenge: phase === 'overall' ? overallNonce : exportNonce, phase, ordinal: phase === 'overall' ? 0 : 1 });
const tick = (): Promise<void> => new Promise(resolve => setImmediate(resolve));
function decoded(bytes: Buffer): JsonObject {
  const values: JsonObject[] = [], decoder = new OwnerFrameDecoder();
  decoder.feed(bytes, process.hrtime.bigint(), value => { values.push(value); });
  assert.equal(values.length, 1); assert.equal(decoder.partialDeadlineNanos, null); return values[0]!;
}
function send(proof: PreownerOnlyWireProof, frame: JsonObject | Buffer): Promise<void> {
  const bytes = Buffer.isBuffer(frame) ? frame : encodeOwnerFrame(frame);
  return new Promise((resolve, reject) => { proof.input.write(bytes, error => error ? reject(error) : resolve()); });
}
function inert(rawOverride?: Writable) {
  const overall = new RealModelClock(), clocks = new OwnerSessionClocks(overall, captureOriginalOwnerEntry());
  const writes: Buffer[] = [], errors: unknown[] = [];
  const raw = rawOverride ?? new Writable({ write(bytes: Buffer, _encoding, done) { writes.push(Buffer.from(bytes)); done(); } });
  raw.on('error', error => { errors.push(error); });
  const proof = new PreownerOnlyWireProof(raw, overall); proof.input.on('error', error => { errors.push(error); });
  clocks.bindSession(session); clocks.beginRequest(1, 'owner.open');
  return { overall, clocks, writes, errors, raw, proof };
}
async function atOverall() {
  const value = inert(); value.proof.observeStdout(encodeOwnerFrame(hello())); await send(value.proof, open());
  value.proof.observeStdout(encodeOwnerFrame(challenge('overall'))); return value;
}
async function atExport() {
  const value = await atOverall(); await send(value.proof, value.clocks.replyToChallenge(challenge('overall')));
  value.proof.observeStdout(encodeOwnerFrame(challenge('export'))); return value;
}

test('actual TS channel on synthetic pipes forwards only open, original overall reply and deny', async () => {
  const overall = new RealModelClock(), clocks = new OwnerSessionClocks(overall, captureOriginalOwnerEntry());
  const rawFrames: Buffer[] = [], raw = new Writable({ write(bytes: Buffer, _encoding, done) { rawFrames.push(Buffer.from(bytes)); done(); } });
  const proof = new PreownerOnlyWireProof(raw, overall), channel = new RetainedOwnerPipeChannel(clocks);
  const stdout = new PassThrough(), stderr = new PassThrough(); const errors: unknown[] = [];
  raw.on('error', error => { errors.push(error); }); proof.input.on('error', error => { errors.push(error); });
  stdout.on('data', (bytes: Buffer) => { try { proof.observeStdout(bytes); } catch (error) { errors.push(error); channel.poison(error); } });
  stderr.on('data', (bytes: Buffer) => { proof.observeStderr(bytes); });
  channel.attachPipes(proof.input, stdout, stderr);
  let opening: Promise<JsonObject> | null = null;
  try {
    stdout.write(encodeOwnerFrame(hello())); await channel.awaitHello();
    opening = channel.request('owner.open', {}); void opening.catch(() => {}); await tick();
    assert.equal(rawFrames.length, 1); assert.equal(decoded(rawFrames[0]!).method, 'owner.open');
    stdout.write(encodeOwnerFrame(challenge('overall'))); await tick();
    assert.equal(rawFrames.length, 2); assert.equal(decoded(rawFrames[1]!).phase, 'overall');
    stdout.write(encodeOwnerFrame(challenge('export'))); await tick();
    proof.assertComplete(); assert.equal(rawFrames.length, 3); assert.deepEqual(decoded(rawFrames[2]!), { kind: 'deny', session });
    assert.equal(rawFrames.map(decoded).filter(frame => frame.kind === 'clock.reply' && frame.phase === 'export').length, 0);
    channel.poison(new HostError('synthetic_preowner_expected_denial')); await assert.rejects(opening); await tick();
    assert.equal(rawFrames.length, 3); assert.equal(proof.evidence().laterDeniesSuppressed, 1);
    assert.equal(channel.evidence().retainedOriginalReturn, false); assert.equal(errors.length, 0); proof.assertComplete();
  } finally { channel.poison(new HostError('synthetic_preowner_inert_control_done')); }
});

test('closed shim rejects any first method, id or params other than exact open1 empty', async () => {
  for (const frame of [{ ...open(), method: 'gate.complete' }, { ...open(), id: 2 }, { ...open(), params: { extra: true } }, { ...open(), extra: true }]) {
    const value = inert(); value.proof.observeStdout(encodeOwnerFrame(hello()));
    await assert.rejects(send(value.proof, frame)); assert.equal(value.writes.length, 0);
  }
});

test('shim checks canonical actual writer bytes without claiming identical TS and JVM lexical decoders', async () => {
  const value = inert(); value.proof.observeStdout(encodeOwnerFrame(hello()));
  const body = Buffer.from(JSON.stringify(open()).replace('"id":1', '"id":1.0'));
  const header = Buffer.alloc(4); header.writeUInt32BE(body.length);
  assert.equal(decoded(Buffer.concat([header, body])).id, 1); // TS numeric equality is intentionally documented.
  await assert.rejects(send(value.proof, Buffer.concat([header, body])), /preowner_canonical_writer_required/);
  assert.equal(value.writes.length, 0);
});

test('a partial frame or two frames in one shim write never forwards any raw prefix', async () => {
  const encoded = encodeOwnerFrame(open());
  for (const bytes of [encoded.subarray(0, 3), encoded.subarray(0, encoded.length - 1), Buffer.concat([encoded, encoded])]) {
    const value = inert(); value.proof.observeStdout(encodeOwnerFrame(hello()));
    await assert.rejects(send(value.proof, bytes)); assert.equal(value.writes.length, 0);
  }
});

test('overall reply is live same-object remaining and cannot widen or use a foreign nonce', async () => {
  for (const mutate of [(frame: JsonObject) => ({ ...frame, remainingMillis: 900_000 }),
    (frame: JsonObject) => ({ ...frame, challenge: exportNonce }), (frame: JsonObject) => ({ ...frame, remainingMillis: '900000' })]) {
    const value = await atOverall(), reply = value.clocks.replyToChallenge(challenge('overall'));
    await assert.rejects(send(value.proof, mutate(reply))); assert.equal(value.writes.length, 1);
  }
});

test('export reply can only be consumed once and its actual bytes never reach the raw pipe', async () => {
  const value = await atExport(), reply = value.clocks.replyToChallenge(challenge('export'));
  await send(value.proof, reply); value.proof.assertComplete();
  assert.equal(value.writes.length, 3); assert.deepEqual(decoded(value.writes[2]!), { kind: 'deny', session });
  await assert.rejects(send(value.proof, reply)); assert.equal(value.writes.length, 3);
  assert.equal(value.proof.evidence().exportRepliesForwarded, 0);
});

test('one later channel deny is suppressed locally and cannot become a second raw lifecycle write', async () => {
  const value = await atExport(); await send(value.proof, value.clocks.replyToChallenge(challenge('export')));
  await send(value.proof, { kind: 'deny', session }); value.proof.assertComplete();
  assert.equal(value.writes.length, 3); assert.equal(value.proof.evidence().laterDeniesSuppressed, 1);
  await assert.rejects(send(value.proof, { kind: 'deny', session })); assert.equal(value.writes.length, 3);
});

test('unexpected response or challenge order poisons proof instead of becoming owner adoption', async () => {
  for (const frame of [challenge('export'), { kind: 'response', session, id: 1, result: { profile: REQUIRED_PARENT_ADAPTER } }]) {
    const value = inert(); value.proof.observeStdout(encodeOwnerFrame(hello())); await send(value.proof, open());
    assert.throws(() => value.proof.observeStdout(encodeOwnerFrame(frame)));
    await assert.rejects(send(value.proof, { kind: 'deny', session })); assert.equal(value.writes.length, 1);
  }
});

test('raw backpressure needs both original callback and drain without forwarding another frame', async () => {
  let release!: () => void; let rawWrites = 0;
  const raw = new Writable({ highWaterMark: 1, write(_bytes: Buffer, _encoding, done) { rawWrites++; release = done; } });
  const value = inert(raw); value.proof.observeStdout(encodeOwnerFrame(hello()));
  let settled = false; const pending = send(value.proof, open()).then(() => { settled = true; });
  assert.equal(rawWrites, 1); assert.equal(settled, false);
  raw.emit('drain'); await tick(); assert.equal(settled, false); // No callback yet; drain alone is not completion.
  release(); await pending;
  const row = (value.proof.evidence().rawWrites as JsonObject[])[0]!;
  assert.equal(row.accepted, false); assert.equal(row.callbackDone, true); assert.equal(row.drainSeen, true); assert.equal(row.settled, true);
  assert.equal(rawWrites, 1);
});

test('unknown raw callback is retained once, rejects wait and never retries or forwards a deny', async () => {
  let rawWrites = 0;
  const raw = new Writable({ autoDestroy: false, write(_bytes: Buffer, _encoding, done) { rawWrites++; done(new Error('SYNTHETIC raw failure')); } });
  const value = inert(raw); value.proof.observeStdout(encodeOwnerFrame(hello()));
  await assert.rejects(send(value.proof, open())); await tick();
  assert.equal(rawWrites, 1); assert.equal((value.proof.evidence().rawWrites as JsonObject[])[0]!.failed, true);
  assert.throws(() => value.proof.assertComplete());
});

function syntheticSelection(): PreownerSmokeSelection {
  const root = '/inert-preowner-source-root';
  const pin = (label: string, path: string) => ({ label, path, digest: 'sha256:' + '0'.repeat(64), bytes: 1 });
  const modules = requiredPreownerSmokeModules();
  const jvmNames = ['Main.kt', 'OwnerModelSessionTransport.kt', 'OwnerModelTransportPreownerGate.kt', 'OwnerModelTransportFrames.kt',
    'OwnerModelTransportClocks.kt', 'OwnerModelTransportSelection.kt', 'OwnerModelCommand.kt', 'OwnerModelLineage.kt'];
  const sourceInputs = [...modules.map(module => pin(module.source, root + '/' + module.source)), ...jvmNames.map(name => {
    const label = 'modules/cli/src/main/kotlin/dev/ktchecker/cli/' + name; return pin(label, root + '/' + label);
  })].sort((a, b) => a.label < b.label ? -1 : a.label > b.label ? 1 : 0);
  const controllerOutputs = modules.map(module => pin(module.source.replace(/\.ts$/, '.js'), module.path))
    .sort((a, b) => a.label < b.label ? -1 : a.label > b.label ? 1 : 0);
  const distributionHome = root + '/build/preview-handoffs/private-preview-synthetic123/install/ktchecker';
  return { schema: 'ktchecker-owner-preowner-smoke-selection/1', approvalId: 'SYNTHETIC-no-owner-authority', sourceRoot: root,
    distributionHome, javaHome: '/inert-jdk', outputDirectory: root + '/build/ci-evidence/owner-preowner-smoke-synthetic123',
    node: pin('node', '/inert-node/bin/node'), nodeVersion: '24.20.0', jvmExecutable: pin('java', '/inert-jdk/bin/java'),
    jvmClasspath: [pin('classpath/first', distributionHome + '/lib/first.jar'), pin('classpath/second', distributionHome + '/lib/second.jar')],
    sourceInputs, controllerOutputs, buildRecord: pin('build-record', root + '/private/build.json') };
}
function syntheticBuild(choice: PreownerSmokeSelection): JsonObject {
  const logical = (pins: readonly { label: string; digest: string; bytes: number }[]) => pins.map(pin => ({ label: pin.label, digest: pin.digest, bytes: pin.bytes }));
  return { schema: 'ktchecker-owner-approved-preowner-smoke-build/1', status: 'verified_success', approvalId: choice.approvalId, buildId: 'SYNTHETIC-data-only',
    sourceInputsDigest: digest(canonical(logical(choice.sourceInputs))), jvmInputsDigest: digest(canonical(logical([choice.jvmExecutable, ...choice.jvmClasspath]))),
    controllerOutputsDigest: digest(canonical(logical(choice.controllerOutputs))), nodeDigest: choice.node.digest, nodeVersion: choice.nodeVersion,
    sourceDigest: choice.node.digest, buildEvidenceDigest: choice.node.digest, jvmBridgeBaseReviewDigest: choice.node.digest,
    preownerSuccessorReviewDigest: choice.node.digest, hostTransportReviewDigest: choice.node.digest };
}

test('pure preowner selection demands fresh stage, exact closure and no widened caller fields', () => {
  const choice = syntheticSelection(); assert.equal(validatePreownerSmokeSelection(choice).schema, choice.schema);
  for (const invalid of [{ ...choice, distributionHome: choice.sourceRoot + '/modules/cli/build/install/ktchecker' },
    { ...choice, distributionHome: choice.sourceRoot + '/build/preview-handoffs/ktchecker-20261105-f909k8w0' },
    { ...choice, sourceInputs: choice.sourceInputs.filter(pin => !pin.label.endsWith('/OwnerModelTransportPreownerGate.kt')) },
    { ...choice, controllerOutputs: choice.controllerOutputs.slice(1) }, { ...choice, extra: true },
    { ...choice, jvmClasspath: [choice.jvmClasspath[0], choice.jvmClasspath[0]] }]) {
    assert.throws(() => validatePreownerSmokeSelection(invalid));
  }
});

test('pinned build DATA linkage preserves explicit JAR order and cannot turn a failure into approval', () => {
  const choice = syntheticSelection(), build = syntheticBuild(choice);
  assert.equal(validatePreownerBuildRecord(build, choice).status, 'verified_success'); // Schema only; NO actual build/approval is minted.
  assert.throws(() => validatePreownerBuildRecord({ ...build, status: 'failed' }, choice));
  assert.throws(() => validatePreownerBuildRecord({ ...build, sourceInputsDigest: 'sha256:' + 'f'.repeat(64) }, choice));
  assert.throws(() => validatePreownerBuildRecord(build, { ...choice, jvmClasspath: [...choice.jvmClasspath].reverse() }));
  assert.throws(() => validatePreownerBuildRecord({ ...build, ownerAuthority: true }, choice));
});

test('actual Main argument vector has one fixed invalid sentinel and no shell, owner factory or model flag', () => {
  const choice = syntheticSelection();
  const sentinel = { path: choice.outputDirectory + '/unread-owner-selection.json', digest: digest(PREOWNER_SENTINEL_TEXT),
    bytes: Buffer.byteLength(PREOWNER_SENTINEL_TEXT), fileKey: '1:2' };
  const args = preownerJvmArguments(choice, sentinel);
  assert.deepEqual(args, ['-Xmx512m', '-XX:MaxMetaspaceSize=256m', '-Duser.home=' + choice.outputDirectory + '/owner-environment-home',
    '-Djava.io.tmpdir=' + choice.outputDirectory + '/owner-environment-tmp', '-cp', choice.jvmClasspath.map(pin => pin.path).join(':'),
    'dev.ktchecker.cli.MainKt', 'owner-model-session', '--owner-action', '--authorize-execution', '--request', sentinel.path, '--selection-digest', sentinel.digest]);
  assert.throws(() => preownerJvmArguments(choice, { ...sentinel, digest: 'sha256:' + 'f'.repeat(64) }));
  assert.throws(() => preownerJvmArguments(choice, { ...sentinel, path: choice.sourceRoot + '/other.json' }));
});
