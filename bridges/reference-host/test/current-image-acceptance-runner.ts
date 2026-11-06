/** Fresh bounded orchestration around the EXISTING reference host. No CLI auto-entry,
 * scalar submit fallback, retry/recovery loop, process close, signal, deletion or model polling.
 * The production same-lineage adapter remains a separate source/admission obligation.
 */
import { join } from 'node:path';
import type { BrokerPort, JsonObject } from '@ktchecker/mcp-preview/rpc';
import { ReferenceHost } from '../src/adapter.js';
import { KernelBroker } from '../src/broker.js';
import { CodexHostPort } from '../src/codex-host.js';
import { canonical, digest, HostError, InboxJournal } from '../src/journal.js';
import type { ClientBinding, ContextItem, HostBinding, HostPort, InsertedContext, ModelRequest, ModelWitness, Registration } from '../src/types.js';
import { captureApplicationEvidence } from './application-evidence.js';
import { deliveryBinding } from './delivery-receipts.js';
import { absoluteReal, makeFreshRealDirectory, RealModelClock, REAL_MODEL_CONTEXT_BYTES, REAL_MODEL_REVIEW_MS,
  safeRealFailure, writeNewRealJson } from './real-model-run-support.js';
import { PinnedCurrentImage, type CurrentImageEvidence } from './current-image-acceptance-image.js';
import { takeCurrentImageLaunch, type CurrentImageLaunchEvidence, type OpenedCurrentImageLaunch } from './current-image-acceptance-launch.js';
import { validateOwnerCompilerPolicySelection, verifyOwnerCompilerPolicyAudit,
  type OwnerCompilerPolicyAudit, type OwnerCompilerPolicySelection } from './current-image-acceptance-policy.js';
import { ACCEPTANCE_EXPORT_MS, ACCEPTANCE_MAX_EVENTS, check, CurrentAcceptanceBroker, explicitlyUnavailable, freezeCompletedPatch, frozen, id, record, requiredContexts,
  same, sha, verifyDeniedFinal, verifyOriginalContextJoin, verifyParentAdmission, verifyParentCompletion,
  type BoundParent, type CompletedPatch, type OriginalContextJoin, type ParentAdmissionData, type ParentCompletionData, type SnapshotLineage } from './current-image-acceptance-evidence.js';

export const REQUIRED_PARENT_ADAPTER = 'same-workbench-lineage-fresh-observed-export-per-patch/1';
export interface OwnerInvocationSelection { readonly gradleHome: string; readonly gradleCacheSeed: string; readonly compilerJavaHome: string;
  /** Owner-selected EXACT private artifact order, never reconstructed from returned compiler rows. */
  readonly compilerArtifactPaths: readonly string[];
  readonly authorizeExecution: true }
export interface NominalParentSubmission { readonly data: ParentAdmissionData; readonly deliveryHandle: object }
/** NOT an RPC name or deserializable authority. Implement in the owner-controlled live bridge:
 * prepareObservedModelReview -> submitModelPlan, with the same retained Workbench/BrokerPort.
 * Each genuinely NEW patch starts its own original120s export. A previous model is documentary
 * before-input only, never compiler authority; an uncertain previous invocation cannot rearm.
 * The owner successor, not this JSON verifier, must retain SAME admission objects and prove prior
 * child-group quiescence and once-only actual CLOSED compiler retirement BEFORE next commit/export.
 * Private installed artifacts and serialized Gradle userHome may persist; invocation/project/output/
 * generation/capability never persist into another patch. An unknown result poisons the session.
 */
export interface CurrentImageParentAdapter {
  readonly profile: string;
  readonly registration: Registration;
  readonly broker: BrokerPort;
  submitCompletedPatch(patch: CompletedPatch, selection: OwnerInvocationSelection,
    clockScope: { readonly overall: AcceptanceClock; readonly reviewObservation: AcceptanceClock;
      readonly originalExportMillis: typeof ACCEPTANCE_EXPORT_MS }): Promise<NominalParentSubmission>;
  status(deliveryHandle: object): Promise<ParentCompletionData>;
  /** Synchronous LOCAL wait-scope close only, after exact joined durable evidence + summary.
   * No RPC, receipt, ACK-derived success, clock renewal, retirement or effect authority. */
  deliveryVerified(deliveryHandle: object, exactOriginalReviewClock: OriginalReviewObservationClock): void;
}
export function requireFullParentAdapter(adapter: Pick<CurrentImageParentAdapter, 'profile'>): void {
  check(adapter.profile === REQUIRED_PARENT_ADAPTER, 'same_lineage_adapter_integration_unavailable');
}
export interface AcceptanceClock {
  elapsedMs(): number; remainingMs(): number; checkpoint(): void;
  wait<T>(operation: () => Promise<T>, capMs?: number): Promise<T>;
}
export interface EvidenceWriter { append(stage: string, value: unknown): void }
/** Required owner-only locations, not discovered by inspecting host stores or another session. */
export interface PrivateAcceptanceLocations {
  readonly ownerStateDirectory: string; readonly hostStateDirectory: string; readonly journalDirectory: string;
  readonly invocationRoot: string; readonly additional: readonly string[];
}
/** Exclusively creates NEW private evidence; a failed write never overwrites/reopens an old run. */
export class ExclusiveAcceptanceEvidence implements EvidenceWriter {
  private serial = 0;
  constructor(private readonly directory: string) { makeFreshRealDirectory(directory); }
  append(stage: string, value: unknown): void {
    check(/^[a-z0-9_]{1,80}$/.test(stage), 'evidence_stage');
    writeNewRealJson(join(this.directory, String(++this.serial).padStart(4, '0') + '-' + stage + '.json'), value);
  }
}
export interface FindingExpectation { readonly ruleId: string; readonly upstreamId: string; readonly path: string; readonly present: boolean }
export interface AcceptanceAuthorization {
  readonly attemptId: string; readonly approvalDigest: string; readonly requiredClientIds: readonly string[];
  readonly scheduledPatches: readonly { readonly patchId: string; readonly taskId: string; readonly findings: readonly FindingExpectation[] }[];
  readonly compilerRuleIds: readonly string[]; readonly ownerInvocationSelectionDigest: string; readonly imageSelectionDigest: string;
  readonly ownerCompilerPolicySelectionDigest: string;
}
/** Pure explicit selection checks shared with fresh-launch construction BEFORE any owner call.
 * This never configures a rule or interprets execution consent as policy consent.
 */
