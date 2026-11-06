import { test } from 'node:test';
import assert from 'node:assert/strict';
import { assertPrivateStatePlatform, nativePrivateStatePath, PrivateStatePlatformError } from '../src/private-state.js';

test('Windows owner-private state fails closed until actual ACL validation is qualified', () => {
  assert.throws(() => assertPrivateStatePlatform('win32'), /private_state_platform_unqualified/);
  assert.doesNotThrow(() => assertPrivateStatePlatform('linux'));
});

test('Darwin default guard remains unavailable without opt-in (guard control, not native qualification)', () => {
  const previous = process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL;
  delete process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL;
  try {
    assert.throws(() => assertPrivateStatePlatform('darwin'), error =>
      error instanceof PrivateStatePlatformError && error.reason === 'darwin_native_backend_opt_in_required');
  } finally {
    if (previous === undefined) delete process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL;
    else process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL = previous;
  }
});

test('native owner path normalization never erases dot-component ambiguities', () => {
  for (const path of ['', '/owner/../state', '/owner/./state', '/owner/\0state'])
    assert.throws(() => nativePrivateStatePath(path), /unsafe_state_path/);
});

test('Darwin root guard precedes artifact access (mock guard, not native qualification)', () => {
  const descriptor = Object.getOwnPropertyDescriptor(process, 'getuid');
  const previous = process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL;
  Object.defineProperty(process, 'getuid', { configurable: true, value: () => 0 });
  process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL = '1';
  try {
    assert.throws(() => assertPrivateStatePlatform('darwin'), error =>
      error instanceof PrivateStatePlatformError && error.reason === 'darwin_native_identity_unqualified');
  } finally {
    if (descriptor) Object.defineProperty(process, 'getuid', descriptor);
    else delete process.getuid;
    if (previous === undefined) delete process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL;
    else process.env.KTC_DARWIN_PRIVATE_STATE_EXPERIMENTAL = previous;
  }
});
