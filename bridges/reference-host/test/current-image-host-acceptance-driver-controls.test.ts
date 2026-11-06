/** Pure driver selectors and diagnostic DATA only. Importing the executable module is inert;
 * these controls NEVER call runNewCurrentImageHostDriver, prepare/open, owner, host or model. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canonical, digest } from '../src/journal.js';
import { assertCurrentImageControllerClosure, requiredCurrentImageControllerModules, type CurrentImageSelection } from './current-image-acceptance-image.js';
import { assertCurrentImageDriverImage, currentImageDriverDiagnostic, parseCurrentImageDriverArgs,
  requiredCurrentImageDriverModules, type CurrentImageDriverReceipt } from './current-image-host-acceptance-driver.js';
import { currentImagePublicFixtureDigest, CURRENT_IMAGE_TRUSTED_HOST_POLICY } from './current-image-host-acceptance-fixture.js';
import { CURRENT_IMAGE_PUBLICATION_BYTES } from './current-image-host-acceptance-observer.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
const args = () => ['--selection', '/synthetic-public/selection.json', '--selection-digest', digest('synthetic-selection'),
  '--approve-new-public-fixture-trusted-host'];
/** Deliberately NOT a selected, disk-verified or independently approved image. Shape-only DATA. */
function imageSelection(): CurrentImageSelection {
  const modules = [...requiredCurrentImageControllerModules(), ...requiredCurrentImageDriverModules()];
  const pin = (label: string, path: string) => ({ label, path, digest: digest('synthetic-pin-' + label), bytes: 1 });
  return { schema: 'ktchecker-current-image-owner-selection/1', approvalId: 'synthetic-nonauthority',
    controllerOutputs: modules.map(module => pin(module.source.replace(/\.ts$/u, '.js'), module.path)),
    sourceInputs: modules.map(module => pin(module.source, '/synthetic-source/' + module.source)),
    installedOutputs: [pin('synthetic-owner.jar', '/synthetic-installed/owner.jar')], compilerArtifactPaths: ['/synthetic-installed/owner.jar'],
    node: pin('synthetic-node', '/synthetic-selected/node'), nodeVersion: '24.20.0', codex: pin('synthetic-codex', '/synthetic-selected/codex'),
    buildRecord: pin('synthetic-build', '/synthetic-selected/build.json'), ownerCodeImageDigest: digest('synthetic-owner-code') };
}
/** A forged test shape deliberately asks the diagnostic encoder to suppress a success claim. */
function receipt(): CurrentImageDriverReceipt {
  return { schema: 'ktchecker-current-image-driver-observation/1', status: 'verified_delivery_final_denied', stage: 'runner_returned',
    observation: { schema: 'ktchecker-current-image-promise-observation/1', outcome: 'returned', elapsedMillis: 1,
      originalObservationMillis: 900_000, executionAuthority: false, lifecycleDisposition: 'retained_no_quiescence_claim' },
    launchSelectionDigest: digest('synthetic-launch'), attemptIdDigest: digest('synthetic-attempt'), approvalDigest: digest('synthetic-approval'),
    fixtureDigest: currentImagePublicFixtureDigest(), trustedHostPolicy: CURRENT_IMAGE_TRUSTED_HOST_POLICY,
    runnerReportDigest: digest('synthetic-not-a-real-runner-return'), runnerReturnObserved: true, failure: null,
    actualHostDeliveryVerified: true, privateReceiptCommitted: false, privateCandidateFileDigest: null, diagnosticLineDigest: null, publication: null,
    driverReturnWithinOriginal900Attested: false, publicationGrantsExecutionTime: false,
    privateReceiptRequiresReturnedDriverObservation: true, publicReadIsolationAttested: false, qualified: false, usefulClean: false,
    internalInferenceRequestTrace: false, actionPerformed: false, lifecycleDisposition: 'retained_no_quiescence_claim', cleanupOrRetryAuthorized: false };
}

test('driver opt-in accepts only exact ordered selected path, digest and explicit new public trusted-host flag as DATA', linuxOnly, () => {
  const parsed = parseCurrentImageDriverArgs(args());
  assert.deepEqual(parsed, { selectionPath: '/synthetic-public/selection.json', selectionDigest: digest('synthetic-selection') });
  assert(Object.isFrozen(parsed)); assert(!('factory' in parsed)); assert(!('clock' in parsed)); assert(!('launch' in parsed));
});

test('driver selection path retains Unicode quote dollar and bracket characters as data without shell interpretation', linuxOnly, () => {
  const input = args(); input[1] = '/synthetic-public/Δ-\'"-$-[literal]/selection.json';
  assert.equal(parseCurrentImageDriverArgs(input).selectionPath, input[1]);
  // This Linux argument-shape control does not address the held native Windows proof.
});
for (const [name, change] of [
  ['missing consent flag', (value: string[]) => { value.pop(); }],
  ['historical reuse flag', (value: string[]) => { value[4] = '--resume-old-attempt'; }],
  ['replacement component arg', (value: string[]) => { value.push('--factory', '/synthetic/factory'); }],
  ['reordered options', (value: string[]) => { [value[0], value[2]] = [value[2]!, value[0]!]; }],
  ['relative selected path', (value: string[]) => { value[1] = 'selection.json'; }],
  ['non-normalized selected path', (value: string[]) => { value[1] = '/synthetic-public/../selection.json'; }],
  ['root selected path', (value: string[]) => { value[1] = '/'; }],
  ['naked selected digest', (value: string[]) => { value[3] = 'f'.repeat(64); }],
  ['alternate digest option', (value: string[]) => { value[2] = '--selection-sha256'; }],
] as const) test('driver args refuse ' + name + ' before any selected input is opened', linuxOnly, () => {
  const value = args(); change(value); assert.throws(() => parseCurrentImageDriverArgs(value));
});

