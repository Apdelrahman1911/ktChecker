/** OPT-IN executable, deliberately NOT named *.test.ts and absent from npm test scripts.
 * Requires a separately reviewed, explicitly admitted fresh build/stage/input digest.
 * One real Main JVM; framing/clock/pre-owner denial ONLY. No owner/model/provider success.
 */
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { join } from 'node:path';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest, HostError } from '../src/journal.js';
import { captureOriginalOwnerEntry, OwnerSessionClocks } from './current-image-owner-clocks.js';
import { RetainedOwnerPipeChannel } from './current-image-owner-channel.js';
import { ownerDirectoryIdentity } from './current-image-owner-selection.js';
import { ownerCheck } from './current-image-owner-wire.js';
import { PreownerOnlyWireProof } from './current-image-owner-preowner-proof.js';
import { PREOWNER_SENTINEL_TEXT, preownerJvmArguments, readPreownerSmokeInputs, recheckPreownerSmokeInputs,
  type PreparedPreownerSmokeInputs } from './current-image-owner-preowner-inputs.js';
import { absoluteReal, absentReal, makeFreshRealDirectory, RealModelClock, realRunBoundary, safeRealFailure, shaReal,
  writeNewReal, type RealFileEvidence } from './real-model-run-support.js';

interface Observation { readonly code: number | null; readonly signal: NodeJS.Signals | null }
interface RetainedPreownerAttempt {
  readonly overall: RealModelClock; readonly clocks: OwnerSessionClocks; readonly channel: RetainedOwnerPipeChannel;
  selected: PreparedPreownerSmokeInputs | null; child: ChildProcessWithoutNullStreams | null; proof: PreownerOnlyWireProof | null;
  failure: HostError | null; exit: Observation | null; close: Observation | null;
  inputClosed: boolean; outputClosed: boolean; stderrClosed: boolean;
  inputErrors: number; outputErrors: number; stderrErrors: number; shimErrors: number; childErrors: number;
  openedReturn: JsonObject | null; openRejected: boolean; opening: Promise<JsonObject> | null;
}
// Never evicted/unrefed/adopted as another attempt. This entry can start only once.
const retainedAttempts: RetainedPreownerAttempt[] = [];
const OUTPUT_NAMES = new Set(['unread-owner-selection.json', 'admission.json', 'invocation.json', 'wire-proof.json', 'result.json']);

