/** SYNTHETIC explicit-policy and historical-final controls. No real config mutation, owner
 * authority, compiler/provider/model execution or permission to perform a final action.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonical, digest } from '../src/journal.js';
import type { GateCut, GateResult } from '../src/types.js';
import { OBSERVED_GAP, verifyDeniedFinal } from './current-image-acceptance-evidence.js';
import { validateOwnerCompilerPolicySelection, verifyOwnerCompilerPolicyAudit,
  type OwnerCompilerPolicyAudit, type OwnerCompilerPolicySelection } from './current-image-acceptance-policy.js';
import { captureSyntheticTwoPatchRun } from './current-image-acceptance-adversarial-fixtures.js';
import { CONTROL_COMPILER_RULES, CONTROL_POLICY, controlPolicyAudit, controlRegistration, type FixtureValue } from './current-image-acceptance-fixtures.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
/** Unprivileged DATA rehashing only, not Workbench's HMAC permit or ConfigMutation authority. */
function rehashSyntheticPolicyEvents(value: FixtureValue): void {
  const events: FixtureValue[] = [];
  for (const step of value.mutationSteps as FixtureValue[]) {
    step.auditEvent.requestId = step.request.requestId; step.auditEvent.requestDigest = digest(canonical(step.request));
    step.auditEvent.previousEventDigest = events.length ? digest(canonical(events.at(-1))) : null;
    events.push(structuredClone(step.auditEvent));
  }
  value.auditEvents = events; value.afterLocal.auditEvents = structuredClone(events);
}