test('driver code closure adds all three source/output pairs without shrinking the existing mandatory28', () => {
  const base = requiredCurrentImageControllerModules(), additional = requiredCurrentImageDriverModules();
  assert.equal(base.length, 28); assert.equal(additional.length, 3);
  assert.deepEqual(additional.map(module => module.source), ['current-image-host-acceptance-driver', 'current-image-host-acceptance-fixture',
    'current-image-host-acceptance-observer'].map(name => 'bridges/reference-host/test/' + name + '.ts'));
  assert.equal(new Set([...base, ...additional].map(module => module.path)).size, 31);
  assert.equal(new Set([...base, ...additional].map(module => module.source)).size, 31);
  assert(Object.isFrozen(additional)); assert(additional.every(Object.isFrozen));
  const selection = imageSelection(); assertCurrentImageControllerClosure(selection); assertCurrentImageDriverImage(selection);
});
for (const index of [0, 1, 2]) for (const role of ['source', 'output'] as const)
  test('driver code closure refuses missing ' + role + ' for additional module ' + index, () => {
    const selection = imageSelection(), selected = requiredCurrentImageDriverModules()[index]!;
    const changed = role === 'source' ? { ...selection, sourceInputs: selection.sourceInputs.filter(pin => pin.label !== selected.source) } :
      { ...selection, controllerOutputs: selection.controllerOutputs.filter(pin => pin.path !== selected.path) };
    assertCurrentImageControllerClosure(changed); assert.throws(() => assertCurrentImageDriverImage(changed), /driver_image_/);
  });

test('driver-only code pins cannot satisfy the preexisting mandatory controller closure', () => {
  const selection = imageSelection(), added = requiredCurrentImageDriverModules();
  const changed = { ...selection, controllerOutputs: selection.controllerOutputs.filter(pin => added.some(module => module.path === pin.path)),
    sourceInputs: selection.sourceInputs.filter(pin => added.some(module => module.source === pin.label)) };
  assertCurrentImageDriverImage(changed); assert.throws(() => assertCurrentImageControllerClosure(changed), /driver_image_omitted/);
});

for (const outcome of ['returned', 'rejected', 'deadline'] as const)
  test('pre-return diagnostic is non-authoritative incomplete DATA even for forged successful-looking ' + outcome + ' receipt', () => {
    const forged = { ...receipt(), observation: { ...receipt().observation, outcome } }, diagnostic = currentImageDriverDiagnostic(forged);
    assert.equal(diagnostic.schema, 'ktchecker-current-image-driver-candidate/1'); assert.equal(diagnostic.kind, 'candidate_before_driver_return');
    assert.equal(diagnostic.status, 'incomplete_or_failed'); assert.equal(diagnostic.actualHostDeliveryVerified, false);
    assert.equal(diagnostic.requiresReturnedDriverObservation, true); assert.equal(diagnostic.publicationAcknowledgedByReader, false);
    assert.equal(diagnostic.publicReadIsolationAttested, false); assert.equal(diagnostic.qualified, false); assert.equal(diagnostic.actionPerformed, false);
    assert.equal(diagnostic.cleanupOrRetryAuthorized, false); assert.equal(diagnostic.lifecycleDisposition, 'retained_no_quiescence_claim');
    assert.equal(diagnostic.candidateReceiptDigest, digest(canonical(forged))); assert(Object.isFrozen(diagnostic));
    assert(Buffer.byteLength(canonical(diagnostic) + '\n') < CURRENT_IMAGE_PUBLICATION_BYTES);
    assert(!('driverReceipt' in diagnostic)); assert(!('runnerReport' in diagnostic)); assert(!('clock' in diagnostic));
    assert(!canonical(diagnostic).includes('verified_delivery_final_denied'));
  });

test('diagnostic candidate digest distinguishes returned and unconfirmed publication DATA without treating a file as a driver return', () => {
  const first = receipt(), changed = { ...first, status: 'incomplete_or_failed' as const,
    failure: 'current_image_driver_publication_unconfirmed', actualHostDeliveryVerified: false };
  const a = currentImageDriverDiagnostic(first), b = currentImageDriverDiagnostic(changed);
  assert.notEqual(a.candidateReceiptDigest, b.candidateReceiptDigest); assert.equal(a.actualHostDeliveryVerified, false);
  assert.equal(b.actualHostDeliveryVerified, false); assert.equal(b.failure, 'current_image_driver_publication_unconfirmed');
  assert.equal(first.driverReturnWithinOriginal900Attested, false); assert.equal(first.publicationGrantsExecutionTime, false);
});
