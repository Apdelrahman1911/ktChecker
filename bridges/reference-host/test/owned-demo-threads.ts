import { digest, HostError } from '../src/journal.js';

interface CleanupPort {
  connect(): Promise<void>;
  request(method: string, params: Record<string, unknown>): Promise<unknown>;
  close(): Promise<void>;
}
const MAX_CREATIONS = 8;
const SAFE_FAILURE_CODES = new Set([
  'codex_request_rejected', 'codex_request_timeout_outcome_unknown', 'codex_disconnected', 'codex_unavailable',
  'codex_connection_state', 'codex_protocol_error', 'codex_response_too_large', 'codex_request_too_large',
  'codex_closed', 'codex_backpressure',
]);
function safeFailure(error: unknown): string {
  return error instanceof HostError && SAFE_FAILURE_CODES.has(error.code) ? error.code : 'unclassified_cleanup_failure';
}
interface CleanupStepEvidence {
  outcome: 'not_attempted' | 'confirmed' | 'unconfirmed';
  reasonCode: string | null;
}
interface CleanupCloseEvidence {
  /** A returned wrapper is not a process-exit or provider/native quiescence receipt. */
  outcome: 'not_attempted' | 'returned' | 'unconfirmed';
  reasonCode: string | null;
}
interface OwnedThreadDeletionEvidence {
  /** Order of confirmed ownership, NOT an inferred creation-attempt or consumer identity. */
  ownedThreadOrdinal: number;
  threadIdDigest: string;
  deletionAttempted: boolean;
  outcome: 'not_attempted' | 'confirmed_deleted' | 'rejected' | 'timeout_outcome_unknown' |
    'disconnected_outcome_unknown' | 'unconfirmed';
  reasonCode: string | null;
}
export interface OwnedThreadCleanupEvidence {
  knownOwnedThreads: number;
  deletionAttempts: number;
  confirmedDeleted: number;
  deletionUnconfirmed: number;
  unknownCreationOutcomes: number;
  /** Only this run's thread-deletion obligations. Never proof of turn/native quiescence. */
  allConfirmed: boolean;
  confirmationScope: 'owned_thread_deletions_only_not_turn_or_native_quiescence';
  portCreation: Readonly<CleanupStepEvidence>;
  connection: Readonly<CleanupStepEvidence>;
  controlClose: Readonly<CleanupCloseEvidence>;
  threads: readonly Readonly<OwnedThreadDeletionEvidence>[];
}
function deleteFailureOutcome(code: string): OwnedThreadDeletionEvidence['outcome'] {
  if (code === 'codex_request_rejected') return 'rejected';
  if (code === 'codex_request_timeout_outcome_unknown') return 'timeout_outcome_unknown';
  if (code === 'codex_disconnected' || code === 'codex_closed') return 'disconnected_outcome_unknown';
  return 'unconfirmed';
}
function emptyJsonObject(value: unknown): boolean {
  return value !== null && typeof value === 'object' && Object.getPrototypeOf(value) === Object.prototype &&
    Reflect.ownKeys(value).length === 0;
}

/** Disposable demonstration ownership only. Deletion is attempted once per ID
 * actually returned by this run's thread/start, including concurrent/repeated cleanup calls.
 * Unknown creations are retained for owner recovery, never guessed via listing or history.
 * Only bounded outcome codes and ID digests leave the ownership record, never server errors.
 */
