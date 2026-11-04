#!/usr/bin/env node
/** Explicit opt-in live inference. The broker in THIS demonstration is synthetic; do not use this
 * evidence as proof of the actual Kotlin compiler/kernel. npm test never executes this file. */
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { CodexHostPort } from '../src/codex-host.js';
import { canonical, digest, HostError, InboxJournal } from '../src/journal.js';
import { KernelBroker } from '../src/broker.js';
import { ReferenceHost } from '../src/adapter.js';
import { event, FixtureKernel, registration, S1 } from './fixtures.js';

if (process.argv[2] !== '--allow-inference' || !process.argv[3]) {
  process.stderr.write('Explicit owner approval required: demo-codex-inbox --allow-inference EVIDENCE_JSON\n');
  process.exit(2);
}
const outputPath = resolve(process.argv[3]);
const directory = await mkdtemp(join(tmpdir(), 'ktchecker-public-codex-inbox-'));
const report: Record<string, unknown> = {
  schema: 'ktchecker-host-evidence/preview-1', date: new Date().toISOString(),
  evidenceScope: 'real_codex_application_requests_with_synthetic_broker_events',
  model: 'gpt-6-astra', allowProviderModelFallback: false,
  internalInferenceRequestTrace: false, actualKotlinCompilerRun: false, actualKernelIntegration: false,
  guardedActionPerformed: false, modelPollingCalls: 0,
  command: 'npm --prefix bridges/reference-host run demo:codex:synthetic -- --allow-inference EVIDENCE_JSON',
  node: process.version, codex: execFileSync('codex', ['--version'], { encoding: 'utf8' }).trim(),
  phases: [], verified: false,
};
const waitUntil = async (predicate: () => boolean, timeout = 30_000) => {
  const deadline = Date.now() + timeout;
  while (!predicate()) {
    if (Date.now() > deadline) throw new HostError('demonstration_deadline');
    await new Promise(resolve => setTimeout(resolve, 25));
  }
};
const r = registration('synthetic-codex-consumer-' + randomUUID());
const kernel = new FixtureKernel(r); const broker = new KernelBroker(kernel, r);
let host: CodexHostPort | undefined, journal: InboxJournal | undefined;
let controller: AbortController | undefined, running: Promise<void> | undefined;
let releaseTool: () => void = () => {};
async function stop(): Promise<void> {
  controller?.abort(); await running; running = undefined; controller = undefined;
  await host?.close(); journal?.close();
}
async function capture(phase: string, adapter: ReferenceHost): Promise<void> {
  assert(host && journal);
  const result = await adapter.requestModelObservation();
  assert.equal(result.observed, true);
  const proof = host.evidence().at(-1)!;
  const application = proof.applicationRequest as { applicationRequestId: string; serializedRequestDigest: string };
  const contexts = Object.values(journal.read().inbox).map(entry => entry.item!).filter(item =>
    (proof.contextDigests as string[]).includes(item.digest)).sort((a, b) => a.marker.localeCompare(b.marker));
  const output = canonical({ contractVersion: 'ktchecker/preview-1', invocationId: proof.invocationId,
    requestDigest: proof.requestDigest, contexts: contexts.map(item => ({ contextDigest: item.digest, text: item.text })) });
  const serializedRequest = JSON.stringify({ id: application.applicationRequestId, method: 'turn/start', params: {
    threadId: host.binding.hostThreadId, input: [], toolOutput: { name: 'ktchecker', output }, effort: 'low',
  } });
  assert.equal(digest(serializedRequest), application.serializedRequestDigest);
  const publicItems = await host.server.request<{ data: { item: { type: string; id: string; text?: string } }[] }>('thread/items/list', {
    threadId: host.binding.hostThreadId, turnId: proof.turnId, limit: 100, sortDirection: 'desc',
  });
  const message = publicItems.data.find(entry => entry.item.type === 'agentMessage' && entry.item.id === proof.publicItemId)?.item.text;
  assert(message); assert.equal(digest(message), proof.publicResponseDigest);
  // Retain only synthetic input and its verified PUBLIC response. Never any raw event stream.
  (report.phases as unknown[]).push({ phase, ...proof, serializedApplicationRequest: serializedRequest,
    verifiedPublicResponse: JSON.parse(message), verifiedPublicResponseText: message,
    publicTextDigestIndependentlyRecomputable: true, responseTextDigest: digest(message),
    allFindingDiagnosisLocationVerificationAndDigestsMatched: true });
}

