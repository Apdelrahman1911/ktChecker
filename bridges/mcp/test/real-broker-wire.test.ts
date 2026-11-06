/** Actual installed Kotlin broker <-> JsonLineRpc integration, explicitly opt-in.
 * Build the distribution separately, then for example:
 * KTC_TEST_CLI_DISTRIBUTION=/absolute/install/ktchecker npm --prefix bridges/mcp test
 * No build/download, provider execution, authenticated host, or model call occurs here.
 * Default skip is NOT passing integration evidence. The echo-loss relay below emulates
 * an old peer's WIRE only; its success/result and durable enrollment come from Kotlin.
 */
import { test, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { constants } from 'node:fs';
import { access, chmod, lstat, mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join } from 'node:path';
import { FINDING_WIRE_PROFILE, JsonLineRpc, PREVIEW_CONTRACT, type JsonObject, type RpcOptions } from '../src/rpc.js';

const distribution = process.env.KTC_TEST_CLI_DISTRIBUTION;
const skip = !distribution ? 'Real Kotlin broker not run: set KTC_TEST_CLI_DISTRIBUTION to an installed CLI directory.'
  : process.platform !== 'linux' ? 'This opt-in subprocess/private-mode harness is Linux-only; no other-platform result is claimed.' : false;
const testOptions = { skip, timeout: 120_000 };
const RESPONSE_MS = 30_000;
const LINE_BYTES = 256 * 1024;
const PROFILE_ERROR = 'incompatible_finding_wire_profile';

function object(value: unknown): JsonObject {
  // Boolean assertions avoid dumping private registration credentials on a shape failure.
  assert.ok(value !== null && typeof value === 'object' && !Array.isArray(value), 'Expected an object; private body not logged');
  return value as JsonObject;
}
function success(envelope: JsonObject): JsonObject {
  assert.deepEqual(Object.keys(envelope).sort(), ['contractVersion', 'findingWireProfile', 'id', 'result']);
  assert.equal(envelope.contractVersion, PREVIEW_CONTRACT);
  assert.equal(envelope.findingWireProfile, 'ktchecker/finding-retrieval-attachments-1');
  return object(envelope.result);
}
function failure(envelope: JsonObject, code: string): void {
  assert.deepEqual(Object.keys(envelope).sort(), ['contractVersion', 'error', 'id']);
  assert.equal(envelope.contractVersion, PREVIEW_CONTRACT);
  assert.equal(object(envelope.error).code, code); // A deadline/disconnection is never the expected refusal.
}
async function boundedFile(path: string): Promise<string> {
  const info = await lstat(path);
  assert.ok(info.isFile() && !info.isSymbolicLink() && info.size <= 16_384, 'Expected a bounded fixture-owned regular file');
  return readFile(path, 'utf8');
}

/** Raw requests are needed to omit/corrupt metadata that JsonLineRpc correctly owns. */
class RawBroker {
  private readonly child: ChildProcessWithoutNullStreams;
  private readonly closed: Promise<void>;
  private buffer = Buffer.alloc(0);
  private readonly decoder = new TextDecoder('utf-8', { fatal: true });
  private pending: { id: string; resolve(value: JsonObject): void; reject(error: Error): void; timer: NodeJS.Timeout } | undefined;
  private problem: Error | undefined;
  private closing: Promise<void> | undefined;
  private sequence = 0;
  constructor(options: RpcOptions) {
    this.child = spawn(options.command, options.args, { ...(options.cwd ? { cwd: options.cwd } : {}),
      ...(options.env ? { env: options.env } : {}), shell: false, stdio: ['pipe', 'pipe', 'pipe'] });
    this.closed = new Promise(resolve => this.child.once('close', () => resolve()));
    this.child.stderr.resume(); // Never print broker logs or private response bodies.
    this.child.stdout.on('data', (chunk: Buffer) => this.receive(chunk));
    this.child.stdout.on('error', () => this.fail(new Error('Real broker output disconnected')));
    this.child.stdin.on('error', () => this.fail(new Error('Real broker input disconnected')));
    this.child.on('error', () => this.fail(new Error('Opted-in installed CLI could not be started')));
    this.child.on('exit', () => this.fail(new Error('Real broker exited before an expected response')));
  }
  request(method: string, params: JsonObject, profile: unknown = FINDING_WIRE_PROFILE, omit = false): Promise<JsonObject> {
    if (this.problem) return Promise.reject(this.problem);
    assert.ok(!this.pending && !this.closing, 'Only one bounded raw request at a time');
    const id = `raw-${++this.sequence}`;
    const bytes = Buffer.from(JSON.stringify({ id, method, params, ...(omit ? {} : { findingWireProfile: profile }) }) + '\n');
    assert.ok(bytes.length <= LINE_BYTES, 'Fixture request exceeds bound');
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.fail(new Error('Real broker response deadline expired; NOT success or cancellation')), RESPONSE_MS);
      this.pending = { id, resolve, reject, timer };
      this.child.stdin.write(bytes, error => { if (error) this.fail(new Error('Real broker write failed')); });
    });
  }
  private receive(chunk: Buffer): void {
    if (this.problem) return;
    if (this.buffer.length + chunk.length > LINE_BYTES) { this.fail(new Error('Real broker response exceeded fixture bound')); return; }
    this.buffer = Buffer.concat([this.buffer, chunk]);
    let newline: number;
    while ((newline = this.buffer.indexOf(10)) >= 0) {
      const bytes = this.buffer.subarray(0, newline); this.buffer = this.buffer.subarray(newline + 1);
      let value: unknown;
      try { value = JSON.parse(this.decoder.decode(bytes)); }
      catch { this.fail(new Error('Real broker response was not UTF-8 JSON; private body not logged')); return; }
      const pending = this.pending;
      if (!pending || value === null || typeof value !== 'object' || Array.isArray(value) ||
          (value as JsonObject).id !== pending.id) {
        this.fail(new Error('Real broker response did not match the pending request')); return;
      }
      this.pending = undefined; clearTimeout(pending.timer); pending.resolve(value as JsonObject);
    }
  }
  private fail(error: Error): void {
    this.problem ??= error;
    if (this.pending) { clearTimeout(this.pending.timer); this.pending.reject(this.problem); this.pending = undefined; }
  }
  private async exitedWithin(ms: number): Promise<boolean> {
    let timer: NodeJS.Timeout | undefined;
    try { return await Promise.race([this.closed.then(() => true), new Promise<false>(resolve => { timer = setTimeout(() => resolve(false), ms); })]); }
    finally { if (timer) clearTimeout(timer); }
  }
  close(): Promise<void> {
    this.closing ??= (async () => {
      this.fail(new Error('Raw fixture closed; no operation outcome inferred'));
      this.child.stdin.end();
      if (await this.exitedWithin(300)) return;
      this.child.kill('SIGTERM');
      if (await this.exitedWithin(500)) return;
      this.child.kill('SIGKILL');
      if (!await this.exitedWithin(1_000)) throw new Error('Real broker cleanup deadline expired; private fixture retained');
    })();
    return this.closing;
  }
}

