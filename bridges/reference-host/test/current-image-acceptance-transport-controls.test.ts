/** LIVE ONLY IN THE SENSE OF AN IN-MEMORY SYNTHETIC TRANSPORT: real ReferenceHost and a
 * NEW local test journal, fake owner/broker/host witnesses, NO selected executable/model call.
 * These controls exercise wrapper fences that a standalone byte-join function cannot provide.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { digest, HostError } from '../src/journal.js';
import type { ModelWitness, InsertedContext } from '../src/types.js';
import { assertNoActualClaim, assertSyntheticFailure, rehashSyntheticApplication } from './current-image-acceptance-adversarial-fixtures.js';
import { controlFixture, type FixtureValue } from './current-image-acceptance-fixtures.js';
import { ACCEPTANCE_MAX_EVENTS } from './current-image-acceptance-evidence.js';
import { safeCurrentAcceptanceFailure } from './current-image-acceptance-runner.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };

test('synthetic host insertion must confirm exact marker, thread, generation and context before any ACK/model', linuxOnly, async t => {
  const mutations: readonly [string, (inserted: FixtureValue) => void][] = [
    ['missing marker', value => { delete value.marker; }],
    ['wrong host thread', value => { value.hostThreadId = 'synthetic-other-thread'; }],
    ['stale generation', value => { value.generation++; }],
    ['wrong context digest', value => { value.receipt.contextDigest = digest('synthetic-wrong-context'); }],
    ['wrong receipt identity', value => { value.receipt.receiptId = 'synthetic-wrong-receipt'; }],
    ['missing context item identity', value => { value.receipt.contextItemId = ''; }],
  ];
  for (const [name, mutate] of mutations) await t.test(name, async st => {
    const f = await controlFixture(st), insert = f.host.insertContext.bind(f.host);
    f.host.insertContext = async (item, receiptId) => {
      const result = await insert(item, receiptId); mutate(result as unknown as FixtureValue); return result;
    };
    const report = await f.run(); assertSyntheticFailure(report); assert.equal(report.failure, 'injection_unconfirmed');
    assert.equal(f.host.requests.length, 0); assert.equal(f.adapter.calls.includes('review.ack'), false);
  });
  await t.test('duplicate prior insertion cannot satisfy a different required context', async st => {
    const f = await controlFixture(st), insert = f.host.insertContext.bind(f.host); let previous: InsertedContext | null = null;
    f.host.insertContext = async (item, receiptId) => {
      const result = await insert(item, receiptId); if (previous) return structuredClone(previous); previous = result; return result;
    };
    const report = await f.run(); assertSyntheticFailure(report); assert.equal(report.failure, 'injection_unconfirmed');
    assert.equal(f.host.requests.length, 0); assert.equal(f.adapter.calls.includes('review.ack'), false);
  });
  await t.test('caller-advertised historical insertion is not adopted from an allegedly fresh host', async st => {
    const f = await controlFixture(st);
    f.host.findContext = async marker => ({ marker, hostThreadId: f.host.binding.hostThreadId, generation: f.host.binding.client.generation,
      receipt: { receiptId: 'synthetic-unissued-receipt', contextDigest: digest('synthetic-unissued-context'), contextItemId: 'synthetic-unissued-item', insertedAt: '2026-11-05T00:00:00.000Z' } });
    const report = await f.run(); assertSyntheticFailure(report); assert.equal(report.failure, 'current_acceptance_historical_context_not_fresh');
    assert.equal(f.host.inserted.size, 0); assert.equal(f.host.requests.length, 0);
  });
});

test('synthetic model witness cannot omit, change or duplicate required request joins', linuxOnly, async t => {
  const mutations: readonly [string, (witness: FixtureValue) => void][] = [
    ['missing model request identity', value => { delete value.modelRequestId; }],
    ['wrong invocation identity', value => { value.invocationId = 'synthetic-other-invocation'; }],
    ['wrong host thread', value => { value.hostThreadId = 'synthetic-other-thread'; }],
    ['stale generation', value => { value.generation++; }],
    ['wrong request digest', value => { value.requestDigest = digest('synthetic-other-request'); }],
    ['missing required context', value => { value.contextDigests.pop(); }],
    ['duplicate context', value => { value.contextDigests.push(value.contextDigests[0]); }],
  ];
  for (const [name, mutate] of mutations) await t.test(name, async st => {
    const f = await controlFixture(st), invoke = f.host.invokeModel.bind(f.host);
    f.host.invokeModel = async request => {
      const witness = await invoke(request); assert.equal(witness.kind, 'actual-model-request');
      mutate(witness as unknown as FixtureValue); return witness as ModelWitness;
    };
    const report = await f.run(); assertSyntheticFailure(report); assert.equal(report.failure, 'model_request_proof_mismatch');
    assert.equal(f.host.requests.length, 1); assert.equal(f.adapter.calls.includes('review.observe'), false);
    assert.equal(f.adapter.parents.length, 1); assert.equal(f.adapter.calls.includes('gate.complete'), false);
  });
  await t.test('coherently rehashed phase invocation cannot replace the request actually passed to the fake host', async st => {
    const f = await controlFixture(st);
    f.host.evidenceHook = phase => {
      rehashSyntheticApplication({ row: { phase }, state: f.journal.read() }, (_app, output) => { output.invocationId = 'synthetic-substitute-invocation'; });
      return phase;
    };
    const report = await f.run(); assertSyntheticFailure(report); assert.equal(report.failure, 'current_acceptance_application_not_same_model_invocation');
    assert.equal(f.host.requests.length, 1); assert.equal(f.adapter.parents.length, 1);
  });
  await t.test('freshly rehashed second phase cannot reuse the first fake model invocation identity', async st => {
    const f = await controlFixture(st);
    f.host.evidenceHook = phase => {
      if (f.host.requests.length === 2) rehashSyntheticApplication({ row: { phase }, state: f.journal.read() }, (_app, output) => { output.invocationId = f.host.requests[0]!.invocationId; });
      return phase;
    };
    const report = await f.run(); assertSyntheticFailure(report); assert.equal(report.failure, 'current_acceptance_application_not_same_model_invocation');
    assert.equal(f.host.requests.length, 2); assert.equal(f.adapter.calls.includes('gate.complete'), false);
  });
  await t.test('second application proof cannot reuse the first fake application identity', async st => {
    const f = await controlFixture(st); f.host.reuseApplicationId = true;
    const report = await f.run(); assertSyntheticFailure(report); assert.equal(report.failure, 'current_acceptance_model_application_id_reused_or_unavailable');
    assert.equal(f.host.requests.length, 2); assert.equal(f.adapter.calls.filter(method => method === 'review.observe').length, 1);
  });
});

test('synthetic ACK/observation response omissions, substitutions and duplicates poison the exact attempt without retry', linuxOnly, async t => {
  const mutations: readonly [string, (response: FixtureValue) => void][] = [
    ['missing event identity set', response => { delete response.eventIds; }],
    ['duplicate returned event identity', response => { response.eventIds.push(response.eventIds[0]); }],
    ['foreign client', response => { response.clientId = 'synthetic-foreign-client'; }],
    ['missing response state', response => { delete response.state; }],
    ['wrong preview contract', response => { response.contractVersion = 'synthetic-wrong-contract'; }],
  ];
  for (const method of ['review.ack', 'review.observe'] as const) {
    for (const [name, mutate] of mutations) await t.test(method + ': ' + name, async st => {
      const f = await controlFixture(st);
      f.adapter.responseHook = (called, response) => { if (called === method) mutate(response); return response; };
      const report = await f.run(); assertSyntheticFailure(report);
      assert.equal(report.failure, 'delivery_capture_confirmation_rejected'); assert.equal(f.host.requests.length, method === 'review.ack' ? 0 : 1);
      assert.equal(f.adapter.calls.filter(called => called === method).length, 1); assert.equal(f.adapter.calls.includes('gate.complete'), false);
      const calls = [...f.adapter.calls], requests = f.host.requests.length;
      assert.throws(() => f.run(), /components_previously_attempted/);
      assert.deepEqual(f.adapter.calls, calls); assert.equal(f.host.requests.length, requests);
    });
    await t.test(method + ': stale first-patch event set cannot confirm the second patch', async st => {
      const f = await controlFixture(st);
      f.adapter.responseHook = (called, response) => {
        if (called === method && f.adapter.parents.length === 2) response.eventIds = [f.adapter.parents[0]!.events[0]!.eventId];
        return response;
      };
      const report = await f.run(); assertSyntheticFailure(report);
      assert.equal(report.failure, 'delivery_capture_confirmation_rejected'); assert.equal(f.host.requests.length, method === 'review.ack' ? 1 : 2);
      assert.equal(f.adapter.parents.length, 2); assert.equal(f.adapter.calls.includes('gate.complete'), false);
    });
  }
});

test('current-image failure diagnostics admit only the bounded capture-rejection and poison-guard cascade', () => {
  const rejected = () => new HostError('delivery_capture_confirmation_rejected');
  const poisoned = () => new HostError('current_acceptance_broker_unknown_or_concurrent');
  const cascade = (count: number) => new AggregateError(Array.from({ length: count }, (_, index) => index === 0 ? rejected() : poisoned()),
    'synthetic_private_aggregate_message');
  assert.equal(safeCurrentAcceptanceFailure(rejected()), 'delivery_capture_confirmation_rejected');
  assert.equal(safeCurrentAcceptanceFailure(new HostError('injection_unconfirmed')), 'injection_unconfirmed');
  assert.equal(safeCurrentAcceptanceFailure(new HostError('synthetic private message')), 'real_model_companion_operation_failed');
  assert.equal(safeCurrentAcceptanceFailure(cascade(2)), 'delivery_capture_confirmation_rejected');
  assert.equal(safeCurrentAcceptanceFailure(cascade(ACCEPTANCE_MAX_EVENTS)), 'delivery_capture_confirmation_rejected');
  const sparse = cascade(2); delete sparse.errors[1];
  const arrayAccessor = cascade(2);
  Object.defineProperty(arrayAccessor.errors, '1', { get: () => { throw new Error('synthetic_error_slot_must_not_be_read'); } });
  const errorsAccessor = cascade(2);
  Object.defineProperty(errorsAccessor, 'errors', { get: () => { throw new Error('synthetic_errors_accessor_must_not_be_read'); } });
  const notArray = cascade(2); Object.defineProperty(notArray, 'errors', { value: { 0: rejected(), 1: poisoned(), length: 2 } });
  const overBound = cascade(ACCEPTANCE_MAX_EVENTS + 1);
  Object.defineProperty(overBound.errors, '0', { get: () => { throw new Error('synthetic_over_bound_must_not_be_scanned'); } });
  const controls: readonly [string, unknown][] = [
    ['plain error message', new Error('delivery_capture_confirmation_rejected')],
    ['aggregate lookalike', { errors: [rejected(), poisoned()] }],
    ['unknown member', new AggregateError([rejected(), new Error('synthetic_private_nested_message')])],
    ['different HostError', new AggregateError([rejected(), new HostError('journal_lease_lost')])],
    ['code lookalike', new AggregateError([rejected(), { code: 'current_acceptance_broker_unknown_or_concurrent' }])],
    ['different first fault', new AggregateError([new HostError('injection_unconfirmed'), poisoned()])],
    ['reordered faults', new AggregateError([poisoned(), rejected()])],
    ['repeated capture rejection', new AggregateError([rejected(), rejected()])],
    ['nested aggregate', new AggregateError([cascade(2), poisoned()])],
    ['empty aggregate', cascade(0)], ['single-member aggregate', cascade(1)],
    ['sparse array', sparse], ['array accessor', arrayAccessor], ['errors accessor', errorsAccessor],
    ['non-array errors', notArray], ['over-bound aggregate', overBound],
  ];
  for (const [name, error] of controls) assert.equal(safeCurrentAcceptanceFailure(error), 'current_acceptance_operation_failed', name);
});

test('synthetic unknown or mixed aggregate faults remain generic and cannot advance acceptance', linuxOnly, async t => {
  for (const mixed of [false, true]) await t.test(mixed ? 'different HostError' : 'unknown Error', async st => {
    const f = await controlFixture(st);
    f.host.insertContext = async () => { throw new AggregateError([new HostError('delivery_capture_confirmation_rejected'),
      mixed ? new HostError('journal_lease_lost') : new Error('synthetic_private_nested_message')], 'synthetic_private_aggregate_message'); };
    const report = await f.run(); assertSyntheticFailure(report);
    assert.equal(report.failure, 'current_acceptance_operation_failed');
    assert.equal(f.host.requests.length, 0); assert.equal(f.adapter.calls.includes('review.ack'), false);
    assert.equal(f.adapter.calls.includes('gate.complete'), false);
    const calls = [...f.adapter.calls]; assert.throws(() => f.run(), /components_previously_attempted/);
    assert.deepEqual(f.adapter.calls, calls);
  });
});

test('synthetic evidence self-labels cannot promote a fake host into actual-current-image acceptance', linuxOnly, async t => {
  const f = await controlFixture(t);
  f.host.evidenceHook = phase => ({ ...phase, origin: 'actual-current-image', actualHostDeliveryVerified: true,
    syntheticAdversarialClaim: 'UNTRUSTED CONTROL LABEL - no actual execution occurred' });
  const report = await f.run(); assertNoActualClaim(report); assert.equal(report.status, 'verified_delivery_final_denied');
  assert.equal(f.host.requests.length, 2); assert.equal(report.launch, null);
});