export function validateAcceptanceSelections(authorization: AcceptanceAuthorization, invocation: OwnerInvocationSelection,
  policy: OwnerCompilerPolicySelection): void {
  id(authorization.attemptId); sha(authorization.approvalDigest); sha(authorization.imageSelectionDigest); sha(authorization.ownerInvocationSelectionDigest);
  sha(authorization.ownerCompilerPolicySelectionDigest);
  same(Object.keys(authorization).sort(), ['approvalDigest', 'attemptId', 'compilerRuleIds', 'imageSelectionDigest',
    'ownerCompilerPolicySelectionDigest', 'ownerInvocationSelectionDigest', 'requiredClientIds', 'scheduledPatches'], 'authorization_schema');
  check(Array.isArray(authorization.compilerRuleIds) && authorization.compilerRuleIds.length === 14 && new Set(authorization.compilerRuleIds).size === 14 &&
    authorization.compilerRuleIds.every(rule => id(rule).startsWith('ktc.compiler.')), 'authorized_compiler_exact14');
  same(authorization.compilerRuleIds, [...authorization.compilerRuleIds].sort(), 'authorized_compiler_order');
  check(Array.isArray(authorization.requiredClientIds) && authorization.requiredClientIds.length === 1, 'required_single_client_scope');
  id(authorization.requiredClientIds[0]);
  check(Array.isArray(authorization.scheduledPatches) && authorization.scheduledPatches.length === 2 &&
    new Set(authorization.scheduledPatches.map(patch => patch.patchId)).size === 2, 'patch_and_fix_schedule_required');
  const findingKey = (finding: FindingExpectation) => canonical({ ruleId: finding.ruleId, upstreamId: finding.upstreamId, path: finding.path });
  for (const [index, patch] of authorization.scheduledPatches.entries()) {
    id(patch.patchId); id(patch.taskId); check(Array.isArray(patch.findings) && patch.findings.length > 0 && patch.findings.length <= 64, 'finding_expectation_required');
    for (const finding of patch.findings) {
      id(finding.ruleId); id(finding.upstreamId);
      check(authorization.compilerRuleIds.includes(finding.ruleId) && finding.present === (index === 0) && typeof finding.path === 'string' &&
        finding.path.endsWith('.kt') && !finding.path.startsWith('/') && !finding.path.includes('\\') &&
        !/[\u0000-\u001f\u007f]/u.test(finding.path) && finding.path.split('/').every((part: string) => !['', '.', '..'].includes(part)), 'finding_flip_schedule');
    }
    check(new Set(patch.findings.map(findingKey)).size === patch.findings.length, 'finding_expectation_duplicate');
  }
  same(authorization.scheduledPatches[0]!.findings.map(findingKey).sort(), authorization.scheduledPatches[1]!.findings.map(findingKey).sort(),
    'same_intended_findings_must_disappear');
  check(invocation.authorizeExecution === true, 'owner_execution_not_authorized');
  absoluteReal(invocation.gradleHome); absoluteReal(invocation.gradleCacheSeed); absoluteReal(invocation.compilerJavaHome);
  check(Array.isArray(invocation.compilerArtifactPaths) && invocation.compilerArtifactPaths.length > 0 && invocation.compilerArtifactPaths.length <= 128 &&
    new Set(invocation.compilerArtifactPaths).size === invocation.compilerArtifactPaths.length, 'ordered_compiler_artifacts_required');
  for (const path of invocation.compilerArtifactPaths) check(absoluteReal(path).endsWith('.jar'), 'compiler_artifact_kind');
  same(Object.keys(invocation).sort(), ['authorizeExecution', 'compilerArtifactPaths', 'compilerJavaHome', 'gradleCacheSeed', 'gradleHome'], 'owner_selection_schema');
  check(digest(canonical(invocation)) === authorization.ownerInvocationSelectionDigest, 'owner_selection_changed');
  validateOwnerCompilerPolicySelection(policy, authorization.compilerRuleIds);
  check(digest(canonical(policy)) === authorization.ownerCompilerPolicySelectionDigest, 'owner_policy_selection_changed');
}
interface RunOptions {
  readonly adapter: CurrentImageParentAdapter; readonly host: HostPort; readonly journal: InboxJournal;
  readonly clock: AcceptanceClock; readonly evidence: EvidenceWriter; readonly authorization: AcceptanceAuthorization;
  readonly ownerInvocation: OwnerInvocationSelection; readonly forbidden: readonly string[];
  readonly ownerCompilerPolicy: OwnerCompilerPolicySelection; readonly ownerCompilerPolicyAudit: OwnerCompilerPolicyAudit;
  readonly privateLocations: PrivateAcceptanceLocations;
  readonly assertLaunchCurrent?: () => void;
  /** Actual construction binds this LOCAL negative guard to the launcher-owned server. */
  readonly assertNoModelReroute: () => void;
  readonly image: { assertCurrent(): CurrentImageEvidence; assertCompilerArtifactPaths(paths: readonly string[]): void };
}
/** No caller-port, clock, image, owner or journal bypass. Only the fresh-launch registry issues a ticket. */
export interface ActualRunOptions { readonly launch: OpenedCurrentImageLaunch; readonly forbidden: readonly string[] }
/** Public DATA only, from the retained run binding; no credential, handle, clock or owner authority. */
export type CompletedPatchCapture = (ordinal: number, previous: SnapshotLineage | null,
  binding: Readonly<ClientBinding>) => Promise<CompletedPatch>;
