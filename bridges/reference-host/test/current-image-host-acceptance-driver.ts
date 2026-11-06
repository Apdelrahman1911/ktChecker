/** Explicit NEW actual composition entry. Importing is inert: no read, directory, owner or host.
 * Root must first admit the exact source/image/selection and trusted-host public-fixture scope.
 * There is deliberately NO injected launch/factory/adapter/host/clock or historical-run option.
 */
import { write } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { canonical, digest } from '../src/journal.js';
import { check, record, same, sha } from './current-image-acceptance-evidence.js';
import { type CurrentImageSelection } from './current-image-acceptance-image.js';
import { PreparedCurrentImageLaunch, validateCurrentImageLaunchSelection,
  type CurrentImageLaunchSelection, type OpenedCurrentImageLaunch } from './current-image-acceptance-launch.js';
import { runActualCurrentImageAcceptance, type AcceptanceAuthorization, type AcceptanceReport } from './current-image-acceptance-runner.js';
import { RetainedCurrentImageOwnerFactory } from './current-image-owner-transport.js';
import { absoluteReal, jsonReal, makeFreshRealDirectory, readRealFile, safeRealFailure, writeNewRealJson } from './real-model-run-support.js';
import { captureCurrentImagePublicPatch, currentImageFixtureSchedule, currentImagePublicFixtureDigest,
  validateCurrentImageDriverConsent, CURRENT_IMAGE_TRUSTED_HOST_POLICY, type CurrentImageDriverConsent } from './current-image-host-acceptance-fixture.js';
import { CURRENT_IMAGE_OBSERVER_MILLIS, RetainedCurrentImageObservation, RetainedCurrentImagePublication,
  type CurrentImageObservation, type CurrentImagePublication } from './current-image-host-acceptance-observer.js';

export interface CurrentImageDriverArgs { readonly selectionPath: string; readonly selectionDigest: string }
export function parseCurrentImageDriverArgs(args: readonly string[]): CurrentImageDriverArgs {
  check(args.length === 5 && args[0] === '--selection' && args[2] === '--selection-digest' &&
    args[4] === '--approve-new-public-fixture-trusted-host', 'driver_explicit_new_launch_required');
  return Object.freeze({ selectionPath: absoluteReal(args[1]), selectionDigest: sha(args[3]) });
}
/** Additional code closure; existing PinnedCurrentImage independently retains its mandatory28. */
export function requiredCurrentImageDriverModules(): readonly Readonly<{ path: string; source: string }>[] {
  return Object.freeze(['current-image-host-acceptance-driver', 'current-image-host-acceptance-fixture', 'current-image-host-acceptance-observer']
    .map(name => Object.freeze({ path: fileURLToPath(new URL(name + '.js', import.meta.url)), source: 'bridges/reference-host/test/' + name + '.ts' })));
}
export function assertCurrentImageDriverImage(selection: CurrentImageSelection): void {
  const required = requiredCurrentImageDriverModules();
  check(required.every(entry => selection.controllerOutputs.some(pin => pin.path === entry.path)), 'driver_image_entry_omitted');
  check(required.every(entry => selection.sourceInputs.some(pin => pin.label === entry.source)), 'driver_image_source_omitted');
}

type Stage = 'selection' | 'prepared' | 'opening_owner_and_host' | 'running_two_patches' | 'runner_returned';
export interface CurrentImageDriverReceipt {
  readonly schema: 'ktchecker-current-image-driver-observation/1';
  readonly status: 'verified_delivery_final_denied' | 'incomplete_or_failed'; readonly stage: Stage;
  readonly observation: CurrentImageObservation; readonly launchSelectionDigest: string | null;
  readonly attemptIdDigest: string | null; readonly approvalDigest: string | null;
  readonly fixtureDigest: string; readonly trustedHostPolicy: typeof CURRENT_IMAGE_TRUSTED_HOST_POLICY;
  readonly runnerReportDigest: string | null; readonly runnerReturnObserved: boolean; readonly failure: string | null;
  readonly actualHostDeliveryVerified: boolean; readonly privateReceiptCommitted: boolean;
  readonly privateCandidateFileDigest: string | null; readonly diagnosticLineDigest: string | null;
  readonly publication: CurrentImagePublication | null;
  readonly driverReturnWithinOriginal900Attested: false; readonly publicationGrantsExecutionTime: false;
  readonly privateReceiptRequiresReturnedDriverObservation: true;
  readonly publicReadIsolationAttested: false; readonly qualified: false; readonly usefulClean: false;
  readonly internalInferenceRequestTrace: false; readonly actionPerformed: false;
  readonly lifecycleDisposition: 'retained_no_quiescence_claim'; readonly cleanupOrRetryAuthorized: false;
}
interface RetainedAttempt {
  stage: Stage; observer: RetainedCurrentImageObservation; args: CurrentImageDriverArgs | null;
  prepared: PreparedCurrentImageLaunch | null; selection: CurrentImageLaunchSelection | null;
  factory: RetainedCurrentImageOwnerFactory | null; opening: Promise<OpenedCurrentImageLaunch> | null;
  launch: OpenedCurrentImageLaunch | null; running: Promise<AcceptanceReport> | null;
  operation: Promise<AcceptanceReport> | null; report: AcceptanceReport | null; failure: unknown;
  evidenceDirectory: string | null; receipt: CurrentImageDriverReceipt | null;
  publication: Promise<CurrentImagePublication> | null; publicationResult: CurrentImagePublication | null;
  returnedReceipt: CurrentImageDriverReceipt | null; returnedRecordCommitted: boolean;
}
// Strong PROCESS-LIFETIME custody, including a failed first attempt. No reset/retry/removal API.
let retainedAttempt: RetainedAttempt | null = null;
let retainedPublication: RetainedCurrentImagePublication | null = null;

