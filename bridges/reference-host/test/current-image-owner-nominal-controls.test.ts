/** PURE SYNTHETIC authority negatives. Actual factory construction below is inert; its
 * open is NEVER called. No successful prepared launcher/context/adapter/ticket is minted. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { digest } from '../src/journal.js';
import { claimFreshCurrentImageOwnerContext, PreparedCurrentImageLaunch,
  type FreshCurrentImageOwnerContext, type FreshCurrentImageOwnerFactory } from './current-image-acceptance-launch.js';
import { REQUIRED_PARENT_ADAPTER } from './current-image-acceptance-runner.js';
import { isCurrentImageParentAdapterFor, isRetainedCurrentImageOwnerFactory, projectOwnerBrokerParams,
  RetainedCurrentImageOwnerFactory } from './current-image-owner-transport.js';
import type { Registration } from '../src/types.js';
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
test('factory constructor identity is nominal but is NOT a selected launch/context/adapter grant', linuxOnly, () => {
  const factory = new RetainedCurrentImageOwnerFactory('/synthetic-selected/transport.json', digest('SYNTHETIC selected DATA'));
  assert.equal(isRetainedCurrentImageOwnerFactory(factory), true); assert(Object.isFrozen(factory));
  assert(Object.isFrozen(RetainedCurrentImageOwnerFactory)); assert(Object.isFrozen(RetainedCurrentImageOwnerFactory.prototype));
  const descriptor = Object.getOwnPropertyDescriptor(factory, 'open'); assert(descriptor); assert.equal(descriptor.writable, false); assert.equal(descriptor.configurable, false);
  const context = {} as FreshCurrentImageOwnerContext;
  assert.throws(() => claimFreshCurrentImageOwnerContext(context, factory), /actual_fresh_owner_context_required/);
  assert.equal(isCurrentImageParentAdapterFor(factory, factory, context), false);
  class Subclass extends RetainedCurrentImageOwnerFactory {}
  assert.throws(() => new Subclass('/synthetic-selected/transport.json', digest('SYNTHETIC')), /concrete_factory_required/);
});
test('factory profile, inherited methods, cloned private-field guesses and proxies never enroll nominal authority', linuxOnly, () => {
  const factory = new RetainedCurrentImageOwnerFactory('/synthetic-selected/transport.json', digest('SYNTHETIC selected DATA'));
  let reads = 0;
  const advertised = Object.defineProperties({}, {
    profile: { get() { reads++; return REQUIRED_PARENT_ADAPTER; } }, open: { get() { reads++; throw new Error('must not call'); } },
  });
  const proxy = new Proxy(factory, { get() { reads++; throw new Error('must not inspect'); }, ownKeys() { reads++; throw new Error('must not inspect'); } });
  for (const candidate of [null, undefined, {}, { profile: REQUIRED_PARENT_ADAPTER, nominal: true }, advertised, { ...factory },
    Object.create(RetainedCurrentImageOwnerFactory.prototype), Object.create(factory), proxy])
    assert.equal(isRetainedCurrentImageOwnerFactory(candidate), false);
  assert.equal(reads, 0);
});
test('launcher context consumption rejects copied/unissued getter-bearing and proxy contexts without touching caller data', linuxOnly, () => {
  const factory = new RetainedCurrentImageOwnerFactory('/synthetic-selected/transport.json', digest('SYNTHETIC selected DATA'));
  let reads = 0; const raw = {};
  for (const key of ['attemptId', 'overall', 'ownerStateDirectory', 'invocationRoot', 'invocationSelection', 'image',
    'imageSelectionPath', 'ownerCompilerPolicySelection', 'ownerTransportSelectionPath', 'ownerTransportSelectionDigest'])
    Object.defineProperty(raw, key, { get() { reads++; throw new Error('unissued field must not be read'); } });
  const proxy = new Proxy(raw, { get() { reads++; throw new Error('unissued proxy'); }, ownKeys() { reads++; throw new Error('unissued proxy'); } });
  for (const candidate of [raw, proxy, {}, Object.freeze({ attemptId: 'same-looking', claimed: false, factory })]) {
    assert.throws(() => claimFreshCurrentImageOwnerContext(candidate as FreshCurrentImageOwnerContext, factory), /actual_fresh_owner_context_required/);
    assert.equal(isCurrentImageParentAdapterFor(candidate, factory, raw as FreshCurrentImageOwnerContext), false);
  }
  assert.equal(reads, 0);
});
test('prototype-imitated prepared launch cannot issue a context or call a caller factory', async () => {
  let reads = 0;
  const factory = Object.defineProperties({}, {
    profile: { get() { reads++; return REQUIRED_PARENT_ADAPTER; } }, open: { get() { reads++; throw new Error('must not call'); } },
  }) as FreshCurrentImageOwnerFactory;
  const imitation = Object.create(PreparedCurrentImageLaunch.prototype) as PreparedCurrentImageLaunch;
  await assert.rejects(PreparedCurrentImageLaunch.prototype.open.call(imitation, factory), /actual_prepared_launch_required/);
  await assert.rejects(PreparedCurrentImageLaunch.prototype.open.call(new Proxy(imitation, { get() { reads++; throw new Error('must not read'); } }), factory), /actual_prepared_launch_required/);
  assert.equal(reads, 0); assert(Object.isFrozen(PreparedCurrentImageLaunch)); assert(Object.isFrozen(PreparedCurrentImageLaunch.prototype));
});

function registration(): Registration {
  return { contractVersion: PREVIEW_CONTRACT, binding: { ownerId: 'synthetic-owner', workspaceId: 'synthetic-workspace', worktreeId: 'synthetic-worktree',
    clientId: 'synthetic-client', sessionId: 'synthetic-session', lineageId: 'synthetic-lineage', generation: 1 },
    client: { clientId: 'synthetic-client', token: 'SYNTHETIC_CLIENT_NOT_AN_ACTUAL_CREDENTIAL' },
    adapter: { clientId: 'synthetic-client', token: 'SYNTHETIC_ADAPTER_NOT_AN_ACTUAL_CREDENTIAL' } };
}
test('broker projection preserves identity-equal original credential objects without hashing or cloning their contents', () => {
  const original = registration(); let credentialReads = 0;
  const client = new Proxy(original.client, { get() { credentialReads++; throw new Error('credential contents must not be read'); },
    ownKeys() { credentialReads++; throw new Error('credential must not be cloned'); } });
  const value = { ...original, client };
  const projected = projectOwnerBrokerParams('review.events', { credential: client, cursor: null, limit: 500 }, value);
  assert.equal(projected.credential, client); assert.equal(credentialReads, 0);
  assert.throws(() => projectOwnerBrokerParams('review.events', { credential: original.client, cursor: null, limit: 500 }, value), /original_credential_required/);
  assert.equal(credentialReads, 0);
});
test('broker projection accepts only exact method-scoped DATA fields and separates adapter/client identity', () => {
  const r = registration(), receipt = { receiptId: 'synthetic-receipt', contextDigest: digest('SYNTHETIC context'), contextItemId: 'synthetic-item', insertedAt: 'synthetic-time' };
  const projected = projectOwnerBrokerParams('review.ack', { credential: r.adapter, eventIds: ['synthetic-event'], receipt }, r);
  assert.equal(projected.credential, r.adapter); assert.deepEqual(projected.receipt, receipt); assert.notEqual(projected.receipt, receipt);
  for (const method of ['owner.open', 'owner.configureCompilerPolicy', 'owner.submitCompletedPatch', 'owner.status', 'close', 'cancel', 'submit'])
    assert.throws(() => projectOwnerBrokerParams(method, { credential: r.client }, r), /broker_method_scope/);
  assert.throws(() => projectOwnerBrokerParams('review.ack', { credential: r.client, eventIds: ['synthetic-event'], receipt }, r), /original_credential_required/);
  assert.throws(() => projectOwnerBrokerParams('review.ack', { credential: { ...r.adapter }, eventIds: ['synthetic-event'], receipt }, r), /original_credential_required/);
  assert.throws(() => projectOwnerBrokerParams('review.ack', { credential: r.adapter, eventIds: ['synthetic-event', 'synthetic-event'], receipt }, r), /broker_event_ids/);
  assert.throws(() => projectOwnerBrokerParams('gate.prepare', { credential: r.client, authorizeOwnerPolicy: true }, r), /unexpected_fields/);
  assert.throws(() => projectOwnerBrokerParams('review.events', { credential: r.client, cursor: null, limit: 501 }, r), /integer/);
});