export interface SyntheticControlOptions extends RunOptions {
  /** Deliberately unavailable in the actual factory. Cannot qualify synthetic witnesses as real. */
  readonly syntheticEvidence: (journal: InboxJournal) => Promise<JsonObject>;
}
export interface PatchDeliverySummary {
  readonly ordinal: number; readonly patchIdDigest: string; readonly completedPatchDigest: string;
  readonly sourceSnapshotId: string; readonly parentPlanDigest: string; readonly originalEventJoin: OriginalContextJoin;
  readonly allChildComputationsEvidenced: boolean; readonly exact14CompilerExecutionsEvidenced: boolean; readonly expectedFindingChangesMatched: boolean;
}
export interface AcceptanceReport {
  readonly schema: 'ktchecker-current-image-host-acceptance/1'; readonly origin: 'actual-current-image' | 'synthetic-control';
  readonly approvalDigest: string; readonly binding: ReturnType<typeof deliveryBinding>; readonly image: CurrentImageEvidence | null;
  readonly launch: CurrentImageLaunchEvidence | null; readonly ownerCompilerPolicyAuditDigest: string;
  readonly status: 'verified_delivery_final_denied' | 'incomplete_or_failed'; readonly failure: string | null;
  readonly unknownOperation: string | null; readonly patches: readonly PatchDeliverySummary[];
  readonly finalBarrier: 'not_attempted' | 'denied' | 'failed'; readonly actualHostDeliveryVerified: boolean;
  readonly usefulClean: false; readonly qualified: false; readonly fullProject: false; readonly actionPerformed: false;
  readonly internalInferenceRequestTrace: false; readonly lifecycleDisposition: 'retained_no_quiescence_claim';
}

/** Diagnostic only: syncReceipts may aggregate one rejected confirmation with later
 * independent ACK attempts stopped locally by the poisoned wrapper. Recognize ONLY that
 * flat, event-bounded cascade. Never flatten nested errors, expose messages or conceal a
 * different fault. This changes no receipt, retry, transport or acceptance-success policy.
 */
export function safeCurrentAcceptanceFailure(error: unknown): string {
  if (error instanceof HostError) return safeRealFailure(error);
  const fallback = 'current_acceptance_operation_failed';
  if (!(error instanceof AggregateError)) return fallback;
  // Native AggregateError owns a data array. An accessor/lookalike is not this cascade.
  const errors: unknown = Object.getOwnPropertyDescriptor(error, 'errors')?.value;
  if (!Array.isArray(errors) || errors.length < 2 || errors.length > ACCEPTANCE_MAX_EVENTS) return fallback;
  for (let index = 0; index < errors.length; index++) {
    // Require every own data slot: sparse arrays/accessors cannot hide another cause.
    const cause: unknown = Object.getOwnPropertyDescriptor(errors, String(index))?.value;
    if (!(cause instanceof HostError) || cause.code !== (index === 0 ? 'delivery_capture_confirmation_rejected' :
      'current_acceptance_broker_unknown_or_concurrent')) return fallback;
  }
  return 'delivery_capture_confirmation_rejected';
}

/** One view of the ORIGINAL review-observation phase, not a new clock per operation.
 * This same instance spans owner submission, status/poll, insertion, actual application request,
 * public response, ACK/observation confirmation and private evidence. The overall clock is never reset.
 */
const originalReviewClocks = new WeakMap<object, AcceptanceClock>();
export class OriginalReviewObservationClock implements AcceptanceClock {
  private readonly entered: number;
  constructor(private readonly overall: AcceptanceClock) {
    check(new.target === OriginalReviewObservationClock, 'original_review_clock_type');
    overall.checkpoint(); this.entered = overall.elapsedMs(); originalReviewClocks.set(this, overall); Object.freeze(this);
  }
  elapsedMs(): number { return this.overall.elapsedMs() - this.entered; }
  remainingMs(): number {
    const remaining = Math.min(this.overall.remainingMs(), Math.floor(REAL_MODEL_REVIEW_MS - this.elapsedMs()));
    check(remaining > 0, 'original_review_observation_deadline'); return remaining;
  }
  checkpoint(): void { this.remainingMs(); }
  async wait<T>(operation: () => Promise<T>, capMs = REAL_MODEL_REVIEW_MS): Promise<T> {
    const value = await this.overall.wait(operation, Math.min(this.remainingMs(), capMs)); this.checkpoint(); return value;
  }
}

