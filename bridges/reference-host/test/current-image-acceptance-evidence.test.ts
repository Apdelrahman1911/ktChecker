/** Pure SYNTHETIC immutable snapshot/parent controls. Not compiler or owner execution. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonical, digest } from '../src/journal.js';
import { freezeCompletedPatch, verifySnapshotCommit, verifyParentAdmission, verifyParentCompletion,
  type CompletedPatch, type ParentAdmissionData, type ParentCompletionData } from './current-image-acceptance-evidence.js';
import { CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE, controlParent, controlPatch, controlRegistration,
  type FixtureValue } from './current-image-acceptance-fixtures.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
function first() {
  const registration = controlRegistration(), patch = controlPatch(registration, 1, null), parent = controlParent(patch);
  return { registration, patch, parent };
}
test('whole immutable source is copied, mode/root/base bound, and exact snapshot delta retained across two patches', () => {
  const f = first(), patch = structuredClone(f.patch) as unknown as FixtureValue, captured = freezeCompletedPatch(patch as CompletedPatch);
  patch.files[0].contentBase64 = Buffer.from('a later mutation').toString('base64');
  assert.equal(captured.request.files[0]!.contentBase64, f.patch.files[0]!.contentBase64); assert(Object.isFrozen(captured.request.files));
  const initial = verifySnapshotCommit(captured, f.parent.data.snapshotCommit, null);
  assert.equal(initial.snapshotId, f.parent.lineage.snapshotId); assert.equal(initial.headRevision, 1); assert.equal(initial.sourceEpoch, 1);
  const next = controlParent(controlPatch(f.registration, 2, initial), f.parent);
  const after = verifySnapshotCommit(freezeCompletedPatch(next.patch), next.data.snapshotCommit, initial);
  assert.equal(after.headRevision, 2); assert.equal(after.sourceEpoch, 2); assert.notEqual(after.snapshotId, initial.snapshotId);
  const delta = (next.data.snapshotCommit.manifest as FixtureValue).delta;
  assert.equal(delta.length, 1); assert.equal(delta[0].path, 'src/test/kotlin/Use.kt'); assert.equal(delta[0].operation, 'modified');
});
for (const [name, mutate] of [
  ['noncanonical base64', (value: FixtureValue) => { value.files[0].contentBase64 += '\n'; }],
  ['omitted full source vector', (value: FixtureValue) => { value.files = []; }],
  ['duplicate source', (value: FixtureValue) => { value.files.push(value.files[0]); }],
  ['source order changed', (value: FixtureValue) => value.files.reverse()],
  ['unsafe source path', (value: FixtureValue) => { value.files[0].path = '../other.kt'; }],
  ['unknown root', (value: FixtureValue) => { value.files[0].rootId = 'not-declared'; }],
  ['missing mode bit', (value: FixtureValue) => { delete value.files[0].executable; }],
  ['missing full session binding', (value: FixtureValue) => { delete value.binding.sessionId; }],
] as const) test('completed immutable patch rejects ' + name, () => {
  const value = structuredClone(first().patch) as unknown as FixtureValue; mutate(value); assert.throws(() => freezeCompletedPatch(value as CompletedPatch));
});
for (const [name, mutate] of [
  ['mode bytes', (value: FixtureValue) => { value.manifest.entries[0].executable = true; }],
  ['declared roots', (value: FixtureValue) => { value.manifest.roots[0].mountPath = 'different'; }],
  ['live root substitution', (value: FixtureValue) => { value.manifest.roots[0].livePath = '/synthetic-live'; }],
  ['exact delta omission', (value: FixtureValue) => value.manifest.delta.pop()],
  ['parent rebase', (value: FixtureValue) => { value.manifest.parentSnapshotId = digest('different-base'); }],
  ['source epoch', (value: FixtureValue) => { value.manifest.capture.sourceEpoch++; }],
  ['head revision', (value: FixtureValue) => { value.headRevision++; }],
  ['client substitution', (value: FixtureValue) => { value.manifest.clientId = 'other-client'; }],
  ['tree digest', (value: FixtureValue) => { value.manifest.treeDigest = digest('different-tree'); }],
  ['snapshot digest', (value: FixtureValue) => { value.manifest.snapshotId = digest('different-snapshot'); }],
] as const) test('SnapshotCommit rejects substituted ' + name, () => {
  const f = first(), value = structuredClone(f.parent.data.snapshotCommit) as FixtureValue; mutate(value);
  assert.throws(() => verifySnapshotCommit(freezeCompletedPatch(f.patch), value, null));
});
test('mode-only delta is bound while observed changedPaths preserves its content-based contract', linuxOnly, () => {
  const f = first(), previous = verifyParentAdmission(freezeCompletedPatch(f.patch), f.parent.data, null, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE);
  const patch: CompletedPatch = { ...f.patch, patchId: 'synthetic-patch-2', ordinal: 2, baseSnapshotId: previous.snapshot.snapshotId,
    expectedHeadRevision: 1, files: f.patch.files.map((file, index) => index === 0 ? { ...file, executable: !file.executable } : { ...file }) };
  const next = controlParent(patch, f.parent), bound = verifyParentAdmission(freezeCompletedPatch(patch), next.data, previous, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE);
  assert.notEqual(bound.snapshot.snapshotId, previous.snapshot.snapshotId);
  assert.equal((next.data.snapshotCommit.manifest as FixtureValue).delta.length, 1);
  for (const slot of next.data.declaredPlan.slots as FixtureValue[]) assert.deepEqual(slot.input.changedPaths, []);
});
test('returned parent data cannot replace a previously bound immutable admission', linuxOnly, () => {
  const f = first(), raw = structuredClone(f.parent.data) as unknown as FixtureValue;
  const parent = verifyParentAdmission(freezeCompletedPatch(f.patch), raw as ParentAdmissionData, null, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE);
  raw.parent.planId = 'revised-after-return'; assert.notEqual(parent.data.parent.planId, raw.parent.planId); assert(Object.isFrozen(parent.data.parent));
  assert(verifyParentCompletion(parent, f.parent.completion));
});
test('second observed invocation must be genuinely new with exact documentary before linkage', linuxOnly, () => {
  const f = first(), initial = verifyParentAdmission(freezeCompletedPatch(f.patch), f.parent.data, null, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE);
  const next = controlParent(controlPatch(f.registration, 2, initial.snapshot), f.parent);
  const mutations = [
    (value: FixtureValue) => { value.observed.documentaryBefore = null; },
    (value: FixtureValue) => { value.observed.documentaryBefore.modelDigest = digest('another-model'); },
    (value: FixtureValue) => { value.observed.originalDeadlineIdentity = f.parent.data.observed.originalDeadlineIdentity; },
    (value: FixtureValue) => { value.observed.ownerCodeImageDigest = digest('another-owner'); },
  ];
  for (const mutation of mutations) {
    const value = structuredClone(next.data) as unknown as FixtureValue; mutation(value);
    assert.throws(() => verifyParentAdmission(freezeCompletedPatch(next.patch), value as ParentAdmissionData, initial, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE));
  }
});
test('terminal state is required even when a progressive compiler result is already nonnull', linuxOnly, () => {
  const f = first(), parent = verifyParentAdmission(freezeCompletedPatch(f.patch), f.parent.data, null, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE);
  const pending = structuredClone(f.parent.completion) as unknown as FixtureValue;
  pending.parent.state = 'pending'; pending.parent.slots.forEach((slot: FixtureValue) => { slot.state = 'running'; });
  pending.children.forEach((child: FixtureValue) => { child.state = 'running'; });
  assert(pending.children[0].result); assert.equal(verifyParentCompletion(parent, pending as ParentCompletionData), false);
  assert.equal(verifyParentCompletion(parent, f.parent.completion), true);
});
test('actual exported empty target is not silently normalized to jvm', linuxOnly, () => {
  const f = first(), value = structuredClone(f.parent.data) as unknown as FixtureValue;
  value.declaredPlan.slots[0].input.context.scope.target = 'jvm'; value.parent.slots[0].context.target = 'jvm';
  value.parent.planDigest = digest(canonical(value.declaredPlan));
  assert.throws(() => verifyParentAdmission(freezeCompletedPatch(f.patch), value as ParentAdmissionData, null, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE));
});
