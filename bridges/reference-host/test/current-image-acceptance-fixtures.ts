/** Explicitly SYNTHETIC, non-executable compiler/owner data. Only tests import this module.
 * No live compiler, JVM, Gradle, broker process, owner, Codex, model or qualification claim.
 * Full IDs come from all four catalogs; the fourteen BELOW are a separately pinned CONTROL
 * selection, deliberately not advertised as the production owner's eventual activation plan.
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest, HostError, InboxJournal } from '../src/journal.js';
import { expectedPublicResponse } from '../src/codex-observation.js';
import type { ContextItem, ContextReceipt, HostBinding, HostPort, InsertedContext, ModelRequest, ModelWitness, Registration, ReviewEvent } from '../src/types.js';
import { OBSERVED_ASSURANCE, OBSERVED_GAP, type CompletedPatch, type ParentAdmissionData, type ParentCompletionData, type SnapshotLineage } from './current-image-acceptance-evidence.js';
import { REQUIRED_PARENT_ADAPTER, runSyntheticCurrentImageAcceptanceControl, type AcceptanceAuthorization, type AcceptanceClock,
  isOriginalReviewObservationClockFor, type OriginalReviewObservationClock,
  type CurrentImageParentAdapter, type EvidenceWriter, type OwnerInvocationSelection, type SyntheticControlOptions } from './current-image-acceptance-runner.js';
import type { CurrentImageEvidence } from './current-image-acceptance-image.js';
import { syntheticJournalCleanup } from './current-image-acceptance-cleanup.js';
import type { OwnerCompilerPolicyAudit, OwnerCompilerPolicySelection } from './current-image-acceptance-policy.js';

export type FixtureValue = Record<string, any>; // Mutation controls are deliberately untrusted JSON shapes.
export const CONTROL_RULES = Object.freeze(['core', 'libraries', 'platform', 'ecosystem'].flatMap(name =>
  (JSON.parse(readFileSync(new URL('../../../../data/rules-' + name + '.json', import.meta.url), 'utf8')) as { id: string }[]).map(row => row.id)).sort());
export const CONTROL_COMPILER_RULES = Object.freeze([
  'ktc.compiler.common.unresolved_reference', 'ktc.compiler.common.unresolved_import',
  'ktc.compiler.common.return_type_mismatch', 'ktc.compiler.common.initializer_type_mismatch',
  'ktc.compiler.common.assignment_type_mismatch', 'ktc.compiler.common.argument_type_mismatch',
  'ktc.compiler.common.null_for_nonnull_type', 'ktc.compiler.common.unsafe_call',
  'ktc.compiler.common.no_value_for_parameter', 'ktc.compiler.common.too_many_arguments',
  'ktc.compiler.common.named_parameter_not_found', 'ktc.compiler.common.argument_passed_twice',
  'ktc.compiler.common.non_vararg_spread', 'ktc.compiler.common.spread_of_nullable',
].sort());
export const CONTROL_OWNER_IMAGE = digest('synthetic owner image - no successful build or execution');
export const CONTROL_PATH = 'src/test/kotlin/Use.kt';
export const CONTROL_RULE = 'ktc.compiler.common.unresolved_reference';
export const CONTROL_POLICY: OwnerCompilerPolicySelection = Object.freeze({ compilerRuleIds: CONTROL_COMPILER_RULES,
  reason: 'SYNTHETIC selector shape control only; no owner mutation, provider execution or qualification.', authorizeOwnerPolicy: true });
export const CONTROL_POLICY_DIGEST = digest('synthetic-explicit-policy-' + CONTROL_COMPILER_RULES.length);
export const CONTROL_INVOCATION: OwnerInvocationSelection = {
  gradleHome: '/synthetic-current-image/gradle', gradleCacheSeed: '/synthetic-current-image/seed',
  compilerJavaHome: '/synthetic-current-image/jdk', authorizeExecution: true,
  // Deliberately nonalphabetical: no sort or directory enumeration is installation authority.
  compilerArtifactPaths: ['/synthetic-current-image/installed/z-compiler.jar', '/synthetic-current-image/installed/a-support.jar'],
};
export const CONTROL_IMAGE: CurrentImageEvidence = {
  selectionDigest: digest('synthetic selected image'), buildRecordDigest: digest('synthetic build-record data'),
  sourceInputsDigest: digest('synthetic source'), installedOutputsDigest: digest('synthetic installed outputs'),
  controllerOutputsDigest: digest('synthetic controller outputs'), compilerArtifactOrderDigest: digest('synthetic ordered compiler pins'),
  ownerCodeImageDigest: CONTROL_OWNER_IMAGE, nodeDigest: digest('synthetic node'), nodeVersion: '24.20.0', codexDigest: digest('synthetic codex'),
  sourceLinkage: 'owner_pinned_successful_build_record_linkage_not_reproducibility_or_loaded_memory_attestation', loadedImageAttestation: false,
};
export function controlRegistration(): Registration {
  const clientId = 'synthetic-current-client';
  return { contractVersion: PREVIEW_CONTRACT, binding: { ownerId: 'synthetic-owner', workspaceId: 'synthetic-workspace', worktreeId: 'synthetic-worktree',
    clientId, sessionId: 'synthetic-session', lineageId: 'synthetic-lineage', generation: 1 },
    client: { clientId, token: 'SYNTHETIC_PRIVATE_CLIENT_TOKEN_NOT_A_REAL_SECRET' },
    adapter: { clientId, token: 'SYNTHETIC_PRIVATE_ADAPTER_TOKEN_NOT_A_REAL_SECRET' } };
}
export function controlAuthorization(registration: Registration): AcceptanceAuthorization {
  return { attemptId: 'synthetic-current-attempt', approvalDigest: digest('synthetic control authorization - cannot authorize any actual execution'),
    requiredClientIds: [registration.binding.clientId], compilerRuleIds: CONTROL_COMPILER_RULES,
    ownerInvocationSelectionDigest: digest(canonical(CONTROL_INVOCATION)), imageSelectionDigest: CONTROL_IMAGE.selectionDigest,
    ownerCompilerPolicySelectionDigest: digest(canonical(CONTROL_POLICY)),
    scheduledPatches: [1, 2].map(ordinal => ({ patchId: 'synthetic-patch-' + ordinal, taskId: 'synthetic-task', findings: [{
      ruleId: CONTROL_RULE, upstreamId: 'UNRESOLVED_REFERENCE', path: CONTROL_PATH, present: ordinal === 1 }] })) };
}
/** Independent source-data construction of config serialization, not an owner permit or SUT call. */
export function controlPolicyAudit(registration: Registration): OwnerCompilerPolicyAudit {
  const binding = registration.binding, configBinding = { ownerId: binding.ownerId, workspaceId: binding.workspaceId,
    worktreeId: binding.worktreeId, lineageId: binding.lineageId };
  const absent = { revision: 0, generationId: null, documentDigest: digest('ktchecker/config/absent/1') };
  let prior: JsonObject = { binding: configBinding, shared: absent, local: absent, policyDigest: digest('synthetic-explicit-policy-0') };
  const beforeRevision = structuredClone(prior), auditEvents: JsonObject[] = [], mutationSteps: OwnerCompilerPolicyAudit['mutationSteps'][number][] = [];
  for (const [index, ruleId] of CONTROL_COMPILER_RULES.entries()) {
    const request = { binding: configBinding, layer: 'local', expected: structuredClone(prior),
      action: { type: 'set_rule_enabled', ruleId, enabled: true }, requestId: 'synthetic-policy-request-' + index };
    const auditEvent = { revision: index + 1, requestId: request.requestId, requestDigest: digest(canonical(request)),
      actor: { ownerId: binding.ownerId, clientId: 'explicit-owner-cli', sessionId: 'synthetic-policy-actor-session-' + index },
      appliedAtEpochMillis: 0, previousEventDigest: index === 0 ? null : digest(canonical(auditEvents[index - 1])) };
    const next = { binding: configBinding, shared: absent, local: { revision: index + 1, generationId: 'synthetic-local-generation',
      documentDigest: digest('synthetic-policy-document-' + index) }, policyDigest: digest('synthetic-explicit-policy-' + (index + 1)) };
    mutationSteps.push({ request, beforeRevision: structuredClone(prior), afterRevision: structuredClone(next), auditEvent });
    auditEvents.push(auditEvent); prior = next;
  }
  return { schema: 'ktchecker/owner-compiler-policy-selection/1', binding: structuredClone(binding), selection: structuredClone(CONTROL_POLICY),
    selectionDigest: digest(canonical(CONTROL_POLICY)), beforeRevision, afterRevision: prior, policyDigest: CONTROL_POLICY_DIGEST, auditEvents, mutationSteps,
    afterLocal: { layer: 'local', revision: prior.local, ruleSettings: CONTROL_COMPILER_RULES.map(ruleId => ({ ruleId, enabled: true })),
      suppressions: [], suppressionMasks: [], baselines: [], auditEvents } };
}
export function controlPatch(registration: Registration, ordinal: number, previous: SnapshotLineage | null): CompletedPatch {
  const files: Record<string, string> = {
    'build.gradle.kts': '// NON-EXECUTED shape control\nplugins { kotlin("jvm") version "2.4.21" }\n',
    'settings.gradle.kts': 'rootProject.name = "synthetic-current-image"\n',
    'src/main/kotlin/Api.kt': 'package sample\nfun declaredAcrossFiles(): String = "ok"\n',
    [CONTROL_PATH]: 'package sample\nfun use(): String = ' + (ordinal === 1 ? 'declaredAcrossFile' : 'declaredAcrossFiles') + '()\n',
  };
  return { patchId: 'synthetic-patch-' + ordinal, taskId: 'synthetic-task', ordinal, binding: structuredClone(registration.binding),
    baseSnapshotId: previous?.snapshotId ?? null, expectedHeadRevision: previous?.headRevision ?? 0,
    roots: [{ id: 'project', mountPath: '' }], files: Object.keys(files).sort().map(path => ({ rootId: 'project', path,
      contentBase64: Buffer.from(files[path]!, 'utf8').toString('base64'), executable: false })) };
}
export interface SyntheticParent {
  readonly patch: CompletedPatch; readonly lineage: SnapshotLineage; readonly data: ParentAdmissionData;
  completion: ParentCompletionData; readonly events: ReviewEvent[];
}
const entryKey = (entry: JsonObject) => String(entry.rootId) + '\u0000' + String(entry.path);
const snapshotHash = (tag: string, value: unknown) => digest('ktchecker:snapshot:' + tag + '\u0000' + canonical(value));
/** Independent fixture serialization of the existing StoredSnapshotManifest scheme. */
export function controlParent(patch: CompletedPatch, previous: SyntheticParent | null = null): SyntheticParent {
  const roots = patch.roots.map(root => ({ ...root, livePath: null })), entries: JsonObject[] = patch.files.map(file => ({
    rootId: file.rootId, path: file.path, kind: 'FILE', digest: 'sha256:' + createHash('sha256').update(Buffer.from(file.contentBase64, 'base64')).digest('hex'),
    size: Buffer.from(file.contentBase64, 'base64').length, executable: file.executable, symlinkTarget: null }));
  const before = new Map((previous?.lineage.entries ?? []).map(entry => [entryKey(entry), entry])), after = new Map(entries.map(entry => [entryKey(entry), entry]));
  const delta = [...new Set([...before.keys(), ...after.keys()])].sort().flatMap(key => {
    const old = before.get(key) ?? null, next = after.get(key) ?? null;
    if (canonical(old) === canonical(next)) return [];
    const entry = next ?? old!;
    return [{ rootId: entry.rootId, path: entry.path, operation: old === null ? 'added' : next === null ? 'deleted' : 'modified',
      beforeDigest: old === null ? null : snapshotHash('entry-v1', old), afterDigest: next === null ? null : snapshotHash('entry-v1', next) }];
  });
  const manifest: FixtureValue = { snapshotId: '', schemaVersion: 1, roots, entries, treeDigest: snapshotHash('tree-v1', { roots, entries }),
    identity: { ownerId: patch.binding.ownerId, workspaceId: patch.binding.workspaceId, worktreeId: patch.binding.worktreeId },
    clientId: patch.binding.clientId, sessionId: patch.binding.sessionId, patchId: patch.patchId,
    parentSnapshotId: patch.baseSnapshotId, producerBaseSnapshotId: patch.baseSnapshotId, headRevision: patch.expectedHeadRevision + 1,
    capture: { kind: 'IMMUTABLE_OVERLAY', sourceEpoch: (previous?.lineage.sourceEpoch ?? 0) + 1, allWritersCooperate: false, leaseFence: null, uncertainty: [] },
    scope: { description: 'declared producer inputs', exclusions: [], unknownInputs: [] }, delta };
  manifest.snapshotId = snapshotHash('manifest-v1', manifest);
  const contract: FixtureValue = { snapshotId: manifest.snapshotId, ownerId: patch.binding.ownerId, workspaceId: patch.binding.workspaceId,
    worktreeId: patch.binding.worktreeId, files: entries.map(entry => ({ path: entry.path, digest: entry.digest, size: entry.size })),
    parentSnapshotId: patch.baseSnapshotId, captureKind: 'immutable_overlay', declaredRoots: ['project'], scopeExclusions: [], unknownInputs: [] };
  const lineage: SnapshotLineage = { snapshotId: manifest.snapshotId, manifestDigest: digest(canonical(manifest)), headRevision: manifest.headRevision,
    sourceEpoch: manifest.capture.sourceEpoch, binding: structuredClone(patch.binding), roots, entries, contract };
  const n = patch.ordinal, modelDigest = digest('synthetic-model-' + manifest.snapshotId), evidenceIdentity = digest('synthetic-evidence-' + n),
    invocationId = 'synthetic-export-' + n, deadlineId = 'synthetic-original-export-clock-' + n, catalogDigest = digest('synthetic-catalog-inventory');
  const observedExport = { binding: patch.binding, invocationId, evidenceDigest: evidenceIdentity, assurance: OBSERVED_ASSURANCE };
  const oldFiles = new Map(((previous?.lineage.contract.files ?? []) as JsonObject[]).map(file => [String(file.path), file]));
  const newFiles = new Map((contract.files as JsonObject[]).map(file => [String(file.path), file]));
  const changedPaths = [...new Set([...oldFiles.keys(), ...newFiles.keys()])].sort().filter(path => canonical(oldFiles.get(path) ?? null) !== canonical(newFiles.get(path) ?? null));
  const rawContexts = ['main', 'test'].map(name => ({ scope: { module: ':', target: '', sourceSet: name, compilation: name }, compilerVersion: '2.4.21',
    classpath: [{ coordinate: 'synthetic:stdlib:1', digest: digest('synthetic stdlib'), path: '/synthetic-current-image/dependencies/stdlib.jar' }],
    compilerArguments: ['-language-version', '2.4', '-jvm-target', '21'], libraries: {},
    policies: { 'ktchecker.model-assurance': OBSERVED_ASSURANCE, 'ktchecker.observed-export-invocation': invocationId,
      'ktchecker.observed-model-evidence': evidenceIdentity, 'ktchecker.observed-export-closure': OBSERVED_GAP },
    modelDigest, dependencyDigest: digest('synthetic-dependencies'), toolchainDigest: digest('synthetic-toolchain'),
    configurationDigest: CONTROL_POLICY_DIGEST, fullProject: false,
    compilationFiles: [(name === 'main' ? 'src/main/kotlin/Api.kt' : CONTROL_PATH)] }));
  const slots = rawContexts.map((raw, index) => ({ slotId: index === 0 ? 'main' : 'test', identity: null, state: 'declared',
    reasons: ['observed_compilation_context_unavailable', OBSERVED_GAP, 'jvm_produced_output_deferred'], input: {
      snapshot: contract, materializedRoot: '/synthetic-current-image/invocations/' + n + '/snapshot', requiredRuleIds: CONTROL_RULES,
      context: { ...raw, policies: { ...raw.policies, 'ktchecker.catalog-inventory': catalogDigest,
        'ktchecker.activation-plan': digest('synthetic-activation'), 'ktchecker.observed-export-evidence': digest(canonical(observedExport)) } },
      baselineSnapshotId: patch.baseSnapshotId, changedPaths, execution: null, outputRoot: null } }));
  const declaration = (index: number) => ({ slotId: slots[index]!.slotId, compilationId: ':||' + slots[index]!.slotId,
    rawContextDigest: digest(canonical(rawContexts[index])), argumentLayoutDigest: digest('synthetic-argument-layout-' + n + '-' + index) });
  const plan: FixtureValue = { binding: patch.binding, request: { patchId: patch.patchId, planRef: 'synthetic-plan-ref-' + n, idempotencyKey: 'synthetic-plan-key-' + n },
    sourceBindingDigest: digest(canonical(contract)), generatedInputsDigest: digest(canonical([])), modelCertificateId: evidenceIdentity,
    impactCertificateId: digest('synthetic-impact-' + n), slots, requiredRecipientClientIds: [patch.binding.clientId], jvmProduced: {
      profile: 'ktchecker/produced-jvm-plan/1', graphDigest: digest('synthetic-graph-' + n), importRequestDigest: digest('synthetic-import-' + n),
      modelDigest, moduleId: ':', toolchainDigest: rawContexts[0]!.toolchainDigest, producer: declaration(0), consumer: declaration(1),
      referenceDigest: digest('synthetic-produced-reference-' + n), classpathIndex: 1, friend: true, catalogDigest,
      requiredRulesDigest: digest(canonical(CONTROL_RULES)), intentDigest: digest('synthetic-intent-' + n),
      originalDeadlineIdentity: deadlineId, originalDeadlineMillis: 120_000, observedExport } };
  const parent: FixtureValue = { contractVersion: PREVIEW_CONTRACT, planId: 'synthetic-plan-' + n, clientId: patch.binding.clientId,
    patchId: patch.patchId, sourceSnapshotId: lineage.snapshotId, planDigest: digest(canonical(plan)), state: 'pending',
    reasons: ['produced_plan_preview_unqualified', OBSERVED_GAP], slots: slots.map((slot, index) => ({ slotId: slot.slotId,
      reviewId: 'synthetic-review-' + n + '-' + slot.slotId, context: slot.input.context.scope, requiredRuleIds: CONTROL_RULES,
      state: index === 0 ? 'queued' : 'deferred', reasons: [...slot.reasons] })) };
  const data: ParentAdmissionData = { snapshotCommit: { manifest, headRevision: lineage.headRevision, sourceEpoch: lineage.sourceEpoch, replayed: false },
    declaredPlan: plan, parent, compilerRuleIds: CONTROL_COMPILER_RULES, observed: { invocationId, evidenceIdentity, modelDigest,
      observedEvidenceDigest: digest('synthetic-observed-document-' + n), ownerCodeImageDigest: CONTROL_OWNER_IMAGE,
      originalDeadlineIdentity: deadlineId, originalDeadlineMillis: 120_000, assurance: OBSERVED_ASSURANCE, reasons: [OBSERVED_GAP],
      documentaryBefore: previous === null ? null : { snapshotId: previous.lineage.snapshotId, invocationId: previous.data.observed.invocationId,
        evidenceIdentity: previous.data.observed.evidenceIdentity, modelDigest: previous.data.observed.modelDigest } } };
  const child = (index: number): FixtureValue => {
    const slot = parent.slots[index], scope = slot.context, file = contract.files.find((file: FixtureValue) => file.path === CONTROL_PATH);
    const hasFinding = n === 1 && index === 1;
    const location = { path: CONTROL_PATH, fileDigest: file.digest, line: 2, column: 20, endLine: 2, endColumn: 39, excerpt: 'declaredAcrossFile()' };
    const findings = hasFinding ? [{ ruleId: CONTROL_RULE, upstreamId: 'UNRESOLVED_REFERENCE', title: 'Unresolved reference',
      diagnosis: 'Unresolved reference: declaredAcrossFile.', whyItMatters: 'The test compilation cannot resolve this symbol.',
      location, fingerprint: digest('synthetic-finding-' + lineage.snapshotId), providerId: 'synthetic-provider-not-executed', context: scope,
      severity: 'error', kind: 'probable_defect', certainty: 'high', evidence: [], fixHint: null,
      verification: [{ kind: 'compile', expected: 'Recompile the unchanged test scope after the intended repair.', command: [], state: 'not_run' }],
      symbolId: null, semanticDigest: null, relatedLocations: [], disposition: 'open' }] : [];
    const coverage = CONTROL_RULES.map(ruleId => {
      const selected = CONTROL_COMPILER_RULES.includes(ruleId);
      return { ruleId, context: scope, state: selected ? hasFinding && ruleId === CONTROL_RULE ? 'findings' : 'passed' : 'unavailable',
        reason: selected ? OBSERVED_GAP : 'no_compatible_installed_rule_implementation', required: true, depth: 'build',
        provenance: selected ? 'executed' : 'not_executed', certificateId: null, implemented: selected, tested: selected, qualified: false };
    });
    const reasons = [...coverage.flatMap(row => [...(row.implemented ? [] : ['coverage_unimplemented:' + row.ruleId]),
      'coverage_unqualified:' + row.ruleId, 'coverage_unsuccessful:' + row.ruleId]), 'produced_plan_preview_unqualified', OBSERVED_GAP,
      'provider_reported_incompleteness', 'required_coverage_incomplete_or_wrong_context'].sort();
    return { contractVersion: PREVIEW_CONTRACT, reviewId: slot.reviewId, clientId: patch.binding.clientId,
      patchId: 'plan-child:' + digest(canonical([parent.planId, slot.slotId])), sequence: 2 * (n - 1) + index + 1,
      sourceSnapshotId: lineage.snapshotId, state: 'incomplete', cancelled: false, parentPlan: null, reasons,
      result: { providerId: 'synthetic-provider-not-executed', providerVersion: 'preview', status: 'incomplete', findings, coverage,
        reasons: [OBSERVED_GAP], rawDiagnostics: hasFinding ? [{ message: 'Unresolved reference: declaredAcrossFile.', severity: 'error',
          upstreamId: 'UNRESOLVED_REFERENCE', location }] : [], metrics: { elapsedMillis: 1, cpuMillis: null, peakRssBytes: null, outputBytes: 0 } } };
  };
  const children = [child(0), child(1)], completeParent: FixtureValue = structuredClone(parent);
  completeParent.state = 'incomplete';
  completeParent.slots = completeParent.slots.map((slot: FixtureValue, index: number) => ({ ...slot, state: 'incomplete', reasons: children[index]!.reasons }));
  const completion: ParentCompletionData = { parent: completeParent, children };
  const events: ReviewEvent[] = [];
  const add = (kind: string, payload: FixtureValue) => {
    const text = canonical(payload), sequence = (previous?.events.at(-1)?.sequence ?? 0) + events.length + 1;
    events.push({ contractVersion: PREVIEW_CONTRACT, clientId: patch.binding.clientId, generation: patch.binding.generation,
      eventId: 'synthetic-event-' + sequence, sequence, reviewId: payload.reviewId, patchId: payload.patchId,
      sourceSnapshotId: lineage.snapshotId, kind, deliveryRequired: true, payload: text, payloadDigest: digest(text) });
  };
  for (const value of children) add('accepted', { ...value, state: 'accepted', result: null, reasons: [OBSERVED_GAP] });
  add('plan_accepted', { ...children[1], state: 'plan_accepted', result: null, reasons: [OBSERVED_GAP], parentPlan: parent });
  add('result', children[0]!); add('result', children[1]!);
  add('plan_resolved', { ...children[1], state: 'plan_resolved', result: null, reasons: [OBSERVED_GAP], parentPlan: completeParent });
  return { patch, lineage, data, completion, events };
}

