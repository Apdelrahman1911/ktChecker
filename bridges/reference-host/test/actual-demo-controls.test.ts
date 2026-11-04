/** Synthetic Node/fake-port controls only. Never runs the actual worker, CLI,
 * JVM, Codex executable, provider, network, or private credential store.
 */
import assert from 'node:assert/strict';
import { spawn, type ChildProcess } from 'node:child_process';
import { EventEmitter, once } from 'node:events';
import { test } from 'node:test';
import { CodexAppServer, type CodexTurnResult } from '../src/codex-app-server.js';
import { digest, HostError } from '../src/journal.js';
import { actualDemoInitialReport, type ActualDemoEvidence } from './actual-cli-fixture.js';
import { ActualDemoReporter, ExactlyOnceDemoBarrier, MAX_ACTUAL_DEMO_REPORT_BYTES, actualDemoBudget, actualDemoProgressPacket, actualDemoCheckpointPacket,
  actualDemoResultPacket, directActualDemoWorker, superviseActualDemo, type ActualDemoWorker, type DemoClock, type DemoProgress } from './actual-demo-controls.js';

const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const never = <T>() => new Promise<T>(() => {});
class Clock implements DemoClock {
  time = 0;
  private serial = 0;
  readonly timers = new Map<number, { at: number; callback(): void }>();
  now() { return this.time; }
  set(ms: number, callback: () => void) { const id = ++this.serial; this.timers.set(id, { at: this.time + ms, callback }); return id; }
  clear(id: unknown) { this.timers.delete(id as number); }
  async until(time: number) {
    for (;;) {
      const next = [...this.timers.entries()].filter(([, timer]) => timer.at <= time).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      this.time = next[1].at; this.timers.delete(next[0]); next[1].callback(); await flush();
    }
    this.time = time; await flush();
  }
}
class Evidence implements ActualDemoEvidence {
  path = 'not-a-file'; writes: string[] = []; closes = 0;
  async write(value: string) { this.writes.push(value); }
  async close() { this.closes++; }
  report() { return JSON.parse(this.writes[0]!) as Record<string, any>; }
}
class Worker implements ActualDemoWorker {
  message: (value: unknown) => void = () => {};
  exit: (code: number | null) => void = () => {};
  acknowledgements: number[] = []; signals: string[] = []; sequence = 0;
  onMessage(callback: (value: unknown) => void) { this.message = callback; }
  onExit(callback: (code: number | null) => void) { this.exit = callback; }
  acknowledge(sequence: number) { this.acknowledgements.push(sequence); }
  terminate(signal: 'SIGTERM' | 'SIGKILL') { this.signals.push(signal); }
  progress(value: DemoProgress) { this.message(actualDemoProgressPacket(++this.sequence, value)); }
  complete(report: Record<string, unknown>) { this.message(actualDemoResultPacket(++this.sequence, report)); }
  cleanup(allConfirmed = true) {
    for (let i = 1; i <= 2; i++) {
      const threadIdDigest = digest('synthetic-owned-' + i);
      this.progress({ kind: 'creation_started', attempt: i });
      this.progress({ kind: 'creation_confirmed', attempt: i, threadIdDigest });
      this.progress({ kind: 'deletion_started', threadIdDigest });
      this.progress({ kind: 'deletion_finished', threadIdDigest, outcome: 'confirmed_deleted' });
    }
    this.progress({ kind: 'cleanup_finished', allConfirmed });
  }
}
function initial() { return { ...actualDemoInitialReport(), evidenceScope: 'synthetic_supervisor_control_test_not_actual_delivery' }; }
function candidate() {
  return { ...initial(), verified: true, actualKernelIntegration: true, actualKotlinCompilerRun: true,
    initialBarrier: { invocations: 1, completed: true, failure: null }, deliveryRpcConfirmations: { complete: true }, deliveryReceiptJoins: [{}, {}, {}],
    phases: ['actual_background_compiler_diagnosis_joined_active_codex_turn', 'independent_consumer_own_thread_and_own_diagnostic_observation',
      'owner_repair_p2_rechecked_and_consumed_after_owned_thread_and_broker_resume'].map(phase => ({ phase })) };
}
function fixture(options: { reserve?: () => Promise<ActualDemoEvidence>; start?: () => Promise<ActualDemoWorker>;
  publish?: (summary: Record<string, unknown>) => Promise<void> } = {}) {
  const clock = new Clock(), worker = new Worker(), evidence = new Evidence(), summaries: Record<string, unknown>[] = [];
  const outcome = superviseActualDemo({ maxRuntimeMs: 1_000, clock, initialReport: initial(), reserve: options.reserve ?? (async () => evidence),
    start: options.start ?? (async () => worker), publish: options.publish ?? (async value => { summaries.push(value); }) });
  return { clock, worker, evidence, summaries, outcome };
}

