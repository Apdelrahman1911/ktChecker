/** Opt-in REAL selected JVM pipe factory. Import/construction performs no IO or execution.
 * ONLY launcher-issued exact context + independently selected transport/image/install pins can
 * enter open. No caller subprocess/stream/provider/receipt can be substituted into this factory.
 */
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { dirname } from 'node:path';
import { PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, HostError } from '../src/journal.js';
import type { Registration } from '../src/types.js';
import { absoluteReal } from './real-model-run-support.js';
import { frozen, freezeCompletedPatch, id, same, sha,
  type CompletedPatch, type ParentAdmissionData, type ParentCompletionData } from './current-image-acceptance-evidence.js';
import { REQUIRED_PARENT_ADAPTER, isOriginalReviewObservationClockFor, type CurrentImageParentAdapter,
  type OriginalReviewObservationClock, type OwnerInvocationSelection } from './current-image-acceptance-runner.js';
import { claimFreshCurrentImageOwnerContext, type FreshCurrentImageOwnerContext, type FreshCurrentImageOwnerFactory,
  type ConfigurableCurrentImageParentAdapter } from './current-image-acceptance-launch.js';
import { validateOwnerCompilerPolicySelection, verifyOwnerCompilerPolicyAudit,
  type OwnerCompilerPolicySelection, type OwnerCompilerPolicyAudit } from './current-image-acceptance-policy.js';
import { captureOriginalOwnerEntry, OwnerSessionClocks } from './current-image-owner-clocks.js';
import { RetainedOwnerPipeChannel } from './current-image-owner-channel.js';
import { PreparedOwnerSessionSelection, selectedOwnerJvmArguments, type OwnerTransportSelection } from './current-image-owner-selection.js';
import { ownerCheck, ownerInteger, ownerKeys, ownerObject, OWNER_SESSION_ID } from './current-image-owner-wire.js';

interface FactoryState {
  readonly path: string; readonly digest: string;
  attempted: boolean; context: FreshCurrentImageOwnerContext | null; clocks: OwnerSessionClocks | null;
  selected: PreparedOwnerSessionSelection | null; channel: RetainedOwnerPipeChannel | null;
  child: ChildProcessWithoutNullStreams | null; opened: JsonObject | null; adapter: ConcreteCurrentImageParentAdapter | null;
  failure: HostError | null;
}
const factories = new WeakMap<object, FactoryState>();
const retainedFactories = new Set<FactoryState>();
const attempts = new Set<string>(), roots = new Set<string>();
const adapters = new WeakMap<object, { factory: RetainedCurrentImageOwnerFactory; context: FreshCurrentImageOwnerContext;
  child: ChildProcessWithoutNullStreams; state: FactoryState }>();
function failFactory(state: FactoryState, failure: unknown): HostError {
  state.failure ??= failure instanceof HostError ? failure : new HostError('current_owner_factory_outcome_unknown');
  state.clocks?.poison(state.failure); state.channel?.poison(state.failure); return state.failure;
}
/** Identity check only, NOT an execution grant. Constructor pins must equal launcher-selected
 * path/digest; actual selection/install verification happens under the original first120 in open.
 */