interface Fixture {
  root: string;
  options: RpcOptions;
  raw(): RawBroker;
  track<T extends { close(): Promise<void> }>(resource: T): T;
  close(): Promise<void>;
  owner(): Promise<string>;
}
async function fixture(t: TestContext): Promise<Fixture> {
  assert.ok(distribution && isAbsolute(distribution), 'KTC_TEST_CLI_DISTRIBUTION must explicitly name an absolute installed directory');
  assert.ok((await stat(distribution)).isDirectory(), 'Opt-in path must be an installed distribution directory');
  const command = join(distribution, 'bin', 'ktchecker');
  await access(command, constants.X_OK); // A missing/bad opt-in installation fails; never silently skips/falls back.
  t.signal.throwIfAborted();
  const root = await mkdtemp(join(tmpdir(), 'ktc-real-broker-wire-'));
  const resources: { close(): Promise<void> }[] = [];
  let closing: Promise<void> | undefined;
  const close = (): Promise<void> => closing ??= (async () => {
    let failed: unknown;
    for (const resource of [...resources].reverse()) { try { await resource.close(); } catch (error) { failed ??= error; } }
    if (failed) throw failed; // Never delete private state while child retirement is unconfirmed.
    await rm(root, { recursive: true, force: true, maxRetries: 0 });
  })();
  t.after(close);
  // A test-runner deadline is failure. t.after awaits this same promise and reports cleanup errors.
  t.signal.addEventListener('abort', () => { void close().catch(() => {}); }, { once: true });
  t.signal.throwIfAborted();
  await chmod(root, 0o700);
  const state = join(root, 'state'); const home = join(root, 'home');
  await mkdir(state, { mode: 0o700 }); await mkdir(home, { mode: 0o700 });
  const env: NodeJS.ProcessEnv = {
    PATH: process.env.PATH ?? '/usr/bin:/bin', HOME: home, TMPDIR: root, LANG: 'C.UTF-8',
    JAVA_OPTS: '-Xmx256m -XX:MaxMetaspaceSize=192m',
  };
  if (process.env.JAVA_HOME) env.JAVA_HOME = process.env.JAVA_HOME;
  // No inherited auth/model environment, Java/Node injection options, owner state, or worker execution.
  const options: RpcOptions = { command, args: ['rpc', '--state', state, '--workers', '0'], cwd: root, env,
    timeoutMs: RESPONSE_MS, maxPending: 1, maxLineBytes: LINE_BYTES };
  return { root, options,
    raw: () => {
      // Check before spawning: an aborted test must not allocate a child after cleanup began.
      assert.ok(!closing, 'Fixture already closing'); t.signal.throwIfAborted();
      const raw = new RawBroker(options); resources.push(raw); return raw;
    },
    track: resource => { assert.ok(!closing, 'Fixture already closing'); resources.push(resource); return resource; }, close,
    owner: async () => {
      // Only this newly created fixture's owner identifier is read, not SQL/runtime ledger internals.
      const owner = object(JSON.parse(await boundedFile(join(state, 'owner.json')))).ownerId;
      assert.ok(typeof owner === 'string' && /^owner-[0-9a-f-]{36}$/.test(owner), 'Fixture owner identifier missing');
      return owner;
    } };
}
function binding(ownerId: string, clientId: string, sessionId: string): JsonObject {
  return { ownerId, workspaceId: 'wire-workspace', worktreeId: 'wire-worktree', clientId,
    lineageId: sessionId, sessionId, generation: 1 };
}
function checkRegistration(value: unknown, expected: JsonObject): JsonObject {
  const result = object(value);
  assert.deepEqual(object(result.binding), expected); // Binding identifiers only, never credential bodies.
  assert.equal(result.contractVersion, PREVIEW_CONTRACT);
  const credential = object(result.client);
  assert.ok(credential.clientId === expected.clientId && typeof credential.token === 'string' && credential.token.length > 0,
    'Real registration did not provide a usable private client capability');
  return credential;
}

