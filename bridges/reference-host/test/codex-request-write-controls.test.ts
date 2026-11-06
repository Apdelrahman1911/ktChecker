/** SYNTHETIC request-write controls. Only a fresh, unconnected protocol instance and an
 * inert child facade are used: no subprocess, host/account/model, database or network is opened.
 * Protocol timers remain real and bounded; cleanup is checked without polling or timing sleeps.
 */
import { test, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { CodexAppServer, type ApplicationRequestEvidence } from '../src/codex-app-server.js';
import { digest, HostError } from '../src/journal.js';

type WriteCallback = (error?: Error | null) => void;
type Write = (line: string, callback: WriteCallback) => boolean;
type Outcome<T> = { kind: 'returned'; value: T } | { kind: 'rejected'; error: unknown };
type Timed = { timer: NodeJS.Timeout };
interface ProtocolView {
  failure: HostError | undefined;
  pending: Map<string, Timed>;
  waiters: Map<string, Timed[]>;
  receive(chunk: Buffer): void;
}
const threadId = 'synthetic-request-write-thread';
const bounded = { timeout: 10_000, concurrency: false };
function observe<T>(work: Promise<T>): Promise<Outcome<T>> {
  // Both terminal handlers are attached immediately, including on sibling requests and turn waits.
  return work.then<Outcome<T>, Outcome<T>>(
    value => ({ kind: 'returned', value }), error => ({ kind: 'rejected', error }),
  );
}
function rejected(outcome: Outcome<unknown>): unknown {
  assert(outcome.kind === 'rejected'); return outcome.error;
}
function frame(line: string): { id: string; method: string; params: Record<string, unknown> } {
  return JSON.parse(line) as { id: string; method: string; params: Record<string, unknown> };
}
function localProtocol(t: TestContext) {
  const server = new CodexAppServer({ timeoutMs: 15_000 }); server.ownThread(threadId);
  // Test-only private seam: there is no connect(), spawn(), real child or launch-ticket enrollment.
  const state = server as unknown as ProtocolView;
  const control = {
    write: ((_line: string, _callback: WriteCallback) => true) as Write,
    signalError: undefined as Error | undefined,
    lines: [] as string[], signals: [] as string[],
  };
  Object.assign(server, { child: { stdin: { write(line: string, callback: WriteCallback) {
    control.lines.push(line); return control.write(line, callback);
  } }, kill(signal: string) {
    control.signals.push(signal); if (control.signalError) throw control.signalError; return true;
  } } });
  // Remove the exact inert facade before close: teardown can clear/reject waits but cannot signal anything.
  t.after(() => { Object.assign(server, { child: undefined }); return server.close(); });
  // Default mock delegates to real clearTimeout; it does not replace or accelerate the protocol clock.
  const clearing = t.mock.method(globalThis, 'clearTimeout');
  const timers = (): NodeJS.Timeout[] => [
    ...[...state.pending.values()].map(value => value.timer),
    ...[...state.waiters.values()].flatMap(values => values.map(value => value.timer)),
  ];
  const assertClearedOnce = (handles: readonly NodeJS.Timeout[]): void => {
    assert.equal(new Set(handles).size, handles.length);
    for (const handle of handles) {
      assert.equal(clearing.mock.calls.filter(call => call.arguments[0] === handle).length, 1);
    }
  };
  const deliver = (value: unknown): void => state.receive(Buffer.from(JSON.stringify(value) + '\n'));
  return { server, state, control, timers, assertClearedOnce, deliver };
}

for (const method of ['request', 'requestRecorded'] as const) {
  for (const mode of ['throw', 'callback'] as const) {
    for (const signal of ['returns', 'throws'] as const) {
      test(`synthetic ${method} ${mode} failure clears all waits when the inert signal ${signal}`, bounded, async t => {
        const p = localProtocol(t), transportError = new Error('synthetic_transport_detail');
        const cleanupError = new Error('synthetic_signal_detail');
        if (signal === 'throws') p.control.signalError = cleanupError;
        const pending = [
          observe(p.server.request('synthetic/pending-one', {})),
          observe(p.server.request('synthetic/pending-two', {})),
        ];
        const waiting = [
          observe(p.server.waitForTurn(threadId, 'synthetic-turn-one', 15_000)),
          observe(p.server.waitForTurn(threadId, 'synthetic-turn-one', 15_000)),
          observe(p.server.waitForTurn(threadId, 'synthetic-turn-two', 15_000)),
        ];
        const evidence: { count: number; captured: ApplicationRequestEvidence | undefined;
          atWrite: ApplicationRequestEvidence | undefined } = { count: 0, captured: undefined, atWrite: undefined };
        const callback = { captured: undefined as WriteCallback | undefined };
        let registered: NodeJS.Timeout[] = [], pendingAtWrite = 0, groupsAtWrite = 0;
        p.control.write = (_line, done) => {
          // Capture here, assert outside: production's write catch must never swallow a test assertion.
          registered = p.timers(); pendingAtWrite = p.state.pending.size; groupsAtWrite = p.state.waiters.size;
          evidence.atWrite = evidence.captured;
          if (mode === 'throw') throw transportError;
          callback.captured = done; return true;
        };
        const failed = observe<unknown>(method === 'request' ? p.server.request('synthetic/failure', {}) :
          p.server.requestRecorded('synthetic/failure', {}, value => { evidence.count++; evidence.captured = value; }));
        if (mode === 'callback') {
          assert(callback.captured); assert.doesNotThrow(() => callback.captured!(transportError));
        }
        const failure = p.state.failure;
        assert(failure instanceof HostError); assert.equal(failure.code, 'codex_disconnected');
        assert.equal(failure.message, 'codex_disconnected');
        assert.notStrictEqual(failure, transportError); assert.notStrictEqual(failure, cleanupError);
        assert.equal(pendingAtWrite, 3); assert.equal(groupsAtWrite, 2); assert.equal(registered.length, 6);
        assert.equal(p.state.pending.size, 0); assert.equal(p.state.waiters.size, 0); p.assertClearedOnce(registered);
        assert.equal(p.control.lines.length, 3); assert.deepEqual(p.control.signals, ['SIGTERM']);
        assert.equal(p.control.lines.filter(line => frame(line).method === 'synthetic/failure').length, 1);
        assert.equal(evidence.count, method === 'requestRecorded' ? 1 : 0);
        if (method === 'requestRecorded') {
          assert(evidence.captured); assert.strictEqual(evidence.atWrite, evidence.captured);
          const line = p.control.lines[2]!;
          assert.equal(evidence.captured.applicationRequestId, frame(line).id);
          assert.equal(evidence.captured.method, 'synthetic/failure');
          assert.equal(evidence.captured.serializedRequestDigest, digest(line.slice(0, -1)));
        }
        for (const outcome of await Promise.all([...pending, ...waiting, failed])) {
          assert.strictEqual(rejected(outcome), failure);
        }
        let laterBeforeSend = 0;
        const later = [
          observe(p.server.request('synthetic/no-retry', {})),
          observe(p.server.requestRecorded('synthetic/no-retry-recorded', {}, () => { laterBeforeSend++; })),
          observe(p.server.waitForTurn(threadId, 'synthetic-later-turn', 15_000)),
        ];
        for (const outcome of await Promise.all(later)) assert.strictEqual(rejected(outcome), failure);
        assert.equal(laterBeforeSend, 0); assert.equal(p.control.lines.length, 3);
        assert.deepEqual(p.control.signals, ['SIGTERM']); assert.strictEqual(p.state.failure, failure);
        assert.equal(p.state.pending.size, 0); assert.equal(p.state.waiters.size, 0);
      });
    }
  }
}

test('synthetic prior protocol failure survives callback error, write throw and secondary signal errors', bounded, async t => {
  const p = localProtocol(t), callbackError = new Error('synthetic_callback_detail');
  const writeError = new Error('synthetic_write_detail'), cleanupError = new Error('synthetic_signal_detail');
  const waiting = observe(p.server.waitForTurn(threadId, 'synthetic-prior-failure-turn', 15_000));
  const phase = { first: undefined as HostError | undefined, callbackReturned: false, writeThrew: false };
  let registered: NodeJS.Timeout[] = [];
  p.control.write = (_line, callback) => {
    registered = p.timers();
    p.state.receive(Buffer.from('not-json\n')); phase.first = p.state.failure;
    p.control.signalError = cleanupError;
    callback(callbackError); phase.callbackReturned = true;
    phase.writeThrew = true; throw writeError;
  };
  const result = observe(p.server.requestRecorded('synthetic/prior-failure', {}));
  const first = phase.first;
  assert(first instanceof HostError); assert.equal(first.code, 'codex_protocol_error');
  assert(phase.callbackReturned); assert(phase.writeThrew); assert.strictEqual(p.state.failure, first);
  assert.equal(registered.length, 2); p.assertClearedOnce(registered);
  assert.equal(p.state.pending.size, 0); assert.equal(p.state.waiters.size, 0);
  for (const outcome of await Promise.all([waiting, result])) assert.strictEqual(rejected(outcome), first);
  assert.equal(p.control.lines.length, 1);
  // Three distinct fail notifications, not a write retry; the existing common fail routine is unchanged.
  assert.deepEqual(p.control.signals, ['SIGTERM', 'SIGTERM', 'SIGTERM']);
  assert.strictEqual(rejected(await observe(p.server.request('synthetic/no-prior-failure-retry', {}))), first);
  assert.equal(p.control.lines.length, 1); assert.strictEqual(p.state.failure, first);
});

test('synthetic beforeSend veto preserves its exact error without writing or latching a transport failure', bounded, async t => {
  const p = localProtocol(t), veto = new Error('synthetic_before_send_veto'); let evidenceCalls = 0;
  const result = observe(p.server.requestRecorded('synthetic/veto', {}, () => { evidenceCalls++; throw veto; }));
  assert.strictEqual(rejected(await result), veto); assert.equal(evidenceCalls, 1);
  assert.equal(p.control.lines.length, 0); assert.equal(p.control.signals.length, 0);
  assert.equal(p.state.pending.size, 0); assert.equal(p.state.waiters.size, 0); assert.equal(p.state.failure, undefined);
  p.control.write = (line, callback) => {
    callback(null); p.deliver({ id: frame(line).id, result: { accepted: true } }); return true;
  };
  assert.deepEqual(await p.server.request('synthetic/explicit-after-veto', {}), { accepted: true });
  assert.equal(p.control.lines.length, 1); assert.equal(p.control.signals.length, 0);
  assert.equal(p.state.failure, undefined); assert.equal(p.state.pending.size, 0);
});

test('synthetic requestRecorded keeps exact beforeSend evidence and one write when backpressure returns false', bounded, async t => {
  const p = localProtocol(t), order: string[] = [];
  const evidence: { captured: ApplicationRequestEvidence | undefined; atWrite: ApplicationRequestEvidence | undefined } =
    { captured: undefined, atWrite: undefined };
  let registered: NodeJS.Timeout[] = [];
  p.control.write = (line, callback) => {
    order.push('write'); evidence.atWrite = evidence.captured; registered = p.timers();
    callback(null); p.deliver({ id: frame(line).id, result: { accepted: true } }); return false;
  };
  const params = { label: 'synthetic-unicode-λ\nwith-newline' };
  const result = await p.server.requestRecorded('synthetic/backpressure', params, value => {
    order.push('beforeSend'); evidence.captured = value;
  });
  assert.deepEqual(result.result, { accepted: true }); assert.deepEqual(order, ['beforeSend', 'write']);
  assert.strictEqual(result.evidence, evidence.captured); assert.strictEqual(evidence.atWrite, evidence.captured);
  assert.equal(p.control.lines.length, 1); const line = p.control.lines[0]!;
  assert(line.endsWith('\n')); assert(!line.slice(0, -1).includes('\n'));
  assert.deepEqual(frame(line).params, params); assert.equal(result.evidence.applicationRequestId, frame(line).id);
  assert.equal(result.evidence.method, 'synthetic/backpressure');
  assert.equal(result.evidence.serializedRequestDigest, digest(line.slice(0, -1)));
  assert.match(result.evidence.sentAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  assert.equal(registered.length, 1); p.assertClearedOnce(registered);
  assert.equal(p.state.failure, undefined); assert.equal(p.state.pending.size, 0); assert.equal(p.state.waiters.size, 0);
  assert.equal(p.control.signals.length, 0);
});
