/** PURE SYNTHETIC nominal-authority negatives. Never calls prepare(), open(), a constructor
 * with IO, or a real owner/host/model. A claimed launch record is deliberately NOT a ticket.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CodexHostPort } from '../src/codex-host.js';
import { digest } from '../src/journal.js';
import { deliveryBinding } from './delivery-receipts.js';
import { EXISTING_CODEX_CHILD_FAILURE_POLICY, takeCurrentImageLaunch,
  type CurrentImageLaunchEvidence, type OpenedCurrentImageLaunch } from './current-image-acceptance-launch.js';
import { REQUIRED_PARENT_ADAPTER, runActualCurrentImageAcceptance, type ActualRunOptions } from './current-image-acceptance-runner.js';
import { REAL_CODEX_ELF_BYTES, REAL_CODEX_ELF_DIGEST } from './real-model-codex-selection.js';
import { controlRegistration } from './current-image-acceptance-fixtures.js';

// Compile-time controls only; no value construction or fake authorization path is added.
type MustBeFalse<T extends false> = T;
type EvidenceObjectIsNotNominal = MustBeFalse<{ evidence: CurrentImageLaunchEvidence } extends OpenedCurrentImageLaunch ? true : false>;
type CallerCannotSupplyHost = MustBeFalse<'host' extends keyof ActualRunOptions ? true : false>;
type CallerCannotSupplyClock = MustBeFalse<'clock' extends keyof ActualRunOptions ? true : false>;
type CallerCannotSupplyImage = MustBeFalse<'image' extends keyof ActualRunOptions ? true : false>;
type CallerCannotSupplyOwner = MustBeFalse<'adapter' extends keyof ActualRunOptions ? true : false>;
type CallerCannotSupplyJournal = MustBeFalse<'journal' extends keyof ActualRunOptions ? true : false>;

/** Shape copied by an adversarial test, NOT observed execution or issued launch evidence. */
function unissuedSyntheticLaunchClaim(): CurrentImageLaunchEvidence {
  const fake = digest('SYNTHETIC UNISSUED LAUNCH CLAIM - not executable selection or authorization');
  return { schema: 'ktchecker-current-image-host-launch/1', attemptId: 'synthetic-unissued-attempt', approvalDigest: fake,
    selectionDigest: fake, imageSelectionDigest: fake, independentVerificationFileDigest: fake, authorizationFileDigest: fake,
    authorizationCanonicalDigest: fake, ownerInvocationSelectionDigest: fake, ownerCompilerPolicySelectionDigest: fake,
    ownerCompilerPolicyAuditDigest: fake, constructionOptionsDigest: fake, ownerTransportSelectionFileDigest: fake,
    // Public known pin constants do not attest that this test launched or loaded that binary.
    codexDigest: REAL_CODEX_ELF_DIGEST, codexBytes: REAL_CODEX_ELF_BYTES, args: ['app-server', '--stdio'],
    model: 'gpt-6-astra', effort: 'max', failOnModelReroute: true, overallClockIdentity: fake, originalOverallMillis: 900000,
    beforeHostOpenElapsedMs: 0, hostOpenReturnedElapsedMs: 1, binding: deliveryBinding(controlRegistration().binding),
    hostThreadId: 'synthetic-unissued-thread', threadOrigin: 'own_new_thread_start_confirmed_not_resume',
    codexChildFailurePolicy: EXISTING_CODEX_CHILD_FAILURE_POLICY, loadedImageAttestation: false, internalInferenceRequestTrace: false,
    ownerCompilerRetirementClaim: false, outerCleanupOrRetry: false };
}

