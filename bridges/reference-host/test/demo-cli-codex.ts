#!/usr/bin/env node
/** Explicit opt-in integration: REAL CLI/kernel/compiler and REAL read-only Codex,
 * with only disposable public synthetic Kotlin SOURCE. Not run by npm test.
 */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { BrokerError } from '@ktchecker/mcp-preview/rpc';
import { ReferenceHost } from '../src/adapter.js';
import { KernelBroker } from '../src/broker.js';
import { CodexHostPort } from '../src/codex-host.js';
import { CodexAppServer } from '../src/codex-app-server.js';
import { canonical, digest, HostError, InboxJournal } from '../src/journal.js';
import type { InboxEntry, Registration } from '../src/types.js';
import { ACTUAL_DEMO_USAGE, ActualCliFixture, ActualFaultBroker, BROKEN_SOURCE, COMPILER_RULE, FIXTURE_PATH, REPAIRED_SOURCE,
  actualDemoCleanupEvidence, parseActualDemoArgs, reserveActualDemoEvidence } from './actual-cli-fixture.js';
import { captureApplicationEvidence } from './application-evidence.js';
import { captureRunIdentity } from './run-identity.js';
import { captureDeliveryTelemetry, permanentDeliveryProblem, safeDeliveryProblem } from './delivery-telemetry.js';
import { OwnedDemoThreads } from './owned-demo-threads.js';

const selection = (() => {
  try { return parseActualDemoArgs(process.argv.slice(2)); }
  catch {
    process.stderr.write('Explicit selections and approval required: ' + ACTUAL_DEMO_USAGE + '\n');
    process.exit(2);
  }
})();
// Refuse a reused evidence pathname BEFORE creating runtime state or starting any subprocess.
const evidence = await reserveActualDemoEvidence(selection.evidence).catch(error => {
  process.stderr.write((error instanceof HostError ? error.code : 'actual_demo_evidence_reservation_failed') + '\n');
  process.exit(2);
});
const outputPath = evidence.path;
let directory: string;
try { directory = await mkdtemp(join(tmpdir(), 'ktchecker-public-actual-delivery-')); }
catch {
  try { await evidence.write(JSON.stringify({ verified: false, failure: 'actual_demo_temp_directory_failed' }) + '\n'); }
  finally { await evidence.close(); }
  process.stderr.write('actual_demo_temp_directory_failed\n');
  process.exit(1);
}
// Never put this private path into public evidence/model context. No automatic tree deletion
// is safe here: closing an RPC process is not a verified native-group shutdown receipt.
process.stderr.write('Run tree retained for owner-only manual reconciliation/cleanup; native quiescence is not observed: ' + directory + '\n');
const publicDirectory = join(directory, 'public-source');
const privateDirectory = join(directory, 'private-controller');
const fixture = new ActualCliFixture(selection.launcher, privateDirectory, selection.compilerJavaHome);
const rpc = new ActualFaultBroker(fixture);
const report: Record<string, unknown> = {
  schema: 'ktchecker-host-evidence/preview-1', date: new Date().toISOString(),
  evidenceScope: 'actual_cli_kernel_compiler_and_real_codex_application_requests_with_public_synthetic_source',
  contractVersion: 'ktchecker/preview-1', model: 'gpt-6-astra', allowProviderModelFallback: false,
  internalInferenceRequestTrace: false, actualKernelIntegration: false, actualKotlinCompilerRun: false,
  sourceAuthority: 'immutable_owner_overlay_not_physical_live_worktree',
  ...actualDemoCleanupEvidence(),
  fullProjectModel: false, releaseQualified: false, guardedActionPerformed: false, modelPollingCalls: 0,
  node: process.version, codex: null,
  compilerJdkSelection: 'explicit_owner_path_shared_by_every_owner_command_and_rpc_restart', compilerJdkAmbientFallback: false,
  compilerJdkValidationBoundary: 'actual_cli_runtime_verifier_not_this_argument_parser',
  sourceFixture: { path: FIXTURE_PATH, p1: BROKEN_SOURCE, p2: REPAIRED_SOURCE },
  ownerExplicitlyEnabledRule: COMPILER_RULE, candidateCoverageScope: 'all-catalog-candidates-for-declared-target',
  phases: [], negatives: {}, verified: false,
};
type Consumer = { registration: Registration; host: CodexHostPort; journal: InboxJournal; adapter: ReferenceHost;
  controller?: AbortController; running?: Promise<void> };