/** No property/getter/prototype read can register a lookalike or a foreign overall clock. */
export function isOriginalReviewObservationClockFor(value: unknown, overall: AcceptanceClock): value is OriginalReviewObservationClock {
  return value !== null && typeof value === 'object' && originalReviewClocks.get(value) === overall;
}
Object.freeze(OriginalReviewObservationClock.prototype); Object.freeze(OriginalReviewObservationClock);

/** Actual Codex store evidence only. Synthetic controls do not receive this audit or an actual label.
 * Detects extra recorded invocations, replayed IDs and proof mutation. It does not claim to observe
 * arbitrary out-of-band calls by other privileged code: exclusive component ownership is a prerequisite.
 */
class ActualApplicationAudit {
  private previous: readonly JsonObject[] = []; private invoking = false;
  constructor(private readonly host: CodexHostPort) {}
  assertIdle(): void {
    this.host.server.assertNoModelReroute(); // Durable proof equality alone cannot establish current exact-model eligibility.
    if (!this.invoking) same(this.host.evidence(), this.previous, 'extra_or_revised_host_invocation');
  }
  before(request: ModelRequest, ordinal: number): void {
    check(!this.invoking && ordinal === this.previous.length + 1, 'actual_invocation_ordinal'); this.assertIdle();
    check(!this.previous.some(proof => proof.invocationId === request.invocationId), 'actual_invocation_reused'); this.invoking = true;
  }
  after(request: ModelRequest, witness: ModelWitness): void {
    const all = this.host.evidence();
    check(this.invoking && all.length === this.previous.length + 1 && witness.kind === 'actual-model-request', 'actual_invocation_count');
    same(all.slice(0, -1), this.previous, 'earlier_host_invocation_changed');
    const proof = record(all.at(-1)), application = record(proof.applicationRequest);
    check(proof.status === 'verified' && proof.model === 'gpt-6-astra' && proof.effort === 'max' && proof.internalInferenceRequestTrace === false &&
      proof.invocationId === request.invocationId && proof.requestDigest === request.requestDigest && application.method === 'turn/start' &&
      witness.modelRequestId === 'codex-app-request:' + id(application.applicationRequestId), 'actual_invocation_proof');
    same(proof.contextDigests, request.contexts.map(item => item.digest), 'actual_invocation_contexts');
    sha(application.serializedRequestDigest); sha(proof.publicResponseDigest); id(proof.turnId); id(proof.publicItemId);
    check(!this.previous.some(old => record(old.applicationRequest).applicationRequestId === application.applicationRequestId ||
      old.turnId === proof.turnId || old.publicItemId === proof.publicItemId), 'actual_application_or_public_item_reused');
    this.previous = frozen(structuredClone(all)); this.invoking = false; this.assertIdle();
  }
  verifyCaptured(phase: JsonObject, ordinal: number): void {
    check(!this.invoking && this.previous.length === ordinal, 'application_capture_ordinal'); this.assertIdle();
    const proof = this.previous.at(-1)!;
    same(Object.fromEntries(Object.keys(proof).map(key => [key, phase[key]])), proof, 'captured_application_not_current_invocation');
  }
}

class ExactOneShotHost implements HostPort {
  readonly binding; private expected: readonly ContextItem[] = []; private invoked = true;
  private readonly attempted = new Set<string>(); private readonly found = new Set<string>();
  private readonly inserted = new Map<string, ContextItem>();
  private readonly invocationIds = new Set<string>(); private readonly applicationIds = new Set<string>();
  private completed: { request: ModelRequest; witness: Extract<ModelWitness, { kind: 'actual-model-request' }> } | null = null;
  constructor(private readonly actual: HostPort, private readonly guard: () => void, private readonly audit: ActualApplicationAudit | null) {
    this.binding = actual.binding;
  }
  completedModel(): NonNullable<ExactOneShotHost['completed']> { check(this.completed !== null, 'model_observation_missing'); return this.completed; }
  prepare(items: readonly ContextItem[]): void {
    this.guard(); check(this.invoked && items.length > 0, 'observation_schedule'); this.expected = frozen(structuredClone(items)); this.invoked = false;
  }
  async findContext(marker: string): Promise<InsertedContext | null> {
    this.guard(); check(this.expected.some(item => item.marker === marker) && !this.found.has(marker), 'context_find_replay');
    this.found.add(marker); const value = await this.actual.findContext(marker); this.guard();
    check(value === null, 'historical_context_not_fresh'); return null;
  }
  async insertContext(item: ContextItem, receiptId: string): Promise<InsertedContext> {
    this.guard(); check(!this.attempted.has(item.marker), 'insertion_replay');
    same(item, this.expected.find(value => value.marker === item.marker), 'insertion_original_projection');
    this.attempted.add(item.marker); const result = await this.actual.insertContext(item, receiptId); this.guard();
    this.inserted.set(item.marker, frozen(structuredClone(item))); return result;
  }
  async invokeModel(request: ModelRequest): Promise<ModelWitness> {
    this.guard(); check(!this.invoked, 'model_request_replay'); same(request.contexts, this.expected, 'required_model_context_set');
    for (const item of request.contexts) same(item, this.inserted.get(item.marker), 'model_context_not_inserted');
    check(!this.invocationIds.has(request.invocationId), 'model_invocation_id_reused'); this.invocationIds.add(request.invocationId);
    this.invoked = true; this.audit?.before(request, this.invocationIds.size);
    const result = await this.actual.invokeModel(request);
    this.audit?.after(request, result); this.guard();
    check(result.kind === 'actual-model-request' && !this.applicationIds.has(result.modelRequestId), 'model_application_id_reused_or_unavailable');
    this.applicationIds.add(result.modelRequestId);
    this.completed = frozen({ request: structuredClone(request), witness: structuredClone(result) }); return result;
  }
}