export class SyntheticAcceptanceClock implements AcceptanceClock {
  now = 0; readonly waits: { entered: number; cap: number }[] = [];
  elapsedMs(): number { return this.now; }
  remainingMs(): number { if (this.now >= 900_000) throw new HostError('synthetic_original_overall_expired'); return 900_000 - this.now; }
  checkpoint(): void { this.remainingMs(); }
  async wait<T>(operation: () => Promise<T>, capMs = 900_000): Promise<T> {
    const cap = Math.min(this.remainingMs(), capMs), entered = this.now; this.waits.push({ entered, cap });
    const value = await operation();
    if (this.now - entered >= cap) throw new HostError('synthetic_wait_outcome_unknown'); this.checkpoint(); return value;
  }
}
export class SyntheticEvidenceWriter implements EvidenceWriter {
  readonly rows: { stage: string; value: unknown }[] = [];
  hook: ((stage: string, value: unknown) => void) | null = null;
  append(stage: string, value: unknown): void { this.rows.push({ stage, value: structuredClone(value) }); this.hook?.(stage, value); }
}
export class SyntheticApplicationHost implements HostPort {
  readonly inserted = new Map<string, InsertedContext>(); readonly items = new Map<string, ContextItem>();
  readonly requests: ModelRequest[] = []; readonly phases: JsonObject[] = [];
  insertHook: (() => void) | null = null; invokeHook: ((request: ModelRequest) => void) | null = null;
  evidenceHook: ((phase: JsonObject) => JsonObject) | null = null;
  reuseApplicationId = false; unavailable = false;
  constructor(readonly binding: HostBinding) {}
  async findContext(marker: string): Promise<InsertedContext | null> { return structuredClone(this.inserted.get(marker) ?? null); }
  async insertContext(item: ContextItem, receiptId: string): Promise<InsertedContext> {
    this.insertHook?.(); const value: InsertedContext = { marker: item.marker, hostThreadId: item.hostThreadId, generation: item.generation,
      receipt: { receiptId, contextDigest: item.digest, contextItemId: 'synthetic-context-item-' + (this.inserted.size + 1), insertedAt: '2026-11-05T00:00:00.000Z' } };
    this.items.set(item.marker, structuredClone(item)); this.inserted.set(item.marker, value); return structuredClone(value);
  }
  async invokeModel(request: ModelRequest): Promise<ModelWitness> {
    this.invokeHook?.(request); this.requests.push(structuredClone(request));
    if (this.unavailable) return { kind: 'test-only', reason: 'synthetic receiver cannot make an actual-host claim' };
    const applicationRequestId = 'synthetic-application-' + (this.reuseApplicationId ? 1 : this.requests.length);
    const output = canonical({ contractVersion: PREVIEW_CONTRACT, invocationId: request.invocationId, requestDigest: request.requestDigest,
      contexts: request.contexts.map(item => ({ contextDigest: item.digest, text: item.text })) });
    const serializedApplicationRequest = JSON.stringify({ id: applicationRequestId, method: 'turn/start', params: {
      threadId: this.binding.hostThreadId, input: [], toolOutput: { name: 'ktchecker', output }, effort: 'max' } });
    const verifiedPublicResponseText = canonical(expectedPublicResponse(request));
    this.phases.push({ status: 'verified', model: 'gpt-6-astra', effort: 'max', internalInferenceRequestTrace: false,
      invocationId: request.invocationId, requestDigest: request.requestDigest, contextDigests: request.contexts.map(item => item.digest),
      applicationRequest: { applicationRequestId, method: 'turn/start', serializedRequestDigest: digest(serializedApplicationRequest), sentAt: '2026-11-05T00:00:00.000Z' },
      serializedApplicationRequest, verifiedPublicResponseText, publicResponseDigest: digest(verifiedPublicResponseText) });
    // Explicit protected-SPI CONTROL shape. Only runSyntheticCurrentImageAcceptanceControl admits it.
    return { kind: 'actual-model-request', invocationId: request.invocationId, modelRequestId: 'codex-app-request:' + applicationRequestId,
      hostThreadId: request.hostThreadId, generation: request.generation, requestDigest: request.requestDigest, contextDigests: request.contexts.map(item => item.digest) };
  }
  async evidence(): Promise<JsonObject> { const phase = structuredClone(this.phases.at(-1)!); return this.evidenceHook?.(phase) ?? phase; }
}

