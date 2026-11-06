/** Real bounded pipe pump shared by the concrete factory and explicitly synthetic stream
 * controls. There is NO injectable provider/owner callback and no reconnect/close/signal API.
 * A synthetic stream channel cannot mint an actual-launch factory, context, adapter or receipt.
 */
import type { ChildProcessWithoutNullStreams } from 'node:child_process';
import type { Readable, Writable } from 'node:stream';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { HostError } from '../src/journal.js';
import { frozen } from './current-image-acceptance-evidence.js';
import { REQUIRED_PARENT_ADAPTER } from './current-image-acceptance-runner.js';
import { isOwnerSessionClocks, OwnerSessionClocks } from './current-image-owner-clocks.js';
import { encodeOwnerFrame, ownerCheck, ownerInteger, ownerKeys, ownerObject, OwnerFrameDecoder,
  OWNER_FRAME_BYTES, OWNER_FRAME_MILLIS, OWNER_REQUEST_LIMIT, OWNER_SESSION_ID, OWNER_SESSION_PROTOCOL } from './current-image-owner-wire.js';

type WriteKind = 'request' | 'clock' | 'deny';
interface WriteJob {
  readonly kind: WriteKind; readonly id: number; readonly bytes: Buffer; readonly deadline: bigint;
  returned: boolean; accepted: boolean; drainSeen: boolean; callbackDone: boolean; settled: boolean; writeFailed: boolean;
}
interface Pending {
  readonly id: number; readonly method: string;
  readonly promise: Promise<JsonObject>; readonly resolve: (value: JsonObject) => void; readonly reject: (error: HostError) => void;
  requestWritten: boolean; clockWrites: number; response: JsonObject | null; settled: boolean;
}