test('coherent public request/audit hashes cannot substitute explicit policy selection, fresh state or owner lineage', async t => {
  const registration = controlRegistration(), base = controlPolicyAudit(registration);
  const mutations: readonly [string, (value: FixtureValue) => void][] = [
    ['stale registration session', value => { value.binding.sessionId = 'synthetic-previous-owner-session'; }],
    ['foreign registration generation', value => { value.binding.generation++; }],
    ['foreign registration lineage', value => { value.binding.lineageId = 'synthetic-other-lineage'; }],
    ['coherent replay of another selected reason', value => {
      value.selection.reason = 'SYNTHETIC different independently selected reason'; value.selectionDigest = digest(canonical(value.selection));
    }],
    ['execution approval in place of separate policy approval', value => {
      delete value.selection.authorizeOwnerPolicy; value.selection.authorizeExecution = true; value.selectionDigest = digest(canonical(value.selection));
    }],
    ['duplicate owner request ID with rehashed event chain', value => { value.mutationSteps[1].request.requestId = value.mutationSteps[0].request.requestId; }],
    ['duplicate explicit-owner actor session with rehashed event chain', value => { value.mutationSteps[1].auditEvent.actor.sessionId = value.mutationSteps[0].auditEvent.actor.sessionId; }],
    ['wrong owner actor with rehashed event chain', value => { value.mutationSteps[0].auditEvent.actor.ownerId = 'synthetic-other-owner'; }],
    ['model actor with rehashed event chain', value => { value.mutationSteps[0].auditEvent.actor.clientId = 'synthetic-model'; }],
    ['duplicate stable enable action with rehashed request/event chain', value => { value.mutationSteps[1].request.action.ruleId = value.mutationSteps[0].request.action.ruleId; }],
    ['shared-layer action with rehashed request/event chain', value => { value.mutationSteps[0].request.layer = 'shared'; }],
    ['invented legacy request reason with valid digest', value => { value.mutationSteps[0].request.reason = CONTROL_POLICY.reason; }],
    ['stale request expected revision with valid request digest', value => { value.mutationSteps[1].request.expected = structuredClone(value.beforeRevision); }],
    ['nonfresh LOCAL state disguised as a newly selected policy', value => {
      value.beforeRevision.local = { revision: 1, generationId: 'synthetic-old-local-generation', documentDigest: digest('synthetic-old-local-document') };
      value.mutationSteps[0].beforeRevision = structuredClone(value.beforeRevision);
      value.mutationSteps[0].request.expected = structuredClone(value.beforeRevision);
    }],
    ['stale final policy token and local revision despite intact mutation history', value => {
      value.afterRevision = structuredClone(value.mutationSteps[0].afterRevision); value.policyDigest = value.afterRevision.policyDigest;
      value.afterLocal.revision = structuredClone(value.afterRevision.local);
    }],
    ['LOCAL generation switch with coherent revision handoff', value => {
      value.mutationSteps[1].afterRevision.local.generationId = 'synthetic-replacement-local-generation';
      for (let index = 2; index < value.mutationSteps.length; index++) {
        const step = value.mutationSteps[index]; step.beforeRevision = structuredClone(value.mutationSteps[index - 1].afterRevision);
        step.request.expected = structuredClone(step.beforeRevision); step.afterRevision.local.generationId = 'synthetic-replacement-local-generation';
      }
      value.afterRevision = structuredClone(value.mutationSteps.at(-1).afterRevision); value.afterLocal.revision = structuredClone(value.afterRevision.local);
    }],
    ['suppression mask bundled with selected enables', value => { value.afterLocal.suppressionMasks = [{ id: 'synthetic-hidden-mask' }]; }],
    ['baseline bundled with selected enables', value => { value.afterLocal.baselines = [{ id: 'synthetic-hidden-baseline' }]; }],
  ];
  for (const [name, mutate] of mutations) await t.test(name, () => {
    const value = structuredClone(base) as unknown as FixtureValue; mutate(value); rehashSyntheticPolicyEvents(value);
    assert.throws(() => verifyOwnerCompilerPolicyAudit(value as OwnerCompilerPolicyAudit, CONTROL_POLICY, registration.binding), name);
  });
  for (const which of ['missing', 'duplicate', 'reordered'] as const) await t.test(which + ' selected final LOCAL rule row is rejected', () => {
    const value = structuredClone(base) as unknown as FixtureValue;
    if (which === 'missing') value.afterLocal.ruleSettings.pop();
    if (which === 'duplicate') value.afterLocal.ruleSettings[1] = structuredClone(value.afterLocal.ruleSettings[0]);
    if (which === 'reordered') value.afterLocal.ruleSettings.reverse();
    assert.throws(() => verifyOwnerCompilerPolicyAudit(value as OwnerCompilerPolicyAudit, CONTROL_POLICY, registration.binding), /policy_local_exact_rules/);
  });
  for (const field of ['mutationSteps', 'auditEvents'] as const) await t.test('missing ' + field + ' is not implicit policy configuration', () => {
    const value = structuredClone(base) as unknown as FixtureValue; delete value[field];
    assert.throws(() => verifyOwnerCompilerPolicyAudit(value as OwnerCompilerPolicyAudit, CONTROL_POLICY, registration.binding));
  });
  const verified = verifyOwnerCompilerPolicyAudit(base, CONTROL_POLICY, registration.binding);
  assert.equal(verified.mutationSteps.length, 14); assert.deepEqual(verified.selection.compilerRuleIds, CONTROL_COMPILER_RULES);
});

test('selection remains independently exact14 even when a coherent audit advertises a smaller or reordered runtime subset', () => {
  const registration = controlRegistration();
  for (const compilerRuleIds of [CONTROL_COMPILER_RULES.slice(0, 2), [...CONTROL_COMPILER_RULES].reverse()]) {
    const selection = { ...CONTROL_POLICY, compilerRuleIds };
    assert.throws(() => validateOwnerCompilerPolicySelection(selection as OwnerCompilerPolicySelection, CONTROL_COMPILER_RULES));
    const audit = structuredClone(controlPolicyAudit(registration)) as unknown as FixtureValue;
    audit.selection = selection; audit.selectionDigest = digest(canonical(selection));
    assert.throws(() => verifyOwnerCompilerPolicyAudit(audit as OwnerCompilerPolicyAudit, CONTROL_POLICY, registration.binding));
  }
});

