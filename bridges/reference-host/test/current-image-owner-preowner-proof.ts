/** Closed byte gate for the opt-in REAL pre-owner-only JVM smoke. This is NOT an owner
 * factory/context/adapter, shutdown RPC or cleanup receipt. It permits exactly open1,
 * the real overall0 reply, and existing deny. No export reply can reach its raw pipe.
 * Inert stream controls exercise this SAME gate without minting child/owner authority.
 */
import { createHash, type Hash } from 'node:crypto';
import { Writable } from 'node:stream';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { HostError } from '../src/journal.js';
import { REQUIRED_PARENT_ADAPTER } from './current-image-acceptance-runner.js';
import { isOriginalRealModelClock, RealModelClock } from './real-model-run-support.js';
import { encodeOwnerFrame, OwnerFrameDecoder, ownerCheck, ownerInteger, ownerKeys, ownerObject,
  OWNER_FRAME_BYTES, OWNER_SESSION_ID, OWNER_SESSION_PROTOCOL } from './current-image-owner-wire.js';

export const PREOWNER_STDOUT_BYTES = 8_192;
export const PREOWNER_STDERR_BYTES = 65_536;
export const PREOWNER_WRITE_BYTES = 2_048;
type Stage = 'hello' | 'open' | 'overall-challenge' | 'overall-reply' | 'export-challenge' | 'export-reply' | 'deny-started';
type ForwardedKind = 'request.owner.open' | 'clock.reply.overall0' | 'deny';
interface ForwardedWrite {
  readonly kind: ForwardedKind; readonly bytes: Buffer; readonly done: (error?: Error | null) => void;
  returned: boolean; accepted: boolean; callbackDone: boolean; drainSeen: boolean; settled: boolean; failed: boolean;
}
const hash = (bytes: Uint8Array | string): string => 'sha256:' + createHash('sha256').update(bytes).digest('hex');

export class PreownerOnlyWireProof {
  readonly input: Writable;
  readonly #raw: Writable; readonly #overall: RealModelClock;
  readonly #stdoutDecoder = new OwnerFrameDecoder(); readonly #inputDecoder = new OwnerFrameDecoder();
  readonly #stdoutHash: Hash = createHash('sha256'); readonly #stderrHash: Hash = createHash('sha256');
  #stage: Stage = 'hello'; #session: string | null = null; #failure: HostError | null = null;
  #overallChallenge: JsonObject | null = null; #exportChallenge: JsonObject | null = null;
  #overallBefore: number | null = null; #overallReply: number | null = null; #overallAfter: number | null = null;
  #stdoutBytes = 0; #stderrBytes = 0; #frames = 0; #laterDeniesSuppressed = 0; #exportRepliesSuppressed = 0;
  #writing: ForwardedWrite | null = null; readonly #forwarded: ForwardedWrite[] = [];

