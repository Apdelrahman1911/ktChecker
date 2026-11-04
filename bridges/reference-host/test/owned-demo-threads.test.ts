import { test } from 'node:test';
import assert from 'node:assert/strict';
import { digest, HostError } from '../src/journal.js';
import { closeDemoConsumer, OwnedDemoThreads, type OwnedThreadCleanupEvidence } from './owned-demo-threads.js';

function owned(...ids: string[]): OwnedDemoThreads {
  const threads = new OwnedDemoThreads();
  for (const id of ids) { threads.beginCreation(); threads.confirmCreation(id); }
  return threads;
}
function successfulPort() { return { async connect() {}, async request() { return {}; }, async close() {} }; }

test('cleanup maps each exact owned ID to its own sanitized result without any discovery or inference method', async () => {
  const ids = ['PRIVATE_THREAD_A_do_not_disclose', 'PRIVATE_THREAD_B_do_not_disclose'];
  const threads = owned(...ids), calls: unknown[] = [];
  const result = await threads.cleanup(() => ({
    async connect() { calls.push('connect'); },
    async request(method, params) {
      calls.push({ method, params });
      if (params.threadId === ids[1]) throw new HostError('codex_request_rejected');
      return {};
    },
    async close() { calls.push('close'); },
  }));
  assert.deepEqual(calls, ['connect', ...ids.map(threadId => ({ method: 'thread/delete', params: { threadId } })), 'close']);
  assert.deepEqual(result.threads, [
    { ownedThreadOrdinal: 1, threadIdDigest: digest(ids[0]!), deletionAttempted: true, outcome: 'confirmed_deleted', reasonCode: null },
    { ownedThreadOrdinal: 2, threadIdDigest: digest(ids[1]!), deletionAttempted: true, outcome: 'rejected', reasonCode: 'codex_request_rejected' },
  ]);
  assert.equal(result.deletionAttempts, 2); assert.equal(result.confirmedDeleted, 1);
  assert.equal(result.deletionUnconfirmed, 1); assert.equal(result.allConfirmed, false);
  assert.equal(result.confirmationScope, 'owned_thread_deletions_only_not_turn_or_native_quiescence');
  for (const id of ids) assert(!JSON.stringify(result).includes(id));
});

test('concurrent, reentrant and later cleanup share one immutable outcome and reject late ownership changes', async () => {
  const threads = owned('owned-a');
  let factories = 0, connections = 0, deletes = 0, closes = 0;
  let release!: () => void;
  const barrier = new Promise<void>(resolve => { release = resolve; });
  let reentrant: Promise<OwnedThreadCleanupEvidence> | undefined;
  const first = threads.cleanup(() => {
    factories++; reentrant = threads.cleanup(() => { throw new Error('must_not_reenter_factory'); });
    return { async connect() { connections++; await barrier; }, async request() { deletes++; return {}; }, async close() { closes++; } };
  });
  const second = threads.cleanup(() => { throw new Error('must_not_create_second_port'); });
  assert.equal(first, second);
  assert.throws(() => threads.beginCreation(), { code: 'demo_thread_cleanup_started' });
  assert.throws(() => threads.confirmCreation('late-id'), { code: 'demo_thread_cleanup_started' });
  release(); const result = await first;
  assert.equal(reentrant, first);
  assert.equal(await second, result);
  assert.equal(await threads.cleanup(() => { throw new Error('must_not_retry'); }), result);
  assert.deepEqual([factories, connections, deletes, closes], [1, 1, 1, 1]);
  for (const value of [result, result.threads, result.threads[0], result.portCreation, result.connection, result.controlClose]) assert(Object.isFrozen(value));
  assert.throws(() => { result.allConfirmed = false; }, TypeError);
  assert.equal(result.allConfirmed, true);
});

test('factory rejection returns per-owned-ID not-attempted evidence without a replacement port', async () => {
  const threads = owned('owned-a', 'owned-b'); let factories = 0;
  const result = await threads.cleanup(() => { factories++; throw new Error('/PRIVATE/credential=sentinel'); });
  assert.equal(result.portCreation.outcome, 'unconfirmed'); assert.equal(result.portCreation.reasonCode, 'unclassified_cleanup_failure');
  assert.equal(result.connection.outcome, 'not_attempted'); assert.equal(result.controlClose.outcome, 'not_attempted');
  assert.equal(result.deletionAttempts, 0); assert.equal(result.deletionUnconfirmed, 2); assert.equal(result.allConfirmed, false);
  for (const row of result.threads) assert.deepEqual([row.deletionAttempted, row.outcome, row.reasonCode], [false, 'not_attempted', 'unclassified_cleanup_failure']);
  assert(!JSON.stringify(result).includes('PRIVATE')); assert(!JSON.stringify(result).includes('sentinel'));
  assert.equal(await threads.cleanup(() => { throw new Error('must_not_retry'); }), result); assert.equal(factories, 1);
});

