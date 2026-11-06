/** SYNTHETIC callback DATA routing only. NEW test journal/fake owner/host; never a launch,
 * JVM, compiler, provider or model. No returned public binding issues any owner authority. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canonical } from '../src/journal.js';
import type { ClientBinding } from '../src/types.js';
import { runSyntheticCurrentImageAcceptanceControl, type CompletedPatchCapture } from './current-image-acceptance-runner.js';
import { assertNoActualClaim, assertSyntheticFailure } from './current-image-acceptance-adversarial-fixtures.js';
import { controlFixture, controlPatch } from './current-image-acceptance-fixtures.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
const bindingKeys = ['clientId', 'generation', 'lineageId', 'ownerId', 'sessionId', 'workspaceId', 'worktreeId'];

test('capture receives fresh frozen seven-field public binding for both synthetic patches with no credential or component reference', linuxOnly, async t => {
  const f = await controlFixture(t), captured: Readonly<ClientBinding>[] = [];
  const callback: CompletedPatchCapture = async (ordinal, previous, binding) => {
    assert.equal(ordinal, captured.length + 1); assert.equal(previous === null, ordinal === 1);
    assert.deepEqual(Object.keys(binding).sort(), bindingKeys); assert.equal(Reflect.ownKeys(binding).length, 7);
    assert.deepEqual(binding, f.registration.binding); assert.notEqual(binding, f.registration.binding);
    assert(Object.isFrozen(binding)); assert.equal(Reflect.set(binding, 'ownerId', 'synthetic-forged-owner'), false);
    assert.equal(Reflect.deleteProperty(binding, 'ownerId'), false);
    assert.throws(() => Object.setPrototypeOf(binding, {}), TypeError);
    const text = canonical(binding);
    assert(!text.includes(f.registration.client.token)); assert(!text.includes(f.registration.adapter.token));
    assert(!('clock' in binding)); assert(!('broker' in binding)); assert(!('owner' in binding)); assert(!('take' in binding));
    if (previous !== null) {
      assert.deepEqual(previous.binding, binding); assert.equal(previous.snapshotId, f.adapter.parents[0]!.lineage.snapshotId);
      assert.equal(f.adapter.verifiedDeliveries.length, 1);
    }
    captured.push(binding);
    return { ...controlPatch(f.registration, ordinal, previous), binding: { ...binding } };
  };
  const report = await runSyntheticCurrentImageAcceptanceControl(f.options, callback);
  assertNoActualClaim(report); assert.equal(report.status, 'verified_delivery_final_denied');
  assert.equal(captured.length, 2); assert.notEqual(captured[0], captured[1]);
  assert.deepEqual(captured[0], captured[1]); assert.equal(f.host.requests.length, 2);
});

test('an existing two-argument capture remains source-compatible and ignores the additive DATA argument', linuxOnly, async t => {
  const f = await controlFixture(t); let captures = 0;
  const callback: Parameters<typeof f.run>[1] = async (ordinal, previous) => {
    captures++; return controlPatch(f.registration, ordinal, previous);
  };
  assert.equal(callback.length, 2);
  const report = await f.run({}, callback); assertNoActualClaim(report);
  assert.equal(report.status, 'verified_delivery_final_denied'); assert.equal(captures, 2); assert.equal(f.host.requests.length, 2);
});

for (const field of ['ownerId', 'workspaceId', 'worktreeId', 'clientId', 'lineageId', 'generation', 'sessionId'] as const)
  test('capture cannot substitute public ' + field + ' in the returned patch', linuxOnly, async t => {
    const f = await controlFixture(t); let captures = 0;
    const report = await runSyntheticCurrentImageAcceptanceControl(f.options, async (ordinal, previous, binding) => {
      captures++; const changed = { ...binding, [field]: field === 'generation' ? 2 : 'synthetic-different-' + field };
      return { ...controlPatch(f.registration, ordinal, previous), binding: changed as ClientBinding };
    });
    assertSyntheticFailure(report); assert.equal(report.failure, 'current_acceptance_completed_patch_client');
    assert.equal(captures, 1); assert.equal(f.adapter.parents.length, 0); assert.equal(f.host.requests.length, 0);
  });

test('mutating retained registration during capture is rejected even if the returned patch copies that mutation', linuxOnly, async t => {
  const f = await controlFixture(t); let captures = 0;
  const report = await runSyntheticCurrentImageAcceptanceControl(f.options, async (ordinal, previous, binding) => {
    captures++; assert.equal(binding.ownerId, f.registration.binding.ownerId);
    f.registration.binding.ownerId = 'synthetic-mutated-retained-owner';
    return controlPatch(f.registration, ordinal, previous);
  });
  assertSyntheticFailure(report); assert.equal(report.failure, 'current_acceptance_owner_binding_changed');
  assert.equal(captures, 1); assert.equal(f.adapter.parents.length, 0); assert.equal(f.host.requests.length, 0);
});

test('first public capture DATA does not permit replacement components or a second synthetic attempt after failure', linuxOnly, async t => {
  const f = await controlFixture(t); let captures = 0;
  const callback: CompletedPatchCapture = async () => { captures++; throw new Error('synthetic lost capture return'); };
  const report = await runSyntheticCurrentImageAcceptanceControl(f.options, callback); assertSyntheticFailure(report);
  assert.equal(report.unknownOperation, 'capture_completed_mutation'); assert.equal(captures, 1);
  assert.throws(() => runSyntheticCurrentImageAcceptanceControl(f.options, callback), /components_previously_attempted/);
  assert.equal(captures, 1); assert.equal(f.adapter.parents.length, 0); assert.equal(f.host.requests.length, 0);
});
