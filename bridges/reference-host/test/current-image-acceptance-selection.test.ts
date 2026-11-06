/** Pure SYNTHETIC selection/audit controls. Never calls prepare/open, runs an executable,
 * creates an owner, configures a real rule, or obtains any actual-model witness.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonical, digest } from '../src/journal.js';
import { REAL_CODEX_ELF_BYTES, REAL_CODEX_ELF_DIGEST } from './real-model-codex-selection.js';
import { assertCurrentImageControllerClosure, requiredCurrentImageControllerModules, validateImageSelection,
  type CurrentImageSelection, type ImagePin } from './current-image-acceptance-image.js';
import { EXISTING_CODEX_CHILD_FAILURE_POLICY, selectedCurrentImageHostOptions, validateCurrentImageLaunchSelection,
  type CurrentImageLaunchSelection, type OpenedCurrentImageLaunch } from './current-image-acceptance-launch.js';
import { validateAcceptanceSelections, runActualCurrentImageAcceptance } from './current-image-acceptance-runner.js';
import { validateOwnerCompilerPolicySelection, verifyOwnerCompilerPolicyAudit,
  type OwnerCompilerPolicyAudit, type OwnerCompilerPolicySelection } from './current-image-acceptance-policy.js';
import { CONTROL_COMPILER_RULES, CONTROL_INVOCATION, CONTROL_POLICY, controlAuthorization, controlPolicyAudit, controlRegistration,
  type FixtureValue } from './current-image-acceptance-fixtures.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
function launchSelection(): CurrentImageLaunchSelection {
  return { schema: 'ktchecker-current-image-fresh-launch-selection/1', attemptId: 'synthetic-launch-attempt', approvalDigest: digest('synthetic-approval'),
    runRoot: '/synthetic-launch/new-only', executable: '/synthetic-selected/codex', args: ['app-server', '--stdio'], model: 'gpt-6-astra', effort: 'max',
    codexChildFailurePolicy: EXISTING_CODEX_CHILD_FAILURE_POLICY,
    imageSelectionPath: '/synthetic-selected/image.json', imageSelectionDigest: digest('synthetic-image-selection-file'),
    authorizationPath: '/synthetic-selected/authorization.json', authorizationFileDigest: digest('synthetic-authorization-file'),
    ownerInvocationSelectionPath: '/synthetic-selected/invocation.json', ownerInvocationSelectionFileDigest: digest('synthetic-invocation-file'),
    ownerCompilerPolicySelectionPath: '/synthetic-selected/policy.json', ownerCompilerPolicySelectionFileDigest: digest('synthetic-policy-file'),
    ownerTransportSelectionPath: '/synthetic-selected/owner-transport.json', ownerTransportSelectionFileDigest: digest('synthetic-transport-file'),
    independentVerificationPath: '/synthetic-selected/independent-verification.json', independentVerificationFileDigest: digest('synthetic-independent-file') };
}
const codexPin = (path: string): ImagePin => ({ label: 'codex-elf', path, digest: REAL_CODEX_ELF_DIGEST, bytes: REAL_CODEX_ELF_BYTES });

test('fixed selected absolute host construction uses max, no reroute, no dynamic tools or PATH fallback', linuxOnly, () => {
  const selection = launchSelection(), options = selectedCurrentImageHostOptions(selection, codexPin(selection.executable), controlRegistration().binding);
  assert.deepEqual(options.server, { executable: selection.executable, args: ['app-server', '--stdio'], timeoutMs: 15000,
    maxLineBytes: 16 * 1024 * 1024, maxPublicBytes: 2 * 1024 * 1024, failOnModelReroute: true });
  assert.equal(options.effort, 'max'); assert.equal(options.cwd, selection.runRoot + '/public-workspace');
  assert.equal(options.directory, selection.runRoot + '/host-state'); assert.equal(options.dynamicTools, undefined);
  assert(Object.isFrozen(options)); assert(Object.isFrozen(options.server)); assert(Object.isFrozen(options.server!.args));
  assert.equal(selection.codexChildFailurePolicy, EXISTING_CODEX_CHILD_FAILURE_POLICY);
});
test('selected executable path preserves Unicode quote dollar and bracket data without a shell', linuxOnly, () => {
  const original = launchSelection(), executable = '/synthetic-selected/Unicode-Δ-\'"-$-[literal]/codex';
  const selection = { ...original, executable }, options = selectedCurrentImageHostOptions(selection, codexPin(executable), controlRegistration().binding);
  assert.equal(options.server!.executable, executable); assert.deepEqual(options.server!.args, ['app-server', '--stdio']);
  // Linux pure construction only. The native Windows readonly-ancestor/quote path hold is unchanged.
});
for (const [name, mutation] of [
  ['PATH executable', (value: FixtureValue) => { value.executable = 'codex'; }],
  ['shell-shaped args', (value: FixtureValue) => { value.args = ['-c', 'codex app-server --stdio']; }],
  ['extra config arg', (value: FixtureValue) => { value.args.push('--config'); }],
  ['alternate model', (value: FixtureValue) => { value.model = 'another-model'; }],
  ['lower effort', (value: FixtureValue) => { value.effort = 'low'; }],
  ['undisclosed own-child policy', (value: FixtureValue) => { delete value.codexChildFailurePolicy; }],
  ['pretended zero signals', (value: FixtureValue) => { value.codexChildFailurePolicy = 'no_signals'; }],
  ['selected file inside new root', (value: FixtureValue) => { value.authorizationPath = value.runRoot + '/authorization.json'; }],
  ['policy and execution selection alias', (value: FixtureValue) => { value.ownerCompilerPolicySelectionPath = value.ownerInvocationSelectionPath; }],
  ['transport not independently selected', (value: FixtureValue) => { delete value.ownerTransportSelectionFileDigest; }],
  ['transport path alias', (value: FixtureValue) => { value.ownerTransportSelectionPath = value.ownerInvocationSelectionPath; }],
  ['transport file inside run root', (value: FixtureValue) => { value.ownerTransportSelectionPath = value.runRoot + '/transport.json'; }],
  ['naked transport hash', (value: FixtureValue) => { value.ownerTransportSelectionFileDigest = 'f'.repeat(64); }],
  ['missing independent verification selection', (value: FixtureValue) => { delete value.independentVerificationFileDigest; }],
  ['extra retry option', (value: FixtureValue) => { value.retry = true; }],
] as const) test('launch selection rejects ' + name + ' without host execution', linuxOnly, () => {
  const value = structuredClone(launchSelection()) as unknown as FixtureValue; mutation(value);
  assert.throws(() => validateCurrentImageLaunchSelection(value as CurrentImageLaunchSelection));
});
test('a different image executable path, digest or size cannot reach host construction', linuxOnly, () => {
  const selection = launchSelection(), pin = codexPin(selection.executable), binding = controlRegistration().binding;
  for (const bad of [{ ...pin, path: '/synthetic-other/codex' }, { ...pin, digest: digest('not-selected-elf') }, { ...pin, bytes: 1 }])
    assert.throws(() => selectedCurrentImageHostOptions(selection, bad, binding));
});
test('actual runner has no JSON/caller-port ticket bypass', () => {
  let captures = 0;
  assert.throws(() => runActualCurrentImageAcceptance({ launch: {} as OpenedCurrentImageLaunch, forbidden: [] }, async () => {
    captures++; throw new Error('must not capture a patch');
  }), /actual_fresh_launch_ticket_required/);
  assert.equal(captures, 0);
});
if (process.platform !== 'linux') test('unsupported platform rejects current launch options rather than claiming native coverage', () => {
  assert.throws(() => validateCurrentImageLaunchSelection(launchSelection()), /real_model_absolute_path_required/);
});

test('explicit compiler selector and actual-shaped LOCAL audit bind each selected stable ID and final policy token', () => {
  const registration = controlRegistration(), audit = controlPolicyAudit(registration);
  assert.deepEqual(validateOwnerCompilerPolicySelection(CONTROL_POLICY, CONTROL_COMPILER_RULES), CONTROL_POLICY);
  const verified = verifyOwnerCompilerPolicyAudit(audit, CONTROL_POLICY, registration.binding);
  assert.equal(verified.mutationSteps.length, 14); assert.equal(verified.policyDigest, verified.afterRevision.policyDigest);
  assert(Object.isFrozen(verified)); assert(Object.isFrozen(verified.mutationSteps));
  for (const [index, step] of verified.mutationSteps.entries()) {
    assert.deepEqual(step.request.action, { type: 'set_rule_enabled', ruleId: CONTROL_COMPILER_RULES[index], enabled: true });
    assert.equal(step.auditEvent.requestDigest, digest(canonical(step.request))); assert.equal(Object.hasOwn(step.request, 'reason'), false);
  }
});
for (const [name, mutation] of [
  ['no explicit owner policy approval', (value: FixtureValue) => { value.authorizeOwnerPolicy = false; }],
  ['execution consent instead of policy consent', (value: FixtureValue) => { delete value.authorizeOwnerPolicy; value.authorizeExecution = true; }],
  ['empty IDs', (value: FixtureValue) => { value.compilerRuleIds = []; }],
  ['duplicate IDs', (value: FixtureValue) => { value.compilerRuleIds.push(value.compilerRuleIds[0]); }],
  ['unstable alias', (value: FixtureValue) => { value.compilerRuleIds[0] = 'UNRESOLVED_REFERENCE'; }],
  ['unknown compiler ID', (value: FixtureValue) => { value.compilerRuleIds[0] = 'ktc.compiler.common.synthetic_unknown'; value.compilerRuleIds.sort(); }],
  ['unordered IDs', (value: FixtureValue) => value.compilerRuleIds.reverse()],
  ['blank reason', (value: FixtureValue) => { value.reason = '\u001c\u2003'; }],
  ['oversized reason', (value: FixtureValue) => { value.reason = 'x'.repeat(1025); }],
  ['UTF-8 oversized reason', (value: FixtureValue) => { value.reason = '€'.repeat(342); }],
  ['ISO control reason', (value: FixtureValue) => { value.reason = 'reason\u0085'; }],
  ['NUL reason', (value: FixtureValue) => { value.reason = 'reason\u0000'; }],
] as const) test('separate owner policy selection rejects ' + name, () => {
  const value = structuredClone(CONTROL_POLICY) as unknown as FixtureValue; mutation(value);
  assert.throws(() => validateOwnerCompilerPolicySelection(value as OwnerCompilerPolicySelection, CONTROL_COMPILER_RULES));
});
test('reason stays separately bound and is not silently normalized or retrofitted into legacy request digests', () => {
  const selection = { ...CONTROL_POLICY, reason: '\uFEFF' };
  assert.equal(validateOwnerCompilerPolicySelection(selection, CONTROL_COMPILER_RULES).reason, '\uFEFF');
  const r = controlRegistration(); assert.throws(() => verifyOwnerCompilerPolicyAudit(controlPolicyAudit(r), selection, r.binding), /policy_audit_selection/);
});
for (const [name, mutation] of [
  ['policy digest substituted', (value: FixtureValue) => { value.policyDigest = digest('substitute'); }],
  ['missing mutation', (value: FixtureValue) => value.mutationSteps.pop()],
  ['non-owner actor', (value: FixtureValue) => { value.mutationSteps[0].auditEvent.actor.clientId = 'model'; }],
  ['shared mutation', (value: FixtureValue) => { value.mutationSteps[0].request.layer = 'shared'; }],
  ['disabled rule', (value: FixtureValue) => { value.mutationSteps[0].request.action.enabled = false; }],
  ['different stable rule action', (value: FixtureValue) => { value.mutationSteps[0].request.action.ruleId = CONTROL_COMPILER_RULES[1]; }],
  ['unbound action request bytes', (value: FixtureValue) => { value.mutationSteps[0].auditEvent.requestDigest = digest('other-request'); }],
  ['different expected revision', (value: FixtureValue) => { value.mutationSteps[1].request.expected = value.beforeRevision; }],
  ['broken audit chain', (value: FixtureValue) => { value.mutationSteps[1].auditEvent.previousEventDigest = null; }],
  ['different rule settings', (value: FixtureValue) => value.afterLocal.ruleSettings.pop()],
  ['suppression instead of plain enablement', (value: FixtureValue) => value.afterLocal.suppressions.push({ synthetic: true })],
] as const) test('owner policy audit rejects ' + name, () => {
  const r = controlRegistration(), value = structuredClone(controlPolicyAudit(r)) as unknown as FixtureValue; mutation(value);
  assert.throws(() => verifyOwnerCompilerPolicyAudit(value as OwnerCompilerPolicyAudit, CONTROL_POLICY, r.binding));
});
test('execution artifact ordering and separate policy selection are both pinned before any owner call', linuxOnly, () => {
  const r = controlRegistration(), authorization = controlAuthorization(r);
  assert.doesNotThrow(() => validateAcceptanceSelections(authorization, CONTROL_INVOCATION, CONTROL_POLICY));
  assert.throws(() => validateAcceptanceSelections(authorization, { ...CONTROL_INVOCATION,
    compilerArtifactPaths: [...CONTROL_INVOCATION.compilerArtifactPaths].reverse() }, CONTROL_POLICY));
  assert.throws(() => validateAcceptanceSelections(authorization, CONTROL_INVOCATION, { ...CONTROL_POLICY, reason: 'Changed independent owner decision.' }));
});

function imageData(): { selection: CurrentImageSelection; build: FixtureValue } {
  const pin = (label: string, path: string): ImagePin => ({ label, path, digest: digest('synthetic-' + label), bytes: 1 });
  const selection: CurrentImageSelection = { schema: 'ktchecker-current-image-owner-selection/1', approvalId: 'synthetic-image-approval',
    sourceInputs: [pin('source/file.kt', '/synthetic-image/source/file.kt')],
    installedOutputs: [pin('a-support.jar', '/synthetic-image/installed/a-support.jar'), pin('z-compiler.jar', '/synthetic-image/installed/z-compiler.jar')],
    controllerOutputs: [pin('controller.js', '/synthetic-image/controller.js')],
    compilerArtifactPaths: ['/synthetic-image/installed/z-compiler.jar', '/synthetic-image/installed/a-support.jar'],
    node: pin('node', '/synthetic-image/node'), nodeVersion: '24.20.0', codex: codexPin('/synthetic-image/codex'),
    buildRecord: pin('build-record', '/synthetic-image/build-record.json'), ownerCodeImageDigest: digest('synthetic-owner-code') };
  const logical = (pins: readonly ImagePin[]) => pins.map(({ label, digest, bytes }) => ({ label, digest, bytes }));
  const build = { schema: 'ktchecker-owner-approved-current-image-build/1', status: 'verified_success', ownerApprovalId: selection.approvalId,
    sourceInputsDigest: digest(canonical(logical(selection.sourceInputs))), installedOutputsDigest: digest(canonical(logical(selection.installedOutputs))),
    controllerOutputsDigest: digest(canonical(logical(selection.controllerOutputs))),
    compilerArtifactOrderDigest: digest(canonical(logical(selection.compilerArtifactPaths.map(path => selection.installedOutputs.find(pin => pin.path === path)!)))),
    ownerCodeImageDigest: selection.ownerCodeImageDigest, nodeDigest: selection.node.digest, nodeVersion: selection.nodeVersion,
    model: 'gpt-6-astra', effort: 'max', buildId: 'synthetic-build-shape-only', buildEvidenceDigest: digest('synthetic-not-actual-build') };
  return { selection, build };
}
test('pure image linkage retains owner-pinned build scope and explicitly denies loaded-memory attestation', linuxOnly, () => {
  const { selection, build } = imageData(), evidence = validateImageSelection(selection, build);
  assert.equal(evidence.loadedImageAttestation, false);
  assert.equal(evidence.sourceLinkage, 'owner_pinned_successful_build_record_linkage_not_reproducibility_or_loaded_memory_attestation');
  assert.equal(evidence.compilerArtifactOrderDigest, build.compilerArtifactOrderDigest);
});
for (const [name, mutation] of [
  ['source digest changed', (value: FixtureValue) => { value.sourceInputs[0].digest = digest('other-source'); }],
  ['installed bytes changed', (value: FixtureValue) => { value.installedOutputs[0].bytes = 2; }],
  ['compiler order changed', (value: FixtureValue) => value.compilerArtifactPaths.reverse()],
  ['unselected compiler artifact', (value: FixtureValue) => value.compilerArtifactPaths.push('/synthetic-image/other.jar')],
  ['role path alias', (value: FixtureValue) => { value.node.path = value.installedOutputs[0].path; }],
  ['lower Node version', (value: FixtureValue) => { value.nodeVersion = '24.19.0'; }],
  ['different Codex bytes', (value: FixtureValue) => { value.codex.digest = digest('different-codex'); }],
] as const) test('image linkage rejects ' + name, linuxOnly, () => {
  const { selection, build } = imageData(), value = structuredClone(selection) as unknown as FixtureValue; mutation(value);
  assert.throws(() => validateImageSelection(value as CurrentImageSelection, build));
});
test('source/output closure requires all selected controller modules including launcher, policy and private database', () => {
  const required = requiredCurrentImageControllerModules();
  for (const expected of ['current-image-acceptance-launch.ts', 'current-image-acceptance-policy.ts', 'private-database.ts',
    'current-image-owner-wire.ts', 'current-image-owner-clocks.ts', 'current-image-owner-channel.ts', 'current-image-owner-selection.ts', 'current-image-owner-transport.ts'])
    assert(required.some(module => module.source.endsWith('/' + expected)));
  const { selection } = imageData();
  const complete = { ...selection, sourceInputs: required.map(module => ({ label: module.source, path: '/synthetic-source/' + module.source, digest: digest(module.source), bytes: 1 })),
    controllerOutputs: required.map((module, index) => ({ label: 'module-' + index, path: module.path, digest: digest(module.path), bytes: 1 })) };
  assert.doesNotThrow(() => assertCurrentImageControllerClosure(complete));
  for (const [index, module] of required.entries()) {
    assert.throws(() => assertCurrentImageControllerClosure({ ...complete, controllerOutputs: complete.controllerOutputs.filter((_pin, ordinal) => ordinal !== index) }), module.source);
    assert.throws(() => assertCurrentImageControllerClosure({ ...complete, sourceInputs: complete.sourceInputs.filter((_pin, ordinal) => ordinal !== index) }), module.source);
  }
});
