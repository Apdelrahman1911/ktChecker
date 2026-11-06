/** Owner-run acceptance DATA joins, not authority. No entry point or process/model launch.
 * Original bytes stay private. A projected context is never relabeled as full-payload delivery.
 */
import { createHash } from 'node:crypto';
import { PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest, HostError } from '../src/journal.js';
import { prepareDiagnosticContext } from '../src/context-projection.js';
import type { ClientBinding, ContextItem, GateCut, GateResult, HostBinding, JournalState, ReviewEvent } from '../src/types.js';
import { DeliveryReceiptCapture, deliveryBinding, deliveryIdentity, fullCurrentRules, verifyDeliveryReceiptJoin,
  type DeliveryReceiptSnapshot } from './delivery-receipts.js';
import { publicRealText } from './real-model-delivery.js';
import { absoluteReal, REAL_MODEL_CONTEXT_BYTES, REAL_MODEL_PUBLIC_BYTES, REAL_MODEL_RPC_BYTES } from './real-model-run-support.js';

export const ACCEPTANCE_EXPORT_MS = 120_000;
export const ACCEPTANCE_MAX_EVENTS = 256;
export const OBSERVED_ASSURANCE = 'ktchecker/observed-export-one-shot/1';
export const OBSERVED_GAP = 'configuration_input_closure_unproven';
export function check(value: unknown, code: string): asserts value { if (!value) throw new HostError('current_acceptance_' + code); }
export function record(value: unknown): JsonObject {
  check(value !== null && typeof value === 'object' && !Array.isArray(value), 'object'); return value as JsonObject;
}
export function same(actual: unknown, expected: unknown, code: string): void { check(canonical(actual) === canonical(expected), code); }
export function sha(value: unknown): string { check(typeof value === 'string' && /^sha256:[0-9a-f]{64}$/.test(value), 'digest'); return value; }
export function id(value: unknown): string { check(typeof value === 'string' && /^[a-zA-Z0-9_.:-]{1,256}$/.test(value), 'identity'); return value; }
/** Project-model identifiers are NOT opaque RPC UUIDs. In particular genuine JVM IDs are
 * :||main/:||test; pipes and Unicode remain data under Importer.identifier's bounded domain. */
function modelId(value: unknown): string {
  check(typeof value === 'string' && value.length > 0 && value.length <= 4096 && !/\p{Cc}/u.test(value) &&
    !/^[\u0009-\u000D\u001C-\u0020\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]*$/u.test(value), 'model_identity'); return value;
}
export function integer(value: unknown, minimum = 0): number {
  check(Number.isSafeInteger(value) && (value as number) >= minimum, 'integer'); return value as number;
}
export function frozen<T>(value: T): T {
  if (value !== null && typeof value === 'object') { for (const child of Object.values(value)) frozen(child); Object.freeze(value); }
  return value;
}
function boundedJson(value: unknown, limit = REAL_MODEL_RPC_BYTES): void { check(Buffer.byteLength(canonical(value)) <= limit, 'json_bound'); }
function path(value: unknown, empty = false): string {
  check(typeof value === 'string' && value.length <= 4096 && (empty && value === '' || value.length > 0 &&
    !value.startsWith('/') && !value.includes('\\') && !/[\u0000-\u001f\u007f]/u.test(value) &&
    value.split('/').every(part => part !== '' && part !== '.' && part !== '..')), 'relative_path'); return value;
}
const byteDigest = (bytes: Uint8Array) => 'sha256:' + createHash('sha256').update(bytes).digest('hex');

export interface CompletedPatch {
  readonly patchId: string; readonly taskId: string; readonly ordinal: number;
  readonly binding: ClientBinding; readonly baseSnapshotId: string | null; readonly expectedHeadRevision: number;
  readonly roots: readonly { readonly id: string; readonly mountPath: string }[];
  /** Whole immutable regular-file input, not only changed files. Deleted files are absent. */
  readonly files: readonly { readonly rootId: string; readonly path: string; readonly contentBase64: string; readonly executable: boolean }[];
}
export interface FrozenPatch {
  readonly request: CompletedPatch; readonly digest: string;
  readonly entries: readonly JsonObject[];
}
/** Copy BEFORE awaiting submission. Base64 is canonical and decoded bytes, modes and names are all bound. */
export function freezeCompletedPatch(input: CompletedPatch): FrozenPatch {
  boundedJson(input); const value = structuredClone(input);
  id(value.patchId); id(value.taskId); integer(value.ordinal, 1); deliveryBinding(value.binding); id(value.binding.sessionId);
  if (value.baseSnapshotId !== null) sha(value.baseSnapshotId); integer(value.expectedHeadRevision);
  check(Array.isArray(value.roots) && value.roots.length > 0 && value.roots.length <= 16 &&
    Array.isArray(value.files) && value.files.length > 0 && value.files.length <= 40_000, 'source_bound');
  const roots = value.roots.map(root => ({ id: id(root.id), mountPath: path(root.mountPath, true) }));
  check(new Set(roots.map(root => root.id)).size === roots.length, 'source_roots');
  same(roots, [...roots].sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0), 'source_root_order');
  let total = 0;
  const entries = value.files.map(file => {
    id(file.rootId); path(file.path); check(roots.some(root => root.id === file.rootId) && typeof file.executable === 'boolean', 'source_entry');
    check(typeof file.contentBase64 === 'string' && file.contentBase64.length <= REAL_MODEL_RPC_BYTES, 'source_byte_bound');
    const bytes = Buffer.from(file.contentBase64, 'base64');
    check(bytes.toString('base64') === file.contentBase64 && (total += bytes.length) <= REAL_MODEL_RPC_BYTES, 'source_bytes');
    return { rootId: file.rootId, path: file.path, kind: 'FILE', digest: byteDigest(bytes), size: bytes.length,
      executable: file.executable, symlinkTarget: null };
  });
  const keys = entries.map(entry => entry.rootId + '\u0000' + entry.path);
  check(new Set(keys).size === keys.length, 'source_duplicate'); same(keys, [...keys].sort(), 'source_order');
  return frozen({ request: value, digest: digest(canonical(value)), entries });
}