/** Every typed client uses a real-broker relay so close() alone cannot masquerade as JVM
 * retirement. Normal mode forwards original upstream bytes; fault mode removes only the
 * outer marker semantically, then reserializes. Audit contains fixed names, never bodies.
 * Confirmed retirement requires JVM close before relay exit; unconfirmed cleanup fails
 * and retains state. Relay escalation fits inside the outer client's two-second bound.
 */
function brokerRelay(options: RpcOptions, audit: string, stripSuccessProfile: boolean): string {
  return String.raw`
import { spawn } from 'node:child_process';
import { appendFileSync } from 'node:fs';
const audit = ${JSON.stringify(audit)};
const mark = value => appendFileSync(audit, value + '\n', { mode: 0o600 });
mark('relay-started');
const broker = spawn(${JSON.stringify(options.command)}, ${JSON.stringify(options.args)}, { shell: false, stdio: ['pipe','pipe','pipe'] });
let stopping = false, status = 0, timers = [], forwarded = 0;
const overall = setTimeout(() => stop(2), 35_000);
function stop(code = 0) {
  status = Math.max(status, code);
  if (stopping) return;
  stopping = true; process.stdin.pause(); broker.stdin.end();
  timers = [setTimeout(() => broker.kill('SIGTERM'), 150), setTimeout(() => broker.kill('SIGKILL'), 650),
    setTimeout(() => { mark('cleanup-unconfirmed'); process.exit(3); }, 1_200)];
}
process.on('SIGTERM', () => stop()); process.on('SIGINT', () => stop(2));
process.stdin.on('end', () => stop()); process.stdin.on('error', () => stop(2));
process.stdout.on('error', () => stop(2));
broker.stdin.on('error', () => stop(2)); broker.stdout.on('error', () => stop(2));
broker.on('error', () => stop(2)); broker.stderr.resume();
broker.once('close', () => { for (const timer of timers) clearTimeout(timer); clearTimeout(overall); mark('broker-closed'); process.exit(status); });
function lines(stream, consume) {
  let buffer = Buffer.alloc(0);
  stream.on('data', chunk => {
    if (stopping) return;
    if (buffer.length + chunk.length > ${LINE_BYTES}) { stop(2); return; }
    buffer = Buffer.concat([buffer, chunk]);
    let index;
    while ((index = buffer.indexOf(10)) >= 0) {
      const line = buffer.subarray(0, index); buffer = buffer.subarray(index + 1);
      try { consume(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(line)), line); }
      catch { stop(2); return; }
    }
  });
}
lines(process.stdin, (request, bytes) => {
  if (++forwarded > 8) { stop(2); return; }
  mark(request.findingWireProfile === ${JSON.stringify(FINDING_WIRE_PROFILE)} ? 'forwarded-profile' : 'forwarded-bad-profile');
  broker.stdin.write(Buffer.concat([bytes, Buffer.from('\n')]));
});
lines(broker.stdout, (envelope, bytes) => {
  if (Object.hasOwn(envelope, 'result') && !Object.hasOwn(envelope, 'error')) {
    mark(envelope.findingWireProfile === ${JSON.stringify(FINDING_WIRE_PROFILE)} ? 'real-success-profile' : 'real-success-other-profile');
    if (${stripSuccessProfile}) {
      delete envelope.findingWireProfile;
      process.stdout.write(JSON.stringify(envelope) + '\n'); return;
    }
  }
  process.stdout.write(Buffer.concat([bytes, Buffer.from('\n')]));
});
`;
}