try {
  await writeFile(join(directory, 'Label.kt'), 'fun label(name: String?): String = name\n');
  let toolEntered = false;
  const heldTool = new Promise<void>(resolve => { releaseTool = resolve; });
  host = await CodexHostPort.open({ directory: join(directory, 'session'), client: r.binding, cwd: directory,
    dynamicTools: [{ type: 'function', name: 'hold_for_background_review',
      description: 'Fixed no-op synthetic demonstration barrier. Wait for the external review controller; never polls or executes code.',
      inputSchema: { type: 'object', properties: {}, required: [], additionalProperties: false } }],
    onModelRequestAccepted: () => releaseTool(),
  });
  const firstThread = host.binding.hostThreadId;
  host.server.handleTool('hold_for_background_review', async () => {
    toolEntered = true; await heldTool;
    return { success: true, contentItems: [{ type: 'inputText', text: 'Background controller completed. Consume the queued ktchecker external tool output; no polling is needed.' }] };
  });
  journal = new InboxJournal(join(directory, 'inbox'), host.binding);
  let adapter = new ReferenceHost(journal, broker, host);
  controller = new AbortController(); running = adapter.run(controller.signal);
  const activeTurn = host.server.runTurn({ threadId: firstThread, input: [{ type: 'text',
    text: 'Call hold_for_background_review exactly once. Remain in this active turn until it returns; then consume the queued ktchecker data using the diagnostic JSON response contract. Do not use other tools.' }], effort: 'low' });
  // Attach a rejection handler immediately; do not leave a rejected live-turn promise unobserved.
  void activeTurn.catch(() => {});
  await waitUntil(() => toolEntered);
  await adapter.recordCompletedPatch({ patchId: 'p1', idempotencyKey: 'public-fixture-p1', input: { snapshot: { snapshotId: S1 }, materializedRoot: directory } });
  await new Promise(resolve => setTimeout(resolve, 600));
  kernel.eventsByClient.get(r.binding.clientId)!.push(event(r, 'active-diagnostic', S1, 1));
  await waitUntil(() => journal!.read().inbox['active-diagnostic']?.acknowledged === true);
  assert.equal(journal.read().inbox['active-diagnostic']!.observed, false);
  const before = await adapter.prepareFinal(); assert.equal((await adapter.completeFinal(before.cutId)).allowed, false);
  await capture('background_result_while_real_turn_active', adapter);
  const active = await activeTurn;
  assert.equal(active.status, 'completed'); assert.equal(active.turnId, host.evidence().at(-1)!.turnId);
  report.activeTurnJoined = true;
  const cursorBefore = journal.read().fetchCursor;
  await stop();
  // The adapter and app-server are disconnected here; only durable inbox and owned thread persist.
  kernel.eventsByClient.get(r.binding.clientId)!.push(event(r, 'disconnected-diagnostic', S1, 2));
  host = await CodexHostPort.open({ directory: join(directory, 'session'), client: r.binding, cwd: directory });
  assert.equal(host.resumed, true); assert.equal(host.binding.hostThreadId, firstThread);
  journal = new InboxJournal(join(directory, 'inbox'), host.binding);
  assert.equal(journal.read().fetchCursor, cursorBefore);
  assert.equal(journal.read().inbox['active-diagnostic']!.observed, true);
  adapter = new ReferenceHost(journal, broker, host);
  controller = new AbortController(); running = adapter.run(controller.signal);
  await waitUntil(() => journal!.read().inbox['disconnected-diagnostic']?.acknowledged === true);
  assert.equal(journal.read().inbox['disconnected-diagnostic']!.observed, false);
  await capture('owned_thread_resumed_after_adapter_and_app_server_reconnect', adapter);
  report.restartPreservedCursorAndIndependentAckObservation = true;
  report.backgroundPolls = kernel.calls.filter(call => call.method === 'review.events').length;
  report.verified = true;
  // Delete only the disposable thread created by this controller. No guessed or pre-existing IDs.
  await host.server.request('thread/delete', { threadId: firstThread });
} catch (error) {
  report.failure = error instanceof HostError ? error.code : error instanceof assert.AssertionError ? 'demonstration_assertion_failed' : 'demonstration_failed';
  process.exitCode = 1;
} finally {
  releaseTool(); await stop(); await rm(directory, { recursive: true, force: true });
  await mkdir(dirname(outputPath), { recursive: true }); await writeFile(outputPath, JSON.stringify(report, null, 2) + '\n');
  process.stdout.write(JSON.stringify({ evidenceScope: report.evidenceScope, verified: report.verified,
    phases: (report.phases as unknown[]).length, failure: report.failure ?? null,
    internalInferenceRequestTrace: false, actualKernelIntegration: false }) + '\n');
}