test('finite controller budget reserves termination and finalization inside the selected cap', () => {
  for (const cap of [1_000, 10_000, 600_000, 1_800_000]) {
    const budget = actualDemoBudget(cap);
    assert.equal(budget.workerAndCleanupMs + budget.terminationGraceMs + budget.finalizationReserveMs, cap);
    assert(budget.workerAndCleanupMs > 0 && budget.terminationGraceMs > 0 && budget.finalizationReserveMs > 0);
    assert(Object.isFrozen(budget));
  }
  for (const invalid of [NaN, Infinity, 1.5, '1000', 999, 1_800_001]) assert.throws(() => actualDemoBudget(invalid as number));
});
test('deadline is armed before a never-returning reservation; no worker or positive output starts', async () => {
  let started = false;
  const f = fixture({ reserve: () => never(), start: async () => { started = true; return new Worker(); } });
  assert.equal(f.clock.timers.size, 2);
  await f.clock.until(700);
  assert.equal((await f.outcome).exitCode, 1); assert.equal(started, false); assert.equal(f.summaries[0]!.verified, false);
});
test('exclusive reservation rejection preserves the existing path and prevents startup', async () => {
  let starts = 0;
  const f = fixture({ reserve: async () => { throw new HostError('actual_demo_evidence_exists'); }, start: async () => { starts++; return new Worker(); } });
  await flush(); const result = await f.outcome;
  assert.equal(starts, 0); assert.equal(result.exitCode, 2); assert.equal(result.failure, 'actual_demo_evidence_exists');
});
test('a reservation arriving after timeout is closed, not reused to start late work', async () => {
  let release!: (evidence: ActualDemoEvidence) => void, starts = 0;
  const f = fixture({ reserve: () => new Promise(resolve => { release = resolve; }), start: async () => { starts++; return new Worker(); } });
  await f.clock.until(700); assert.equal((await f.outcome).verified, false);
  release(f.evidence); await flush(); assert.equal(starts, 0); assert.equal(f.evidence.closes, 1); assert.equal(f.evidence.writes.length, 0);
});
test('hanging work and child finally receive only direct TERM/KILL requests, then bounded failure evidence', async () => {
  const f = fixture(); await flush();
  f.worker.progress({ kind: 'stage', stage: 'cleanup' });
  await f.clock.until(700); assert.deepEqual(f.worker.signals, ['SIGTERM']);
  await f.clock.until(800); const result = await f.outcome;
  assert.deepEqual(f.worker.signals, ['SIGTERM', 'SIGKILL']); assert.equal(result.failure, 'actual_demo_controller_deadline');
  assert.equal(f.evidence.report().verified, false); assert.equal(f.evidence.report().supervisor.workerExitObserved, false);
  assert.equal(f.evidence.report().runtimeCleanup.nativeQuiescenceObserved, false);
  assert.equal(f.evidence.writes.length, 1); assert.equal(f.summaries.length, 1);
});
test('unknown creation and pending deletion remain unknown when cleanup cannot finish', async () => {
  const f = fixture(); await flush(); const known = digest('owned');
  f.worker.progress({ kind: 'creation_started', attempt: 1 });
  f.worker.progress({ kind: 'creation_confirmed', attempt: 1, threadIdDigest: known });
  f.worker.progress({ kind: 'creation_started', attempt: 2 });
  f.worker.progress({ kind: 'deletion_started', threadIdDigest: known });
  await f.clock.until(800); await f.outcome;
  const progress = f.evidence.report().supervisor.ownedThreadProgress;
  assert.equal(progress.unknownCreationOutcomes, 1); assert.equal(progress.confirmedDeleted, 0);
  assert.equal(progress.threads[0].deletionOutcome, 'pending_unknown'); assert.equal(progress.allConfirmed, false);
  assert.equal(f.evidence.report().privateRecoveryStateRetained, true);
});
test('a completed delete before a blocked control close is retained without claiming complete cleanup', async () => {
  const f = fixture(); await flush(); const id = digest('owned');
  f.worker.progress({ kind: 'creation_started', attempt: 1 }); f.worker.progress({ kind: 'creation_confirmed', attempt: 1, threadIdDigest: id });
  f.worker.progress({ kind: 'deletion_started', threadIdDigest: id });
  f.worker.progress({ kind: 'deletion_finished', threadIdDigest: id, outcome: 'confirmed_deleted' });
  await f.clock.until(800); await f.outcome;
  const progress = f.evidence.report().supervisor.ownedThreadProgress;
  assert.equal(progress.confirmedDeleted, 1); assert.equal(progress.cleanupReturned, false); assert.equal(progress.allConfirmed, false);
});
test('explicit unconfirmed cleanup remains unconfirmed even when individual deletion receipts look complete', async () => {
  const f = fixture(); await flush(); f.worker.cleanup(false); f.worker.complete(candidate()); f.worker.exit(0); await flush();
  assert.equal((await f.outcome).verified, false);
  const progress = f.evidence.report().supervisor.ownedThreadProgress;
  assert.equal(progress.confirmedDeleted, 2); assert.equal(progress.cleanupReturned, true); assert.equal(progress.allConfirmed, false);
  assert.equal(f.evidence.report().ownedDisposableThreadsDeleted, null);
});
test('success requires exact phases, barrier, receipts, cleanup, worker exit, completed evidence and final stdout together', async () => {
  const f = fixture(); await flush(); f.worker.cleanup(); f.worker.complete(candidate());
  await flush(); assert.equal(f.evidence.writes.length, 0, 'A result IPC packet without exit is not success');
  f.worker.exit(0); await flush(); const result = await f.outcome;
  assert.equal(result.verified, true); assert.equal(result.exitCode, 0); assert.equal(f.evidence.closes, 1);
  assert.equal(f.summaries[0]!.verified, true); assert.equal(f.evidence.report().supervisor.evidenceFileAloneSufficient, false);
  assert.equal(f.clock.timers.size, 0);
});
test('worker nonzero, incomplete barrier, retained failure and missing phase cannot be promoted by a true result packet', async () => {
  for (const fault of ['nonzero', 'barrier', 'phase', 'retained-failure']) {
    const f = fixture(); await flush(); f.worker.cleanup(); const report = candidate();
    if (fault === 'barrier') report.initialBarrier.invocations = 2;
    if (fault === 'phase') report.phases.pop();
    if (fault === 'retained-failure') (report as Record<string, unknown>).failure = 'actual_delivery_failed';
    f.worker.complete(report); f.worker.exit(fault === 'nonzero' ? 1 : 0); await flush();
    assert.equal((await f.outcome).verified, false); assert.equal(f.summaries[0]!.verified, false);
  }
});
test('malformed final phase shapes fail closed without throwing from the asynchronous worker-exit callback', async () => {
  for (const phase of [null, false, 7, [], 'wrong-phase']) {
    const f = fixture(); await flush(); f.worker.cleanup();
    const report = candidate() as Record<string, unknown>; report.phases = [phase, phase, phase];
    f.worker.complete(report); assert.doesNotThrow(() => f.worker.exit(0)); await flush();
    assert.equal((await f.outcome).verified, false); assert.equal(f.evidence.report().verified, false);
  }
});
test('bounded non-final checkpoints retain partial phases/confirmations but can never substitute for the final result packet', async () => {
  for (const outcome of ['exit', 'timeout']) {
    const f = fixture(); await flush(); f.worker.cleanup();
    const partial = candidate(); partial.phases.splice(1);
    f.worker.message(actualDemoCheckpointPacket(++f.worker.sequence, partial));
    f.worker.progress({ kind: 'stage', stage: 'cleanup' });
    if (outcome === 'exit') f.worker.exit(0); else await f.clock.until(800);
    await flush(); assert.equal((await f.outcome).verified, false);
    assert.equal(f.evidence.report().phases.length, 1); assert.equal(f.evidence.report().deliveryRpcConfirmations.complete, true);
    assert.equal(f.evidence.report().supervisor.finalResultPacketReceived, false);
    assert.equal(f.evidence.report().stage, 'cleanup'); assert.equal(f.summaries[0]!.verified, false);
  }
});
test('success arriving exactly at the work cutoff loses before a queued timeout callback can run', async () => {
  const f = fixture(); await flush(); f.worker.cleanup();
  f.clock.time = 700; // Deliberately do not run the timer yet: receive must check the absolute clock.
  f.worker.complete(candidate()); f.worker.exit(0); await flush();
  assert.equal((await f.outcome).verified, false); assert.equal(f.summaries[0]!.verified, false);
});
test('timeout is sticky when a worker reports late success after TERM', async () => {
  const f = fixture(); await flush(); f.worker.cleanup();
  await f.clock.until(700); f.worker.complete(candidate()); f.worker.exit(0); await flush();
  assert.equal((await f.outcome).failure, 'actual_demo_controller_deadline'); assert.equal(f.evidence.report().verified, false);
});
test('a never-settling evidence write or close cannot hang controller completion', async () => {
  for (const field of ['write', 'close'] as const) {
    const evidence = new Evidence(); evidence[field] = () => never();
    const f = fixture({ reserve: async () => evidence }); await flush(); f.worker.cleanup(); f.worker.complete(candidate()); f.worker.exit(0);
    await flush(); await f.clock.until(1_000);
    const result = await f.outcome; assert.equal(result.exitCode, 1); assert.equal(result.failure, 'actual_demo_finalization_deadline');
    assert(!f.summaries.some(summary => summary.verified === true));
  }
});
test('late successful evidence completion or hung final stdout never changes a deadline failure to exit zero', async () => {
  let finishWrite!: () => void;
  const evidence = new Evidence(); evidence.write = () => new Promise(resolve => { finishWrite = resolve; });
  const f = fixture({ reserve: async () => evidence }); await flush(); f.worker.cleanup(); f.worker.complete(candidate()); f.worker.exit(0);
  await f.clock.until(1_000); assert.equal((await f.outcome).verified, false);
  finishWrite(); await flush(); assert(!f.summaries.some(summary => summary.verified === true));
  const g = fixture({ publish: () => never() }); await flush(); g.worker.cleanup(); g.worker.complete(candidate()); g.worker.exit(0);
  await g.clock.until(1_000); assert.equal((await g.outcome).verified, false);
  assert.equal(g.evidence.report().verified, true, 'A candidate file alone is explicitly not a success receipt');
});
test('I/O exceptions yield failure without indefinite finalize/close retries', async () => {
  const evidence = new Evidence(); evidence.write = async () => { throw new Error('PRIVATE_IO_ERROR_MUST_NOT_ESCAPE'); };
  const f = fixture({ reserve: async () => evidence }); await flush(); f.worker.cleanup(); f.worker.complete(candidate()); f.worker.exit(0);
  await flush(); assert.equal((await f.outcome).failure, 'actual_demo_finalization_failed');
  assert(!JSON.stringify(f.summaries).includes('PRIVATE_IO_ERROR')); assert.equal(evidence.closes, 0);
});
test('unexpected, oversized, repeated or out-of-order IPC fails closed without an acknowledgement', async () => {
  for (const packet of [JSON.stringify({ kind: 'progress', sequence: 1, progress: { kind: 'stage', stage: '/private/arbitrary' } }),
    JSON.stringify({ kind: 'progress', sequence: 2, progress: { kind: 'stage', stage: 'cleanup' } }),
    JSON.stringify({ kind: 'progress', sequence: 1, progress: { kind: 'deletion_started', threadIdDigest: digest('not-owned') } }),
    'x'.repeat(MAX_ACTUAL_DEMO_REPORT_BYTES + 129)]) {
    const f = fixture(); await flush(); f.worker.message(packet); await f.clock.until(100); await f.outcome;
    assert.equal(f.worker.acknowledgements.length, 0); assert.equal(f.summaries[0]!.verified, false);
  }
});
test('trusted reporter bounds and allowlists BEFORE serializing; accessors/private extras are not read', async () => {
  let touched = 0;
  const extra = { verified: false };
  Object.defineProperty(extra, 'credential', { enumerable: true, get() { touched++; throw new Error('credential getter'); } });
  assert.throws(() => actualDemoResultPacket(1, extra));
  const progress = { kind: 'stage' };
  Object.defineProperty(progress, 'stage', { enumerable: true, get() { touched++; throw new Error('progress getter'); } });
  assert.throws(() => actualDemoProgressPacket(1, progress as DemoProgress));
  const array: unknown[] = [null]; Object.defineProperty(array, '0', { get() { touched++; throw new Error('array getter'); } });
  assert.throws(() => actualDemoResultPacket(1, { phases: array }));
  assert.throws(() => actualDemoResultPacket(1, { failure: 'x'.repeat(MAX_ACTUAL_DEMO_REPORT_BYTES) }));
  assert.equal(touched, 0);
  let receive!: (packet: unknown) => void; const packets: string[] = [];
  const reporter = new ActualDemoReporter({ onMessage: callback => { receive = callback; }, send: (packet, callback) => { packets.push(packet); callback(null); } });
  let acknowledged = false;
  const pending = reporter.progress({ kind: 'creation_started', attempt: 1 }).then(() => { acknowledged = true; });
  assert.throws(() => reporter.progress({ kind: 'stage', stage: 'cleanup' }));
  await flush(); assert.equal(acknowledged, false); receive({ kind: 'continue', sequence: 2 }); await flush(); assert.equal(acknowledged, false);
  receive({ kind: 'continue', sequence: 1 }); await pending; assert.equal(packets.length, 1);
});
test('both ends enforce six bounded report packets and a final result ends the trusted reporter stream', async () => {
  let receive!: (packet: unknown) => void; const packets: string[] = [];
  const reporter = new ActualDemoReporter({ onMessage: callback => { receive = callback; },
    send: (packet, callback) => { packets.push(packet); callback(null); receive({ kind: 'continue', sequence: JSON.parse(packet).sequence }); } });
  for (let i = 0; i < 6; i++) await reporter.checkpoint(initial());
  assert.throws(() => reporter.checkpoint(initial()), { code: 'actual_demo_report_packet_bound' }); assert.equal(packets.length, 6);
  const f = fixture(); await flush();
  for (let i = 0; i < 7; i++) f.worker.message(actualDemoCheckpointPacket(++f.worker.sequence, initial()));
  await f.clock.until(100); assert.equal((await f.outcome).failure, 'actual_demo_worker_protocol'); assert.equal(f.worker.acknowledgements.length, 6);
  let acknowledge!: (packet: unknown) => void;
  const final = new ActualDemoReporter({ onMessage: callback => { acknowledge = callback; },
    send: (packet, callback) => { callback(null); acknowledge({ kind: 'continue', sequence: JSON.parse(packet).sequence }); } });
  await final.result(initial()); assert.throws(() => final.progress({ kind: 'stage', stage: 'cleanup' }), { code: 'actual_demo_reporter_bound' });
});
test('a stale sender callback or malformed acknowledgement cannot clear another in-flight intent', async () => {
  let receive!: (packet: unknown) => void; const callbacks: ((error: Error | null) => void)[] = [];
  const reporter = new ActualDemoReporter({ onMessage: callback => { receive = callback; }, send: (_, callback) => { callbacks.push(callback); } });
  const first = reporter.progress({ kind: 'stage', stage: 'cleanup' }); receive({ kind: 'continue', sequence: 1 }); await first;
  const second = reporter.progress({ kind: 'cleanup_finished', allConfirmed: false });
  callbacks[0]!(new Error('late callback')); let touched = false;
  const malformed = { sequence: 2 }; Object.defineProperty(malformed, 'kind', { enumerable: true, get() { touched = true; return 'continue'; } });
  receive(malformed); assert.equal(touched, false);
  assert.throws(() => reporter.result(initial()), { code: 'actual_demo_reporter_bound' });
  receive({ kind: 'continue', sequence: 2 }); await second;
});
test('a direct ChildProcess error is not an exit receipt and never disables owned termination prematurely', () => {
  const child = Object.assign(new EventEmitter(), { exitCode: null, signalCode: null, connected: true,
    kill(signal: string) { signals.push(signal); return true; }, send() {} });
  const signals: string[] = [], owned = directActualDemoWorker(child as unknown as ChildProcess);
  let exits = 0, failures = 0; owned.onExit(() => { exits++; }); owned.onFailure!(() => { failures++; });
  child.emit('error', new Error('synthetic child-process failure'));
  assert.equal(failures, 1); assert.equal(exits, 0); owned.terminate('SIGTERM'); assert.deepEqual(signals, ['SIGTERM']);
  child.emit('exit', null); assert.equal(exits, 1); owned.terminate('SIGKILL'); assert.deepEqual(signals, ['SIGTERM']);
});

