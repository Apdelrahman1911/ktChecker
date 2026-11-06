// Authored SOURCE controls only. Synthetic ports/receipts are NOT authenticated-host/model proof.
import { test, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BrokerError, PREVIEW_CONTRACT, type JsonObject } from '@ktchecker/mcp-preview/rpc';
import { parsePolicyReassessmentView } from '@ktchecker/mcp-preview';
import { KernelBroker } from '../src/broker.js';
import { ReferenceHost, type AdapterOptions } from '../src/adapter.js';
import { canonical, digest, HostError, InboxJournal } from '../src/journal.js';
import { CONTEXT_SCHEMA, policyReassessmentEventView, prepareDiagnosticContext, projectDiagnostic, requiresObservationAttempt } from '../src/context-projection.js';
import { expectedPublicResponse, observationProjection, verifyPublicResponse } from '../src/codex-observation.js';
import { S1, S2, registration, binding, event as ordinaryEvent, FixtureKernel, FixtureHost } from './fixtures.js';
import type { ContextItem, HostBinding, ModelRequest, ModelWitness, PolicyReassessmentRequest, PolicyReassessmentView,
  ReviewEvent } from '../src/types.js';

// Exact hand-authored ASCII wire control shared with the MCP SOURCE test, NOT kernel-emitted.
// Its incomplete/unknown result is NOT a publishable/admitted kernel subject; it tests nonpromotion.
// Normal adapter flow below derives a separate structurally complete synthetic view (still no authority).
const SOURCE_WIRE = String.raw`{"auditOnly":true,"capture":{"analysisAuthorityDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","currentCaptureId":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","currentPolicyDigest":"sha256:2222222222222222222222222222222222222222222222222222222222222222","format":"ktchecker/policy-reassessment-capture/1","originalCaptureId":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","originalPolicyDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"certificateDigest":"sha256:f6ff5ab242366464c985d7c9ce48e1ff97aad654186a882fcf97ee0bd3d07881","certified":{"effectiveInputKey":"sha256:5555555555555555555555555555555555555555555555555555555555555555","inputKey":"sha256:3333333333333333333333333333333333333333333333333333333333333333","originalEffectiveResultDigest":"sha256:9999999999999999999999999999999999999999999999999999999999999999","originalPolicyCertificateDigest":"sha256:7777777777777777777777777777777777777777777777777777777777777777","rawResultDigest":"sha256:8888888888888888888888888888888888888888888888888888888888888888","reviewSequence":3,"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000000","workCertificateId":"sha256:6666666666666666666666666666666666666666666666666666666666666666","workKey":"sha256:4444444444444444444444444444444444444444444444444444444444444444"},"clientId":"client-a","evaluatorIdentity":"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","format":"ktchecker/policy-reassessment-view/1","generation":1,"issuerIdentity":"sha256:0000000000000000000000000000000000000000000000000000000000000000","patchId":"patch-original","reasons":["source_scope_incomplete"],"request":{"expectedPolicyDigest":"sha256:2222222222222222222222222222222222222222222222222222222222222222","generation":1,"idempotencyKey":"policy-request-1","reviewId":"review-original"},"result":{"coverage":[{"certificateId":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","context":{"compilation":"main","module":":main","sourceSet":"main","target":"jvm"},"depth":"build","implemented":true,"provenance":"executed","qualified":true,"reason":null,"required":true,"ruleId":"fixture.nullability","state":"findings","tested":true},{"certificateId":null,"context":{"compilation":"main","module":":main","sourceSet":"main","target":"jvm"},"depth":"build","implemented":false,"provenance":"not_executed","qualified":false,"reason":"Original unknown coverage is not promoted by policy.","required":true,"ruleId":"fixture.unknown","state":"unknown","tested":false}],"findings":[{"certainty":"medium","context":{"compilation":"main","module":":main","sourceSet":"main","target":"jvm"},"diagnosis":"Nullable name cannot be assigned to String.","disposition":"accepted","evidence":[{"artifactRef":null,"digest":null,"kind":"source","summary":"Original source excerpt."},{"artifactRef":"artifact:diagnostic-1","digest":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","kind":"compiler","summary":"Original compiler message."}],"fingerprint":"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","fixHint":null,"kind":"probable_defect","location":{"column":34,"endColumn":38,"endLine":1,"excerpt":"val label: String = nullable","fileDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","line":1,"path":"Label.kt"},"providerId":"fixture-provider","relatedLocations":[{"column":0,"endColumn":0,"endLine":2,"excerpt":"","fileDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","line":2,"path":"Usage.kt"}],"ruleId":"fixture.nullability","semanticDigest":null,"severity":"warning","symbolId":null,"title":"Nullable assignment","upstreamId":null,"verification":[{"command":[],"expected":"Compile the same immutable snapshot.","kind":"recheck","state":"not_run"},{"command":["compiler","--analysis-only"],"expected":"No mismatch in the target.","kind":"compile","state":"not_run"}],"whyItMatters":"A nullable value is not a non-null String."}],"metrics":{"cpuMillis":null,"elapsedMillis":5,"outputBytes":0,"peakRssBytes":null},"providerId":"fixture-provider","providerVersion":"preview","rawDiagnostics":[{"location":null,"message":"Original compiler output.","severity":"error","upstreamId":null},{"location":{"column":34,"endColumn":38,"endLine":1,"excerpt":"val label: String = nullable","fileDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","line":1,"path":"Label.kt"},"message":"At the original location.","severity":"warning","upstreamId":"UP-1"}],"reasons":["original_execution_incomplete"],"status":"incomplete"},"reviewId":"review-original","sequence":7,"sourceSnapshotId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","state":"policy_reassessed","viewId":"policy-sha256:1111111111111111111111111111111111111111111111111111111111111111"}`;
const SOURCE_WIRE_DIGEST = 'sha256:58156bf9312cf4fefbfa4e272aa069ba8b55f342c53053d94f3791ccffb14535';
const hash = (character: string) => 'sha256:' + character.repeat(64);
function wire(): PolicyReassessmentView { return JSON.parse(SOURCE_WIRE) as PolicyReassessmentView; }
function reseal(view: PolicyReassessmentView): PolicyReassessmentView {
  const copy = structuredClone(view); delete copy.contractVersion;
  copy.certificateDigest = ''; copy.certificateDigest = digest(canonical(copy)); return copy;
}
function completeView(): PolicyReassessmentView {
  const view = wire(); view.result.status = 'complete'; view.result.coverage = [view.result.coverage[0]!];
  view.result.reasons = []; view.auditOnly = false; view.reasons = []; return reseal(view);
}
function policyEvent(view = completeView()): ReviewEvent {
  const payload = canonical(view);
  return { contractVersion: PREVIEW_CONTRACT, eventId: view.viewId, clientId: view.clientId, sequence: view.sequence,
    reviewId: view.reviewId, patchId: view.patchId, sourceSnapshotId: view.sourceSnapshotId,
    kind: 'policy_reassessed', deliveryRequired: true, payloadDigest: digest(payload), payload, generation: view.generation };
}
function eventPage(events: ReviewEvent[] = [policyEvent()], cursor = 'client-a:1', hasMore = false) {
  return { contractVersion: PREVIEW_CONTRACT, events, cursor, hasMore };
}
function drop(value: object, key: string): void { delete (value as JsonObject)[key]; }
function envelope(item: ContextItem): JsonObject { return JSON.parse(item.text.slice(item.text.indexOf('\n') + 1)) as JsonObject; }
function changedEnvelope(item: ContextItem, edit: (body: JsonObject) => void): ContextItem {
  const body = envelope(item); edit(body);
  const text = item.text.slice(0, item.text.indexOf('\n') + 1) + canonical(body);
  return { ...item, text, digest: digest(text) };
}
function requestFor(contexts: ContextItem[], host: HostBinding): ModelRequest {
  const body = { hostThreadId: host.hostThreadId, generation: host.client.generation, contexts };
  return { ...body, invocationId: 'synthetic-policy-observation', requestDigest: digest(canonical(body)) };
}
const hostCode = (code: string) => (error: unknown) => error instanceof HostError && error.code === code;
const invalidView = (error: unknown) => error instanceof BrokerError && error.code === 'invalid_policy_reassessment_view';