export interface SnapshotLineage { readonly snapshotId: string; readonly headRevision: number; readonly sourceEpoch: number;
  readonly manifestDigest: string; readonly contract: JsonObject; readonly binding: ClientBinding;
  readonly roots: readonly JsonObject[]; readonly entries: readonly JsonObject[] }
function snapshotDelta(before: readonly JsonObject[], after: readonly JsonObject[]): JsonObject[] {
  const key = (entry: JsonObject) => String(entry.rootId) + '\u0000' + String(entry.path);
  const old = new Map(before.map(entry => [key(entry), entry])), current = new Map(after.map(entry => [key(entry), entry]));
  return [...new Set([...old.keys(), ...current.keys()])].sort().flatMap(name => {
    const previous = old.get(name) ?? null, next = current.get(name) ?? null;
    if (canonical(previous) === canonical(next)) return [];
    const entry = next ?? previous!;
    const hash = (value: JsonObject | null) => value === null ? null : digest('ktchecker:snapshot:entry-v1\u0000' + canonical(value));
    return [{ rootId: entry.rootId, path: entry.path, operation: previous === null ? 'added' : next === null ? 'deleted' : 'modified',
      beforeDigest: hash(previous), afterDigest: hash(next) }];
  });
}
/** Existing SnapshotStore hash scheme. This verifies returned bytes, not a fabricated live capture capability. */
export function verifySnapshotCommit(patch: FrozenPatch, value: unknown, previous: SnapshotLineage | null): SnapshotLineage {
  boundedJson(value); const commit = record(value), manifest = record(commit.manifest), request = patch.request;
  check(commit.replayed === false && manifest.schemaVersion === 1, 'snapshot_replayed_or_schema');
  check(Array.isArray(manifest.roots), 'snapshot_roots');
  const snapshotId = sha(manifest.snapshotId), roots = manifest.roots.map(record), capture = record(manifest.capture), scope = record(manifest.scope);
  check(roots.length === request.roots.length, 'snapshot_roots');
  same(roots.map(root => ({ id: root.id, mountPath: root.mountPath })), request.roots, 'snapshot_roots');
  // Immutable overlay is the selected scope; do not read or claim current live source paths.
  check(roots.every(root => root.livePath === null), 'snapshot_live_root_not_selected');
  same(manifest.entries, patch.entries, 'snapshot_original_bytes');
  if (previous) { same(request.binding, previous.binding, 'snapshot_client_lineage'); same(roots, previous.roots, 'snapshot_root_lineage'); }
  same(manifest.delta, snapshotDelta(previous?.entries ?? [], patch.entries), 'snapshot_exact_delta');
  same(manifest.identity, { ownerId: request.binding.ownerId, workspaceId: request.binding.workspaceId, worktreeId: request.binding.worktreeId }, 'snapshot_namespace');
  check(manifest.clientId === request.binding.clientId && manifest.sessionId === request.binding.sessionId && manifest.patchId === request.patchId,
    'snapshot_producer');
  const expectedBase = previous?.snapshotId ?? null;
  check(request.baseSnapshotId === expectedBase && manifest.parentSnapshotId === expectedBase && manifest.producerBaseSnapshotId === expectedBase,
    'snapshot_parent_chain');
  const revision = integer(manifest.headRevision, 1), sourceEpoch = integer(capture.sourceEpoch, 1);
  check(request.expectedHeadRevision === (previous?.headRevision ?? 0) && revision === request.expectedHeadRevision + 1 &&
    sourceEpoch === (previous?.sourceEpoch ?? 0) + 1 && commit.headRevision === revision && commit.sourceEpoch === sourceEpoch,
    'snapshot_revision_chain');
  check(capture.kind === 'IMMUTABLE_OVERLAY' && capture.allWritersCooperate === false && capture.leaseFence === null, 'snapshot_capture_scope');
  same(capture.uncertainty, [], 'snapshot_uncertainty'); same(scope.exclusions, [], 'snapshot_exclusions'); same(scope.unknownInputs, [], 'snapshot_unknown');
  check(digest('ktchecker:snapshot:tree-v1\u0000' + canonical({ roots: manifest.roots, entries: manifest.entries })) === manifest.treeDigest &&
    digest('ktchecker:snapshot:manifest-v1\u0000' + canonical({ ...manifest, snapshotId: '' })) === snapshotId, 'snapshot_digest');
  const files = patch.entries.map(entry => {
    const mount = roots.find(root => root.id === entry.rootId)!.mountPath as string;
    return { path: mount === '' ? entry.path : mount + '/' + entry.path, digest: entry.digest, size: entry.size };
  }).sort((a, b) => String(a.path) < String(b.path) ? -1 : String(a.path) > String(b.path) ? 1 : 0);
  check(new Set(files.map(file => file.path)).size === files.length, 'snapshot_logical_alias');
  return frozen({ snapshotId, headRevision: revision, sourceEpoch, manifestDigest: digest(canonical(manifest)),
    binding: structuredClone(request.binding), roots, entries: structuredClone(patch.entries), contract: {
    snapshotId, ownerId: request.binding.ownerId, workspaceId: request.binding.workspaceId, worktreeId: request.binding.worktreeId,
    files, parentSnapshotId: expectedBase, captureKind: 'immutable_overlay', declaredRoots: roots.map(root => root.id), scopeExclusions: [], unknownInputs: [],
  } });
}

/** Data projected by the actual same-Workbench adapter. Never deserialize these fields as permits.
 * The owner must enforce same imported OBJECT, current custody and original clock in its typed path.
 */