export class OwnedDemoThreads {
  private readonly confirmed = new Set<string>();
  private creationAttempts = 0;
  private confirmedCreations = 0;
  private cleanupStarted = false;
  private cleanupPromise: Promise<OwnedThreadCleanupEvidence> | undefined;
  beginCreation(): void {
    if (this.cleanupStarted) throw new HostError('demo_thread_cleanup_started');
    if (this.creationAttempts >= MAX_CREATIONS) throw new HostError('demo_thread_creation_bound');
    this.creationAttempts++;
  }
  confirmCreation(threadId: string): void {
    if (this.cleanupStarted) throw new HostError('demo_thread_cleanup_started');
    if (typeof threadId !== 'string' || !threadId || threadId.length > 256 || /[\s\u0000-\u001f\u007f]/u.test(threadId) ||
        this.confirmedCreations >= this.creationAttempts) throw new HostError('demo_thread_creation_not_started');
    // A duplicated confirmation cannot consume a second, potentially unknown creation.
    if (this.confirmed.has(threadId)) throw new HostError('demo_thread_creation_duplicate');
    this.confirmed.add(threadId); this.confirmedCreations++;
  }
  cleanup(createPort: () => CleanupPort): Promise<OwnedThreadCleanupEvidence> {
    if (!this.cleanupPromise) {
      this.cleanupStarted = true;
      // Publish the single promise before even a synchronously reentrant factory can run.
      this.cleanupPromise = Promise.resolve().then(() => this.performCleanup(createPort));
    }
    return this.cleanupPromise;
  }
  private async performCleanup(createPort: () => CleanupPort): Promise<OwnedThreadCleanupEvidence> {
    const ids = [...this.confirmed];
    const threads: OwnedThreadDeletionEvidence[] = ids.map((threadId, index) => ({
      ownedThreadOrdinal: index + 1, threadIdDigest: digest(threadId), deletionAttempted: false,
      outcome: 'not_attempted', reasonCode: null,
    }));
    const portCreation: CleanupStepEvidence = { outcome: 'not_attempted', reasonCode: null };
    const connection: CleanupStepEvidence = { outcome: 'not_attempted', reasonCode: null };
    const controlClose: CleanupCloseEvidence = { outcome: 'not_attempted', reasonCode: null };
    const unknownCreations = this.creationAttempts - this.confirmedCreations;
    if (ids.length) {
      let port: CleanupPort | undefined;
      try {
        try { port = createPort(); portCreation.outcome = 'confirmed'; }
        catch (error) { portCreation.outcome = 'unconfirmed'; portCreation.reasonCode = safeFailure(error); }
        if (port) {
          try { await port.connect(); connection.outcome = 'confirmed'; }
          catch (error) { connection.outcome = 'unconfirmed'; connection.reasonCode = safeFailure(error); }
          if (connection.outcome === 'confirmed') for (let index = 0; index < ids.length; index++) {
            const row = threads[index]!;
            row.deletionAttempted = true;
            try {
              const response = await port.request('thread/delete', { threadId: ids[index]! });
              if (emptyJsonObject(response)) row.outcome = 'confirmed_deleted';
              else { row.outcome = 'unconfirmed'; row.reasonCode = 'thread_delete_response_not_empty_object'; }
            } catch (error) {
              row.reasonCode = safeFailure(error); row.outcome = deleteFailureOutcome(row.reasonCode);
            }
          }
        }
        for (const row of threads) if (!row.deletionAttempted) row.reasonCode = connection.reasonCode ?? portCreation.reasonCode;
      } finally {
        if (port) {
          try { await port.close(); controlClose.outcome = 'returned'; }
          catch (error) { controlClose.outcome = 'unconfirmed'; controlClose.reasonCode = safeFailure(error); }
        }
      }
    }
    const deleted = threads.filter(row => row.outcome === 'confirmed_deleted').length;
    return Object.freeze({ knownOwnedThreads: ids.length,
      deletionAttempts: threads.filter(row => row.deletionAttempted).length, confirmedDeleted: deleted,
      deletionUnconfirmed: ids.length - deleted, unknownCreationOutcomes: unknownCreations,
      allConfirmed: deleted === ids.length && unknownCreations === 0,
      confirmationScope: 'owned_thread_deletions_only_not_turn_or_native_quiescence',
      portCreation: Object.freeze(portCreation), connection: Object.freeze(connection), controlClose: Object.freeze(controlClose),
      threads: Object.freeze(threads.map(row => Object.freeze(row))),
    });
  }
}

/** A rejected background loop must not skip host or journal closure. Returning from
 * these wrappers is still not evidence of provider/native-group quiescence. */
export async function closeDemoConsumer(
  stopPolling: () => Promise<void>, closeHost: () => Promise<void>, closeJournal: () => void,
): Promise<void> {
  try { await stopPolling(); }
  finally { try { await closeHost(); } finally { closeJournal(); } }
}
