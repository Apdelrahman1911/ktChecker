import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonical, digest } from '../src/journal.js';
import type { JournalState } from '../src/types.js';
import { binding, event, registration } from './fixtures.js';
import { captureDeliveryTelemetry, permanentDeliveryProblem, safeDeliveryProblem } from './delivery-telemetry.js';
import { OwnedDemoThreads } from './owned-demo-threads.js';

test('failure telemetry measures exact oversized fields without leaking their content or identifiers', () => {
  const r = registration(), e = event(r, 'secret-event-identity');
  const secret = 'PRIVATE_SENTINEL_do_not_output';
  const payload = JSON.parse(e.payload); payload.reasons = [secret + 'x'.repeat(40_000)];
  e.payload = canonical(payload); e.payloadDigest = digest(e.payload);
  const state: JournalState = { version: 1, binding: binding(r), fetchCursor: secret, inbox: {
    [e.eventId]: { event: e, item: null, insertionReceiptId: null, inserted: null, acknowledged: false, observation: null, observed: false, stale: false },
  }, submissions: {}, currentSnapshotId: null, localEpoch: 0, cut: null, lastProblem: 'required_context_exceeds_budget' };
  const telemetry = captureDeliveryTelemetry(state), serialized = JSON.stringify(telemetry);
  assert(!serialized.includes(secret)); assert(!serialized.includes(e.eventId)); assert(!serialized.includes(r.client.token));
  assert.equal(permanentDeliveryProblem(state), 'required_context_exceeds_budget');
  const entry = (telemetry.events as any[])[0];
  assert.equal(entry.fieldSizes.topLevelReasons.bytes, Buffer.byteLength(canonical(payload.reasons)));
  assert.equal(entry.fieldSizes.topLevelReasons.entries, 1); assert.equal(entry.preparedContextExceedsDefaultBudget, true);
  assert.equal(entry.savedContextBytes, null); assert.equal(entry.acknowledged, false); assert.equal(entry.observed, false);
  state.lastProblem = secret;
  assert.equal(safeDeliveryProblem(secret), 'unclassified_delivery_problem'); assert.equal(safeDeliveryProblem(null), null);
  assert.equal(permanentDeliveryProblem(state), null); assert(!JSON.stringify(captureDeliveryTelemetry(state)).includes(secret));
});

test('failed demonstration cleanup deletes only confirmed owned IDs once and retains ambiguous creation or deletion outcomes', async () => {
  const owned = new OwnedDemoThreads(); owned.beginCreation(); owned.confirmCreation('owned-a');
  owned.beginCreation(); owned.confirmCreation('owned-b'); owned.beginCreation();
  const calls: unknown[] = [];
  const result = await owned.cleanup(() => ({
    async connect() { calls.push('connect'); },
    async request(method, params) { calls.push({ method, params }); if (params.threadId === 'owned-b') throw new Error('unknown'); return {}; },
    async close() { calls.push('close'); },
  }));
  assert.deepEqual(calls, ['connect', { method: 'thread/delete', params: { threadId: 'owned-a' } },
    { method: 'thread/delete', params: { threadId: 'owned-b' } }, 'close']);
  assert.equal(result.confirmedDeleted, 1); assert.equal(result.deletionUnconfirmed, 1);
  assert.equal(result.unknownCreationOutcomes, 1); assert.equal(result.allConfirmed, false);
});

test('demonstration cleanup needs no process for zero creations and confirms success only from successful deletes', async () => {
  const empty = await new OwnedDemoThreads().cleanup(() => { throw new Error('must_not_connect'); });
  assert.equal(empty.allConfirmed, true); assert.equal(empty.knownOwnedThreads, 0);
  const owned = new OwnedDemoThreads(); owned.beginCreation(); owned.confirmCreation('owned-only');
  const result = await owned.cleanup(() => ({ async connect() {}, async request() { return {}; }, async close() {} }));
  assert.equal(result.allConfirmed, true); assert.equal(result.confirmedDeleted, 1);
});
