/** Same-object original TS denial clocks for the real owner pipe. No scalar creates JVM
 * export/model/worker authority. Importing creates no timer, process, file or application.
 */
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { HostError } from '../src/journal.js';
import { RealModelClock, isOriginalRealModelClock } from './real-model-run-support.js';
import { OriginalReviewObservationClock, isOriginalReviewObservationClockFor,
  type CurrentImageParentAdapter } from './current-image-acceptance-runner.js';
import { ownerCheck, ownerInteger, ownerKeys, ownerObject, OWNER_CHALLENGE_LIMIT,
  OWNER_REQUEST_LIMIT, OWNER_SESSION_ID } from './current-image-owner-wire.js';

export const OWNER_MAX_PATCHES = 32;
type Phase = 'overall' | 'export' | 'review';
const moments = new WeakMap<object, bigint>();
class OwnerEntryMoment { private readonly nominal = true; constructor() { Object.freeze(this); } }
export type OriginalOwnerEntry = OwnerEntryMoment;
/** Call as the FIRST statement of factory.open/submit, before any caller property or IO.
 * Capturing a moment on submit1 does NOT create/reset an export clock; export1 is retained.
 */
export function captureOriginalOwnerEntry(): OriginalOwnerEntry {
  const now = process.hrtime.bigint();
  const value = new OwnerEntryMoment(); moments.set(value, now); return value;
}
class OriginalExportClock {
  readonly entered: bigint; readonly deadline: bigint;
  constructor(entry: OriginalOwnerEntry) {
    const entered = moments.get(entry); ownerCheck(entered !== undefined && entered <= process.hrtime.bigint(), 'export_origin_not_original');
    this.entered = entered; this.deadline = entered + 120_000_000_000n; Object.freeze(this);
  }
  remainingMs(): number {
    const remaining = Math.floor(Number(this.deadline - process.hrtime.bigint()) / 1_000_000);
    ownerCheck(remaining > 0 && remaining <= 120_000, 'original_export_deadline'); return remaining;
  }
}
interface Attempt {
  readonly ordinal: number; readonly review: OriginalReviewObservationClock; readonly export: OriginalExportClock;
  state: 'entering' | 'admitted' | 'delivered'; handle: object | null; submittedResponse: boolean;
}
interface RequestScope {
  readonly id: number; readonly method: string; readonly expected: readonly { phase: Phase; ordinal: number }[];
  next: number; responded: boolean;
}
const clockLedgers = new WeakSet<object>();
const methods = new Set(['owner.open', 'owner.configureCompilerPolicy', 'owner.submitCompletedPatch', 'owner.status',
  'review.events', 'review.ack', 'review.observe', 'gate.prepare', 'gate.complete']);

/** Concrete, nominal local ledger. Pump never calls a supplied callback/AcceptanceClock method:
 * it uses only the retained concrete original RealModelClock and registered original review.
 * Closed review/export objects stay retained; no eviction, reset, reopening or old-status check.
 */
