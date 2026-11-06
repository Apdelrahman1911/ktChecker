/** SYNTHETIC in-memory streams exercise the actual bounded channel implementation. These
 * controls cannot issue a launcher context, actual factory/adapter or owner/model receipt. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { RetainedOwnerPipeChannel } from './current-image-owner-channel.js';
import { encodeOwnerFrame } from './current-image-owner-wire.js';
import { syntheticChallenge, syntheticOwnerChannel, syntheticSubmitClock, syntheticTurn,
  SYNTHETIC_OWNER_SESSION } from './current-image-owner-control-fixtures.js';

const outcome = <T>(promise: Promise<T>) => promise.then(value => ({ value, failure: null }), failure => ({ value: null, failure }));
test('write(false) leaves pump live for challenges; response adoption waits for callback AND drain on every write', async t => {
  const f = syntheticOwnerChannel(t); await f.hello(); let settled = false;
  const pending = outcome(f.channel.request('owner.open', {})).then(value => { settled = true; return value; });
  f.frame(syntheticChallenge(1, 'overall', 0, 1)); f.frame(syntheticChallenge(1, 'export', 1, 2)); f.response(1);
  assert.equal(f.input.writes.length, 1); assert.equal(f.channel.evidence().retainedOriginalReturn, true);
  f.input.drain(); assert.equal(f.input.writes.length, 1); // Drain alone is not write completion.
  f.input.complete(0); assert.equal(f.input.writes.length, 2);
  f.input.complete(1); assert.equal(f.input.writes.length, 2); // Callback alone is not backpressure completion.
  f.input.drain(); assert.equal(f.input.writes.length, 3);
  f.input.complete(2); await syntheticTurn(); assert.equal(settled, false);
  f.input.drain(); const result = await pending; assert.equal(result.failure, null); assert.deepEqual(result.value, { synthetic: true });
  f.clocks.adoptOpen();
  assert.equal(f.input.frame(0).kind, 'request'); assert.equal(f.input.frame(1).phase, 'overall'); assert.equal(f.input.frame(2).phase, 'export');
  assert.equal(f.channel.evidence().writes, 3); assert.equal(f.channel.evidence().ownerSignalsSent, 0);
  assert.equal(f.input.destroyed, false); assert.equal(f.output.destroyed, false); assert.equal(f.input.writableEnded, false);
});
test('accepted write(true) still waits for the callback; synchronous caller success is not delivery', async t => {
  const f = syntheticOwnerChannel(t); await f.hello(); f.input.accepted = true;
  const pending = outcome(f.channel.request('owner.open', {}));
  f.frame(syntheticChallenge(1, 'overall', 0, 1)); f.frame(syntheticChallenge(1, 'export', 1, 2)); f.response(1);
  assert.equal(f.input.writes.length, 1); f.input.complete(0); f.input.complete(1); f.input.complete(2);
  assert.equal((await pending).failure, null); f.clocks.adoptOpen();
});
test('original export is checked after response promise continuation, not merely by a pending timer', async t => {
  const f = syntheticOwnerChannel(t); await f.open(); syntheticSubmitClock(f.overall, f.clocks, 1);
  const pending = outcome(f.channel.request('owner.submitCompletedPatch', {}));
  f.frame(syntheticChallenge(2, 'review', 1, 3)); f.response(2); f.flushWrite(3); f.flushWrite(4);
  f.advance(120_000); assert.match(String((await pending).failure), /original_export_deadline/);
  assert.equal(f.channel.evidence().retainedOriginalReturn, true);
});
test('active original300 governs actual channel broker response adoption until exact local delivery closure', async t => {
  const f = syntheticOwnerChannel(t); await f.open(); await f.admitFirst(); f.advance(299_999);
  const pending = outcome(f.channel.request('review.events', {})); f.response(3); f.flushWrite(5); f.advance(1);
  assert.match(String((await pending).failure), /original_review_observation_deadline/);
});
test('closed original300 and first120 are never consulted for an old-status request', async t => {
  const f = syntheticOwnerChannel(t); await f.open(); const first = await f.admitFirst();
  f.clocks.deliveryVerified(first.handle, first.review); f.advance(300_001);
  const pending = outcome(f.channel.request('owner.status', { handle: 'SYNTHETIC DATA NOT A NOMINAL HANDLE' }));
  f.response(3); f.flushWrite(5); assert.equal((await pending).failure, null);
  assert.throws(() => first.review.checkpoint(), /original_review_observation_deadline/);
});
test('fixed15s output deadline is not refreshed by drain or an original900 still being live', async t => {
  const f = syntheticOwnerChannel(t); await f.hello(); const pending = outcome(f.channel.request('owner.open', {}));
  f.advance(14_999); f.input.drain(); f.channel.checkpoint(); f.advance(1);
  assert.throws(() => f.channel.checkpoint(), /write_outcome_unknown/); assert((await pending).failure);
  assert.equal(f.input.destroyed, false); assert.equal(f.input.writableEnded, false);
});
test('partial frame has fixed15s from first header byte and permanently denies hello', async t => {
  const f = syntheticOwnerChannel(t), bytes = encodeOwnerFrame({ kind: 'SYNTHETIC incomplete' });
  f.output.emit('data', bytes.subarray(0, 1)); f.advance(14_999); f.output.emit('data', bytes.subarray(1, 2)); f.advance(1);
  assert.throws(() => f.channel.checkpoint(), /frame_fragment_timeout/);
  await assert.rejects(f.channel.awaitHello(), /frame_fragment_timeout/);
});
for (const [name, mutation] of [
  ['foreign session', (value: JsonObject) => { value.session = 'b'.repeat(64); }],
  ['wrong response id', (value: JsonObject) => { value.id = 2; }],
  ['extra response property', (value: JsonObject) => { value.retry = true; }],
  ['nonobject result', (value: JsonObject) => { value.result = []; }],
] as const) test('channel rejects ' + name + ' without retry or cleanup', async t => {
  const f = syntheticOwnerChannel(t); await f.hello(); const pending = outcome(f.channel.request('owner.open', {}));
  f.frame(syntheticChallenge(1, 'overall', 0, 1)); f.frame(syntheticChallenge(1, 'export', 1, 2));
  const response: JsonObject = { kind: 'response', session: SYNTHETIC_OWNER_SESSION, id: 1, result: {} }; mutation(response); f.frame(response);
  assert((await pending).failure); await assert.rejects(f.channel.request('owner.open', {}));
  assert.equal(f.channel.evidence().requests, 1); assert.equal(f.channel.evidence().ownerRetries, 0); assert.equal(f.input.destroyed, false);
});
test('malformed or duplicate-key frame uses the same permanent channel poison path', async t => {
  const f = syntheticOwnerChannel(t); await f.hello(); const pending = outcome(f.channel.request('owner.open', {}));
  const bytes = Buffer.from('{"kind":"response","kind":"failure"}'), header = Buffer.alloc(4); header.writeUInt32BE(bytes.length);
  f.output.emit('data', Buffer.concat([header, bytes])); assert((await pending).failure);
  assert.throws(() => f.channel.checkpoint(), /strict_json/);
});
test('returned result is retained before a replay frame poisons a still-backpressured request', async t => {
  const f = syntheticOwnerChannel(t); await f.hello(); const pending = outcome(f.channel.request('owner.open', {}));
  f.frame(syntheticChallenge(1, 'overall', 0, 1)); f.frame(syntheticChallenge(1, 'export', 1, 2)); f.response(1);
  assert.equal(f.channel.evidence().retainedOriginalReturn, true); f.response(1);
  assert.match(String((await pending).failure), /unsolicited_or_late_response/);
  assert.equal(f.channel.evidence().retainedOriginalReturn, true); assert.equal(f.input.writes.length, 1);
});
test('write failure retains the uncertain write and response, never resends it or signals a child', async t => {
  const f = syntheticOwnerChannel(t); await f.hello(); const pending = outcome(f.channel.request('owner.open', {}));
  f.frame(syntheticChallenge(1, 'overall', 0, 1)); f.frame(syntheticChallenge(1, 'export', 1, 2)); f.response(1);
  f.input.complete(0, new Error('SYNTHETIC output lost')); f.input.drain();
  assert.match(String((await pending).failure), /write_outcome_unknown/);
  assert.equal(f.input.writes.length, 1); assert.equal(f.channel.evidence().retainedOriginalReturn, true);
  assert.equal(f.channel.evidence().ownerCleanupPerformed, false); assert.equal(f.input.destroyed, false);
});
test('concurrent request permanently denies both waits rather than introducing a queue or replay lane', async t => {
  const f = syntheticOwnerChannel(t); await f.hello(); const first = outcome(f.channel.request('owner.open', {}));
  const second = outcome(f.channel.request('owner.open', {}));
  assert.match(String((await second).failure), /concurrent_or_unready_request/); assert((await first).failure);
  assert.equal(f.channel.evidence().requests, 1); assert.equal(f.input.writes.length, 1);
});
for (const event of ['end', 'close', 'error'] as const) test('owner output ' + event + ' rejects an uncertain wait and retains pipes', async t => {
  const f = syntheticOwnerChannel(t); await f.hello(); const pending = outcome(f.channel.request('owner.open', {}));
  f.output.emit(event, new Error('SYNTHETIC disconnected')); assert((await pending).failure);
  assert.equal(f.input.destroyed, false); assert.equal(f.output.destroyed, false); assert.equal(f.stderr.destroyed, false);
});
test('stderr is bounded and discarded, and best-effort deny is once-only rather than cleanup', async t => {
  const f = syntheticOwnerChannel(t); await f.open(); f.stderr.emit('data', Buffer.alloc(1_048_577, 0x58));
  assert.throws(() => f.channel.checkpoint(), /stderr_bound/);
  assert.equal(f.input.writes.length, 4); assert.deepEqual(f.input.frame(3), { kind: 'deny', session: SYNTHETIC_OWNER_SESSION });
  f.channel.poison(); f.stderr.emit('data', Buffer.from('PRIVATE SYNTHETIC TRANSCRIPT MUST NOT BE RETAINED'));
  assert.equal(f.input.writes.length, 4);
  const evidence = JSON.stringify(f.channel.evidence()); assert(!evidence.includes('TRANSCRIPT')); assert(!evidence.includes(SYNTHETIC_OWNER_SESSION));
  assert.equal(f.input.destroyed, false); assert.equal(f.input.writableEnded, false);
});
test('same channel implementation is frozen and exposes no lifecycle cleanup/reconnect method', () => {
  assert(Object.isFrozen(RetainedOwnerPipeChannel)); assert(Object.isFrozen(RetainedOwnerPipeChannel.prototype));
  for (const method of ['close', 'destroy', 'end', 'kill', 'restart', 'retry', 'cancel', 'unref'])
    assert.equal(Object.hasOwn(RetainedOwnerPipeChannel.prototype, method), false);
});