/** Publication/query double only: never a provider, real policy evaluator or completion oracle. */
class PolicyKernel extends FixtureKernel {
  readonly views = new Map<string, PolicyReassessmentView>();
  readonly timeline: string[] = [];
  policyReply: ((method: string, params: JsonObject) => unknown) | null = null;
  eventReply: ((params: JsonObject) => unknown) | null = null;
  override async request<T = JsonObject>(method: string, params: JsonObject): Promise<T> {
    if (method !== 'review.policy.reassess' && method !== 'review.policy.status') {
      if (method === 'review.ack') this.timeline.push('ack');
      if (method === 'review.observe') this.timeline.push('observe');
      const result = await super.request<T>(method, params);
      return method === 'review.events' && this.eventReply ? structuredClone(this.eventReply(params)) as T : result;
    }
    this.calls.push({ method, params: structuredClone(params) });
    const credential = params.credential as { clientId: string; token: string };
    const registered = this.clients.get(credential.clientId);
    if (!registered || credential.token !== registered.client.token) throw new BrokerError('unauthorized', 'Synthetic capability mismatch');
    if (this.policyReply) return structuredClone(this.policyReply(method, params)) as T;
    const request = method === 'review.policy.reassess' ? structuredClone(params.request) as PolicyReassessmentRequest : null;
    const key = canonical([credential.clientId, request?.idempotencyKey ?? params.idempotencyKey]);
    const prior = this.views.get(key);
    if (method === 'review.policy.status') {
      if (!prior) throw new BrokerError('policy_reassessment_not_found', 'No synthetic historical view for this client/key');
      return structuredClone(prior) as T;
    }
    if (!request || request.generation !== registered.binding.generation) throw new BrokerError('policy_reassessment_generation_mismatch', 'Synthetic stale generation');
    if (prior) {
      if (canonical(prior.request) !== canonical(request)) throw new BrokerError('policy_reassessment_idempotency_conflict', 'Synthetic request changed');
      return structuredClone(prior) as T;
    }
    const events = this.eventsByClient.get(credential.clientId)!;
    const view = completeView(); view.clientId = credential.clientId; view.request = request;
    view.reviewId = request.reviewId; view.generation = request.generation;
    view.viewId = 'policy-' + digest(key); view.sequence = 7 + events.length;
    view.capture.currentPolicyDigest = request.expectedPolicyDigest;
    const stored = reseal(view); this.views.set(key, stored); events.push(policyEvent(stored));
    return structuredClone(stored) as T;
  }
}
class TracingHost extends FixtureHost {
  readonly requests: ModelRequest[] = [];
  constructor(host: HostBinding, private readonly timeline: string[]) { super(host); }
  override async insertContext(item: ContextItem, receiptId: string) {
    this.timeline.push('insert'); return super.insertContext(item, receiptId);
  }
  override async invokeModel(request: ModelRequest): Promise<ModelWitness> {
    this.timeline.push('invoke'); this.requests.push(structuredClone(request)); return super.invokeModel(request);
  }
}
function setup(t: TestContext, options: AdapterOptions = {}, registered = registration(), kernel = new PolicyKernel(registered)) {
  const directory = mkdtempSync(join(tmpdir(), 'ktchecker-policy-source-control-'));
  let opened: InboxJournal | undefined;
  // Install cleanup BEFORE database/controller construction; use public APIs, never inspect raw DB bytes.
  t.after(() => { try { opened?.close(); } finally { rmSync(directory, { recursive: true, force: true }); } });
  const host = new TracingHost(binding(registered), kernel.timeline);
  const journal = new InboxJournal(directory, host.binding); opened = journal;
  const broker = new KernelBroker(kernel, registered);
  return { journal, host, broker, kernel, registered, adapter: new ReferenceHost(journal, broker, host, options) };
}