/** JsonLineRpc.close() can resolve immediately after SIGKILL delivery. Do not reuse/remove
 * real broker state on that return alone: require the independently recorded child close.
 */
async function realClient(f: Fixture, stripSuccessProfile = false) {
  const audit = join(f.root, stripSuccessProfile ? 'old-wire-audit.txt' : 'rpc-audit.txt');
  await writeFile(audit, '', { mode: 0o600, flag: 'wx' });
  const rpc = new JsonLineRpc({ ...f.options, command: process.execPath,
    args: ['--input-type=module', '-e', brokerRelay(f.options, audit, stripSuccessProfile)] });
  let retired: Promise<void> | undefined;
  return f.track({ rpc, audit, close: (): Promise<void> => retired ??= (async () => {
    await rpc.close();
    const marks = (await boundedFile(audit)).trim().split('\n').filter(Boolean);
    assert.ok(marks.length === 0 || (marks.includes('broker-closed') && !marks.includes('cleanup-unconfirmed')),
      'Relay did not confirm bounded real-broker retirement; private fixture retained');
  })() });
}

test('real Kotlin success echoes profile and JsonLineRpc returns real registration/event results', testOptions, async t => {
  const f = await fixture(t);
  try {
    assert.equal(FINDING_WIRE_PROFILE, 'ktchecker/finding-retrieval-attachments-1');
    const raw = f.raw();
    const doctor = success(await raw.request('doctor', {}));
    assert.equal(doctor.command, 'doctor'); assert.equal(doctor.installedProvidersInspected, true);
    assert.equal(Object.hasOwn(doctor, 'findingWireProfile'), false, 'Profile must not be inserted into result DTOs');
    const owner = await f.owner(); await raw.close();
    const { rpc } = await realClient(f);
    const actual = object(await rpc.request('doctor', {}));
    assert.equal(actual.command, 'doctor'); assert.equal(actual.releaseQualified, false);
    const selected = binding(owner, 'compatible-client', 'compatible-session');
    const credential = checkRegistration(await rpc.request('client.register', { binding: selected }), selected);
    const page = object(await rpc.request('review.events', { credential, limit: 1 }));
    assert.deepEqual(page.events, []); assert.equal(page.hasMore, false); assert.equal(page.contractVersion, PREVIEW_CONTRACT);
    assert.ok(typeof page.cursor === 'string', 'Real event page cursor missing');
  } finally { await f.close(); }
});