function findingChanges(parent: BoundParent, completion: ParentCompletionData, expected: readonly FindingExpectation[]): boolean {
  if (parent.reviewIds.some(review => explicitlyUnavailable(parent, completion, review))) return false;
  const findings = completion.children.flatMap(child => (record(child.result).findings as unknown[]).map(record));
  return expected.every(wanted => findings.some(finding => finding.ruleId === wanted.ruleId && finding.upstreamId === wanted.upstreamId &&
    record(finding.location).path === wanted.path) === wanted.present);
}
function exactCompilerExecutions(parent: BoundParent, completion: ParentCompletionData): boolean {
  if (parent.reviewIds.some(review => explicitlyUnavailable(parent, completion, review))) return false;
  return completion.children.every(child => {
    const rows = (record(child.result).coverage as unknown[]).map(record);
    const executedCompilerIds = rows.filter(row => typeof row.ruleId === 'string' && row.ruleId.startsWith('ktc.compiler.') &&
      row.provenance === 'executed').map(row => String(row.ruleId)).sort();
    if (canonical(executedCompilerIds) !== canonical(parent.data.compilerRuleIds)) return false;
    return parent.data.compilerRuleIds.every(rule => {
      const selected = rows.filter(row => row.required === true && row.ruleId === rule);
      return selected.length === 1 && selected[0]!.provenance === 'executed' && selected[0]!.qualified === false && selected[0]!.certificateId === null;
    });
  });
}

/** Caller must capture EVERY completed mutation (including repairs) through this scheduled loop.
 * Two planned patches are mandatory. Omitted repair, fresh-state reset or one-parent-only adapter
 * cannot become a successful acceptance. Unscheduled external mutations remain an owner fault;
 * this is not a general writer lease and cannot authorize a source mutation or final action.
 */
