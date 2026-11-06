/** SYNTHETIC monotonic controls of the real original clock ledger, never an execution grant. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { RealModelClock, isOriginalRealModelClock } from './real-model-run-support.js';
import { OriginalReviewObservationClock, isOriginalReviewObservationClockFor } from './current-image-acceptance-runner.js';
import { captureOriginalOwnerEntry, isOwnerSessionClocks, OwnerSessionClocks } from './current-image-owner-clocks.js';
import { syntheticAdmitClock, syntheticChallenge, syntheticOpenLedger, syntheticOwnerClocks, syntheticSubmitClock,
  SYNTHETIC_OWNER_SESSION } from './current-image-owner-control-fixtures.js';

test('owner replies use floored actual original900 and pre-open original120, never requested budgets', t => {
  const f = syntheticOwnerClocks(t); f.clocks.bindSession(SYNTHETIC_OWNER_SESSION); f.clocks.beginRequest(1, 'owner.open');
  f.advanceNanos(1_234_567n);
  assert.equal(f.clocks.replyToChallenge(syntheticChallenge(1, 'overall', 0, 1)).remainingMillis, 899_998);
  f.advance(1_000);
  assert.equal(f.clocks.replyToChallenge(syntheticChallenge(1, 'export', 1, 2)).remainingMillis, 118_998);
});
test('first submit keeps the original pre-open120 rather than creating another120', t => {
  const f = syntheticOwnerClocks(t); f.advance(60_000); syntheticOpenLedger(f.clocks); f.advance(59_999);
  syntheticSubmitClock(f.overall, f.clocks, 1); assert.equal(f.clocks.remainingMs(), 1);
  f.advance(1); assert.throws(() => f.clocks.checkpoint(), /original_export_deadline/);
});
test('wire return does not close export120 before actual adapter admission', t => {
  const f = syntheticOwnerClocks(t); syntheticOpenLedger(f.clocks); syntheticSubmitClock(f.overall, f.clocks, 1);
  f.clocks.beginRequest(2, 'owner.submitCompletedPatch'); f.clocks.replyToChallenge(syntheticChallenge(2, 'review', 1, 3));
  f.clocks.noteResponse(2); f.clocks.finishRequest(2); f.advance(120_000);
  assert.throws(() => f.clocks.admitSubmission({}), /original_export_deadline/);
});
test('active original300 continues after admission and constrains broker/status boundaries', t => {
  const f = syntheticOwnerClocks(t); syntheticOpenLedger(f.clocks); syntheticSubmitClock(f.overall, f.clocks, 1);
  syntheticAdmitClock(f.clocks, 1); f.advance(299_999); assert.equal(f.clocks.remainingMs(), 1); f.advance(1);
  assert.throws(() => f.clocks.beginRequest(3, 'review.events'), /original_review_observation_deadline/);
  assert.throws(() => f.clocks.beginRequest(3, 'owner.status'), /original_review_observation_deadline/);
});
test('exact synchronous delivery close stops only old wait caps; old status and later own120 use no expired old clock', t => {
  const f = syntheticOwnerClocks(t); syntheticOpenLedger(f.clocks);
  const first = syntheticSubmitClock(f.overall, f.clocks, 1), handle = syntheticAdmitClock(f.clocks, 1);
  f.advance(1_000); f.clocks.deliveryVerified(handle, first); f.advance(300_001);
  assert.throws(() => first.checkpoint(), /original_review_observation_deadline/); // Object is retained and still expired, not renewed.
  f.clocks.beginRequest(3, 'owner.status'); f.clocks.noteResponse(3); f.clocks.finishRequest(3);
  const second = syntheticSubmitClock(f.overall, f.clocks, 2); assert.notEqual(second, first); f.advance(2_500);
  f.clocks.beginRequest(4, 'owner.submitCompletedPatch');
  assert.equal(f.clocks.replyToChallenge(syntheticChallenge(4, 'review', 2, 4)).remainingMillis, 297_500);
  assert.equal(f.clocks.replyToChallenge(syntheticChallenge(4, 'export', 2, 5)).remainingMillis, 117_500);
});
test('original overall900 never resets when review/export scopes close', t => {
  const f = syntheticOwnerClocks(t); syntheticOpenLedger(f.clocks);
  const review = syntheticSubmitClock(f.overall, f.clocks, 1), handle = syntheticAdmitClock(f.clocks, 1);
  f.clocks.deliveryVerified(handle, review); f.advance(900_000);
  assert.throws(() => f.clocks.beginRequest(3, 'gate.prepare'), /real_model_original_observation_deadline/);
});
for (const [name, mutation] of [
  ['foreign session', (value: Record<string, unknown>) => { value.session = 'b'.repeat(64); }],
  ['foreign request', (value: Record<string, unknown>) => { value.requestId = 2; }],
  ['unknown phase', (value: Record<string, unknown>) => { value.phase = 'renew'; }],
  ['out-of-order phase', (value: Record<string, unknown>) => { value.phase = 'export'; value.ordinal = 1; }],
  ['wrong ordinal', (value: Record<string, unknown>) => { value.ordinal = 1; }],
  ['session as nonce', (value: Record<string, unknown>) => { value.challenge = SYNTHETIC_OWNER_SESSION; }],
  ['uppercase nonce', (value: Record<string, unknown>) => { value.challenge = 'B'.repeat(64); }],
  ['requested renewed budget', (value: Record<string, unknown>) => { value.remainingMillis = 900_000; }],
] as const) test('owner original-clock challenge rejects ' + name + ' permanently', t => {
  const f = syntheticOwnerClocks(t); f.clocks.bindSession(SYNTHETIC_OWNER_SESSION); f.clocks.beginRequest(1, 'owner.open');
  const frame = syntheticChallenge(1, 'overall', 0, 1); mutation(frame);
  assert.throws(() => f.clocks.replyToChallenge(frame), /current_owner_/);
  assert.throws(() => f.clocks.replyToChallenge(syntheticChallenge(1, 'overall', 0, 1)), /current_owner_/);
});
test('consumed original challenge nonce cannot be reused for another expected phase', t => {
  const f = syntheticOwnerClocks(t); f.clocks.bindSession(SYNTHETIC_OWNER_SESSION); f.clocks.beginRequest(1, 'owner.open');
  f.clocks.replyToChallenge(syntheticChallenge(1, 'overall', 0, 1));
  assert.throws(() => f.clocks.replyToChallenge(syntheticChallenge(1, 'export', 1, 1)), /unknown_or_replayed/);
});
test('response cannot omit any original challenge or be adopted twice', t => {
  const f = syntheticOwnerClocks(t); f.clocks.bindSession(SYNTHETIC_OWNER_SESSION); f.clocks.beginRequest(1, 'owner.open');
  f.clocks.replyToChallenge(syntheticChallenge(1, 'overall', 0, 1));
  assert.throws(() => f.clocks.noteResponse(1), /response_before_original_clocks_or_duplicate/);
  assert.throws(() => f.clocks.adoptOpen(), /response_before_original_clocks_or_duplicate/);
});
test('overall/review runtime registries reject prototypes, proxies, subclasses and frozen numeric imitations before getters', t => {
  const f = syntheticOwnerClocks(t); let getters = 0;
  const proxy = new Proxy(f.overall, { get() { getters++; throw new Error('must not read proxy'); } });
  const fake = Object.create(RealModelClock.prototype) as RealModelClock;
  assert.equal(isOriginalRealModelClock(fake), false); assert.equal(isOriginalRealModelClock(proxy), false);
  assert.throws(() => new OwnerSessionClocks(proxy, captureOriginalOwnerEntry()), /original_overall_required/);
  assert.throws(() => new OwnerSessionClocks(fake, captureOriginalOwnerEntry()), /original_overall_required/);
  assert.equal(getters, 0); assert.equal(isOwnerSessionClocks(Object.create(OwnerSessionClocks.prototype)), false);
  class FakeOverall extends RealModelClock {}
  class FakeReview extends OriginalReviewObservationClock {}
  assert.throws(() => new FakeOverall(), /real_model_original_clock_type/);
  assert.throws(() => new FakeReview(f.overall), /original_review_clock_type/);
  const review = new OriginalReviewObservationClock(f.overall);
  assert.equal(isOriginalReviewObservationClockFor(review, f.overall), true);
  assert.equal(isOriginalReviewObservationClockFor(new Proxy(review, {}), f.overall), false);
  assert.equal(isOriginalReviewObservationClockFor(Object.create(OriginalReviewObservationClock.prototype), f.overall), false);
  for (const value of [f.overall, review, RealModelClock, RealModelClock.prototype, OriginalReviewObservationClock,
    OriginalReviewObservationClock.prototype, OwnerSessionClocks, OwnerSessionClocks.prototype]) assert(Object.isFrozen(value));
  assert.throws(() => Object.defineProperty(f.overall, 'originalDeadlineNanos', { value: 999_999_999_999_999n }));
  assert.throws(() => Object.defineProperty(review, 'remainingMs', { value: () => 300_000 }));
});
for (const name of ['replacement overall', 'foreign review', 'copied review', 'new duration'] as const)
  test('submit denies ' + name + ' instead of authorizing with equal numbers', t => {
    const f = syntheticOwnerClocks(t); syntheticOpenLedger(f.clocks); const other = new RealModelClock();
    const review = new OriginalReviewObservationClock(name === 'foreign review' ? other : f.overall);
    assert.throws(() => f.clocks.beginSubmission(captureOriginalOwnerEntry(), 1, {
      overall: name === 'replacement overall' ? other : f.overall,
      reviewObservation: name === 'copied review' ? Object.create(OriginalReviewObservationClock.prototype) as OriginalReviewObservationClock : review,
      originalExportMillis: (name === 'new duration' ? 120_001 : 120_000) as 120_000,
    }), /original_review_scope_required/);
  });
for (const name of ['unknown handle', 'wrong review', 'duplicate closure', 'expired review', 'before admission'] as const)
  test('deliveryVerified denies ' + name + ' and does not permit another patch', t => {
    const f = syntheticOwnerClocks(t); syntheticOpenLedger(f.clocks);
    const review = syntheticSubmitClock(f.overall, f.clocks, 1), handle = name === 'before admission' ? {} : syntheticAdmitClock(f.clocks, 1);
    if (name === 'duplicate closure') f.clocks.deliveryVerified(handle, review);
    if (name === 'expired review') f.advance(300_000);
    assert.throws(() => f.clocks.deliveryVerified(name === 'unknown handle' ? {} : handle,
      name === 'wrong review' ? new OriginalReviewObservationClock(f.overall) : review), /current_|original_/);
    assert.throws(() => syntheticSubmitClock(f.overall, f.clocks, 2), /current_|original_/);
  });
test('old closed review object cannot be reused for a later new patch', t => {
  const f = syntheticOwnerClocks(t); syntheticOpenLedger(f.clocks);
  const review = syntheticSubmitClock(f.overall, f.clocks, 1), handle = syntheticAdmitClock(f.clocks, 1);
  f.clocks.deliveryVerified(handle, review);
  assert.throws(() => f.clocks.beginSubmission(captureOriginalOwnerEntry(), 2,
    { overall: f.overall, reviewObservation: review, originalExportMillis: 120_000 }), /original_review_scope_required/);
});
