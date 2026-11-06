/** SYNTHETIC retained-reroute controls. No child process, Codex account, real host/owner,
 * launch ticket, compiler or model is opened. Protocol delivery is one explicit in-memory batch.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CodexAppServer } from '../src/codex-app-server.js';
import { CodexHostPort } from '../src/codex-host.js';
import { digest } from '../src/journal.js';
import type { AcceptanceReport } from './current-image-acceptance-runner.js';
import { controlFixture } from './current-image-acceptance-fixtures.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
const rerouted = /codex_selected_model_rerouted/;
const notice = (threadId = 'synthetic-current-thread') => ({ method: 'model/rerouted', params: {
  threadId, turnId: 'synthetic-turn', fromModel: 'gpt-6-astra', toModel: 'synthetic-other-model', reason: 'synthetic-reroute',
} });
function localProtocol(failOnModelReroute = true) {
  const server = new CodexAppServer({ failOnModelReroute }); server.ownThread('synthetic-current-thread');
  // This private-method cast only drives the decoder; it cannot enroll any actual launch ticket.
  const decoder = server as unknown as { receive(chunk: Buffer): void };
  const deliver = (...entries: unknown[]): void => decoder.receive(Buffer.from(entries.map(entry => JSON.stringify(entry) + '\n').join('')));
  const assertNoModelReroute = (): void => server.assertNoModelReroute();
  return { server, deliver, assertNoModelReroute };
}
function assertFailed(report: AcceptanceReport): void {
  assert.equal(report.status, 'incomplete_or_failed'); assert.equal(report.failure, 'codex_selected_model_rerouted');
  assert.equal(report.actualHostDeliveryVerified, false); assert.equal(report.origin, 'synthetic-control');
  assert.equal(report.qualified, false); assert.equal(report.actionPerformed, false);
}

for (const method of ['request', 'requestRecorded'] as const) test(method + ' rejects a response then owned reroute in one decoded batch', async () => {
  const p = localProtocol(); let writes = 0, signals = 0;
  // There is deliberately no connect()/spawn(): stdin and kill are inert in-memory controls.
  Object.assign(p.server, { child: { stdin: { write(line: string) {
    writes++; const request = JSON.parse(line) as { id: string; method: string };
    assert.equal(request.method, 'thread/items/list');
    p.deliver({ id: request.id, result: { data: [] } }, notice()); return true;
  } }, kill() { signals++; return true; } } });
  await assert.rejects(p.server[method]('thread/items/list', { threadId: 'synthetic-current-thread' }), rerouted);
  assert.equal(writes, 1); assert.equal(signals, 1); assert.throws(p.assertNoModelReroute, rerouted);
});

test('response then reroute remains backward compatible when the exact-model guard is off', async () => {
  const p = localProtocol(false);
  Object.assign(p.server, { child: { stdin: { write(line: string) {
    const request = JSON.parse(line) as { id: string };
    p.deliver({ id: request.id, result: { data: [] } }, notice()); return true;
  } }, kill() { assert.fail('guard-off control must not signal'); } } });
  assert.deepEqual(await p.server.request('thread/items/list', {}), { data: [] });
  assert.doesNotThrow(p.assertNoModelReroute);
});

test('retained reroute failure never hides or revises the durable Codex host evidence view', async () => {
  const p = localProtocol(), saved = { effort: 'max', status: 'verified', turnId: 'synthetic-turn', publicItemId: 'synthetic-public',
    publicResponseDigest: digest('synthetic-public-response'), request: { invocationId: 'synthetic-invocation',
      requestDigest: digest('synthetic-request'), contexts: [{ digest: digest('synthetic-context') }] },
    applicationRequest: { applicationRequestId: 'synthetic-application', method: 'turn/start',
      serializedRequestDigest: digest('synthetic-serialized-request'), sentAt: '2026-11-06T00:00:00.000Z' } };
  // A read-only in-memory store facade, NEVER passed to the actual acceptance factory.
  const host = Object.create(CodexHostPort.prototype) as CodexHostPort;
  Object.assign(host, { server: p.server, store: { read: () => ({ invocations: { synthetic: saved } }) } });
  const before = host.evidence(); assert.equal(before[0]!.status, 'verified');
  p.deliver(notice()); assert.throws(p.assertNoModelReroute, rerouted);
  assert.deepEqual(host.evidence(), before);
  await p.server.close(); assert.throws(p.assertNoModelReroute, rerouted);
  assert.deepEqual(host.evidence(), before);
});

test('reroute after the second application proof fails the next guard without erasing earlier joined delivery', linuxOnly, async t => {
  const f = await controlFixture(t), p = localProtocol(); let proofs: unknown;
  f.host.evidenceHook = phase => {
    if (f.host.requests.length === 2) { proofs = structuredClone(f.host.phases); p.deliver(notice()); }
    return phase;
  };
  const report = await f.run({ assertNoModelReroute: p.assertNoModelReroute }); assertFailed(report);
  assert.equal(report.unknownOperation, 'application_evidence'); assert.equal(report.finalBarrier, 'not_attempted');
  assert.equal(f.host.requests.length, 2); assert.deepEqual(f.host.phases, proofs);
  assert.equal(f.evidence.rows.filter(row => row.stage === 'private_joined_delivery').length, 1);
  const summary = f.evidence.rows.find(row => row.stage === 'acceptance_summary')!.value as AcceptanceReport;
  assertFailed(summary); assert.equal(summary.patches.length, 1); assert.equal(f.adapter.calls.includes('gate.complete'), false);
});

for (const [method, stage] of [['gate.prepare', 'final_prepare'], ['gate.complete', 'final_complete']] as const) {
  test('reroute during ' + method + ' invalidates unchanged completed application proofs', linuxOnly, async t => {
    const f = await controlFixture(t), p = localProtocol(); let proofs: unknown;
    f.adapter.responseHook = (called, response) => {
      if (called === method) { proofs = structuredClone(f.host.phases); p.deliver(notice()); }
      return response;
    };
    const report = await f.run({ assertNoModelReroute: p.assertNoModelReroute }); assertFailed(report);
    assert.equal(report.unknownOperation, stage); assert.equal(report.finalBarrier, 'failed');
    assert.equal(report.patches.length, 2); assert.equal(f.host.requests.length, 2); assert.deepEqual(f.host.phases, proofs);
    assert.equal(f.evidence.rows.filter(row => row.stage === 'private_joined_delivery').length, 2);
    assertFailed(f.evidence.rows.find(row => row.stage === 'acceptance_summary')!.value as AcceptanceReport);
    assert.equal(f.adapter.calls.filter(called => called === 'gate.complete').length, method === 'gate.complete' ? 1 : 0);
  });
}

test('reroute in the final private summary write rejects instead of returning its summary candidate', linuxOnly, async t => {
  const f = await controlFixture(t), p = localProtocol();
  f.evidence.hook = stage => { if (stage === 'acceptance_summary') p.deliver(notice()); };
  await assert.rejects(f.run({ assertNoModelReroute: p.assertNoModelReroute }), rerouted);
  assert.equal(f.host.requests.length, 2); assert.equal(f.adapter.calls.filter(called => called === 'gate.complete').length, 1);
  assert.equal(f.evidence.rows.filter(row => row.stage === 'private_joined_delivery').length, 2);
  // Existing candidate evidence is retained, NOT adopted as completion or rewritten after failure.
  assert.equal((f.evidence.rows.find(row => row.stage === 'acceptance_summary')!.value as AcceptanceReport).status, 'verified_delivery_final_denied');
  assert.throws(() => f.run(), /components_previously_attempted/);
});

test('queued reroute after the last synchronous summary check is rejected at the settled-return boundary', linuxOnly, async t => {
  const f = await controlFixture(t), p = localProtocol(); let summaryWritten = false, received = false, checkedHealthyAfterSummary = false;
  f.evidence.hook = stage => {
    if (stage !== 'acceptance_summary') return;
    summaryWritten = true;
    // Deterministic ordering: queued before the producer resolves, handled after its last sync
    // check, and before the shared terminal .then callback. No sleep or imagined future event.
    queueMicrotask(() => { received = true; p.deliver(notice()); });
  };
  await assert.rejects(f.run({ assertNoModelReroute: () => {
    if (summaryWritten && !received) checkedHealthyAfterSummary = true;
    p.assertNoModelReroute();
  } }), rerouted);
  assert.equal(checkedHealthyAfterSummary, true); assert.equal(received, true);
  assert.equal(f.host.requests.length, 2); assert.equal(f.adapter.calls.filter(called => called === 'gate.complete').length, 1);
  assert.equal(f.evidence.rows.filter(row => row.stage === 'private_joined_delivery').length, 2);
  assert.throws(p.assertNoModelReroute, rerouted);
  assert.throws(() => f.run(), /components_previously_attempted/);
});

test('settled-return guard ignores unrelated reroutes and does not promise future notifications', linuxOnly, async t => {
  const f = await controlFixture(t), p = localProtocol(); let unrelatedReceived = false;
  f.evidence.hook = stage => {
    if (stage === 'acceptance_summary') queueMicrotask(() => { unrelatedReceived = true; p.deliver(notice('synthetic-other-thread')); });
  };
  const report = await f.run({ assertNoModelReroute: p.assertNoModelReroute });
  assert.equal(unrelatedReceived, true); assert.equal(report.status, 'verified_delivery_final_denied');
  assert.equal(report.origin, 'synthetic-control'); assert.equal(report.actualHostDeliveryVerified, false);
  assert.doesNotThrow(p.assertNoModelReroute);
  // A later event cannot be observed before this returned report. No retrospective/future guarantee.
  p.deliver(notice()); assert.throws(p.assertNoModelReroute, rerouted);
});