class AcceptanceRun {
  private stopped = false; private unknownOperation: string | null = null; private failure: string | null = null;
  private finalBarrier: AcceptanceReport['finalBarrier'] = 'not_attempted'; private image: CurrentImageEvidence | null = null;
  private readonly summaries: PatchDeliverySummary[] = []; private readonly handles = new WeakSet<object>();
  private readonly transport: CurrentAcceptanceBroker; private readonly observedHost: ExactOneShotHost; private readonly controller: ReferenceHost;
  private readonly registration: Registration; private readonly ownerBroker: BrokerPort;
  private readonly clientBinding: ClientBinding; private readonly hostBinding: HostBinding;
  private readonly clientToken: string; private readonly adapterToken: string;
  private forbidden: readonly string[] = []; private reviewClock: OriginalReviewObservationClock | null = null;
  private previous: BoundParent | null = null;
  constructor(private readonly options: RunOptions, private readonly origin: AcceptanceReport['origin'],
    private readonly applicationEvidence: () => Promise<JsonObject>, private readonly audit: ActualApplicationAudit | null,
    private readonly launch: CurrentImageLaunchEvidence | null = null) {
    const registration = options.adapter.registration;
    // Credentials stay on the owner side. No cloning, digesting or serializing registration.
    this.registration = registration; this.ownerBroker = options.adapter.broker;
    this.clientBinding = frozen(structuredClone(registration.binding)); this.hostBinding = frozen(structuredClone(options.host.binding));
    this.clientToken = registration.client.token; this.adapterToken = registration.adapter.token;
    this.transport = new CurrentAcceptanceBroker(this.ownerBroker, registration.binding);
    this.observedHost = new ExactOneShotHost(options.host, () => this.guard(), audit);
    this.controller = new ReferenceHost(options.journal, new KernelBroker(this.transport, registration), this.observedHost,
      { maxContextBytes: REAL_MODEL_CONTEXT_BYTES });
  }
  private guard(): void {
    check(!this.stopped, 'run_stopped_no_retry'); this.options.clock.checkpoint(); this.reviewClock?.checkpoint();
    this.options.assertNoModelReroute();
    check(this.options.adapter.registration === this.registration && this.options.adapter.broker === this.ownerBroker &&
      this.registration.client.token === this.clientToken && this.registration.adapter.token === this.adapterToken, 'owner_components_changed');
    same(this.registration.binding, this.clientBinding, 'owner_binding_changed');
    same(this.options.host.binding, this.hostBinding, 'host_binding_changed');
    this.audit?.assertIdle();
  }
  private verifyImage(): void {
    this.guard(); this.options.assertLaunchCurrent?.(); const current = this.options.image.assertCurrent();
    this.options.image.assertCompilerArtifactPaths(this.options.ownerInvocation.compilerArtifactPaths); this.guard();
    check(current.selectionDigest === this.options.authorization.imageSelectionDigest, 'authorized_image_selection');
    if (this.image) same(current, this.image, 'source_installed_image_drift'); else this.image = frozen(structuredClone(current));
  }
  private async once<T>(stage: string, operation: () => Promise<T>, cap?: number): Promise<T> {
    this.guard(); this.unknownOperation = stage;
    this.options.evidence.append('operation_attempted', { stage, elapsedMs: this.options.clock.elapsedMs(), outcome: 'unknown_until_returned' });
    const value = await (this.reviewClock ?? this.options.clock).wait(operation, cap); this.guard(); this.unknownOperation = null;
    this.options.evidence.append('operation_returned', { stage, elapsedMs: this.options.clock.elapsedMs() }); return value;
  }
  private initialize(): void {
    requireFullParentAdapter(this.options.adapter);
    check(typeof this.options.adapter.deliveryVerified === 'function', 'delivery_clock_hook_required');
    const authorization = this.options.authorization, invocation = this.options.ownerInvocation;
    validateAcceptanceSelections(authorization, invocation, this.options.ownerCompilerPolicy);
    same(authorization.requiredClientIds, [this.registration.binding.clientId], 'required_single_client_scope');
    verifyOwnerCompilerPolicyAudit(this.options.ownerCompilerPolicyAudit, this.options.ownerCompilerPolicy, this.registration.binding);
    const privatePaths = this.options.privateLocations;
    same(Object.keys(privatePaths).sort(), ['additional', 'hostStateDirectory', 'invocationRoot', 'journalDirectory', 'ownerStateDirectory'], 'private_locations_schema');
    check(Array.isArray(privatePaths.additional) && privatePaths.additional.length <= 32768, 'private_location_bound');
    const locations = [privatePaths.ownerStateDirectory, privatePaths.hostStateDirectory, privatePaths.journalDirectory, privatePaths.invocationRoot,
      ...privatePaths.additional, invocation.gradleHome, invocation.gradleCacheSeed, invocation.compilerJavaHome, ...invocation.compilerArtifactPaths];
    for (const path of locations) absoluteReal(path);
    check(typeof this.clientToken === 'string' && this.clientToken.length > 20 && typeof this.adapterToken === 'string' && this.adapterToken.length > 20 &&
      this.clientToken !== this.adapterToken && this.options.forbidden.length <= 256 && this.options.forbidden.every(value => typeof value === 'string'),
      'private_filter_inputs');
    // Strings used only for in-memory exclusion checks. Never hash/serialize known credential values.
    this.forbidden = [...new Set([this.clientToken, this.adapterToken, ...locations, ...this.options.forbidden])];
    const state = this.options.journal.read();
    check(state.localEpoch === 0 && state.currentSnapshotId === null && state.fetchCursor === null && state.cut === null && state.lastProblem === null &&
      !state.pendingModelRequest && Object.keys(state.inbox).length === 0 && Object.keys(state.submissions).length === 0, 'fresh_journal_required');
    this.verifyImage();
    this.options.evidence.append('fresh_acceptance_admitted', { origin: this.origin, approvalDigest: authorization.approvalDigest,
      binding: deliveryBinding(state.binding.client), image: this.image, plannedPatches: 2, originalExportMillis: ACCEPTANCE_EXPORT_MS,
      model: 'gpt-6-astra', effort: 'max', internalInferenceRequestTrace: false, qualified: false, launch: this.launch,
      ownerCompilerPolicyAuditDigest: digest(canonical(this.options.ownerCompilerPolicyAudit)) });
  }
  private async acceptPatch(input: CompletedPatch): Promise<void> {
    this.guard(); check(this.reviewClock === null, 'review_clock_rearmed');
    this.reviewClock = new OriginalReviewObservationClock(this.options.clock);
    const patch = freezeCompletedPatch(input), number = this.summaries.length + 1;
    const planned = this.options.authorization.scheduledPatches[number - 1]!;
    check(patch.request.ordinal === number && patch.request.patchId === planned.patchId && patch.request.taskId === planned.taskId, 'completed_patch_omitted_or_reordered');
    same(patch.request.binding, this.options.adapter.registration.binding, 'completed_patch_client');
    this.verifyImage();
    this.options.evidence.append('completed_patch_captured', { patchDigest: patch.digest, request: patch.request });
    const submission = await this.once('parent_submit', () => this.options.adapter.submitCompletedPatch(patch.request, this.options.ownerInvocation,
      { overall: this.options.clock, reviewObservation: this.reviewClock!, originalExportMillis: ACCEPTANCE_EXPORT_MS }));
    check(submission.deliveryHandle !== null && typeof submission.deliveryHandle === 'object' && !this.handles.has(submission.deliveryHandle), 'delivery_handle_reused');
    this.handles.add(submission.deliveryHandle);
    const parent = verifyParentAdmission(patch, submission.data, this.previous, this.options.authorization.compilerRuleIds, this.image!.ownerCodeImageDigest);
    check((record(record((parent.data.declaredPlan.slots as unknown[])[0]).input).changedPaths as unknown[]).length > 0, 'completed_patch_has_no_source_change');
    for (const slot of (parent.data.declaredPlan.slots as unknown[]).map(record)) {
      check(record(record(slot.input).context).configurationDigest === this.options.ownerCompilerPolicyAudit.policyDigest, 'parent_policy_not_selected_audit');
    }
    this.options.evidence.append('parent_admission', { data: parent.data, patchDigest: patch.digest, image: this.image });
    // Parent admission was returned by the actual owner path. Never write a fake scalar submission.
    this.options.journal.edit(state => { check(!state.pendingModelRequest && Object.keys(state.submissions).length === 0, 'scalar_submission_forbidden');
      state.currentSnapshotId = parent.snapshot.snapshotId; state.localEpoch++; state.cut = null; });
    let completion: ParentCompletionData;
    for (;;) {
      completion = frozen(structuredClone(await this.once('parent_status', () => this.options.adapter.status(submission.deliveryHandle))));
      while (await this.once('event_poll', () => this.controller.pollOnce())) { /* Outside every model turn. */ }
      if (verifyParentCompletion(parent, completion)) break;
      await this.once('status_wait', () => new Promise<void>(resolve => { setTimeout(resolve, 100); }));
    }
    this.verifyImage(); const prepared = requiredContexts(parent, completion, this.options.journal.read(), this.transport.originals(), this.forbidden);
    this.observedHost.prepare(prepared.contexts);
    await this.once('required_context_insert_and_ack', () => this.controller.drainContext());
    const observed = await this.once('actual_model_observation', () => this.controller.requestModelObservation());
    check(observed.observed && observed.reason === null, 'actual_observation_unavailable');
    const phase: JsonObject = { ...await this.once('application_evidence', this.applicationEvidence), consumer: patch.request.binding.clientId,
      deliveryBinding: deliveryBinding(patch.request.binding) };
    this.audit?.verifyCaptured(phase, number);
    const model = this.observedHost.completedModel();
    check(phase.invocationId === model.request.invocationId && phase.requestDigest === model.request.requestDigest &&
      model.witness.modelRequestId === 'codex-app-request:' + record(phase.applicationRequest).applicationRequestId, 'application_not_same_model_invocation');
    this.verifyImage();
    const originalEventJoin = verifyOriginalContextJoin(parent, completion, this.options.journal.read(), this.transport.originals(), phase,
      this.transport.receipts.snapshot(), this.forbidden);
    const summary = frozen({ ordinal: number, patchIdDigest: digest(patch.request.patchId), completedPatchDigest: patch.digest,
      sourceSnapshotId: parent.snapshot.snapshotId, parentPlanDigest: sha(parent.data.parent.planDigest), originalEventJoin,
      allChildComputationsEvidenced: !parent.reviewIds.some(review => explicitlyUnavailable(parent, completion, review)),
      exact14CompilerExecutionsEvidenced: exactCompilerExecutions(parent, completion),
      expectedFindingChangesMatched: findingChanges(parent, completion, planned.findings) });
    // Exact original bytes and actual application payload retained privately, never in Telegram/status.
    this.options.evidence.append('private_joined_delivery', { originalEvents: this.transport.originals(), completion, phase,
      deliveryReceipts: this.transport.receipts.snapshot(), image: this.image, summary });
    this.summaries.push(summary); this.previous = parent;
    check(summary.allChildComputationsEvidenced && summary.exact14CompilerExecutionsEvidenced && summary.expectedFindingChangesMatched,
      'patch_review_partial_or_expected_fix_not_observed');
    this.guard();
    const closed: unknown = this.options.adapter.deliveryVerified(submission.deliveryHandle, this.reviewClock!);
    check(closed === undefined, 'delivery_clock_hook_not_synchronous'); this.guard();
    this.transport.markDelivered(parent.snapshot.snapshotId); this.reviewClock = null;
  }
  private priorDeliveryConfirmed(): void {
    this.guard(); const state = this.options.journal.read();
    check(this.previous !== null && state.currentSnapshotId === this.previous.snapshot.snapshotId && state.localEpoch === this.summaries.length &&
      state.lastProblem === null && !state.pendingModelRequest && state.cut === null && Object.keys(state.submissions).length === 0 &&
      Object.keys(state.inbox).length === this.transport.originals().length && Object.values(state.inbox).every(entry => !entry.event.deliveryRequired ||
        entry.item !== null && entry.inserted !== null && entry.acknowledged && entry.observation !== null && entry.observed && !entry.stale),
      'next_patch_before_confirmed_delivery');
  }
  async run(captureCompletedPatch: CompletedPatchCapture): Promise<AcceptanceReport> {
    try {
      this.initialize();
      for (let number = 1; number <= 2; number++) {
        if (this.previous) {
          // A late old event must fail, never be silently skipped, reprojected as stale or sent in a new patch's turn.
          while (await this.once('between_patches_event_poll', () => this.controller.pollOnce())) { /* Outside model. */ }
          this.priorDeliveryConfirmed();
        }
        const patch = await this.once('capture_completed_mutation', () => captureCompletedPatch(number, this.previous?.snapshot ?? null,
          Object.freeze({ ownerId: this.clientBinding.ownerId, workspaceId: this.clientBinding.workspaceId,
            worktreeId: this.clientBinding.worktreeId, clientId: this.clientBinding.clientId, lineageId: this.clientBinding.lineageId,
            generation: this.clientBinding.generation, sessionId: this.clientBinding.sessionId })));
        await this.acceptPatch(patch);
      }
      this.finalBarrier = 'failed'; this.verifyImage();
      const cut = await this.once('final_prepare', () => this.controller.prepareFinal());
      const result = await this.once('final_complete', () => this.controller.completeFinal(cut.cutId));
      const completions = this.transport.finalCompletions();
      check(completions.length === 1, 'actual_gate_complete_required'); same(completions[0], result, 'actual_gate_complete_result');
      verifyDeniedFinal(this.options.host.binding, this.previous!.snapshot.snapshotId, this.transport.originals(), cut, result);
      this.verifyImage(); this.options.evidence.append('denied_final_barrier', { cut, result, image: this.image }); this.guard(); this.finalBarrier = 'denied';
    } catch (error) {
      this.failure = safeCurrentAcceptanceFailure(error);
      this.stopped = true; this.transport.poison();
    }
    const verified = this.failure === null && this.summaries.length === 2 && this.finalBarrier === 'denied';
    this.stopped = true; this.transport.poison();
    const report: AcceptanceReport = frozen({ schema: 'ktchecker-current-image-host-acceptance/1', origin: this.origin,
      approvalDigest: this.options.authorization.approvalDigest, binding: deliveryBinding(this.clientBinding), image: this.image,
      launch: this.launch, ownerCompilerPolicyAuditDigest: digest(canonical(this.options.ownerCompilerPolicyAudit)),
      status: verified ? 'verified_delivery_final_denied' : 'incomplete_or_failed', failure: this.failure,
      unknownOperation: this.unknownOperation, patches: [...this.summaries], finalBarrier: this.finalBarrier,
      actualHostDeliveryVerified: verified && this.origin === 'actual-current-image', usefulClean: false, qualified: false,
      fullProject: false, actionPerformed: false, internalInferenceRequestTrace: false, lifecycleDisposition: 'retained_no_quiescence_claim' });
    // A successful return also requires the final private summary write to fit the ORIGINAL
    // overall fence. A throwing/late write is not a returned verified report; callers must not
    // adopt an on-disk summary candidate alone as completion or retry the consumed components.
    // Observed reroutes also invalidate success without hiding/revising captured durable proof.
    // Incomplete diagnostic reports remain writable after expiry and confer no delivery claim.
    if (verified) { this.options.clock.checkpoint(); this.options.assertNoModelReroute(); this.audit?.assertIdle(); }
    this.options.evidence.append('acceptance_summary', report);
    if (verified) { this.options.clock.checkpoint(); this.options.assertNoModelReroute(); this.audit?.assertIdle(); }
    return report;
  }
}

