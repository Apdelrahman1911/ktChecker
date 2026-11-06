// Synthetic dependency-injection controls for the protected HostPort witness SPI.
// Positive receipts below are NOT real model-delivery proof, passed-review evidence,
// host/process recovery evidence, or authority to perform a native guarded action.
import { test, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ReferenceHost } from '../src/adapter.js';
import { KernelBroker } from '../src/broker.js';
import { canonical, digest, HostError, InboxJournal } from '../src/journal.js';
import type { ContextItem, GateCut, ModelRequest, ModelWitness } from '../src/types.js';
import { FixtureHost, FixtureKernel, registration, binding, event, S1 } from './fixtures.js';

type ActualWitness = Extract<ModelWitness, { kind: 'actual-model-request' }>;
type WitnessField = 'invocationId' | 'hostThreadId' | 'generation' | 'requestDigest' | 'modelRequestId' | 'contextDigests';
type WitnessTransform = (witness: ActualWitness) => ActualWitness;
const initialEventIds = ['witness-a', 'witness-b'] as const;
const sorted = (values: readonly string[]): string[] => [...values].sort();

/** Only the in-memory test SPI changes; adapter, broker, and journal are real classes. */
class WitnessHost extends FixtureHost {
  readonly requests: ModelRequest[] = [];
  readonly baselines: ActualWitness[] = [];
  readonly witnesses: ActualWitness[] = [];
  transform: WitnessTransform = witness => witness;

  override async invokeModel(request: ModelRequest): Promise<ModelWitness> {
    this.modelCalls++;
    this.requests.push(structuredClone(request));
    const baseline: ActualWitness = {
      kind: 'actual-model-request', invocationId: request.invocationId,
      modelRequestId: 'synthetic-witness-model-' + this.modelCalls,
      hostThreadId: request.hostThreadId, generation: request.generation,
      requestDigest: request.requestDigest, contextDigests: request.contexts.map(item => item.digest),
    };
    this.baselines.push(structuredClone(baseline));
    const witness = this.transform(structuredClone(baseline));
    this.witnesses.push(structuredClone(witness));
    return witness;
  }
}

interface WitnessFixture {
  r: ReturnType<typeof registration>;
  kernel: FixtureKernel;
  port: WitnessHost;
  journal: InboxJournal;
  adapter: ReferenceHost;
  cut: GateCut;
}

// Local copy of the small fixture setup pattern; no adapter.test.ts helper is exported/imported.
async function setup(t: TestContext): Promise<WitnessFixture> {
  const directory = await mkdtemp(join(tmpdir(), 'ktchecker-witness-controls-'));
  let opened: InboxJournal | undefined;
  t.after(async () => {
    try { opened?.close(); }
    finally { await rm(directory, { recursive: true, force: true }); }
  });
  const r = registration();
  const kernel = new FixtureKernel(r), port = new WitnessHost(binding(r));
  const journal = new InboxJournal(directory, binding(r));
  opened = journal;
  const adapter = new ReferenceHost(journal, new KernelBroker(kernel, r), port);
  kernel.eventsByClient.set(r.binding.clientId, initialEventIds.map((id, index) => event(r, id, S1, index + 1)));
  const cut = await adapter.prepareFinal();
  assert.equal(cut.ready, true);
  assert.deepEqual(sorted(cut.requiredEventIds), sorted(initialEventIds));
  assert.equal(cut.eventFrontier, initialEventIds.length);
  assert.equal(port.modelCalls, 0);
  assert.equal(port.insertionCalls, initialEventIds.length);
  assert.equal(journal.read().pendingModelRequest ?? null, null);
  assert.equal(kernel.calls.filter(call => call.method === 'review.observe').length, 0);
  return { r, kernel, port, journal, adapter, cut };
}

const rpcCalls = (fixture: WitnessFixture, method: string) => fixture.kernel.calls.filter(call => call.method === method);

