import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { PrivateStatePlatformError } from '@ktchecker/mcp-preview/private-state';
import { InboxJournal, privatePath } from '../src/journal.js';
import { CodexStore } from '../src/codex-store.js';
import { CodexHostPort } from '../src/codex-host.js';
import { readHostConfig } from '../src/config.js';

test('Darwin opt-out precedes validation/filesystem/server side effects at public private-state entries (mock guard only)', async () => {
  const descriptor = Object.getOwnPropertyDescriptor(process, 'platform')!;
  const previous = process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL;
  const path = join(tmpdir(), 'ktc-uncreated-darwin-guard-' + randomUUID());
  assert.equal(existsSync(path), false);
  Object.defineProperty(process, 'platform', { ...descriptor, value: 'darwin' });
  delete process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL;
  const unavailable = (error: unknown) => error instanceof PrivateStatePlatformError && error.reason === 'darwin_native_backend_opt_in_required';
  try {
    assert.throws(() => new InboxJournal(path, undefined as never), unavailable);
    assert.throws(() => new CodexStore(path, undefined as never, path, '', 'not:valid'), unavailable);
    assert.throws(() => privatePath(path, true), unavailable);
    await assert.rejects(readHostConfig(path), unavailable);
    // Even reading options/realpath or constructing an app server would fail
    // differently; undefined proves guard ordering without a real owner probe.
    await assert.rejects(CodexHostPort.open(undefined as never), unavailable);
    assert.equal(existsSync(path), false);
  } finally {
    Object.defineProperty(process, 'platform', descriptor);
    if (previous === undefined) delete process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL;
    else process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL = previous;
  }
});