// Check AFTER the producer promise settles, in the very callback returning the report. A
// sibling onRejected would not catch an onFulfilled guard throw, so deny it explicitly. No
// subsequent success-forwarding promise is added here, and no future notification is promised.
function guardAcceptanceReturn(pending: Promise<AcceptanceReport>, clock: AcceptanceClock,
  assertCurrent: () => void, deny: () => void): Promise<AcceptanceReport> {
  return pending.then(report => {
    if (report.status === 'verified_delivery_final_denied') {
      try { clock.checkpoint(); assertCurrent(); clock.checkpoint(); } catch (failure) { deny(); throw failure; }
    } else deny();
    return report;
  }, failure => { deny(); throw failure; });
}

// Process-lifetime tombstones for this exact actual component set, including uncertain first runs.
// A second object wrapping the same historic store is separately forbidden by the owner/source
// integration requirement; this set is NOT a process-restart or on-disk ownership proof.
const consumedAcceptanceComponents = new WeakSet<object>();
function claimComponents(components: readonly object[]): void {
  check(components.every(component => !consumedAcceptanceComponents.has(component)), 'components_previously_attempted');
  for (const component of components) consumedAcceptanceComponents.add(component);
}

export function runActualCurrentImageAcceptance(options: ActualRunOptions,
  captureCompletedPatch: CompletedPatchCapture): Promise<AcceptanceReport> {
  const launched = takeCurrentImageLaunch(options.launch);
  const { adapter, host, journal, clock, image, evidence } = launched;
  try {
    requireFullParentAdapter(adapter);
    check(host instanceof CodexHostPort && !host.resumed && host.evidence().length === 0 &&
      clock instanceof RealModelClock && image instanceof PinnedCurrentImage && evidence instanceof ExclusiveAcceptanceEvidence &&
      journal instanceof InboxJournal, 'actual_fresh_components_required');
    claimComponents([adapter, adapter.broker, adapter.registration, host, host.server, clock, journal, evidence]);
    const audit = new ActualApplicationAudit(host), assertNoModelReroute = (): void => host.server.assertNoModelReroute();
    const pending = new AcceptanceRun({ ...launched, assertNoModelReroute, forbidden: frozen([...options.forbidden]) }, 'actual-current-image',
      () => captureApplicationEvidence(host, journal), audit, launched.launchEvidence).run(captureCompletedPatch);
    return guardAcceptanceReturn(pending, clock, () => audit.assertIdle(), () => adapter.deny());
  } catch (failure) { adapter.deny(); throw failure; }
  // Denial only on the exact launcher-returned adapter. It retains owner/child/pending state,
  // never retries, signals, closes, certifies quiescence or turns failure into delivered evidence.
}