test('policy projection retains the exact wire, original unknown coverage, full findings and compact references', () => {
  const view = wire(), event = policyEvent(view), projected = projectDiagnostic(event);
  assert.equal(event.payload, SOURCE_WIRE); assert.equal(digest(SOURCE_WIRE), SOURCE_WIRE_DIGEST);
  assert.deepEqual(parsePolicyReassessmentView(view), view);
  assert.equal(projected.diagnosticPayload, event.payload); assert.equal(projected.diagnosticPayloadDigest, event.payloadDigest);
  const diagnostic = JSON.parse(projected.diagnosticPayload) as PolicyReassessmentView;
  assert.deepEqual(diagnostic.result, view.result); assert.deepEqual(diagnostic.capture, view.capture);
  assert.deepEqual(diagnostic.certified, view.certified); assert.deepEqual(diagnostic.reasons, view.reasons);
  assert.equal(diagnostic.state, 'policy_reassessed'); assert.equal(diagnostic.result.coverage[1]!.state, 'unknown');
  assert.equal(diagnostic.result.coverage[1]!.tested, false); assert.equal(diagnostic.result.coverage[1]!.qualified, false);
  assert.equal(diagnostic.result.metrics.cpuMillis, null); assert.equal(diagnostic.result.findings[0]!.fixHint, null);
  assert.equal(Object.hasOwn(diagnostic.result, 'coverageSummary'), false); assert.equal(Object.hasOwn(diagnostic.result, 'coverageRowsOmitted'), false);
  const findingDigest = digest(canonical(view.result.findings[0]));
  assert.deepEqual(projected.findingObligations, [{ index: 0, findingDigest,
    obligationId: digest(canonical({ eventId: event.eventId, index: 0, findingDigest })) }]);
  assert.deepEqual(projected.originalPayloadRef, { kind: 'private_durable_inbox', clientId: event.clientId, generation: 1,
    eventId: event.eventId, payloadDigest: SOURCE_WIRE_DIGEST, bytes: 4997 });
  assert.equal(diagnostic.sequence, 7); assert.equal(diagnostic.certified.reviewSequence, 3);
});

test('historical policy source remains stale against S2 with lossless receipt scope, never currentness permission', () => {
  const event = policyEvent(), host = binding(registration());
  const item = prepareDiagnosticContext(event, host, S2), body = envelope(item);
  assert.equal(body.contextSchema, CONTEXT_SCHEMA); assert.equal(body.receiptScope, 'projected_context_only');
  assert.equal(body.deliveryScope, 'complete_lossless_policy_reassessment_view'); assert.equal(body.eventSequence, event.sequence);
  assert.equal(body.sourceSnapshotId, S1); assert.equal(body.comparedSnapshotId, S2); assert.equal(body.freshnessAtPreparation, 'stale');
  assert.equal(body.diagnosticPayload, event.payload); assert.equal(body.payloadDigest, body.diagnosticPayloadDigest);
  assert.equal((JSON.parse(body.diagnosticPayload as string) as PolicyReassessmentView).auditOnly, false);
  assert.equal(Object.hasOwn(body, 'allowed'), false); assert.equal(item.digest, digest(item.text));
  assert.match(String(body.caveat), /Historical evidence/);
  const unverified = envelope(prepareDiagnosticContext(event, host, null)); assert.equal(unverified.freshnessAtPreparation, 'unverified');
});

test('public policy reflection is conditional and copies the entire view; ordinary saved reflection stays unchanged', () => {
  const host = binding(registration()), event = policyEvent(wire());
  const item = prepareDiagnosticContext(event, host, S1), request = requestFor([item], host);
  const reflected = observationProjection(item);
  assert.deepEqual(reflected.policyReassessment, wire()); assert.equal(reflected.coverageSummary, null);
  assert.equal(Object.hasOwn(reflected, 'parentPlan'), false);
  const expected = expectedPublicResponse(request);
  assert.equal(verifyPublicResponse(canonical(expected), request), true);
  assert.equal((reflected.policyReassessment as PolicyReassessmentView).result.coverage.length, 2);
  assert.equal((reflected.policyReassessment as PolicyReassessmentView).result.rawDiagnostics.length, 2);
  const ordinary = prepareDiagnosticContext(ordinaryEvent(registration()), host, S1);
  const ordinaryBody = envelope(ordinary), ordinaryReflection = observationProjection(ordinary);
  assert.equal(Object.hasOwn(ordinaryBody, 'eventSequence'), false);
  assert.equal(ordinaryBody.deliveryScope, 'all_findings_and_raw_diagnostics_with_explicit_coverage_and_derivable_validation_reason_summary');
  assert.equal(Object.hasOwn(ordinaryReflection, 'policyReassessment'), false);
  assert.deepEqual(Object.keys(ordinaryReflection).sort(), ['contextDigest', 'coverage', 'coverageSummary', 'eventId', 'findingObligations',
    'findings', 'payloadDigest', 'receiptScope', 'sourceSnapshotId', 'state'].sort());
  assert.equal(verifyPublicResponse(canonical(expectedPublicResponse(requestFor([ordinary], host))), requestFor([ordinary], host)), true);
});

test('omitting or changing any policy evidence/disposition/capture/certified metadata invalidates public reflection', () => {
  const host = binding(registration()), item = prepareDiagnosticContext(policyEvent(wire()), host, S1);
  const request = requestFor([item], host), expected = expectedPublicResponse(request);
  const cases: [string, (view: PolicyReassessmentView) => void][] = [
    ['disposition', view => { view.result.findings[0]!.disposition = 'suppressed'; }],
    ['full finding title', view => { view.result.findings[0]!.title = 'shortened'; }],
    ['source excerpt', view => { view.result.findings[0]!.location.excerpt = ''; }],
    ['related locations', view => { view.result.findings[0]!.relatedLocations = []; }],
    ['evidence null', view => { drop(view.result.findings[0]!.evidence[0]!, 'artifactRef'); }],
    ['fixHint null', view => { view.result.findings[0]!.fixHint = ''; }],
    ['verification command', view => { view.result.findings[0]!.verification[1]!.command = []; }],
    ['unknown coverage promotion', view => { view.result.coverage[1]!.state = 'passed'; }],
    ['coverage qualification', view => { view.result.coverage[1]!.qualified = true; }],
    ['coverage omission', view => { view.result.coverage = []; }],
    ['raw diagnostic omission', view => { view.result.rawDiagnostics.pop(); }],
    ['metric null', view => { view.result.metrics.cpuMillis = 0; }],
    ['result reason', view => { view.result.reasons = []; }],
    ['view reason', view => { view.reasons = []; }],
    ['provider version', view => { view.result.providerVersion = 'different'; }],
    ['raw reference', view => { view.certified.rawResultDigest = hash('f'); }],
    ['capture reference', view => { view.capture.currentCaptureId = hash('f'); }],
    ['auditOnly', view => { view.auditOnly = false; }],
    ['certificate', view => { view.certificateDigest = hash('f'); }],
    ['default field omission', view => { drop(view, 'format'); }],
  ];
  for (const [label, mutate] of cases) {
    const changed = structuredClone(expected), observation = (changed.observations as JsonObject[])[0]!;
    mutate(observation.policyReassessment as PolicyReassessmentView);
    assert.equal(verifyPublicResponse(canonical(changed), request), false, label);
  }
  const missing = structuredClone(expected); drop((missing.observations as JsonObject[])[0]!, 'policyReassessment');
  assert.equal(verifyPublicResponse(canonical(missing), request), false);
  const extra = structuredClone(expected); Object.assign((extra.observations as JsonObject[])[0]!.policyReassessment as object, { fullPolicyDocument: 'not-public' });
  assert.equal(verifyPublicResponse(canonical(extra), request), false);
  assert.equal(verifyPublicResponse(canonical(expected), request), true); // Original request is unchanged.
});

