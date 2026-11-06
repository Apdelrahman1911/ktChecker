/** SYNTHETIC runner controls only: same real runner/NEW local journal with explicitly fake
 * owner/host evidence. No real factory, JVM, provider, model, delivery or qualification claim. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canonical, digest, HostError } from '../src/journal.js';
import { OBSERVED_GAP } from './current-image-acceptance-evidence.js';
import { OriginalReviewObservationClock } from './current-image-acceptance-runner.js';
import { assertNoActualClaim, assertSyntheticFailure } from './current-image-acceptance-adversarial-fixtures.js';
import { CONTROL_COMPILER_RULES, controlFixture, type FixtureValue, type SyntheticParent } from './current-image-acceptance-fixtures.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
function synchronizeSyntheticCompletion(parent: SyntheticParent): void {
  for (const event of parent.events) {
    const payload = JSON.parse(event.payload) as FixtureValue;
    if (event.kind === 'result') Object.assign(payload, parent.completion.children.find(child => child.reviewId === event.reviewId));
    if (event.kind === 'plan_resolved' || event.kind === 'plan_unavailable') payload.parentPlan = structuredClone(parent.completion.parent);
    event.payload = canonical(payload); event.payloadDigest = digest(event.payload);
  }
}
test('runner closes exact handle/original300 only after exact durable joined evidence write and all summary checks', linuxOnly, async t => {
  const f = await controlFixture(t), observed: object[] = [];
  f.adapter.deliveryHook = (handle, review) => {
    const index = observed.length, row = f.evidence.rows.at(-1); assert.equal(row?.stage, 'private_joined_delivery');
    const joined = row!.value as FixtureValue;
    assert.equal(joined.summary.ordinal, index + 1);
    assert.equal(joined.summary.allChildComputationsEvidenced, true); assert.equal(joined.summary.exact14CompilerExecutionsEvidenced, true);
    assert.equal(joined.summary.expectedFindingChangesMatched, true); assert.equal(joined.summary.originalEventJoin.requiredEventIds.length, 6);
    assert.equal(review, f.adapter.clockScopes[index]!.reviewObservation); assert.equal(f.adapter.clockScopes[index]!.overall, f.clock);
    review.checkpoint(); assert.equal(f.adapter.verifiedDeliveries.length, index);
    assert(Object.values(f.journal.read().inbox).every(entry => !entry.event.deliveryRequired || entry.acknowledged && entry.observed && !entry.stale));
    observed.push(handle);
  };
  const report = await f.run(); assertNoActualClaim(report); assert.equal(report.status, 'verified_delivery_final_denied');
  assert.equal(observed.length, 2); assert.notEqual(observed[0], observed[1]); assert.equal(f.adapter.verifiedDeliveries.length, 2);
  for (const [index, value] of f.adapter.verifiedDeliveries.entries()) {
    assert.equal(value.handle, observed[index]); assert.equal(value.review, f.adapter.clockScopes[index]!.reviewObservation);
  }
  assert.notEqual(f.adapter.verifiedDeliveries[0]!.review, f.adapter.verifiedDeliveries[1]!.review);
  assert.equal(f.adapter.calls.filter(method => method === 'owner.deliveryVerified').length, 2);
  assert.equal(f.adapter.calls.filter(method => method === 'gate.complete').length, 1);
});
test('missing mandatory delivery hook fails before any capture/submission/model', linuxOnly, async t => {
  const f = await controlFixture(t); Object.defineProperty(f.adapter, 'deliveryVerified', { value: undefined });
  const report = await f.run(); assertSyntheticFailure(report); assert.equal(report.failure, 'current_acceptance_delivery_clock_hook_required');
  assert.equal(f.adapter.parents.length, 0); assert.equal(f.host.requests.length, 0); assert.equal(f.adapter.calls.length, 0);
});
for (const failure of ['throwing evidence write', 'evidence write reaches original300'] as const)
  test('no delivery closure after ' + failure, linuxOnly, async t => {
    const f = await controlFixture(t);
    f.evidence.hook = stage => {
      if (stage === 'private_joined_delivery') {
        if (failure === 'throwing evidence write') throw new HostError('synthetic_private_evidence_write_unknown');
        f.clock.now = 300_000;
      }
    };
    const report = await f.run(); assertSyntheticFailure(report);
    assert.equal(f.adapter.calls.includes('owner.deliveryVerified'), false); assert.equal(f.adapter.verifiedDeliveries.length, 0);
    assert.equal(f.adapter.parents.length, 1); assert.equal(f.host.requests.length, 1); assert.equal(f.adapter.calls.includes('gate.prepare'), false);
  });
for (const failure of ['async hook', 'nonvoid hook', 'throwing hook', 'hook consumes remaining300'] as const)
  test(failure + ' cannot start another patch or final, even after full synthetic evidence', linuxOnly, async t => {
    const f = await controlFixture(t);
    f.adapter.deliveryHook = () => {
      if (failure === 'async hook') return Promise.resolve();
      if (failure === 'nonvoid hook') return true;
      if (failure === 'throwing hook') throw new HostError('synthetic_delivery_hook_failed');
      f.clock.now = 300_000;
    };
    const report = await f.run(); assertSyntheticFailure(report);
    assert.equal(f.adapter.calls.filter(method => method === 'owner.deliveryVerified').length, 1);
    assert.equal(f.adapter.verifiedDeliveries.length, 0); assert.equal(f.adapter.parents.length, 1); assert.equal(f.host.requests.length, 1);
    assert.equal(f.adapter.calls.includes('gate.prepare'), false); assert.equal(f.adapter.calls.includes('gate.complete'), false);
    if (failure === 'async hook' || failure === 'nonvoid hook') assert.equal(report.failure, 'current_acceptance_delivery_clock_hook_not_synchronous');
    const calls = [...f.adapter.calls]; assert.throws(() => f.run(), /components_previously_attempted/); assert.deepEqual(f.adapter.calls, calls);
  });
for (const failure of ['wrong handle', 'equal-duration replacement review', 'duplicate local closure', 'expired after local closure'] as const)
  test('runner does not adopt ' + failure + ' as a successful delivery', linuxOnly, async t => {
    const f = await controlFixture(t), original = f.adapter.deliveryVerified.bind(f.adapter);
    f.adapter.deliveryVerified = (handle, review) => {
      if (failure === 'wrong handle') original({}, review);
      else if (failure === 'equal-duration replacement review') original(handle, new OriginalReviewObservationClock(f.clock));
      else {
        original(handle, review);
        if (failure === 'duplicate local closure') original(handle, review);
        else f.clock.now = 300_000;
      }
    };
    const report = await f.run(); assertSyntheticFailure(report); assert.equal(f.adapter.parents.length, 1); assert.equal(f.host.requests.length, 1);
    assert.equal(f.adapter.calls.includes('gate.prepare'), false); assert.equal(f.adapter.calls.includes('gate.complete'), false);
  });
for (const failure of ['unavailable child', 'non-exact14 compiler executions', 'expected finding missing'] as const)
  test('joined delivery with ' + failure + ' does not close original300 or permit another patch', linuxOnly, async t => {
    const f = await controlFixture(t);
    f.adapter.admissionHook = parent => {
      const completion = parent.completion as unknown as FixtureValue;
      if (failure === 'unavailable child') {
        completion.children[1].result = null; completion.children[1].reasons = ['produced_plan_preview_unqualified', OBSERVED_GAP, 'synthetic_explicit_unavailable'];
        completion.parent.slots[1].state = 'unavailable'; completion.parent.slots[1].reasons = completion.children[1].reasons;
        const index = parent.events.findIndex(event => event.kind === 'result' && event.reviewId === completion.children[1].reviewId);
        assert(index >= 0); parent.events.splice(index, 1); parent.events.forEach((event, ordinal) => { event.sequence = ordinal + 1; });
      } else if (failure === 'non-exact14 compiler executions') {
        const extra = (completion.children[0].result.coverage as FixtureValue[]).find(row => row.ruleId.startsWith('ktc.compiler.') && !CONTROL_COMPILER_RULES.includes(row.ruleId));
        assert(extra); extra.provenance = 'executed';
      } else completion.children[1].result.findings = [];
      synchronizeSyntheticCompletion(parent);
    };
    const report = await f.run(); assertSyntheticFailure(report); assert.equal(report.failure, 'current_acceptance_patch_review_partial_or_expected_fix_not_observed');
    assert(f.evidence.rows.some(row => row.stage === 'private_joined_delivery'));
    assert.equal(f.adapter.calls.includes('owner.deliveryVerified'), false); assert.equal(f.adapter.parents.length, 1); assert.equal(f.host.requests.length, 1);
  });
test('durable ACK/observation join failure is not bypassed by a present delivery hook', linuxOnly, async t => {
  const f = await controlFixture(t);
  f.host.evidenceHook = phase => {
    f.journal.edit(state => { const entry = Object.values(state.inbox)[0]; assert(entry); entry.acknowledged = false; }); return phase;
  };
  const report = await f.run(); assertSyntheticFailure(report); assert.equal(f.adapter.verifiedDeliveries.length, 0);
  assert.equal(f.adapter.calls.includes('owner.deliveryVerified'), false); assert(!f.evidence.rows.some(row => row.stage === 'private_joined_delivery'));
});
test('second delivery-hook failure retains first exact closure but forbids the final barrier', linuxOnly, async t => {
  const f = await controlFixture(t);
  f.adapter.deliveryHook = () => { if (f.adapter.parents.length === 2) throw new HostError('synthetic_second_delivery_hook_failed'); };
  const report = await f.run(); assertSyntheticFailure(report); assert.equal(f.adapter.verifiedDeliveries.length, 1);
  assert.equal(f.adapter.verifiedDeliveries[0]!.review, f.adapter.clockScopes[0]!.reviewObservation);
  assert.equal(f.adapter.parents.length, 2); assert.equal(f.host.requests.length, 2); assert.equal(report.finalBarrier, 'not_attempted');
  assert.equal(f.adapter.calls.includes('gate.prepare'), false); assert.equal(f.adapter.calls.includes('gate.complete'), false);
});