const application = { applicationRequestId: 'synthetic-barrier-request', method: 'turn/start', serializedRequestDigest: digest('synthetic request'), sentAt: '2026-11-04T00:00:00.000Z' };
const tool = { threadId: 'owned-thread', turnId: 'owned-turn', tool: 'hold_for_background_review' };
function barrier(clock?: Clock) {
  const value = clock ? new ExactlyOnceDemoBarrier('owned-thread', clock) : new ExactlyOnceDemoBarrier('owned-thread');
  value.recordApplication(application); value.confirmTurn('owned-turn'); return value;
}
function completed(): CodexTurnResult { return { threadId: 'owned-thread', turnId: 'owned-turn', status: 'completed', messages: [], applicationRequest: application }; }
test('barrier binds owned thread/confirmed turn and exact application metadata, release is idempotent', async () => {
  const value = barrier(); const call = value.invoke(tool); await value.waitForInvocation(100); value.release(); value.release(); await call;
  value.confirmCompleted(completed()); const snapshot = value.snapshot();
  assert.equal(snapshot.invocations, 1); assert.equal(snapshot.completed, true); assert.equal(snapshot.failure, null);
  assert.equal(snapshot.applicationRequest!.serializedRequestDigest, application.serializedRequestDigest); assert(Object.isFrozen(snapshot));
});
test('barrier latches duplicate before/after release and after an apparent completed result', async () => {
  for (const when of ['held', 'released', 'completed']) {
    const value = barrier(); const first = value.invoke(tool); void first.catch(() => {});
    if (when !== 'held') { value.release(); await first; }
    if (when === 'completed') value.confirmCompleted(completed());
    await assert.rejects(value.invoke(tool), { code: 'actual_demo_barrier_duplicate' });
    await first.catch(() => {});
    assert.equal(value.snapshot().invocations, 2); assert.equal(value.snapshot().released, true); assert.equal(value.snapshot().completed, false);
    assert.throws(() => value.confirmCompleted(completed()), { code: 'actual_demo_barrier_duplicate' });
  }
});
test('actual CodexAppServer swallowing the handler error cannot hide a duplicated barrier', async () => {
  const value = barrier(), server = new CodexAppServer(); server.ownThread('owned-thread');
  server.handleTool('hold_for_background_review', params => value.invoke(params));
  // Exercise the real server-request catch path without connecting/spawning it.
  const dispatch = server as unknown as { serverRequest(value: Record<string, unknown>): Promise<void> };
  const first = dispatch.serverRequest({ id: 'synthetic-tool-1', method: 'item/tool/call', params: tool });
  const second = dispatch.serverRequest({ id: 'synthetic-tool-2', method: 'item/tool/call', params: tool });
  await Promise.all([first, second]); // Both dispatch promises fulfilled: the server did swallow the errors.
  assert.throws(() => value.assertHealthy(), { code: 'actual_demo_barrier_duplicate' });
  await server.close();
});
test('no invocation, incorrect thread/turn, failed turn and a changed application cannot verify the barrier', async () => {
  const clock = new Clock(), absent = barrier(clock), pending = absent.waitForInvocation(40); void pending.catch(() => {});
  await clock.until(40); await assert.rejects(pending, { code: 'actual_demo_barrier_not_invoked' });
  for (const params of [{ ...tool, threadId: 'not-owned' }, { ...tool, turnId: 'another-turn' }]) {
    const value = barrier(); await assert.rejects(value.invoke(params)); assert.throws(() => value.assertHealthy());
  }
  for (const wrong of [{ ...completed(), status: 'failed' }, { ...completed(), applicationRequest: { ...application, applicationRequestId: 'other-request' } },
    { ...completed(), applicationRequest: { ...application, method: 'turn/other' } },
    { ...completed(), applicationRequest: { ...application, sentAt: '2026-11-04T00:00:01.000Z' } },
    { ...completed(), applicationRequest: { ...application, serializedRequestDigest: digest('other-application') } },
    { ...completed(), applicationRequest: undefined }]) {
    const value = barrier(), call = value.invoke(tool); value.release(); await call;
    assert.throws(() => value.confirmCompleted(wrong as CodexTurnResult)); assert.throws(() => value.assertHealthy());
  }
});
test('a tool call before the turn/start response is later bound, not guessed; missing optional tool turn metadata is explicit', async () => {
  const value = new ExactlyOnceDemoBarrier('owned-thread'); value.recordApplication(application);
  const call = value.invoke(tool); value.confirmTurn('owned-turn'); value.release(); await call; value.confirmCompleted(completed());
  const missing = barrier(), noTurn = { threadId: tool.threadId, tool: tool.tool }, other = missing.invoke(noTurn);
  missing.release(); await other; missing.confirmCompleted(completed()); assert.equal(missing.snapshot().toolTurnBinding, 'not_supplied_by_tool_request');
  const conflict = new ExactlyOnceDemoBarrier('owned-thread'), held = conflict.invoke(tool); void held.catch(() => {});
  assert.throws(() => conflict.confirmTurn('another-turn')); await assert.rejects(held);
});

