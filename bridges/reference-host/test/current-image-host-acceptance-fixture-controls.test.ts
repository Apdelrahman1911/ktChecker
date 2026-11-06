/** Pure PUBLIC fixture/consent/patch DATA controls. No source IO, project export, owner,
 * compiler, provider, host or model. Digest agreement is not evidence that Gradle ran. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canonical, digest } from '../src/journal.js';
import type { ClientBinding } from '../src/types.js';
import { freezeCompletedPatch, type SnapshotLineage } from './current-image-acceptance-evidence.js';
import type { AcceptanceAuthorization } from './current-image-acceptance-runner.js';
import { captureCurrentImagePublicPatch, currentImageFixtureSchedule, currentImagePublicFixtureDigest,
  currentImagePublicFixtureFiles, validateCurrentImageDriverConsent, CURRENT_IMAGE_PUBLIC_COMPILER_RULES,
  CURRENT_IMAGE_PUBLIC_FINDING, CURRENT_IMAGE_PUBLIC_FIXTURE, CURRENT_IMAGE_TRUSTED_HOST_POLICY,
  type CurrentImageDriverConsent } from './current-image-host-acceptance-fixture.js';

type Mutable = Record<string, any>; // Deliberately malformed, unprivileged test DATA.
const expectedFixtureDigest = 'sha256:253ad7c3b078bb5ba5bc312e208ded85c6a790807e8f81c521ec624462ae3e49';
const binding = (): ClientBinding => ({ ownerId: 'synthetic-public-owner', workspaceId: 'synthetic-workspace',
  worktreeId: 'synthetic-worktree', clientId: 'synthetic-client', lineageId: 'synthetic-lineage', generation: 1, sessionId: 'synthetic-session' });
const authorization = (): AcceptanceAuthorization => ({ attemptId: 'synthetic-public-attempt', approvalDigest: digest('synthetic-not-authority'),
  requiredClientIds: ['synthetic-client'], compilerRuleIds: [...CURRENT_IMAGE_PUBLIC_COMPILER_RULES],
  ownerInvocationSelectionDigest: digest('synthetic-invocation'), imageSelectionDigest: digest('synthetic-image'),
  ownerCompilerPolicySelectionDigest: digest('synthetic-separate-policy'),
  scheduledPatches: [1, 2].map(n => ({ patchId: 'synthetic-public-patch-' + n, taskId: 'synthetic-public-task',
    findings: [{ ...CURRENT_IMAGE_PUBLIC_FINDING, present: n === 1 }] })) });
const consent = (): CurrentImageDriverConsent => ({ schema: 'ktchecker-current-image-thin-driver-consent/1',
  fixture: CURRENT_IMAGE_PUBLIC_FIXTURE, fixtureDigest: expectedFixtureDigest, trustedHostPolicy: CURRENT_IMAGE_TRUSTED_HOST_POLICY,
  retainedLifetimeAccepted: true });
const previous = (): SnapshotLineage => ({ snapshotId: digest('synthetic-prior-snapshot-DATA'), headRevision: 1, sourceEpoch: 1,
  manifestDigest: digest('synthetic-prior-manifest'), contract: {}, binding: binding(), roots: [], entries: [] });

test('public fixture has pinned whole five-file vectors and only the intended nonempty test-source repair changes', () => {
  const before = currentImagePublicFixtureFiles(1), after = currentImagePublicFixtureFiles(2);
  const names = ['build.gradle', 'gradle.properties', 'settings.gradle', 'src/main/kotlin/Api.kt', 'src/test/kotlin/Use.kt'];
  assert.deepEqual(before.map(file => file.path), names); assert.deepEqual(after.map(file => file.path), names);
  assert(before.every(file => file.rootId === 'project' && !file.executable && Buffer.from(file.contentBase64, 'base64').length > 0));
  assert.equal(before.filter(file => file.path.endsWith('.kt')).length, 2);
  assert.deepEqual(before.filter((file, index) => canonical(file) !== canonical(after[index])).map(file => file.path), ['src/test/kotlin/Use.kt']);
  assert.equal(Buffer.from(before[4]!.contentBase64, 'base64').toString('utf8'), 'package sample\n\nfun useAcrossFiles(): String = missingDeclaration()\n');
  assert.equal(Buffer.from(after[4]!.contentBase64, 'base64').toString('utf8'), 'package sample\n\nfun useAcrossFiles(): String = declaredAcrossFiles()\n');
  assert.equal(currentImagePublicFixtureDigest(), expectedFixtureDigest);
  assert.equal(currentImagePublicFixtureDigest(), digest(canonical({ fixture: CURRENT_IMAGE_PUBLIC_FIXTURE,
    roots: [{ id: 'project', mountPath: '' }], patches: [before, after], finding: CURRENT_IMAGE_PUBLIC_FINDING, compilerRules: CURRENT_IMAGE_PUBLIC_COMPILER_RULES })));
});

test('public fixture selects genuine main/test friend task wiring before capture without deleting test or filtering a model', () => {
  const files = currentImagePublicFixtureFiles(1), content = (path: string) => Buffer.from(files.find(file => file.path === path)!.contentBase64, 'base64').toString('utf8');
  const build = content('build.gradle');
  assert.equal(digest(build), 'sha256:08056b207041c2496ff2ee98a7cec736b96de8a6f687a3f139f5e6f350309aea');
  for (const expected of ["actualCompilations.getByName('main')", "actualCompilations.getByName('test')",
    'consumerTask.libraries.setFrom(producerOutput, external)', 'consumerTask.friendPaths.setFrom(producerOutput)',
    'consumerTask.libraries.disallowChanges()', 'consumerTask.friendPaths.disallowChanges()',
    'associations.size() != 1 || !associations[0].is(producer)',
    'producerTask.sources.asFileTree.files.isEmpty() || consumerTask.sources.asFileTree.files.isEmpty()',
    "plugins { id 'org.jetbrains.kotlin.jvm' version '2.4.21' }", 'kotlin { jvmToolchain(21) }']) assert(build.includes(expected));
  assert(!build.includes('actualCompilations.remove')); assert(!build.includes('associateWith('));
  assert(content('src/main/kotlin/Api.kt').includes('internal fun declaredAcrossFiles()'));
  assert.equal(content('gradle.properties'), 'org.gradle.java.installations.auto-detect=false\norg.gradle.java.installations.auto-download=false\n');
});

test('public file vectors and exact14 policy expectation remain independently immutable DATA', () => {
  const files = currentImagePublicFixtureFiles(1);
  assert(Object.isFrozen(files)); assert(files.every(Object.isFrozen)); assert(Object.isFrozen(CURRENT_IMAGE_PUBLIC_FINDING));
  assert(Object.isFrozen(CURRENT_IMAGE_PUBLIC_COMPILER_RULES)); assert.equal(CURRENT_IMAGE_PUBLIC_COMPILER_RULES.length, 14);
  assert.equal(new Set(CURRENT_IMAGE_PUBLIC_COMPILER_RULES).size, 14);
  assert.deepEqual(CURRENT_IMAGE_PUBLIC_COMPILER_RULES, [...CURRENT_IMAGE_PUBLIC_COMPILER_RULES].sort());
  assert.equal(Reflect.set(files[0]!, 'executable', true), false);
  assert.equal(Reflect.set(CURRENT_IMAGE_PUBLIC_FINDING, 'present', true), false);
  assert.equal(currentImagePublicFixtureDigest(), expectedFixtureDigest);
});

for (const ordinal of [0, 3, -1, 1.5, NaN]) test('public fixture refuses unscheduled ordinal ' + String(ordinal), () => {
  assert.throws(() => currentImagePublicFixtureFiles(ordinal), /driver_fixture_ordinal/);
});

test('exact independently selected public trusted-host consent is accepted only as DATA, never a read-isolation attestation', () => {
  validateCurrentImageDriverConsent(consent());
  assert.equal(CURRENT_IMAGE_TRUSTED_HOST_POLICY, 'explicit_trusted_host_public_fixture_no_read_isolation_attestation');
  assert.equal('publicReadIsolationAttested' in consent(), false); assert.equal('clock' in consent(), false);
});
for (const [name, change] of [
  ['schema', (value: Mutable) => { value.schema = 'another-schema'; }],
  ['fixture', (value: Mutable) => { value.fixture = 'private-checkout'; }],
  ['fixture digest', (value: Mutable) => { value.fixtureDigest = digest('different-public-source'); }],
  ['naked fixture hash', (value: Mutable) => { value.fixtureDigest = 'f'.repeat(64); }],
  ['prompt-only policy', (value: Mutable) => { value.trustedHostPolicy = 'read_only_means_isolated'; }],
  ['retention refusal', (value: Mutable) => { value.retainedLifetimeAccepted = false; }],
  ['missing retention consent', (value: Mutable) => { delete value.retainedLifetimeAccepted; }],
  ['invented read-isolation approval', (value: Mutable) => { value.publicReadIsolationAttested = true; }],
  ['extra cleanup authority', (value: Mutable) => { value.cleanupAuthorized = true; }],
] as const) test('driver consent refuses ' + name, () => {
  const value = structuredClone(consent()) as unknown as Mutable; change(value);
  assert.throws(() => validateCurrentImageDriverConsent(value as CurrentImageDriverConsent));
});

test('authorized schedule retains explicit patch/task IDs and exact selected present-then-absent finding without configuring policy', () => {
  const selected = authorization(), schedule = currentImageFixtureSchedule(selected);
  assert.deepEqual(schedule, selected.scheduledPatches.map(({ patchId, taskId }) => ({ patchId, taskId })));
  assert(Object.isFrozen(schedule)); assert(schedule.every(Object.isFrozen));
  assert.equal(schedule[0]!.taskId, schedule[1]!.taskId); assert.notEqual(schedule[0]!.patchId, schedule[1]!.patchId);
});
for (const [name, change] of [
  ['non-exact14', (value: Mutable) => { value.compilerRuleIds.pop(); }],
  ['reordered14', (value: Mutable) => { value.compilerRuleIds.reverse(); }],
  ['different14', (value: Mutable) => { value.compilerRuleIds[0] = 'ktc.compiler.other'; value.compilerRuleIds.sort(); }],
  ['one patch', (value: Mutable) => { value.scheduledPatches.pop(); }],
  ['third patch', (value: Mutable) => { value.scheduledPatches.push(value.scheduledPatches[0]); }],
  ['reused patch ID', (value: Mutable) => { value.scheduledPatches[1].patchId = value.scheduledPatches[0].patchId; }],
  ['unsafe task ID', (value: Mutable) => { value.scheduledPatches[0].taskId = '../unsafe'; }],
  ['finding not fixed', (value: Mutable) => { value.scheduledPatches[1].findings[0].present = true; }],
  ['different finding path', (value: Mutable) => { value.scheduledPatches[0].findings[0].path = 'src/main/kotlin/Api.kt'; }],
  ['extra expected finding', (value: Mutable) => { value.scheduledPatches[0].findings.push(value.scheduledPatches[0].findings[0]); }],
  ['extra schedule authority', (value: Mutable) => { value.scheduledPatches[0].authorizeReplacement = true; }],
] as const) test('fixed fixture schedule refuses ' + name, () => {
  const value = structuredClone(authorization()) as unknown as Mutable; change(value);
  assert.throws(() => currentImageFixtureSchedule(value as AcceptanceAuthorization));
});

test('capture uses passed actual-shaped public identity and prior snapshot DATA, never guessing or discovering an owner', () => {
  const schedule = currentImageFixtureSchedule(authorization()), identity = binding();
  identity.ownerId = 'synthetic-runtime-chosen-owner'; const prior = { ...previous(), binding: identity };
  const first = captureCurrentImagePublicPatch(schedule, 1, null, identity), second = captureCurrentImagePublicPatch(schedule, 2, prior, identity);
  assert.deepEqual(first.binding, identity); assert.notEqual(first.binding, identity); assert(Object.isFrozen(first.binding));
  assert.equal(first.baseSnapshotId, null); assert.equal(first.expectedHeadRevision, 0);
  assert.equal(second.baseSnapshotId, prior.snapshotId); assert.equal(second.expectedHeadRevision, prior.headRevision);
  assert.deepEqual(first.roots, [{ id: 'project', mountPath: '' }]); assert.equal(first.files.length, 5); assert.equal(second.files.length, 5);
  assert.equal(freezeCompletedPatch(first).digest, digest(canonical(first)));
  assert.equal(freezeCompletedPatch(second).digest, digest(canonical(second)));
  identity.ownerId = 'synthetic-later-mutation'; assert.equal(first.binding.ownerId, 'synthetic-runtime-chosen-owner');
});

for (const [name, ordinal, priorChange, bindingChange] of [
  ['prior snapshot on first capture', 1, (_value: Mutable) => {}, (_value: Mutable) => {}],
  ['missing prior on repair', 2, (value: Mutable) => { value.missing = true; }, (_value: Mutable) => {}],
  ['prior owner mismatch', 2, (value: Mutable) => { value.binding.ownerId = 'synthetic-another-owner'; }, (_value: Mutable) => {}],
  ['invalid prior snapshot digest', 2, (value: Mutable) => { value.snapshotId = 'not-a-sha'; }, (_value: Mutable) => {}],
  ['invalid prior head revision', 2, (value: Mutable) => { value.headRevision = -1; }, (_value: Mutable) => {}],
  ['rearmed generation', 2, (_value: Mutable) => {}, (value: Mutable) => { value.generation = 2; }],
  ['missing session', 2, (_value: Mutable) => {}, (value: Mutable) => { delete value.sessionId; }],
  ['credential-shaped extra field', 2, (_value: Mutable) => {}, (value: Mutable) => { value.token = 'SYNTHETIC_NOT_A_CREDENTIAL'; }],
] as const) test('public capture refuses ' + name, () => {
  const prior = previous() as unknown as Mutable, identity = binding() as unknown as Mutable;
  priorChange(prior); bindingChange(identity);
  assert.throws(() => captureCurrentImagePublicPatch(currentImageFixtureSchedule(authorization()), ordinal,
    prior.missing ? null : prior as SnapshotLineage, identity as ClientBinding));
});