/** One bounded diagnostic stdout write. No resend on partial/unknown write, no stream end/close.
 * This separate <=15s publication wait is NOT time for owner/provider/model work after original900.
 */
function publishLine(bytes: Buffer): Promise<CurrentImagePublication> {
  check(retainedPublication === null, 'driver_publication_no_retry');
  retainedPublication = new RetainedCurrentImagePublication(process.hrtime.bigint());
  return retainedPublication.publish(bytes, (buffer, callback) => {
    write(1, buffer, 0, buffer.length, null, (error, count) => { callback(error, count); });
  });
}
/** Pure sanitized diagnostic DATA. Even a caller-fabricated successful-looking receipt cannot
 * turn these pre-return bytes into a successful driver return, model witness or publication ACK.
 */
export function currentImageDriverDiagnostic(receipt: CurrentImageDriverReceipt) {
  return Object.freeze({ schema: 'ktchecker-current-image-driver-candidate/1', status: 'incomplete_or_failed',
    kind: 'candidate_before_driver_return', candidateReceiptDigest: digest(canonical(receipt)),
    observationOutcome: receipt.observation.outcome, runnerReturnObserved: receipt.runnerReturnObserved,
    launchSelectionDigest: receipt.launchSelectionDigest, attemptIdDigest: receipt.attemptIdDigest,
    approvalDigest: receipt.approvalDigest, fixtureDigest: receipt.fixtureDigest,
    privateCandidateFileDigest: receipt.privateCandidateFileDigest, failure: receipt.failure,
    actualHostDeliveryVerified: false, requiresReturnedDriverObservation: true,
    publicationAcknowledgedByReader: false, publicReadIsolationAttested: false, qualified: false,
    internalInferenceRequestTrace: false, actionPerformed: false,
    lifecycleDisposition: 'retained_no_quiescence_claim', cleanupOrRetryAuthorized: false });
}
function selectedRead(state: RetainedAttempt, path: string, expected: string): unknown {
  state.observer.checkpoint(); const read = readRealFile(path, 2 * 1024 * 1024);
  check(read.evidence.digest === sha(expected), 'driver_selected_file_changed');
  state.observer.checkpoint(); return jsonReal(read.bytes);
}
async function conduct(state: RetainedAttempt, argv: readonly string[]): Promise<AcceptanceReport> {
  try {
    state.observer.checkpoint(); state.args = parseCurrentImageDriverArgs(argv);
    // The existing prepare FIRST creates the ORIGINAL overall clock before any selected IO.
    state.prepared = PreparedCurrentImageLaunch.prepare(state.args.selectionPath, state.args.selectionDigest);
    state.stage = 'prepared';
    const selection = validateCurrentImageLaunchSelection(selectedRead(state, state.args.selectionPath,
      state.args.selectionDigest) as CurrentImageLaunchSelection); state.selection = selection;
    const image = selectedRead(state, selection.imageSelectionPath, selection.imageSelectionDigest) as CurrentImageSelection;
    assertCurrentImageDriverImage(image);
    const independent = record(selectedRead(state, selection.independentVerificationPath, selection.independentVerificationFileDigest));
    validateCurrentImageDriverConsent(record(independent.driver) as unknown as CurrentImageDriverConsent);
    const authorization = selectedRead(state, selection.authorizationPath, selection.authorizationFileDigest) as AcceptanceAuthorization;
    const schedule = currentImageFixtureSchedule(authorization);
    state.observer.checkpoint();
    const directory = join(selection.runRoot, 'driver-observer'); makeFreshRealDirectory(directory); state.evidenceDirectory = directory;
    writeNewRealJson(join(directory, 'started.json'), { schema: 'ktchecker-current-image-driver-started/1',
      launchSelectionDigest: state.args.selectionDigest, attemptIdDigest: digest(selection.attemptId), approvalDigest: selection.approvalDigest,
      fixtureDigest: currentImagePublicFixtureDigest(), trustedHostPolicy: CURRENT_IMAGE_TRUSTED_HOST_POLICY,
      originalObservationMillis: CURRENT_IMAGE_OBSERVER_MILLIS, actualHostDeliveryVerified: false,
      publicReadIsolationAttested: false, qualified: false, lifecycleDisposition: 'retained_no_quiescence_claim' });
    state.observer.checkpoint();
    state.factory = new RetainedCurrentImageOwnerFactory(selection.ownerTransportSelectionPath, selection.ownerTransportSelectionFileDigest);
    state.stage = 'opening_owner_and_host'; state.opening = state.prepared.open(state.factory);
    state.launch = await state.opening; // FIRST after return; same nominal one-shot ticket, never inspected via take().
    state.observer.checkpoint(); state.stage = 'running_two_patches';
    state.running = runActualCurrentImageAcceptance({ launch: state.launch, forbidden: [] }, async (ordinal, previous, binding) => {
      state.observer.checkpoint();
      // Sole mutation producer: fixed PUBLIC bytes, same actual public binding and previous snapshot DATA.
      // No private store, credentials, path capture, injected parent or synthesized delivery witness.
      return captureCurrentImagePublicPatch(schedule, ordinal, previous, binding);
    });
    state.report = await state.running; // FIRST after actual report return, including a later observation timeout.
    state.stage = 'runner_returned'; return state.report;
  } catch (failure) { state.failure = failure; throw failure; }
}
function makeReceipt(state: RetainedAttempt, observation: CurrentImageObservation): CurrentImageDriverReceipt {
  const returned = observation.outcome === 'returned' && state.report !== null;
  let verified = false, failure: string | null = observation.outcome === 'deadline' ? 'current_image_driver_observation_deadline' :
    observation.outcome === 'rejected' ? safeRealFailure(state.failure) : state.report?.failure ?? null;
  // This branch receives ONLY the direct return retained above, never a caller/on-disk receipt.
  if (returned && state.report!.status === 'verified_delivery_final_denied') {
    try {
      const report = state.report!;
      check(report.schema === 'ktchecker-current-image-host-acceptance/1' && report.origin === 'actual-current-image' &&
        observation.originalObservationMillis === CURRENT_IMAGE_OBSERVER_MILLIS && observation.elapsedMillis < CURRENT_IMAGE_OBSERVER_MILLIS &&
        report.actualHostDeliveryVerified && report.failure === null &&
        report.unknownOperation === null && report.finalBarrier === 'denied' && report.patches.length === 2 &&
        report.patches.every(patch => patch.allChildComputationsEvidenced && patch.exact14CompilerExecutionsEvidenced && patch.expectedFindingChangesMatched) &&
        !report.qualified && !report.usefulClean && !report.fullProject && !report.actionPerformed && !report.internalInferenceRequestTrace &&
        report.lifecycleDisposition === 'retained_no_quiescence_claim' && state.launch !== null, 'driver_actual_runner_return_required');
      same(report.launch, state.launch!.evidence, 'driver_same_nominal_launch');
      check(report.approvalDigest === state.selection!.approvalDigest, 'driver_same_approval'); verified = true;
    } catch (error) { state.failure = error; failure = safeRealFailure(error); }
  }
  if (!verified && failure === null) failure = 'current_image_driver_incomplete';
  return Object.freeze({ schema: 'ktchecker-current-image-driver-observation/1',
    status: verified ? 'verified_delivery_final_denied' : 'incomplete_or_failed', stage: state.stage, observation,
    launchSelectionDigest: state.args?.selectionDigest ?? null, attemptIdDigest: state.selection ? digest(state.selection.attemptId) : null,
    approvalDigest: state.selection?.approvalDigest ?? null, fixtureDigest: currentImagePublicFixtureDigest(),
    trustedHostPolicy: CURRENT_IMAGE_TRUSTED_HOST_POLICY, runnerReportDigest: returned ? digest(canonical(state.report)) : null,
    runnerReturnObserved: returned, failure, actualHostDeliveryVerified: verified, privateReceiptCommitted: false,
    privateCandidateFileDigest: null, diagnosticLineDigest: null, publication: null,
    driverReturnWithinOriginal900Attested: false, publicationGrantsExecutionTime: false,
    privateReceiptRequiresReturnedDriverObservation: true, publicReadIsolationAttested: false,
    qualified: false, usefulClean: false, internalInferenceRequestTrace: false, actionPerformed: false,
    lifecycleDisposition: 'retained_no_quiescence_claim', cleanupOrRetryAuthorized: false });
}
/** Exact opt-in actual entry, no component injection. The returned observation is NOT process exit.
 * No cleanup even on success; the actual owner/host/pending work remain under their original custody.
 * A verified return describes the PRIOR within-original900 runner observation; subsequent diagnostic
 * publication does not claim a new delivery or that this driver function returned within original900.
 */
