/** SYNTHETIC capture controls only. No compiler, owner, native host or model is invoked. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest } from '../src/journal.js';
import { prepareDiagnosticContext } from '../src/context-projection.js';
import { expectedPublicResponse } from '../src/codex-observation.js';
import type { ModelRequest, ReviewEvent } from '../src/types.js';
import { DeliveryReceiptCapture, deliveryBinding, verifyDeliveryReceiptJoin, type ProducedRuleVectorProfile } from './delivery-receipts.js';
import { CURRENT_RULE_IDS, syntheticRuleVector } from './required-rule-vector-fixtures.js';
import { binding, registration, S1 } from './fixtures.js';
import { REAL_MODEL_CONTEXT_BYTES, REAL_MODEL_PUBLIC_BYTES, REAL_MODEL_RPC_BYTES } from './real-model-run-support.js';

const r = registration(), b = binding(r);
const profiles = ['historical-full1663', 'current-full1665'] as const;
const gap = 'configuration_input_closure_unproven';
function notification(kind: 'plan_accepted' | 'plan_resolved' | 'plan_unavailable', observed = false,
  profile: ProducedRuleVectorProfile = 'current-full1665'): ReviewEvent {
  const rules = syntheticRuleVector(profile);
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
function feed(c: DeliveryReceiptCapture, event: ReviewEvent): void {
  c.beforeRequest('review.events', { credential: r.client })!.returned({ contractVersion: PREVIEW_CONTRACT,
    events: [event], cursor: 'synthetic-cursor', hasMore: false });
}
function capture(event: ReviewEvent, observed = false, profile: ProducedRuleVectorProfile = 'current-full1665'): DeliveryReceiptCapture {
  const c = new DeliveryReceiptCapture({ expectedProducedAssurance: observed ? 'ktchecker/observed-export-one-shot/1' : 'legacy-strict',
    expectedProducedRuleVector: profile }); c.bind(r.binding); feed(c, event); return c;
}

for (const profile of profiles) for (const observed of [false, true]) for (const kind of ['plan_accepted', 'plan_resolved', 'plan_unavailable'] as const) {
  if (!observed && kind === 'plan_accepted') continue; // Generic legacy acceptance remains outside this narrow helper.
  test(`${profile} ${observed ? 'observed' : 'legacy strict'} ${kind} joins ACK → actual-shaped application → public response → observation`, () => {
    const event = notification(kind, observed, profile), c = capture(event, observed, profile), item = prepareDiagnosticContext(event, b, S1);
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
    assert.equal(observation.parentPlan.slots[0].requiredRuleIds.length, profile === 'current-full1665' ? 1665 : 1663);
    assert.deepEqual(observation.parentPlan.slots[0].requiredRuleIds, syntheticRuleVector(profile));
    assert.deepEqual(observation.parentPlan.reasons, ['produced_plan_preview_unqualified', ...(observed ? [gap] : [])]);
  });
}

test('new produced-plan kinds require the full typed two-slot payload, never a complete claim or a narrowed/scalar event', () => {
  const mutations: ((value: any, rules: readonly string[]) => void)[] = [
    value => { delete value.parentPlan; }, value => { value.parentPlan.slots.pop(); },
    value => { value.parentPlan.slots.reverse(); }, value => { value.parentPlan.slots[1].reviewId = 'another-review'; },
    value => { value.parentPlan.clientId = 'another-client'; }, value => { value.parentPlan.sourceSnapshotId = digest('other'); },
    value => { value.parentPlan.state = 'complete'; }, value => { value.parentPlan.slots[1].requiredRuleIds = ['narrowed.rule']; },
    value => { value.parentPlan.state = 'pending'; },
    value => { for (const slot of value.parentPlan.slots) slot.requiredRuleIds = ['narrowed.rule']; },
    (value, rules) => { for (const slot of value.parentPlan.slots) slot.requiredRuleIds = [...rules.slice(1), 'zzz.changed-rule'].sort(); },
    value => { value.parentPlan.slots[0].requiredRuleIds = ['synthetic.rule', 'synthetic.rule']; },
    value => { value.parentPlan.slots[0].context.target = 'jvm'; },
    value => { value.parentPlan.slots[0].state = 'complete'; }, value => { value.cancelled = true; },
    value => { value.parentPlan.reasons = []; },
    value => { delete value.parentPlan.slots[0].context; }, value => { value.result = { status: 'complete' }; },
    value => { value.reviewId = 'other-anchor'; }, value => { value.state = 'result'; },
  ];
  for (const profile of profiles) for (const mutate of mutations) {
    const event = notification('plan_resolved', false, profile), value = JSON.parse(event.payload); mutate(value, syntheticRuleVector(profile));
    event.payload = canonical(value); event.payloadDigest = digest(event.payload);
    assert.throws(() => capture(event, false, profile), { code: 'delivery_capture_confirmation_rejected' });
  }
  const optional = notification('plan_unavailable'); optional.deliveryRequired = false;
  assert.throws(() => capture(optional), { code: 'delivery_capture_confirmation_rejected' });
});

// Capture has its own event-shape boundary: exercise each slot and assurance/kind,
// not merely the fixed validators or an admission check that might reject earlier.
for (const profile of profiles) test(`${profile} capture rejects malformed, missing, extra and equal-size wrong vectors in either slot`, () => {
  const changes: readonly [string, (rules: readonly string[]) => unknown][] = [
    ['missing field', () => undefined], ['null', () => null], ['object', () => ({})],
    ['missing ID', rules => rules.slice(1)], ['extra ID', rules => [...rules, 'zzz.synthetic.extra_rule'].sort()],
    ['equal-size wrong ID', rules => [...rules.slice(1), 'zzz.synthetic.wrong_rule'].sort()],
    ['equal-size duplicate ID', rules => [...rules.slice(1), rules[1]!].sort()],
    ['reordered IDs', rules => [...rules].reverse()], ['non-string member', rules => [null, ...rules.slice(1)]],
  ];
  for (const index of [0, 1]) for (const observed of [false, true]) for (const kind of ['plan_accepted', 'plan_resolved', 'plan_unavailable'] as const) {
    if (!observed && kind === 'plan_accepted') continue; // Preserve the unchanged generic legacy acceptance path.
    for (const [name, change] of changes) {
      const event = notification(kind, observed, profile), value = JSON.parse(event.payload), rules = change(syntheticRuleVector(profile));
      if (rules === undefined) delete value.parentPlan.slots[index].requiredRuleIds;
      else value.parentPlan.slots[index].requiredRuleIds = rules;
      event.payload = canonical(value); event.payloadDigest = digest(event.payload);
      assert.throws(() => capture(event, observed, profile), { code: 'delivery_capture_confirmation_rejected' },
        `${kind} ${observed ? 'observed' : 'strict'} slot${index}: ${name}`);
    }
  }
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
  for (const profile of profiles) for (const kind of ['plan_accepted', 'plan_resolved', 'plan_unavailable'] as const) for (const mutate of mutations) {
    const event = notification(kind, true, profile), value = JSON.parse(event.payload); mutate(value);
    event.payload = canonical(value); event.payloadDigest = digest(event.payload);
    assert.throws(() => capture(event, true, profile), { code: 'delivery_capture_confirmation_rejected' });
  }
  // A catalog selection never changes assurance requirements, in either direction.
  for (const profile of profiles) {
    assert.throws(() => capture(notification('plan_resolved', false, profile), true, profile), { code: 'delivery_capture_confirmation_rejected' });
    assert.throws(() => capture(notification('plan_resolved', true, profile), false, profile), { code: 'delivery_capture_confirmation_rejected' });
  }
});

test('observed cancelled parent keeps the ordered closure gap and original cancelled anchor', () => {
  for (const profile of profiles) {
    const event = notification('plan_unavailable', true, profile), value = JSON.parse(event.payload);
    value.cancelled = true; value.parentPlan.state = 'cancelled'; value.parentPlan.slots[1].state = 'cancelled';
    value.parentPlan.reasons.push('parent_cancelled_obligations_retained');
    event.payload = canonical(value); event.payloadDigest = digest(event.payload);
    assert.doesNotThrow(() => capture(event, true, profile));
    value.reasons.push('parent_cancelled_obligations_retained');
    event.payload = canonical(value); event.payloadDigest = digest(event.payload);
    assert.throws(() => capture(event, true, profile), { code: 'delivery_capture_confirmation_rejected' });
  }
});

for (const selected of profiles) test(`${selected} rejects the other catalog instead of inferring it from event size or assurance`, () => {
  const other = selected === 'current-full1665' ? 'historical-full1663' : 'current-full1665';
  for (const observed of [false, true]) for (const kind of ['plan_resolved', 'plan_unavailable'] as const) {
    assert.throws(() => capture(notification(kind, observed, other), observed, selected), { code: 'delivery_capture_confirmation_rejected' });
  }
  assert.throws(() => capture(notification('plan_accepted', true, other), true, selected), { code: 'delivery_capture_confirmation_rejected' });
});
test('omitted catalog selection preserves exact historical1663 in both strict and explicitly observed capture', () => {
  for (const observed of [false, true]) {
    const c = new DeliveryReceiptCapture(observed ? { expectedProducedAssurance: 'ktchecker/observed-export-one-shot/1' } : {});
    c.bind(r.binding); feed(c, notification('plan_resolved', observed, 'historical-full1663'));
    const before = c.snapshot(); assert.equal(before.complete, true); assert.equal(before.capturedEvents, 1);
    assert.throws(() => feed(c, notification('plan_unavailable', observed, 'current-full1665')), { code: 'delivery_capture_confirmation_rejected' });
    const after = c.snapshot(); assert.equal(after.complete, false); assert.equal(after.capturedEvents, before.capturedEvents);
    assert.deepEqual(after.rows, before.rows); assert.equal(after.successfulConfirmationCalls, before.successfulConfirmationCalls);
    assert.throws(() => feed(c, notification('plan_resolved', observed, 'historical-full1663')), { code: 'delivery_capture_failed' });
  }
});
test('catalog choice is read once before event requests, not read from a later mutated options object', () => {
  const options = { expectedProducedAssurance: 'ktchecker/observed-export-one-shot/1' as const,
    expectedProducedRuleVector: 'current-full1665' as ProducedRuleVectorProfile };
  const c = new DeliveryReceiptCapture(options); c.bind(r.binding); options.expectedProducedRuleVector = 'historical-full1663';
  feed(c, notification('plan_resolved', true, 'current-full1665')); assert.equal(c.snapshot().complete, true);
  assert.equal(c.snapshot().capturedEvents, 1); assert.equal(CURRENT_RULE_IDS.length, 1665);
  let reads = 0;
  const selected = new DeliveryReceiptCapture({ expectedProducedAssurance: 'ktchecker/observed-export-one-shot/1',
    get expectedProducedRuleVector(): ProducedRuleVectorProfile { return ++reads === 1 ? 'current-full1665' : 'historical-full1663'; } });
  assert.equal(reads, 1); selected.bind(r.binding); feed(selected, notification('plan_resolved', true, 'current-full1665'));
  assert.equal(selected.snapshot().complete, true); assert.equal(reads, 1);
});
