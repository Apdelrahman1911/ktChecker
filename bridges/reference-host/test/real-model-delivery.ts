/** Genuine owner/ReferenceHost joins for the opt-in fixture. These validators create no
 * compiler, ingress, delivery, observation, cleanup or qualification authority. */
import { PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest } from '../src/journal.js';
import type { CodexHostPort } from '../src/codex-host.js';
import type { ContextItem, HostPort, InboxEntry, InsertedContext, JournalState, ModelRequest, ModelWitness, Registration } from '../src/types.js';
import { DeliveryReceiptCapture } from './delivery-receipts.js';
import { identifierReal, objectReal, requireReal, sameReal, shaReal, RealModelClock,
  REAL_MODEL_CASE, REAL_MODEL_CONTEXT_BYTES, REAL_MODEL_PUBLIC_BYTES } from './real-model-run-support.js';

export const REAL_MODEL_RULE = 'ktc.compiler.common.unresolved_reference';
/** Independent sorted-ID derivation from all FOUR frozen catalog files, not one selected rule. */
export const REAL_MODEL_RULE_VECTOR_DIGEST = 'sha256:fc45c20ddecf87113dc392a3127f4dfa9d2dd75177a71252f8fd9c67929e316b';
export function fullRealRules(value: unknown): string[] {
  requireReal(Array.isArray(value) && value.length === 1663 && value.every(item => typeof item === 'string'), 'real_model_full_rule_vector_required');
  const ids = value as string[];
  requireReal(ids.includes(REAL_MODEL_RULE) && new Set(ids).size === 1663 && digest(canonical(ids)) === REAL_MODEL_RULE_VECTOR_DIGEST,
    'real_model_full_rule_vector_changed');
  return ids;
}
export function publicRealText(text: string, forbidden: readonly string[]): void {
  requireReal(typeof text === 'string' && !forbidden.some(value => value.length > 0 && text.includes(value)) &&
    !/\/(?:root|home|tmp|proc|var|usr|opt|etc)\//.test(text), 'real_model_private_data_in_model_context');
}
export function publicRealValue(value: unknown, forbidden: readonly string[]): void { publicRealText(canonical(value), forbidden); }

export function realOwnerHello(value: unknown, selectionDigest: string): { registration: Registration; generation: JsonObject; rules: string[] } {
  const hello = objectReal(value), registration = objectReal(hello.registration), binding = objectReal(registration.binding);
  const client = objectReal(registration.client), adapter = objectReal(registration.adapter), generation = objectReal(hello.generation);
  requireReal(hello.fixture === REAL_MODEL_CASE && hello.selectionDigest === selectionDigest && hello.qualified === false &&
    registration.contractVersion === PREVIEW_CONTRACT && client.clientId === binding.clientId && adapter.clientId === binding.clientId &&
    typeof client.token === 'string' && client.token.length > 20 && typeof adapter.token === 'string' && adapter.token.length > 20 &&
    client.token !== adapter.token, 'real_model_owner_hello_binding');
  for (const key of ['ownerId', 'workspaceId', 'worktreeId', 'clientId', 'lineageId', 'sessionId']) identifierReal(binding[key]);
  requireReal(Number.isSafeInteger(binding.generation) && (binding.generation as number) > 0, 'real_model_owner_generation_binding');
  const actual = objectReal(generation.actualHello);
  requireReal(generation.state === 'ready' && generation.generationOriginalDeadlineMillis === 900_000 && generation.qualified === false &&
    actual.version === '2.4.21' && actual.originsVerified === true && actual.initialResetObserved === true &&
    generation.retirementDisposition === null, 'real_model_genuine_generation_not_ready');
  identifierReal(generation.generationId); identifierReal(generation.generationOriginalDeadlineIdentity);
  shaReal(generation.namespaceDigest); shaReal(generation.launchDigest); shaReal(generation.installedToolchainDigest);
  shaReal(generation.runtimeImplementationIdentity); shaReal(generation.wireImplementationIdentity); shaReal(actual.digest);
  requireReal(typeof actual.loaderToken === 'string' && actual.loaderToken.length > 0, 'real_model_loader_identity_missing');
  sameReal(generation.openUnavailableReasons, [], 'real_model_generation_open_has_reasons');
  return { registration: registration as unknown as Registration, generation, rules: fullRealRules(hello.fullRequiredRuleIds) };
}

export interface RealPhase {
  number: number; accepted: JsonObject; snapshotId: string; reviewId: string; enteredMs: number;
  acceptanceReturnedMs: number; completionObservedMs: number | null; completion: JsonObject | null;
}
function parentReal(value: unknown, registration: Registration, rules: readonly string[]): JsonObject {
  const parent = objectReal(value);
  requireReal(parent.contractVersion === PREVIEW_CONTRACT && parent.clientId === registration.binding.clientId &&
    Array.isArray(parent.slots) && parent.slots.length === 1, 'real_model_whole_parent_binding');
  shaReal(parent.planDigest); shaReal(parent.sourceSnapshotId); identifierReal(parent.planId); identifierReal(parent.patchId);
  const slot = objectReal(parent.slots[0]); identifierReal(slot.reviewId); identifierReal(slot.slotId);
  sameReal(fullRealRules(slot.requiredRuleIds), rules, 'real_model_parent_rule_vector_changed');
  return parent;
}
export function acceptedRealPhase(value: unknown, number: number, registration: Registration, rules: readonly string[],
  enteredMs: number, returnedMs: number): RealPhase {
  const response = objectReal(value);
  requireReal(response.state === 'parent_accepted' && response.phase === number && response.qualified === false, 'real_model_parent_not_accepted');
  const accepted = parentReal(response.parent, registration, rules), timing = objectReal(response.timing);
  requireReal(timing.originalDeadlineMillis === 300_000 && timing.phase === number &&
    accepted.patchId === 'no-test-feasibility-patch-' + number, 'real_model_phase_original_identity');
  identifierReal(timing.originalDeadlineIdentity);
  return { number, accepted, snapshotId: shaReal(accepted.sourceSnapshotId), reviewId: identifierReal(objectReal((accepted.slots as unknown[])[0]).reviewId),
    enteredMs, acceptanceReturnedMs: returnedMs, completionObservedMs: null, completion: null };
}
export function completeRealPhase(value: unknown, phase: RealPhase, registration: Registration, rules: readonly string[], observedMs: number): boolean {
  const response = objectReal(value);
  requireReal(response.phase === phase.number && response.qualified === false && response.usefulClean === false &&
    (response.state === 'phase_pending' || response.state === 'phase_verified_unqualified'), 'real_model_owner_phase_failed');
  if (response.state === 'phase_pending') return false;
  requireReal(response.phaseVerified === true && response.terminal === true && Array.isArray(response.children) && response.children.length === 1,
    'real_model_required_completion_missing');
  const parent = parentReal(response.parent, registration, rules);
  for (const key of ['planId', 'clientId', 'patchId', 'sourceSnapshotId', 'planDigest']) sameReal(parent[key], phase.accepted[key], 'real_model_completed_parent_changed');
  requireReal(parent.state !== 'complete', 'real_model_unqualified_parent_claimed_complete');
  const child = objectReal(response.children[0]), result = objectReal(child.result);
  requireReal(child.reviewId === phase.reviewId && child.sourceSnapshotId === phase.snapshotId && result.status !== 'complete' &&
    Array.isArray(result.coverage) && Array.isArray(result.findings), 'real_model_required_result_binding');
  const rows = result.coverage.map(objectReal), required = rows.filter(row => row.required === true);
  sameReal(required.map(row => identifierReal(row.ruleId)).sort(), rules, 'real_model_required_result_vector_changed');
  const selected = rows.filter(row => row.ruleId === REAL_MODEL_RULE);
  requireReal(selected.length === 1 && selected[0]!.provenance === 'executed' && selected[0]!.qualified === false &&
    selected[0]!.certificateId == null, 'real_model_unqualified_compiler_execution_missing');
  const findings = result.findings.map(objectReal), matches = findings.filter(finding => finding.ruleId === REAL_MODEL_RULE);
  if (phase.number === 1) requireReal(matches.length > 0 && matches.every(finding => {
    const location = objectReal(finding.location);
    return location.path === 'src/main/kotlin/Use.kt' && finding.upstreamId === 'UNRESOLVED_REFERENCE' &&
      typeof finding.diagnosis === 'string' && finding.diagnosis.includes('declaredAcrossFiles');
  }), 'real_model_cross_file_diagnosis_missing');
  else requireReal(findings.length === 0, 'real_model_fixed_cross_file_diagnosis_remains');
  phase.completion = response; phase.completionObservedMs = observedMs;
  return true;
}

/** Validates ENTIRE durable actual event domain before any external insertion; no event filtering. */
export function validateRealDelivery(state: JournalState, phase: RealPhase, forbidden: readonly string[]): InboxEntry[] {
  requireReal(phase.completion !== null && Object.keys(state.submissions).length === 0 && state.currentSnapshotId === phase.snapshotId,
    'real_model_phase_delivery_state');
  const all = Object.values(state.inbox);
  requireReal(all.length <= 64, 'real_model_fixture_event_bound');
  for (const entry of all) {
    requireReal(Buffer.byteLength(entry.event.payload) <= REAL_MODEL_PUBLIC_BYTES && digest(entry.event.payload) === entry.event.payloadDigest,
      'real_model_event_payload_bound');
    publicRealText(entry.event.payload, forbidden);
  }
  const pending = all.filter(entry => entry.event.deliveryRequired && !entry.observed);
  requireReal(pending.length >= 3 && pending.every(entry => entry.event.sourceSnapshotId === phase.snapshotId && entry.event.reviewId === phase.reviewId),
    'real_model_required_delivery_domain_changed');
  const accepted = pending.filter(entry => entry.event.kind === 'accepted'), plan = pending.filter(entry => entry.event.kind === 'plan_accepted'),
    terminal = pending.filter(entry => entry.event.kind === 'result');
  requireReal(accepted.length === 1 && plan.length === 1 && terminal.length === 1, 'real_model_required_event_missing');
  const parentPayload = objectReal(JSON.parse(plan[0]!.event.payload));
  sameReal(parentPayload.parentPlan, phase.accepted, 'real_model_required_parent_event_changed');
  const child = objectReal((phase.completion.children as unknown[])[0]), resultPayload = objectReal(JSON.parse(terminal[0]!.event.payload));
  sameReal(resultPayload.result, child.result, 'real_model_actual_result_event_changed');
  requireReal(resultPayload.state === child.state && resultPayload.reviewId === phase.reviewId && resultPayload.sourceSnapshotId === phase.snapshotId,
    'real_model_actual_terminal_identity_changed');
  return pending.sort((a, b) => a.event.sequence - b.event.sequence);
}

/** Captures only existing public receipt projections. Raw credentials are never retained/hashed. */
export class RealReceiptBroker implements BrokerPort {
  readonly receipts = new DeliveryReceiptCapture();
  constructor(private readonly owner: BrokerPort, registration: Registration) { this.receipts.bind(registration.binding); }
  async request<T = JsonObject>(method: string, params: JsonObject): Promise<T> {
    const capture = this.receipts.beforeRequest(method, params);
    try { const result = await this.owner.request<T>(method, params); capture?.returned(result); return result; }
    catch (error) { capture?.rejected(); throw error; }
  }
}

/** Transparent owner-only observation boundary. Every result/witness comes from the SAME actual
 * CodexHostPort; this wrapper cannot manufacture an insertion or an actual-model-request witness. */
export class RealObservedHost implements HostPort {
  readonly binding;
  readonly timings: JsonObject[] = [];
  private phase: RealPhase | null = null;
  private expected: InboxEntry[] = [];
  private invoked = 0;
  private readonly inserted = new Map<string, ContextItem>();
  constructor(readonly actual: CodexHostPort, private readonly clock: RealModelClock, private readonly forbidden: readonly string[]) {
    this.binding = actual.binding;
  }
  prepare(phase: RealPhase, expected: InboxEntry[]): void {
    requireReal(phase.number === this.invoked + 1 && expected.length > 0, 'real_model_observation_phase_replayed');
    this.phase = phase; this.expected = expected;
  }
  async findContext(marker: string): Promise<InsertedContext | null> { this.clock.checkpoint(); return this.actual.findContext(marker); }
  async insertContext(item: ContextItem, receiptId: string): Promise<InsertedContext> {
    requireReal(this.phase !== null && !this.inserted.has(item.marker) && Buffer.byteLength(item.text) <= REAL_MODEL_CONTEXT_BYTES,
      'real_model_context_insertion_replayed_or_bound');
    publicRealText(item.text, this.forbidden); this.clock.checkpoint();
    const enteredMs = this.clock.elapsedMs(), actual = await this.actual.insertContext(item, receiptId);
    this.clock.checkpoint(); this.inserted.set(item.marker, structuredClone(item));
    this.timings.push({ phase: this.phase.number, stage: 'actual_context_insertion_returned', contextDigest: item.digest,
      enteredMs, returnedMs: this.clock.elapsedMs(), receipt: actual.receipt });
    return actual;
  }
  async invokeModel(request: ModelRequest): Promise<ModelWitness> {
    requireReal(this.phase !== null && ++this.invoked === this.phase.number && this.invoked <= 2 &&
      this.actual.evidence().length === this.invoked - 1, 'real_model_model_invocation_replayed');
    requireReal(request.contexts.reduce((sum, item) => sum + Buffer.byteLength(item.text), 0) <= REAL_MODEL_CONTEXT_BYTES,
      'real_model_whole_context_bound');
    const expected = this.expected.map(entry => `ktchecker-event:${entry.event.eventId}:${entry.event.payloadDigest}`).sort();
    sameReal(request.contexts.map(item => item.marker).sort(), expected, 'real_model_request_required_context_missing');
    for (const item of request.contexts) {
      publicRealText(item.text, this.forbidden); sameReal(this.inserted.get(item.marker), item, 'real_model_request_not_actual_inserted_context');
    }
    const enteredMs = this.clock.elapsedMs(); this.clock.checkpoint();
    const actual = await this.actual.invokeModel(request);
    this.clock.checkpoint();
    this.timings.push({ phase: this.phase.number, stage: 'actual_model_request_and_public_witness_returned',
      invocationId: request.invocationId, requestDigest: request.requestDigest, enteredMs, returnedMs: this.clock.elapsedMs(), witnessKind: actual.kind });
    return actual;
  }
}
