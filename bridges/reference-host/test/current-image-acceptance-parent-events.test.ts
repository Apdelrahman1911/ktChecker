/** SYNTHETIC capture controls only. No compiler, owner, native host or model is invoked. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest } from '../src/journal.js';
import { prepareDiagnosticContext } from '../src/context-projection.js';
import { expectedPublicResponse } from '../src/codex-observation.js';
import type { ModelRequest, ReviewEvent } from '../src/types.js';
import { DeliveryReceiptCapture, deliveryBinding, verifyDeliveryReceiptJoin } from './delivery-receipts.js';
import { binding, registration, S1 } from './fixtures.js';
import { REAL_MODEL_CONTEXT_BYTES, REAL_MODEL_PUBLIC_BYTES, REAL_MODEL_RPC_BYTES } from './real-model-run-support.js';

const r = registration(), b = binding(r);
const rules = ['core', 'libraries', 'platform', 'ecosystem'].flatMap(name =>
  (JSON.parse(readFileSync(new URL('../../../../data/rules-' + name + '.json', import.meta.url), 'utf8')) as { id: string }[]).map(row => row.id)).sort();
const gap = 'configuration_input_closure_unproven';
function notification(kind: 'plan_accepted' | 'plan_resolved' | 'plan_unavailable', observed = false): ReviewEvent {
  const parent = { contractVersion: PREVIEW_CONTRACT, planId: 'synthetic-plan', clientId: r.binding.clientId,
    patchId: 'synthetic-patch', sourceSnapshotId: S1, planDigest: digest('synthetic-declaration'), state: kind === 'plan_accepted' ? 'pending' : 'incomplete',
    reasons: ['produced_plan_preview_unqualified', ...(observed ? [gap] : [])], slots: ['main', 'test'].map(name => ({ slotId: name,
      reviewId: 'synthetic-review-' + name, context: { target: '', module: ':fixture', sourceSet: name, compilation: name },
      requiredRuleIds: rules, state: kind === 'plan_accepted' ? (name === 'main' ? 'queued' : 'deferred') :
        name === 'test' && kind === 'plan_unavailable' ? 'unavailable' : 'incomplete',
      reasons: ['verified_compilation_context_unavailable', ...(observed ? [gap] : [])] })) };
  const payload = canonical({ contractVersion: PREVIEW_CONTRACT, reviewId: 'synthetic-review-test', clientId: r.binding.clientId,
    patchId: 'synthetic-child-patch', sequence: 2, sourceSnapshotId: S1, state: kind, result: null, reasons: observed ? [gap] : [],
    cancelled: false, parentPlan: parent });
  return { contractVersion: PREVIEW_CONTRACT, eventId: 'synthetic-event-' + kind, clientId: r.binding.clientId,
    generation: r.binding.generation, sequence: 1, reviewId: 'synthetic-review-test', patchId: 'synthetic-child-patch',
    sourceSnapshotId: S1, kind, deliveryRequired: true, payload, payloadDigest: digest(payload) };
}
function capture(event: ReviewEvent, observed = false): DeliveryReceiptCapture {
  const c = new DeliveryReceiptCapture({ expectedProducedAssurance: observed ? 'ktchecker/observed-export-one-shot/1' : 'legacy-strict' }); c.bind(r.binding);
  c.beforeRequest('review.events', { credential: r.client })!.returned({ contractVersion: PREVIEW_CONTRACT,
    events: [event], cursor: 'synthetic-cursor', hasMore: false });
  return c;
}

for (const observed of [false, true]) for (const kind of ['plan_accepted', 'plan_resolved', 'plan_unavailable'] as const) {
  if (!observed && kind === 'plan_accepted') continue; // Generic legacy acceptance remains outside this narrow helper.
  test(`${observed ? 'observed' : 'legacy strict'} ${kind} joins ACK → actual-shaped application → public response → observation`, () => {
    const event = notification(kind, observed), c = capture(event, observed), item = prepareDiagnosticContext(event, b, S1);
    const request: ModelRequest = { invocationId: 'synthetic-invocation', hostThreadId: b.hostThreadId,
      generation: r.binding.generation, contexts: [item], requestDigest: digest(canonical({ hostThreadId: b.hostThreadId,
        generation: r.binding.generation, contexts: [item] })) };
    c.beforeRequest('review.ack', { credential: r.adapter, eventIds: [event.eventId], receipt: { receiptId: 'synthetic-ack',
      contextDigest: item.digest, contextItemId: 'synthetic-item', insertedAt: '2026-11-05T00:00:00.000Z' } })!.returned({
      contractVersion: PREVIEW_CONTRACT, clientId: r.binding.clientId, eventIds: [event.eventId], state: 'acknowledged' });
    const applicationRequestId = 'synthetic-app', output = canonical({ contractVersion: PREVIEW_CONTRACT,
      invocationId: request.invocationId, requestDigest: request.requestDigest, contexts: [{ contextDigest: item.digest, text: item.text }] });
    const serializedApplicationRequest = JSON.stringify({ id: applicationRequestId, method: 'turn/start', params: {
      threadId: b.hostThreadId, input: [], toolOutput: { name: 'ktchecker', output }, effort: 'max' } });
    const verifiedPublicResponseText = canonical(expectedPublicResponse(request));
    c.beforeRequest('review.observe', { credential: r.adapter, eventIds: [event.eventId], receipt: { observationId: 'synthetic-observation',
      modelRequestId: 'codex-app-request:' + applicationRequestId, contextDigests: [item.digest] } })!.returned({
      contractVersion: PREVIEW_CONTRACT, clientId: r.binding.clientId, eventIds: [event.eventId], state: 'model_observed' });
    const phase = { consumer: r.binding.clientId, deliveryBinding: deliveryBinding(r.binding), status: 'verified', model: 'gpt-6-astra',
      effort: 'max', internalInferenceRequestTrace: false, invocationId: request.invocationId, requestDigest: request.requestDigest,
      contextDigests: [item.digest], applicationRequest: { applicationRequestId, method: 'turn/start', serializedRequestDigest: digest(serializedApplicationRequest) },
      serializedApplicationRequest, verifiedPublicResponseText, publicResponseDigest: digest(verifiedPublicResponseText) };
    assert.equal(verifyDeliveryReceiptJoin(c.snapshot(), phase, r.binding, { maxApplicationBytes: REAL_MODEL_RPC_BYTES,
      maxContextBytes: REAL_MODEL_CONTEXT_BYTES, maxPublicBytes: REAL_MODEL_PUBLIC_BYTES }).joinedEvents, 1);
    assert.equal(c.snapshot().rows[1]!.event.kind, kind);
    assert.equal(c.snapshot().rows[1]!.event.deliveryRequired, true);
    const observation = JSON.parse(verifiedPublicResponseText).observations[0];
    assert.deepEqual(observation.parentPlan, JSON.parse(event.payload).parentPlan);
    assert.equal(observation.parentPlan.state, kind === 'plan_accepted' ? 'pending' : 'incomplete');
    assert.equal(observation.parentPlan.slots[0].context.target, '');
    assert.equal(observation.parentPlan.slots[0].requiredRuleIds.length, 1663);
    assert.deepEqual(observation.parentPlan.reasons, ['produced_plan_preview_unqualified', ...(observed ? [gap] : [])]);
  });
}

test('new produced-plan kinds require the full typed two-slot payload, never a complete claim or a narrowed/scalar event', () => {
  const mutations: ((value: any) => void)[] = [
    value => { delete value.parentPlan; }, value => { value.parentPlan.slots.pop(); },
    value => { value.parentPlan.slots.reverse(); }, value => { value.parentPlan.slots[1].reviewId = 'another-review'; },
    value => { value.parentPlan.clientId = 'another-client'; }, value => { value.parentPlan.sourceSnapshotId = digest('other'); },
    value => { value.parentPlan.state = 'complete'; }, value => { value.parentPlan.slots[1].requiredRuleIds = ['narrowed.rule']; },
    value => { value.parentPlan.state = 'pending'; },
    value => { for (const slot of value.parentPlan.slots) slot.requiredRuleIds = ['narrowed.rule']; },
    value => { for (const slot of value.parentPlan.slots) slot.requiredRuleIds = [...rules.slice(1), 'zzz.changed-rule'].sort(); },
    value => { value.parentPlan.slots[0].requiredRuleIds = ['synthetic.rule', 'synthetic.rule']; },
    value => { value.parentPlan.slots[0].context.target = 'jvm'; },
    value => { value.parentPlan.slots[0].state = 'complete'; }, value => { value.cancelled = true; },
    value => { value.parentPlan.reasons = []; },
    value => { delete value.parentPlan.slots[0].context; }, value => { value.result = { status: 'complete' }; },
    value => { value.reviewId = 'other-anchor'; }, value => { value.state = 'result'; },
  ];
  for (const mutate of mutations) {
    const event = notification('plan_resolved'), value = JSON.parse(event.payload); mutate(value);
    event.payload = canonical(value); event.payloadDigest = digest(event.payload);
    assert.throws(() => capture(event), { code: 'delivery_capture_confirmation_rejected' });
  }
  const optional = notification('plan_unavailable'); optional.deliveryRequired = false;
  assert.throws(() => capture(optional), { code: 'delivery_capture_confirmation_rejected' });
});

test('unknown plan kinds remain fail-closed rather than becoming a generic accepts-anything escape', () => {
  const event = notification('plan_resolved'); event.kind = 'plan_complete';
  assert.throws(() => capture(event), { code: 'delivery_capture_confirmation_rejected' });
});

test('externally selected observed lane requires parent, anchor and both slot closure gaps, even if every payload marker is stripped', () => {
  const mutations: ((value: any) => void)[] = [
    value => { value.reasons = []; }, value => { value.parentPlan.reasons = ['produced_plan_preview_unqualified']; },
    value => { value.parentPlan.slots[0].reasons = ['verified_compilation_context_unavailable']; },
    value => { value.parentPlan.slots[1].reasons = ['verified_compilation_context_unavailable']; },
    value => { value.reasons = []; value.parentPlan.reasons = ['produced_plan_preview_unqualified'];
      for (const slot of value.parentPlan.slots) slot.reasons = ['verified_compilation_context_unavailable']; },
    value => { value.reasons.push(gap); }, value => { value.parentPlan.reasons.push(gap); },
    value => { value.parentPlan.slots[0].reasons.push(gap); },
    value => { value.reasons = ['other_reason']; }, value => { value.parentPlan.reasons.reverse(); },
  ];
  for (const kind of ['plan_accepted', 'plan_resolved', 'plan_unavailable'] as const) for (const mutate of mutations) {
    const event = notification(kind, true), value = JSON.parse(event.payload); mutate(value);
    event.payload = canonical(value); event.payloadDigest = digest(event.payload);
    assert.throws(() => capture(event, true), { code: 'delivery_capture_confirmation_rejected' });
  }
  // No lane inference/fallback, in either direction.
  assert.throws(() => capture(notification('plan_resolved', false), true), { code: 'delivery_capture_confirmation_rejected' });
  assert.throws(() => capture(notification('plan_resolved', true), false), { code: 'delivery_capture_confirmation_rejected' });
});

test('observed cancelled parent keeps the ordered closure gap and original cancelled anchor', () => {
  const event = notification('plan_unavailable', true), value = JSON.parse(event.payload);
  value.cancelled = true; value.parentPlan.state = 'cancelled'; value.parentPlan.slots[1].state = 'cancelled';
  value.parentPlan.reasons.push('parent_cancelled_obligations_retained');
  event.payload = canonical(value); event.payloadDigest = digest(event.payload);
  assert.doesNotThrow(() => capture(event, true));
  value.reasons.push('parent_cancelled_obligations_retained');
  event.payload = canonical(value); event.payloadDigest = digest(event.payload);
  assert.throws(() => capture(event, true), { code: 'delivery_capture_confirmation_rejected' });
});
