/** Explicitly SYNTHETIC, pure full1663 join controls. None of these values grants live custody,
 * native/compiler execution, actual model observation or release qualification. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest } from '../src/journal.js';
import { prepareDiagnosticContext } from '../src/context-projection.js';
import { expectedPublicResponse } from '../src/codex-observation.js';
import type { InboxEntry, JournalState, ModelRequest, ReviewEvent } from '../src/types.js';
import { binding, registration, S1, S2 } from './fixtures.js';
import { acceptedRealPhase, completeRealPhase, fullRealRules, publicRealText, REAL_MODEL_RULE, validateRealDelivery } from './real-model-delivery.js';
import { DeliveryReceiptCapture, deliveryBinding, verifyDeliveryReceiptJoin } from './delivery-receipts.js';
import { REAL_MODEL_CONTEXT_BYTES, REAL_MODEL_PUBLIC_BYTES, REAL_MODEL_RPC_BYTES } from './real-model-run-support.js';

const ids = ['core', 'libraries', 'platform', 'ecosystem'].flatMap(name =>
  (JSON.parse(readFileSync(new URL('../../../../data/rules-' + name + '.json', import.meta.url), 'utf8')) as { id: string }[]).map(value => value.id)).sort();

function fixture() {
  const r = registration(), b = binding(r);
  const accepted = { contractVersion: PREVIEW_CONTRACT, planId: 'synthetic-plan', clientId: r.binding.clientId,
    patchId: 'no-test-feasibility-patch-1', sourceSnapshotId: S1, planDigest: digest('synthetic-plan-only'), state: 'queued', reasons: [],
    slots: [{ slotId: 'synthetic-slot', reviewId: 'synthetic-review', context: { platform: 'jvm' }, requiredRuleIds: ids, state: 'queued', reasons: [] }] };
  const phase = acceptedRealPhase({ state: 'parent_accepted', phase: 1, parent: accepted,
    timing: { phase: 1, originalDeadlineMillis: 300_000, originalDeadlineIdentity: 'synthetic-original-clock' }, qualified: false }, 1, r, ids, 0, 1);
  const result = { status: 'partial', coverage: ids.map(ruleId => ({ ruleId, required: true, qualified: false, certificateId: null,
    state: 'unavailable', provenance: ruleId === REAL_MODEL_RULE ? 'executed' : 'unavailable' })),
    findings: [{ ruleId: REAL_MODEL_RULE, upstreamId: 'UNRESOLVED_REFERENCE', diagnosis: 'Unresolved reference declaredAcrossFiles.',
      location: { path: 'src/main/kotlin/Use.kt', line: 3, column: 33 }, verification: [] }], rawDiagnostics: [], reasons: [] };
  const child = { contractVersion: PREVIEW_CONTRACT, clientId: r.binding.clientId, reviewId: phase.reviewId, sourceSnapshotId: S1,
    state: 'partial', result, reasons: [] };
  const completion = { state: 'phase_verified_unqualified', phase: 1, qualified: false, usefulClean: false, terminal: true, phaseVerified: true,
    parent: { ...accepted, state: 'partial' }, children: [child] };
  assert(completeRealPhase(completion, phase, r, ids, 2));
  const values = [
    { kind: 'accepted', payload: { ...child, state: 'accepted', result: null } },
    { kind: 'plan_accepted', payload: { ...child, state: 'plan_accepted', result: null, parentPlan: accepted } },
    { kind: 'progress', payload: { ...child, state: 'running', result: null } },
    { kind: 'result', payload: child },
  ];
  const events: ReviewEvent[] = values.map((value, index) => {
    const payload = canonical(value.payload);
    return { contractVersion: PREVIEW_CONTRACT, eventId: 'synthetic-event-' + index, clientId: r.binding.clientId, sequence: index + 1,
      reviewId: phase.reviewId, patchId: 'synthetic-child-patch', sourceSnapshotId: S1, generation: r.binding.generation,
      kind: value.kind, deliveryRequired: true, payload, payloadDigest: digest(payload) };
  });
  const inbox: Record<string, InboxEntry> = Object.fromEntries(events.map(event => [event.eventId, { event,
    item: null, inserted: null, insertionReceiptId: null, acknowledged: false, observation: null, observed: false, stale: false }]));
  const state: JournalState = { version: 1, binding: b, fetchCursor: null, inbox, submissions: {}, currentSnapshotId: S1,
    localEpoch: 1, cut: null, lastProblem: null };
  return { r, b, phase, accepted, completion, events, state };
}

test('all FOUR catalog inputs bind the exact sorted1663 identity vector, never a count-only prefix', () => {
  assert.equal(fullRealRules(ids).length, 1663);
  for (const changed of [ids.slice(0, -1), [...ids].reverse(), [...ids.slice(0, -1), ids[0]!], [...ids.slice(0, -1), 'synthetic-new-rule']])
    assert.throws(() => fullRealRules(changed));
});
test('whole required completion requires full1663 coverage and an executed but unqualified compiler row', () => {
  const f = fixture();
  const shortened = structuredClone(f.completion); shortened.children[0]!.result.coverage.pop();
  assert.throws(() => completeRealPhase(shortened, f.phase, f.r, ids, 3), /real_model_required_result_vector_changed/);
  const qualified = structuredClone(f.completion); qualified.children[0]!.result.coverage.find(value => value.ruleId === REAL_MODEL_RULE)!.qualified = true;
  assert.throws(() => completeRealPhase(qualified, f.phase, f.r, ids, 3), /real_model_unqualified_compiler_execution_missing/);
  const complete = structuredClone(f.completion); complete.parent.state = 'complete';
  assert.throws(() => completeRealPhase(complete, f.phase, f.r, ids, 3), /real_model_unqualified_parent_claimed_complete/);
});
test('required-delivery validation retains progress, whole parent and terminal event without filtering', () => {
  const f = fixture(), all = validateRealDelivery(f.state, f.phase, []);
  assert.deepEqual(all.map(entry => entry.event.kind), ['accepted', 'plan_accepted', 'progress', 'result']);
  delete f.state.inbox['synthetic-event-1'];
  assert.throws(() => validateRealDelivery(f.state, f.phase, []), /real_model_required_event_missing/);
});
test('required parent event cannot narrow a full vector or switch source even with a recomputed payload digest', () => {
  const f = fixture(), parent = f.state.inbox['synthetic-event-1']!;
  const value = JSON.parse(parent.event.payload); value.parentPlan.slots[0].requiredRuleIds.pop();
  parent.event.payload = canonical(value); parent.event.payloadDigest = digest(parent.event.payload);
  assert.throws(() => validateRealDelivery(f.state, f.phase, []), /real_model_required_parent_event_changed/);
  const g = fixture(); g.state.inbox['synthetic-event-3']!.event.sourceSnapshotId = S2;
  assert.throws(() => validateRealDelivery(g.state, g.phase, []), /real_model_required_delivery_domain_changed/);
});
test('model-facing public-data check rejects owner paths and credentials, never redacts or truncates to pass', () => {
  assert.doesNotThrow(() => publicRealText('Unresolved reference in src/main/kotlin/Use.kt.', []));
  for (const text of ['/root/private/file', '/tmp/another-owner', 'synthetic-secret-token'])
    assert.throws(() => publicRealText(text, ['synthetic-secret-token']), /real_model_private_data_in_model_context/);
});

function joined() {
  const f = fixture(), receipts = new DeliveryReceiptCapture(); receipts.bind(f.r.binding);
  receipts.beforeRequest('review.events', { credential: f.r.client, cursor: null, limit: 100 })!.returned({
    contractVersion: PREVIEW_CONTRACT, events: f.events, cursor: 'synthetic-cursor', hasMore: false });
  const contexts = f.events.map(event => prepareDiagnosticContext(event, f.b, S1)).sort((a, b) => a.marker.localeCompare(b.marker));
  const request: ModelRequest = { invocationId: 'synthetic-invocation', hostThreadId: f.b.hostThreadId, generation: f.b.client.generation, contexts,
    requestDigest: digest(canonical({ hostThreadId: f.b.hostThreadId, generation: f.b.client.generation, contexts })) };
  for (const [index, event] of f.events.entries()) {
    const context = contexts.find(value => value.marker.startsWith('ktchecker-event:' + event.eventId + ':'))!;
    receipts.beforeRequest('review.ack', { credential: f.r.adapter, eventIds: [event.eventId], receipt: {
      receiptId: 'synthetic-ack-' + index, contextItemId: 'synthetic-item-' + index, contextDigest: context.digest, insertedAt: '2026-11-05T00:00:00.000Z',
    } })!.returned({ contractVersion: PREVIEW_CONTRACT, clientId: f.r.binding.clientId, eventIds: [event.eventId], state: 'acknowledged' });
  }
  const applicationId = 'synthetic-app-request', eventIds = f.events.map(event => event.eventId);
  receipts.beforeRequest('review.observe', { credential: f.r.adapter, eventIds, receipt: {
    observationId: 'synthetic-observation', modelRequestId: 'codex-app-request:' + applicationId, contextDigests: contexts.map(value => value.digest).sort(),
  } })!.returned({ contractVersion: PREVIEW_CONTRACT, clientId: f.r.binding.clientId, eventIds, state: 'model_observed' });
  const output = canonical({ contractVersion: PREVIEW_CONTRACT, invocationId: request.invocationId, requestDigest: request.requestDigest,
    contexts: contexts.map(value => ({ contextDigest: value.digest, text: value.text })) });
  const serializedApplicationRequest = JSON.stringify({ id: applicationId, method: 'turn/start', params: {
    threadId: f.b.hostThreadId, input: [], toolOutput: { name: 'ktchecker', output }, effort: 'max',
  } });
  const verifiedPublicResponseText = canonical(expectedPublicResponse(request));
  const proof = { consumer: f.r.binding.clientId, deliveryBinding: deliveryBinding(f.r.binding), status: 'verified', internalInferenceRequestTrace: false,
    model: 'gpt-6-astra', effort: 'max', contextDigests: contexts.map(value => value.digest), invocationId: request.invocationId, requestDigest: request.requestDigest,
    applicationRequest: { applicationRequestId: applicationId, method: 'turn/start', serializedRequestDigest: digest(serializedApplicationRequest) },
    serializedApplicationRequest, verifiedPublicResponseText, publicResponseDigest: digest(verifiedPublicResponseText) };
  return { ...f, receipts: receipts.snapshot(), contexts, proof };
}
const limits = { maxApplicationBytes: REAL_MODEL_RPC_BYTES, maxContextBytes: REAL_MODEL_CONTEXT_BYTES, maxPublicBytes: REAL_MODEL_PUBLIC_BYTES };

test('explicit approved bounds join full1663 parent and every required context; historical small bounds still reject', () => {
  const f = joined();
  assert(f.contexts.some(value => Buffer.byteLength(value.text) > 32_768));
  assert.throws(() => verifyDeliveryReceiptJoin(f.receipts, f.proof, f.r.binding));
  assert.equal(verifyDeliveryReceiptJoin(f.receipts, f.proof, f.r.binding, limits).joinedEvents, 4);
  const response = JSON.parse(f.proof.verifiedPublicResponseText);
  assert.deepEqual(response.observations.find((value: { parentPlan?: unknown }) => value.parentPlan).parentPlan, f.accepted);
});
test('whole-context receipt join rejects effort mismatch, invalid bounds and changed public parent content', () => {
  const f = joined();
  assert.throws(() => verifyDeliveryReceiptJoin(f.receipts, { ...f.proof, effort: 'low' }, f.r.binding, limits), /delivery_join_effort/);
  for (const bad of [{ ...limits, maxContextBytes: Number.NaN }, { ...limits, maxApplicationBytes: REAL_MODEL_RPC_BYTES + 1 },
    { ...limits, maxPublicBytes: REAL_MODEL_PUBLIC_BYTES + 1 }])
    assert.throws(() => verifyDeliveryReceiptJoin(f.receipts, f.proof, f.r.binding, bad), /delivery_join_limits/);
  const response = JSON.parse(f.proof.verifiedPublicResponseText);
  response.observations.find((value: { parentPlan?: unknown }) => value.parentPlan).parentPlan.slots[0].requiredRuleIds.pop();
  const text = canonical(response);
  assert.throws(() => verifyDeliveryReceiptJoin(f.receipts, { ...f.proof, verifiedPublicResponseText: text,
    publicResponseDigest: digest(text) }, f.r.binding, limits), /delivery_join_public_response/);
});