export interface ParentAdmissionData {
  readonly snapshotCommit: JsonObject; readonly declaredPlan: JsonObject; readonly parent: JsonObject;
  readonly observed: { readonly invocationId: string; readonly evidenceIdentity: string; readonly observedEvidenceDigest: string; readonly modelDigest: string;
    readonly ownerCodeImageDigest: string; readonly originalDeadlineIdentity: string; readonly originalDeadlineMillis: number;
    readonly assurance: typeof OBSERVED_ASSURANCE; readonly reasons: readonly string[];
    /** Documentary metadata of the retained prior object, never reimported compiler authority. */
    readonly documentaryBefore: { readonly snapshotId: string; readonly invocationId: string; readonly evidenceIdentity: string; readonly modelDigest: string } | null };
  readonly compilerRuleIds: readonly string[];
}
export interface BoundParent {
  readonly patch: FrozenPatch; readonly snapshot: SnapshotLineage; readonly data: ParentAdmissionData;
  readonly reviewIds: readonly string[]; readonly childPatchIds: readonly string[];
}
function identityOfParent(parent: JsonObject): JsonObject {
  check(Array.isArray(parent.slots), 'parent_slots');
  return { contractVersion: parent.contractVersion, planId: parent.planId, clientId: parent.clientId, patchId: parent.patchId,
    sourceSnapshotId: parent.sourceSnapshotId, planDigest: parent.planDigest,
    slots: (parent.slots as unknown[]).map(raw => { const slot = record(raw); return { slotId: slot.slotId, reviewId: slot.reviewId,
      context: slot.context, requiredRuleIds: slot.requiredRuleIds }; }) };
}
function observedParentShape(parent: JsonObject): void {
  check(Array.isArray(parent.slots) && parent.slots.length === 2, 'parent_two_slots');
  const slots = parent.slots.map(record);
  const states = ['queued', 'running', 'deferred', 'incomplete', 'unavailable', 'cancelled'];
  for (const slot of slots) check(states.includes(String(slot.state)) && Array.isArray(slot.reasons) &&
    slot.reasons.filter(reason => reason === OBSERVED_GAP).length === 1, 'parent_observed_slot_state_or_gap');
  const cancelled = slots.some(slot => slot.state === 'cancelled'), incomplete = slots.some(slot => ['incomplete', 'unavailable'].includes(String(slot.state)));
  check(['pending', 'incomplete', 'cancelled'].includes(String(parent.state)) && (!cancelled || parent.state === 'cancelled') &&
    (parent.state !== 'incomplete' || incomplete) && (parent.state !== 'pending' || !incomplete && !cancelled), 'parent_state');
  same(parent.reasons, ['produced_plan_preview_unqualified', OBSERVED_GAP,
    ...(parent.state === 'cancelled' ? ['parent_cancelled_obligations_retained'] : [])], 'parent_observed_reasons');
}
function changedContractPaths(before: SnapshotLineage | null, after: SnapshotLineage): string[] {
  const old = new Map(((before?.contract.files ?? []) as unknown[]).map(record).map(file => [String(file.path), file]));
  const current = new Map((after.contract.files as unknown[]).map(record).map(file => [String(file.path), file]));
  return [...new Set([...old.keys(), ...current.keys()])].sort().filter(path => canonical(old.get(path) ?? null) !== canonical(current.get(path) ?? null));
}
export function verifyParentAdmission(patch: FrozenPatch, raw: ParentAdmissionData, previous: BoundParent | null,
  expectedCompilerRules: readonly string[], expectedOwnerCodeImage: string): BoundParent {
  boundedJson(raw); const data = structuredClone(raw), plan = record(data.declaredPlan), parent = record(data.parent), observed = data.observed;
  const snapshot = verifySnapshotCommit(patch, data.snapshotCommit, previous?.snapshot ?? null), binding = patch.request.binding;
  same(plan.binding, binding, 'plan_binding'); const request = record(plan.request);
  check(request.patchId === patch.request.patchId, 'plan_patch'); id(request.planRef); id(request.idempotencyKey);
  same(plan.requiredRecipientClientIds, [binding.clientId], 'plan_recipient_scope');
  check(parent.contractVersion === PREVIEW_CONTRACT && parent.clientId === binding.clientId && parent.patchId === patch.request.patchId &&
    parent.sourceSnapshotId === snapshot.snapshotId && parent.state !== 'complete' && parent.planDigest === digest(canonical(plan)), 'plan_admission');
  observedParentShape(parent);
  id(parent.planId); sha(plan.modelCertificateId); sha(plan.impactCertificateId); sha(plan.generatedInputsDigest);
  check(plan.sourceBindingDigest === digest(canonical(snapshot.contract)), 'plan_snapshot_binding');
  check(Array.isArray(plan.slots) && Array.isArray(parent.slots), 'plan_slots');
  const slots = plan.slots.map(record), statuses = parent.slots.map(record), graph = record(plan.jvmProduced);
  check(slots.length === 2 && statuses.length === 2 && graph.profile === 'ktchecker/produced-jvm-plan/1' && graph.friend === true, 'plan_two_slot_required');
  const producer = record(graph.producer), consumer = record(graph.consumer);
  for (const key of ['graphDigest', 'importRequestDigest', 'modelDigest', 'toolchainDigest', 'referenceDigest', 'catalogDigest', 'requiredRulesDigest', 'intentDigest']) sha(graph[key]);
  for (const selected of [producer, consumer]) { id(selected.slotId); modelId(selected.compilationId); sha(selected.rawContextDigest); sha(selected.argumentLayoutDigest); }
  modelId(graph.moduleId); check(integer(graph.classpathIndex) <= 16, 'plan_classpath_ordinal');
  const observedExport = record(graph.observedExport);
  same(observedExport, { binding, invocationId: observed.invocationId, evidenceDigest: observed.evidenceIdentity,
    assurance: OBSERVED_ASSURANCE }, 'plan_observed_evidence');
  check(plan.modelCertificateId === observed.evidenceIdentity, 'plan_model_evidence_identity');
  same(slots.map(slot => slot.slotId).sort(), [producer.slotId, consumer.slotId].sort(), 'plan_graph_slots');
  check(producer.slotId !== consumer.slotId && producer.compilationId !== consumer.compilationId, 'plan_distinct_slots');
  same(slots.map(slot => slot.slotId), slots.map(slot => slot.slotId).sort(), 'plan_slot_order');
  const allowedReasons = ['observed_compilation_context_unavailable', OBSERVED_GAP, 'jvm_produced_output_deferred',
    'friend_artifact_rewrite_unavailable', 'compiler_arguments_require_verified_rewrite'];
  const observedPolicies = { 'ktchecker.model-assurance': OBSERVED_ASSURANCE, 'ktchecker.observed-export-invocation': observed.invocationId,
    'ktchecker.observed-model-evidence': observed.evidenceIdentity, 'ktchecker.observed-export-closure': OBSERVED_GAP,
    'ktchecker.observed-export-evidence': digest(canonical(observedExport)) };
  const reviewIds: string[] = [], childPatchIds: string[] = [];
  for (const [index, slot] of slots.entries()) {
    const input = record(slot.input), context = record(input.context), status = statuses[index]!;
    id(slot.slotId); same(fullCurrentRules(input.requiredRuleIds), fullCurrentRules(status.requiredRuleIds), 'plan_full_rule_vector');
    check(digest(canonical(input.requiredRuleIds)) === graph.requiredRulesDigest && slot.state === 'declared' && slot.identity === null &&
      Array.isArray(slot.reasons) && slot.reasons.includes(OBSERVED_GAP) && slot.reasons.includes('observed_compilation_context_unavailable') &&
      slot.reasons.every(reason => allowedReasons.includes(String(reason))), 'plan_declared_observed_slot');
    same(input.snapshot, snapshot.contract, 'plan_whole_snapshot'); check(context.fullProject === false, 'plan_full_project_upgrade');
    const name = slot.slotId === producer.slotId ? 'main' : 'test', scope = record(context.scope), policies = record(context.policies);
    same(scope, { target: '', sourceSet: name, module: graph.moduleId, compilation: name }, 'plan_exact_exported_scope');
    check(context.modelDigest === graph.modelDigest && context.toolchainDigest === graph.toolchainDigest && context.compilerVersion === '2.4.21' &&
      context.configurationDigest === record(record(slots[0]!.input).context).configurationDigest &&
      input.execution === null && input.outputRoot === null && input.materializedRoot === record(slots[0]!.input).materializedRoot,
      'plan_declared_input_binding');
    absoluteReal(input.materializedRoot); sha(context.dependencyDigest); sha(context.configurationDigest);
    check(Array.isArray(context.classpath) && context.classpath.length <= 16 && Array.isArray(context.compilerArguments) &&
      context.compilerArguments.every(argument => typeof argument === 'string') && Array.isArray(context.compilationFiles) && context.compilationFiles.length > 0,
      'plan_context_shape');
    const members = context.compilationFiles.map(value => path(value));
    check(new Set(members).size === members.length && members.every(value => value.endsWith('.kt') &&
      (snapshot.contract.files as JsonObject[]).some(file => file.path === value)), 'plan_compilation_members');
    for (const entry of context.classpath.map(record)) { sha(entry.digest); absoluteReal(entry.path);
      check(typeof entry.coordinate === 'string' && String(entry.path).endsWith('.jar'), 'plan_classpath_shape'); }
    for (const [key, value] of Object.entries(observedPolicies)) check(policies[key] === value, 'plan_five_observed_policies');
    check(policies['ktchecker.catalog-inventory'] === graph.catalogDigest && typeof policies['ktchecker.activation-plan'] === 'string' &&
      policies['ktchecker.activation-plan'].length > 0, 'plan_catalog_activation');
    const rawPolicies = Object.fromEntries(Object.entries(policies).filter(([key]) =>
      !['ktchecker.catalog-inventory', 'ktchecker.activation-plan', 'ktchecker.observed-export-evidence'].includes(key)));
    check(digest(canonical({ ...context, policies: rawPolicies })) === (slot.slotId === producer.slotId ? producer : consumer).rawContextDigest,
      'plan_original_observed_context');
    check(input.baselineSnapshotId === (previous?.snapshot.snapshotId ?? null), 'plan_documentary_baseline');
    same(input.changedPaths, changedContractPaths(previous?.snapshot ?? null, snapshot), 'plan_exact_changed_paths');
    same(status.context, context.scope, 'plan_context'); check(status.slotId === slot.slotId, 'plan_slot_identity');
    reviewIds.push(id(status.reviewId)); childPatchIds.push('plan-child:' + digest(canonical([parent.planId, slot.slotId])));
  }
  check(new Set(reviewIds).size === 2, 'plan_review_alias');
  same(data.compilerRuleIds, expectedCompilerRules, 'compiler_projection');
  check(expectedCompilerRules.length === 14 && new Set(expectedCompilerRules).size === 14 &&
    expectedCompilerRules.every(rule => rule.startsWith('ktc.compiler.') && (record(slots[0]!.input).requiredRuleIds as string[]).includes(rule)), 'compiler_exact14');
  same(expectedCompilerRules, [...expectedCompilerRules].sort(), 'compiler_order');
  id(observed.invocationId); sha(observed.evidenceIdentity); sha(observed.observedEvidenceDigest); sha(observed.modelDigest); id(observed.originalDeadlineIdentity);
  check(observed.assurance === OBSERVED_ASSURANCE && observed.reasons.includes(OBSERVED_GAP) &&
    observed.ownerCodeImageDigest === sha(expectedOwnerCodeImage) && observed.originalDeadlineMillis === ACCEPTANCE_EXPORT_MS &&
    graph.originalDeadlineIdentity === observed.originalDeadlineIdentity && graph.originalDeadlineMillis === ACCEPTANCE_EXPORT_MS &&
    graph.modelDigest === observed.modelDigest, 'observed_original_invocation');
  const consumerInput = record(slots.find(slot => slot.slotId === consumer.slotId)!.input);
  check(Number(graph.classpathIndex) <= (record(consumerInput.context).classpath as unknown[]).length, 'plan_deferred_classpath_index');
  same(observed.documentaryBefore, previous === null ? null : { snapshotId: previous.snapshot.snapshotId,
    invocationId: previous.data.observed.invocationId, evidenceIdentity: previous.data.observed.evidenceIdentity,
    modelDigest: previous.data.observed.modelDigest }, 'observed_documentary_before_lineage');
  if (previous) {
    same(binding, previous.patch.request.binding, 'plan_client_lineage');
    const oldGraph = record(previous.data.declaredPlan.jvmProduced);
    same([graph.moduleId, producer.compilationId, consumer.compilationId], [oldGraph.moduleId, record(oldGraph.producer).compilationId,
      record(oldGraph.consumer).compilationId], 'plan_context_retirement_not_supported');
    check(previous.data.observed.invocationId !== observed.invocationId &&
    previous.data.observed.originalDeadlineIdentity !== observed.originalDeadlineIdentity && previous.data.parent.planId !== parent.planId &&
    previous.data.parent.planDigest !== parent.planDigest && !previous.reviewIds.some(review => reviewIds.includes(review)), 'owner_invocation_reused');
  }
  return frozen({ patch, snapshot, data, reviewIds, childPatchIds });
}