const consumers: Consumer[] = [];
const ownedThreads = new OwnedDemoThreads();
let releaseTool: () => void = () => {};
const waitUntil = async (predicate: () => boolean, timeout = 180_000) => {
  const deadline = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > deadline) throw new HostError('actual_delivery_deadline');
    await new Promise(resolve => setTimeout(resolve, 40));
  }
};
async function stopPolling(consumer: Consumer): Promise<void> {
  consumer.controller?.abort(); await consumer.running;
  delete consumer.running; delete consumer.controller;
}
function startPolling(consumer: Consumer): void {
  consumer.controller = new AbortController(); consumer.running = consumer.adapter.run(consumer.controller.signal);
  void consumer.running.catch(() => {});
}
async function closeConsumer(consumer: Consumer): Promise<void> {
  await stopPolling(consumer); await consumer.host.close(); consumer.journal.close();
}
function resultEntry(consumer: Consumer, patch: string): InboxEntry | undefined {
  return Object.values(consumer.journal.read().inbox).find(entry => entry.event.patchId === patch && entry.event.kind === 'result');
}
async function waitForResult(consumer: Consumer, patch: string): Promise<InboxEntry> {
  await waitUntil(() => {
    if (resultEntry(consumer, patch)?.acknowledged === true) return true;
    const problem = permanentDeliveryProblem(consumer.journal.read());
    if (problem) throw new HostError(problem); // Deterministic representation failure cannot improve by polling for 180s.
    return false;
  });
  return resultEntry(consumer, patch)!;
}
function assertPublicOnly(text: string): void {
  if (text.includes(privateDirectory) || text.includes(fixture.compilerJavaHome)) throw new HostError('private_path_in_public_diagnostic');
  for (const consumer of consumers) for (const credential of [consumer.registration.client, consumer.registration.adapter]) {
    if (text.includes(credential.token)) throw new HostError('credential_in_public_diagnostic');
  }
}
function compilerEvidence(entry: InboxEntry): Record<string, unknown> {
  // Future runs retain the exact public synthetic original, so the complete
  // event and coverage digests can be recomputed after disposable-store cleanup.
  // This does not retroactively add missing bytes to predecessor evidence.
  assertPublicOnly(entry.event.payload);
  assert.equal(digest(entry.event.payload), entry.event.payloadDigest);
  const payload = JSON.parse(entry.event.payload);
  const result = payload.result;
  assert(result && Array.isArray(result.coverage) && Array.isArray(result.findings));
  const row = result.coverage.find((value: { ruleId: string }) => value.ruleId === COMPILER_RULE);
  assert(row);
  return { reviewId: entry.event.reviewId, eventId: entry.event.eventId, sourceSnapshotId: entry.event.sourceSnapshotId,
    payloadDigest: entry.event.payloadDigest, originalPayloadBytes: Buffer.byteLength(entry.event.payload),
    originalPayload: entry.event.payload, originalPayloadDigestIndependentlyRecomputableOffline: true,
    projectedContextBytes: Buffer.byteLength(entry.item!.text), state: payload.state,
    findings: result.findings, rawDiagnostics: result.rawDiagnostics, selectedCompilerCoverage: row,
    requiredCoverageRows: result.coverage.filter((row: { required: boolean }) => row.required).length,
    allCoverageDigest: digest(canonical(result.coverage)), reasons: payload.reasons, resultReasons: result.reasons };
}
function recordCompilerCheckpoint(entry: InboxEntry): Record<string, unknown> {
  const evidence = compilerEvidence(entry);
  report.lastCompilerCheckpoint = { patchId: entry.event.patchId, payloadDigest: entry.event.payloadDigest,
    state: evidence.state, selectedCompilerCoverage: evidence.selectedCompilerCoverage,
    findingsCount: (evidence.findings as unknown[]).length, requiredCoverageRows: evidence.requiredCoverageRows,
    reasonCodes: (evidence.resultReasons as unknown[]).filter(value => typeof value === 'string' && /^[a-zA-Z0-9_.:-]{1,512}$/.test(value)).slice(0, 16) };
  assert.equal((evidence.selectedCompilerCoverage as { provenance: string }).provenance, 'executed');
  return evidence;
}
async function observe(consumer: Consumer, phase: string, entry: InboxEntry): Promise<void> {
  for (const entry of Object.values(consumer.journal.read().inbox)) if (entry.item) assertPublicOnly(entry.item.text);
  assert.equal((await consumer.adapter.requestModelObservation()).observed, true);
  const proof = await captureApplicationEvidence(consumer.host, consumer.journal);
  const value = { phase, consumer: consumer.registration.binding.clientId, ...proof, actualCompiler: compilerEvidence(entry) };
  assertPublicOnly(JSON.stringify(value)); (report.phases as unknown[]).push(value);
}
async function expectRejection(name: string, operation: () => Promise<unknown>, expected: string): Promise<void> {
  let observed: string | null = null;
  try { await operation(); } catch (error) { if (error instanceof BrokerError) observed = error.code; else throw error; }
  assert.equal(observed, expected); (report.negatives as Record<string, unknown>)[name] = observed;
}