export class OwnerSessionClocks {
  readonly #overall: RealModelClock;
  readonly #firstExport: OriginalExportClock;
  #export: OriginalExportClock | null;
  readonly #attempts: Attempt[] = [];
  readonly #reviews = new WeakSet<object>();
  readonly #nonces = new Set<string>();
  #session: string | null = null;
  #request: RequestScope | null = null;
  #completed = 0; #openWireReturned = false; #openAdopted = false;
  #failed: HostError | null = null;
  constructor(overall: RealModelClock, entry: OriginalOwnerEntry) {
    ownerCheck(new.target === OwnerSessionClocks && isOriginalRealModelClock(overall) && Object.isFrozen(overall) && Object.getPrototypeOf(overall) === RealModelClock.prototype, 'original_overall_required');
    this.#overall = overall; this.#firstExport = new OriginalExportClock(entry); this.#export = this.#firstExport;
    ownerCheck(this.#firstExport.entered >= overall.enteredNanos, 'export_before_original_overall');
    this.checkpoint(); clockLedgers.add(this); Object.freeze(this);
  }
  poison(failure: unknown = new HostError('current_owner_denied')): void {
    this.#failed ??= failure instanceof HostError ? failure : new HostError('current_owner_clock_outcome_unknown');
  }
  #guard<T>(operation: () => T): T {
    try { if (this.#failed) throw this.#failed; return operation(); }
    catch (failure) { this.poison(failure); throw this.#failed!; }
  }
  remainingMs(): number {
    return this.#guard(() => {
      const overall = RealModelClock.prototype.remainingMs.call(this.#overall);
      const current = this.#attempts.at(-1);
      const review = current && current.state !== 'delivered' ? OriginalReviewObservationClock.prototype.remainingMs.call(current.review) : 900_000;
      const remaining = Math.min(overall, this.#export?.remainingMs() ?? 900_000, review);
      return ownerInteger(remaining, 900_000, 1);
    });
  }
  checkpoint(): void { this.remainingMs(); }
  bindSession(session: string): void {
    this.#guard(() => { this.checkpoint(); ownerCheck(this.#session === null && OWNER_SESSION_ID.test(session), 'session_rebound'); this.#session = session; });
  }
  beginSubmission(entry: OriginalOwnerEntry, ordinal: number,
    scope: Parameters<CurrentImageParentAdapter['submitCompletedPatch']>[2]): void {
    this.#guard(() => {
      this.checkpoint(); ownerKeys(ownerObject(scope), ['overall', 'reviewObservation', 'originalExportMillis']);
      ownerCheck(this.#openAdopted && this.#request === null && ordinal === this.#attempts.length + 1 && ordinal <= OWNER_MAX_PATCHES &&
        (this.#attempts.length === 0 || this.#attempts.at(-1)!.state === 'delivered'), 'submit_before_previous_delivery_or_reused');
      const overall = scope.overall, review = scope.reviewObservation, millis = scope.originalExportMillis;
      ownerCheck(overall === this.#overall && millis === 120_000 && isOriginalReviewObservationClockFor(review, overall) &&
        Object.getPrototypeOf(review) === OriginalReviewObservationClock.prototype && !this.#reviews.has(review), 'original_review_scope_required');
      // Export1 started BEFORE selection/environment/spawn. Never create another120 on submit1.
      const exportClock = ordinal === 1 ? this.#firstExport : new OriginalExportClock(entry);
      this.#reviews.add(review); this.#export = exportClock;
      this.#attempts.push({ ordinal, review, export: exportClock, state: 'entering', handle: null, submittedResponse: false });
      this.checkpoint();
    });
  }
  beginRequest(id: number, method: string): void {
    this.#guard(() => {
      this.checkpoint(); ownerInteger(id, OWNER_REQUEST_LIMIT, 1);
      ownerCheck(this.#session !== null && this.#request === null && id === this.#completed + 1 && methods.has(method), 'request_clock_order');
      const expected: { phase: Phase; ordinal: number }[] = [];
      if (method === 'owner.open') {
        ownerCheck(id === 1 && !this.#openWireReturned && !this.#openAdopted, 'open_replayed');
        expected.push({ phase: 'overall', ordinal: 0 }, { phase: 'export', ordinal: 1 });
      } else {
        ownerCheck(this.#openAdopted, 'request_before_open_adoption');
        if (method === 'owner.submitCompletedPatch') {
          const attempt = this.#attempts.at(-1);
          ownerCheck(attempt && attempt.state === 'entering' && !attempt.submittedResponse, 'submit_clock_missing');
          expected.push({ phase: 'review', ordinal: attempt.ordinal });
          if (attempt.ordinal > 1) expected.push({ phase: 'export', ordinal: attempt.ordinal });
        }
      }
      this.#request = { id, method, expected, next: 0, responded: false };
    });
  }
  /** Only called synchronously by the bounded byte pump. Nonce is consumed BEFORE any reply.
   * Report floored ACTUAL remaining from retained original objects, never requested/new budgets.
   */
  replyToChallenge(frame: JsonObject): JsonObject {
    return this.#guard(() => {
      this.checkpoint(); ownerKeys(frame, ['kind', 'session', 'requestId', 'challenge', 'phase', 'ordinal']);
      const request = this.#request, nonce = frame.challenge;
      ownerCheck(frame.kind === 'clock.challenge' && frame.session === this.#session && request !== null && !request.responded &&
        frame.requestId === request.id && typeof nonce === 'string' && OWNER_SESSION_ID.test(nonce) && nonce !== this.#session &&
        !this.#nonces.has(nonce) && this.#nonces.size < OWNER_CHALLENGE_LIMIT, 'clock_challenge_unknown_or_replayed');
      ownerInteger(frame.requestId, OWNER_REQUEST_LIMIT, 1); ownerInteger(frame.ordinal, OWNER_MAX_PATCHES);
      const expected = request.expected[request.next];
      ownerCheck(expected && frame.phase === expected.phase && frame.ordinal === expected.ordinal, 'clock_challenge_phase_order');
      this.#nonces.add(nonce); request.next++;
      const attempt = this.#attempts.at(-1);
      const remaining = expected.phase === 'overall' ? RealModelClock.prototype.remainingMs.call(this.#overall) : expected.phase === 'export' ?
        (expected.ordinal === 1 ? this.#firstExport : attempt!.export).remainingMs() :
        OriginalReviewObservationClock.prototype.remainingMs.call(attempt!.review);
      ownerInteger(remaining, expected.phase === 'overall' ? 900_000 : expected.phase === 'export' ? 120_000 : 300_000, 1);
      this.checkpoint();
      return Object.freeze({ kind: 'clock.reply', session: this.#session, requestId: request.id, challenge: nonce,
        phase: expected.phase, ordinal: expected.ordinal, remainingMillis: remaining });
    });
  }
  noteResponse(id: number): void {
    this.#guard(() => {
      this.checkpoint(); const request = this.#request;
      ownerCheck(request && request.id === id && !request.responded && request.next === request.expected.length, 'response_before_original_clocks_or_duplicate');
      request.responded = true;
    });
  }
  finishRequest(id: number): void {
    this.#guard(() => {
      this.checkpoint(); const request = this.#request;
      ownerCheck(request && request.id === id && request.responded && request.next === request.expected.length, 'response_adoption_order');
      if (request.method === 'owner.open') this.#openWireReturned = true;
      if (request.method === 'owner.submitCompletedPatch') this.#attempts.at(-1)!.submittedResponse = true;
      this.#completed = id; this.#request = null;
    });
  }
  adoptOpen(): void {
    this.#guard(() => {
      this.checkpoint(); ownerCheck(this.#openWireReturned && !this.#openAdopted && this.#request === null, 'open_adoption_order');
      this.#openAdopted = true; // Original first120 remains active through installation and submit1.
    });
  }
  admitSubmission(handle: object): void {
    this.#guard(() => {
      this.checkpoint(); const attempt = this.#attempts.at(-1);
      ownerCheck(handle !== null && typeof handle === 'object' && this.#request === null && attempt?.state === 'entering' &&
        attempt.submittedResponse && attempt.handle === null && !this.#attempts.some(old => old.handle === handle), 'admission_handle_order');
      attempt.handle = handle; attempt.state = 'admitted'; this.#export = null;
      // Only response/admission wait has ended. Review300 stays active until the exact joined hook.
      this.checkpoint();
    });
  }
  deliveryVerified(handle: object, review: OriginalReviewObservationClock): void {
    this.#guard(() => {
      this.checkpoint(); const attempt = this.#attempts.at(-1);
      ownerCheck(this.#request === null && attempt?.state === 'admitted' && attempt.handle === handle && attempt.review === review &&
        isOriginalReviewObservationClockFor(review, this.#overall), 'delivery_clock_not_exact_or_replayed');
      OriginalReviewObservationClock.prototype.checkpoint.call(review);
      attempt.state = 'delivered'; // Retain the closed object, not a refreshed/equal-duration clock.
      OriginalReviewObservationClock.prototype.checkpoint.call(review); this.checkpoint();
    });
  }
}
export function isOwnerSessionClocks(value: unknown): value is OwnerSessionClocks {
  return value !== null && typeof value === 'object' && clockLedgers.has(value);
}
// No subclass/prototype callback can be installed in the pump's concrete clock implementation.
Object.freeze(OwnerSessionClocks.prototype); Object.freeze(OwnerSessionClocks);