test('rehashing a malformed policy context cannot hide kind/identity/lossless-reference inconsistencies', () => {
  const host = binding(registration()), item = prepareDiagnosticContext(policyEvent(), host, S1);
  const cases: [string, (body: JsonObject) => void][] = [
    ['wrong kind', body => { body.kind = 'terminal'; }],
    ['wrong client', body => { body.clientId = 'client-b'; }],
    ['wrong generation', body => { body.generation = 2; }],
    ['wrong event sequence', body => { body.eventSequence = 3; }],
    ['wrong event ID', body => { body.eventId = 'other-event'; }],
    ['wrong review', body => { body.reviewId = 'other-review'; }],
    ['wrong patch', body => { body.patchId = 'other-patch'; }],
    ['wrong snapshot', body => { body.sourceSnapshotId = S2; }],
    ['not lossless', body => { body.payloadDigest = hash('f'); (body.originalPayloadRef as JsonObject).payloadDigest = hash('f'); }],
    ['original reference client', body => { (body.originalPayloadRef as JsonObject).clientId = 'client-b'; }],
    ['original reference generation', body => { (body.originalPayloadRef as JsonObject).generation = 2; }],
    ['original reference bytes', body => { (body.originalPayloadRef as JsonObject).bytes = 1; }],
    ['legacy rather than projected', body => { drop(body, 'contextSchema'); }],
    ['lost original obligation', body => { body.findingObligations = []; }],
    ['MCP version inside event payload', body => {
      const payload = { ...completeView(), contractVersion: PREVIEW_CONTRACT }; body.diagnosticPayload = canonical(payload);
      body.diagnosticPayloadDigest = digest(body.diagnosticPayload as string); body.payloadDigest = body.diagnosticPayloadDigest;
      Object.assign(body.originalPayloadRef as object, { payloadDigest: body.payloadDigest, bytes: Buffer.byteLength(body.diagnosticPayload as string) });
    }],
    ['noncanonical event bytes', body => {
      body.diagnosticPayload = JSON.stringify(completeView(), null, 2); body.diagnosticPayloadDigest = digest(body.diagnosticPayload as string);
      body.payloadDigest = body.diagnosticPayloadDigest;
      Object.assign(body.originalPayloadRef as object, { payloadDigest: body.payloadDigest, bytes: Buffer.byteLength(body.diagnosticPayload as string) });
    }],
  ];
  for (const [label, mutate] of cases) {
    const changed = changedEnvelope(item, mutate);
    assert.throws(() => observationProjection(changed), error => error instanceof HostError, label);
    assert.equal(verifyPublicResponse('{}', requestFor([changed], host)), false, label);
  }
});

test('policy reflection binds the outer ContextItem generation even when all inner policy bytes agree', () => {
  const host = binding(registration('client-a', 2)), view = completeView();
  view.generation = 2; view.request.generation = 2;
  const item = prepareDiagnosticContext(policyEvent(reseal(view)), host, S1);
  const request = requestFor([item], host), expected = expectedPublicResponse(request);
  assert.equal(verifyPublicResponse(canonical(expected), request), true);
  assert.equal((observationProjection(item).policyReassessment as PolicyReassessmentView).generation, 2);
  const changed = structuredClone(item); changed.generation = 1; // The ONLY altered field.
  assert.equal(changed.text, item.text); assert.equal(changed.digest, item.digest);
  assert.deepEqual({ ...changed, generation: item.generation }, item);
  assert.throws(() => observationProjection(changed), hostCode('unsupported_diagnostic_context'));
  const changedRequest = requestFor([changed], host);
  // Rebind the response request digest so a stale outer response ID cannot cause this refusal.
  const reboundResponse = { ...expected, invocationId: changedRequest.invocationId, requestDigest: changedRequest.requestDigest };
  assert.equal(verifyPublicResponse(canonical(reboundResponse), changedRequest), false);
});

test('a lone required policy event is eligible under the unchanged owner observation budget predicate', () => {
  const policy = policyEvent(); assert.equal(requiresObservationAttempt(policy), true);
  const lonePending = [{ event: policy, observed: false }];
  assert.equal(lonePending.some(entry => requiresObservationAttempt(entry.event)), true);
  for (const kind of ['result', 'cancelled', 'terminal', 'policy_reassessed']) {
    assert.equal(requiresObservationAttempt({ kind, deliveryRequired: true }), true, kind);
    assert.equal(requiresObservationAttempt({ kind, deliveryRequired: false }), false, kind);
  }
  for (const kind of ['accepted', 'plan_accepted', 'other']) assert.equal(requiresObservationAttempt({ kind, deliveryRequired: true }), false, kind);
  // Pure shared predicate only: this test neither imports CLI main nor invokes a model controller loop.
});

