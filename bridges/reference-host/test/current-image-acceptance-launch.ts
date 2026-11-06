/** Executable, explicit NEW-launch seam. Merely importing this file performs no IO or launch.
 * Only owner-pinned, independently source/synthetic-verified current images may be selected.
 * Disk bytes and construction inputs are evidenced, never claimed to attest loaded memory.
 */
import { join } from 'node:path';
import { PREVIEW_CONTRACT, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import { CodexHostPort, type CodexHostOptions } from '../src/codex-host.js';
import { canonical, digest, InboxJournal } from '../src/journal.js';
import type { ClientBinding } from '../src/types.js';
import { deliveryBinding } from './delivery-receipts.js';
import { absoluteReal, absentReal, jsonReal, makeFreshRealDirectory, readRealFile, RealModelClock,
  REAL_MODEL_RPC_BYTES, REAL_MODEL_PUBLIC_BYTES, safeRealFailure } from './real-model-run-support.js';
import { REAL_CODEX_MODEL, REAL_CODEX_EFFORT, REAL_CODEX_ELF_DIGEST, REAL_CODEX_ELF_BYTES } from './real-model-codex-selection.js';
import { PinnedCurrentImage, type CurrentImageEvidence, type ImagePin } from './current-image-acceptance-image.js';
import { ACCEPTANCE_MAX_EVENTS, check, frozen, id, record, same, sha } from './current-image-acceptance-evidence.js';
// The runner imports this module too. These live bindings are used only in method bodies,
// after module evaluation; no static initializer reads the other side of the ESM cycle.
import { ExclusiveAcceptanceEvidence, requireFullParentAdapter, validateAcceptanceSelections,
  type AcceptanceAuthorization, type CurrentImageParentAdapter, type OwnerInvocationSelection,
  type PrivateAcceptanceLocations } from './current-image-acceptance-runner.js';
import { verifyOwnerCompilerPolicyAudit, type OwnerCompilerPolicyAudit, type OwnerCompilerPolicySelection } from './current-image-acceptance-policy.js';
import { isCurrentImageParentAdapterFor, isRetainedCurrentImageOwnerFactory } from './current-image-owner-transport.js';

export const EXISTING_CODEX_CHILD_FAILURE_POLICY = 'inherited_own_codex_fail_SIGTERM_open_failure_close_may_SIGKILL_no_owner_compiler_retirement_claim';
export interface CurrentImageLaunchSelection {
  readonly schema: 'ktchecker-current-image-fresh-launch-selection/1';
  readonly attemptId: string; readonly approvalDigest: string; readonly runRoot: string;
  readonly executable: string; readonly args: readonly ['app-server', '--stdio'];
  readonly model: typeof REAL_CODEX_MODEL; readonly effort: typeof REAL_CODEX_EFFORT;
  readonly codexChildFailurePolicy: typeof EXISTING_CODEX_CHILD_FAILURE_POLICY;
  readonly imageSelectionPath: string; readonly imageSelectionDigest: string;
  readonly authorizationPath: string; readonly authorizationFileDigest: string;
  readonly ownerInvocationSelectionPath: string; readonly ownerInvocationSelectionFileDigest: string;
  readonly ownerCompilerPolicySelectionPath: string; readonly ownerCompilerPolicySelectionFileDigest: string;
  readonly ownerTransportSelectionPath: string; readonly ownerTransportSelectionFileDigest: string;
  readonly independentVerificationPath: string; readonly independentVerificationFileDigest: string;
}
export interface CurrentImageLaunchEvidence {
  readonly schema: 'ktchecker-current-image-host-launch/1'; readonly attemptId: string; readonly approvalDigest: string;
  readonly selectionDigest: string; readonly imageSelectionDigest: string; readonly independentVerificationFileDigest: string;
  readonly authorizationFileDigest: string; readonly authorizationCanonicalDigest: string;
  readonly ownerInvocationSelectionDigest: string; readonly ownerCompilerPolicySelectionDigest: string;
  readonly ownerTransportSelectionFileDigest: string;
  readonly ownerCompilerPolicyAuditDigest: string; readonly constructionOptionsDigest: string;
  readonly codexDigest: string; readonly codexBytes: number; readonly args: readonly ['app-server', '--stdio'];
  readonly model: typeof REAL_CODEX_MODEL; readonly effort: typeof REAL_CODEX_EFFORT; readonly failOnModelReroute: true;
  readonly overallClockIdentity: string; readonly originalOverallMillis: 900000;
  readonly beforeHostOpenElapsedMs: number; readonly hostOpenReturnedElapsedMs: number;
  readonly binding: ReturnType<typeof deliveryBinding>; readonly hostThreadId: string;
  readonly threadOrigin: 'own_new_thread_start_confirmed_not_resume';
  readonly codexChildFailurePolicy: typeof EXISTING_CODEX_CHILD_FAILURE_POLICY;
  readonly loadedImageAttestation: false; readonly internalInferenceRequestTrace: false;
  readonly ownerCompilerRetirementClaim: false; readonly outerCleanupOrRetry: false;
}
/** Factory is called ONCE by PreparedCurrentImageLaunch AFTER the original overall clock and
 * fresh root exist. Its typed JVM openLineage must receive this already-running same fence.
 * It must not begin a review, configure a rule implicitly, or start another clock/session.
 */
export interface FreshCurrentImageOwnerContext {
  readonly attemptId: string; readonly overall: RealModelClock;
  readonly ownerStateDirectory: string; readonly invocationRoot: string;
  readonly invocationSelection: OwnerInvocationSelection; readonly image: CurrentImageEvidence;
  readonly imageSelectionPath: string;
  readonly ownerCompilerPolicySelection: OwnerCompilerPolicySelection;
  readonly ownerTransportSelectionPath: string; readonly ownerTransportSelectionDigest: string;
}
export interface ConfigurableCurrentImageParentAdapter extends CurrentImageParentAdapter {
  configureCompilerPolicy(selection: OwnerCompilerPolicySelection): Promise<OwnerCompilerPolicyAudit>;
  assertCurrent(): void;
  privateInputLocations(): readonly string[];
  deny(): void;
  transportEvidence(): JsonObject;
}
export interface FreshCurrentImageOwnerFactory {
  readonly profile: string;
  open(context: FreshCurrentImageOwnerContext): Promise<ConfigurableCurrentImageParentAdapter>;
}

const launchKeys = ['schema', 'attemptId', 'approvalDigest', 'runRoot', 'executable', 'args', 'model', 'effort', 'codexChildFailurePolicy',
  'imageSelectionPath', 'imageSelectionDigest', 'authorizationPath', 'authorizationFileDigest', 'ownerInvocationSelectionPath',
  'ownerInvocationSelectionFileDigest', 'ownerCompilerPolicySelectionPath', 'ownerCompilerPolicySelectionFileDigest',
  'ownerTransportSelectionPath', 'ownerTransportSelectionFileDigest',
  'independentVerificationPath', 'independentVerificationFileDigest'];
/** Pure selection/options controls can test this without opening a host, owner or model. */
export function validateCurrentImageLaunchSelection(raw: CurrentImageLaunchSelection): CurrentImageLaunchSelection {
  same(Object.keys(record(raw)).sort(), [...launchKeys].sort(), 'launch_selection_schema');
  check(raw.schema === 'ktchecker-current-image-fresh-launch-selection/1' && raw.model === REAL_CODEX_MODEL && raw.effort === REAL_CODEX_EFFORT &&
    raw.codexChildFailurePolicy === EXISTING_CODEX_CHILD_FAILURE_POLICY, 'launch_exact_selection_required');
  id(raw.attemptId); sha(raw.approvalDigest); absoluteReal(raw.runRoot); absoluteReal(raw.executable);
  same(raw.args, ['app-server', '--stdio'], 'launch_exact_args_required');
  const paths = [raw.executable, raw.imageSelectionPath, raw.authorizationPath, raw.ownerInvocationSelectionPath,
    raw.ownerCompilerPolicySelectionPath, raw.ownerTransportSelectionPath, raw.independentVerificationPath];
  for (const path of paths) absoluteReal(path);
  for (const value of [raw.imageSelectionDigest, raw.authorizationFileDigest, raw.ownerInvocationSelectionFileDigest,
    raw.ownerCompilerPolicySelectionFileDigest, raw.ownerTransportSelectionFileDigest, raw.independentVerificationFileDigest]) sha(value);
  check(new Set(paths).size === paths.length && paths.every(path => path !== raw.runRoot && !path.startsWith(raw.runRoot + '/')),
    'launch_input_alias_or_inside_run');
  return frozen(structuredClone(raw));
}
export function selectedCurrentImageHostOptions(selection: CurrentImageLaunchSelection, selectedCodex: Readonly<ImagePin>,
  client: ClientBinding): CodexHostOptions {
  validateCurrentImageLaunchSelection(selection); deliveryBinding(client); id(client.sessionId);
  check(selection.executable === selectedCodex.path && selectedCodex.digest === REAL_CODEX_ELF_DIGEST &&
    selectedCodex.bytes === REAL_CODEX_ELF_BYTES, 'launch_executable_not_image_pin');
  return frozen({ directory: join(selection.runRoot, 'host-state'), cwd: join(selection.runRoot, 'public-workspace'),
    client: structuredClone(client), effort: REAL_CODEX_EFFORT, server: { executable: selection.executable, args: [...selection.args],
      timeoutMs: 15_000, maxLineBytes: REAL_MODEL_RPC_BYTES, maxPublicBytes: REAL_MODEL_PUBLIC_BYTES, failOnModelReroute: true } });
}

interface SelectedFile { readonly path: string; readonly digest: string }
export interface LaunchedCurrentImageComponents {
  readonly adapter: ConfigurableCurrentImageParentAdapter; readonly host: CodexHostPort; readonly journal: InboxJournal;
  readonly clock: RealModelClock; readonly evidence: ExclusiveAcceptanceEvidence; readonly image: PinnedCurrentImage;
  readonly authorization: AcceptanceAuthorization; readonly ownerInvocation: OwnerInvocationSelection;
  readonly ownerCompilerPolicy: OwnerCompilerPolicySelection; readonly ownerCompilerPolicyAudit: OwnerCompilerPolicyAudit;
  readonly privateLocations: PrivateAcceptanceLocations; readonly launchEvidence: CurrentImageLaunchEvidence;
  readonly assertLaunchCurrent: () => void;
}
// The constructor is module-private, and the WeakMap is the nominal authority. A JSON object,
// caller CodexHostPort, Object.create() imitation or type cast never enters this registry.
class LaunchTicket {
  private readonly nominal = true;
  constructor(readonly evidence: CurrentImageLaunchEvidence) { Object.freeze(this); }
}
export type OpenedCurrentImageLaunch = LaunchTicket;
const tickets = new WeakMap<LaunchTicket, { components: LaunchedCurrentImageComponents; consumed: boolean }>();
const attemptIds = new Set<string>(), runRoots = new Set<string>();
const retainedAttempts = new Set<PreparedCurrentImageLaunch>();
const issuedOwnerContexts = new WeakMap<object, { readonly factory: FreshCurrentImageOwnerFactory; claimed: boolean }>();
/** The only exported operation CONSUMES a context, never issues one. No getters, profile,
 * timestamp, copied JSON or factory constructor can enroll a selected launch context. */
export function claimFreshCurrentImageOwnerContext(context: FreshCurrentImageOwnerContext, factory: FreshCurrentImageOwnerFactory): void {
  const issued = issuedOwnerContexts.get(context);
  check(issued && !issued.claimed && issued.factory === factory, 'actual_fresh_owner_context_required');
  issued.claimed = true;
}

/** No reset/reopen/retry/close/signal/delete method. Failed and late-resolving own handles remain
 * strongly retained, not adopted as a second attempt and not declared closed/quiescent.
 * Existing CodexAppServer.fail/host-open catch may signal THEIR OWN newly created Codex child;
 * this seam neither suppresses that policy nor misstates it as zero host signals.
 */
export class PreparedCurrentImageLaunch {
  #state: 'prepared' | 'opening_owner' | 'configuring_owner' | 'opening_host' | 'opened' | 'poisoned' = 'prepared';
  #retainedOwner: ConfigurableCurrentImageParentAdapter | null = null;
  #retainedHost: CodexHostPort | null = null;
  #retainedJournal: InboxJournal | null = null;
  readonly #evidence: ExclusiveAcceptanceEvidence;
  readonly #selection: CurrentImageLaunchSelection; readonly #selectionDigest: string;
  readonly #selectedFiles: readonly SelectedFile[]; readonly #image: PinnedCurrentImage;
  readonly #clock: RealModelClock; readonly #authorization: AcceptanceAuthorization;
  readonly #invocation: OwnerInvocationSelection; readonly #policy: OwnerCompilerPolicySelection;
  private constructor(selection: CurrentImageLaunchSelection, selectionDigest: string,
    selectedFiles: readonly SelectedFile[], image: PinnedCurrentImage, clock: RealModelClock,
    authorization: AcceptanceAuthorization, invocation: OwnerInvocationSelection, policy: OwnerCompilerPolicySelection) {
    check(new.target === PreparedCurrentImageLaunch, 'concrete_prepared_launch_required');
    this.#selection = selection; this.#selectionDigest = selectionDigest; this.#selectedFiles = selectedFiles;
    this.#image = image; this.#clock = clock; this.#authorization = authorization; this.#invocation = invocation; this.#policy = policy;
    // Exclusively NEW root; EEXIST is a stop, never an invitation to inspect/clean/reuse it.
    makeFreshRealDirectory(selection.runRoot);
    this.#evidence = new ExclusiveAcceptanceEvidence(join(selection.runRoot, 'evidence'));
    makeFreshRealDirectory(join(selection.runRoot, 'public-workspace')); Object.freeze(this);
  }
  static prepare(selectionPath: string, expectedSelectionDigest: string): PreparedCurrentImageLaunch {
    const clock = new RealModelClock(); // MUST precede even selected-file/image reads and owner construction.
    const files: SelectedFile[] = [];
    const load = (path: string, expected: string): unknown => {
      clock.checkpoint(); const read = readRealFile(path, 2 * 1024 * 1024);
      check(read.evidence.digest === sha(expected), 'launch_selected_file_changed');
      files.push(frozen({ path, digest: expected })); clock.checkpoint(); return jsonReal(read.bytes);
    };
    const selection = validateCurrentImageLaunchSelection(load(absoluteReal(selectionPath), expectedSelectionDigest) as CurrentImageLaunchSelection);
    check(retainedAttempts.size < 64 && !attemptIds.has(selection.attemptId) && !runRoots.has(selection.runRoot), 'launch_previously_attempted');
    attemptIds.add(selection.attemptId); runRoots.add(selection.runRoot); // Never rolled back after later uncertainty.
    const image = PinnedCurrentImage.open(selection.imageSelectionPath, selection.imageSelectionDigest); clock.checkpoint();
    const authorization = frozen(load(selection.authorizationPath, selection.authorizationFileDigest) as AcceptanceAuthorization),
      invocation = frozen(load(selection.ownerInvocationSelectionPath, selection.ownerInvocationSelectionFileDigest) as OwnerInvocationSelection),
      policy = frozen(load(selection.ownerCompilerPolicySelectionPath, selection.ownerCompilerPolicySelectionFileDigest) as OwnerCompilerPolicySelection),
      independent = record(load(selection.independentVerificationPath, selection.independentVerificationFileDigest));
    validateAcceptanceSelections(authorization, invocation, policy);
    check(authorization.attemptId === selection.attemptId && authorization.approvalDigest === selection.approvalDigest &&
      authorization.imageSelectionDigest === selection.imageSelectionDigest, 'launch_authorization_not_selected');
    // Owner-pinned linkage to independent source review + completed synthetic controls, NOT a
    // verifier-generated test certificate or permission inferred from synthetic fixtures.
    check(independent.schema === 'ktchecker-current-image-host-independent-preexecution/1' && independent.status === 'verified_source_and_synthetic_controls' &&
      independent.imageSelectionDigest === selection.imageSelectionDigest && independent.qualified === false &&
      independent.actualOwnerOrModelAttempt === false, 'launch_independent_preexecution_required');
    id(independent.verificationId); sha(independent.sourceReviewDigest); sha(independent.syntheticControlsEvidenceDigest);
    image.assertCompilerArtifactPaths(invocation.compilerArtifactPaths); image.assertLaunchExecutable(selection.executable);
    image.assertInputsOutside(selection.runRoot, [...files.map(file => file.path), selection.ownerTransportSelectionPath, invocation.gradleHome, invocation.gradleCacheSeed,
      invocation.compilerJavaHome, ...invocation.compilerArtifactPaths]); clock.checkpoint();
    // These are later rechecked independently of the retained copied toolchain. No selected
    // launch/image input may indirectly cause a per-patch read beneath the ORIGINAL external seed.
    const seed = invocation.gradleCacheSeed;
    check([...files.map(file => file.path), selection.ownerTransportSelectionPath, ...image.privateInputLocations()].every(path =>
      path !== seed && !path.startsWith(seed + '/') && !seed.startsWith(path + '/')), 'launch_input_overlaps_external_seed');
    // Do NOT load ownerTransportSelectionPath here. Its independently pinned reference is in
    // this launch selection; its first read belongs inside factory.open's ORIGINAL first120.
    const prepared = new PreparedCurrentImageLaunch(selection, expectedSelectionDigest, frozen(files), image, clock, authorization, invocation, policy);
    retainedAttempts.add(prepared); prepared.#assertSelectionsCurrent();
    prepared.#evidence.append('launch_prepared', { attemptId: selection.attemptId, selectionDigest: expectedSelectionDigest,
      independentVerificationFileDigest: selection.independentVerificationFileDigest, originalOverallMillis: 900000,
      ownerTransportSelectionFileDigest: selection.ownerTransportSelectionFileDigest,
      elapsedMs: clock.elapsedMs(), loadedImageAttestation: false, historicalReuse: false });
    clock.checkpoint(); return prepared;
  }
  #assertSelectionsCurrent(): void {
    this.#clock.checkpoint();
    for (const file of this.#selectedFiles) check(readRealFile(file.path, 2 * 1024 * 1024).evidence.digest === file.digest, 'launch_selected_file_changed');
    this.#clock.checkpoint();
  }
  async open(ownerFactory: FreshCurrentImageOwnerFactory): Promise<OpenedCurrentImageLaunch> {
    check(retainedAttempts.has(this), 'actual_prepared_launch_required');
    check(this.#state === 'prepared', 'launch_no_retry'); this.#state = 'opening_owner';
    const selection = this.#selection, ownerStateDirectory = join(selection.runRoot, 'owner-state'),
      invocationRoot = join(ownerStateDirectory, 'model-command', 'runs'), hostStateDirectory = join(selection.runRoot, 'host-state'),
      journalDirectory = join(selection.runRoot, 'journal');
    let stage = 'fresh_owner_initialization';
    let pairedOwner: ConfigurableCurrentImageParentAdapter | null = null;
    try {
      check(isRetainedCurrentImageOwnerFactory(ownerFactory), 'actual_owner_factory_required');
      requireFullParentAdapter(ownerFactory);
      this.#assertSelectionsCurrent(); this.#image.assertCurrent(); this.#clock.checkpoint();
      // Absence of the owner-state ancestor also proves its selected nested invocation path is
      // not an existing store; do not traverse a nonexistent parent or weaken owner containment.
      check(absentReal(ownerStateDirectory) && absentReal(hostStateDirectory) && absentReal(journalDirectory), 'launch_state_not_fresh');
      this.#evidence.append('operation_attempted', { stage, elapsedMs: this.#clock.elapsedMs(), outcome: 'unknown_until_returned' });
      const context: FreshCurrentImageOwnerContext = Object.freeze({ attemptId: selection.attemptId, overall: this.#clock, ownerStateDirectory, invocationRoot,
        invocationSelection: this.#invocation, image: this.#image.evidence, imageSelectionPath: selection.imageSelectionPath,
        ownerCompilerPolicySelection: this.#policy, ownerTransportSelectionPath: selection.ownerTransportSelectionPath,
        ownerTransportSelectionDigest: selection.ownerTransportSelectionFileDigest });
      const owner = await this.#clock.wait(async () => {
        issuedOwnerContexts.set(context, { factory: ownerFactory, claimed: false });
        const value = await ownerFactory.open(context);
        this.#retainedOwner = value; // FIRST after return, even if the outer original wait timed out.
        check(isCurrentImageParentAdapterFor(value, ownerFactory, context), 'actual_paired_owner_adapter_required');
        pairedOwner = value;
        if (this.#state === 'poisoned') { value.deny(); check(false, 'late_owner_return_denied'); }
        return value;
      });
      this.#clock.checkpoint(); check(isCurrentImageParentAdapterFor(owner, ownerFactory, context), 'actual_paired_owner_adapter_required');
      requireFullParentAdapter(owner); owner.assertCurrent();
      const registration = owner.registration, broker = owner.broker;
      check(registration.contractVersion === PREVIEW_CONTRACT && registration.client.clientId === registration.binding.clientId &&
        registration.adapter.clientId === registration.binding.clientId, 'fresh_owner_registration');
      same(this.#authorization.requiredClientIds, [registration.binding.clientId], 'required_single_client_scope');
      const ownerBinding = frozen(structuredClone(registration.binding)); deliveryBinding(ownerBinding); id(ownerBinding.sessionId);
      this.#evidence.append('operation_returned', { stage, elapsedMs: this.#clock.elapsedMs(), binding: deliveryBinding(ownerBinding),
        ownerTransport: owner.transportEvidence() });
      this.#state = 'configuring_owner'; stage = 'explicit_owner_compiler_policy'; this.#assertSelectionsCurrent(); this.#image.assertCurrent(); this.#clock.checkpoint();
      this.#evidence.append('operation_attempted', { stage, selectionDigest: digest(canonical(this.#policy)), outcome: 'unknown_until_returned' });
      const policyAudit = verifyOwnerCompilerPolicyAudit(await this.#clock.wait(() => owner.configureCompilerPolicy(this.#policy)), this.#policy, ownerBinding);
      this.#clock.checkpoint(); this.#evidence.append('owner_compiler_policy_audit', policyAudit); this.#clock.checkpoint();
      check(owner.registration === registration && owner.broker === broker, 'launch_owner_components_replaced');
      same(registration.binding, ownerBinding, 'launch_owner_binding_changed');
      this.#assertSelectionsCurrent(); const codex = this.#image.assertLaunchExecutable(selection.executable); this.#clock.checkpoint();
      const hostOptions = selectedCurrentImageHostOptions(selection, codex, ownerBinding);
      check(absentReal(hostStateDirectory) && absentReal(journalDirectory), 'launch_host_store_not_fresh');
      makeFreshRealDirectory(hostStateDirectory); makeFreshRealDirectory(journalDirectory);
      this.#state = 'opening_host'; stage = 'fresh_selected_codex_host_open';
      const beforeHostOpenElapsedMs = this.#clock.elapsedMs();
      this.#evidence.append('private_host_launch_intent', { stage, hostOptions, selectionDigest: this.#selectionDigest,
        attemptId: selection.attemptId, beforeHostOpenElapsedMs, ownerCompilerPolicyAuditDigest: digest(canonical(policyAudit)),
        codexChildFailurePolicy: EXISTING_CODEX_CHILD_FAILURE_POLICY, outcome: 'unknown_until_returned' });
      const host = await this.#clock.wait(async () => {
        const value = await CodexHostPort.open(hostOptions); this.#retainedHost = value; return value;
      }, 60_000);
      const hostOpenReturnedElapsedMs = this.#clock.elapsedMs(); this.#clock.checkpoint();
      check(!host.resumed && host.evidence().length === 0, 'launch_not_fresh_thread'); same(host.binding.client, ownerBinding, 'launch_host_binding');
      check(owner.registration === registration && owner.broker === broker, 'launch_owner_components_replaced');
      same(registration.binding, ownerBinding, 'launch_owner_binding_changed');
      const journal = new InboxJournal(journalDirectory, host.binding, { maxEvents: ACCEPTANCE_MAX_EVENTS, maxStateBytes: REAL_MODEL_RPC_BYTES });
      this.#retainedJournal = journal; this.#assertSelectionsCurrent(); this.#image.assertCurrent(); this.#clock.checkpoint();
      const launchEvidence: CurrentImageLaunchEvidence = frozen({ schema: 'ktchecker-current-image-host-launch/1', attemptId: selection.attemptId,
        approvalDigest: selection.approvalDigest, selectionDigest: this.#selectionDigest, imageSelectionDigest: selection.imageSelectionDigest,
        ownerTransportSelectionFileDigest: selection.ownerTransportSelectionFileDigest,
        independentVerificationFileDigest: selection.independentVerificationFileDigest, authorizationFileDigest: selection.authorizationFileDigest,
        authorizationCanonicalDigest: digest(canonical(this.#authorization)), ownerInvocationSelectionDigest: digest(canonical(this.#invocation)),
        ownerCompilerPolicySelectionDigest: digest(canonical(this.#policy)), ownerCompilerPolicyAuditDigest: digest(canonical(policyAudit)),
        constructionOptionsDigest: digest(canonical(hostOptions)), codexDigest: codex.digest, codexBytes: codex.bytes, args: selection.args,
        model: REAL_CODEX_MODEL, effort: REAL_CODEX_EFFORT, failOnModelReroute: true,
        overallClockIdentity: digest(canonical({ enteredNanos: this.#clock.enteredNanos.toString(), enteredAt: this.#clock.enteredAt,
          originalDeadlineNanos: this.#clock.originalDeadlineNanos.toString() })), originalOverallMillis: 900000,
        beforeHostOpenElapsedMs, hostOpenReturnedElapsedMs, binding: deliveryBinding(ownerBinding), hostThreadId: host.binding.hostThreadId,
        threadOrigin: 'own_new_thread_start_confirmed_not_resume', codexChildFailurePolicy: EXISTING_CODEX_CHILD_FAILURE_POLICY,
        loadedImageAttestation: false, internalInferenceRequestTrace: false, ownerCompilerRetirementClaim: false, outerCleanupOrRetry: false });
      const privateLocations = frozen({ ownerStateDirectory, invocationRoot, hostStateDirectory, journalDirectory,
        additional: [selection.runRoot, ...this.#selectedFiles.map(file => file.path), ...this.#image.privateInputLocations(), ...owner.privateInputLocations()] });
      const assertLaunchCurrent = (): void => {
        this.#assertSelectionsCurrent(); owner.assertCurrent(); this.#clock.checkpoint(); host.server.assertNoModelReroute();
      };
      assertLaunchCurrent();
      const ticket = new LaunchTicket(launchEvidence);
      tickets.set(ticket, { consumed: false, components: { adapter: owner, host, journal, clock: this.#clock, evidence: this.#evidence,
        image: this.#image, authorization: this.#authorization, ownerInvocation: this.#invocation, ownerCompilerPolicy: this.#policy,
        ownerCompilerPolicyAudit: policyAudit, privateLocations, launchEvidence, assertLaunchCurrent } });
      this.#evidence.append('fresh_host_launch_confirmed', launchEvidence); this.#clock.checkpoint(); this.#state = 'opened'; return ticket;
    } catch (error) {
      this.#state = 'poisoned';
      // Only the checked nominal adapter may be called; an invalid return is retained but never invoked.
      if (pairedOwner !== null) (pairedOwner as ConfigurableCurrentImageParentAdapter).deny();
      try { this.#evidence.append('launch_incomplete', { stage, failure: safeRealFailure(error), outcome: 'retained_no_quiescence_claim',
        outerCleanupOrRetry: false, codexChildFailurePolicy: EXISTING_CODEX_CHILD_FAILURE_POLICY }); } catch { /* Never overwrite an earlier failure or perform cleanup. */ }
      throw error;
    }
  }
}
Object.freeze(PreparedCurrentImageLaunch.prototype); Object.freeze(PreparedCurrentImageLaunch);

/** One-shot registry consumption. Even validation failure consumes this ticket; no historic
 * store adoption or caller-supplied CodexHostPort construction path exists in the actual factory.
 */
export function takeCurrentImageLaunch(ticket: OpenedCurrentImageLaunch): LaunchedCurrentImageComponents {
  const saved = tickets.get(ticket); check(saved && !saved.consumed, 'actual_fresh_launch_ticket_required'); saved.consumed = true;
  try { saved.components.clock.checkpoint(); saved.components.assertLaunchCurrent(); return saved.components; }
  catch (failure) { saved.components.adapter.deny(); throw failure; }
}