function ackedContexts(fixture: WitnessFixture, eventIds: readonly string[]): ContextItem[] {
  assert.ok(eventIds.length > 0);
  const state = fixture.journal.read();
  const contexts = eventIds.map(id => {
    const entry = state.inbox[id];
    assert.ok(entry, 'the required event must really be in the journal');
    assert.equal(entry.event.deliveryRequired, true);
    assert.ok(entry.item);
    assert.ok(entry.inserted);
    assert.equal(entry.acknowledged, true);
    assert.equal(entry.inserted.receipt.contextDigest, entry.item.digest);
    assert.equal(entry.inserted.receipt.receiptId, entry.insertionReceiptId);
    assert.deepEqual(fixture.kernel.acked.get(id), entry.inserted.receipt);
    assert.deepEqual(fixture.port.items.get(entry.item.marker), entry.item);
    return entry.item;
  }).sort((a, b) => a.marker.localeCompare(b.marker));
  assert.equal(contexts.length, eventIds.length);
  assert.equal(new Set(contexts.map(item => item.digest)).size, eventIds.length);
  return contexts;
}

function assertUnobserved(fixture: WitnessFixture, eventIds: readonly string[]): void {
  ackedContexts(fixture, eventIds);
  const state = fixture.journal.read();
  for (const id of eventIds) {
    assert.equal(state.inbox[id]!.observation, null);
    assert.equal(state.inbox[id]!.observed, false);
    assert.equal(fixture.kernel.observed.has(id), false);
  }
  assert.equal(state.lastProblem, null, 'the delivery fence must not depend on an unrelated problem');
}

function assertMatchingWitness(request: ModelRequest, witness: ActualWitness): void {
  assert.notEqual(witness.modelRequestId, '');
  assert.deepEqual({ ...witness, contextDigests: sorted(witness.contextDigests) }, {
    kind: 'actual-model-request', invocationId: request.invocationId, modelRequestId: witness.modelRequestId,
    hostThreadId: request.hostThreadId, generation: request.generation, requestDigest: request.requestDigest,
    contextDigests: sorted(request.contexts.map(item => item.digest)),
  });
}

function assertedInvocation(fixture: WitnessFixture, index: number, eventIds: readonly string[]) {
  assert.equal(fixture.port.modelCalls, index + 1);
  assert.equal(fixture.port.requests.length, index + 1);
  assert.equal(fixture.port.baselines.length, index + 1);
  assert.equal(fixture.port.witnesses.length, index + 1);
  const request = fixture.port.requests[index]!, baseline = fixture.port.baselines[index]!, witness = fixture.port.witnesses[index]!;
  assert.notEqual(request.invocationId, '');
  assert.equal(request.hostThreadId, fixture.port.binding.hostThreadId);
  assert.equal(request.generation, fixture.r.binding.generation);
  assert.deepEqual(request.contexts, ackedContexts(fixture, eventIds));
  assert.equal(request.requestDigest, digest(canonical({
    hostThreadId: request.hostThreadId, generation: request.generation, contexts: request.contexts,
  })));
  assertMatchingWitness(request, baseline);
  return { request, baseline, witness };
}

async function assertFencesClosed(fixture: WitnessFixture, cut: GateCut): Promise<void> {
  assert.equal(cut.ready, true, 'a non-ready fixture cut must not make this control pass vacuously');
  const observedCalls = rpcCalls(fixture, 'review.observe').length;
  const completedCalls = rpcCalls(fixture, 'gate.complete').length;
  assert.deepEqual(fixture.adapter.mutationFence(), { allowed: false, reasons: ['required_context_not_observed'] });
  const decision = await fixture.adapter.completeFinal(cut.cutId);
  assert.equal(decision.allowed, false);
  assert.equal(decision.actionPerformed, false);
  assert.equal(decision.cutId, cut.cutId);
  assert.deepEqual(decision.reasons, ['required_context_not_observed']);
  assert.equal(rpcCalls(fixture, 'gate.complete').length, completedCalls, 'local refusal must precede gate.complete RPC');
  assert.equal(rpcCalls(fixture, 'review.observe').length, observedCalls);
}

