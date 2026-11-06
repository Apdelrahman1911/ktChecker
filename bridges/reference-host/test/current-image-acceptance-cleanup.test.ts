/** SYNTHETIC cleanup controls: fake close handles and exclusively NEW temp roots.
 * No InboxJournal/SQLite, acceptance runner, compiler/provider, owner, host or model.
 * These assertions cover lifecycle semantics, not GC behavior or an OOM cure.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { syntheticJournalCleanup } from './current-image-acceptance-cleanup.js';

async function cleanupRoots(t: { after(fn: () => Promise<unknown> | void): void }) {
  const root = await mkdtemp(join(tmpdir(), 'ktchecker-current-image-cleanup-SYNTHETIC-'));
  // This outer test root is exclusively new; never delete tmpdir() or any shared root.
  t.after(() => rm(root, { recursive: true, force: true }));
  const selected = join(root, 'selected'), unrelated = join(root, 'unrelated');
  await mkdir(selected); await mkdir(unrelated);
  const sentinel = 'SYNTHETIC cleanup sentinel\n';
  await writeFile(join(selected, 'sentinel'), sentinel, { flag: 'wx' });
  await writeFile(join(unrelated, 'sentinel'), sentinel, { flag: 'wx' });
  return { selected, unrelated, sentinel };
}

test('synthetic cleanup closes before removing exactly the selected root, not its sibling', async t => {
  const roots = await cleanupRoots(t); let closes = 0;
  const cleanup = syntheticJournalCleanup({ close() {
    closes++;
    assert.equal(readFileSync(join(roots.selected, 'sentinel'), 'utf8'), roots.sentinel);
    assert.equal(readFileSync(join(roots.unrelated, 'sentinel'), 'utf8'), roots.sentinel);
  } }, roots.selected);
  assert.equal(closes, 0);
  assert.equal(await readFile(join(roots.selected, 'sentinel'), 'utf8'), roots.sentinel);
  await cleanup();
  assert.equal(closes, 1);
  await assert.rejects(readFile(roots.selected), { code: 'ENOENT' });
  assert.equal(await readFile(join(roots.unrelated, 'sentinel'), 'utf8'), roots.sentinel);
});

test('synthetic cleanup is one-shot for concurrent/reentrant calls and preserves a later replacement', async t => {
  const roots = await cleanupRoots(t); let closes = 0, reentered: Promise<void> | undefined;
  let cleanup: () => Promise<void>;
  cleanup = syntheticJournalCleanup({ close() { closes++; reentered = cleanup(); } }, roots.selected);
  const first = cleanup(), concurrent = cleanup();
  assert.equal(concurrent, first);
  await first;
  assert.equal(reentered, first); assert.equal(closes, 1);
  await assert.rejects(readFile(roots.selected), { code: 'ENOENT' });
  await mkdir(roots.selected);
  await writeFile(join(roots.selected, 'replacement'), roots.sentinel, { flag: 'wx' });
  assert.equal(cleanup(), first);
  await cleanup();
  assert.equal(closes, 1);
  assert.equal(await readFile(join(roots.selected, 'replacement'), 'utf8'), roots.sentinel);
  assert.equal(await readFile(join(roots.unrelated, 'sentinel'), 'utf8'), roots.sentinel);
});

test('synthetic cleanup propagates one close failure without removing anything or retrying', async t => {
  const roots = await cleanupRoots(t), failure = new Error('synthetic close failure'); let closes = 0;
  const cleanup = syntheticJournalCleanup({ close() { closes++; throw failure; } }, roots.selected);
  const first = cleanup();
  assert.equal(cleanup(), first);
  await assert.rejects(first, error => error === failure);
  assert.equal(closes, 1);
  assert.equal(await readFile(join(roots.selected, 'sentinel'), 'utf8'), roots.sentinel);
  assert.equal(await readFile(join(roots.unrelated, 'sentinel'), 'utf8'), roots.sentinel);
  assert.equal(cleanup(), first);
  await assert.rejects(cleanup(), error => error === failure);
  assert.equal(closes, 1);
  assert.equal(await readFile(join(roots.selected, 'sentinel'), 'utf8'), roots.sentinel);
});

test('synthetic cleanup propagates removal rejection once and never deletes a valid path prefix', async t => {
  const roots = await cleanupRoots(t); let closes = 0;
  // A NUL makes fs reject before deletion; no permission/root-dependent failure setup.
  const cleanup = syntheticJournalCleanup({ close() { closes++; } }, roots.selected + '\0');
  const first = cleanup();
  await assert.rejects(first, { code: 'ERR_INVALID_ARG_VALUE' });
  assert.equal(closes, 1); assert.equal(cleanup(), first);
  await assert.rejects(cleanup(), { code: 'ERR_INVALID_ARG_VALUE' });
  assert.equal(closes, 1);
  assert.equal(await readFile(join(roots.selected, 'sentinel'), 'utf8'), roots.sentinel);
  assert.equal(await readFile(join(roots.unrelated, 'sentinel'), 'utf8'), roots.sentinel);
});