test('historical final cut/result cannot change lineage, current source, frontier or required event identity set', linuxOnly, async t => {
  const run = await captureSyntheticTwoPatchRun(t), first = run.deliveries[0]!, current = run.deliveries[1]!;
  const binding = structuredClone(run.fixture.host.binding), events = current.row.originalEvents;
  const check = (value: FixtureValue) => verifyDeniedFinal(binding, current.parent.snapshot.snapshotId, events, value.cut as GateCut, value.result as GateResult);
  assert.doesNotThrow(() => check(run.final));
  const mutations: readonly [string, (value: FixtureValue) => void][] = [
    ['missing cut lineage', value => { delete value.cut.lineageId; }],
    ['wrong cut lineage even when result agrees', value => { value.cut.lineageId = value.result.lineageId = 'synthetic-foreign-lineage'; }],
    ['wrong cut client even when result agrees', value => { value.cut.clientId = value.result.clientId = 'synthetic-foreign-client'; }],
    ['stale cut generation even when result agrees', value => { value.cut.generation++; value.result.generation = value.cut.generation; }],
    ['missing cut contract', value => { delete value.cut.contractVersion; }],
    ['missing current source', value => { value.cut.sourceSnapshotId = null; }],
    ['stale first-patch source', value => { value.cut.sourceSnapshotId = first.parent.snapshot.snapshotId; }],
    ['stale first-patch frontier', value => { value.cut.eventFrontier = first.row.originalEvents.at(-1).sequence; }],
    ['future uncaptured frontier', value => { value.cut.eventFrontier++; }],
    ['missing historical event prefix', value => { value.cut.requiredEventIds = value.cut.requiredEventIds.slice(6); }],
    ['duplicate required event, unchanged length', value => { value.cut.requiredEventIds[1] = value.cut.requiredEventIds[0]; }],
    ['duplicate historical event appended', value => { value.cut.requiredEventIds.push(value.cut.requiredEventIds[0]); }],
    ['required event reorder', value => { value.cut.requiredEventIds.reverse(); }],
    ['foreign required event, unchanged length', value => { value.cut.requiredEventIds[0] = 'synthetic-foreign-event'; }],
    ['missing cut identity', value => { delete value.cut.cutId; }],
    ['noninteger lineage epoch', value => { value.cut.lineageEpoch = 0.5; }],
    ['ready cut inconsistent with permanent observed gap', value => { value.cut.ready = true; }],
    ['stale result cut identity', value => { value.result.cutId = 'synthetic-previous-cut'; }],
    ['missing result contract', value => { delete value.result.contractVersion; }],
    ['missing result client', value => { delete value.result.clientId; }],
    ['wrong result lineage', value => { value.result.lineageId = 'synthetic-other-lineage'; }],
    ['stale result generation', value => { value.result.generation++; }],
    ['missing explicit denial', value => { delete value.result.allowed; }],
    ['missing explicit no-action result', value => { delete value.result.actionPerformed; }],
    ['allowed final action', value => { value.result.allowed = true; }],
    ['performed final action', value => { value.result.actionPerformed = true; }],
    ['local denial without captured historical taints', value => { value.result.reasons = ['required_context_not_observed']; }],
    ['cut drops only historical review taints', value => { value.cut.reasons = value.cut.reasons.filter((reason: string) => !first.parent.reviewIds.some(review => reason === OBSERVED_GAP + ':' + review)); }],
    ['result drops only historical review taints', value => { value.result.reasons = value.result.reasons.filter((reason: string) => !first.parent.reviewIds.some(review => reason === OBSERVED_GAP + ':' + review)); }],
    ['stale complete first-patch cut', value => {
      value.cut.sourceSnapshotId = first.parent.snapshot.snapshotId; value.cut.eventFrontier = first.row.originalEvents.at(-1).sequence;
      value.cut.requiredEventIds = first.row.originalEvents.filter((event: FixtureValue) => event.deliveryRequired).map((event: FixtureValue) => event.eventId);
      value.cut.reasons = first.parent.reviewIds.map(review => OBSERVED_GAP + ':' + review); value.result.reasons = [...value.cut.reasons];
    }],
  ];
  for (const [name, mutate] of mutations) await t.test(name, () => {
    const value = structuredClone(run.final) as FixtureValue; mutate(value); assert.throws(() => check(value), name);
  });
  assert.equal(run.fixture.adapter.calls.filter(method => method === 'gate.complete').length, 1);
  assert.equal(run.fixture.host.requests.length, 2);
  // Offline final validation is data consistency, not proof of that broker call. The separate
  // runner assertion above retains exactly one fake transport call; neither is actual delivery.
});