test('policy client queries/replay do not mutate host snapshot/epoch/cut/inbox/receipt state', async t => {
  const { adapter, broker, kernel, journal, host, registered } = setup(t);
  await adapter.recordCompletedPatch({ patchId: 'current-patch', idempotencyKey: 'current-patch-key', input: { snapshot: { snapshotId: S2 } } });
  await adapter.prepareFinal(); const before = journal.read(), request = wire().request;
  const view = await broker.reassessPolicy(request);
  assert.deepEqual(await broker.reassessPolicy(request), view);
  assert.deepEqual(await broker.policyReassessmentStatus(request.idempotencyKey), view);
  assert.deepEqual(journal.read(), before); assert.equal(journal.read().currentSnapshotId, S2);
  assert.equal(journal.read().localEpoch, 1); assert.deepEqual(journal.read().cut, before.cut);
  assert.equal(Object.keys(journal.read().inbox).length, 0); assert.equal(kernel.eventsByClient.get(registered.client.clientId)!.length, 1);
  assert.equal(host.insertionCalls, 0); assert.equal(host.modelCalls, 0); assert.equal(kernel.acked.size, 0); assert.equal(kernel.observed.size, 0);
  assert.deepEqual(kernel.calls.slice(-3), [
    { method: 'review.policy.reassess', params: { request, credential: registered.client } },
    { method: 'review.policy.reassess', params: { request, credential: registered.client } },
    { method: 'review.policy.status', params: { idempotencyKey: request.idempotencyKey, credential: registered.client } },
  ]);
  assert.equal(view.sourceSnapshotId, S1); assert.equal(view.auditOnly, false); assert.equal(Object.hasOwn(view, 'allowed'), false);
  kernel.policyReply = () => { throw new BrokerError('policy_reassessment_currentness_unavailable', 'Synthetic stale policy'); };
  await assert.rejects(broker.reassessPolicy(request), error => error instanceof BrokerError && error.code === 'policy_reassessment_currentness_unavailable');
  assert.deepEqual(journal.read(), before); assert.equal(kernel.acked.size, 0); assert.equal(kernel.observed.size, 0);
});

test('KernelBroker binds both policy routes to trusted client/generation and rejects invalid input before RPC', async t => {
  const { broker, kernel, journal } = setup(t); const request = wire().request, before = journal.read();
  await assert.rejects(broker.reassessPolicy(Object.assign({ ...request }, { raw: {} })));
  await assert.rejects(broker.reassessPolicy({ ...request, generation: Number('9007199254740993') }));
  await assert.rejects(broker.policyReassessmentStatus(''));
  assert.equal(kernel.calls.length, 0);
  for (const mutate of [
    (view: PolicyReassessmentView) => { view.clientId = 'client-b'; },
    (view: PolicyReassessmentView) => { view.generation = 2; view.request.generation = 2; },
    (view: PolicyReassessmentView) => { view.request.idempotencyKey = 'other-key'; },
    (view: PolicyReassessmentView) => { view.generation = Number('9223372036854775807'); view.request.generation = view.generation; },
  ]) {
    const view = completeView(); mutate(view); kernel.policyReply = () => reseal(view);
    await assert.rejects(broker.reassessPolicy(request), invalidView);
    await assert.rejects(broker.policyReassessmentStatus(request.idempotencyKey), invalidView);
    assert.deepEqual(journal.read(), before);
  }
  assert.equal(kernel.acked.size, 0); assert.equal(kernel.observed.size, 0);
});

test('KernelBroker preserves complete ordinary/policy/mixed/empty pages and open ordinary kind/payload bytes', async t => {
  const { broker, kernel, journal, host, registered } = setup(t), before = journal.read();
  const ordinary = ordinaryEvent(registered), policy = policyEvent();
  ordinary.payload = '{ "state": "complete", "ordinaryExtension": true }'; ordinary.payloadDigest = digest(ordinary.payload);
  const pages = [eventPage([ordinary]), eventPage([policy]), eventPage([ordinary, policy], 'client-a:2', true),
    eventPage(['accepted', 'plan_accepted', 'progress', 'result', 'cancelled', 'future-public-kind'].map(kind =>
      ({ ...ordinary, eventId: 'ordinary-' + kind, kind, deliveryRequired: kind !== 'progress' })), 'client-a:6'),
    eventPage([], 'client-a:0')];
  for (const page of pages) {
    kernel.eventReply = () => page;
    const received = await broker.events(null, 10); assert.deepEqual(received, page);
    assert.deepEqual(received.events.map(event => event.payload), page.events.map(event => event.payload));
    assert.deepEqual(journal.read(), before); // Reading is not committing, inserting or ACKing.
  }
  assert.deepEqual(kernel.calls, pages.map(() => ({ method: 'review.events', params: { cursor: null, limit: 10, credential: registered.client } })));
  assert.equal(host.insertionCalls, 0); assert.equal(host.modelCalls, 0); assert.equal(kernel.acked.size, 0); assert.equal(kernel.observed.size, 0);
});