export class SyntheticParentAdapter implements CurrentImageParentAdapter, BrokerPort {
  profile = REQUIRED_PARENT_ADAPTER; readonly broker: BrokerPort = this;
  readonly parents: SyntheticParent[] = []; readonly calls: string[] = []; readonly clockScopes: Parameters<CurrentImageParentAdapter['submitCompletedPatch']>[2][] = [];
  readonly observed = new Set<string>(); readonly acked = new Map<string, ContextReceipt>();
  readonly publishedEvents: ReviewEvent[] = [];
  readonly verifiedDeliveries: { readonly handle: object; readonly review: OriginalReviewObservationClock }[] = [];
  private readonly handles = new WeakMap<object, SyntheticParent>(); private eventCursor = 0; private expectedCursor: string | null = null;
  private readonly reviewClocks = new WeakMap<object, OriginalReviewObservationClock>();
  private readonly deliveredHandles = new WeakSet<object>();
  statusCalls = 0; progressive = false; loseSubmitReturn = false;
  admissionHook: ((parent: SyntheticParent) => void) | null = null;
  statusHook: ((parent: SyntheticParent, count: number) => ParentCompletionData) | null = null;
  responseHook: ((method: string, response: JsonObject) => JsonObject) | null = null;
  deliveryHook: ((handle: object, review: OriginalReviewObservationClock) => unknown) | null = null;
  constructor(readonly registration: Registration) {}
  async submitCompletedPatch(patch: CompletedPatch, selection: OwnerInvocationSelection,
    clockScope: Parameters<CurrentImageParentAdapter['submitCompletedPatch']>[2]) {
    this.calls.push('owner.submit'); this.clockScopes.push(clockScope); assert.deepEqual(selection, CONTROL_INVOCATION);
    clockScope.overall.checkpoint(); clockScope.reviewObservation.checkpoint(); assert.equal(clockScope.originalExportMillis, 120_000);
    const parent = controlParent(patch, this.parents.at(-1) ?? null); this.admissionHook?.(parent); this.parents.push(parent);
    this.publishedEvents.push(...structuredClone(this.progressive ? parent.events.slice(0, 3) : parent.events));
    const deliveryHandle = {}; this.handles.set(deliveryHandle, parent);
    assert(isOriginalReviewObservationClockFor(clockScope.reviewObservation, clockScope.overall));
    this.reviewClocks.set(deliveryHandle, clockScope.reviewObservation);
    if (this.loseSubmitReturn) throw new HostError('synthetic_submit_outcome_unknown');
    return { data: structuredClone(parent.data), deliveryHandle };
  }
  async status(handle: object): Promise<ParentCompletionData> {
    this.calls.push('owner.status'); const parent = this.handles.get(handle); assert(parent, 'same opaque admission required'); this.statusCalls++;
    if (this.statusHook) return structuredClone(this.statusHook(parent, this.statusCalls));
    if (this.progressive && this.statusCalls === 1) {
      const value = structuredClone(parent.completion) as { parent: FixtureValue; children: FixtureValue[] };
      value.parent.state = 'pending'; value.parent.slots[0].state = 'running'; value.parent.slots[1].state = 'deferred';
      value.children[0]!.state = 'running'; value.children[1]!.state = 'deferred'; value.children[1]!.result = null;
      return value; // Deliberately a non-null progressive result on a running work row.
    }
    if (this.progressive) {
      for (const event of parent.events) if (!this.publishedEvents.some(old => old.eventId === event.eventId)) this.publishedEvents.push(structuredClone(event));
    }
    return structuredClone(parent.completion);
  }
  deliveryVerified(handle: object, exactOriginalReviewClock: OriginalReviewObservationClock): void {
    this.calls.push('owner.deliveryVerified');
    assert(this.handles.has(handle) && !this.deliveredHandles.has(handle) && this.reviewClocks.get(handle) === exactOriginalReviewClock,
      'same original delivery handle and review clock required once');
    exactOriginalReviewClock.checkpoint();
    const injected = this.deliveryHook?.(handle, exactOriginalReviewClock);
    if (injected !== undefined) return injected as unknown as void; // Deliberate ASYNC/nonvoid failure control; not an actual transport adapter.
    exactOriginalReviewClock.checkpoint(); this.deliveredHandles.add(handle);
    this.verifiedDeliveries.push({ handle, review: exactOriginalReviewClock });
  }
  async request<T = JsonObject>(method: string, params: JsonObject): Promise<T> {
    this.calls.push(method);
    const credential = params.credential as { clientId: string; token: string }, adapter = method === 'review.ack' || method === 'review.observe';
    assert.equal(credential.clientId, this.registration.binding.clientId); assert.equal(credential.token, adapter ? this.registration.adapter.token : this.registration.client.token);
    let result: JsonObject; const binding = this.registration.binding;
    if (method === 'review.events') {
      assert.equal(params.cursor, this.expectedCursor);
      const events = this.publishedEvents.slice(this.eventCursor); this.eventCursor = this.publishedEvents.length;
      this.expectedCursor = 'synthetic-cursor-' + this.eventCursor;
      result = { contractVersion: PREVIEW_CONTRACT, events, cursor: this.expectedCursor, hasMore: false };
    } else if (adapter) {
      const ids = params.eventIds as string[];
      for (const id of ids) assert(this.publishedEvents.some(event => event.eventId === id));
      if (method === 'review.ack') for (const id of ids) this.acked.set(id, structuredClone(params.receipt) as ContextReceipt);
      else for (const id of ids) { assert(this.acked.has(id)); this.observed.add(id); }
      result = { contractVersion: PREVIEW_CONTRACT, clientId: binding.clientId, eventIds: [...ids].sort(), state: method === 'review.ack' ? 'acknowledged' : 'model_observed' };
    } else if (method === 'gate.prepare') result = { contractVersion: PREVIEW_CONTRACT, cutId: 'synthetic-cut', clientId: binding.clientId,
      lineageId: binding.lineageId, generation: binding.generation, lineageEpoch: 4, sourceSnapshotId: this.parents.at(-1)!.lineage.snapshotId,
      eventFrontier: this.publishedEvents.at(-1)!.sequence, requiredEventIds: this.publishedEvents.filter(event => event.deliveryRequired).map(event => event.eventId),
      ready: false, reasons: this.gateReasons() };
    else if (method === 'gate.complete') result = { contractVersion: PREVIEW_CONTRACT, cutId: params.cutId, clientId: binding.clientId,
      lineageId: binding.lineageId, generation: binding.generation, allowed: false, actionPerformed: false, reasons: this.gateReasons() };
    else throw new HostError('synthetic_unknown_method');
    return structuredClone(this.responseHook?.(method, result) ?? result) as T;
  }
  private gateReasons(): string[] {
    return [...new Set(this.publishedEvents.map(event => event.reviewId))].map(review => OBSERVED_GAP + ':' + review);
  }
}