export async function runNewCurrentImageHostDriver(argv: readonly string[]): Promise<CurrentImageDriverReceipt> {
  const entered = process.hrtime.bigint(); // BEFORE parsing, selected IO, fixture or owner preparation.
  check(retainedAttempt === null, 'driver_no_retry');
  const state: RetainedAttempt = { stage: 'selection', observer: new RetainedCurrentImageObservation(entered), args: null,
    prepared: null, selection: null, factory: null, opening: null, launch: null, running: null, operation: null,
    report: null, failure: null, evidenceDirectory: null, receipt: null, publication: null, publicationResult: null,
    returnedReceipt: null, returnedRecordCommitted: false };
  retainedAttempt = state;
  // Attach the bounded observer BEFORE the first conduct microtask can prepare/read/open anything.
  state.operation = Promise.resolve().then(() => conduct(state, argv));
  const observation = await state.observer.observe(state.operation);
  let receipt = makeReceipt(state, observation); state.receipt = receipt;
  if (state.evidenceDirectory !== null) {
    try {
      // Candidate DATA deliberately NEVER carries a verified status, even when the actual runner returned.
      const candidate = writeNewRealJson(join(state.evidenceDirectory, 'observation.json'), currentImageDriverDiagnostic(receipt));
      receipt = Object.freeze({ ...receipt, privateReceiptCommitted: true, privateCandidateFileDigest: candidate.digest }); state.receipt = receipt;
    } catch (error) {
      state.failure = error;
      receipt = Object.freeze({ ...receipt, status: 'incomplete_or_failed', failure: 'current_image_driver_receipt_unconfirmed',
        actualHostDeliveryVerified: false }); state.receipt = receipt;
    }
  }
  const diagnostic = Buffer.from(canonical(currentImageDriverDiagnostic(receipt)) + '\n', 'utf8');
  receipt = Object.freeze({ ...receipt, diagnosticLineDigest: digest(diagnostic.toString('utf8')) }); state.receipt = receipt;
  state.publication = publishLine(diagnostic); state.publicationResult = await state.publication;
  receipt = Object.freeze({ ...receipt, publication: state.publicationResult }); state.receipt = receipt;
  if (!receipt.privateReceiptCommitted || state.publicationResult.outcome !== 'confirmed') {
    receipt = Object.freeze({ ...receipt, status: 'incomplete_or_failed', failure: receipt.failure ??
      (!receipt.privateReceiptCommitted ? 'current_image_driver_receipt_unconfirmed' : 'current_image_driver_publication_unconfirmed'),
      actualHostDeliveryVerified: false }); state.receipt = receipt;
  }
  return receipt;
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  void runNewCurrentImageHostDriver(process.argv.slice(2)).then(receipt => {
    const state = retainedAttempt!; state.returnedReceipt = receipt; // FIRST upon observing the actual driver return.
    if (state.evidenceDirectory !== null) {
      try {
        // Records a PRIOR actual function return. It does NOT confirm its own write/reader ACK,
        // process exit, cleanup, quiescence or a second/new delivery after the original deadline.
        writeNewRealJson(join(state.evidenceDirectory, 'returned.json'), {
          schema: 'ktchecker-current-image-driver-returned/1', driverReceipt: receipt,
          driverReceiptDigest: digest(canonical(receipt)), thisRecordWriteConfirmed: false,
          thisRecordAcknowledgedByReader: false, processExitObserved: false, cleanupOrRetryAuthorized: false });
        state.returnedRecordCommitted = true;
      } catch (failure) { state.failure = failure; process.exitCode = 1; }
    }
    // Only a natural process exit could use this status; setting it does not terminate retained work.
    if (receipt.status !== 'verified_delivery_final_denied') process.exitCode = 1;
  }, failure => {
    if (retainedAttempt !== null) retainedAttempt.failure = failure;
    process.exitCode = 1;
    // Never resend after a partial/unknown publication. This fallback is only a first diagnostic.
    if (retainedPublication === null) void publishLine(Buffer.from(canonical({
      schema: 'ktchecker-current-image-driver-failed/1', status: 'incomplete_or_failed',
      failure: safeRealFailure(failure), actualHostDeliveryVerified: false, qualified: false,
      lifecycleDisposition: 'retained_no_quiescence_claim', cleanupOrRetryAuthorized: false }) + '\n', 'utf8'));
  });
}
