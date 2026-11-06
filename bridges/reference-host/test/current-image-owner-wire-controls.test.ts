/** SYNTHETIC codec controls. Buffer/data only; no process, owner, host, compiler or model. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { encodeOwnerFrame, OwnerFrameDecoder, OWNER_CHALLENGE_LIMIT, OWNER_FRAME_BYTES, OWNER_FRAME_MILLIS,
  OWNER_REQUEST_LIMIT, strictOwnerJson } from './current-image-owner-wire.js';

test('owner strict JSON preserves Unicode and escaped paired-surrogate DATA', () => {
  const raw = '{"path":"Unicode-Δ-\\\"-$-[literal]","paired":"\\ud83d\\ude00","values":[true,false,null,-1.25e2]}';
  assert.deepEqual(strictOwnerJson(Buffer.from(raw)), JSON.parse(raw));
});
for (const [name, raw] of [
  ['literal duplicate key', '{"kind":1,"kind":2}'], ['decoded duplicate key', '{"kind":1,"\\u006bind":2}'],
  ['nested decoded duplicate', '{"outer":{"a":1,"\\u0061":2}}'], ['lone high surrogate', '"\\ud800"'],
  ['lone low surrogate', '"\\udfff"'], ['surrogate object key', '{"\\ud800":1}'],
  ['UTF8 BOM', '\ufeff{"kind":1}'], ['infinite numeric value', '{"value":1e309}'],
  ['leading zero', '{"value":01}'], ['plus sign', '+1'], ['trailing object', '{}{}'],
  ['trailing comma', '{"value":1,}'], ['unescaped control', '"a\nb"'], ['unfinished escape', '"\\u12"'],
] as const) test('owner strict JSON rejects ' + name, () => {
  assert.throws(() => strictOwnerJson(Buffer.from(raw)), /current_owner_/);
});
for (const bytes of [[0xc0, 0xaf], [0xe2, 0x82], [0xed, 0xa0, 0x80], [0xff]])
  test('owner strict UTF8 rejects malformed bytes ' + bytes.join('-'), () => {
    assert.throws(() => strictOwnerJson(Buffer.from(bytes)), /current_owner_utf8/);
  });
test('owner JSON depth and total-value limits are fixed, not caller widened', () => {
  assert.doesNotThrow(() => strictOwnerJson(Buffer.from('['.repeat(64) + '0' + ']'.repeat(64))));
  assert.throws(() => strictOwnerJson(Buffer.from('['.repeat(65) + '0' + ']'.repeat(65))), /strict_json/);
  assert.doesNotThrow(() => strictOwnerJson(Buffer.from('[' + '0,'.repeat(199_998) + '0]')));
  assert.throws(() => strictOwnerJson(Buffer.from('[' + '0,'.repeat(199_999) + '0]')), /strict_json/);
  assert.throws(() => strictOwnerJson(Buffer.alloc(0)), /json_bound/);
  assert.throws(() => strictOwnerJson(Buffer.alloc(OWNER_FRAME_BYTES + 1)), /json_bound/);
});
test('owner frame deadline belongs to FIRST header byte, through fragmented header and payload', () => {
  const decoder = new OwnerFrameDecoder(), frame = encodeOwnerFrame({ data: 'Δ-$-[literal]' }), start = 5_000_000n;
  const deadline = start + BigInt(OWNER_FRAME_MILLIS) * 1_000_000n, values: unknown[] = [], deadlines: bigint[] = [];
  const emit = (value: unknown, original: bigint) => { values.push(value); deadlines.push(original); };
  decoder.feed(frame.subarray(0, 1), start, emit); assert.equal(decoder.partialDeadlineNanos, deadline);
  decoder.feed(frame.subarray(1, 3), start + 1_000_000_000n, emit); assert.equal(decoder.partialDeadlineNanos, deadline);
  decoder.feed(frame.subarray(3, 6), deadline - 2n, emit); assert.equal(values.length, 0);
  decoder.feed(frame.subarray(6), deadline - 1n, emit);
  assert.deepEqual(values, [{ data: 'Δ-$-[literal]' }]); assert.deepEqual(deadlines, [deadline]); assert.equal(decoder.partialDeadlineNanos, null);
});
test('slow fragments cannot refresh an expired frame, even if later synthetic now goes backward', () => {
  const decoder = new OwnerFrameDecoder(), frame = encodeOwnerFrame({ data: true });
  decoder.feed(frame.subarray(0, 1), 0n, () => assert.fail('incomplete frame'));
  decoder.feed(frame.subarray(1, 2), 14_000_000_000n, () => assert.fail('incomplete frame'));
  assert.throws(() => decoder.feed(frame.subarray(2), 15_000_000_000n, () => assert.fail('expired frame')), /fragment_timeout/);
  assert.throws(() => decoder.feed(frame, 1n, () => assert.fail('poisoned frame')), /frame_poisoned/);
});
for (const size of [0, OWNER_FRAME_BYTES + 1, 0xffff_ffff]) test('owner frame length rejects before allocation: ' + size, () => {
  const decoder = new OwnerFrameDecoder(), header = Buffer.alloc(4); header.writeUInt32BE(size);
  assert.throws(() => decoder.feed(header, 0n, () => assert.fail()), /frame_length/);
  assert.throws(() => decoder.feed(encodeOwnerFrame({}), 0n, () => assert.fail()), /frame_poisoned/);
});
test('owner coalesced frames keep order and each complete object retains its original frame deadline', () => {
  const decoder = new OwnerFrameDecoder(), seen: unknown[] = [];
  decoder.feed(Buffer.concat([encodeOwnerFrame({ n: 1 }), encodeOwnerFrame({ n: 2 })]), 0n, value => seen.push(value));
  assert.deepEqual(seen, [{ n: 1 }, { n: 2 }]);
});
test('owner decode/dispatch exception and EOF poison the original decoder permanently', () => {
  const decoder = new OwnerFrameDecoder();
  assert.throws(() => decoder.feed(encodeOwnerFrame({}), 0n, () => { throw new Error('synthetic dispatch failure'); }), /synthetic dispatch/);
  assert.throws(() => decoder.feed(encodeOwnerFrame({}), 0n, () => assert.fail()), /frame_poisoned/);
  const partial = new OwnerFrameDecoder(); partial.feed(Buffer.from([0]), 0n, () => assert.fail());
  assert.throws(() => partial.eof(), /unexpected_eof/); assert.throws(() => partial.checkpoint(0n), /frame_poisoned/);
});
test('framed nonobject payload and cumulative frame-count overflow deny rather than dispatch', () => {
  const decoder = new OwnerFrameDecoder(), raw = Buffer.from('[]'), header = Buffer.alloc(4); header.writeUInt32BE(raw.length);
  assert.throws(() => decoder.feed(Buffer.concat([header, raw]), 0n, () => assert.fail()), /current_owner_object/);
  const bounded = new OwnerFrameDecoder(), frame = encodeOwnerFrame({}); let seen = 0;
  const maximum = OWNER_REQUEST_LIMIT + OWNER_CHALLENGE_LIMIT + 2;
  for (let i = 0; i < maximum; i++) bounded.feed(frame, 0n, () => { seen++; });
  assert.equal(seen, maximum); assert.throws(() => bounded.feed(frame, 0n, () => { seen++; }), /frame_count/); assert.equal(seen, maximum);
});
test('actual decoder class/prototype cannot be replaced with a callback-bearing parser', () => {
  assert(Object.isFrozen(OwnerFrameDecoder)); assert(Object.isFrozen(OwnerFrameDecoder.prototype));
  assert.throws(() => Object.defineProperty(OwnerFrameDecoder.prototype, 'feed', { value() { assert.fail('replacement'); } }));
});
