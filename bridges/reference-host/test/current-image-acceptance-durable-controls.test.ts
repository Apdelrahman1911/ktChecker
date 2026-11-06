/** SYNTHETIC R4 durable-state regressions. No candidate executable, launch ticket,
 * owner/provider/compiler or model. New fake-journal fixture only; offline mutations
 * cannot establish an initial live injection bypass or historical-state attestation.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonical, digest } from '../src/journal.js';
import type { JournalState } from '../src/types.js';
import { deliveryIdentity, verifyDeliveryReceiptJoin } from './delivery-receipts.js';
import { REAL_MODEL_CONTEXT_BYTES, REAL_MODEL_PUBLIC_BYTES, REAL_MODEL_RPC_BYTES } from './real-model-run-support.js';
import { freezeCompletedPatch, requiredContexts, verifyOriginalContextJoin, verifyParentAdmission,
  type BoundParent, type ParentCompletionData } from './current-image-acceptance-evidence.js';
import { CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE, controlFixture, type FixtureValue } from './current-image-acceptance-fixtures.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
interface SyntheticDelivery { parent: BoundParent; row: FixtureValue; state: JournalState }
async function capture(t: { after(fn: () => Promise<unknown> | void): void }) {
  const fixture = await controlFixture(t), deliveries: SyntheticDelivery[] = [];
  fixture.evidence.hook = (stage, value) => {
    if (stage !== 'private_joined_delivery') return;
    const actual = fixture.adapter.parents.at(-1)!;
    const parent = verifyParentAdmission(freezeCompletedPatch(actual.patch), actual.data, deliveries.at(-1)?.parent ?? null,
      CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE);
    deliveries.push({ parent, row: structuredClone(value) as FixtureValue, state: fixture.journal.read() });
  };
  const report = await fixture.run();
  assert.equal(report.origin, 'synthetic-control'); assert.equal(report.actualHostDeliveryVerified, false); assert.equal(report.launch, null);
  assert.equal(report.lifecycleDisposition, 'retained_no_quiescence_claim'); assert.equal(report.status, 'verified_delivery_final_denied');
  assert.equal(report.failure, null);
  assert.deepEqual([report.qualified, report.usefulClean, report.fullProject, report.actionPerformed, report.internalInferenceRequestTrace],
    [false, false, false, false, false]);
  assert.equal(deliveries.length, 2); assert.equal(fixture.host.requests.length, 2);
  return { fixture, deliveries };
}
function verify(delivery: SyntheticDelivery, state: JournalState = delivery.state, row: FixtureValue = delivery.row) {
  return verifyOriginalContextJoin(delivery.parent, row.completion as ParentCompletionData, state,
    row.originalEvents, row.phase, row.deliveryReceipts, []);
}
function generic(delivery: SyntheticDelivery, row: FixtureValue = delivery.row) {
  return verifyDeliveryReceiptJoin(row.deliveryReceipts, row.phase, delivery.state.binding.client,
    { maxApplicationBytes: REAL_MODEL_RPC_BYTES, maxContextBytes: REAL_MODEL_CONTEXT_BYTES, maxPublicBytes: REAL_MODEL_PUBLIC_BYTES });
}
function currentEntries(delivery: SyntheticDelivery, state: JournalState): FixtureValue[] {
  return Object.values(state.inbox).filter(entry => entry.event.sourceSnapshotId === delivery.parent.snapshot.snapshotId &&
    entry.event.deliveryRequired) as unknown as FixtureValue[];
}

test('R4 synthetic current durable-only substitutions fail despite unchanged valid application and RPC hashes', linuxOnly, async t => {
  const run = await capture(t), first = run.deliveries[0]!, current = run.deliveries[1]!;
  const firstEntry = currentEntries(first, first.state)[0]!, originalEntry = currentEntries(current, current.state)[0]!;
  const originalState = canonical(current.state), originalPublic = canonical(current.row);
  const fields: readonly [string, string, (entry: FixtureValue) => void][] = [
    ['missing inserted marker', 'durable_insertion_binding', entry => { delete entry.inserted.marker; }],
    ['foreign inserted marker', 'durable_insertion_binding', entry => { entry.inserted.marker += ':synthetic-other'; }],
    ['historical inserted marker', 'durable_insertion_binding', entry => { entry.inserted.marker = firstEntry.inserted.marker; }],
    ['missing inserted thread', 'durable_insertion_binding', entry => { delete entry.inserted.hostThreadId; }],
    ['foreign inserted thread', 'durable_insertion_binding', entry => { entry.inserted.hostThreadId = 'synthetic-other-thread'; }],
    ['missing inserted generation', 'durable_insertion_binding', entry => { delete entry.inserted.generation; }],
    ['changed inserted generation', 'durable_insertion_binding', entry => { entry.inserted.generation++; }],
    ['missing inserted receipt context digest', 'durable_insertion_receipt', entry => { delete entry.inserted.receipt.contextDigest; }],
    ['wrong inserted receipt context digest', 'durable_insertion_receipt', entry => { entry.inserted.receipt.contextDigest = digest('synthetic-other-context'); }],
    ['historical inserted receipt digest', 'durable_insertion_receipt', entry => { entry.inserted.receipt.contextDigest = firstEntry.inserted.receipt.contextDigest; }],
    ['missing insertion intent', 'durable_insertion_receipt', entry => { delete entry.insertionReceiptId; }],
    ['null insertion intent', 'durable_insertion_receipt', entry => { entry.insertionReceiptId = null; }],
    ['wrong insertion intent', 'durable_insertion_receipt', entry => { entry.insertionReceiptId = 'synthetic-other-receipt'; }],
    ['historical insertion intent', 'durable_insertion_receipt', entry => { entry.insertionReceiptId = firstEntry.insertionReceiptId; }],
    ['missing durable observation vector', 'durable_observation_contexts', entry => { delete entry.observation.contextDigests; }],
    ['empty durable observation vector', 'durable_observation_contexts', entry => { entry.observation.contextDigests = []; }],
    ['missing member in durable observation vector', 'durable_observation_contexts', entry => { entry.observation.contextDigests.pop(); }],
    ['duplicate durable observation member', 'durable_observation_contexts', entry => { entry.observation.contextDigests.push(entry.observation.contextDigests[0]); entry.observation.contextDigests.sort(); }],
    ['wrong durable observation member', 'durable_observation_contexts', entry => { entry.observation.contextDigests[0] = digest('synthetic-other-context'); entry.observation.contextDigests.sort(); }],
    ['historical durable observation vector', 'durable_observation_contexts', entry => { entry.observation.contextDigests = structuredClone(firstEntry.observation.contextDigests); }],
    ['unsorted durable observation vector', 'durable_observation_contexts', entry => { entry.observation.contextDigests.reverse(); }],
    ['current stale true', 'current_delivery_stale', entry => { entry.stale = true; }],
    ['missing current stale flag', 'current_delivery_stale', entry => { delete entry.stale; }],
    ['string current stale false', 'current_delivery_stale', entry => { entry.stale = 'false'; }],
    ['truthy nonboolean acknowledged', 'durable_receipts_not_confirmed', entry => { entry.acknowledged = 'true'; }],
    ['truthy nonboolean observed', 'durable_receipts_not_confirmed', entry => { entry.observed = 1; }],
  ];
  assert.equal(originalEntry.observation.contextDigests.length, 6);
  assert.deepEqual(originalEntry.observation.contextDigests, [...originalEntry.observation.contextDigests].sort());
  assert.equal(verify(current).requiredEventIds.length, 6);
  for (const [name, code, mutate] of fields) await t.test(name, () => {
    const changed = structuredClone(current.state); mutate(currentEntries(current, changed)[0]!);
    // Durable JSON has no independent signature/checksum. Round-trip and recompute its
    // public digest anyway: the rejection must be the join, not stale application hashes.
    const serialized = canonical(changed), reparsed = JSON.parse(serialized) as JournalState;
    assert.notEqual(digest(serialized), digest(originalState)); assert.equal(digest(canonical(reparsed)), digest(serialized));
    assert.equal(canonical(current.row), originalPublic); assert.equal(generic(current).joinedEvents, 6);
    assert.throws(() => verify(current, reparsed), new RegExp('current_acceptance_' + code));
  });
  await t.test('coherent replacement of every current durable vector cannot choose a smaller observed context set', () => {
    const changed = structuredClone(current.state), replacement = originalEntry.observation.contextDigests.slice(1);
    for (const entry of currentEntries(current, changed)) entry.observation.contextDigests = structuredClone(replacement);
    assert.equal(generic(current).joinedEvents, 6);
    assert.throws(() => verify(current, JSON.parse(canonical(changed)) as JournalState), /current_acceptance_durable_observation_contexts/);
  });
  await t.test('current stale state is denied already by the before-insertion preparation fence', () => {
    const changed = structuredClone(current.state); currentEntries(current, changed)[0]!.stale = true;
    assert.throws(() => requiredContexts(current.parent, current.row.completion as ParentCompletionData, changed, current.row.originalEvents, []),
      /current_acceptance_current_delivery_stale/);
  });
  assert.equal(canonical(current.state), originalState); assert.equal(canonical(current.row), originalPublic);
  assert.equal(run.fixture.host.requests.length, 2); // Offline mutations caused no further even-fake inference.
});

test('R4 synthetic durable join checks later current confirmations but preserves separately identical repeats', linuxOnly, async t => {
  const run = await capture(t), current = run.deliveries[1]!, originalState = canonical(current.state), originalRows = canonical(current.row);
  function repeat(revised: boolean): FixtureValue {
    const row = structuredClone(current.row), snapshot = row.deliveryReceipts as FixtureValue;
    const selected = (snapshot.rows as FixtureValue[]).filter(value => value.method === 'review.observe' &&
      value.event.sourceSnapshotId === current.parent.snapshot.snapshotId);
    const order = ++snapshot.successfulConfirmationCalls;
    for (const original of selected) {
      const duplicate = structuredClone(original);
      duplicate.confirmationOrder = order;
      duplicate.eventOccurrence = (snapshot.rows as FixtureValue[]).filter(old => old.event.eventIdDigest === duplicate.event.eventIdDigest).length + 1;
      if (revised) duplicate.observation.observationIdDigest = deliveryIdentity('observation', 'synthetic-later-revised-observation');
      duplicate.requestProjectionDigest = digest(canonical({ method: duplicate.method, clientIdDigest: duplicate.binding.clientIdDigest,
        eventIdDigests: duplicate.response.eventIdDigests, receipt: duplicate.observation }));
      duplicate.responseProjectionDigest = digest(canonical(duplicate.response)); snapshot.rows.push(duplicate);
    }
    return row;
  }
  const identical = repeat(false);
  assert.equal(generic(current, identical).observationConfirmationOrders.length, 2);
  assert.equal(verify(current, current.state, identical).requiredEventIds.length, 6);
  const revised = repeat(true);
  assert.equal(generic(current, revised).observationConfirmationOrders.length, 2);
  assert.throws(() => verify(current, current.state, revised), /current_acceptance_durable_observation_receipt/);
  assert.equal(canonical(current.state), originalState); assert.equal(canonical(current.row), originalRows);
  assert.equal(run.fixture.host.requests.length, 2);
});