test('malformed EventPage wrappers and ordinary entry metadata cannot partly commit or advance an existing inbox cursor', async t => {
  const { adapter, kernel, journal, host, registered } = setup(t);
  kernel.eventsByClient.set(registered.client.clientId, [ordinaryEvent(registered, 'event-before')]);
  await adapter.pollOnce(); const before = journal.read(); assert.equal(before.fetchCursor, 'client-a:1');
  const cases: [string, (page: JsonObject) => void][] = [
    ['unknown policy document beside valid policy event', page => { page.policyDocument = 'PRIVATE-DOCUMENT'; }],
    ['unknown top-level capture', page => { page.capture = { policyDocument: 'PRIVATE-DOCUMENT' }; }],
    ['wrong page version', page => { page.contractVersion = 'ktchecker/not-this-contract'; }],
    ['null page version', page => { page.contractVersion = null; }],
  ];
  for (const key of ['contractVersion', 'events', 'cursor', 'hasMore']) cases.push(['missing ' + key, page => drop(page, key)]);
  for (const value of [null, {}, 'PRIVATE-DOCUMENT', 1, true]) cases.push(['non-array events ' + JSON.stringify(value), page => { page.events = value; }]);
  for (const value of [null, {}, [], 1, true]) cases.push(['non-string cursor ' + JSON.stringify(value), page => { page.cursor = value; }]);
  for (const value of [null, {}, [], 'false', 0]) cases.push(['non-boolean hasMore ' + JSON.stringify(value), page => { page.hasMore = value; }]);
  const ordinary = ordinaryEvent(registered, 'ordinary-bad', S1, 3);
  const badEntries: [string, unknown][] = [['null entry', null], ['array entry', []], ['string entry', 'PRIVATE-DOCUMENT'],
    ['minimal carrier', { kind: 'terminal', payload: ordinary.payload }],
    ['unknown event policy document', { ...ordinary, policyDocument: 'PRIVATE-DOCUMENT' }],
    ['unknown event capture', { ...ordinary, capture: { policyDocument: 'PRIVATE-DOCUMENT' } }],
    ['sibling ordinary client', { ...ordinary, clientId: 'client-b' }],
    ['ordinary registration generation mismatch', { ...ordinary, generation: 2 }],
    ['wrong ordinary event version', { ...ordinary, contractVersion: 'ktchecker/not-this-contract' }],
    ['wrong deliveryRequired type', { ...ordinary, deliveryRequired: 1 }]];
  for (const key of Object.keys(ordinary)) { const bad = { ...ordinary }; drop(bad, key); badEntries.push(['missing ordinary ' + key, bad]); }
  for (const key of ['eventId', 'clientId', 'reviewId', 'patchId', 'sourceSnapshotId', 'kind', 'payloadDigest', 'payload']) {
    for (const value of [null, 1, {}, [], true]) badEntries.push(['wrong ordinary type ' + key + ':' + JSON.stringify(value), { ...ordinary, [key]: value }]);
  }
  for (const key of ['sequence', 'generation']) {
    for (const value of [null, '1', 1.5, Number.MAX_SAFE_INTEGER + 1, Number.MIN_SAFE_INTEGER - 1]) {
      badEntries.push(['unsafe/noninteger ordinary ' + key + ':' + JSON.stringify(value), { ...ordinary, [key]: value }]);
    }
  }
  for (const [label, bad] of badEntries) cases.push([label, page => { page.events = [policyEvent(), bad, ordinaryEvent(registered, 'event-next', S1, 2)]; }]);
  for (const [label, mutate] of cases) {
    const page: JsonObject = eventPage([ordinaryEvent(registered, 'event-next', S1, 2), policyEvent()], 'PRIVATE-CURSOR', true);
    mutate(page); kernel.eventReply = () => page; const callCount = kernel.calls.length;
    await assert.rejects(adapter.pollOnce(), hostCode('unsupported_event_payload'), label);
    assert.deepEqual(journal.read(), before, label); assert.equal(kernel.calls.length, callCount + 1, label);
    assert.deepEqual(kernel.calls.at(-1), { method: 'review.events', params: { cursor: before.fetchCursor, limit: 100, credential: registered.client } }, label);
    assert.equal(JSON.stringify(journal.read()).includes('PRIVATE-DOCUMENT'), false, label);
    assert.equal(JSON.stringify(journal.read()).includes('PRIVATE-CURSOR'), false, label);
    assert.equal(host.insertionCalls, 0, label); assert.equal(host.modelCalls, 0, label);
    assert.equal(kernel.acked.size, 0, label); assert.equal(kernel.observed.size, 0, label);
  }
  const nonObjects = [null, [], 1, true, 'PRIVATE-DOCUMENT'];
  for (const page of nonObjects) {
    kernel.eventReply = () => page; const callCount = kernel.calls.length;
    await assert.rejects(adapter.pollOnce(), hostCode('unsupported_event_payload'));
    assert.deepEqual(journal.read(), before); assert.equal(kernel.calls.length, callCount + 1);
  }
  kernel.eventReply = () => eventPage([ordinaryEvent(registered, 'event-next', S1, 2), policyEvent()], 'client-a:3');
  assert.equal(await adapter.pollOnce(), false); assert.equal(journal.read().fetchCursor, 'client-a:3');
  assert.equal(Object.keys(journal.read().inbox).length, 3);
  assert.equal(journal.readPayload(policyEvent().eventId, policyEvent().payloadDigest), policyEvent().payload);
  assert.equal(kernel.calls.every(call => call.method === 'review.events'), true); // No retries, insertion, ACK or observe.
  assert.equal(kernel.calls.length, cases.length + nonObjects.length + 2);
});

test('malformed/nonrequired/misbound policy pages fail before ANY inbox/cursor commit, insertion or ACK', async t => {
  const { adapter, kernel, journal, host, registered } = setup(t), before = journal.read();
  const cases: [string, (event: ReviewEvent) => void][] = [
    ['wrong kind', event => { event.kind = 'result'; }],
    ['not required', event => { event.deliveryRequired = false; }],
    ['event client', event => { event.clientId = 'client-b'; }],
    ['event generation', event => { event.generation = 2; }],
    ['event review', event => { event.reviewId = 'other-review'; }],
    ['event patch', event => { event.patchId = 'other-patch'; }],
    ['event snapshot', event => { event.sourceSnapshotId = S2; }],
    ['event ID', event => { event.eventId = 'policy-' + hash('f'); }],
    ['event sequence', event => { event.sequence = 3; }],
    ['unsafe generation', event => { event.generation = Number('9007199254740993'); }],
    ['unsafe sequence', event => { event.sequence = Number('9223372036854775807'); }],
    ['wrong payload digest', event => { event.payloadDigest = hash('f'); }],
    ['wrong state', event => { const view = completeView(); Object.assign(view, { state: 'complete' }); event.payload = canonical(reseal(view)); event.payloadDigest = digest(event.payload); }],
    ['wrong format', event => { const view = completeView(); Object.assign(view, { format: 'normal-review' }); event.payload = canonical(reseal(view)); event.payloadDigest = digest(event.payload); }],
    ['unknown capture document', event => { const view = completeView(); Object.assign(view.capture, { policyDocument: 'private-only' }); event.payload = canonical(reseal(view)); event.payloadDigest = digest(event.payload); }],
    ['MCP version in event', event => { event.payload = canonical({ ...completeView(), contractVersion: PREVIEW_CONTRACT }); event.payloadDigest = digest(event.payload); }],
    ['noncanonical bytes', event => { event.payload = JSON.stringify(JSON.parse(event.payload), null, 2); event.payloadDigest = digest(event.payload); }],
    ['invalid JSON', event => { event.payload = '{'; event.payloadDigest = digest(event.payload); }],
  ];
  for (const [label, mutate] of cases) {
    const bad = policyEvent(); mutate(bad);
    assert.throws(() => policyReassessmentEventView(bad), hostCode('unsupported_event_payload'), label);
    kernel.eventsByClient.set(registered.client.clientId, [ordinaryEvent(registered), bad]);
    await assert.rejects(adapter.pollOnce(), hostCode('unsupported_event_payload'), label);
    assert.deepEqual(journal.read(), before, label); assert.equal(host.insertionCalls, 0, label);
    assert.equal(host.modelCalls, 0, label); assert.equal(kernel.acked.size, 0, label); assert.equal(kernel.observed.size, 0, label);
  }
  // An ordinary-only page retains its original validation/projection path.
  kernel.eventsByClient.set(registered.client.clientId, [ordinaryEvent(registered)]);
  await adapter.pollOnce(); assert.equal(Object.keys(journal.read().inbox).length, 1);
});

