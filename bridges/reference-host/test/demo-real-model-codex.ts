#!/usr/bin/env node
/** Sole explicit NEW no-test live model/compiler/Workbench/Codex acceptance entry point.
 * NOT an npm test; no supervisor, timeout command, retry, replay, signal, deletion or auto-close.
 * An unavailable/unknown owner remains retained with its input/stdio open for owner reconciliation.
 */
import { write } from 'node:fs';
import { dirname, join } from 'node:path';
import type { JsonObject } from '@ktchecker/mcp-preview/rpc';
import { ReferenceHost } from '../src/adapter.js';
import { KernelBroker } from '../src/broker.js';
import { CodexHostPort } from '../src/codex-host.js';
import { canonical, digest, InboxJournal } from '../src/journal.js';
import { captureApplicationEvidence } from './application-evidence.js';
import { deliveryBinding, verifyDeliveryReceiptJoin } from './delivery-receipts.js';
import { captureRealCompanionImage, loadRealCodexSelection, preflightRealCodex, verifyRealCompanionImage,
  REAL_CODEX_EFFORT, REAL_CODEX_MODEL } from './real-model-codex-selection.js';
import { acceptedRealPhase, completeRealPhase, fullRealRules, publicRealValue, realOwnerHello,
  RealObservedHost, RealReceiptBroker, validateRealDelivery, type RealPhase } from './real-model-delivery.js';
import { absentReal, jsonReal, loadRealSelection, makeFreshRealDirectory, objectReal, parseRealModelArgs,
  preflightRealOwner, readRealFile, RealModelClock, realRunBoundary, requireReal, RetainedRealModelOwner, safeRealFailure,
  sameReal, usableRealBytes, writeNewRealJson, REAL_MODEL_CASE, REAL_MODEL_CONTEXT_BYTES,
  REAL_MODEL_OVERALL_MS, REAL_MODEL_PUBLIC_BYTES, REAL_MODEL_REVIEW_MS, REAL_MODEL_RPC_BYTES, type RealFileEvidence } from './real-model-run-support.js';

const clock = new RealModelClock(); // BEFORE selection, preflight, evidence, fixture or execution-home IO.
let owner: RetainedRealModelOwner | undefined;
let host: CodexHostPort | undefined;
let journal: InboxJournal | undefined;
let evidenceDirectory: string | undefined;
let stage = 'selection';
let retirementAndOwnerExitVerified = false;
let hostExplicitlyClosedAfterOwnerExit = false;
const summaries: JsonObject[] = [];

async function publish(value: JsonObject): Promise<void> {
  const bytes = Buffer.from(canonical(value) + '\n', 'utf8');
  requireReal(bytes.length <= 4096, 'real_model_terminal_summary_bound');
  for (let offset = 0; offset < bytes.length;) {
    const amount = await new Promise<number>((resolve, reject) => write(1, bytes, offset, bytes.length - offset, null,
      (error, count) => error ? reject(error) : resolve(count)));
    requireReal(amount > 0, 'real_model_terminal_output_stalled'); offset += amount;
  }
}
function retainedOwnerReceipt(root: string, name: string, limit = REAL_MODEL_RPC_BYTES): { value: JsonObject; file: RealFileEvidence } {
  const read = readRealFile(join(root, 'owner/evidence', name), limit);
  return { value: objectReal(jsonReal(read.bytes)), file: read.evidence };
}
function assertActualParentReflection(proof: JsonObject, phase: RealPhase, expectedContextDigests: string[]): void {
  requireReal(proof.model === REAL_CODEX_MODEL && proof.effort === REAL_CODEX_EFFORT && proof.status === 'verified' &&
    proof.internalInferenceRequestTrace === false && typeof proof.serializedApplicationRequest === 'string', 'real_model_actual_application_unverified');
  sameReal([...(proof.contextDigests as string[])].sort(), expectedContextDigests.sort(), 'real_model_application_context_domain_changed');
  const wire = objectReal(JSON.parse(proof.serializedApplicationRequest)), params = objectReal(wire.params);
  requireReal(params.effort === REAL_CODEX_EFFORT, 'real_model_actual_request_effort_changed');
  const output = objectReal(JSON.parse(objectReal(params.toolOutput).output as string));
  requireReal(Array.isArray(output.contexts), 'real_model_actual_request_contexts_missing');
  const envelopes = output.contexts.map(raw => {
    const context = objectReal(raw); requireReal(typeof context.text === 'string', 'real_model_context_text_missing');
    return objectReal(JSON.parse(context.text.slice(context.text.indexOf('\n') + 1)));
  });
  const parent = envelopes.filter(envelope => envelope.kind === 'plan_accepted');
  requireReal(parent.length === 1, 'real_model_parent_not_in_actual_request');
  sameReal(objectReal(JSON.parse(parent[0]!.diagnosticPayload as string)).parentPlan, phase.accepted,
    'real_model_actual_request_parent_was_narrowed');
  const response = objectReal(proof.verifiedPublicResponse);
  requireReal(Array.isArray(response.observations), 'real_model_public_observations_missing');
  const reflected = response.observations.map(objectReal).filter(item => item.eventId === parent[0]!.eventId);
  requireReal(reflected.length === 1, 'real_model_parent_not_in_public_response');
  sameReal(reflected[0]!.parentPlan, phase.accepted, 'real_model_public_parent_was_narrowed');
  fullRealRules(objectReal((objectReal(reflected[0]!.parentPlan).slots as unknown[])[0]).requiredRuleIds);
}

