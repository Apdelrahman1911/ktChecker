/** SYNTHETIC offline joins against R3. One fake two-patch transport run supplies each matrix.
 * Recomputed public hashes cannot choose a different current host, patch, receipt or model call.
 * No actual owner, selected executable, compiler/provider or model is invoked by these controls.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonical, digest } from '../src/journal.js';
import { deliveryBinding, deliveryIdentity, verifyDeliveryReceiptJoin } from './delivery-receipts.js';
import { REAL_MODEL_CONTEXT_BYTES, REAL_MODEL_PUBLIC_BYTES, REAL_MODEL_RPC_BYTES } from './real-model-run-support.js';
import { captureSyntheticTwoPatchRun, copySyntheticDelivery, rehashSyntheticApplication, rehashSyntheticReceiptRows,
  regroupSyntheticReceiptRows, verifySyntheticDelivery, type CapturedSyntheticDelivery, type SyntheticDeliveryCopy } from './current-image-acceptance-adversarial-fixtures.js';
import type { FixtureValue } from './current-image-acceptance-fixtures.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
function genericJoin(capture: CapturedSyntheticDelivery, copy: SyntheticDeliveryCopy) {
  return verifyDeliveryReceiptJoin(copy.row.deliveryReceipts, copy.row.phase, capture.state.binding.client,
    { maxApplicationBytes: REAL_MODEL_RPC_BYTES, maxContextBytes: REAL_MODEL_CONTEXT_BYTES, maxPublicBytes: REAL_MODEL_PUBLIC_BYTES });
}
function currentRows(capture: CapturedSyntheticDelivery, copy: SyntheticDeliveryCopy): FixtureValue[] {
  return (copy.row.deliveryReceipts.rows as FixtureValue[]).filter(row => row.event.sourceSnapshotId === capture.parent.snapshot.snapshotId);
}

test('synthetic current host/model/public joins reject missing, wrong, stale and duplicated application data', linuxOnly, async t => {
  const run = await captureSyntheticTwoPatchRun(t), first = run.deliveries[0]!, current = run.deliveries[1]!;
  const originalDigest = digest(canonical({ row: current.row, state: current.state }));
  const mutations: readonly [string, (copy: SyntheticDeliveryCopy) => void][] = [
    ['missing model', copy => { delete copy.row.phase.model; }],
    ['wrong model', copy => { copy.row.phase.model = 'synthetic-unselected-model'; }],
    ['missing effort', copy => { delete copy.row.phase.effort; }],
    ['coherent lower effort', copy => { copy.row.phase.effort = 'low'; rehashSyntheticApplication(copy, app => { app.params.effort = 'low'; }); }],
    ['missing host binding', copy => { delete copy.row.phase.deliveryBinding; }],
    ['foreign host lineage', copy => { copy.row.phase.deliveryBinding = deliveryBinding({ ...current.state.binding.client, lineageId: 'synthetic-foreign-lineage' }); }],
    ['stale host generation', copy => { copy.row.phase.deliveryBinding = deliveryBinding({ ...current.state.binding.client, generation: current.state.binding.client.generation + 1 }); }],
    ['wrong public consumer', copy => { copy.row.phase.consumer = 'synthetic-foreign-client'; }],
    ['missing verified status', copy => { delete copy.row.phase.status; }],
    ['send-only status', copy => { copy.row.phase.status = 'sent'; }],
    ['missing application bytes', copy => { delete copy.row.phase.serializedApplicationRequest; }],
    ['missing application identity', copy => { delete copy.row.phase.applicationRequest.applicationRequestId; }],
    ['wrong application method with valid byte digest', copy => { rehashSyntheticApplication(copy, app => { app.method = 'thread/read'; }); }],
    ['extra user input with valid byte digest', copy => { rehashSyntheticApplication(copy, app => { app.params.input = [{ type: 'text', text: 'SYNTHETIC extra input' }]; }); }],
    ['wrong tool-output name with valid byte digest', copy => { rehashSyntheticApplication(copy, app => { app.params.toolOutput.name = 'synthetic-other-tool'; }); }],
    ['duplicate context with recomputed request/public hashes', copy => { rehashSyntheticApplication(copy, (_app, output) => { output.contexts.push(structuredClone(output.contexts[0])); }); }],
    ['missing model context', copy => { rehashSyntheticApplication(copy, (_app, output) => { output.contexts.pop(); }); }],
    ['stale first-patch public response, rehashed', copy => { copy.row.phase.verifiedPublicResponseText = first.row.phase.verifiedPublicResponseText; copy.row.phase.publicResponseDigest = digest(copy.row.phase.verifiedPublicResponseText); }],
    ['duplicate public observation, rehashed', copy => {
      const response = JSON.parse(copy.row.phase.verifiedPublicResponseText) as FixtureValue;
      response.observations.push(structuredClone(response.observations[0])); copy.row.phase.verifiedPublicResponseText = canonical(response);
      copy.row.phase.publicResponseDigest = digest(copy.row.phase.verifiedPublicResponseText);
    }],
    ['missing public observation, rehashed', copy => {
      const response = JSON.parse(copy.row.phase.verifiedPublicResponseText) as FixtureValue;
      response.observations.pop(); copy.row.phase.verifiedPublicResponseText = canonical(response);
      copy.row.phase.publicResponseDigest = digest(copy.row.phase.verifiedPublicResponseText);
    }],
  ];
  for (const [name, mutate] of mutations) await t.test(name, () => {
    const copy = copySyntheticDelivery(current); mutate(copy); assert.throws(() => verifySyntheticDelivery(current, copy), name);
  });
  await t.test('coherently rehashed foreign thread satisfies generic byte joins but fails independently known host binding', () => {
    const copy = copySyntheticDelivery(current);
    rehashSyntheticApplication(copy, app => { app.params.threadId = 'synthetic-foreign-thread'; });
    assert.equal(genericJoin(current, copy).joinedEvents, 6);
    assert.throws(() => verifySyntheticDelivery(current, copy), /current_acceptance_application_thread/);
  });
  await t.test('complete historical request/public/receipt joins cannot replace the current patch observation', () => {
    const copy = copySyntheticDelivery(current); copy.row.phase = structuredClone(first.row.phase);
    assert.equal(genericJoin(current, copy).joinedEvents, 6);
    assert.throws(() => verifySyntheticDelivery(current, copy), /current_acceptance_original_projection_application_bytes/);
  });
  await t.test('coherently omitted context and its public confirmations still fail the complete original event projection', () => {
    const copy = copySyntheticDelivery(current); let removedEvent = '';
    rehashSyntheticApplication(copy, (_app, output) => {
      const removed = output.contexts.pop() as FixtureValue;
      removedEvent = (JSON.parse(removed.text.slice(removed.text.indexOf('\n') + 1)) as FixtureValue).eventId;
    });
    const removedDigest = deliveryIdentity('event', removedEvent);
    copy.row.deliveryReceipts.rows = (copy.row.deliveryReceipts.rows as FixtureValue[]).filter(row => row.event.eventIdDigest !== removedDigest);
    regroupSyntheticReceiptRows(copy.row.deliveryReceipts);
    // A generic receipt join properly proves only its supplied five contexts, not completeness.
    assert.equal(genericJoin(current, copy).joinedEvents, 5);
    assert.throws(() => verifySyntheticDelivery(current, copy), /current_acceptance_original_projection_application_bytes/);
  });
  assert.equal(digest(canonical({ row: current.row, state: current.state })), originalDigest);
  assert.equal(run.fixture.host.requests.length, 2); // Offline mutations did not ask even the fake host again.
});

test('synthetic ACK/observe joins reject wrong, missing, stale and duplicate confirmations after public rehashing', linuxOnly, async t => {
  const run = await captureSyntheticTwoPatchRun(t), first = run.deliveries[0]!, current = run.deliveries[1]!;
  const mutations: readonly [string, (copy: SyntheticDeliveryCopy) => void][] = [
    ['missing all current ACK rows', copy => { copy.row.deliveryReceipts.rows = (copy.row.deliveryReceipts.rows as FixtureValue[]).filter(row => row.method !== 'review.ack' || row.event.sourceSnapshotId !== current.parent.snapshot.snapshotId); }],
    ['missing all current observation rows', copy => { copy.row.deliveryReceipts.rows = (copy.row.deliveryReceipts.rows as FixtureValue[]).filter(row => row.method !== 'review.observe' || row.event.sourceSnapshotId !== current.parent.snapshot.snapshotId); }],
    ['stale first-patch receipt snapshot', copy => { copy.row.deliveryReceipts = structuredClone(first.row.deliveryReceipts); }],
    ['observation linked to historical ACK order', copy => { for (const row of currentRows(current, copy).filter(row => row.method === 'review.observe')) row.priorAckConfirmationOrder = 1; }],
    ['observation without prior ACK', copy => { currentRows(current, copy).find(row => row.method === 'review.observe')!.priorAckConfirmationOrder = null; }],
    ['missing model-observation projection', copy => { currentRows(current, copy).find(row => row.method === 'review.observe')!.observation = null; }],
    ['foreign full receipt binding', copy => {
      const binding = deliveryBinding({ ...current.state.binding.client, sessionId: 'synthetic-foreign-session' });
      for (const row of currentRows(current, copy)) row.binding = structuredClone(binding);
    }],
    ['stale receipt generation made internally consistent', copy => {
      for (const row of currentRows(current, copy)) { row.binding = { ...row.binding, generation: row.binding.generation + 1 }; row.event = { ...row.event, generation: row.event.generation + 1 }; }
    }],
    ['wrong source snapshot in all current receipt rows', copy => { for (const row of currentRows(current, copy)) row.event = { ...row.event, sourceSnapshotId: first.parent.snapshot.snapshotId }; }],
    ['wrong model request identity', copy => {
      for (const row of currentRows(current, copy).filter(row => row.observation)) row.observation.modelRequestIdDigest = deliveryIdentity('model-request', 'codex-app-request:synthetic-other-application');
    }],
    ['wrong application request identity', copy => {
      for (const row of currentRows(current, copy).filter(row => row.observation)) row.observation.applicationRequestIdDigest = deliveryIdentity('application-request', 'synthetic-other-application');
    }],
    ['duplicate event identity in one response', copy => {
      const row = currentRows(current, copy).find(row => row.method === 'review.observe')!;
      row.response.eventIdDigests = [...row.response.eventIdDigests, row.event.eventIdDigest].sort();
    }],
    ['duplicate row in the same confirmation with coherent occurrence counters', copy => {
      const rows = copy.row.deliveryReceipts.rows as FixtureValue[], row = rows.at(-1)!;
      rows.push(structuredClone(row)); const counts = new Map<string, number>();
      for (const value of rows) { value.eventOccurrence = (counts.get(value.event.eventIdDigest) ?? 0) + 1; counts.set(value.event.eventIdDigest, value.eventOccurrence); }
    }],
  ];
  for (const [name, mutate] of mutations) await t.test(name, () => {
    const copy = copySyntheticDelivery(current); mutate(copy); rehashSyntheticReceiptRows(copy.row.deliveryReceipts);
    assert.throws(() => verifySyntheticDelivery(current, copy), name);
  });
  for (const field of ['receiptIdDigest', 'contextItemIdDigest', 'insertedAtDigest'] as const) await t.test('internally consistent substituted ACK ' + field + ' still fails durable receipt join', () => {
    const copy = copySyntheticDelivery(current), rows = currentRows(current, copy), event = rows[0]!.event.eventIdDigest;
    for (const row of rows.filter(row => row.event.eventIdDigest === event)) row.ack = { ...row.ack, [field]: digest('synthetic-substituted-' + field) };
    rehashSyntheticReceiptRows(copy.row.deliveryReceipts); assert.equal(genericJoin(current, copy).joinedEvents, 6);
    assert.throws(() => verifySyntheticDelivery(current, copy), /current_acceptance_durable_ack_receipt/);
  });
  await t.test('coherent substituted observation identity remains bound to the durable journal', () => {
    const copy = copySyntheticDelivery(current);
    for (const row of currentRows(current, copy).filter(row => row.observation)) row.observation.observationIdDigest = deliveryIdentity('observation', 'synthetic-other-observation');
    rehashSyntheticReceiptRows(copy.row.deliveryReceipts); assert.equal(genericJoin(current, copy).joinedEvents, 6);
    assert.throws(() => verifySyntheticDelivery(current, copy), /current_acceptance_durable_observation_receipt/);
  });
  await t.test('coherent replacement application ID in request and receipt rows cannot replace the durable model request', () => {
    const copy = copySyntheticDelivery(current), applicationId = 'synthetic-replacement-application';
    rehashSyntheticApplication(copy, app => { app.id = applicationId; });
    for (const row of currentRows(current, copy).filter(row => row.observation)) {
      row.observation.applicationRequestIdDigest = deliveryIdentity('application-request', applicationId);
      row.observation.modelRequestIdDigest = deliveryIdentity('model-request', 'codex-app-request:' + applicationId);
    }
    rehashSyntheticReceiptRows(copy.row.deliveryReceipts); assert.equal(genericJoin(current, copy).joinedEvents, 6);
    assert.throws(() => verifySyntheticDelivery(current, copy), /current_acceptance_durable_observation_receipt/);
  });
  await t.test('separately ordered identical confirmation is retained, not mistaken for duplicate authority or a new model call', () => {
    const copy = copySyntheticDelivery(current), snapshot = copy.row.deliveryReceipts as FixtureValue;
    const originals = currentRows(current, copy).filter(row => row.method === 'review.observe');
    const confirmationOrder = ++snapshot.successfulConfirmationCalls;
    for (const row of originals) {
      const duplicate = structuredClone(row), priorCount = (snapshot.rows as FixtureValue[]).filter(old => old.event.eventIdDigest === row.event.eventIdDigest).length;
      duplicate.confirmationOrder = confirmationOrder; duplicate.eventOccurrence = priorCount + 1; snapshot.rows.push(duplicate);
    }
    rehashSyntheticReceiptRows(snapshot);
    assert.equal(genericJoin(current, copy).observationConfirmationOrders.length, 2);
    assert.equal(verifySyntheticDelivery(current, copy).requiredEventIds.length, 6);
    // The generic capture intentionally retains legitimate separate confirmations; only an
    // inconsistent/duplicated same-call row is rejected. No repeated RPC or model was performed.
  });
  assert.equal(run.fixture.host.requests.length, 2);
});