test('real adapter/journal synthetic flow needs insertion then adapter ACK then matching witness before observation', async t => {
  const { adapter, broker, kernel, journal, host } = setup(t);
  await adapter.recordCompletedPatch({ patchId: 'new-patch', idempotencyKey: 'new-patch-key', input: { snapshot: { snapshotId: S2 } } });
  const view = await broker.reassessPolicy(wire().request), id = view.viewId;
  assert.equal(view.result.status, 'complete'); assert.equal(view.result.coverage.length, 1); assert.equal(view.auditOnly, false);
  assert.equal(host.modelCalls, 0); assert.equal(kernel.observed.size, 0);
  await adapter.pollOnce(); assert.equal(journal.read().inbox[id]!.inserted, null);
  assert.equal(adapter.mutationFence().allowed, false); assert.equal(kernel.acked.size, 0);
  await adapter.drainContext(); const entry = journal.read().inbox[id]!;
  assert(entry.inserted); assert.equal(entry.acknowledged, true); assert.equal(entry.observed, false); assert.equal(entry.observation, null);
  assert.equal(entry.stale, true); assert.equal(envelope(entry.item!).freshnessAtPreparation, 'stale');
  assert.equal(journal.readPayload(id, entry.event.payloadDigest), entry.event.payload);
  assert.deepEqual(kernel.timeline, ['insert', 'ack']); assert.equal(host.modelCalls, 0);
  const cut = await adapter.prepareFinal(); const denied = await adapter.completeFinal(cut.cutId);
  assert.equal(denied.allowed, false); assert.deepEqual(denied.reasons, ['required_context_not_observed']);
  assert.equal(kernel.calls.filter(call => call.method === 'gate.complete').length, 0);
  assert.deepEqual(await broker.policyReassessmentStatus(view.request.idempotencyKey), view);
  assert.equal(journal.read().inbox[id]!.observed, false);
  host.mode = 'protected-fixture'; // Actual-shaped SPI only: NOT a real model or authenticated host.
  assert.deepEqual(await adapter.requestModelObservation(), { observed: true, reason: null });
  assert.deepEqual(kernel.timeline, ['insert', 'ack', 'invoke', 'observe']); assert.equal(host.requests.length, 1);
  const request = host.requests[0]!;
  assert.equal(request.contexts.length, 1); assert.equal(envelope(request.contexts[0]!).diagnosticPayload, entry.event.payload);
  assert.equal(verifyPublicResponse(canonical(expectedPublicResponse(request)), request), true);
  assert.equal(journal.read().inbox[id]!.observed, true); assert.equal(kernel.observed.has(id), true);
  const receiptCall = kernel.calls.find(call => call.method === 'review.observe')!;
  assert.deepEqual(receiptCall.params.credential, broker.registration.adapter);
  assert.equal(JSON.stringify(request).includes(broker.registration.client.token), false);
  assert.equal(JSON.stringify(request).includes(broker.registration.adapter.token), false);
  // Fixture gate is delivery bookkeeping only, not production completion/source authority.
  const syntheticGate = await adapter.completeFinal(cut.cutId);
  assert.equal(syntheticGate.allowed, true); assert.equal(syntheticGate.actionPerformed, false);
});

test('unavailable/insertion-only/test-only policy witnesses leave required delivery unobserved and local final fence closed', async t => {
  for (const mode of ['unavailable', 'insertion-only', 'test-only'] as const) {
    const { adapter, broker, kernel, journal, host } = setup(t); host.mode = mode;
    const view = await broker.reassessPolicy(wire().request); await adapter.pollOnce();
    const result = await adapter.requestModelObservation(); assert.equal(result.observed, false, mode);
    const entry = journal.read().inbox[view.viewId]!;
    assert.equal(entry.acknowledged, true, mode); assert.equal(entry.observation, null, mode); assert.equal(entry.observed, false, mode);
    assert.equal(kernel.observed.size, 0, mode); assert.equal(host.modelCalls, 1, mode);
    const cut = await adapter.prepareFinal(), denied = await adapter.completeFinal(cut.cutId);
    assert.equal(denied.allowed, false, mode); assert.equal(adapter.mutationFence().allowed, false, mode);
    assert.equal(kernel.calls.some(call => call.method === 'gate.complete'), false, mode);
  }
});

test('an actual-shaped but wrong-request policy witness cannot create observation authority', async t => {
  const { adapter, broker, kernel, journal, host } = setup(t);
  const view = await broker.reassessPolicy(wire().request); await adapter.pollOnce();
  host.mode = 'protected-fixture'; host.wrongRequest = true;
  await assert.rejects(adapter.requestModelObservation(), hostCode('model_request_proof_mismatch'));
  assert.equal(journal.read().inbox[view.viewId]!.acknowledged, true);
  assert.equal(journal.read().inbox[view.viewId]!.observation, null); assert.equal(journal.read().inbox[view.viewId]!.observed, false);
  assert.equal(kernel.calls.some(call => call.method === 'review.observe'), false); assert.equal(adapter.mutationFence().allowed, false);
});