async function main(): Promise<void> {
  const overall = new RealModelClock(); // FIRST, before arguments, selected IO, directories or spawn.
  const entered = captureOriginalOwnerEntry(); // Actual first120, retained by the SAME concrete channel ledger.
  const clocks = new OwnerSessionClocks(overall, entered), channel = new RetainedOwnerPipeChannel(clocks);
  // Additional smoke observation denial starts slightly EARLIER than that original first120.
  // It can only shorten waiting; never refreshes a phase or grants process cleanup authority.
  const originalSmokeDeadline = overall.enteredNanos + 120_000_000_000n;
  const attempt: RetainedPreownerAttempt = { overall, clocks, channel, selected: null, child: null, proof: null,
    failure: null, exit: null, close: null, inputClosed: false, outputClosed: false, stderrClosed: false,
    inputErrors: 0, outputErrors: 0, stderrErrors: 0, shimErrors: 0, childErrors: 0,
    openedReturn: null, openRejected: false, opening: null };
  ownerCheck(retainedAttempts.length === 0, 'preowner_entry_replayed'); retainedAttempts.push(attempt);
  let outputRoot: string | null = null; let outputIdentity: string | null = null;
  const environmentDirectories: { path: string; identity: string }[] = [];
  const consumedOutputs = new Set<string>(), files: RealFileEvidence[] = [];
  let deadlineTimer: NodeJS.Timeout | null = null, expired: Promise<never> | null = null;
  let observed = false;
  let runtimeBoundaryBeforeReport: ReturnType<typeof realRunBoundary> | null = null;
  let finalRuntimeBoundary: ReturnType<typeof realRunBoundary> | null = null;
  process.exitCode = 1; // Success is impossible until the final stdout callback AND deadline checks.
  const fail = (error: unknown): HostError => {
    attempt.failure ??= error instanceof HostError ? error : new HostError('current_owner_preowner_outcome_unknown');
    observed = false; process.exitCode = 1;
    attempt.proof?.fail(attempt.failure); channel.poison(attempt.failure); return attempt.failure;
  };
  process.stdout.on('error', () => { fail(new HostError('current_owner_preowner_report_delivery_unknown')); process.exitCode = 1; });
  const remaining = (): number => {
    RealModelClock.prototype.checkpoint.call(overall);
    const value = Math.floor(Number(originalSmokeDeadline - process.hrtime.bigint()) / 1_000_000);
    ownerCheck(value > 0 && value <= 120_000, 'preowner_original_observation_expired'); return value;
  };
  const publicationCheckpoint = (): void => {
    if (attempt.failure) throw attempt.failure;
    remaining(); // SAME original overall900 and earlier original120; never poisoned channel success.
  };
  const checkOutputRoot = (): void => {
    ownerCheck(outputRoot !== null && ownerDirectoryIdentity(outputRoot, true) === outputIdentity, 'preowner_output_root_changed');
    for (const directory of environmentDirectories) ownerCheck(ownerDirectoryIdentity(directory.path, true) === directory.identity,
      'preowner_environment_directory_changed');
  };
  const writeOwned = (name: string, bytes: Buffer): RealFileEvidence => {
    const withinOriginalDeadline = attempt.failure === null;
    if (withinOriginalDeadline) publicationCheckpoint();
    ownerCheck(OUTPUT_NAMES.has(name) && !consumedOutputs.has(name) && bytes.length <= 1_048_576, 'preowner_owned_output_bound');
    consumedOutputs.add(name); // Unknown/partial file delivery is NEVER retried or overwritten.
    checkOutputRoot();
    if (withinOriginalDeadline) publicationCheckpoint();
    const output = writeNewReal(join(outputRoot!, name), bytes); files.push(output); // Retain BEFORE late-delivery denial.
    if (withinOriginalDeadline) publicationCheckpoint();
    return output;
  };
  const writeJson = (name: string, value: unknown): RealFileEvidence => writeOwned(name, Buffer.from(canonical(value) + '\n', 'utf8'));
  const measureOutputBoundary = (): ReturnType<typeof realRunBoundary> => {
    checkOutputRoot();
    if (attempt.failure === null) publicationCheckpoint();
    const boundary = realRunBoundary(outputRoot!);
    if (attempt.failure === null) publicationCheckpoint();
    ownerCheck(boundary.entries <= 64 && boundary.bytes <= 2_097_152, 'preowner_runtime_output_bound');
    checkOutputRoot();
    if (attempt.failure === null) publicationCheckpoint();
    return boundary;
  };

  try { // Retain the original deadline timer through evidence IO AND final stdout callback.
    try {
      const args = process.argv.slice(2);
      ownerCheck(args.length === 4 && args[0] === '--selection' && args[2] === '--selection-digest', 'preowner_explicit_arguments_required');
      const path = absoluteReal(args[1]), expectedDigest = shaReal(args[3]);
      const prepared = readPreownerSmokeInputs(path, expectedDigest, clocks); attempt.selected = prepared;
      const choice = prepared.selection; remaining();
      ownerCheck(absentReal(choice.outputDirectory), 'preowner_output_already_exists');
      outputIdentity = makeFreshRealDirectory(choice.outputDirectory); outputRoot = choice.outputDirectory;
      for (const name of ['owner-environment-home', 'owner-environment-tmp']) {
        const path = join(outputRoot, name), identity = makeFreshRealDirectory(path); environmentDirectories.push({ path, identity });
        clocks.checkpoint(); remaining();
      }
      const sentinel = writeOwned('unread-owner-selection.json', Buffer.from(PREOWNER_SENTINEL_TEXT, 'utf8'));
      writeJson('admission.json', { schema: 'ktchecker-owner-preowner-smoke-admission/1', selection: prepared.selectionEvidence,
        buildRecord: choice.buildRecord, approvedBuildData: prepared.build, observedInputCount: prepared.observed.length,
        observedInputDigest: digest(canonical(prepared.observed)), sourceLinkage: 'independently-root-pinned-build-record-not-loaded-memory-attestation',
        originalOverallMillis: 900_000, originalExportMillis: 120_000, modelOrProviderRequested: false,
        originalFirst120StartedBeforeInputIo: true, preownerSuccessRequiresNoExportReply: true });
      const argsJvm = preownerJvmArguments(choice, sentinel);
      const environment = Object.freeze({ PATH: choice.javaHome + '/bin:/usr/bin:/bin', JAVA_HOME: choice.javaHome,
        HOME: join(outputRoot, 'owner-environment-home'), TMPDIR: join(outputRoot, 'owner-environment-tmp'),
        LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' });
      writeJson('invocation.json', { executable: choice.jvmExecutable, arguments: argsJvm, cwd: outputRoot,
        environment, shell: false, stdio: ['pipe', 'pipe', 'pipe'], maxJvmStarts: 1, expectedExit: 78,
        inputWhitelist: ['request.owner.open.id1', 'clock.reply.overall0', 'deny'], exportRepliesForwarded: 0,
        stdoutBoundBytes: 8_192, stderrBoundBytes: 65_536, rawWriteBoundBytes: 2_048, maxRawWrites: 3,
        observationOnly: true, signalOrPipeCloseOnTimeout: false, retry: false });
      recheckPreownerSmokeInputs(prepared, () => { clocks.checkpoint(); remaining(); }); checkOutputRoot();
      let resolveClose!: () => void;
      const closed = new Promise<void>(resolve => { resolveClose = resolve; });
      expired = new Promise<never>((_resolve, reject) => {
        deadlineTimer = setTimeout(() => reject(fail(new HostError('current_owner_preowner_original_observation_unknown'))), remaining());
      });
      void expired.catch(() => {});
      clocks.checkpoint(); remaining();
      const child = spawn(choice.jvmExecutable.path, [...argsJvm], { cwd: outputRoot, env: environment,
        shell: false, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
      attempt.child = child; // FIRST after actual return, BEFORE attachment, callbacks or any await.
      // Own fresh child AND all raw pipes independently of the closed shim. No historical handle,
      // pid lookup, process-tree probe, signal, stdin end/destroy, retry or inferred quiescence.
      child.on('error', () => { attempt.childErrors++; fail(new HostError('current_owner_preowner_spawn_unknown')); });
      child.on('exit', (code, signal) => {
        if (attempt.exit !== null) { fail(new HostError('current_owner_preowner_duplicate_exit')); return; }
        attempt.exit = Object.freeze({ code, signal });
        channel.poison(new HostError('current_owner_preowner_natural_exit_observed')); // Expected DENIAL, never channel success.
      });
      child.on('close', (code, signal) => {
        if (attempt.close !== null) { fail(new HostError('current_owner_preowner_duplicate_close')); return; }
        attempt.close = Object.freeze({ code, signal }); resolveClose();
      });
      child.stdin.on('error', () => { attempt.inputErrors++; fail(new HostError('current_owner_preowner_raw_input_error')); });
      child.stdout.on('error', () => { attempt.outputErrors++; fail(new HostError('current_owner_preowner_raw_output_error')); });
      child.stderr.on('error', () => { attempt.stderrErrors++; fail(new HostError('current_owner_preowner_raw_stderr_error')); });
      child.stdin.on('close', () => { attempt.inputClosed = true; });
      child.stdout.on('close', () => { attempt.outputClosed = true; });
      child.stderr.on('close', () => { attempt.stderrClosed = true; });
      const proof = new PreownerOnlyWireProof(child.stdin, overall); attempt.proof = proof;
      proof.input.on('error', () => { attempt.shimErrors++; fail(new HostError('current_owner_preowner_shim_error')); });
      // The proof observer is registered FIRST; the actual channel then sees these same bytes.
      child.stdout.on('data', (bytes: Buffer) => { try { proof.observeStdout(bytes); } catch (error) { fail(error); } });
      child.stderr.on('data', (bytes: Buffer) => { try { proof.observeStderr(bytes); } catch (error) { fail(error); } });
      channel.attachPipes(proof.input, child.stdout, child.stderr);
      await Promise.race([channel.awaitHello(), expired]);
      const opening = channel.request('owner.open', {}); attempt.opening = opening;
      const rejected = opening.then(actual => {
        attempt.openedReturn = actual; // Retain an unexpected actual return BEFORE any failure check.
        fail(new HostError('current_owner_preowner_open_must_not_return'));
      }, () => { attempt.openRejected = true; });
      await Promise.race([Promise.all([closed, rejected]), expired]); remaining();
      ownerCheck(attempt.failure === null && attempt.exit?.code === 78 && attempt.exit.signal === null &&
        attempt.close?.code === 78 && attempt.close.signal === null && attempt.inputClosed && attempt.outputClosed && attempt.stderrClosed &&
        attempt.childErrors + attempt.inputErrors + attempt.outputErrors + attempt.stderrErrors + attempt.shimErrors === 0 &&
        attempt.openRejected && attempt.openedReturn === null, 'preowner_natural_exit_and_close_unproven');
      proof.assertComplete();
      // The channel is EXPECTED to remain poisoned, with no actual response or owner adoption.
      const transport = channel.evidence();
      ownerCheck(transport.requests === 1 && transport.retainedOriginalReturn === false && typeof transport.failure === 'string', 'preowner_channel_scope');
      recheckPreownerSmokeInputs(prepared, () => { publicationCheckpoint(); }); checkOutputRoot(); publicationCheckpoint();
      observed = true; // Framing observation only; no evidence/report-delivery success yet.
    } catch (error) { fail(error); }

    let report: RealFileEvidence | null = null;
    try {
      if (outputRoot !== null) {
        writeJson('wire-proof.json', { proof: attempt.proof?.evidence() ?? null, channel: channel.evidence(),
          exit: attempt.exit, close: attempt.close, rawStdioClosed: { input: attempt.inputClosed, output: attempt.outputClosed, stderr: attempt.stderrClosed },
          errors: { child: attempt.childErrors, input: attempt.inputErrors, output: attempt.outputErrors, stderr: attempt.stderrErrors, shim: attempt.shimErrors },
          requestRejected: attempt.openRejected, retainedUnexpectedOpenReturn: attempt.openedReturn !== null });
        runtimeBoundaryBeforeReport = measureOutputBoundary();
        report = writeJson('result.json', { schema: 'ktchecker-owner-preowner-smoke-result/1',
          state: observed && attempt.failure === null ? 'observed_preowner_framing_pending_final_delivery' : 'failed_or_incomplete',
          failure: attempt.failure ? safeRealFailure(attempt.failure) : null, scope: 'actual-Main-framing-original-clock-early-denial-only',
          directChildNaturalExit78AndClose: observed && attempt.failure === null, independentlyReviewedSourceGateRequired: true,
          noExportReplyForwarded: attempt.proof?.evidence().exportRepliesForwarded === 0,
          originalOverallEnteredAt: overall.enteredAt, elapsedMillis: overall.elapsedMs(), runtimeBoundaryBeforeReport,
          ownedFiles: [...files], retainedRuntimeDirectories: [...environmentDirectories],
          maxJvmStarts: 1, ownerSignalsSent: 0, retries: 0, cleanupPerformed: false, ownerQuiescenceClaim: false,
          ownerInitializationValidated: false, factoryOrRegistrationValidated: false, compilerOrProviderValidated: false,
          modelOrHostAcceptanceValidated: false, loadedMemoryAttestation: false, releaseQualified: false,
          finalOnlyWithExit0AndPinnedReportReadback: true,
          outputManifestBoundary: 'listed files exclude result itself; fresh runtime home/tmp retained; post-result boundary and result digest delivered separately on stdout' });
        // This SECOND boundary includes wire-proof.json AND result.json. It is an observed
        // boundary only, not a continuous quota or a claim about unlisted external JVM effects.
        finalRuntimeBoundary = measureOutputBoundary();
      }
    } catch (error) { fail(error); } // An immutable observation-shaped file is NOT final delivery proof.
    try {
      const candidate = observed && attempt.failure === null && report !== null && finalRuntimeBoundary !== null;
      if (candidate) publicationCheckpoint();
      const summary = Buffer.from(JSON.stringify({ state: candidate ? 'verified_preowner_framing_only' : 'failed_or_incomplete',
        failure: attempt.failure ? safeRealFailure(attempt.failure) : null, outputWritten: report !== null,
        report: report?.path ?? null, reportDigest: report?.digest ?? null, finalRuntimeBoundary,
        requiresExit0AndPinnedReportReadback: true, ownerOrModelAcceptance: false }) + '\n', 'utf8');
      ownerCheck(summary.length <= 32_768, 'preowner_stdout_report_bound');
      if (candidate) publicationCheckpoint();
      let callbackObserved = false;
      const delivered = new Promise<void>((resolve, reject) => {
        process.stdout.write(summary, error => {
          try {
            ownerCheck(!callbackObserved, 'preowner_report_callback_reentered'); callbackObserved = true;
            if (error) throw new HostError('current_owner_preowner_report_delivery_unknown');
            if (candidate) publicationCheckpoint(); // Late callback cannot promote an earlier file/line.
            resolve();
          } catch (error) { reject(fail(error)); }
        });
      });
      if (candidate) {
        ownerCheck(expired !== null, 'preowner_original_timer_missing');
        await Promise.race([delivered, expired]);
        publicationCheckpoint(); ownerCheck(callbackObserved, 'preowner_report_delivery_unobserved');
        process.exitCode = 0; // Only now; never process.exit or owner/pipe termination.
      } else { await delivered; } // Failure-only diagnostics may be delivered after expiry.
    } catch (error) { fail(error); } // No second stdout attempt, overwrite, refresh or cleanup.
  } finally { if (deadlineTimer !== null) clearTimeout(deadlineTimer); } // Timer only, AFTER final publication checks.
}

void main().catch(() => {
  // Initialization/reporting may itself be unavailable. Retained attempts are not signalled or
  // disposed. No automatic rerun, process.exit or success-shaped fallback is permitted.
  process.exitCode = 1;
});
