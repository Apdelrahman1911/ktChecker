/** Test-only cleanup for an exclusively NEW synthetic fixture journal/directory.
 * Never use this for actual launch attempts, uncertain owner/host handles or shared roots.
 * This factory is outside controlFixture's activation: its hook captures only the
 * selected journal/path slot and one completion promise, not the fixture graph.
 */
import { rm } from 'node:fs/promises';

export function syntheticJournalCleanup(journal: { close(): void }, directory: string): () => Promise<void> {
  let retained: { journal: { close(): void }; directory: string } | null = { journal, directory };
  let completion: Promise<void> | null = null;
  return () => {
    // Publish the one attempt before close runs, including for reentrant callers.
    completion ??= Promise.resolve().then(async () => {
      const selected = retained!;
      try {
        selected.journal.close();
        await rm(selected.directory, { recursive: true, force: true });
      } finally {
        // Release only our retained slot after this attempt, even when cleanup fails.
        // A failed close still forbids removal; repeated callers get the same failure.
        retained = null;
      }
    });
    return completion;
  };
}