test('connection rejection still closes the created port and marks every owned delete not attempted', async () => {
  let requests = 0, closes = 0;
  const result = await owned('owned-a', 'owned-b').cleanup(() => ({
    async connect() { throw new HostError('codex_disconnected'); },
    async request() { requests++; return {}; }, async close() { closes++; },
  }));
  assert.deepEqual(result.connection, { outcome: 'unconfirmed', reasonCode: 'codex_disconnected' });
  assert.equal(result.portCreation.outcome, 'confirmed'); assert.equal(result.controlClose.outcome, 'returned');
  assert.equal(requests, 0); assert.equal(closes, 1); assert.equal(result.allConfirmed, false);
  for (const row of result.threads) assert.deepEqual([row.outcome, row.reasonCode], ['not_attempted', 'codex_disconnected']);
});

test('close rejection is separate from successful deletions and cannot establish runtime quiescence', async () => {
  const result = await owned('owned-a').cleanup(() => ({ ...successfulPort(), async close() { throw new HostError('codex_request_timeout_outcome_unknown'); } }));
  assert.equal(result.confirmedDeleted, 1); assert.equal(result.allConfirmed, true);
  assert.deepEqual(result.controlClose, { outcome: 'unconfirmed', reasonCode: 'codex_request_timeout_outcome_unknown' });
  assert.equal(result.confirmationScope, 'owned_thread_deletions_only_not_turn_or_native_quiescence');
});

test('connect and close failures preserve independent sanitized outcomes', async () => {
  const result = await owned('owned-a').cleanup(() => ({
    async connect() { throw new HostError('codex_unavailable'); },
    async request() { throw new Error('must_not_request'); },
    async close() { throw new Error('PRIVATE_CLOSE_FAILURE'); },
  }));
  assert.deepEqual(result.connection, { outcome: 'unconfirmed', reasonCode: 'codex_unavailable' });
  assert.deepEqual(result.controlClose, { outcome: 'unconfirmed', reasonCode: 'unclassified_cleanup_failure' });
  assert.equal(result.deletionAttempts, 0); assert(!JSON.stringify(result).includes('PRIVATE_CLOSE_FAILURE'));
});

test('delete timeout, disconnect and rejected outcomes remain distinct and never consume another deletion', async () => {
  const cases = [
    ['codex_request_timeout_outcome_unknown', 'timeout_outcome_unknown'],
    ['codex_disconnected', 'disconnected_outcome_unknown'], ['codex_closed', 'disconnected_outcome_unknown'],
    ['codex_request_rejected', 'rejected'], ['codex_protocol_error', 'unconfirmed'],
  ] as const;
  for (const [code, outcome] of cases) {
    let calls = 0;
    const result = await owned('owned-a', 'owned-b').cleanup(() => ({ ...successfulPort(), async request() {
      calls++; if (calls === 1) throw new HostError(code); return {};
    } }));
    assert.equal(calls, 2); assert.equal(result.threads[0]!.outcome, outcome); assert.equal(result.threads[0]!.reasonCode, code);
    assert.equal(result.threads[1]!.outcome, 'confirmed_deleted'); assert.equal(result.confirmedDeleted, 1);
    assert.equal(result.allConfirmed, false);
  }
});

test('arbitrary exceptions, unknown HostError codes and server payloads never enter cleanup evidence', async () => {
  const secret = 'PRIVATE_PATH_TOKEN_ERROR_PAYLOAD';
  for (const failure of [new Error(secret), new HostError(secret), { code: 'codex_request_rejected', message: secret }, secret]) {
    const result = await owned('owned-a').cleanup(() => ({ ...successfulPort(), async request() { throw failure; } }));
    assert.equal(result.threads[0]!.outcome, 'unconfirmed'); assert.equal(result.threads[0]!.reasonCode, 'unclassified_cleanup_failure');
    assert(!JSON.stringify(result).includes(secret)); assert.equal(result.allConfirmed, false);
  }
});