function assertPublished(
  fixture: WitnessFixture, request: ModelRequest, witness: ActualWitness,
  eventIds: readonly string[], expectedObserveCalls: number,
): void {
  assertMatchingWitness(request, witness);
  const state = fixture.journal.read();
  const first = state.inbox[eventIds[0]!]!;
  assert.ok(first.observation);
  const receipt = first.observation;
  assert.notEqual(receipt.observationId, '');
  assert.deepEqual(receipt, {
    observationId: receipt.observationId, modelRequestId: witness.modelRequestId,
    contextDigests: sorted(request.contexts.map(item => item.digest)),
  });
  for (const id of eventIds) {
    assert.deepEqual(state.inbox[id]!.observation, receipt);
    assert.equal(state.inbox[id]!.observed, true);
    assert.equal(fixture.kernel.observed.has(id), true);
  }
  assert.equal(state.pendingModelRequest, null);
  assert.equal(state.lastProblem, null);
  const calls = rpcCalls(fixture, 'review.observe');
  assert.equal(calls.length, expectedObserveCalls);
  const call = calls[expectedObserveCalls - 1]!;
  assert.deepEqual(sorted(call.params.eventIds as string[]), sorted(eventIds));
  assert.deepEqual(call.params.receipt, receipt);
  assert.deepEqual(call.params.credential, fixture.r.adapter);
  const observeIndex = fixture.kernel.calls.indexOf(call);
  for (const id of eventIds) {
    const ackIndex = fixture.kernel.calls.findIndex(candidate => candidate.method === 'review.ack' &&
      (candidate.params.eventIds as string[]).includes(id));
    assert.ok(ackIndex >= 0 && ackIndex < observeIndex, 'each exact event ACK must precede its observation RPC');
  }
}

async function assertFencesOpen(
  fixture: WitnessFixture, cut: GateCut, eventIds: readonly string[], expectedObserveCalls: number,
): Promise<void> {
  assert.deepEqual(sorted([...fixture.kernel.observed]), sorted(eventIds));
  assert.deepEqual(fixture.adapter.mutationFence(), { allowed: true, reasons: [] });
  const before = rpcCalls(fixture, 'gate.complete').length;
  const decision = await fixture.adapter.completeFinal(cut.cutId);
  assert.equal(decision.allowed, true);
  assert.equal(decision.actionPerformed, false, 'synthetic delivery state confers no native-action authority');
  assert.equal(decision.cutId, cut.cutId);
  assert.deepEqual(decision.reasons, []);
  assert.equal(rpcCalls(fixture, 'gate.complete').length, before + 1);
  assert.equal(rpcCalls(fixture, 'review.observe').length, expectedObserveCalls);
}

const foreignContextDigest = digest('synthetic witness-controls foreign context, not an inserted item');
const invalidWitnesses: readonly { name: string; field: WitnessField; alter: WitnessTransform }[] = [
  { name: 'mismatched invocationId', field: 'invocationId', alter: witness => ({ ...witness, invocationId: witness.invocationId + '-other' }) },
  { name: 'mismatched hostThreadId', field: 'hostThreadId', alter: witness => ({ ...witness, hostThreadId: witness.hostThreadId + '-other' }) },
  { name: 'mismatched generation', field: 'generation', alter: witness => ({ ...witness, generation: witness.generation + 1 }) },
  { name: 'mismatched requestDigest', field: 'requestDigest', alter: witness => ({ ...witness, requestDigest: digest('synthetic different request body') }) },
  { name: 'empty modelRequestId', field: 'modelRequestId', alter: witness => ({ ...witness, modelRequestId: '' }) },
  { name: 'one mismatched context digest', field: 'contextDigests', alter: witness => ({ ...witness, contextDigests: [foreignContextDigest, ...witness.contextDigests.slice(1)] }) },
  { name: 'one extra foreign context digest', field: 'contextDigests', alter: witness => ({ ...witness, contextDigests: [...witness.contextDigests, foreignContextDigest] }) },
  { name: 'one missing required context digest', field: 'contextDigests', alter: witness => ({ ...witness, contextDigests: witness.contextDigests.slice(0, 1) }) },
  { name: 'one extra duplicate context digest', field: 'contextDigests', alter: witness => ({ ...witness, contextDigests: [...witness.contextDigests, witness.contextDigests[0]!] }) },
  { name: 'same-size duplicate replacing a required context digest', field: 'contextDigests', alter: witness => ({ ...witness, contextDigests: [witness.contextDigests[0]!, witness.contextDigests[0]!] }) },
];