test('oversized required policy view remains durable but uninserted/unACKed/unobserved, never summarized or truncated', async t => {
  const { adapter, kernel, journal, host, registered } = setup(t);
  const view = completeView(); view.result.findings[0]!.diagnosis = 'x'.repeat(40_000);
  const event = policyEvent(reseal(view)); kernel.eventsByClient.set(registered.client.clientId, [event]);
  await adapter.pollOnce(); assert.equal(journal.read().fetchCursor, 'client-a:1');
  assert.equal(journal.readPayload(event.eventId, event.payloadDigest), event.payload);
  assert(Buffer.byteLength(prepareDiagnosticContext(event, host.binding, null).text, 'utf8') > 32_768);
  await assert.rejects(adapter.drainContext(), hostCode('required_context_exceeds_budget'));
  await assert.rejects(adapter.requestModelObservation(), hostCode('required_context_exceeds_budget'));
  await assert.rejects(adapter.prepareFinal(), hostCode('required_context_exceeds_budget'));
  const entry = journal.read().inbox[event.eventId]!;
  assert.equal(entry.item, null); assert.equal(entry.insertionReceiptId, null); assert.equal(entry.inserted, null);
  assert.equal(entry.acknowledged, false); assert.equal(entry.observation, null); assert.equal(entry.observed, false);
  assert.equal(entry.event.payload, event.payload); assert.equal(journal.readPayload(event.eventId, event.payloadDigest), event.payload);
  assert.equal(host.insertionCalls, 0); assert.equal(host.modelCalls, 0); assert.equal(kernel.acked.size, 0); assert.equal(kernel.observed.size, 0);
  assert.equal(adapter.mutationFence().allowed, false); assert.equal(kernel.calls.some(call => call.method.startsWith('gate.')), false);
});

test('policy observation batches preserve full views and oldest obligations under the existing aggregate request budget', async t => {
  const { adapter, kernel, journal, host, registered } = setup(t); host.mode = 'protected-fixture';
  const first = completeView(); first.result.findings[0]!.diagnosis = 'x'.repeat(14_500);
  const second = structuredClone(first); second.viewId = 'policy-' + hash('2'); second.sequence = 8; second.request.idempotencyKey = 'policy-request-2';
  const events = [policyEvent(reseal(first)), policyEvent(reseal(second))];
  const items = events.map(event => prepareDiagnosticContext(event, host.binding, null));
  assert(items.every(item => Buffer.byteLength(item.text, 'utf8') <= 32_768));
  assert(items.reduce((sum, item) => sum + Buffer.byteLength(item.text, 'utf8'), 0) > 32_768);
  kernel.eventsByClient.set(registered.client.clientId, events); await adapter.pollOnce(); await adapter.drainContext();
  assert.equal(host.insertionCalls, 2); assert.equal(kernel.acked.size, 2); assert.equal(host.modelCalls, 0);
  assert.equal((await adapter.requestModelObservation()).observed, true);
  assert.equal(host.requests[0]!.contexts.length, 1); assert.equal(envelope(host.requests[0]!.contexts[0]!).eventId, events[0]!.eventId);
  assert.equal(journal.read().inbox[events[0]!.eventId]!.observed, true); assert.equal(journal.read().inbox[events[1]!.eventId]!.observed, false);
  assert.equal(adapter.mutationFence().allowed, false);
  const cut = await adapter.prepareFinal(); assert.equal((await adapter.completeFinal(cut.cutId)).allowed, false);
  assert.equal(kernel.calls.some(call => call.method === 'gate.complete'), false);
  // A second explicit owner-budgeted call, not an automatic budget increase or a skipped obligation.
  assert.equal((await adapter.requestModelObservation()).observed, true); assert.equal(host.requests.length, 2);
  for (const request of host.requests) {
    assert(request.contexts.reduce((sum, item) => sum + Buffer.byteLength(item.text, 'utf8'), 0) <= 32_768);
    assert.equal(request.contexts.length, 1);
    const body = envelope(request.contexts[0]!); const original = events.find(event => event.eventId === body.eventId)!;
    assert.equal(body.diagnosticPayload, original.payload); assert.equal(body.diagnosticPayloadDigest, original.payloadDigest);
  }
  assert.equal(journal.read().inbox[events[1]!.eventId]!.observed, true); assert.equal(kernel.observed.size, 2);
});

test('sibling client queries/events/ACKs/observations remain isolated despite policy fixture content reuse', async t => {
  const a = registration('client-a'), b = registration('client-b'), kernel = new PolicyKernel(a, b);
  const A = setup(t, {}, a, kernel), B = setup(t, {}, b, kernel);
  const va = await A.broker.reassessPolicy(wire().request);
  const vb = await B.broker.reassessPolicy({ ...wire().request, idempotencyKey: 'client-b-policy-request' });
  assert.notEqual(va.viewId, vb.viewId); assert.equal(vb.clientId, 'client-b');
  await assert.rejects(A.broker.policyReassessmentStatus(vb.request.idempotencyKey), error => error instanceof BrokerError && error.code === 'policy_reassessment_not_found');
  await A.adapter.pollOnce(); await B.adapter.pollOnce(); await A.adapter.drainContext(); await B.adapter.drainContext();
  const receiptA = A.journal.read().inbox[va.viewId]!.inserted!.receipt, receiptB = B.journal.read().inbox[vb.viewId]!.inserted!.receipt;
  await assert.rejects(A.broker.ack([vb.viewId], receiptA), hostCode('cross_consumer_receipt'));
  await assert.rejects(A.broker.observe([vb.viewId], { observationId: 'synthetic-cross-client', modelRequestId: 'not-authority', contextDigests: [receiptA.contextDigest] }), hostCode('cross_consumer_receipt'));
  assert.deepEqual(kernel.acked.get(vb.viewId), receiptB);
  assert.equal(B.journal.read().inbox[vb.viewId]!.observation, null); assert.equal(kernel.observed.has(vb.viewId), false);
  A.host.mode = 'protected-fixture'; assert.equal((await A.adapter.requestModelObservation()).observed, true);
  assert.equal(A.journal.read().inbox[va.viewId]!.observed, true); assert.equal(B.journal.read().inbox[vb.viewId]!.observed, false);
  assert.equal(kernel.observed.has(va.viewId), true); assert.equal(kernel.observed.has(vb.viewId), false);
  const before = A.journal.read(); kernel.eventsByClient.get(a.client.clientId)!.push(policyEvent(vb));
  await assert.rejects(A.adapter.pollOnce(), hostCode('unsupported_event_payload'));
  assert.deepEqual(A.journal.read(), before); assert.equal(Object.hasOwn(A.journal.read().inbox, vb.viewId), false);
  assert.equal(B.host.modelCalls, 0); assert.equal(B.adapter.mutationFence().allowed, false);
});
