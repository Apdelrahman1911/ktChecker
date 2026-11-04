/** Synthetic returned-RPC/public-application fixtures. These tests are NOT real
 * kernel, broker process, model, internal-inference, or private-ledger evidence.
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resolve } from 'node:path';
import { BrokerError, PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest } from '../src/journal.js';
import { prepareDiagnosticContext } from '../src/context-projection.js';
import { expectedPublicResponse } from '../src/codex-observation.js';
import type { ContextReceipt, ModelRequest, ObservationReceipt, Registration, ReviewEvent } from '../src/types.js';
import { ActualCliFixture, ActualFaultBroker } from './actual-cli-fixture.js';
import { DeliveryReceiptCapture, deliveryBinding, deliveryIdentity, verifyDeliveryReceiptJoin, type DeliveryReceiptSnapshot } from './delivery-receipts.js';
import { S1, S2, binding, event, registration } from './fixtures.js';

const r = registration();
function publicEvent(owner = r, id = 'event-1', sequence = 1): ReviewEvent { return { ...event(owner, id, S1, sequence), kind: 'result' }; }
function page(events: ReviewEvent[]) { return { contractVersion: PREVIEW_CONTRACT, events, cursor: 'synthetic-not-retained-cursor', hasMore: false }; }
function response(ids: string[], clientId = r.binding.clientId, state = 'acknowledged'): JsonObject {
  return { contractVersion: PREVIEW_CONTRACT, clientId, eventIds: ids, state };
}
function application(owner = r, events = [publicEvent(owner)], applicationId = 'synthetic-application-1') {
  const host = binding(owner), contexts = events.map(value => prepareDiagnosticContext(value, host, S1)).sort((a, b) => a.marker.localeCompare(b.marker));
  const request: ModelRequest = { invocationId: 'synthetic-invocation', hostThreadId: host.hostThreadId, generation: host.client.generation, contexts,
    requestDigest: digest(canonical({ hostThreadId: host.hostThreadId, generation: host.client.generation, contexts })) };
  const output = canonical({ contractVersion: PREVIEW_CONTRACT, invocationId: request.invocationId, requestDigest: request.requestDigest,
    contexts: contexts.map(context => ({ contextDigest: context.digest, text: context.text })) });
  const serializedApplicationRequest = JSON.stringify({ id: applicationId, method: 'turn/start', params: {
    threadId: host.hostThreadId, input: [], toolOutput: { name: 'ktchecker', output }, effort: 'low' } });
  const verifiedPublicResponseText = canonical(expectedPublicResponse(request));
  const ackReceipts: ContextReceipt[] = contexts.map((context, i) => ({ receiptId: 'synthetic-ack-' + i, contextDigest: context.digest,
    contextItemId: 'synthetic-context-item-' + i, insertedAt: '2026-11-04T00:00:00.000Z' }));
  const observation: ObservationReceipt = { observationId: 'synthetic-observation', modelRequestId: 'codex-app-request:' + applicationId,
    contextDigests: contexts.map(context => context.digest).sort() };
  const phase = { phase: 'synthetic_rpc_confirmation_join', consumer: owner.binding.clientId, status: 'verified', model: 'gpt-6-astra',
    internalInferenceRequestTrace: false, applicationRequest: { applicationRequestId: applicationId, method: 'turn/start',
      serializedRequestDigest: digest(serializedApplicationRequest), sentAt: '2026-11-04T00:00:00.000Z' },
    serializedApplicationRequest, invocationId: request.invocationId, requestDigest: request.requestDigest,
    contextDigests: contexts.map(context => context.digest), verifiedPublicResponseText, publicResponseDigest: digest(verifiedPublicResponseText),
    deliveryBinding: deliveryBinding(owner.binding) };
  return { events, contexts, ackReceipts, observation, phase };
}
function captureEvents(capture: DeliveryReceiptCapture, events: ReviewEvent[], owner = r) {
  capture.beforeRequest('review.events', { credential: owner.client, cursor: null })!.returned(page(events));
}
function ack(capture: DeliveryReceiptCapture, id: string, receipt: ContextReceipt, owner = r) {
  capture.beforeRequest('review.ack', { credential: owner.adapter, eventIds: [id], receipt })!.returned(response([id], owner.binding.clientId));
}
function observe(capture: DeliveryReceiptCapture, ids: string[], receipt: ObservationReceipt, owner = r) {
  capture.beforeRequest('review.observe', { credential: owner.adapter, eventIds: ids, receipt })!.returned(response(ids, owner.binding.clientId, 'model_observed'));
}
function fixture(events = [publicEvent()], owner = r) {
  const proof = application(owner, events), capture = new DeliveryReceiptCapture(); capture.bind(owner.binding); captureEvents(capture, events, owner);
  events.forEach((value, index) => ack(capture, value.eventId, proof.ackReceipts[index]!, owner));
  observe(capture, events.map(value => value.eventId), proof.observation, owner);
  return { ...proof, capture };
}

test('successful captured ACK and observation responses join exact public application/context/event metadata offline', () => {
  const f = fixture([publicEvent(r, 'event-1', 1), publicEvent(r, 'event-2', 2)]), snapshot = f.capture.snapshot();
  const join = verifyDeliveryReceiptJoin(snapshot, f.phase, r.binding);
  assert.equal(join.joinedEvents, 2); assert.deepEqual(join.observationConfirmationOrders, [3]);
  assert.equal(snapshot.rows.length, 4); assert.equal(snapshot.successfulConfirmationCalls, 3);
  assert(snapshot.rows.every(row => row.event.sourceSnapshotId === S1));
  assert(snapshot.rows.every(row => row.responseProjectionDigest === digest(canonical(row.response))));
  assert.equal(snapshot.evidenceScope, 'captured_application_rpc_confirmations_not_signed_kernel_or_internal_inference_attestations');
});
test('capture ignores arbitrary extras, credentials, cursors, paths and their toJSON/getters before and after await', () => {
  const f = application(), capture = new DeliveryReceiptCapture(); capture.bind(r.binding); let touched = 0;
  const secret = 'PRIVATE_SENTINEL_NOT_TO_BE_READ_HASHED_OR_SERIALIZED';
  const forbidden = { enumerable: true, get() { touched++; throw new Error(secret); } };
  const credential = { clientId: r.binding.clientId }; Object.defineProperty(credential, 'token', forbidden);
  Object.defineProperty(credential, 'toJSON', forbidden);
  const params: JsonObject = { credential, eventIds: ['event-1'], receipt: { ...f.ackReceipts[0] } };
  Object.defineProperty(params, 'privatePath', forbidden); Object.defineProperty(params, 'toJSON', forbidden);
  Object.defineProperty(params.receipt, 'secret', forbidden);
  const eventResult = page(f.events); Object.defineProperty(eventResult, 'cursor', forbidden);
  Object.defineProperty(eventResult.events[0], 'privatePath', forbidden);
  capture.beforeRequest('review.events', { credential })!.returned(eventResult);
  const confirmation = response(['event-1']); Object.defineProperty(confirmation, 'token', forbidden);
  Object.defineProperty(confirmation, 'toJSON', forbidden); Object.defineProperty(confirmation, 'arbitraryError', forbidden);
  const pending = capture.beforeRequest('review.ack', params)!; pending.returned(confirmation);
  observe(capture, ['event-1'], f.observation);
  const serialized = JSON.stringify(capture.snapshot()); assert.equal(touched, 0);
  for (const excluded of [secret, digest(secret), r.client.token, r.adapter.token, r.binding.clientId, f.ackReceipts[0]!.receiptId,
    f.ackReceipts[0]!.contextItemId, f.observation.observationId, f.observation.modelRequestId, 'Label.kt', 'Nullable name']) {
    assert(!serialized.includes(excluded), 'Only selected commitments and response enums are retained');
  }
  assert.equal(verifyDeliveryReceiptJoin(capture.snapshot(), f.phase, r.binding).joinedEvents, 1);
});
test('ActualFaultBroker captures around a successful actual-return boundary with immutable pre-await public projection', async () => {
  const proof = application(); let release!: (value: unknown) => void;
  const transport: BrokerPort & { close(): Promise<void> } = { close: async () => {}, request: <T>() => new Promise<T>(resolve => { release = value => resolve(value as T); }) };
  const owner = new ActualCliFixture(resolve('not-executed'), resolve('not-created'), resolve('not-executed-jdk'));
  const broker = new ActualFaultBroker(owner, () => transport); broker.deliveryReceipts.bind(r.binding);
  const eventCall = broker.request('review.events', { credential: r.client, cursor: null }); release(page(proof.events)); await eventCall;
  let touched = 0;
  const credential = { clientId: r.binding.clientId }; Object.defineProperty(credential, 'token', { get() { touched++; throw new Error('SENTINEL'); } });
  const receipt = { ...proof.ackReceipts[0]! }, ids = ['event-1'];
  const call = broker.request('review.ack', { credential, eventIds: ids, receipt });
  credential.clientId = 'mutated-after-await'; receipt.contextDigest = S2; ids[0] = 'substituted-after-await';
  release(response(['event-1'])); await call;
  const row = broker.deliveryReceipts.snapshot().rows[0]!;
  assert.equal(row.event.eventIdDigest, deliveryIdentity('event', 'event-1')); assert.equal(row.ack.contextDigest, proof.contexts[0]!.digest);
  assert.equal(touched, 0); await broker.close();
});
test('rejected RPCs never produce a positive confirmation, including a reused rejected capture ticket', async () => {
  const f = application(), capture = new DeliveryReceiptCapture(); capture.bind(r.binding); captureEvents(capture, f.events);
  const ticket = capture.beforeRequest('review.ack', { credential: r.adapter, eventIds: ['event-1'], receipt: f.ackReceipts[0] })!;
  ticket.rejected(); ticket.rejected(); assert.throws(() => ticket.returned(response(['event-1'])), { code: 'delivery_capture_already_settled' });
  assert.equal(capture.snapshot().rows.length, 0); assert.equal(capture.snapshot().complete, true);
  const owner = new ActualCliFixture(resolve('not-executed'), resolve('not-created'), resolve('not-executed-jdk'));
  const broker = new ActualFaultBroker(owner, () => ({ request: async () => { throw new BrokerError('unauthorized', 'SYNTHETIC_PRIVATE_REJECTION'); }, close: async () => {} }));
  broker.deliveryReceipts.bind(r.binding);
  await assert.rejects(broker.request('review.ack', { credential: r.client, eventIds: ['event-1'], receipt: f.ackReceipts[0] }), { code: 'unauthorized' });
  assert.equal(broker.deliveryReceipts.snapshot().rows.length, 0); assert.equal(broker.deliveryReceipts.snapshot().complete, true);
  assert(!JSON.stringify(broker.deliveryReceipts.snapshot()).includes('SYNTHETIC_PRIVATE_REJECTION')); await broker.close();
});
test('wrong response contracts, client, event sets, duplicate events and states fail atomically and permanently', () => {
  for (const mutate of [
    (value: JsonObject) => { value.contractVersion = 'other'; }, (value: JsonObject) => { value.clientId = 'other-client'; },
    (value: JsonObject) => { value.eventIds = ['other-event']; }, (value: JsonObject) => { value.eventIds = ['event-1', 'event-1']; },
    (value: JsonObject) => { value.state = 'model_observed'; },
  ]) {
    const f = application(), capture = new DeliveryReceiptCapture(); capture.bind(r.binding); captureEvents(capture, f.events);
    const result = response(['event-1']); mutate(result);
    const ticket = capture.beforeRequest('review.ack', { credential: r.adapter, eventIds: ['event-1'], receipt: f.ackReceipts[0] })!;
    assert.throws(() => ticket.returned(result), { code: 'delivery_capture_confirmation_rejected' });
    assert.equal(capture.snapshot().rows.length, 0); assert.equal(capture.snapshot().complete, false);
    assert.throws(() => ack(capture, 'event-1', f.ackReceipts[0]!), { code: 'delivery_capture_failed' });
  }
});
test('successful-but-mismatched event pages cannot supply another client, generation or revised event metadata', () => {
  for (const change of ['client', 'generation', 'payload', 'revision']) {
    const capture = new DeliveryReceiptCapture(); capture.bind(r.binding); const original = publicEvent(), revised = { ...original };
    if (change === 'client') revised.clientId = 'another-client';
    if (change === 'generation') revised.generation++;
    if (change === 'payload') revised.payloadDigest = S2;
    if (change === 'revision') { captureEvents(capture, [original]); revised.sequence++; }
    assert.throws(() => captureEvents(capture, [revised]), { code: 'delivery_capture_confirmation_rejected' });
    assert.equal(capture.snapshot().complete, false); assert.equal(capture.snapshot().rows.length, 0);
  }
});
test('ACK needs an earlier exact successful event and observe needs prior matching ACK/context set', () => {
  const proof = application();
  for (const fault of ['missing-event', 'missing-ack', 'context-substitution', 'extra-context']) {
    const capture = new DeliveryReceiptCapture(); capture.bind(r.binding);
    if (fault !== 'missing-event') captureEvents(capture, proof.events);
    if (fault === 'missing-event') assert.throws(() => ack(capture, 'event-1', proof.ackReceipts[0]!));
    else {
      if (fault !== 'missing-ack') ack(capture, 'event-1', proof.ackReceipts[0]!);
      const observation = { ...proof.observation, contextDigests: fault === 'context-substitution' ? [S2] : fault === 'extra-context' ? [...proof.observation.contextDigests, S2] : proof.observation.contextDigests };
      assert.throws(() => observe(capture, ['event-1'], observation));
    }
    assert(capture.snapshot().rows.every(row => row.method !== 'review.observe')); assert.equal(capture.snapshot().complete, false);
  }
});
test('multi-event confirmation with a mismatched final event never records a valid prefix', () => {
  const proof = application(), capture = new DeliveryReceiptCapture(); capture.bind(r.binding); captureEvents(capture, proof.events);
  const ticket = capture.beforeRequest('review.ack', { credential: r.adapter, eventIds: ['event-1', 'unknown-event'], receipt: proof.ackReceipts[0] })!;
  assert.throws(() => ticket.returned(response(['event-1', 'unknown-event']))); assert.equal(capture.snapshot().rows.length, 0);
});
test('repeated successful confirmations are separate immutable occurrences, not dedup or persistent-state claims', () => {
  const f = application(), capture = new DeliveryReceiptCapture(); capture.bind(r.binding); captureEvents(capture, f.events); captureEvents(capture, f.events);
  ack(capture, 'event-1', f.ackReceipts[0]!); ack(capture, 'event-1', f.ackReceipts[0]!);
  observe(capture, ['event-1'], f.observation); observe(capture, ['event-1'], f.observation);
  const first = capture.snapshot();
  assert.equal(first.capturedEvents, 1); assert.deepEqual(first.rows.map(row => row.confirmationOrder), [1, 2, 3, 4]);
  assert.deepEqual(first.rows.map(row => row.eventOccurrence), [1, 2, 3, 4]);
  assert.deepEqual(verifyDeliveryReceiptJoin(first, f.phase, r.binding).observationConfirmationOrders, [3, 4]);
  assert(Object.isFrozen(first) && Object.isFrozen(first.rows));
  for (const row of first.rows) for (const part of [row, row.binding, row.event, row.ack, row.response, row.response.eventIdDigests, ...(row.observation ? [row.observation, row.observation.contextDigests] : [])]) {
    assert(Object.isFrozen(part));
  }
  observe(capture, ['event-1'], f.observation); assert.equal(first.rows.length, 4); assert.equal(capture.snapshot().rows.length, 5);
  assert.throws(() => { (first.rows[0]!.ack as { contextDigest: string }).contextDigest = S2; }, TypeError);
});
test('a different ACK receipt for the same event is not laundered by a successful replay-shaped response', () => {
  const f = fixture();
  assert.throws(() => ack(f.capture, 'event-1', { ...f.ackReceipts[0]!, contextItemId: 'substituted-context-item' }));
  assert.equal(f.capture.snapshot().rows.length, 2); assert.equal(f.capture.snapshot().complete, false);
});
test('offline joins reject client/generation/event/context/ACK/application substitutions and broken response commitments', () => {
  const f = fixture(), original = f.capture.snapshot();
  const mutations: ((snapshot: any) => void)[] = [
    snapshot => { snapshot.rows[1].binding.clientIdDigest = deliveryIdentity('client', 'another-client'); },
    snapshot => { snapshot.rows[1].binding.generation++; snapshot.rows[1].event.generation++; },
    snapshot => { snapshot.rows[1].event.eventIdDigest = deliveryIdentity('event', 'other-event'); },
    snapshot => { snapshot.rows[1].event.sourceSnapshotId = S2; },
    snapshot => { snapshot.rows[1].event.payloadDigest = S2; },
    snapshot => { snapshot.rows[1].ack.contextDigest = S2; },
    snapshot => { snapshot.rows[1].ack.contextItemIdDigest = S2; },
    snapshot => { snapshot.rows[1].ack.receiptIdDigest = S2; },
    snapshot => { snapshot.rows[1].priorAckConfirmationOrder = 2; },
    snapshot => { snapshot.rows[1].observation.applicationRequestIdDigest = S2; },
    snapshot => { snapshot.rows[1].observation.modelRequestIdDigest = S2; },
    snapshot => { snapshot.rows[1].observation.contextDigests = [S2]; },
    snapshot => { snapshot.rows[1].responseProjectionDigest = S2; },
    snapshot => { snapshot.rows[1].response.state = 'acknowledged'; snapshot.rows[1].responseProjectionDigest = digest(canonical(snapshot.rows[1].response)); },
    snapshot => { snapshot.rows.reverse(); }, snapshot => { snapshot.rows.shift(); }, snapshot => { snapshot.rows[1].eventOccurrence = 1; },
    snapshot => { snapshot.successfulConfirmationCalls++; }, snapshot => { snapshot.complete = false; },
  ];
  for (const [index, mutate] of mutations.entries()) {
    const snapshot = structuredClone(original); mutate(snapshot);
    assert.throws(() => verifyDeliveryReceiptJoin(snapshot, f.phase, r.binding), 'mutation ' + index);
  }
  const changedApplication = application(r, f.events, 'different-actual-application').phase;
  assert.throws(() => verifyDeliveryReceiptJoin(original, changedApplication, r.binding));
  const other = registration('other-client', 2), otherPhase = application(other).phase;
  assert.throws(() => verifyDeliveryReceiptJoin(original, otherPhase, other.binding));
});
test('offline join checks exact serialized bytes, request digest and content-bound public response, not a receipt-only claim', () => {
  const f = fixture();
  for (const changed of [{ ...f.phase, serializedApplicationRequest: f.phase.serializedApplicationRequest + ' ' },
    { ...f.phase, requestDigest: S2 }, { ...f.phase, verifiedPublicResponseText: 'marker-only' },
    { ...f.phase, publicResponseDigest: S2 }, { ...f.phase, status: 'rejected' }]) {
    assert.throws(() => verifyDeliveryReceiptJoin(f.capture.snapshot(), changed, r.binding));
  }
});
test('capture limits reject extra pending requests and row/event growth without silently dropping confirmations', () => {
  const proof = application(), capture = new DeliveryReceiptCapture(); capture.bind(r.binding); captureEvents(capture, proof.events);
  const pending = Array.from({ length: 16 }, () => capture.beforeRequest('review.events', { credential: r.client })!);
  assert.equal(capture.snapshot().complete, false);
  assert.throws(() => capture.beforeRequest('review.events', { credential: r.client }), { code: 'delivery_capture_pending_bound' });
  pending.forEach(ticket => ticket.rejected());
  for (let i = 0; i < 1_024; i++) ack(capture, 'event-1', proof.ackReceipts[0]!);
  const full = capture.snapshot(); assert.equal(full.rows.length, 1_024); assert.equal(full.complete, true);
  assert.throws(() => ack(capture, 'event-1', proof.ackReceipts[0]!));
  assert.equal(capture.snapshot().rows.length, 1_024); assert.equal(capture.snapshot().complete, false); assert.equal(full.complete, true);
  const events = new DeliveryReceiptCapture(); events.bind(r.binding);
  for (let offset = 0; offset < 256; offset += 100) captureEvents(events, Array.from({ length: Math.min(100, 256 - offset) }, (_, index) => publicEvent(r, 'event-' + (offset + index), offset + index + 1)));
  assert.equal(events.snapshot().capturedEvents, 256);
  assert.throws(() => captureEvents(events, [publicEvent(r, 'overflow-event', 257)])); assert.equal(events.snapshot().capturedEvents, 256);
});
test('invalid IDs, duplicate request sets and non-application observation identities fail before awaiting transport', () => {
  const proof = application(), capture = new DeliveryReceiptCapture(); capture.bind(r.binding);
  for (const fields of [{ eventIds: ['event-1', 'event-1'], receipt: proof.ackReceipts[0] },
    { eventIds: ['private/path'], receipt: proof.ackReceipts[0] }, { eventIds: ['event-1'], receipt: { ...proof.ackReceipts[0], insertedAt: 'private unstructured text' } }]) {
    assert.throws(() => capture.beforeRequest('review.ack', { credential: r.adapter, ...fields }));
  }
  assert.throws(() => capture.beforeRequest('review.observe', { credential: r.adapter, eventIds: ['event-1'],
    receipt: { ...proof.observation, modelRequestId: 'internal-inference-id-is-not-an-app-request' } }));
  assert.equal(capture.snapshot().successfulConfirmationCalls, 0);
});