export interface ParentCompletionData { readonly parent: JsonObject; readonly children: readonly JsonObject[] }
export function explicitlyUnavailable(parent: BoundParent, completion: ParentCompletionData, reviewId: string): boolean {
  const slot = (completion.parent.slots as unknown[]).map(record).find(value => value.reviewId === reviewId);
  const child = completion.children.find(value => value.reviewId === reviewId);
  return parent.reviewIds.includes(reviewId) && slot?.state === 'unavailable' && Array.isArray(slot.reasons) && slot.reasons.length > 0 &&
    child?.state === 'incomplete' && child.result === null && Array.isArray(child.reasons) && child.reasons.length > 0;
}
/** Exact final child results decide terminality. Observed parent state remains unqualified/incomplete. */
export function verifyParentCompletion(parent: BoundParent, raw: ParentCompletionData): boolean {
  boundedJson(raw); same(identityOfParent(raw.parent), identityOfParent(parent.data.parent), 'completed_parent_identity');
  observedParentShape(raw.parent);
  check(raw.parent.state !== 'complete' && raw.children.length === 2, 'completed_parent_upgrade');
  let terminal = true;
  for (const [index, reviewId] of parent.reviewIds.entries()) {
    const matches = raw.children.filter(child => child.reviewId === reviewId); check(matches.length === 1, 'completed_child_set');
    const child = matches[0]!;
    check(child.contractVersion === PREVIEW_CONTRACT && child.clientId === parent.patch.request.binding.clientId &&
      child.patchId === parent.childPatchIds[index] && child.sourceSnapshotId === parent.snapshot.snapshotId &&
      child.sequence === 2 * (parent.patch.request.ordinal - 1) + index + 1, 'completed_child_identity');
    check(child.cancelled === false && ['queued', 'running', 'deferred', 'incomplete'].includes(String(child.state)), 'completed_cancelled_or_unknown_state');
    check(Array.isArray(child.reasons) && child.reasons.includes(OBSERVED_GAP) && child.reasons.includes('produced_plan_preview_unqualified'),
      'completed_observed_gap_missing');
    const slot = (raw.parent.slots as unknown[]).map(record).find(value => value.reviewId === reviewId)!;
    // The owner reads parent then children in separate read transactions. Publication can
    // legitimately advance between them; poll again on this ORIGINAL bound, never treat
    // a progressive/non-converged status pair as terminal or spend a model invocation.
    if (!(slot.state === child.state || slot.state === 'unavailable' && child.state === 'incomplete')) { terminal = false; continue; }
    // A running work row can contain a progressive result. It is NEVER terminal delivery.
    if (child.state !== 'incomplete') { terminal = false; continue; }
    if (child.result === null || child.result === undefined) {
      if (!explicitlyUnavailable(parent, raw, reviewId)) terminal = false;
      continue;
    }
    const result = record(child.result); check(result.status === 'incomplete' || result.status === 'partial', 'completed_qualification_upgrade');
    const coverage = result.coverage; check(Array.isArray(coverage), 'completed_whole_vector');
    const rows = coverage.map(record), required = rows.filter(row => row.required === true);
    const declaredInput = record(record((parent.data.declaredPlan.slots as unknown[])[index]).input);
    const rules = fullCurrentRules(declaredInput.requiredRuleIds);
    check(rows.length === rules.length && required.length === rules.length, 'completed_whole_vector');
    same(required.map(row => id(row.ruleId)).sort(), rules, 'completed_full_rule_coverage');
    const scope = record(declaredInput.context).scope;
    for (const row of required) { same(row.context, scope, 'completed_coverage_context');
      check(row.qualified === false && row.certificateId === null, 'completed_coverage_upgrade'); }
    check(Array.isArray(result.findings) && Array.isArray(result.rawDiagnostics) && Array.isArray(result.reasons), 'completed_result_shape');
    check(result.reasons.includes(OBSERVED_GAP) || result.reasons.includes('kotlin-jvm-live-model-preview:' + OBSERVED_GAP), 'completed_provider_gap_missing');
    for (const finding of result.findings.map(record)) {
      same(finding.context, scope, 'completed_finding_scope');
      check((declaredInput.requiredRuleIds as string[]).includes(id(finding.ruleId)), 'completed_finding_rule');
      for (const location of [finding.location, ...((finding.relatedLocations ?? []) as unknown[])].map(record)) {
        const file = (parent.snapshot.contract.files as unknown[]).map(record).find(file => file.path === location.path);
        check(file && file.digest === location.fileDigest, 'completed_finding_original_source');
        integer(location.line, 1); integer(location.column); integer(location.endLine, 1); integer(location.endColumn);
      }
    }
  }
  return terminal;
}