export function isRetainedCurrentImageOwnerFactory(value: unknown): value is RetainedCurrentImageOwnerFactory {
  return value !== null && typeof value === 'object' && factories.has(value);
}
export function isCurrentImageParentAdapterFor(value: unknown, factory: FreshCurrentImageOwnerFactory,
  context: FreshCurrentImageOwnerContext): value is ConfigurableCurrentImageParentAdapter {
  if (value === null || typeof value !== 'object') return false;
  const record = adapters.get(value);
  return record !== undefined && record.factory === factory && record.context === context && record.child === record.state.child &&
    record.state.adapter === value && record.state.failure === null;
}
export class RetainedCurrentImageOwnerFactory implements FreshCurrentImageOwnerFactory {
  readonly profile = REQUIRED_PARENT_ADAPTER;
  readonly #state: FactoryState;
  constructor(selectionPath: string, expectedSelectionDigest: string) {
    ownerCheck(new.target === RetainedCurrentImageOwnerFactory, 'concrete_factory_required');
    this.#state = { path: absoluteReal(selectionPath), digest: sha(expectedSelectionDigest), attempted: false, context: null,
      clocks: null, selected: null, channel: null, child: null, opened: null, adapter: null, failure: null };
    factories.set(this, this.#state); Object.freeze(this);
  }
  readonly open = async (context: FreshCurrentImageOwnerContext): Promise<ConfigurableCurrentImageParentAdapter> => {
    const entered = captureOriginalOwnerEntry(); // FIRST: before caller/context fields, selected IO/environment/spawn.
    const state = this.#state;
    try {
      ownerCheck(!state.attempted && retainedFactories.size < 64, 'factory_no_retry'); state.attempted = true; retainedFactories.add(state);
      // WeakMap issuer check consumes BEFORE reading any context field/getter. No profile/JSON grant.
      claimFreshCurrentImageOwnerContext(context, this); state.context = context;
      ownerCheck(state.path === context.ownerTransportSelectionPath && state.digest === context.ownerTransportSelectionDigest,
        'factory_transport_not_authorized_selection');
      ownerCheck(!attempts.has(context.attemptId) && !roots.has(context.ownerStateDirectory), 'factory_previously_attempted');
      attempts.add(context.attemptId); roots.add(context.ownerStateDirectory);
      const clocks = new OwnerSessionClocks(context.overall, entered); state.clocks = clocks;
      const channel = new RetainedOwnerPipeChannel(clocks); state.channel = channel;
      const selected = PreparedOwnerSessionSelection.read(state.path, state.digest, context, clocks); state.selected = selected;
      const environment = selected.environment(clocks), args = selectedOwnerJvmArguments(selected.selection, state.path, state.digest);
      selected.assertBeforeSpawn(clocks); clocks.checkpoint();
      // Fixed, selected real Java + exact ordered selected JARs. No shell/caller argv, normal RPC
      // elevation, inherited environment, model substitution or historical process adoption.
      const child = spawn(selected.selection.jvmExecutable.path, [...args], { cwd: dirname(context.ownerStateDirectory),
        env: environment, shell: false, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
      state.child = child; // FIRST after return: retain even if attaching/hello/adoption fails.
      channel.attachSpawnedChild(child); channel.checkpoint(); await channel.awaitHello();
      const opened = await channel.request('owner.open', {}); state.opened = opened;
      ownerKeys(opened, ['profile', 'registration']); ownerCheck(opened.profile === REQUIRED_PARENT_ADAPTER, 'opened_profile_mismatch');
      const registration = ownerRegistration(opened.registration, selected.selection);
      selected.verifyInitialCopies(clocks); channel.checkpoint(); clocks.adoptOpen();
      const adapter = new ConcreteCurrentImageParentAdapter(state, registration); state.adapter = adapter;
      adapters.set(adapter, { factory: this, context, child, state }); channel.checkpoint(); return adapter;
    } catch (failure) { throw failFactory(state, failure); }
  };
}
Object.freeze(RetainedCurrentImageOwnerFactory.prototype); Object.freeze(RetainedCurrentImageOwnerFactory);

function ownerRegistration(raw: unknown, choice: OwnerTransportSelection): Registration {
  const value = ownerObject(raw); ownerKeys(value, ['contractVersion', 'binding', 'client', 'adapter']);
  const binding = ownerObject(value.binding); ownerKeys(binding, ['ownerId', 'workspaceId', 'worktreeId', 'clientId', 'sessionId', 'lineageId', 'generation']);
  for (const key of ['ownerId', 'workspaceId', 'worktreeId', 'clientId', 'sessionId', 'lineageId']) id(binding[key]);
  ownerCheck(value.contractVersion === PREVIEW_CONTRACT && binding.generation === 1, 'registration_contract');
  same({ workspaceId: binding.workspaceId, worktreeId: binding.worktreeId, clientId: binding.clientId,
    sessionId: binding.sessionId, lineageId: binding.lineageId }, choice.registration, 'registration_not_selected');
  const client = ownerObject(value.client), adapter = ownerObject(value.adapter);
  for (const credential of [client, adapter]) {
    ownerKeys(credential, ['clientId', 'token']);
    ownerCheck(credential.clientId === binding.clientId && typeof credential.token === 'string' && credential.token.length > 20 &&
      credential.token.length <= 4096 && !/\p{Cc}/u.test(credential.token), 'registration_credential_shape');
  }
  ownerCheck(client.token !== adapter.token, 'registration_credential_alias');
  // SAME objects returned by the real private pipe, not copied/reconstructed server authority.
  return frozen(value) as unknown as Registration;
}

/** DATA-only explicit broker projection. Original credential identity is mandatory locally;
 * the JVM compares JSON but invokes its kernel with its own ORIGINAL retained credential object.
 * This helper cannot open/submit/configure/close anything; synthetic inputs remain synthetic.
 */
export function projectOwnerBrokerParams(method: string, raw: JsonObject, registration: Registration): JsonObject {
  const params = ownerObject(raw), adapter = method === 'review.ack' || method === 'review.observe';
  ownerCheck(['review.events', 'review.ack', 'review.observe', 'gate.prepare', 'gate.complete'].includes(method), 'broker_method_scope');
  ownerKeys(params, method === 'review.events' ? ['credential', 'cursor', 'limit'] : adapter ? ['credential', 'eventIds', 'receipt'] :
    method === 'gate.complete' ? ['credential', 'cutId'] : ['credential']);
  const credential = adapter ? registration.adapter : registration.client;
  ownerCheck(params.credential === credential, 'broker_original_credential_required');
  if (method === 'review.events') {
    const cursor = params.cursor, limit = ownerInteger(params.limit, 500, 1);
    ownerCheck(cursor === null || typeof cursor === 'string' && cursor.length > 0 && cursor.length <= 16_384 && !/\p{Cc}/u.test(cursor), 'broker_cursor');
    return Object.freeze({ credential, cursor, limit });
  }
  if (!adapter) return Object.freeze(method === 'gate.complete' ? { credential, cutId: id(params.cutId) } : { credential });
  // Do not clone/serialize arbitrary credential-bearing caller extras. Only these exact data fields.
  const detached = structuredClone({ eventIds: params.eventIds, receipt: params.receipt });
  ownerCheck(Array.isArray(detached.eventIds) && detached.eventIds.length > 0 && detached.eventIds.length <= 500 &&
    new Set(detached.eventIds).size === detached.eventIds.length, 'broker_event_ids');
  const eventIds = detached.eventIds.map(value => id(value)), receipt = ownerObject(detached.receipt);
  if (method === 'review.ack') {
    ownerKeys(receipt, ['receiptId', 'contextDigest', 'contextItemId', 'insertedAt']);
    id(receipt.receiptId); sha(receipt.contextDigest); id(receipt.contextItemId);
    ownerCheck(typeof receipt.insertedAt === 'string' && receipt.insertedAt.length > 0 && receipt.insertedAt.length <= 256 && !/\p{Cc}/u.test(receipt.insertedAt), 'insertion_time');
  } else {
    ownerKeys(receipt, ['observationId', 'modelRequestId', 'contextDigests']); id(receipt.observationId); id(receipt.modelRequestId);
    ownerCheck(Array.isArray(receipt.contextDigests) && receipt.contextDigests.length > 0 && receipt.contextDigests.length <= 500, 'observation_contexts');
    for (const value of receipt.contextDigests) sha(value);
  }
  return frozen({ credential, eventIds, receipt });
}
interface RetainedSubmission {
  readonly patch: CompletedPatch; readonly originalReview: OriginalReviewObservationClock;
  returned: JsonObject | null; handle: object | null; serverId: string | null; delivered: boolean;
}

/** Module-private concrete adapter. Branding happens ONLY after this factory's actual child open
 * and actual copied-input verification. Constructor acquisition/copy/proxy/subclass cannot mint it.
 */
class ConcreteCurrentImageParentAdapter implements ConfigurableCurrentImageParentAdapter {
  readonly profile = REQUIRED_PARENT_ADAPTER;
  readonly registration: Registration; readonly broker: BrokerPort;
  readonly #state: FactoryState;
  readonly #handles = new WeakMap<object, RetainedSubmission>(); readonly #serverIds = new Set<string>(); readonly #patchIds = new Set<string>();
  readonly #submissions: RetainedSubmission[] = [];
  #busy = false; #policyAttempted = false; #configured = false;
  #policyReturn: JsonObject | null = null; #lastStatus: JsonObject | null = null;
  constructor(state: FactoryState, registration: Registration) {
    ownerCheck(new.target === ConcreteCurrentImageParentAdapter && state.child !== null && state.context !== null && state.channel !== null &&
      state.clocks !== null && state.selected !== null && state.opened !== null && state.adapter === null, 'concrete_adapter_requires_opened_child');
    this.#state = state; this.registration = registration;
    this.broker = Object.freeze({ request: <T = JsonObject>(method: string, params: JsonObject): Promise<T> => this.#brokerRequest<T>(method, params) });
    Object.freeze(this);
  }
  #checkpoint(): void { if (this.#state.failure) throw this.#state.failure; this.#state.channel!.checkpoint(); }
  #claim(): void { this.#checkpoint(); ownerCheck(!this.#busy, 'adapter_concurrent_operation'); this.#busy = true; }
  #failure(failure: unknown): HostError { return failFactory(this.#state, failure); }
  readonly assertCurrent = (): void => {
    try { this.#checkpoint(); this.#state.selected!.assertCurrent(this.#state.clocks!); this.#checkpoint(); }
    catch (failure) { throw this.#failure(failure); }
  };
  readonly privateInputLocations = (): readonly string[] => this.#state.selected!.privateInputLocations();
  /** Local denial only; used by the nominal launcher if a later host/evidence step fails. */
  readonly deny = (): void => { this.#failure(new HostError('current_owner_launch_or_acceptance_denied')); };
  readonly transportEvidence = (): JsonObject => this.#state.channel!.evidence();
  readonly configureCompilerPolicy = async (selection: OwnerCompilerPolicySelection): Promise<OwnerCompilerPolicyAudit> => {
    try {
      this.#claim(); ownerCheck(!this.#policyAttempted && !this.#configured && this.#submissions.length === 0, 'policy_replayed_or_late');
      this.#policyAttempted = true;
      const detached = structuredClone(selection), expected = this.#state.context!.ownerCompilerPolicySelection;
      same(detached, expected, 'owner_policy_not_launcher_selected'); validateOwnerCompilerPolicySelection(detached, expected.compilerRuleIds);
      this.assertCurrent();
      const actual = await this.#state.channel!.request('owner.configureCompilerPolicy', {
        compilerRuleIds: [...detached.compilerRuleIds], reason: detached.reason, authorizeOwnerPolicy: detached.authorizeOwnerPolicy });
      this.#policyReturn = actual; this.#checkpoint();
      const verified = verifyOwnerCompilerPolicyAudit(actual as unknown as OwnerCompilerPolicyAudit, detached, this.registration.binding);
      this.assertCurrent(); this.#configured = true; this.#busy = false; return verified;
    } catch (failure) { throw this.#failure(failure); }
  };
  readonly submitCompletedPatch = async (patch: CompletedPatch, selection: OwnerInvocationSelection,
    scope: Parameters<CurrentImageParentAdapter['submitCompletedPatch']>[2]) => {
    const entered = captureOriginalOwnerEntry(); // FIRST; submit1 retains export1 instead of creating/resetting it.
    try {
      this.#claim(); // Internal frozen-clock/latch checks only, no caller getters.
      const detached = structuredClone({ patch, selection }); // WHOLE caller data before scope/ordinal getters or callbacks.
      const captured = freezeCompletedPatch(detached.patch); ownerCheck(Buffer.byteLength(canonical(captured.request)) <= 8_388_608, 'inner_patch_bound');
      ownerKeys(ownerObject(detached.selection), ['gradleHome', 'gradleCacheSeed', 'compilerJavaHome', 'compilerArtifactPaths', 'authorizeExecution']);
      same(detached.selection, this.#state.context!.invocationSelection, 'submit_selection_changed');
      same(captured.request.binding, this.registration.binding, 'submit_registration_changed');
      ownerCheck(this.#configured && !this.#patchIds.has(captured.request.patchId), 'submit_policy_or_patch_replay');
      ownerKeys(ownerObject(scope), ['overall', 'reviewObservation', 'originalExportMillis']);
      const saved = { overall: scope.overall, reviewObservation: scope.reviewObservation, originalExportMillis: scope.originalExportMillis };
      const originalReview = saved.reviewObservation;
      ownerCheck(isOriginalReviewObservationClockFor(originalReview, this.#state.context!.overall), 'submit_original_review_required');
      this.#state.clocks!.beginSubmission(entered, captured.request.ordinal, saved); this.#patchIds.add(captured.request.patchId);
      const retained: RetainedSubmission = { patch: captured.request, originalReview, returned: null, handle: null, serverId: null, delivered: false };
      this.#submissions.push(retained); this.assertCurrent();
      const actual = await this.#state.channel!.request('owner.submitCompletedPatch', { patch: captured.request, selection: detached.selection });
      retained.returned = actual; // FIRST after return, before shape checks/handle allocation or any late denial.
      this.#checkpoint(); ownerKeys(actual, ['data', 'handle']);
      ownerCheck(typeof actual.handle === 'string' && OWNER_SESSION_ID.test(actual.handle) && !this.#serverIds.has(actual.handle), 'server_handle_replayed_or_invalid');
      const data = ownerObject(actual.data); ownerKeys(data, ['snapshotCommit', 'declaredPlan', 'parent', 'observed', 'compilerRuleIds']);
      this.assertCurrent();
      const handle: object = Object.freeze(Object.create(null) as object);
      retained.handle = handle; retained.serverId = actual.handle; this.#serverIds.add(actual.handle); this.#handles.set(handle, retained);
      this.#state.clocks!.admitSubmission(handle); this.#checkpoint(); this.#busy = false;
      return Object.freeze({ data: data as unknown as ParentAdmissionData, deliveryHandle: handle });
    } catch (failure) { throw this.#failure(failure); }
  };
  readonly status = async (handle: object): Promise<ParentCompletionData> => {
    try {
      this.#claim(); const retained = this.#handles.get(handle); ownerCheck(retained && retained.serverId !== null, 'nominal_delivery_handle_required');
      // Overall + CURRENT active300 only. Never consult the old handle's closed120/300 object.
      const actual = await this.#state.channel!.request('owner.status', { handle: retained.serverId }); this.#lastStatus = actual;
      this.#checkpoint(); ownerKeys(actual, ['parent', 'children']); ownerObject(actual.parent); ownerCheck(Array.isArray(actual.children), 'status_children');
      this.#busy = false; return actual as unknown as ParentCompletionData;
    } catch (failure) { throw this.#failure(failure); }
  };
  async #brokerRequest<T>(method: string, params: JsonObject): Promise<T> {
    try {
      this.#claim(); ownerCheck(this.#configured, 'broker_before_policy');
      const projected = projectOwnerBrokerParams(method, params, this.registration); this.#checkpoint();
      const actual = await this.#state.channel!.request(method, projected); this.#checkpoint(); this.#busy = false; return actual as T;
    } catch (failure) { throw this.#failure(failure); }
  }
  readonly deliveryVerified = (handle: object, exactOriginalReviewClock: OriginalReviewObservationClock): void => {
    try {
      this.#claim(); const retained = this.#handles.get(handle);
      ownerCheck(retained && !retained.delivered && retained.originalReview === exactOriginalReviewClock, 'delivery_hook_wrong_handle_or_clock');
      this.assertCurrent(); this.#state.clocks!.deliveryVerified(handle, exactOriginalReviewClock);
      retained.delivered = true; this.#checkpoint(); this.#busy = false;
    } catch (failure) { throw this.#failure(failure); }
  };
}
Object.freeze(ConcreteCurrentImageParentAdapter.prototype); Object.freeze(ConcreteCurrentImageParentAdapter);