test('real missing/mismatched/malformed profile cannot durably enroll a client, even after admission', testOptions, async t => {
  const f = await fixture(t);
  try {
    const raw = f.raw();
    success(await raw.request('doctor', {})); // Earlier admitted request is not a grant.
    const owner = await f.owner();
    const variants = [{ omit: true, profile: null }, { omit: false, profile: 'ktchecker/finding-retrieval-attachments-2' },
      { omit: false, profile: { profile: FINDING_WIRE_PROFILE } }];
    const rejected = variants.map((_, index) => binding(owner, `rejected-client-${index}`, `must-not-exist-${index}`));
    for (const [index, variant] of variants.entries()) {
      failure(await raw.request('client.register', { binding: rejected[index]! }, variant.profile, variant.omit), PROFILE_ERROR);
    }
    await raw.close(); // Reopen the real durable state, not a mock handler counter.
    const { rpc } = await realClient(f);
    for (const [index, previous] of rejected.entries()) {
      const accepted = binding(owner, `rejected-client-${index}`, `admitted-session-${index}`);
      // If the rejected call secretly enrolled its binding, this different binding cannot succeed.
      checkRegistration(await rpc.request('client.register', { binding: accepted }), accepted);
      // Positive control: the real handler is stateful and DOES reject a conflicting binding.
      await assert.rejects(rpc.request('client.register', { binding: previous }), { code: 'client_binding_conflict' });
    }
  } finally { await f.close(); }
});

test('genuine Kotlin mutation with old-peer success wire is rejected without replay or rollback claim', testOptions, async t => {
  const f = await fixture(t);
  try {
    const raw = f.raw();
    success(await raw.request('doctor', {})); const owner = await f.owner(); await raw.close();
    const relay = await realClient(f, true); const { rpc, audit } = relay;
    const selected = binding(owner, 'old-wire-client', 'remote-enrollment');
    await assert.rejects(rpc.request('client.register', { binding: selected }), { code: PROFILE_ERROR });
    assert.equal(rpc.disconnected, true);
    await assert.rejects(rpc.request('doctor', {}), { code: PROFILE_ERROR }); // Poisoned client must not dispatch again.
    await relay.close();
    const marks = (await boundedFile(audit)).trim().split('\n');
    assert.deepEqual(marks, ['relay-started', 'forwarded-profile', 'real-success-profile', 'broker-closed']);
    // A later, separately explicit operation verifies that the remotely successful mutation
    // survived local rejection. The relay produced no result itself and forwarded exactly once.
    const probe = f.raw();
    failure(await probe.request('client.register', { binding: binding(owner, 'old-wire-client', 'different-session') }), 'client_binding_conflict');
    checkRegistration(success(await probe.request('client.register', { binding: selected })), selected);
  } finally { await f.close(); }
});