try {
  const args = parseRealModelArgs(process.argv.slice(2));
  const selected = loadRealSelection(args.selectionPath, args.selectionDigest), codex = loadRealCodexSelection(args.codexPath, args.codexDigest);
  const selection = selected.selection;
  requireReal([args.selectionPath, args.codexPath, codex.selection.executable, codex.selection.schemaManifest].every(path =>
    path !== selection.runRoot && !path.startsWith(selection.runRoot + '/')), 'real_model_external_input_inside_run');
  const actualInputs = preflightRealOwner(selection), codexImage = preflightRealCodex(codex.selection), companionImage = captureRealCompanionImage();
  clock.checkpoint();
  requireReal(usableRealBytes(dirname(selection.runRoot)) >= 1536 * 1024 * 1024, 'real_model_disk_headroom_unavailable');
  requireReal(absentReal(selection.runRoot), 'real_model_run_attempt_not_fresh');
  makeFreshRealDirectory(selection.runRoot);
  evidenceDirectory = join(selection.runRoot, 'companion-evidence'); makeFreshRealDirectory(evidenceDirectory);
  const cwd = join(selection.runRoot, 'public-codex-workspace'); makeFreshRealDirectory(cwd);
  writeNewRealJson(join(evidenceDirectory, 'started.json'), {
    state: 'new_explicit_no_test_feasibility_started', fixture: REAL_MODEL_CASE,
    enteredAt: clock.enteredAt, enteredNanos: clock.enteredNanos.toString(), originalDeadlineNanos: clock.originalDeadlineNanos.toString(),
    originalOverallMillis: REAL_MODEL_OVERALL_MS, originalGenerationMillis: REAL_MODEL_OVERALL_MS, originalReviewMillis: REAL_MODEL_REVIEW_MS,
    contextBytes: REAL_MODEL_CONTEXT_BYTES, rpcBytes: REAL_MODEL_RPC_BYTES, publicBytes: REAL_MODEL_PUBLIC_BYTES,
    ownerSelection: selected.evidence, codexSelection: codex.evidence, codexImage, companionImage,
    ownerJava: actualInputs.ownerJava, modelWorkerBridge: actualInputs.bridge, sameUidHostileBoundaryClaimed: false,
    oldThreadsReadOrReused: false, qualified: false, usefulClean: false, cpuMillis: null, peakRssBytes: null,
  });
  stage = 'new_owner_initialization';
  owner = new RetainedRealModelOwner(selection, args.selectionPath, args.selectionDigest, clock, selection.runRoot);
  const hello = realOwnerHello(await clock.wait(() => owner!.request('owner.hello', {}), REAL_MODEL_REVIEW_MS), args.selectionDigest);
  const ready = retainedOwnerReceipt(selection.runRoot, 'owner-ready.json', 64 * 1024);
  requireReal(ready.value.state === 'owner_ready' && ready.value.compilerRosterCount === 14 && ready.value.sameSelectedInstallationObject === true &&
    ready.value.qualified === false, 'real_model_real14_owner_not_ready');
  const forbidden = [...actualInputs.privateRoots, args.selectionPath, args.codexPath, codex.selection.executable,
    codex.selection.schemaManifest, hello.registration.client.token, hello.registration.adapter.token];
  writeNewRealJson(join(evidenceDirectory, 'owner-handshake.json'), {
    state: 'actual_owner_and_live_generation_ready', generation: hello.generation, ownerReadyFile: ready.file,
    fullRequiredRuleIds: hello.rules, binding: deliveryBinding(hello.registration.binding), elapsedMs: clock.elapsedMs(), qualified: false,
  });
  // Exactly one fresh app-server/controlled thread. No version probe, resolver, list, resume or delete.
  stage = 'new_codex_thread';
  const codexDirectory = join(selection.runRoot, 'codex-controller'), inboxDirectory = join(selection.runRoot, 'inbox-controller');
  requireReal(absentReal(codexDirectory) && absentReal(inboxDirectory), 'real_model_host_store_not_fresh');
  sameReal(preflightRealCodex(codex.selection), codexImage, 'real_model_codex_prelaunch_image_changed');
  host = await clock.wait(() => CodexHostPort.open({ directory: codexDirectory, client: hello.registration.binding, cwd,
    effort: REAL_CODEX_EFFORT, server: { executable: codex.selection.executable, args: ['app-server', '--stdio'],
      timeoutMs: 15_000, maxLineBytes: REAL_MODEL_RPC_BYTES, maxPublicBytes: REAL_MODEL_PUBLIC_BYTES, failOnModelReroute: true } }), 60_000);
  requireReal(!host.resumed && host.evidence().length === 0, 'real_model_old_thread_or_invocation_reused');
  journal = new InboxJournal(inboxDirectory, host.binding, { maxEvents: 64, maxStateBytes: REAL_MODEL_RPC_BYTES });
  const rpc = new RealReceiptBroker(owner, hello.registration), observedHost = new RealObservedHost(host, clock, forbidden);
  const adapter = new ReferenceHost(journal, new KernelBroker(rpc, hello.registration), observedHost, { maxContextBytes: REAL_MODEL_CONTEXT_BYTES });
  const phases: RealPhase[] = [];
  const materializations: JsonObject[] = [];
  for (const number of [1, 2]) {
    stage = 'phase_' + number + '_genuine_export_import_and_submission';
    requireReal(Object.values(journal.read().inbox).every(entry => !entry.event.deliveryRequired || entry.observed), 'real_model_previous_delivery_unsettled');
    const enteredMs = clock.elapsedMs();
    writeNewRealJson(join(evidenceDirectory, 'phase-' + number + '-begin-intent.json'), {
      phase: number, enteredMs, originalOverallDeadlineNanos: clock.originalDeadlineNanos.toString(),
      operation: 'owner.phase.begin', ownerRetriesPermitted: false,
    });
    const accepted = await clock.wait(() => owner!.request('owner.phase.begin', { phase: number }), REAL_MODEL_REVIEW_MS);
    const phase = acceptedRealPhase(accepted, number, hello.registration, hello.rules, enteredMs, clock.elapsedMs());
    if (number === 2) requireReal(phase.snapshotId !== phases[0]!.snapshotId && phase.reviewId !== phases[0]!.reviewId,
      'real_model_fixed_phase_not_a_new_capture');
    phases.push(phase);
    // Trusted owner-returned capture identity only. No synthesized scalar submission or RPC replay.
    journal.edit(state => { state.currentSnapshotId = phase.snapshotId; state.localEpoch++; state.cut = null; });
    stage = 'phase_' + number + '_whole_required_completion';
    for (let poll = 0; ; poll++) {
      requireReal(poll < 1500 && clock.elapsedMs() - enteredMs < REAL_MODEL_REVIEW_MS, 'real_model_original_review_observation_deadline');
      const status = await clock.wait(() => owner!.request('owner.phase.status', { phase: number }), 30_000);
      if (completeRealPhase(status, phase, hello.registration, hello.rules, clock.elapsedMs())) break;
      await clock.wait(() => new Promise<void>(resolve => setTimeout(resolve, 200)), 1000);
    }
    const materialized = retainedOwnerReceipt(selection.runRoot, 'phase-' + number + '-materialization-complete.json');
    requireReal(materialized.value.phase === number && materialized.value.generationId === hello.generation.generationId &&
      materialized.value.actualHelloDigest === objectReal(hello.generation.actualHello).digest &&
      materialized.value.actualLoaderToken === objectReal(hello.generation.actualHello).loaderToken &&
      materialized.value.wholeParentRequiredRules === 1663 && materialized.value.retainedRawArgvUnchanged === true &&
      materialized.value.originalDeadlineMillis === REAL_MODEL_REVIEW_MS && materialized.value.qualified === false,
      'real_model_same_generation_materialization_unconfirmed');
    materializations.push(materialized.value);
    for (let page = 0; ; page++) {
      requireReal(page < 16, 'real_model_event_page_bound');
      const more = await clock.wait(() => adapter.pollOnce(), 30_000); if (!more) break;
    }
    const pending = validateRealDelivery(journal.read(), phase, forbidden);
    observedHost.prepare(phase, pending);
    // Actual raw event payloads, including EVERY original coverage row, stay private and unfiltered.
    writeNewRealJson(join(evidenceDirectory, 'phase-' + number + '-raw-required-events.json'), pending.map(entry => entry.event));
    stage = 'phase_' + number + '_actual_insertion_and_ack';
    await clock.wait(() => adapter.drainContext(), 60_000);
    const inserted = pending.map(entry => journal!.read().inbox[entry.event.eventId]!);
    requireReal(inserted.every(entry => entry.item !== null && entry.inserted !== null && entry.acknowledged && !entry.observed),
      'real_model_insertion_is_not_observation');
    const insertionMs = clock.elapsedMs();
    writeNewRealJson(join(evidenceDirectory, 'phase-' + number + '-insertion-ack-before-request.json'), {
      phase: number, independentlyObservedMs: insertionMs, items: inserted.map(entry => ({ eventId: entry.event.eventId,
        context: entry.item, insertion: entry.inserted, acknowledged: entry.acknowledged, observed: entry.observed })),
      capturedReceiptConfirmations: rpc.receipts.snapshot(),
    });
    stage = 'phase_' + number + '_actual_subsequent_astra_max_request';
    const beforeInvocationMs = clock.elapsedMs();
    const observed = await clock.wait(() => adapter.requestModelObservation(), 150_000);
    requireReal(observed.observed && observed.reason === null, 'real_model_content_observation_unavailable');
    const afterObservationMs = clock.elapsedMs();
    requireReal(host.evidence().length === number, 'real_model_extra_application_invocation');
    const application = await clock.wait(() => captureApplicationEvidence(host!, journal!), 30_000);
    host.server.assertNoModelReroute();
    const proof = { ...application, consumer: hello.registration.binding.clientId, deliveryBinding: deliveryBinding(hello.registration.binding) };
    publicRealValue(proof, forbidden);
    assertActualParentReflection(proof, phase, inserted.map(entry => entry.item!.digest));
    const receipts = rpc.receipts.snapshot();
    const joinProof = verifyDeliveryReceiptJoin(receipts, proof, hello.registration.binding,
      { maxApplicationBytes: REAL_MODEL_RPC_BYTES, maxContextBytes: REAL_MODEL_CONTEXT_BYTES, maxPublicBytes: REAL_MODEL_PUBLIC_BYTES });
    requireReal(inserted.every(entry => journal!.read().inbox[entry.event.eventId]!.observed), 'real_model_actual_observation_rpc_unconfirmed');
    const phaseEvidence = writeNewRealJson(join(evidenceDirectory, 'phase-' + number + '-actual-application-and-delivery.json'), {
      phase: number, proof, receipts, joinProof, parent: phase.accepted, ownerMaterializationFile: materialized.file,
      timing: { ownerBeginRequestMs: enteredMs, acceptanceReturnMs: phase.acceptanceReturnedMs, requiredCompletionMs: phase.completionObservedMs,
        allInsertionAndAckMs: insertionMs, beforeModelObservationMs: beforeInvocationMs, observedReturnMs: afterObservationMs,
        operations: observedHost.timings.filter(value => value.phase === number), cpuMillis: null, peakRssBytes: null,
        usefulClean: false, speedTargetClaimed: false },
      qualification: { qualified: false, fullProjectProductAcceptance: false, typicalMainTestSupported: false,
        originalCoverageRowsObservedByModel: false, allOriginalCoverageRowsRetainedPrivately: true,
        fullParentVectorAndAllFindingContentInActualApplicationRequest: true, internalInferenceTrace: false,
        requestedEffortAndThreadConfigConfirmed: 'max', internalProviderEffortAttested: false },
    });
    verifyRealCompanionImage(companionImage); sameReal(preflightRealCodex(codex.selection), codexImage, 'real_model_codex_image_changed');
    realRunBoundary(selection.runRoot);
    summaries.push({ phase: number, sourceSnapshotId: phase.snapshotId, requiredRules: 1663,
      requiredEvents: pending.length, model: REAL_CODEX_MODEL, effort: REAL_CODEX_EFFORT, evidenceDigest: phaseEvidence.digest,
      qualified: false, usefulClean: false });
  }
  requireReal(materializations[0]!.originalDeadlineIdentity !== materializations[1]!.originalDeadlineIdentity &&
    materializations[0]!.bindingDigest !== materializations[1]!.bindingDigest &&
    materializations[0]!.requestDigestReconstructedAsData !== materializations[1]!.requestDigestReconstructedAsData,
    'real_model_distinct_original_phase_binding_missing');
  stage = 'unqualified_final_gate';
  const cut = await clock.wait(() => adapter.prepareFinal(), 30_000), decision = await clock.wait(() => adapter.completeFinal(cut.cutId), 30_000);
  requireReal(!decision.allowed && !decision.actionPerformed && decision.reasons.length > 0, 'real_model_unqualified_gate_must_stay_closed');
  writeNewRealJson(join(evidenceDirectory, 'unqualified-final-gate.json'), { cut, decision, qualified: false, nativeActionInvoked: false });
  stage = 'explicit_compiler_retirement_before_owner_eof';
  host.server.assertNoModelReroute();
  const retired = await clock.wait(() => owner!.request('owner.retire', {}), 30_000);
  const order = await owner.endAfterVerifiedRetirement(retired, hello.generation, evidenceDirectory);
  retirementAndOwnerExitVerified = true;
  // The SOLE explicit outer host.close path. Codex's own child lifecycle is not compiler retirement.
  stage = 'close_new_codex_child_after_verified_owner_exit';
  await clock.wait(() => host!.close(), 10_000); hostExplicitlyClosedAfterOwnerExit = true;
  host.server.assertNoModelReroute();
  journal.close();
  verifyRealCompanionImage(companionImage); sameReal(preflightRealCodex(codex.selection), codexImage, 'real_model_codex_final_image_changed');
  const boundary = realRunBoundary(selection.runRoot);
  const completed = writeNewRealJson(join(evidenceDirectory, 'verified-no-test-feasibility.json'), {
    state: 'verified_genuine_no_test_feasibility_not_release_qualification', fixture: REAL_MODEL_CASE,
    elapsedMs: clock.elapsedMs(), phases: summaries, owner: owner.evidence(), retirementOrder: order,
    hostExplicitlyClosedAfterOwnerExit, threadDeletionAttempted: false, oldSessionLookupAttempted: false, retainedNewThread: true,
    compilerGenerationCount: 1, genuineExports: 2, genuineImports: 2, compilerAttempts: 2, actualModelInvocationCount: 2,
    sourceSelectionDigest: args.selectionDigest, codexSelectionDigest: args.codexDigest, boundary,
    qualified: false, usefulClean: false, fullProjectProductAcceptance: false, cpuMillis: null, peakRssBytes: null,
  });
  stage = 'verified';
  await clock.wait(() => publish({ state: stage, fixture: REAL_MODEL_CASE, phases: 2, rulesPerParent: 1663,
    model: REAL_CODEX_MODEL, effort: REAL_CODEX_EFFORT, explicitCompilerRetirementAndNormalOwnerExitVerified: true,
    evidenceDigest: completed.digest, qualified: false, usefulClean: false }), 1000);
} catch (error) {
  const reason = safeRealFailure(error);
  let failureDigest: string | null = null;
  try {
    if (evidenceDirectory) failureDigest = writeNewRealJson(join(evidenceDirectory, 'unavailable-retained.json'), {
      state: 'unavailable_retained', fixture: REAL_MODEL_CASE, stage, reason, elapsedMs: clock.elapsedMs(),
      phases: summaries, owner: owner?.evidence() ?? null, retirementAndOwnerExitVerified, hostExplicitlyClosedAfterOwnerExit,
      retryPermitted: false, ownerOrCompilerSignalsSent: 0, automaticOwnerCleanup: false,
      codexOwnChildLifecycleMayHaveRun: true, threadDeletionAttempted: false, qualified: false, usefulClean: false,
    }).digest;
  } catch { /* Retain failed evidence too; never overwrite, delete, rerun or infer completion. */ }
  // Best effort terminal status only. It does not release/restart/kill an uncertain owner.
  void publish({ state: 'unavailable_retained', fixture: REAL_MODEL_CASE, stage, reason, failureDigest,
    ownerRetained: owner !== undefined && !retirementAndOwnerExitVerified, retryPermitted: false, qualified: false }).catch(() => {});
  process.exitCode = 1;
  // No process.exit: its implicit pipe closure would silently turn timeout into owner EOF.
  if (owner !== undefined && !retirementAndOwnerExitVerified || host !== undefined && !hostExplicitlyClosedAfterOwnerExit) {
    setInterval(() => {}, 60_000); // Park, do not refresh an execution/model/review clock or schedule work.
  }
}