// Ten finite, typed SPI value faults. Missing means an omitted required ARRAY ENTRY,
// not an absent/untyped contextDigests property. Ordering alone is intentionally valid.
for (const control of invalidWitnesses) {
  test(`synthetic witness rejects ${control.name} before observe and final completion`, { timeout: 10_000 }, async t => {
    const fixture = await setup(t);
    assertUnobserved(fixture, initialEventIds);
    await assertFencesClosed(fixture, fixture.cut);
    fixture.port.transform = control.alter;
    await assert.rejects(() => fixture.adapter.requestModelObservation(),
      (error: unknown) => error instanceof HostError && error.code === 'model_request_proof_mismatch');
    const rejected = assertedInvocation(fixture, 0, initialEventIds);
    assert.equal(rejected.baseline.contextDigests.length, 2);
    assert.equal(rejected.baseline.contextDigests.includes(foreignContextDigest), false);
    assert.notDeepEqual(rejected.witness[control.field], rejected.baseline[control.field]);
    assert.deepEqual({ ...rejected.witness, [control.field]: rejected.baseline[control.field] }, rejected.baseline,
      'every other witness field must remain correct');
    if (control.field === 'contextDigests') {
      assert.notDeepEqual(sorted(rejected.witness.contextDigests), sorted(rejected.baseline.contextDigests));
    }
    assertUnobserved(fixture, initialEventIds);
    assert.deepEqual(fixture.journal.read().pendingModelRequest, rejected.request);
    assert.equal(rpcCalls(fixture, 'review.observe').length, 0);
    assert.equal(fixture.kernel.observed.size, 0);
    await fixture.adapter.syncReceipts();
    assertUnobserved(fixture, initialEventIds);
    assert.deepEqual(fixture.journal.read().pendingModelRequest, rejected.request);
    assert.equal(rpcCalls(fixture, 'review.observe').length, 0, 'receipt replay must not turn a rejected witness into observation');
    await assertFencesClosed(fixture, fixture.cut);

    // A valid synthetic retry uses the SAME saved request, not a fabricated journal repair.
    fixture.port.transform = witness => witness;
    assert.deepEqual(await fixture.adapter.requestModelObservation(), { observed: true, reason: null });
    const accepted = assertedInvocation(fixture, 1, initialEventIds);
    assert.deepEqual(accepted.request, rejected.request);
    assertPublished(fixture, accepted.request, accepted.witness, initialEventIds, 1);
    assert.equal(fixture.port.insertionCalls, initialEventIds.length);
    await assertFencesOpen(fixture, fixture.cut, initialEventIds, 1);
  });
}

for (const order of ['request-order', 'reversed-order'] as const) {
  test(`synthetic ${order} witness publishes exact ACKed contexts and opens only delivery fences`, { timeout: 10_000 }, async t => {
    const fixture = await setup(t);
    assertUnobserved(fixture, initialEventIds);
    await assertFencesClosed(fixture, fixture.cut);
    if (order === 'reversed-order') fixture.port.transform = witness => ({ ...witness, contextDigests: [...witness.contextDigests].reverse() });
    assert.deepEqual(await fixture.adapter.requestModelObservation(), { observed: true, reason: null });
    const accepted = assertedInvocation(fixture, 0, initialEventIds);
    if (order === 'reversed-order') {
      assert.notDeepEqual(accepted.witness.contextDigests, accepted.baseline.contextDigests);
      assert.deepEqual(accepted.witness.contextDigests, [...accepted.baseline.contextDigests].reverse());
    } else {
      assert.deepEqual(accepted.witness, accepted.baseline);
    }
    assertPublished(fixture, accepted.request, accepted.witness, initialEventIds, 1);
    assert.equal(fixture.port.insertionCalls, initialEventIds.length);
    await assertFencesOpen(fixture, fixture.cut, initialEventIds, 1);
  });
}

