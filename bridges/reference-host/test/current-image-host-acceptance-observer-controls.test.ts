/** SYNTHETIC promises, plain objects and diagnostic sinks ONLY. No owner/host/factory/clock
 * injection into the actual driver and no JVM, compiler, provider or model execution. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { RetainedCurrentImageObservation, RetainedCurrentImagePublication,
  CURRENT_IMAGE_OBSERVER_MILLIS, CURRENT_IMAGE_PUBLICATION_MILLIS, CURRENT_IMAGE_PUBLICATION_BYTES,
  type CurrentImageDiagnosticSink } from './current-image-host-acceptance-observer.js';

const bytes = () => Buffer.from('{"syntheticDiagnostic":true}\n', 'utf8');
const past = () => process.hrtime.bigint() - 1_000_000_000n;

test('observation retains actual returned promise/value while exposing only frozen boolean custody and no execution authority', async () => {
  const observer = new RetainedCurrentImageObservation(process.hrtime.bigint());
  assert.equal(observer.custody().attempted, false); assert.equal(observer.custody().outcome, null);
  const value = { privateSyntheticValue: 'not emitted' }, promise = Promise.resolve(value);
  const report = await observer.observe(promise);
  assert.equal(report.outcome, 'returned'); assert.equal(report.originalObservationMillis, 900_000);
  assert.equal(CURRENT_IMAGE_OBSERVER_MILLIS, 900_000); assert.equal(report.executionAuthority, false);
  assert.equal(report.lifecycleDisposition, 'retained_no_quiescence_claim'); assert(Object.isFrozen(report));
  assert.deepEqual(observer.custody(), { attempted: true, outcome: 'returned', promiseRetained: true,
    returnRetained: true, failureRetained: false, lateSettlementRetained: false });
  assert(Object.isFrozen(observer.custody())); assert(!JSON.stringify(observer.custody()).includes('not emitted'));
  assert(!('value' in report)); assert(!('promise' in report)); assert(!('actualHostDeliveryVerified' in report));
});

test('observation retains rejection without serializing or invoking private error members', async () => {
  let members = 0; const failure = Object.defineProperty({}, 'close', { get() { members++; throw new Error('must remain retained'); } });
  const observer = new RetainedCurrentImageObservation(process.hrtime.bigint()), report = await observer.observe(Promise.reject(failure));
  assert.equal(report.outcome, 'rejected'); assert.equal(observer.custody().failureRetained, true);
  assert.equal(observer.custody().returnRetained, false); assert.equal(members, 0);
  assert.throws(() => observer.checkpoint(), /observation_stopped/); assert(!('error' in report));
});

for (const settlement of ['return', 'reject'] as const) test('deadline is sticky and late ' + settlement + ' stays retained without cleanup or promotion', async () => {
  const pending: { resolve?: (value: unknown) => void; reject?: (failure: unknown) => void } = {};
  const promise = new Promise<unknown>((resolve, reject) => { pending.resolve = resolve; pending.reject = reject; });
  const observer = new RetainedCurrentImageObservation(process.hrtime.bigint(), 10), report = await observer.observe(promise);
  assert.equal(report.outcome, 'deadline'); assert.equal(observer.custody().lateSettlementRetained, false);
  assert.throws(() => observer.checkpoint(), /observation_stopped/);
  let lifecycleCalls = 0;
  const retained = Object.fromEntries(['cancel', 'close', 'end', 'unref', 'kill', 'terminate'].map(name => [name, () => { lifecycleCalls++; }]));
  if (settlement === 'return') pending.resolve!(retained); else pending.reject!(retained);
  await Promise.resolve();
  assert.equal(observer.custody().outcome, 'deadline'); assert.equal(observer.custody().lateSettlementRetained, true);
  assert.equal(observer.custody().returnRetained, settlement === 'return'); assert.equal(observer.custody().failureRetained, settlement === 'reject');
  assert.equal(report.outcome, 'deadline'); assert.equal(lifecycleCalls, 0);
  assert.throws(() => observer.observe(Promise.resolve()), /no_retry/);
});

test('observation attachment cannot renew an already expired absolute original bound', async () => {
  const observer = new RetainedCurrentImageObservation(past(), 1);
  assert.equal(observer.remainingObservationMillis(), 0); assert.throws(() => observer.checkpoint(), /observation_stopped/);
  const report = await observer.observe(Promise.resolve('synthetic late value'));
  assert.equal(report.outcome, 'deadline'); assert(report.elapsedMillis >= 1);
  assert.equal(report.originalObservationMillis, 1); assert.equal(observer.remainingObservationMillis(), 0);
});

test('a remaining-observation view only narrows the original time and issues no runner clock', () => {
  const observer = new RetainedCurrentImageObservation(past(), 2000);
  const remaining = observer.remainingObservationMillis(); assert(remaining >= 0 && remaining <= 1000);
  assert(!('wait' in observer)); assert(!('originalDeadlineNanos' in observer)); assert(!('overall' in observer));
});

test('native promise observation does not invoke an overridden then property', async () => {
  let calls = 0; const promise = Promise.resolve('synthetic original return');
  Object.defineProperty(promise, 'then', { get() { calls++; throw new Error('not a promise observer'); } });
  const observer = new RetainedCurrentImageObservation(process.hrtime.bigint());
  assert.equal((await observer.observe(promise)).outcome, 'returned'); assert.equal(calls, 0);
});

for (const kind of ['thenable', 'prototype imitation'] as const) test('observation rejects ' + kind + ' without invoking its arbitrary then', async () => {
  let calls = 0; const fake = kind === 'thenable' ? {} : Object.create(Promise.prototype) as object;
  Object.defineProperty(fake, 'then', { get() { calls++; throw new Error('must not run arbitrary then'); } });
  const observer = new RetainedCurrentImageObservation(process.hrtime.bigint());
  assert.equal((await observer.observe(fake as Promise<unknown>)).outcome, 'rejected');
  assert.equal(observer.custody().promiseRetained, true); assert.equal(observer.custody().failureRetained, true); assert.equal(calls, 0);
});

test('a returned observation does not permit a second observed operation', async () => {
  const observer = new RetainedCurrentImageObservation(process.hrtime.bigint()); await observer.observe(Promise.resolve());
  assert.throws(() => observer.observe(Promise.resolve()), /no_retry/); assert.equal(observer.custody().outcome, 'returned');
});

for (const maximum of [0, -1, 900_001, 1.5, Infinity, NaN]) test('observation rejects invalid or expanded bound ' + String(maximum), () => {
  assert.throws(() => new RetainedCurrentImageObservation(process.hrtime.bigint(), maximum), /original_bound/);
});
test('observation rejects future, negative and non-bigint start values and subclass/prototype substitution', () => {
  for (const start of [-1n, process.hrtime.bigint() + 10_000_000_000n, 0 as unknown as bigint])
    assert.throws(() => new RetainedCurrentImageObservation(start), /original_bound/);
  class Replacement extends RetainedCurrentImageObservation {}
  assert.throws(() => new Replacement(process.hrtime.bigint()), /original_bound/);
  const fake = Object.create(RetainedCurrentImageObservation.prototype) as RetainedCurrentImageObservation;
  assert.throws(() => fake.checkpoint(), TypeError);
  assert(Object.isFrozen(RetainedCurrentImageObservation)); assert(Object.isFrozen(RetainedCurrentImageObservation.prototype));
  assert(Object.isFrozen(new RetainedCurrentImageObservation(process.hrtime.bigint())));
});

test('publication confirms exactly one returned diagnostic write and callback, not delivery, reader ACK or process exit', async () => {
  const original = bytes(), publisher = new RetainedCurrentImagePublication(process.hrtime.bigint()); let writes = 0;
  const report = await publisher.publish(original, (copy, done) => {
    writes++; assert.notEqual(copy, original); assert.deepEqual(copy, original); done(null, copy.length);
  });
  assert.equal(writes, 1); assert.equal(report.outcome, 'confirmed'); assert.equal(report.writeAttempted, true);
  assert.equal(report.originalPublicationMillis, 15_000); assert.equal(CURRENT_IMAGE_PUBLICATION_MILLIS, 15_000);
  assert.equal(CURRENT_IMAGE_PUBLICATION_BYTES, 4096); assert.equal(report.executionAuthority, false);
  assert.equal(report.lifecycleDisposition, 'retained_no_quiescence_claim'); assert(Object.isFrozen(report));
  assert.equal(publisher.custody().bytesRetained, true); assert.equal(publisher.custody().callbackRetained, true);
  assert(Object.isFrozen(publisher.custody())); assert(!('actualHostDeliveryVerified' in report));
  assert(!('readerAcknowledged' in report)); assert(!('processExited' in report));
});

test('publication accepts the normal asynchronous callback and copies caller bytes before the write', async () => {
  const original = bytes(), publisher = new RetainedCurrentImagePublication(process.hrtime.bigint()); let observed: Buffer | null = null;
  const observation = publisher.publish(original, (copy, done) => { observed = copy; queueMicrotask(() => done(null, copy.length)); });
  original.fill(0); assert.deepEqual(observed, bytes()); assert.equal((await observation).outcome, 'confirmed');
});

for (const [name, sink] of [
  ['callback error', ((copy, done) => { done(new Error('synthetic private error'), copy.length); }) as CurrentImageDiagnosticSink],
  ['short write', ((copy, done) => { done(null, copy.length - 1); }) as CurrentImageDiagnosticSink],
  ['oversized count', ((copy, done) => { done(null, copy.length + 1); }) as CurrentImageDiagnosticSink],
  ['string count', ((copy, done) => { done(null, String(copy.length)); }) as CurrentImageDiagnosticSink],
  ['undefined error', ((copy, done) => { done(undefined, copy.length); }) as CurrentImageDiagnosticSink],
  ['changed write bytes', ((copy, done) => { copy[0] = 0; done(null, copy.length); }) as CurrentImageDiagnosticSink],
  ['synchronous throw', (() => { throw new Error('synthetic write outcome unknown'); }) as CurrentImageDiagnosticSink],
  ['callback then throw', ((copy, done) => { done(null, copy.length); throw new Error('synthetic call did not return'); }) as CurrentImageDiagnosticSink],
  ['duplicate synchronous callback', ((copy, done) => { done(null, copy.length); done(null, copy.length); }) as CurrentImageDiagnosticSink],
] as const) test('publication treats ' + name + ' as failed with no resend', async () => {
  const publisher = new RetainedCurrentImagePublication(process.hrtime.bigint()); let writes = 0;
  const report = await publisher.publish(bytes(), (copy, done) => { writes++; return sink(copy, done); });
  assert.equal(report.outcome, 'failed'); assert.equal(report.writeAttempted, true); assert.equal(writes, 1);
  assert.throws(() => publisher.publish(bytes(), sink), /no_retry/); assert.equal(writes, 1);
  assert(!JSON.stringify(report).includes('synthetic private error'));
});

test('unknown nonvoid sink return remains retained without invoking its then or lifecycle methods', async () => {
  let accessed = 0; const unknown = Object.fromEntries(['close', 'end', 'kill', 'unref', 'terminate'].map(name => [name, () => { accessed++; }]));
  Object.defineProperty(unknown, 'then', { get() { accessed++; throw new Error('not observed'); } });
  const publisher = new RetainedCurrentImagePublication(process.hrtime.bigint());
  const report = await publisher.publish(bytes(), () => unknown);
  assert.equal(report.outcome, 'failed'); assert.equal(publisher.custody().sinkReturnRetained, true); assert.equal(accessed, 0);
});

test('publication timeout retains a late callback and never upgrades, resends or signals a writer', async () => {
  const pending: { done?: (failure: unknown, count: unknown) => void } = {}; let writes = 0;
  const publisher = new RetainedCurrentImagePublication(process.hrtime.bigint(), 10);
  const report = await publisher.publish(bytes(), (_copy, done) => { writes++; pending.done = done; });
  assert.equal(report.outcome, 'deadline'); assert.equal(publisher.custody().lateCallbackRetained, false);
  assert(pending.done); pending.done(null, bytes().length);
  assert.equal(publisher.custody().outcome, 'deadline'); assert.equal(publisher.custody().lateCallbackRetained, true);
  assert.equal(report.outcome, 'deadline'); assert.equal(writes, 1);
  assert.throws(() => publisher.publish(bytes(), () => { writes++; }), /no_retry/); assert.equal(writes, 1);
});

test('a later duplicate cannot revoke the first immutable callback observation and grants no authority', async () => {
  const pending: { done?: (failure: unknown, count: unknown) => void } = {};
  const publisher = new RetainedCurrentImagePublication(process.hrtime.bigint());
  const report = await publisher.publish(bytes(), (copy, done) => { pending.done = done; done(null, copy.length); });
  assert.equal(report.outcome, 'confirmed'); pending.done!(new Error('synthetic second callback'), 0);
  assert.equal(report.outcome, 'confirmed'); assert.equal(publisher.custody().failureRetained, true);
  assert.equal(report.executionAuthority, false); // The actual selected node:fs.write has exactly ONE callback.
});

test('publication start uses its original absolute deadline and never calls a sink after it is already expired', async () => {
  const publisher = new RetainedCurrentImagePublication(past(), 1); let writes = 0;
  const report = await publisher.publish(bytes(), () => { writes++; });
  assert.equal(report.outcome, 'deadline'); assert.equal(report.writeAttempted, false); assert.equal(writes, 0);
  assert.equal(publisher.custody().bytesRetained, true); assert.equal(report.originalPublicationMillis, 1);
});

for (const [name, input] of [['empty bytes', new Uint8Array(0)], ['oversized bytes', new Uint8Array(4097)],
  ['nonbyte array', [] as unknown as Uint8Array]] as const)
  test('publication refuses ' + name + ' before a sink call', async () => {
    const publisher = new RetainedCurrentImagePublication(process.hrtime.bigint()); let writes = 0;
    const report = await publisher.publish(input, () => { writes++; });
    assert.equal(report.outcome, 'failed'); assert.equal(report.writeAttempted, false); assert.equal(writes, 0);
  });
for (const maximum of [0, -1, 15_001, 0.5, Infinity, NaN]) test('publication rejects invalid or expanded bound ' + String(maximum), () => {
  assert.throws(() => new RetainedCurrentImagePublication(process.hrtime.bigint(), maximum), /original_bound/);
});
test('publication rejects future, negative and non-bigint starts and is frozen against prototype/subclass substitution', () => {
  for (const start of [-1n, process.hrtime.bigint() + 10_000_000_000n, 0 as unknown as bigint])
    assert.throws(() => new RetainedCurrentImagePublication(start), /original_bound/);
  class Replacement extends RetainedCurrentImagePublication {}
  assert.throws(() => new Replacement(process.hrtime.bigint()), /original_bound/);
  const fake = Object.create(RetainedCurrentImagePublication.prototype) as RetainedCurrentImagePublication;
  assert.throws(() => fake.publish(bytes(), () => {}), TypeError);
  assert(Object.isFrozen(RetainedCurrentImagePublication)); assert(Object.isFrozen(RetainedCurrentImagePublication.prototype));
  assert(Object.isFrozen(new RetainedCurrentImagePublication(process.hrtime.bigint())));
});
