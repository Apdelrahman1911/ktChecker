/** Separate explicit owner policy DATA selection and audit joins. Not a permit, RPC tool,
 * rule resolver, execution consent, automatic activation or qualification authority.
 */
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest } from '../src/journal.js';
import type { ClientBinding } from '../src/types.js';
import { check, frozen, id, integer, record, same, sha } from './current-image-acceptance-evidence.js';

export interface OwnerCompilerPolicySelection {
  readonly compilerRuleIds: readonly string[];
  readonly reason: string;
  readonly authorizeOwnerPolicy: true;
}
export interface OwnerCompilerPolicyAudit {
  readonly schema: 'ktchecker/owner-compiler-policy-selection/1';
  readonly binding: ClientBinding;
  readonly selection: OwnerCompilerPolicySelection;
  readonly selectionDigest: string;
  readonly beforeRevision: JsonObject;
  readonly afterRevision: JsonObject;
  readonly policyDigest: string;
  readonly auditEvents: readonly JsonObject[];
  readonly mutationSteps: readonly {
    readonly request: JsonObject; readonly beforeRevision: JsonObject;
    readonly afterRevision: JsonObject; readonly auditEvent: JsonObject;
  }[];
  readonly afterLocal: JsonObject;
}
const exact = (value: unknown, keys: readonly string[], code: string) => same(Object.keys(record(value)).sort(), [...keys].sort(), code);

/** Equality to an independently owner-pinned expectation is NOT support discovery. The typed
 * JVM owner independently checks canonical catalog membership and actual supported JVM IDs.
 * In particular, neither a prefix nor the actual returned execution rows may choose the IDs.
 */
export function validateOwnerCompilerPolicySelection(value: OwnerCompilerPolicySelection, expectedIds: readonly string[]): OwnerCompilerPolicySelection {
  exact(value, ['compilerRuleIds', 'reason', 'authorizeOwnerPolicy'], 'policy_selection_schema');
  check(value.authorizeOwnerPolicy === true && Array.isArray(value.compilerRuleIds) && value.compilerRuleIds.length > 0 &&
    value.compilerRuleIds.length <= 443 && new Set(value.compilerRuleIds).size === value.compilerRuleIds.length &&
    value.compilerRuleIds.every(rule => id(rule).startsWith('ktc.compiler.')), 'explicit_owner_policy_required');
  same(value.compilerRuleIds, [...value.compilerRuleIds].sort(), 'policy_selection_order');
  same(value.compilerRuleIds, expectedIds, 'policy_selection_not_independent_expectation');
  check(typeof value.reason === 'string' && value.reason.length > 0 && Buffer.byteLength(value.reason, 'utf8') <= 1024 && !/\p{Cc}/u.test(value.reason) &&
    !/^[\u0009-\u000D\u001C-\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]*$/u.test(value.reason), 'policy_reason_required');
  return frozen(structuredClone(value));
}

/** Checks actual-shaped data returned by the typed owner, never recreates its HMAC authority.
 * SetRuleEnabled has no reason field. The reason is bound ONLY by the separate selected file
 * and owner witness; it is not falsely attributed to ConfigMutationRequest.requestDigest.
 */