test('synthetic reused modelRequestId rejects a changed context set and permits only a fresh-ID retry', { timeout: 10_000 }, async t => {
  const fixture = await setup(t);
  const reusedId = 'synthetic-reused-model-request';
  fixture.port.transform = witness => ({ ...witness, modelRequestId: reusedId });
  assertUnobserved(fixture, initialEventIds);
  await assertFencesClosed(fixture, fixture.cut);
  assert.deepEqual(await fixture.adapter.requestModelObservation(), { observed: true, reason: null });
  const first = assertedInvocation(fixture, 0, initialEventIds);
  assertPublished(fixture, first.request, first.witness, initialEventIds, 1);
  await assertFencesOpen(fixture, fixture.cut, initialEventIds, 1);
  const originalEntries = initialEventIds.map(id => structuredClone(fixture.journal.read().inbox[id]!));

  // Append a different required context through the normal public poll/insert/ACK path.
  // Thread, generation and snapshot stay fixed; the second witness matches its NEW invocation.
  const laterEventId = 'witness-c', allEventIds = [...initialEventIds, laterEventId];
  fixture.kernel.eventsByClient.get(fixture.r.binding.clientId)!.push(event(fixture.r, laterEventId, S1, 3));
  const nextCut = await fixture.adapter.prepareFinal();
  assert.equal(nextCut.ready, true);
  assert.notEqual(nextCut.cutId, fixture.cut.cutId);
  assert.deepEqual(sorted(nextCut.requiredEventIds), sorted(allEventIds));
  assert.equal(nextCut.eventFrontier, allEventIds.length);
  assertUnobserved(fixture, [laterEventId]);
  await assertFencesClosed(fixture, nextCut);
  await assert.rejects(() => fixture.adapter.requestModelObservation(),
    (error: unknown) => error instanceof HostError && error.code === 'model_request_proof_reused');
  const rejected = assertedInvocation(fixture, 1, [laterEventId]);
  assertMatchingWitness(rejected.request, rejected.witness);
  assert.equal(rejected.witness.modelRequestId, first.witness.modelRequestId);
  assert.notEqual(rejected.request.invocationId, first.request.invocationId);
  assert.notEqual(rejected.request.requestDigest, first.request.requestDigest);
  assert.equal(rejected.request.hostThreadId, first.request.hostThreadId);
  assert.equal(rejected.request.generation, first.request.generation);
  assert.notDeepEqual(sorted(rejected.witness.contextDigests), sorted(first.witness.contextDigests));
  assert.deepEqual(fixture.journal.read().pendingModelRequest, rejected.request);
  assert.deepEqual(initialEventIds.map(id => fixture.journal.read().inbox[id]!), originalEntries);
  assertUnobserved(fixture, [laterEventId]);
  assert.equal(rpcCalls(fixture, 'review.observe').length, 1, 'the rejected reuse must not publish an additional observation RPC');
  await fixture.adapter.syncReceipts();
  assertUnobserved(fixture, [laterEventId]);
  assert.deepEqual(fixture.journal.read().pendingModelRequest, rejected.request);
  assert.deepEqual(initialEventIds.map(id => fixture.journal.read().inbox[id]!), originalEntries);
  assert.deepEqual(sorted([...fixture.kernel.observed]), sorted(initialEventIds));
  assert.equal(rpcCalls(fixture, 'review.observe').length, 1);
  await assertFencesClosed(fixture, nextCut);

  // Only this synthetic witness ID changes; no crash, reopen, timer or process recovery is exercised.
  fixture.port.transform = witness => ({ ...witness, modelRequestId: 'synthetic-fresh-model-request' });
  assert.deepEqual(await fixture.adapter.requestModelObservation(), { observed: true, reason: null });
  const accepted = assertedInvocation(fixture, 2, [laterEventId]);
  assert.deepEqual(accepted.request, rejected.request);
  assert.notEqual(accepted.witness.modelRequestId, rejected.witness.modelRequestId);
  assert.deepEqual({ ...accepted.witness, modelRequestId: rejected.witness.modelRequestId }, rejected.witness);
  assertPublished(fixture, accepted.request, accepted.witness, [laterEventId], 2);
  assert.deepEqual(initialEventIds.map(id => fixture.journal.read().inbox[id]!), originalEntries);
  assert.equal(fixture.port.insertionCalls, allEventIds.length);
  await assertFencesOpen(fixture, nextCut, allEventIds, 2);
});
