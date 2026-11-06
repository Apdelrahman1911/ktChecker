/** Bounded observation ONLY. This utility grants no execution time and has no owner/host API.
 * Its deadline can only stop the driver from starting more work; it never resets runner clocks.
 * Results/errors/promises remain retained after timeout. No cancel, close, signal, end or unref.
 */
import { HostError } from '../src/journal.js';

export const CURRENT_IMAGE_OBSERVER_MILLIS = 900_000;
export interface CurrentImageObservation {
  readonly schema: 'ktchecker-current-image-promise-observation/1';
  readonly outcome: 'returned' | 'rejected' | 'deadline';
  readonly elapsedMillis: number; readonly originalObservationMillis: number;
  readonly executionAuthority: false; readonly lifecycleDisposition: 'retained_no_quiescence_claim';
}
export class RetainedCurrentImageObservation {
  readonly #entered: bigint; readonly #deadline: bigint; readonly #maximumMillis: number;
  #used = false; #outcome: CurrentImageObservation['outcome'] | null = null;
  #promise: Promise<unknown> | null = null; #settler: Promise<unknown> | null = null;
  #value: unknown; #failure: unknown; #returned = false; #rejected = false;
  #timer: ReturnType<typeof setTimeout> | null = null;
  #resolve: ((value: CurrentImageObservation) => void) | null = null;
  constructor(entered: bigint, maximumMillis = CURRENT_IMAGE_OBSERVER_MILLIS) {
    if (new.target !== RetainedCurrentImageObservation || typeof entered !== 'bigint' || entered < 0n || entered > process.hrtime.bigint() ||
      !Number.isSafeInteger(maximumMillis) || maximumMillis < 1 || maximumMillis > CURRENT_IMAGE_OBSERVER_MILLIS)
      throw new HostError('current_image_observer_original_bound');
    this.#entered = entered; this.#maximumMillis = maximumMillis;
    this.#deadline = entered + BigInt(maximumMillis) * 1_000_000n; Object.freeze(this);
  }
  /** Denial-only wall observation, NOT an export/review/overall budget or original clock object. */
  checkpoint(): void {
    if (this.#outcome === 'deadline' || this.#outcome === 'rejected' || process.hrtime.bigint() >= this.#deadline)
      throw new HostError('current_image_driver_observation_stopped');
  }
  remainingObservationMillis(): number {
    const remaining = this.#deadline - process.hrtime.bigint();
    return remaining > 0n ? Math.floor(Number(remaining) / 1_000_000) : 0;
  }
  observe(promise: Promise<unknown>): Promise<CurrentImageObservation> {
    if (this.#used) throw new HostError('current_image_observer_no_retry');
    this.#used = true; this.#promise = promise; // Retain before inspecting/attaching anything.
    const observation = new Promise<CurrentImageObservation>(resolve => { this.#resolve = resolve; });
    try {
      if (!(promise instanceof Promise)) throw new HostError('current_image_observer_promise_required');
      // Use the native internal Promise brand, never an arbitrary overridden .then method.
      this.#settler = Promise.prototype.then.call(promise, (value: unknown) => {
        this.#value = value; this.#returned = true; // FIRST after actual return, including after timeout.
        this.#finish('returned');
      }, (failure: unknown) => {
        this.#failure = failure; this.#rejected = true; this.#finish('rejected');
      });
    } catch (failure) { this.#failure = failure; this.#rejected = true; this.#finish('rejected'); }
    this.#arm(); return observation;
  }
  #arm(): void {
    if (this.#outcome !== null) return;
    const remaining = this.#deadline - process.hrtime.bigint();
    if (remaining <= 0n) { this.#finish('deadline'); return; }
    // Rechecking an early timer uses the SAME absolute original observation deadline.
    this.#timer = setTimeout(() => { this.#timer = null; this.#arm(); }, Math.max(1, Math.ceil(Number(remaining) / 1_000_000)));
  }
  #finish(proposed: CurrentImageObservation['outcome']): void {
    if (this.#outcome !== null) return; // A late real return stays retained but cannot upgrade timeout.
    const now = process.hrtime.bigint();
    this.#outcome = now >= this.#deadline ? 'deadline' : proposed;
    if (this.#timer !== null) { clearTimeout(this.#timer); this.#timer = null; }
    this.#resolve!(Object.freeze({ schema: 'ktchecker-current-image-promise-observation/1', outcome: this.#outcome,
      elapsedMillis: Math.max(0, Number(now - this.#entered) / 1_000_000), originalObservationMillis: this.#maximumMillis,
      executionAuthority: false, lifecycleDisposition: 'retained_no_quiescence_claim' }));
  }
  /** Sanitized custody readback, never the retained value/error itself and never a receipt issuer. */
  custody(): Readonly<{ attempted: boolean; outcome: CurrentImageObservation['outcome'] | null;
    promiseRetained: boolean; returnRetained: boolean; failureRetained: boolean; lateSettlementRetained: boolean }> {
    return Object.freeze({ attempted: this.#used, outcome: this.#outcome, promiseRetained: this.#promise !== null,
      returnRetained: this.#returned, failureRetained: this.#rejected,
      lateSettlementRetained: this.#outcome === 'deadline' && (this.#returned || this.#rejected) });
  }
}
Object.freeze(RetainedCurrentImageObservation.prototype); Object.freeze(RetainedCurrentImageObservation);

export const CURRENT_IMAGE_PUBLICATION_MILLIS = 15_000;
export const CURRENT_IMAGE_PUBLICATION_BYTES = 4096;
export type CurrentImageDiagnosticSink = (bytes: Buffer, callback: (failure: unknown, count: unknown) => void) => unknown;
export interface CurrentImagePublication {
  readonly schema: 'ktchecker-current-image-diagnostic-publication/1';
  readonly outcome: 'confirmed' | 'failed' | 'deadline'; readonly writeAttempted: boolean;
  readonly elapsedMillis: number; readonly originalPublicationMillis: number;
  readonly executionAuthority: false; readonly lifecycleDisposition: 'retained_no_quiescence_claim';
}
/** Authority-free one-write diagnostic observation. The actual driver selects node:fs.write;
 * tests may supply SYNTHETIC sinks here, never substitute a driver/owner/host/factory/clock.
 * A confirmed callback is NOT a reader ACK, model observation or process-exit witness.
 */
export class RetainedCurrentImagePublication {
  readonly #entered: bigint; readonly #deadline: bigint; readonly #maximumMillis: number;
  #used = false; #attempted = false; #sinkReturned = false; #callbackCount = 0;
  #bytes: Buffer | null = null; #original: Buffer | null = null; #sink: CurrentImageDiagnosticSink | null = null;
  #sinkReturn: unknown; #failure: unknown; #thrown = false; #count: unknown;
  #outcome: CurrentImagePublication['outcome'] | null = null; #promise: Promise<CurrentImagePublication> | null = null;
  #timer: ReturnType<typeof setTimeout> | null = null; #resolve: ((value: CurrentImagePublication) => void) | null = null;
  constructor(entered: bigint, maximumMillis = CURRENT_IMAGE_PUBLICATION_MILLIS) {
    if (new.target !== RetainedCurrentImagePublication || typeof entered !== 'bigint' || entered < 0n || entered > process.hrtime.bigint() ||
      !Number.isSafeInteger(maximumMillis) || maximumMillis < 1 || maximumMillis > CURRENT_IMAGE_PUBLICATION_MILLIS)
      throw new HostError('current_image_publication_original_bound');
    this.#entered = entered; this.#maximumMillis = maximumMillis;
    this.#deadline = entered + BigInt(maximumMillis) * 1_000_000n; Object.freeze(this);
  }
  publish(bytes: Uint8Array, sink: CurrentImageDiagnosticSink): Promise<CurrentImagePublication> {
    if (this.#used) throw new HostError('current_image_publication_no_retry');
    this.#used = true; this.#sink = sink;
    this.#promise = new Promise<CurrentImagePublication>(resolve => { this.#resolve = resolve; });
    try {
      if (!(bytes instanceof Uint8Array) || bytes.byteLength < 1 || bytes.byteLength > CURRENT_IMAGE_PUBLICATION_BYTES || typeof sink !== 'function')
        throw new HostError('current_image_publication_input_bound');
      this.#original = Buffer.from(bytes); this.#bytes = Buffer.from(bytes);
      this.#arm();
      if (this.#outcome === null) {
        this.#attempted = true;
        this.#sinkReturn = sink(this.#bytes, (failure, count) => {
          // Retain even after deadline, with no promotion, resend or lifecycle call.
          this.#callbackCount++; this.#failure = failure; this.#count = count;
          if (this.#sinkReturned) this.#settledCallback();
        });
        this.#sinkReturned = true;
        if (this.#sinkReturn !== undefined) this.#finish('failed');
        else if (this.#callbackCount > 0) this.#settledCallback();
      }
    } catch (failure) { this.#failure = failure; this.#thrown = true; this.#finish('failed'); }
    return this.#promise;
  }
  #settledCallback(): void {
    this.#finish(!this.#thrown && this.#callbackCount === 1 && this.#failure === null &&
      this.#count === this.#original!.length && this.#bytes!.equals(this.#original!) ? 'confirmed' : 'failed');
  }
  #arm(): void {
    if (this.#outcome !== null) return;
    const remaining = this.#deadline - process.hrtime.bigint();
    if (remaining <= 0n) { this.#finish('deadline'); return; }
    this.#timer = setTimeout(() => { this.#timer = null; this.#arm(); }, Math.max(1, Math.ceil(Number(remaining) / 1_000_000)));
  }
  #finish(proposed: CurrentImagePublication['outcome']): void {
    if (this.#outcome !== null) return;
    const now = process.hrtime.bigint(); this.#outcome = now >= this.#deadline ? 'deadline' : proposed;
    if (this.#timer !== null) { clearTimeout(this.#timer); this.#timer = null; }
    this.#resolve!(Object.freeze({ schema: 'ktchecker-current-image-diagnostic-publication/1', outcome: this.#outcome,
      writeAttempted: this.#attempted, elapsedMillis: Math.max(0, Number(now - this.#entered) / 1_000_000),
      originalPublicationMillis: this.#maximumMillis, executionAuthority: false, lifecycleDisposition: 'retained_no_quiescence_claim' }));
  }
  custody(): Readonly<{ attempted: boolean; outcome: CurrentImagePublication['outcome'] | null; bytesRetained: boolean;
    sinkRetained: boolean; sinkReturnRetained: boolean; callbackRetained: boolean; failureRetained: boolean; lateCallbackRetained: boolean }> {
    return Object.freeze({ attempted: this.#attempted, outcome: this.#outcome, bytesRetained: this.#bytes !== null,
      sinkRetained: this.#sink !== null, sinkReturnRetained: this.#sinkReturned, callbackRetained: this.#callbackCount > 0,
      failureRetained: this.#thrown || this.#callbackCount > 0 && this.#failure !== null,
      lateCallbackRetained: this.#outcome === 'deadline' && this.#callbackCount > 0 });
  }
}
Object.freeze(RetainedCurrentImagePublication.prototype); Object.freeze(RetainedCurrentImagePublication);
