/** SYNTHETIC byte/clock controls ONLY. No factory.open, launch context, process, owner,
 * provider, compiler, model or actual-delivery authority is constructed by these helpers. */
import assert from 'node:assert/strict';
import { Readable, Writable } from 'node:stream';
import type { TestContext } from 'node:test';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { HostError } from '../src/journal.js';
import { RealModelClock } from './real-model-run-support.js';
import { OriginalReviewObservationClock, REQUIRED_PARENT_ADAPTER } from './current-image-acceptance-runner.js';
import { captureOriginalOwnerEntry, OwnerSessionClocks } from './current-image-owner-clocks.js';
import { RetainedOwnerPipeChannel } from './current-image-owner-channel.js';
import { encodeOwnerFrame, OWNER_FRAME_BYTES, OWNER_SESSION_PROTOCOL, strictOwnerJson } from './current-image-owner-wire.js';

export const SYNTHETIC_OWNER_SESSION = 'a'.repeat(64);
export const syntheticNonce = (number: number): string => number.toString(16).padStart(64, '0');
export function syntheticOwnerClocks(t: TestContext) {
  let now = 1_000_000_000n;
  // A TEST-ONLY monotonic source mock drives the SAME concrete original clock implementations.
  // No alternate clock object or externally supplied timestamp is accepted by the actual factory.
  t.mock.method(process.hrtime, 'bigint', () => now);
  const overall = new RealModelClock(), entered = captureOriginalOwnerEntry(), clocks = new OwnerSessionClocks(overall, entered);
  return { overall, clocks, advance: (milliseconds: number) => { now += BigInt(milliseconds) * 1_000_000n; },
    advanceNanos: (nanos: bigint) => { now += nanos; } };
}
export function syntheticChallenge(requestId: number, phase: string, ordinal: number, nonce: number): JsonObject {
  return { kind: 'clock.challenge', session: SYNTHETIC_OWNER_SESSION, requestId, challenge: syntheticNonce(nonce), phase, ordinal };
}
export function syntheticOpenLedger(clocks: OwnerSessionClocks): void {
  clocks.bindSession(SYNTHETIC_OWNER_SESSION); clocks.beginRequest(1, 'owner.open');
  clocks.replyToChallenge(syntheticChallenge(1, 'overall', 0, 1)); clocks.replyToChallenge(syntheticChallenge(1, 'export', 1, 2));
  clocks.noteResponse(1); clocks.finishRequest(1); clocks.adoptOpen();
}
export function syntheticSubmitClock(overall: RealModelClock, clocks: OwnerSessionClocks, ordinal: number): OriginalReviewObservationClock {
  const review = new OriginalReviewObservationClock(overall), entered = captureOriginalOwnerEntry();
  clocks.beginSubmission(entered, ordinal, { overall, reviewObservation: review, originalExportMillis: 120_000 }); return review;
}
export function syntheticAdmitClock(clocks: OwnerSessionClocks, ordinal: number, requestId = ordinal + 1): object {
  clocks.beginRequest(requestId, 'owner.submitCompletedPatch');
  clocks.replyToChallenge(syntheticChallenge(requestId, 'review', ordinal, ordinal * 2 + 1));
  if (ordinal > 1) clocks.replyToChallenge(syntheticChallenge(requestId, 'export', ordinal, ordinal * 2 + 2));
  clocks.noteResponse(requestId); clocks.finishRequest(requestId);
  const handle = Object.freeze({}); clocks.admitSubmission(handle); return handle;
}

type WriteCallback = (error: Error | null | undefined) => void;
/** New in-memory Writable only: controls callback and drain independently. Not a subprocess,
 * a broker/owner stand-in, a receipt issuer, or an injection point in the real factory. */
export class SyntheticOwnerWriter extends Writable {
  readonly writes: { readonly bytes: Buffer; readonly done: WriteCallback }[] = [];
  accepted = false; throwWrite = false;
  constructor() { super({ autoDestroy: false }); }
  override write(chunk: unknown, encodingOrCallback?: BufferEncoding | WriteCallback, callback?: WriteCallback): boolean {
    assert(Buffer.isBuffer(chunk)); const done = typeof encodingOrCallback === 'function' ? encodingOrCallback : callback; assert(done);
    this.writes.push({ bytes: Buffer.from(chunk), done });
    if (this.throwWrite) throw new HostError('synthetic_owner_write_failure');
    return this.accepted;
  }
  complete(index: number, error?: Error): void { const row = this.writes[index]; assert(row); row.done(error); }
  drain(): void { this.emit('drain'); }
  frame(index: number): JsonObject {
    const row = this.writes[index]; assert(row); assert.equal(row.bytes.readUInt32BE(), row.bytes.length - 4);
    return strictOwnerJson(row.bytes.subarray(4)) as JsonObject;
  }
}
export const syntheticTurn = (): Promise<void> => new Promise(resolve => { setImmediate(resolve); });
export function syntheticOwnerChannel(t: TestContext) {
  const time = syntheticOwnerClocks(t), channel = new RetainedOwnerPipeChannel(time.clocks), input = new SyntheticOwnerWriter();
  const output = new Readable({ read() {}, autoDestroy: false }), stderr = new Readable({ read() {}, autoDestroy: false });
  channel.attachPipes(input, output, stderr);
  t.after(() => channel.poison(new HostError('synthetic_control_finished'))); // TIMER only; no child or native handle exists here.
  const frame = (value: JsonObject): void => { output.emit('data', encodeOwnerFrame(value)); };
  const hello = async (): Promise<void> => {
    frame({ kind: 'hello', protocol: OWNER_SESSION_PROTOCOL, session: SYNTHETIC_OWNER_SESSION,
      profile: REQUIRED_PARENT_ADAPTER, maxFrameBytes: OWNER_FRAME_BYTES }); await channel.awaitHello();
  };
  const response = (requestId: number, result: JsonObject = { synthetic: true }): void => {
    frame({ kind: 'response', session: SYNTHETIC_OWNER_SESSION, id: requestId, result });
  };
  const flushWrite = (index: number): void => { input.complete(index); input.drain(); };
  const open = async (): Promise<void> => {
    await hello(); const pending = channel.request('owner.open', {});
    frame(syntheticChallenge(1, 'overall', 0, 1)); frame(syntheticChallenge(1, 'export', 1, 2)); response(1);
    flushWrite(0); flushWrite(1); flushWrite(2); await pending; time.clocks.adoptOpen();
  };
  const admitFirst = async () => {
    const review = syntheticSubmitClock(time.overall, time.clocks, 1), pending = channel.request('owner.submitCompletedPatch', {});
    frame(syntheticChallenge(2, 'review', 1, 3)); response(2); flushWrite(3); flushWrite(4); await pending;
    const handle = Object.freeze({}); time.clocks.admitSubmission(handle); return { review, handle };
  };
  return { ...time, channel, input, output, stderr, frame, hello, response, flushWrite, open, admitFirst };
}
