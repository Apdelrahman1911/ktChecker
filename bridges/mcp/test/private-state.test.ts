import { test } from 'node:test';
import assert from 'node:assert/strict';
import { assertPrivateStatePlatform } from '../src/private-state.js';

test('Windows owner-private state fails closed until actual ACL validation is qualified', () => {
  assert.throws(() => assertPrivateStatePlatform('win32'), /private_state_platform_unqualified/);
  assert.doesNotThrow(() => assertPrivateStatePlatform('linux'));
  assert.doesNotThrow(() => assertPrivateStatePlatform('darwin'));
});