  constructor(rawInput: Writable, overall: RealModelClock) {
    ownerCheck(new.target === PreownerOnlyWireProof && isOriginalRealModelClock(overall), 'preowner_original_overall_required');
    this.#raw = rawInput; this.#overall = overall;
    // This NEW shim never ends/destroys/closes the raw pipe, even on a callback error.
    this.input = new Writable({ highWaterMark: 4_096, autoDestroy: false,
      write: (bytes: Buffer, _encoding, done) => this.#write(bytes, done) });
    rawInput.on('drain', () => {
      const job = this.#writing; if (job) { job.drainSeen = true; this.#finish(job); }
    });
    Object.freeze(this);
  }
  fail(error: unknown): HostError {
    this.#failure ??= error instanceof HostError ? error : new HostError('current_owner_preowner_outcome_unknown');
    return this.#failure;
  }
  #checkpoint(): void {
    if (this.#failure) throw this.#failure;
    RealModelClock.prototype.checkpoint.call(this.#overall);
  }
  /** Register this observer BEFORE channel.attachPipes. It sees actual child bytes first,
   * captures the SAME original overall upper bound, and admits only the three real frames.
   * Both this observer and the real channel drain the SAME child stdout, not a replay/copy.
   */
  observeStdout(bytes: Buffer): void {
    try {
      ownerCheck(Buffer.isBuffer(bytes), 'preowner_stdout_encoding');
      this.#stdoutBytes = Math.min(Number.MAX_SAFE_INTEGER, this.#stdoutBytes + bytes.length);
      ownerCheck(this.#stdoutBytes <= PREOWNER_STDOUT_BYTES, 'preowner_stdout_bound');
      this.#stdoutHash.update(bytes); this.#checkpoint();
      this.#stdoutDecoder.feed(bytes, process.hrtime.bigint(), frame => this.#observeFrame(frame));
    } catch (error) { throw this.fail(error); }
  }
  observeStderr(bytes: Buffer): void {
    try {
      ownerCheck(Buffer.isBuffer(bytes), 'preowner_stderr_encoding');
      this.#stderrBytes = Math.min(Number.MAX_SAFE_INTEGER, this.#stderrBytes + bytes.length);
      ownerCheck(this.#stderrBytes <= PREOWNER_STDERR_BYTES, 'preowner_stderr_bound');
      this.#stderrHash.update(bytes);
    } catch (error) { throw this.fail(error); }
  }
  #observeFrame(frame: JsonObject): void {
    this.#checkpoint(); ownerCheck(++this.#frames <= 3, 'preowner_extra_child_frame');
    if (this.#stage === 'hello') {
      ownerKeys(frame, ['kind', 'protocol', 'session', 'profile', 'maxFrameBytes']);
      ownerCheck(frame.kind === 'hello' && frame.protocol === OWNER_SESSION_PROTOCOL && frame.profile === REQUIRED_PARENT_ADAPTER &&
        frame.maxFrameBytes === OWNER_FRAME_BYTES && typeof frame.session === 'string' && OWNER_SESSION_ID.test(frame.session), 'preowner_hello');
      this.#session = frame.session; this.#stage = 'open'; return;
    }
    ownerKeys(frame, ['kind', 'session', 'requestId', 'challenge', 'phase', 'ordinal']);
    ownerCheck(frame.kind === 'clock.challenge' && frame.session === this.#session && frame.requestId === 1 &&
      typeof frame.challenge === 'string' && OWNER_SESSION_ID.test(frame.challenge) && frame.challenge !== this.#session, 'preowner_challenge');
    if (this.#stage === 'overall-challenge') {
      ownerCheck(frame.phase === 'overall' && frame.ordinal === 0, 'preowner_overall_order');
      this.#overallChallenge = Object.freeze({ ...frame });
      this.#overallBefore = RealModelClock.prototype.remainingMs.call(this.#overall);
      this.#stage = 'overall-reply'; return;
    }
    ownerCheck(this.#stage === 'export-challenge' && frame.phase === 'export' && frame.ordinal === 1 &&
      frame.challenge !== this.#overallChallenge?.challenge, 'preowner_export_order');
    this.#exportChallenge = Object.freeze({ ...frame }); this.#stage = 'export-reply';
  }
  #clockReply(frame: JsonObject, expected: JsonObject, maximum: number): number {
    ownerKeys(frame, ['kind', 'session', 'requestId', 'challenge', 'phase', 'ordinal', 'remainingMillis']);
    ownerCheck(frame.kind === 'clock.reply' && frame.session === this.#session && frame.requestId === 1 &&
      frame.challenge === expected.challenge && frame.phase === expected.phase && frame.ordinal === expected.ordinal, 'preowner_reply_correlation');
    return ownerInteger(frame.remainingMillis, maximum, 1);
  }
  #write(bytes: Buffer, done: (error?: Error | null) => void): void {
    let completed = false;
    const complete = (error?: Error | null): void => {
      if (completed) { this.fail(new HostError('current_owner_preowner_callback_reentered')); return; }
      completed = true; done(error);
    };
    try {
      ownerCheck(Buffer.isBuffer(bytes) && bytes.length >= 5 && bytes.length <= PREOWNER_WRITE_BYTES, 'preowner_write_bound');
      const frames: JsonObject[] = [];
      this.#inputDecoder.feed(bytes, process.hrtime.bigint(), frame => { frames.push(frame); });
      ownerCheck(frames.length === 1 && this.#inputDecoder.partialDeadlineNanos === null, 'preowner_whole_single_write');
      const frame = ownerObject(frames[0]);
      ownerCheck(encodeOwnerFrame(frame).equals(bytes), 'preowner_canonical_writer_required');
      this.#checkpoint(); ownerCheck(frame.session === this.#session, 'preowner_write_session');
      if (this.#stage === 'deny-started' && frame.kind === 'deny') {
        ownerKeys(frame, ['kind', 'session']);
        ownerCheck(this.#laterDeniesSuppressed++ === 0, 'preowner_repeated_late_deny');
        // The real channel poisons on natural EOF/exit. Its one best-effort deny is consumed
        // LOCALLY, never forwarded to a closing child or mistaken for a lifecycle operation.
        complete(); return;
      }
      ownerCheck(this.#writing === null, 'preowner_overlapping_raw_write');
      let kind: ForwardedKind; let forwarded: Buffer;
      if (this.#stage === 'open') {
        ownerKeys(frame, ['kind', 'session', 'id', 'method', 'params']); ownerKeys(ownerObject(frame.params), []);
        ownerCheck(frame.kind === 'request' && frame.id === 1 && frame.method === 'owner.open', 'preowner_open_only');
        kind = 'request.owner.open'; forwarded = Buffer.from(bytes); this.#stage = 'overall-challenge';
      } else if (this.#stage === 'overall-reply') {
        const remaining = this.#clockReply(frame, this.#overallChallenge!, 900_000);
        const after = RealModelClock.prototype.remainingMs.call(this.#overall);
        ownerCheck(this.#overallBefore !== null && remaining <= this.#overallBefore && remaining >= after, 'preowner_original_overall_bracket');
        this.#overallReply = remaining; this.#overallAfter = after;
        kind = 'clock.reply.overall0'; forwarded = Buffer.from(bytes); this.#stage = 'export-challenge';
      } else {
        ownerCheck(this.#stage === 'export-reply', 'preowner_unexpected_write');
        this.#clockReply(frame, this.#exportChallenge!, 120_000);
        ownerCheck(this.#exportRepliesSuppressed++ === 0, 'preowner_export_reply_repeated');
        // Consume the only permitted export1 reply BEFORE raw IO. Its bytes are NEVER sent.
        // This is existing protocol denial, not a fabricated response/registration/authority.
        this.#stage = 'deny-started'; kind = 'deny'; forwarded = encodeOwnerFrame({ kind: 'deny', session: this.#session });
      }
      ownerCheck(this.#forwarded.length < 3 && forwarded.length <= PREOWNER_WRITE_BYTES, 'preowner_raw_write_bound');
      const job: ForwardedWrite = { kind, bytes: forwarded, done: complete, returned: false, accepted: false, callbackDone: false,
        drainSeen: false, settled: false, failed: false };
      this.#forwarded.push(job); this.#writing = job; // Retain intent BEFORE possibly partial/throwing output.
      try {
        job.accepted = this.#raw.write(job.bytes, error => {
          if (job.callbackDone) { this.fail(new HostError('current_owner_preowner_duplicate_raw_callback')); return; }
          job.callbackDone = true;
          if (error) { job.failed = true; this.fail(new HostError('current_owner_preowner_raw_write_unknown')); }
          this.#finish(job);
        });
        job.returned = true; this.#finish(job);
      } catch (error) {
        job.failed = true; job.settled = true; this.#writing = null; complete(this.fail(error));
      }
    } catch (error) { complete(this.fail(error)); }
  }
  #finish(job: ForwardedWrite): void {
    if (job.settled || this.#writing !== job || !job.returned || !job.callbackDone || !job.failed && !job.accepted && !job.drainSeen) return;
    job.settled = true; this.#writing = null;
    job.done(job.failed || this.#failure ? this.fail(this.#failure) : undefined);
  }
  assertComplete(): void {
    this.#checkpoint();
    ownerCheck(this.#stage === 'deny-started' && this.#frames === 3 && this.#stdoutDecoder.partialDeadlineNanos === null &&
      this.#inputDecoder.partialDeadlineNanos === null && this.#exportRepliesSuppressed === 1 && this.#forwarded.length === 3 &&
      this.#forwarded.map(job => job.kind).join(',') === 'request.owner.open,clock.reply.overall0,deny' && this.#writing === null &&
      this.#forwarded.every(job => job.returned && job.callbackDone && job.settled && !job.failed && (job.accepted || job.drainSeen)) &&
      this.#stderrBytes === 0, 'preowner_wire_not_complete');
  }
  evidence(): JsonObject {
    return Object.freeze({ scope: 'real-jvm-framing-clock-preowner-denial-only', protocol: OWNER_SESSION_PROTOCOL,
      stage: this.#stage, helloObserved: this.#session !== null, childFrames: this.#frames,
      overallChallengeDigest: this.#overallChallenge ? hash(String(this.#overallChallenge.challenge)) : null,
      exportChallengeDigest: this.#exportChallenge ? hash(String(this.#exportChallenge.challenge)) : null,
      originalOverallBeforeMillis: this.#overallBefore, originalOverallReplyMillis: this.#overallReply, originalOverallAfterMillis: this.#overallAfter,
      exportRepliesSuppressed: this.#exportRepliesSuppressed, exportRepliesForwarded: 0, laterDeniesSuppressed: this.#laterDeniesSuppressed,
      rawWrites: this.#forwarded.map(job => ({ kind: job.kind, bytes: job.bytes.length, digest: hash(job.bytes), returned: job.returned,
        accepted: job.accepted, callbackDone: job.callbackDone, drainSeen: job.drainSeen, settled: job.settled, failed: job.failed })),
      stdoutBytes: this.#stdoutBytes, stdoutDigest: 'sha256:' + this.#stdoutHash.copy().digest('hex'),
      stderrBytesDiscarded: this.#stderrBytes, stderrDigest: 'sha256:' + this.#stderrHash.copy().digest('hex'),
      digestsCoverAllObservedBytes: this.#stdoutBytes <= PREOWNER_STDOUT_BYTES && this.#stderrBytes <= PREOWNER_STDERR_BYTES,
      failure: this.#failure?.code ?? null, ownerSignalsSent: 0, ownerRetries: 0, ownerCleanupPerformed: false,
      ownerInitializationValidated: false, ownerQuiescenceClaim: false, providerOrModelValidated: false });
  }
}
Object.freeze(PreownerOnlyWireProof.prototype); Object.freeze(PreownerOnlyWireProof);