test('correct-looking image/model/executable evidence, private-field guesses and JSON copies never issue a nominal ticket', () => {
  const evidence = unissuedSyntheticLaunchClaim(), claim = { evidence, nominal: true, consumed: false };
  const candidates: readonly [string, unknown][] = [
    ['missing', undefined], ['null', null], ['scalar', 'synthetic-ticket'], ['empty', {}], ['evidence only', { evidence }],
    ['private-field guess', claim], ['shallow copy', { ...claim }], ['JSON roundtrip', JSON.parse(JSON.stringify(claim))],
    ['frozen copy', Object.freeze(structuredClone(claim))], ['inherited fields', Object.create(claim)],
    ['claimed consumed', { ...claim, consumed: true }],
    ['claimed stale original900', { ...claim, evidence: { ...evidence, hostOpenReturnedElapsedMs: 900001 } }],
  ];
  for (const [name, candidate] of candidates) {
    let captures = 0;
    const input = candidate as OpenedCurrentImageLaunch;
    assert.throws(() => takeCurrentImageLaunch(input), /actual_fresh_launch_ticket_required/, name);
    assert.throws(() => runActualCurrentImageAcceptance({ launch: input, forbidden: [] }, async () => {
      captures++; throw new Error('SYNTHETIC capture must remain unreachable');
    }), /actual_fresh_launch_ticket_required/, name);
    // A failed unissued claim cannot become valid through repetition. This is NOT a test of
    // consumption of a real ticket: none is minted, inspected or replayed by this module.
    assert.throws(() => takeCurrentImageLaunch(input), /actual_fresh_launch_ticket_required/, name);
    assert.equal(captures, 0, name);
  }
});

test('caller host with CodexHostPort prototype and copied selected-binary claim is rejected before any fake component access', () => {
  const touched = { host: 0, owner: 0, capture: 0, options: 0, claim: 0 };
  // Object.create does not run CodexHostPort.open or its private constructor; instanceof alone
  // would be spoofable, which is precisely why the module-private ticket registry comes first.
  const fakeHost = Object.create(CodexHostPort.prototype) as CodexHostPort;
  for (const key of ['binding', 'server', 'resumed', 'evidence', 'findContext', 'insertContext', 'invokeModel']) {
    Object.defineProperty(fakeHost, key, { get() { touched.host++; throw new Error('SYNTHETIC host must not be consulted'); } });
  }
  assert.equal(fakeHost instanceof CodexHostPort, true);
  const fakeOwnerFactory = { profile: REQUIRED_PARENT_ADAPTER, async open() { touched.owner++; throw new Error('SYNTHETIC owner must not open'); } };
  const claim = { evidence: unissuedSyntheticLaunchClaim(), host: fakeHost, ownerFactory: fakeOwnerFactory, nominal: true };
  const options = { launch: claim };
  for (const key of ['forbidden', 'host', 'clock', 'image', 'journal', 'adapter', 'evidence']) {
    Object.defineProperty(options, key, { get() { touched.options++; throw new Error('SYNTHETIC caller components must not be read'); } });
  }
  assert.throws(() => runActualCurrentImageAcceptance(options as unknown as ActualRunOptions, async () => {
    touched.capture++; throw new Error('SYNTHETIC capture must not run');
  }), /actual_fresh_launch_ticket_required/);
  const proxy = new Proxy(claim, {
    get() { touched.claim++; throw new Error('SYNTHETIC claim getters must not be inspected'); },
    ownKeys() { touched.claim++; throw new Error('SYNTHETIC claim must not be serialized'); },
  });
  assert.throws(() => takeCurrentImageLaunch(proxy as unknown as OpenedCurrentImageLaunch), /actual_fresh_launch_ticket_required/);
  assert.deepEqual(touched, { host: 0, owner: 0, capture: 0, options: 0, claim: 0 });
});

test('a getter advertising a stale selected host is not consulted as nominal authority', () => {
  let reads = 0;
  const candidate = Object.defineProperty({}, 'evidence', { get() { reads++; return unissuedSyntheticLaunchClaim(); } });
  assert.throws(() => takeCurrentImageLaunch(candidate as OpenedCurrentImageLaunch), /actual_fresh_launch_ticket_required/);
  assert.equal(reads, 0);
});