export async function controlFixture(t: { after(fn: () => Promise<unknown> | void): void }) {
  const directory = await mkdtemp(join(tmpdir(), 'ktchecker-current-image-SYNTHETIC-')), registration = controlRegistration();
  const host = new SyntheticApplicationHost({ client: structuredClone(registration.binding), hostKind: 'synthetic-control-only', hostThreadId: 'synthetic-current-thread' });
  const journal = new InboxJournal(join(directory, 'inbox'), host.binding), adapter = new SyntheticParentAdapter(registration),
    clock = new SyntheticAcceptanceClock(), evidence = new SyntheticEvidenceWriter();
  // Only the NEW local synthetic journal is closed/removed; no retained owner/process/host exists.
  // Build the hook outside this scope, without its runner/evidence closure context.
  t.after(syntheticJournalCleanup(journal, directory));
  const options: SyntheticControlOptions = { adapter, host, journal, clock, evidence, authorization: controlAuthorization(registration),
    ownerCompilerPolicy: CONTROL_POLICY, ownerCompilerPolicyAudit: controlPolicyAudit(registration),
    ownerInvocation: CONTROL_INVOCATION, forbidden: [], privateLocations: { ownerStateDirectory: '/synthetic-current-image/owner-state',
      hostStateDirectory: '/synthetic-current-image/host-state', journalDirectory: join(directory, 'inbox'),
      invocationRoot: '/synthetic-current-image/invocations', additional: [] },
    image: { assertCurrent: () => structuredClone(CONTROL_IMAGE), assertCompilerArtifactPaths: paths => assert.deepEqual(paths, CONTROL_INVOCATION.compilerArtifactPaths) },
    assertNoModelReroute: () => {}, syntheticEvidence: () => host.evidence() };
  return { directory, registration, host, journal, adapter, clock, evidence, options,
    run: (overrides: Partial<SyntheticControlOptions> = {},
      capture: (ordinal: number, previous: SnapshotLineage | null) => Promise<CompletedPatch> = async (ordinal, previous) => controlPatch(registration, ordinal, previous)) =>
      runSyntheticCurrentImageAcceptanceControl({ ...options, ...overrides }, capture) };
}