test('deletion confirms only an exact ordinary empty JSON object, never malformed success-shaped values', async () => {
  const malformed: unknown[] = [undefined, null, true, false, 0, '', [], [0], { ok: true }, { error: 'PRIVATE_ERROR' },
    new Date(), new Map(), Object.create(null), Object.create({}), Object.defineProperty({}, 'private', { value: 'PRIVATE_ERROR' }),
    { [Symbol('PRIVATE_ERROR')]: true }];
  for (const response of malformed) {
    const result = await owned('owned-a').cleanup(() => ({ ...successfulPort(), async request() { return response; } }));
    assert.equal(result.confirmedDeleted, 0); assert.equal(result.allConfirmed, false);
    assert.deepEqual([result.threads[0]!.outcome, result.threads[0]!.reasonCode], ['unconfirmed', 'thread_delete_response_not_empty_object']);
    assert(!JSON.stringify(result).includes('PRIVATE_ERROR'));
  }
  const success = await owned('owned-a').cleanup(successfulPort);
  assert.equal(success.confirmedDeleted, 1); assert.equal(success.allConfirmed, true);
});

test('duplicate confirmed identity cannot hide a second unresolved creation', async () => {
  const threads = owned('owned-a'); threads.beginCreation();
  assert.throws(() => threads.confirmCreation('owned-a'), { code: 'demo_thread_creation_duplicate' });
  let requests = 0;
  const result = await threads.cleanup(() => ({ ...successfulPort(), async request() { requests++; return {}; } }));
  assert.equal(requests, 1); assert.equal(result.knownOwnedThreads, 1); assert.equal(result.confirmedDeleted, 1);
  assert.equal(result.unknownCreationOutcomes, 1); assert.equal(result.allConfirmed, false);
});

test('ownership bounds reject malformed IDs and excess creation attempts without losing unresolved work', async () => {
  const invalid = new OwnedDemoThreads();
  assert.throws(() => invalid.confirmCreation('not-started'), { code: 'demo_thread_creation_not_started' });
  invalid.beginCreation();
  for (const value of ['', 'with space', 'newline\n', 'null\0', 'x'.repeat(257)]) assert.throws(
    () => invalid.confirmCreation(value), { code: 'demo_thread_creation_not_started' });
  const unresolved = await invalid.cleanup(() => { throw new Error('must_not_discover'); });
  assert.equal(unresolved.unknownCreationOutcomes, 1); assert.equal(unresolved.allConfirmed, false);
  const bounded = owned(...Array.from({ length: 8 }, (_, i) => 'owned-' + i));
  assert.throws(() => bounded.beginCreation(), { code: 'demo_thread_creation_bound' });
  const result = await bounded.cleanup(successfulPort);
  assert.equal(result.threads.length, 8); assert.equal(result.deletionAttempts, 8); assert.equal(result.allConfirmed, true);
});

test('zero creations and unknown-only creations never create a control port or discover threads', async () => {
  for (const unknown of [0, 1, 8]) {
    const threads = new OwnedDemoThreads(); for (let i = 0; i < unknown; i++) threads.beginCreation();
    const result = await threads.cleanup(() => { throw new Error('must_not_connect'); });
    assert.equal(result.knownOwnedThreads, 0); assert.equal(result.threads.length, 0); assert.equal(result.unknownCreationOutcomes, unknown);
    assert.equal(result.allConfirmed, unknown === 0);
    for (const step of [result.portCreation, result.connection, result.controlClose]) assert.deepEqual(step, { outcome: 'not_attempted', reasonCode: null });
  }
});

for (let mask = 0; mask < 8; mask++) test(`consumer closure always attempts poll-stop, host and journal once (failure mask ${mask})`, async () => {
  const calls: string[] = [], failures = [new Error('poll-stop'), new Error('host-close'), new Error('journal-close')];
  const operation = closeDemoConsumer(
    async () => { calls.push('poll-stop'); if (mask & 1) throw failures[0]; },
    async () => { calls.push('host-close'); if (mask & 2) throw failures[1]; },
    () => { calls.push('journal-close'); if (mask & 4) throw failures[2]; },
  );
  if (mask) await assert.rejects(operation, error => error === failures[mask & 4 ? 2 : mask & 2 ? 1 : 0]);
  else await operation;
  assert.deepEqual(calls, ['poll-stop', 'host-close', 'journal-close']);
});
