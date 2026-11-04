/** Disposable demonstration ownership only. Deletion is attempted once per ID
 * actually returned by this run's thread/start. Unknown creations are explicitly
 * retained for owner recovery, never guessed via thread listing or history.
 */
interface CleanupPort {
  connect(): Promise<void>;
  request(method: string, params: Record<string, unknown>): Promise<unknown>;
  close(): Promise<void>;
}
export class OwnedDemoThreads {
  private readonly confirmed = new Set<string>();
  private creationAttempts = 0;
  private confirmedCreations = 0;
  beginCreation(): void { this.creationAttempts++; }
  confirmCreation(threadId: string): void {
    if (!threadId || this.confirmedCreations >= this.creationAttempts) throw new Error('demo_thread_creation_not_started');
    this.confirmed.add(threadId); this.confirmedCreations++;
  }
  async cleanup(createPort: () => CleanupPort): Promise<Record<string, unknown> & { allConfirmed: boolean }> {
    let attempted = 0, deleted = 0;
    const unknownCreations = this.creationAttempts - this.confirmedCreations;
    if (this.confirmed.size) {
      const port = createPort();
      try {
        await port.connect();
        for (const threadId of this.confirmed) {
          attempted++;
          try { await port.request('thread/delete', { threadId }); deleted++; }
          catch { /* Unknown deletion outcome: never retry or infer success. */ }
        }
      } catch { /* No fallback thread discovery or implicit inference. */ }
      finally { await port.close().catch(() => {}); }
    }
    return { knownOwnedThreads: this.confirmed.size, deletionAttempts: attempted, confirmedDeleted: deleted,
      deletionUnconfirmed: this.confirmed.size - deleted, unknownCreationOutcomes: unknownCreations,
      allConfirmed: deleted === this.confirmed.size && unknownCreations === 0 };
  }
}