/** Transparent existing broker wrapper. It captures every original event in sequence; any unknown
 * RPC outcome poisons this run. It never retries, restarts, cancels, closes or reopens anything.
 */
export class CurrentAcceptanceBroker implements BrokerPort {
  // Fixed CURRENT catalog and observed-only assurance, independently selected before any event.
  // Historical1663 remains available only to historical capture callers, never this runner.
  readonly receipts = new DeliveryReceiptCapture({ expectedProducedAssurance: OBSERVED_ASSURANCE, expectedProducedRuleVector: 'current-full1665' });
  private readonly events: ReviewEvent[] = []; private cursor: string | null = null;
  private failed = false; private active = false;
  private readonly completedGates: JsonObject[] = [];
  private readonly deliveredSnapshots = new Set<string>();
  constructor(private readonly actual: BrokerPort, readonly binding: ClientBinding) { this.receipts.bind(binding); }
  poison(): void { this.failed = true; }
  originals(): readonly ReviewEvent[] { return frozen(structuredClone(this.events)); }
  finalCompletions(): readonly JsonObject[] { return frozen(structuredClone(this.completedGates)); }
  markDelivered(snapshotId: string): void {
    check(!this.failed && !this.active && !this.deliveredSnapshots.has(sha(snapshotId)), 'delivered_snapshot_replayed');
    this.deliveredSnapshots.add(snapshotId);
  }
  async request<T = JsonObject>(method: string, params: JsonObject): Promise<T> {
    check(!this.failed && !this.active, 'broker_unknown_or_concurrent');
    check(['review.events', 'review.ack', 'review.observe', 'gate.prepare', 'gate.complete'].includes(method), 'broker_method_scope');
    check(record(params.credential).clientId === this.binding.clientId, 'broker_client');
    if (method === 'review.events') same(params.cursor, this.cursor, 'broker_cursor_replay');
    const capture = this.receipts.beforeRequest(method, params); this.active = true;
    try {
      const result = await this.actual.request<T>(method, params);
      check(!this.failed, 'broker_late_return');
      if (method === 'review.events') {
        const page = record(result); check(Array.isArray(page.events) && page.events.length <= 100 &&
          typeof page.hasMore === 'boolean' && typeof page.cursor === 'string' && (!page.hasMore || page.events.length > 0), 'event_page');
        const additions = page.events.map(raw => {
          const event = record(raw); check(event.contractVersion === PREVIEW_CONTRACT && event.clientId === this.binding.clientId &&
            event.generation === this.binding.generation && typeof event.payload === 'string' &&
            Buffer.byteLength(event.payload) <= REAL_MODEL_PUBLIC_BYTES && digest(event.payload) === event.payloadDigest, 'original_event');
          check(!this.deliveredSnapshots.has(String(event.sourceSnapshotId)), 'late_event_for_delivered_snapshot');
          // Explicit fields only; never clone a transport object or credentials.
          return { contractVersion: PREVIEW_CONTRACT, eventId: id(event.eventId), clientId: this.binding.clientId, sequence: integer(event.sequence, 1),
            reviewId: id(event.reviewId), patchId: id(event.patchId), sourceSnapshotId: sha(event.sourceSnapshotId), kind: id(event.kind),
            deliveryRequired: event.deliveryRequired, payloadDigest: sha(event.payloadDigest), payload: event.payload, generation: this.binding.generation } as ReviewEvent;
        });
        check(this.events.length + additions.length <= ACCEPTANCE_MAX_EVENTS, 'event_count_bound');
        for (const [index, event] of additions.entries()) check(event.sequence === this.events.length + index + 1 &&
          typeof event.deliveryRequired === 'boolean' && !this.events.some(old => old.eventId === event.eventId) &&
          additions.filter(other => other.eventId === event.eventId).length === 1, 'event_gap_revision_or_replay');
        capture?.returned(result); this.events.push(...frozen(additions)); this.cursor = page.cursor;
      } else { capture?.returned(result);
        if (method === 'gate.complete') {
          const gate = record(result);
          // Selected response fields only: no serialization of credentials/transport extras.
          this.completedGates.push(frozen({ contractVersion: gate.contractVersion, cutId: gate.cutId, clientId: gate.clientId,
            lineageId: gate.lineageId, generation: gate.generation, allowed: gate.allowed, actionPerformed: gate.actionPerformed,
            reasons: structuredClone(gate.reasons) }));
        }
      }
      return result;
    } catch (error) { this.failed = true; capture?.rejected(); throw error; }
    finally { this.active = false; }
  }
}