try {
  report.stage = 'verify_codex_executable_version';
  report.codex = execFileSync('codex', ['--version'], { encoding: 'utf8' }).trim();
  report.stage = 'capture_actual_application_bytecode_identity';
  report.runIdentity = await captureRunIdentity(fixture.launcher);
  report.stage = 'owner_fixture_enrollment_and_preparation';
  await mkdir(publicDirectory, { mode: 0o700 }); await fixture.initialize();
  await writeFile(join(publicDirectory, FIXTURE_PATH), BROKEN_SOURCE);
  const registrations = [await fixture.register('actual-consumer-a-' + randomUUID()), await fixture.register('actual-consumer-b-' + randomUUID())];
  for (const registration of registrations) await fixture.enableCompilerRule(registration);
  const p1Snapshot = await fixture.overlay(registrations[0]!, 'p1', BROKEN_SOURCE);
  const p1Prepared = [await fixture.prepare(registrations[0]!, p1Snapshot, 'p1'), await fixture.prepare(registrations[1]!, p1Snapshot, 'p1')];
  report.p1SnapshotId = p1Snapshot.manifest.snapshotId;
  let toolEntered = false;
  const heldTool = new Promise<void>(resolve => { releaseTool = resolve; });
  for (let i = 0; i < registrations.length; i++) {
    const registration = registrations[i]!;
    ownedThreads.beginCreation();
    const host = await CodexHostPort.open({ directory: join(privateDirectory, 'codex-' + i), client: registration.binding, cwd: publicDirectory,
      ...(i === 0 ? { dynamicTools: [{ type: 'function', name: 'hold_for_background_review',
        description: 'Fixed no-op demonstration barrier. Wait for the external controller; never polls or executes code.',
        inputSchema: { type: 'object', properties: {}, required: [], additionalProperties: false } }], onModelRequestAccepted: () => releaseTool() } : {}) });
    ownedThreads.confirmCreation(host.binding.hostThreadId);
    let journal: InboxJournal;
    try { journal = new InboxJournal(join(privateDirectory, 'inbox-' + i), host.binding); }
    catch (error) { await host.close(); throw error; }
    const adapter = new ReferenceHost(journal, new KernelBroker(rpc, registration), host);
    consumers.push({ registration, host, journal, adapter });
  }
  const a = consumers[0]!, b = consumers[1]!;
  report.stage = 'active_turn_and_actual_submit_recovery';
  const firstAThread = a.host.binding.hostThreadId;
  assert.notEqual(firstAThread, b.host.binding.hostThreadId);
  a.host.server.handleTool('hold_for_background_review', async () => {
    toolEntered = true; await heldTool;
    return { success: true, contentItems: [{ type: 'inputText', text: 'External compiler review is queued. Consume the ktchecker output using the diagnostic JSON contract; no polling is needed.' }] };
  });
  const active = a.host.server.runTurn({ threadId: firstAThread, input: [{ type: 'text',
    text: 'Call hold_for_background_review exactly once. Wait in this active turn until it returns, then consume queued ktchecker data using the diagnostic JSON response contract. Do not use other tools.' }], effort: 'low' }, 240_000);
  void active.catch(() => {});
  await waitUntil(() => toolEntered, 60_000);
  await assert.rejects(a.adapter.recordCompletedPatch(fixture.submission(p1Prepared[0]!)), { code: 'demonstration_submit_response_dropped' });
  assert.equal(a.journal.read().submissions[p1Prepared[0]!.idempotencyKey]!.status, null);
  await a.adapter.recoverSubmissions();
  const recovered = a.journal.read().submissions[p1Prepared[0]!.idempotencyKey]!.status!;
  assert.equal(recovered.reviewId, rpc.acceptedBeforeInjectedLoss!.reviewId);
  report.actualKernelIntegration = true;
  startPolling(a);
  await b.adapter.recordCompletedPatch(fixture.submission(p1Prepared[1]!));
  report.stage = 'waiting_for_actual_p1_result';
  const p1a = await waitForResult(a, 'p1');
  report.stage = 'actual_p1_diagnosis_and_privilege_negatives';
  const p1aEvidence = recordCompilerCheckpoint(p1a);
  assert((p1aEvidence.findings as { ruleId: string }[]).some(finding => finding.ruleId === COMPILER_RULE));
  report.actualKotlinCompilerRun = true;
  assert.equal(p1a.observed, false);
  await expectRejection('ordinary_client_cannot_ack', () => rpc.request('review.ack', { credential: a.registration.client,
    eventIds: [p1a.event.eventId], receipt: p1a.inserted!.receipt }), 'unauthorized');
  await expectRejection('cross_client_cannot_ack', () => rpc.request('review.ack', { credential: b.registration.adapter,
    eventIds: [p1a.event.eventId], receipt: p1a.inserted!.receipt }), 'event_not_found');
  await expectRejection('cross_client_cursor_rejected', () => rpc.request('review.events', { credential: b.registration.client,
    cursor: a.journal.read().fetchCursor, limit: 10 }), 'invalid_cursor');
  const ackOnlyCut = await a.adapter.prepareFinal();
  const ackOnly = await a.adapter.completeFinal(ackOnlyCut.cutId);
  assert.equal(ackOnly.allowed, false); assert.equal(ackOnly.actionPerformed, false);
  (report.negatives as Record<string, unknown>).ackOnlyGate = ackOnly;
  report.stage = 'active_diagnostic_observation';
  await observe(a, 'actual_background_compiler_diagnosis_joined_active_codex_turn', p1a);
  const activeResult = await active;
  assert.equal(activeResult.status, 'completed'); assert.equal(activeResult.turnId, a.host.evidence().at(-1)!.turnId);
  report.activeTurnJoined = true;
  assert.equal(b.journal.read().fetchCursor, null); assert.equal(Object.keys(b.journal.read().inbox).length, 0);
  // A's observation cannot satisfy B. B now independently fetches/inserts, but is still unobserved.
  startPolling(b); const p1b = await waitForResult(b, 'p1');
  report.stage = 'independent_consumer_observation';
  assert.equal(p1b.observed, false); assert.equal(a.journal.read().inbox[p1a.event.eventId]!.observed, true);
  assert.notEqual(p1a.event.eventId, p1b.event.eventId);
  const aObservation = a.journal.read().inbox[p1a.event.eventId]!.observation!;
  await expectRejection('cross_client_cannot_observe', () => rpc.request('review.observe', { credential: b.registration.adapter,
    eventIds: [p1a.event.eventId], receipt: aObservation }), 'event_not_found');
  await expectRejection('ordinary_client_cannot_observe', () => rpc.request('review.observe', { credential: a.registration.client,
    eventIds: [p1a.event.eventId], receipt: aObservation }), 'unauthorized');
  await expectRejection('another_consumers_receipt_cannot_observe_own_event', () => rpc.request('review.observe', { credential: b.registration.adapter,
    eventIds: [p1b.event.eventId], receipt: aObservation }), 'observation_mismatch');
  await observe(b, 'independent_consumer_own_thread_and_own_diagnostic_observation', p1b);
  report.independentConsumers = { ownThreads: true, separateCursorsInboxesAckAndObservation: true,
    noObservationInheritedFromOtherConsumer: true, sharedImmutableSnapshot: p1Snapshot.manifest.snapshotId };
  await stopPolling(a); await stopPolling(b);
  const oldCursor = a.journal.read().fetchCursor;
  const oldObservedIds = Object.values(a.journal.read().inbox).filter(entry => entry.observed).map(entry => entry.event.eventId).sort();
  report.stage = 'owner_p2_repair_and_durable_submission';
  // Owner repairs only the disposable public source. Record P2 through the SAME trusted capture API.
  await writeFile(join(publicDirectory, FIXTURE_PATH), REPAIRED_SOURCE);
  const p2Snapshot = await fixture.overlay(a.registration, 'p2', REPAIRED_SOURCE, p1Snapshot);
  const p2Prepared = await fixture.prepare(a.registration, p2Snapshot, 'p2');
  await rpc.disconnect(0); // Queue P2 without an executing worker until its consumer is disconnected.
  await a.adapter.recordCompletedPatch(fixture.submission(p2Prepared));
  assert.equal(Object.keys(a.journal.read().submissions).length, 2);
  assert.equal(a.journal.read().currentSnapshotId, p2Snapshot.manifest.snapshotId);
  const p2Status = a.journal.read().submissions[p2Prepared.idempotencyKey]!.status!;
  assert.equal(p2Status.state, 'queued');
  // Close the actual adapter/app-server before ANY worker can execute queued P2. The next
  // explicit broker operation starts one worker while that consumer remains disconnected.
  // Control-plane polling
  // below is this owner harness, never a model review/status tool.
  await a.host.close(); a.journal.close();
  await rpc.disconnect(1);
  const broker = new KernelBroker(rpc, a.registration);
  report.stage = 'p2_actual_compiler_finishes_while_consumer_disconnected';
  let terminal = await broker.status(p2Status.reviewId);
  const deadline = Date.now() + 180_000;
  while (!terminal.result) {
    if (Date.now() > deadline) throw new HostError('actual_disconnected_review_deadline');
    await new Promise(resolve => setTimeout(resolve, 200)); terminal = await broker.status(p2Status.reviewId);
  }
  await rpc.disconnect(); // Actual broker subprocess restart too; persisted P2 result must replay.
  a.host = await CodexHostPort.open({ directory: join(privateDirectory, 'codex-0'), client: a.registration.binding, cwd: publicDirectory });
  assert.equal(a.host.resumed, true); assert.equal(a.host.binding.hostThreadId, firstAThread);
  a.journal = new InboxJournal(join(privateDirectory, 'inbox-0'), a.host.binding);
  assert.equal(a.journal.read().fetchCursor, oldCursor);
  assert.deepEqual(Object.values(a.journal.read().inbox).filter(entry => entry.observed).map(entry => entry.event.eventId).sort(), oldObservedIds);
  a.adapter = new ReferenceHost(a.journal, new KernelBroker(rpc, a.registration), a.host);
  report.stage = 'resumed_p2_diagnosis_observation';
  startPolling(a); const p2a = await waitForResult(a, 'p2');
  const p2Evidence = recordCompilerCheckpoint(p2a);
  assert.equal((p2Evidence.findings as { ruleId: string }[]).some(finding => finding.ruleId === COMPILER_RULE), false);
  assert(['resolved_compilation_context_incomplete', 'exact_rule_context_tuple_not_qualified'].includes(
    (p2Evidence.selectedCompilerCoverage as { reason: string }).reason));
  await observe(a, 'owner_repair_p2_rechecked_and_consumed_after_owned_thread_and_broker_resume', p2a);
  const finalCut = await a.adapter.prepareFinal(), finalDecision = await a.adapter.completeFinal(finalCut.cutId);
  assert.equal(finalDecision.allowed, false); assert.equal(finalDecision.actionPerformed, false);
  report.p2 = { snapshotId: p2Snapshot.manifest.snapshotId, submittedThroughSameDurablePatchPath: true,
    oldCompilerDiagnosisAbsentAfterActualRecheck: true, compilationNotFullProjectCertification: true,
    adapterAndAppServerDisconnectedBeforeResultFetch: true, brokerRestartedAfterResult: true,
    queuedWithZeroWorkersThenExecutedWhileConsumerDisconnected: true,
    ownedThreadResumed: true, cursorAndPriorObservationsPreserved: true, finalDecision };
  const replayCalls = rpc.calls.filter(call => call.method === 'review.submit' && call.idempotencyKey === p1Prepared[0]!.idempotencyKey);
  assert.equal(replayCalls.length, 2); assert.equal(replayCalls[0]!.requestDigest, replayCalls[1]!.requestDigest);
  report.transportRecovery = { fault: 'controller_dropped_actual_accepted_submit_response_then_closed_zero_worker_broker',
    acceptedBeforeInjectedLoss: rpc.acceptedBeforeInjectedLoss, exactSameSubmissionDigestReplayed: replayCalls[0]!.requestDigest,
    sameReviewIdConfirmed: true, brokerDisconnects: rpc.disconnects };
  report.backgroundEventPolls = rpc.calls.filter(call => call.method === 'review.events').length;
  report.adapterObservationCalls = rpc.calls.filter(call => call.method === 'review.observe').length;
  assert.equal(await readFile(join(publicDirectory, FIXTURE_PATH), 'utf8'), REPAIRED_SOURCE);
  report.verified = true;
  report.stage = 'verified';
} catch (error) {
  report.failure = error instanceof HostError ? error.code : error instanceof BrokerError ? error.code :
    error instanceof assert.AssertionError ? 'actual_delivery_assertion_failed' : 'actual_delivery_failed';
  report.lastProblems = consumers.map(consumer => { try { return safeDeliveryProblem(consumer.journal.read().lastProblem); } catch { return 'consumer_closed'; } });
  report.failureByteTelemetry = consumers.map(consumer => {
    try { return captureDeliveryTelemetry(consumer.journal.read()); } catch { return { unavailable: 'consumer_closed_or_telemetry_unavailable' }; }
  });
  process.exitCode = 1;
} finally {
  try {
    releaseTool();
    for (const consumer of consumers) await closeConsumer(consumer).catch(() => {});
    await rpc.close();
    // A fresh control-only connection can delete the exact known IDs even when a
    // consumer app-server was already disconnected. No resume/list/turn is sent.
    const cleanup = await ownedThreads.cleanup(() => new CodexAppServer());
    report.threadCleanup = cleanup;
    Object.assign(report, actualDemoCleanupEvidence(cleanup));
    // Thread cleanup cannot authorize deletion of either source or private runtime state.
    // Delivery verification stays separate from the explicitly unverified runtime cleanup.
    if (!cleanup.allConfirmed) {
      report.verified = false; report.failure ??= 'owned_disposable_cleanup_unconfirmed'; process.exitCode = 1;
      if (report.stage === 'verified') report.stage = 'owned_disposable_cleanup_unconfirmed';
      process.stderr.write('Disposable demo cleanup unconfirmed; owner-only recovery state retained at ' + directory + '\n');
    }
    await evidence.write(JSON.stringify(report, null, 2) + '\n');
    if (!report.verified) await writeFile(outputPath.replace(/\.json$/, '') + '.failed-' + new Date().toISOString().replace(/[:.]/g, '-') + '.json',
      JSON.stringify(report, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  } catch (error) {
    report.verified = false; process.exitCode = 1;
    report.failure = error instanceof HostError ? error.code : 'actual_demo_finalization_failed';
    process.stderr.write(String(report.failure) + '\n');
  } finally {
    try { await evidence.close(); }
    catch {
      report.verified = false; report.failure = 'actual_demo_evidence_close_failed'; process.exitCode = 1;
      process.stderr.write('actual_demo_evidence_close_failed\n');
    }
  }
  process.stdout.write(JSON.stringify({ evidenceScope: report.evidenceScope, verified: report.verified,
    phases: (report.phases as unknown[]).length, failure: report.failure ?? null, actualKotlinCompilerRun: report.actualKotlinCompilerRun }) + '\n');
}