test('independent responsive supervisor caps a synchronously blocked Node-only child including TERM grace', { timeout: 10_000 }, async t => {
  const script = `process.on('message', m => { if (m.kind !== 'start') return;
    process.on('SIGTERM', () => {});
    process.send(JSON.stringify({kind:'progress',sequence:1,progress:{kind:'stage',stage:'verify_codex_executable_version'}}), () => {
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0);
    }); });`;
  const child = spawn(process.execPath, ['-e', script], { stdio: ['ignore', 'ignore', 'ignore', 'ipc'] });
  const exited = once(child, 'exit');
  t.after(async () => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL'); await exited; });
  const owned = directActualDemoWorker(child), evidence = new Evidence(), summaries: Record<string, unknown>[] = [], start = performance.now();
  const result = await superviseActualDemo({ maxRuntimeMs: 1_000, initialReport: initial(), reserve: async () => evidence,
    start: async () => ({ ...owned, begin() { child.send({ kind: 'start' }); } }), publish: async summary => { summaries.push(summary); } });
  assert.equal(result.verified, false); assert.equal(result.exitCode, 1); assert.equal(result.failure, 'actual_demo_controller_deadline');
  assert.equal(evidence.report().stage, 'verify_codex_executable_version'); assert.equal(summaries[0]!.verified, false);
  assert(performance.now() - start < 5_000, 'Synthetic OS scheduling allowance, not a hard-real-time guarantee');
  await exited;
});