/** Explicit synthetic integration controls of the same scheduling/ReferenceHost engine. Result
 * origin can NEVER become actual-current-image, regardless of what the injected fixture claims.
 */
export function runSyntheticCurrentImageAcceptanceControl(options: SyntheticControlOptions,
  captureCompletedPatch: CompletedPatchCapture): Promise<AcceptanceReport> {
  claimComponents([options.adapter, options.adapter.broker, options.adapter.registration, options.host, options.journal, options.clock, options.evidence]);
  const authorization = frozen(structuredClone(options.authorization)), ownerInvocation = frozen(structuredClone(options.ownerInvocation)),
    privateLocations = frozen(structuredClone(options.privateLocations)), forbidden = frozen([...options.forbidden]);
  const ownerCompilerPolicy = frozen(structuredClone(options.ownerCompilerPolicy)), ownerCompilerPolicyAudit = frozen(structuredClone(options.ownerCompilerPolicyAudit));
  const pending = new AcceptanceRun({ ...options, authorization, ownerInvocation, privateLocations, forbidden, ownerCompilerPolicy, ownerCompilerPolicyAudit }, 'synthetic-control',
    () => options.syntheticEvidence(options.journal), null).run(captureCompletedPatch);
  // Same settled-return boundary, never an actual ticket or actual-host witness.
  return guardAcceptanceReturn(pending, options.clock, () => options.assertNoModelReroute(), () => {});
}