export interface OriginalContextJoin {
  readonly originalEventsDigest: string; readonly requiredEventIds: readonly string[]; readonly originalPayloadDigests: readonly string[];
  readonly contextDigests: readonly string[]; readonly serializedApplicationRequestDigest: string; readonly applicationRequestIdDigest: string;
  readonly receiptScope: 'projected_context_only'; readonly originalCoverageBytesSentToModel: false; readonly internalInferenceRequestTrace: false;
}
/** Call before insertion and again after actual application evidence. It admits no missing required
 * event, parent vector, final child, stale snapshot or synthetic replacement of original bytes.
 */
export function requiredContexts(parent: BoundParent, completion: ParentCompletionData, state: JournalState,
  originalEvents: readonly ReviewEvent[], forbidden: readonly string[]): { events: ReviewEvent[]; contexts: ContextItem[] } {
  check(verifyParentCompletion(parent, completion), 'review_not_terminal');
  same(state.binding.client, parent.patch.request.binding, 'journal_binding');
  check(state.currentSnapshotId === parent.snapshot.snapshotId && !state.pendingModelRequest && Object.keys(state.submissions).length === 0,
    'journal_submission_or_pending_replay');
  check(originalEvents.length <= ACCEPTANCE_MAX_EVENTS && Object.keys(state.inbox).length === originalEvents.length, 'original_inbox_domain');
  for (const event of originalEvents) {
    const entry = state.inbox[event.eventId]; same(entry?.event, event, 'original_inbox_bytes'); publicRealText(event.payload, forbidden);
    check(event.sourceSnapshotId === parent.snapshot.snapshotId || parent.patch.request.ordinal === 2 &&
      event.sourceSnapshotId === parent.patch.request.baseSnapshotId, 'original_unrelated_snapshot');
    if (event.sourceSnapshotId === parent.snapshot.snapshotId && event.deliveryRequired) check(entry?.stale === false, 'current_delivery_stale');
    if (event.sourceSnapshotId !== parent.snapshot.snapshotId && event.deliveryRequired) check(entry && entry.acknowledged && entry.observed &&
      entry.inserted !== null && entry.observation !== null && entry.item !== null && entry.stale === false, 'historical_delivery_unconfirmed');
  }
  const events = originalEvents.filter(event => event.sourceSnapshotId === parent.snapshot.snapshotId);
  check(events.length > 0, 'required_events_missing');
  for (const event of events) {
    const index = parent.reviewIds.indexOf(event.reviewId); check(index >= 0 && event.patchId === parent.childPatchIds[index], 'event_parent_domain');
    const payload = record(JSON.parse(event.payload));
    check(payload.reviewId === event.reviewId && payload.clientId === event.clientId && payload.patchId === event.patchId &&
      payload.sourceSnapshotId === event.sourceSnapshotId && payload.contractVersion === PREVIEW_CONTRACT &&
      payload.sequence === 2 * (parent.patch.request.ordinal - 1) + index + 1, 'event_payload_binding');
    check(Array.isArray(payload.reasons) && payload.reasons.includes(OBSERVED_GAP), 'original_child_observed_gap_missing');
    if (event.kind === 'accepted') {
      check(payload.state === 'accepted' && payload.result === null && payload.cancelled === false && payload.parentPlan === null,
        'original_child_acceptance_shape');
      same(payload.reasons, [OBSERVED_GAP], 'original_child_acceptance_gap');
    }
    if (event.kind.startsWith('plan_')) {
      const plan = record(payload.parentPlan); same(identityOfParent(plan), identityOfParent(parent.data.parent), 'event_plan_vector');
      observedParentShape(plan);
      check(plan.state !== 'complete' && event.deliveryRequired === true, 'event_plan_upgrade_or_optional');
      check(payload.state === event.kind && payload.result === null && event.reviewId === parent.reviewIds.at(-1), 'event_plan_anchor');
      same(payload.reasons, [OBSERVED_GAP], 'event_plan_observed_gap');
      check(payload.cancelled === ((plan.slots as unknown[]).map(record).at(-1)!.state === 'cancelled'), 'event_plan_anchor_cancelled');
      if (event.kind === 'plan_accepted') same(plan, parent.data.parent, 'original_plan_acceptance');
    }
  }
  check(events.filter(event => event.kind === 'plan_accepted' && event.deliveryRequired).length === 1, 'plan_acceptance_event_missing');
  check(events.some(event => ['plan_resolved', 'plan_unavailable'].includes(event.kind) && event.deliveryRequired), 'plan_resolution_event_missing');
  for (const reviewId of parent.reviewIds) {
    check(events.filter(event => event.reviewId === reviewId && event.kind === 'accepted' && event.deliveryRequired).length === 1, 'child_acceptance_event_missing');
    if (explicitlyUnavailable(parent, completion, reviewId)) {
      const expected = (completion.parent.slots as unknown[]).map(record).find(slot => slot.reviewId === reviewId)!;
      check(events.some(event => {
        if (!event.deliveryRequired || !['plan_resolved', 'plan_unavailable'].includes(event.kind)) return false;
        const plan = record(record(JSON.parse(event.payload)).parentPlan);
        return (plan.slots as unknown[]).map(record).some(slot => slot.reviewId === reviewId && canonical(slot) === canonical(expected));
      }), 'unavailable_child_notification_missing');
      continue;
    }
    const terminals = events.filter(event => event.reviewId === reviewId && event.kind === 'result' && event.deliveryRequired);
    check(terminals.length === 1, 'final_child_event_missing');
    const payload = record(JSON.parse(terminals[0]!.payload)), child = completion.children.find(value => value.reviewId === reviewId)!;
    same(payload, child, 'final_child_payload');
  }
  const required = events.filter(event => event.deliveryRequired);
  check(required.length <= 100 && required.length > 0, 'required_event_bound');
  const contexts = required.map(event => prepareDiagnosticContext(event, state.binding, parent.snapshot.snapshotId)).sort((a, b) => a.marker.localeCompare(b.marker));
  check(contexts.reduce((bytes, item) => bytes + Buffer.byteLength(item.text), 0) <= REAL_MODEL_CONTEXT_BYTES, 'whole_context_bound');
  for (const item of contexts) publicRealText(item.text, forbidden);
  return { events: [...required], contexts };
}
export function verifyOriginalContextJoin(parent: BoundParent, completion: ParentCompletionData, state: JournalState,
  originalEvents: readonly ReviewEvent[], phase: JsonObject, receipts: DeliveryReceiptSnapshot, forbidden: readonly string[]): OriginalContextJoin {
  const prepared = requiredContexts(parent, completion, state, originalEvents, forbidden), binding = state.binding;
  check(phase.effort === 'max' && phase.model === 'gpt-6-astra' && phase.internalInferenceRequestTrace === false, 'requested_model_or_effort');
  const joined = verifyDeliveryReceiptJoin(receipts, phase, binding.client, { maxApplicationBytes: REAL_MODEL_RPC_BYTES,
    maxContextBytes: REAL_MODEL_CONTEXT_BYTES, maxPublicBytes: REAL_MODEL_PUBLIC_BYTES });
  const serialized = phase.serializedApplicationRequest; check(typeof serialized === 'string', 'application_bytes');
  publicRealText(serialized, forbidden);
  check(typeof phase.verifiedPublicResponseText === 'string', 'public_response_bytes'); publicRealText(phase.verifiedPublicResponseText, forbidden);
  const application = record(JSON.parse(serialized)), params = record(application.params), output = record(JSON.parse(record(params.toolOutput).output as string));
  check(params.threadId === binding.hostThreadId && params.effort === 'max', 'application_thread');
  same(output.contexts, prepared.contexts.map(item => ({ contextDigest: item.digest, text: item.text })), 'original_projection_application_bytes');
  const observationContextDigests = prepared.contexts.map(item => item.digest).sort();
  for (const event of prepared.events) {
    const entry = state.inbox[event.eventId]!, item = prepared.contexts.find(value => value.marker === `ktchecker-event:${event.eventId}:${event.payloadDigest}`)!;
    same(entry.item, item, 'durable_projection'); check(entry.acknowledged === true && entry.observed === true && entry.inserted && entry.observation,
      'durable_receipts_not_confirmed');
    // Initial live validation is not a later durable-state attestation. Rebind every
    // current insertion header, saved intent and exact ALREADY SORTED observation vector.
    check(entry.inserted.marker === item.marker && entry.inserted.hostThreadId === binding.hostThreadId &&
      entry.inserted.generation === binding.client.generation, 'durable_insertion_binding');
    check(entry.insertionReceiptId === entry.inserted.receipt.receiptId && entry.inserted.receipt.contextDigest === item.digest,
      'durable_insertion_receipt');
    check(Array.isArray(entry.observation.contextDigests), 'durable_observation_contexts');
    same(entry.observation.contextDigests, observationContextDigests, 'durable_observation_contexts');
    const rows = receipts.rows.filter(value => value.method === 'review.observe' && value.event.eventIdDigest === deliveryIdentity('event', event.eventId) &&
      value.observation?.applicationRequestIdDigest === joined.applicationRequestIdDigest);
    check(rows.length > 0, 'original_event_receipt');
    // Legitimate separate identical confirmations remain allowed; a later revised
    // confirmation cannot hide behind find-first's otherwise valid durable join.
    for (const row of rows) {
      check(row.event.sequence === event.sequence && row.event.payloadDigest === digest(event.payload), 'original_event_receipt');
      same(row.ack, { receiptIdDigest: deliveryIdentity('ack-receipt', entry.inserted.receipt.receiptId), contextDigest: entry.inserted.receipt.contextDigest,
        contextItemIdDigest: deliveryIdentity('context-item', entry.inserted.receipt.contextItemId), insertedAtDigest: digest(entry.inserted.receipt.insertedAt) },
      'durable_ack_receipt');
      same(row.observation, { observationIdDigest: deliveryIdentity('observation', entry.observation.observationId),
        modelRequestIdDigest: deliveryIdentity('model-request', entry.observation.modelRequestId),
        applicationRequestIdDigest: joined.applicationRequestIdDigest, contextDigests: entry.observation.contextDigests }, 'durable_observation_receipt');
    }
  }
  return frozen({ originalEventsDigest: digest(canonical(originalEvents)), requiredEventIds: prepared.events.map(event => event.eventId),
    originalPayloadDigests: prepared.events.map(event => event.payloadDigest), contextDigests: prepared.contexts.map(item => item.digest),
    serializedApplicationRequestDigest: digest(serialized), applicationRequestIdDigest: joined.applicationRequestIdDigest,
    receiptScope: 'projected_context_only', originalCoverageBytesSentToModel: false, internalInferenceRequestTrace: false });
}