export function verifyOwnerCompilerPolicyAudit(raw: OwnerCompilerPolicyAudit, selection: OwnerCompilerPolicySelection,
  binding: ClientBinding): OwnerCompilerPolicyAudit {
  check(Buffer.byteLength(canonical(raw)) <= 2 * 1024 * 1024, 'policy_audit_bound');
  const value = structuredClone(raw);
  exact(value, ['schema', 'binding', 'selection', 'selectionDigest', 'beforeRevision', 'afterRevision', 'policyDigest',
    'auditEvents', 'mutationSteps', 'afterLocal'], 'policy_audit_schema');
  check(value.schema === 'ktchecker/owner-compiler-policy-selection/1', 'policy_audit_schema');
  same(value.binding, binding, 'policy_audit_binding'); same(value.selection, selection, 'policy_audit_selection');
  check(value.selectionDigest === digest(canonical(selection)), 'policy_audit_selection_digest');
  const configBinding = { ownerId: binding.ownerId, workspaceId: binding.workspaceId, worktreeId: binding.worktreeId, lineageId: binding.lineageId };
  const layer = (raw: unknown): JsonObject => {
    const item = record(raw); exact(item, ['revision', 'generationId', 'documentDigest'], 'policy_layer_schema');
    integer(item.revision); sha(item.documentDigest);
    if (item.revision === 0) check(item.generationId === null && item.documentDigest === digest('ktchecker/config/absent/1'), 'policy_fresh_layer');
    else id(item.generationId);
    return item;
  };
  const revision = (raw: unknown): JsonObject => {
    const item = record(raw); exact(item, ['binding', 'shared', 'local', 'policyDigest'], 'policy_revision_schema');
    same(item.binding, configBinding, 'policy_revision_binding'); layer(item.shared); layer(item.local); sha(item.policyDigest); return item;
  };
  const before = revision(value.beforeRevision), after = revision(value.afterRevision);
  check(record(before.local).revision === 0 && record(before.shared).revision === 0, 'policy_not_fresh_owner_state');
  same(before.shared, after.shared, 'policy_shared_mutation_forbidden');
  check(value.policyDigest === after.policyDigest && after.policyDigest !== before.policyDigest, 'policy_digest_not_current');
  check(Array.isArray(value.auditEvents) && Array.isArray(value.mutationSteps) &&
    value.auditEvents.length === selection.compilerRuleIds.length && value.mutationSteps.length === selection.compilerRuleIds.length,
    'policy_audit_count');
  let prior = before, previousEvent: JsonObject | null = null;
  const requestIds = new Set<string>(), actorSessions = new Set<string>();
  for (const [index, rawStep] of value.mutationSteps.entries()) {
    exact(rawStep, ['request', 'beforeRevision', 'afterRevision', 'auditEvent'], 'policy_step_schema');
    const request = record(rawStep.request), event = record(rawStep.auditEvent), next = revision(rawStep.afterRevision);
    same(rawStep.beforeRevision, prior, 'policy_step_revision_chain');
    exact(request, ['binding', 'layer', 'expected', 'action', 'requestId'], 'policy_request_schema');
    same(request.binding, configBinding, 'policy_request_binding'); same(request.expected, prior, 'policy_request_expected_revision');
    check(request.layer === 'local', 'policy_shared_mutation_forbidden');
    same(request.action, { type: 'set_rule_enabled', ruleId: selection.compilerRuleIds[index], enabled: true }, 'policy_exact_enable_action');
    const requestId = id(request.requestId); check(!requestIds.has(requestId), 'policy_request_reused'); requestIds.add(requestId);
    exact(event, ['revision', 'requestId', 'requestDigest', 'actor', 'appliedAtEpochMillis', 'previousEventDigest'], 'policy_event_schema');
    const actor = record(event.actor); exact(actor, ['ownerId', 'clientId', 'sessionId'], 'policy_actor_schema');
    check(actor.ownerId === binding.ownerId && actor.clientId === 'explicit-owner-cli', 'policy_actor_not_explicit_owner');
    const actorSession = id(actor.sessionId); check(!actorSessions.has(actorSession), 'policy_owner_session_reused'); actorSessions.add(actorSession);
    integer(event.appliedAtEpochMillis);
    check(event.revision === index + 1 && event.requestId === requestId && event.requestDigest === digest(canonical(request)), 'policy_request_audit_digest');
    check(event.previousEventDigest === (previousEvent === null ? null : digest(canonical(previousEvent))), 'policy_audit_event_chain');
    same(event, value.auditEvents[index], 'policy_audit_suffix'); same(next.shared, before.shared, 'policy_shared_mutation_forbidden');
    check(record(next.local).revision === index + 1 && next.policyDigest !== prior.policyDigest &&
      record(next.local).documentDigest !== record(prior.local).documentDigest, 'policy_mutation_revision');
    if (index > 0) check(record(next.local).generationId === record(prior.local).generationId, 'policy_local_generation_changed');
    prior = next; previousEvent = event;
  }
  same(prior, after, 'policy_after_revision');
  const local = record(value.afterLocal); exact(local, ['layer', 'revision', 'ruleSettings', 'suppressions', 'suppressionMasks', 'baselines', 'auditEvents'], 'policy_local_schema');
  check(local.layer === 'local', 'policy_local_layer'); same(local.revision, after.local, 'policy_local_revision');
  same(local.ruleSettings, selection.compilerRuleIds.map(ruleId => ({ ruleId, enabled: true })), 'policy_local_exact_rules');
  for (const key of ['suppressions', 'suppressionMasks', 'baselines']) same(local[key], [], 'policy_other_mutations_forbidden');
  same(local.auditEvents, value.auditEvents, 'policy_local_audit_events');
  return frozen(value);
}