export class RetainedOwnerPipeChannel {
  readonly #clocks: OwnerSessionClocks;
  readonly #decoder = new OwnerFrameDecoder();
  #input: Writable | null = null; #output: Readable | null = null; #stderr: Readable | null = null;
  #child: ChildProcessWithoutNullStreams | null = null;
  #failure: HostError | null = null; #session: string | null = null;
  #attached = false; #helloTaken = false; #requests = 0;
  #pending: Pending | null = null; #lastReturned: JsonObject | null = null;
  #writing: WriteJob | null = null; #queue: WriteJob[] = []; readonly #abandoned: WriteJob[] = [];
  #denialStarted = false; #timer: NodeJS.Timeout | null = null;
  #stdoutBytes = 0; #stderrBytes = 0; #writes = 0;
  #exitObserved = false; #closeObserved = false;
  readonly #hello: Promise<void>;
  readonly #helloResolve: () => void; readonly #helloReject: (error: HostError) => void;
  constructor(clocks: OwnerSessionClocks) {
    ownerCheck(new.target === RetainedOwnerPipeChannel && isOwnerSessionClocks(clocks), 'concrete_clock_ledger_required');
    this.#clocks = clocks;
    let resolve!: () => void, reject!: (error: HostError) => void;
    this.#hello = new Promise<void>((yes, no) => { resolve = yes; reject = no; });
    this.#helloResolve = resolve; this.#helloReject = reject;
    void this.#hello.catch(() => {}); // Retained rejection, even if selection/spawn fails before awaitHello.
    Object.freeze(this);
  }
  /** Called immediately on the freshly returned spawn handle, before any await. The concrete
   * factory retains that SAME child independently; no caller may supply/adopt a historic process.
   */
  attachSpawnedChild(child: ChildProcessWithoutNullStreams): void {
    try {
      ownerCheck(this.#child === null && !this.#attached, 'child_already_attached'); this.#child = child;
      child.on('error', () => this.poison(new HostError('current_owner_spawn_unavailable')));
      child.on('exit', () => { this.#exitObserved = true; this.poison(new HostError('current_owner_unexpected_exit')); });
      child.on('close', () => { this.#closeObserved = true; this.poison(new HostError('current_owner_unexpected_close')); });
      this.attachPipes(child.stdin, child.stdout, child.stderr);
    } catch (failure) { this.poison(failure); throw this.#failure!; }
  }
  /** Byte-stream seam only. Tests supply NEW in-memory streams, never fake owner/receipt authority. */
  attachPipes(input: Writable, output: Readable, stderr: Readable): void {
    try {
      this.checkpoint(); ownerCheck(!this.#attached, 'pipes_already_attached'); this.#attached = true;
      this.#input = input; this.#output = output; this.#stderr = stderr;
      input.on('error', () => this.poison(new HostError('current_owner_input_disconnected')));
      input.on('close', () => this.poison(new HostError('current_owner_input_closed')));
      input.on('finish', () => this.poison(new HostError('current_owner_input_ended')));
      input.on('drain', () => this.#drain());
      output.on('error', () => this.poison(new HostError('current_owner_output_disconnected')));
      output.on('end', () => this.poison(new HostError('current_owner_unexpected_eof')));
      output.on('close', () => this.poison(new HostError('current_owner_output_closed')));
      stderr.on('error', () => this.poison(new HostError('current_owner_stderr_disconnected')));
      stderr.on('end', () => this.poison(new HostError('current_owner_stderr_ended')));
      stderr.on('close', () => this.poison(new HostError('current_owner_stderr_closed')));
      output.on('data', (bytes: Buffer) => this.#receive(bytes));
      stderr.on('data', (bytes: Buffer) => {
        // Drain after failure too; never retain/rebroadcast private stderr transcript bytes.
        if (!Buffer.isBuffer(bytes)) { this.poison(new HostError('current_owner_stderr_encoding')); return; }
        this.#stderrBytes = Math.min(Number.MAX_SAFE_INTEGER, this.#stderrBytes + bytes.length);
        if (this.#stderrBytes > 1_048_576) this.poison(new HostError('current_owner_stderr_bound'));
      });
      this.checkpoint(); this.#arm();
    } catch (failure) { this.poison(failure); throw this.#failure!; }
  }
  async awaitHello(): Promise<void> {
    try {
      this.checkpoint(); ownerCheck(this.#attached && !this.#helloTaken, 'hello_wait_replayed'); this.#helloTaken = true;
      await this.#hello; this.checkpoint();
    } catch (failure) { this.poison(failure); throw this.#failure!; }
  }
  checkpoint(): void {
    try {
      if (this.#failure) throw this.#failure;
      this.#clocks.checkpoint(); const now = process.hrtime.bigint(); this.#decoder.checkpoint(now);
      for (const job of [this.#writing, ...this.#queue]) if (job && job.kind !== 'deny') ownerCheck(now < job.deadline, 'write_outcome_unknown');
    } catch (failure) { this.poison(failure); throw this.#failure!; }
  }
  async request(method: string, params: JsonObject): Promise<JsonObject> {
    try {
      this.checkpoint(); ownerCheck(this.#attached && this.#session !== null && this.#pending === null &&
        this.#queue.length === 0 && this.#writing === null, 'concurrent_or_unready_request');
      const id = ++this.#requests; ownerInteger(id, OWNER_REQUEST_LIMIT, 1);
      this.#clocks.beginRequest(id, method);
      const bytes = encodeOwnerFrame({ kind: 'request', session: this.#session, id, method, params }); this.checkpoint();
      let resolve!: (value: JsonObject) => void, reject!: (error: HostError) => void;
      const promise = new Promise<JsonObject>((yes, no) => { resolve = yes; reject = no; });
      void promise.catch(() => {});
      const pending: Pending = { id, method, promise, resolve, reject, requestWritten: false, clockWrites: 0, response: null, settled: false };
      this.#pending = pending; // Retain request/wait before the first possibly failing write.
      this.#enqueue(bytes, 'request', id);
      const result = await promise;
      // Synchronous adoption fence AFTER the promise continuation, not just a lagging timer.
      this.checkpoint(); return result;
    } catch (failure) { this.poison(failure); throw this.#failure!; }
  }
  #receive(bytes: Buffer): void {
    if (Buffer.isBuffer(bytes)) this.#stdoutBytes = Math.min(Number.MAX_SAFE_INTEGER, this.#stdoutBytes + bytes.length);
    if (this.#failure) return; // Keep consuming invalid/late output without accumulating it.
    try {
      ownerCheck(Buffer.isBuffer(bytes), 'output_encoding'); this.checkpoint();
      this.#decoder.feed(bytes, process.hrtime.bigint(), (frame, deadline) => {
        ownerCheck(process.hrtime.bigint() < deadline, 'frame_decode_deadline'); this.#dispatch(frame);
      });
      this.checkpoint(); this.#arm();
    } catch (failure) { this.poison(failure); }
  }
  #dispatch(frame: JsonObject): void {
    this.checkpoint();
    if (this.#session === null) {
      ownerKeys(frame, ['kind', 'protocol', 'session', 'profile', 'maxFrameBytes']);
      ownerCheck(frame.kind === 'hello' && frame.protocol === OWNER_SESSION_PROTOCOL && frame.profile === REQUIRED_PARENT_ADAPTER &&
        frame.maxFrameBytes === OWNER_FRAME_BYTES && typeof frame.session === 'string' && OWNER_SESSION_ID.test(frame.session), 'hello_invalid');
      this.#session = frame.session; this.#clocks.bindSession(frame.session); this.checkpoint(); this.#helloResolve(); return;
    }
    ownerCheck(frame.session === this.#session, 'foreign_session');
    const pending = this.#pending;
    ownerCheck(pending !== null && !pending.settled && pending.response === null, 'unsolicited_or_late_response');
    if (frame.kind === 'clock.challenge') {
      const reply = this.#clocks.replyToChallenge(frame); pending.clockWrites++;
      this.#enqueue(encodeOwnerFrame(reply), 'clock', pending.id); this.checkpoint(); return;
    }
    if (frame.kind === 'failure') {
      ownerKeys(frame, ['kind', 'session', 'id', 'code']);
      ownerCheck(frame.id === pending.id && typeof frame.code === 'string' && /^[a-z0-9_]{1,160}$/.test(frame.code), 'failure_frame_invalid');
      // Remote code is DATA, not an instruction to retry/clean up. Do not forward raw diagnostics.
      throw new HostError('current_owner_remote_outcome_unknown');
    }
    ownerKeys(frame, ['kind', 'session', 'id', 'result']);
    ownerCheck(frame.kind === 'response' && frame.id === pending.id, 'response_id_or_kind'); ownerInteger(frame.id, OWNER_REQUEST_LIMIT, 1);
    pending.response = frozen(ownerObject(frame.result)); // Retain decoded result BEFORE final checks/resolution.
    this.#lastReturned = pending.response;
    this.#clocks.noteResponse(pending.id); this.checkpoint(); this.#tryComplete();
  }
  #enqueue(bytes: Buffer, kind: Exclude<WriteKind, 'deny'>, id: number): void {
    this.checkpoint(); ownerCheck(this.#queue.length < 2 && bytes.length <= OWNER_FRAME_BYTES + 4, 'write_queue_bound');
    // At most one executing request and two small challenge replies. No unbounded writable queue.
    ownerCheck(kind === 'request' || bytes.length <= 2_048, 'clock_reply_bound');
    this.#queue.push({ kind, id, bytes, deadline: process.hrtime.bigint() + BigInt(OWNER_FRAME_MILLIS) * 1_000_000n,
      returned: false, accepted: false, drainSeen: false, callbackDone: false, settled: false, writeFailed: false });
    this.#pumpWrites(); this.#arm();
  }
  #pumpWrites(): void {
    if (this.#failure || this.#writing !== null || this.#queue.length === 0) return;
    this.checkpoint(); const job = this.#queue.shift()!; this.#writing = job; this.#write(job);
  }
  #write(job: WriteJob): void {
    try {
      const input = this.#input;
      ownerCheck(input && !input.destroyed && !input.writableEnded, 'write_pipe_unavailable'); this.#writes++;
      // write(false) NEVER blocks this reader callback. Its bounded job waits for both callback
      // and drain, while stdout/clock challenges and fixed original deadline checks keep running.
      job.accepted = input.write(job.bytes, error => {
        if (job.callbackDone) { this.poison(new HostError('current_owner_duplicate_write_callback')); return; }
        job.callbackDone = true;
        if (error) { job.writeFailed = true; this.poison(new HostError('current_owner_write_outcome_unknown')); }
        this.#finishWrite(job);
      });
      job.returned = true; this.#finishWrite(job);
    } catch (failure) { job.writeFailed = true; this.poison(failure); }
  }
  #drain(): void {
    const job = this.#writing; if (!job) return;
    job.drainSeen = true; this.#finishWrite(job);
  }
  #finishWrite(job: WriteJob): void {
    if (job.settled || this.#writing !== job || !job.returned || !job.callbackDone || !job.accepted && !job.drainSeen) return;
    if (this.#failure) {
      job.settled = true; this.#writing = null;
      if (!job.writeFailed && job.kind !== 'deny') this.#tryDeny(); return;
    }
    try {
      this.checkpoint(); job.settled = true; this.#writing = null;
      const pending = this.#pending; ownerCheck(pending && pending.id === job.id, 'write_request_changed');
      if (job.kind === 'request') pending.requestWritten = true;
      else { ownerCheck(job.kind === 'clock' && pending.clockWrites > 0, 'clock_write_order'); pending.clockWrites--; }
      this.#tryComplete(); this.#pumpWrites(); this.#arm();
    } catch (failure) { this.poison(failure); }
  }
  #tryComplete(): void {
    const pending = this.#pending;
    if (!pending || pending.settled || pending.response === null || !pending.requestWritten || pending.clockWrites !== 0) return;
    this.checkpoint(); this.#clocks.finishRequest(pending.id); this.checkpoint();
    this.#lastReturned = pending.response; pending.settled = true; this.#pending = null;
    pending.resolve(pending.response); // Promise continuation is NOT an owner/user callback on pump.
  }
  #arm(): void {
    if (this.#timer) { clearTimeout(this.#timer); this.#timer = null; } // Timer only, never IO cleanup.
    if (this.#failure || !this.#attached) return;
    try {
      this.checkpoint(); const now = process.hrtime.bigint(); let delay = this.#clocks.remainingMs();
      const deadlines = [this.#decoder.partialDeadlineNanos, this.#writing?.deadline ?? null, ...this.#queue.map(job => job.deadline)];
      for (const deadline of deadlines) if (deadline !== null) delay = Math.min(delay, Math.max(1, Math.floor(Number(deadline - now) / 1_000_000)));
      this.#timer = setTimeout(() => {
        this.#timer = null; try { this.checkpoint(); this.#arm(); } catch (failure) { this.poison(failure); }
      }, delay);
    } catch (failure) { this.poison(failure); }
  }
  /** Irreversible DENIAL only. All original child/pipe/pending-result state remains retained.
   * Neither this method nor any timeout/finalizer ends/destroys/closes/signals/restarts anything.
   */
  poison(failure: unknown = new HostError('current_owner_denied')): void {
    if (this.#failure) return;
    this.#failure = failure instanceof HostError ? failure : new HostError('current_owner_transport_outcome_unknown');
    this.#clocks.poison(this.#failure); this.#decoder.poison();
    if (this.#timer) { clearTimeout(this.#timer); this.#timer = null; }
    this.#helloReject(this.#failure);
    if (this.#pending && !this.#pending.settled) { this.#pending.settled = true; this.#pending.reject(this.#failure); }
    this.#abandoned.push(...this.#queue); this.#queue = []; // Bounded original unstarted writes remain retained.
    this.#tryDeny();
  }
  #tryDeny(): void {
    if (!this.#failure || this.#denialStarted || !this.#session || !this.#input || this.#writing) return;
    this.#denialStarted = true; // Consume BEFORE output; failed/unknown output is never retried.
    try {
      const job: WriteJob = { kind: 'deny', id: 0, bytes: encodeOwnerFrame({ kind: 'deny', session: this.#session }), deadline: 0n,
        returned: false, accepted: false, drainSeen: false, callbackDone: false, settled: false, writeFailed: false };
      this.#writing = job; this.#write(job);
    } catch { /* Best-effort denial is not cleanup, completion, delivery, or a retry permit. */ }
  }
  /** Sanitized transport counters only; never credential/challenge/handle/result/log content. */
  evidence(): JsonObject {
    return Object.freeze({ protocol: OWNER_SESSION_PROTOCOL, requests: this.#requests, writes: this.#writes,
      stdoutBytes: this.#stdoutBytes, stderrBytesDiscarded: this.#stderrBytes, failure: this.#failure?.code ?? null,
      exitObserved: this.#exitObserved, closeObserved: this.#closeObserved, retainedOriginalReturn: this.#lastReturned !== null,
      ownerSignalsSent: 0, ownerRetries: 0, ownerCleanupPerformed: false, ownerQuiescenceClaim: false });
  }
}
Object.freeze(RetainedOwnerPipeChannel.prototype); Object.freeze(RetainedOwnerPipeChannel);