export function verifyDeniedFinal(binding: HostBinding, snapshotId: string, originalEvents: readonly ReviewEvent[], cut: GateCut, result: GateResult): void {
  check(cut.contractVersion === PREVIEW_CONTRACT && cut.clientId === binding.client.clientId && cut.lineageId === binding.client.lineageId &&
    cut.generation === binding.client.generation && cut.sourceSnapshotId === snapshotId && cut.eventFrontier === (originalEvents.at(-1)?.sequence ?? 0), 'final_cut_domain');
  // KernelService.prepareGate SELECTs ALL required historical events through this frontier, in sequence order.
  same(cut.requiredEventIds, originalEvents.filter(event => event.deliveryRequired).map(event => event.eventId), 'final_required_event_set');
  integer(cut.lineageEpoch); id(cut.cutId);
  check(result.contractVersion === PREVIEW_CONTRACT && result.cutId === cut.cutId && result.clientId === cut.clientId &&
    result.lineageId === cut.lineageId && result.generation === cut.generation && result.allowed === false && result.actionPerformed === false &&
    cut.ready === false && Array.isArray(cut.reasons) && Array.isArray(result.reasons) && result.reasons.length > 0, 'observed_final_must_remain_denied');
  const reviews = [...new Set(originalEvents.map(event => event.reviewId))];
  for (const reasons of [cut.reasons, result.reasons]) {
    check(reasons.every(reason => typeof reason === 'string') && reviews.every(review => reasons.includes(OBSERVED_GAP + ':' + review)),
      'observed_final_taint_missing');
    check(!reasons.some(reason => /^(?:insertion_missing:|model_observation_missing:|delivery_frontier_changed$|required_context_not_observed$)/.test(reason)),
      'final_delivery_not_confirmed');
  }
}
