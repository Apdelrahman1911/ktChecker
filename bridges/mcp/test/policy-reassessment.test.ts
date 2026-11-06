// Authored SOURCE controls only: no test/SDK/Kotlin execution claimed by this proposal.
import { test, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { Client, InMemoryTransport } from '@modelcontextprotocol/client';
import { bridgeConfigSchema, serveBridge, FINDING_WIRE_CAPABILITY, MCP_PROFILE, parsePolicyReassessmentView, checkedPolicyReassessmentView,
  checkedPolicyReassessmentEvent, checkedReviewEventPage, policyReassessmentRequestSchema, policyReassessmentStatusSchema,
  type PolicyReassessmentRequest, type PolicyReassessmentView } from '../src/server.js';
import { BrokerError, FINDING_WIRE_PROFILE, PREVIEW_CONTRACT, type BrokerPort, type JsonObject } from '../src/rpc.js';

// Hand-authored ASCII wire control, NOT Kotlin-emitted or an admitted/publishable kernel view.
// Its incomplete/unknown coverage deliberately exercises lossless nonpromotion. The structurally
// complete synthetic routing control is derived separately below, still without semantic authority.
// PolicyReassessmentTypes.kt:6389B/3555de1e...; sealed emitter:25344B/75449b4c... .
const SOURCE_WIRE = String.raw`{"auditOnly":true,"capture":{"analysisAuthorityDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","currentCaptureId":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","currentPolicyDigest":"sha256:2222222222222222222222222222222222222222222222222222222222222222","format":"ktchecker/policy-reassessment-capture/1","originalCaptureId":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","originalPolicyDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},"certificateDigest":"sha256:f6ff5ab242366464c985d7c9ce48e1ff97aad654186a882fcf97ee0bd3d07881","certified":{"effectiveInputKey":"sha256:5555555555555555555555555555555555555555555555555555555555555555","inputKey":"sha256:3333333333333333333333333333333333333333333333333333333333333333","originalEffectiveResultDigest":"sha256:9999999999999999999999999999999999999999999999999999999999999999","originalPolicyCertificateDigest":"sha256:7777777777777777777777777777777777777777777777777777777777777777","rawResultDigest":"sha256:8888888888888888888888888888888888888888888888888888888888888888","reviewSequence":3,"subjectDigest":"sha256:0000000000000000000000000000000000000000000000000000000000000000","workCertificateId":"sha256:6666666666666666666666666666666666666666666666666666666666666666","workKey":"sha256:4444444444444444444444444444444444444444444444444444444444444444"},"clientId":"client-a","evaluatorIdentity":"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","format":"ktchecker/policy-reassessment-view/1","generation":1,"issuerIdentity":"sha256:0000000000000000000000000000000000000000000000000000000000000000","patchId":"patch-original","reasons":["source_scope_incomplete"],"request":{"expectedPolicyDigest":"sha256:2222222222222222222222222222222222222222222222222222222222222222","generation":1,"idempotencyKey":"policy-request-1","reviewId":"review-original"},"result":{"coverage":[{"certificateId":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","context":{"compilation":"main","module":":main","sourceSet":"main","target":"jvm"},"depth":"build","implemented":true,"provenance":"executed","qualified":true,"reason":null,"required":true,"ruleId":"fixture.nullability","state":"findings","tested":true},{"certificateId":null,"context":{"compilation":"main","module":":main","sourceSet":"main","target":"jvm"},"depth":"build","implemented":false,"provenance":"not_executed","qualified":false,"reason":"Original unknown coverage is not promoted by policy.","required":true,"ruleId":"fixture.unknown","state":"unknown","tested":false}],"findings":[{"certainty":"medium","context":{"compilation":"main","module":":main","sourceSet":"main","target":"jvm"},"diagnosis":"Nullable name cannot be assigned to String.","disposition":"accepted","evidence":[{"artifactRef":null,"digest":null,"kind":"source","summary":"Original source excerpt."},{"artifactRef":"artifact:diagnostic-1","digest":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","kind":"compiler","summary":"Original compiler message."}],"fingerprint":"sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","fixHint":null,"kind":"probable_defect","location":{"column":34,"endColumn":38,"endLine":1,"excerpt":"val label: String = nullable","fileDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","line":1,"path":"Label.kt"},"providerId":"fixture-provider","relatedLocations":[{"column":0,"endColumn":0,"endLine":2,"excerpt":"","fileDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","line":2,"path":"Usage.kt"}],"ruleId":"fixture.nullability","semanticDigest":null,"severity":"warning","symbolId":null,"title":"Nullable assignment","upstreamId":null,"verification":[{"command":[],"expected":"Compile the same immutable snapshot.","kind":"recheck","state":"not_run"},{"command":["compiler","--analysis-only"],"expected":"No mismatch in the target.","kind":"compile","state":"not_run"}],"whyItMatters":"A nullable value is not a non-null String."}],"metrics":{"cpuMillis":null,"elapsedMillis":5,"outputBytes":0,"peakRssBytes":null},"providerId":"fixture-provider","providerVersion":"preview","rawDiagnostics":[{"location":null,"message":"Original compiler output.","severity":"error","upstreamId":null},{"location":{"column":34,"endColumn":38,"endLine":1,"excerpt":"val label: String = nullable","fileDigest":"sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","line":1,"path":"Label.kt"},"message":"At the original location.","severity":"warning","upstreamId":"UP-1"}],"reasons":["original_execution_incomplete"],"status":"incomplete"},"reviewId":"review-original","sequence":7,"sourceSnapshotId":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","state":"policy_reassessed","viewId":"policy-sha256:1111111111111111111111111111111111111111111111111111111111111111"}`;
const SOURCE_WIRE_DIGEST = 'sha256:58156bf9312cf4fefbfa4e272aa069ba8b55f342c53053d94f3791ccffb14535';
const SOURCE_CERTIFICATE = 'sha256:f6ff5ab242366464c985d7c9ce48e1ff97aad654186a882fcf97ee0bd3d07881';
const credential = { clientId: 'client-a', token: 'private-client-fixture-capability-never-model-output' };
const hash = (character: string) => 'sha256:' + character.repeat(64);
function sha(text: string): string { return 'sha256:' + createHash('sha256').update(text, 'utf8').digest('hex'); }
// Independent fixture-side algebra, not a call to the production certificate implementation.
function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value !== null && typeof value === 'object') return '{' + Object.keys(value).sort().map(key =>
    JSON.stringify(key) + ':' + canonical((value as JsonObject)[key])).join(',') + '}';
  const text = JSON.stringify(value); if (text === undefined) throw new Error('fixture_non_json'); return text;
}
function wire(): PolicyReassessmentView { return JSON.parse(SOURCE_WIRE) as PolicyReassessmentView; }
function reseal(view: PolicyReassessmentView): PolicyReassessmentView {
  const copy = structuredClone(view); delete copy.contractVersion;
  copy.certificateDigest = ''; copy.certificateDigest = sha(canonical(copy)); return copy;
}
function completeView(): PolicyReassessmentView {
  const view = wire(); view.result.status = 'complete'; view.result.coverage = [view.result.coverage[0]!];
  view.result.reasons = []; view.auditOnly = false; view.reasons = []; return reseal(view);
}
function drop(value: object, key: string): void { delete (value as JsonObject)[key]; }
function eventOf(view = completeView()) {
  const payload = canonical(view);
  return { contractVersion: PREVIEW_CONTRACT, eventId: view.viewId, clientId: view.clientId, sequence: view.sequence,
    reviewId: view.reviewId, patchId: view.patchId, sourceSnapshotId: view.sourceSnapshotId,
    kind: 'policy_reassessed', deliveryRequired: true, payloadDigest: sha(payload), payload, generation: view.generation };
}
function ordinaryEvent(kind = 'terminal') {
  // Full ordinary Kotlin ReviewEvent metadata, not the invalid {kind,payload} carrier.
  // Whitespace and extension data inside the ordinary payload are deliberately opaque.
  const payload = '{ "state": "complete", "ordinaryExtension": true }';
  return { contractVersion: PREVIEW_CONTRACT, eventId: 'ordinary-' + kind, clientId: credential.clientId, sequence: 1,
    reviewId: 'ordinary-review', patchId: 'ordinary-patch', sourceSnapshotId: hash('a'), kind,
    deliveryRequired: kind !== 'progress', payloadDigest: sha(payload), payload, generation: 1 };
}
function pageOf(event = eventOf()): JsonObject {
  return { contractVersion: PREVIEW_CONTRACT, events: [event], cursor: 'client-a:1', hasMore: false };
}
function application(result: unknown): JsonObject {
  const body = (result as { structuredContent?: unknown }).structuredContent;
  assert(body !== null && typeof body === 'object' && !Array.isArray(body)); return body as JsonObject;
}
function codeOf(result: unknown): unknown { return (application(result).error as JsonObject).code; }
const invalidView = (error: unknown) => error instanceof BrokerError && error.code === 'invalid_policy_reassessment_view';

/** In-memory routing double, NOT kernel evaluation/execution/currentness proof. */
class PolicyPort implements BrokerPort {
  readonly calls: { method: string; params: JsonObject }[] = [];
  readonly stored = new Map<string, PolicyReassessmentView>();
  published = 0;
  reply: ((method: string, params: JsonObject) => unknown) | null = null;
  async request<T = JsonObject>(method: string, params: JsonObject): Promise<T> {
    this.calls.push({ method, params: structuredClone(params) });
    assert.deepEqual(params.credential, credential);
    if (this.reply) return structuredClone(this.reply(method, params)) as T;
    if (method === 'review.policy.reassess') {
      const request = structuredClone(params.request) as PolicyReassessmentRequest;
      const prior = this.stored.get(request.idempotencyKey);
      if (prior && canonical(prior.request) !== canonical(request)) {
        throw new BrokerError('policy_reassessment_idempotency_conflict', 'Private fixture conflict details must not escape');
      }
      if (prior) return structuredClone(prior) as T;
      const view = completeView(); view.request = request; view.reviewId = request.reviewId; view.generation = request.generation;
      view.capture.currentPolicyDigest = request.expectedPolicyDigest;
      const stored = reseal(view); this.stored.set(request.idempotencyKey, stored); this.published++;
      return structuredClone(stored) as T;
    }
    if (method === 'review.policy.status') {
      const stored = this.stored.get(String(params.idempotencyKey));
      if (!stored) throw new BrokerError('policy_reassessment_not_found', 'Private fixture path must not escape');
      return structuredClone(stored) as T;
    }
    throw new BrokerError('unexpected_fixture_method', 'No execution, receipt or gate method belongs to this fixture');
  }
}
async function setup(t: TestContext, port = new PolicyPort()) {
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  // Use the production era dispatcher: a bare McpServer.connect() does not install
  // SDK 2.4.0's modern server/discover handler or select its current-profile codec.
  const server = serveBridge(port,
    bridgeConfigSchema.parse({ broker: { command: 'unused-in-memory', args: [] }, credential }),
    { transport: serverTransport });
  const client = new Client({ name: 'ktchecker-policy-wire-source-control', version: '0.1.0' }, {
    versionNegotiation: { mode: { pin: MCP_PROFILE } },
    // The pinned SDK emits this declaration in every current request's envelope.
    capabilities: { experimental: { [FINDING_WIRE_CAPABILITY]: { profile: FINDING_WIRE_PROFILE } } },
  });
  t.after(async () => { try { await server.close(); } finally { await client.close(); } });
  await client.connect(clientTransport);
  assert.equal(client.getProtocolEra(), 'modern');
  return { client, port };
}

test('SOURCE-authored literal preserves explicit defaults/nulls and different original/event sequences', () => {
  assert.equal(Buffer.byteLength(SOURCE_WIRE, 'utf8'), 4997);
  assert.equal(sha(SOURCE_WIRE), SOURCE_WIRE_DIGEST);
  const view = wire(); assert.equal(view.certificateDigest, SOURCE_CERTIFICATE);
  assert.equal(sha(canonical({ ...view, certificateDigest: '' })), SOURCE_CERTIFICATE);
  assert.deepEqual(parsePolicyReassessmentView(JSON.parse(SOURCE_WIRE)), view);
  assert.deepEqual(checkedPolicyReassessmentView(view, credential.clientId, view.request, 1), view);
  assert.equal(view.sequence, 7); assert.equal(view.certified.reviewSequence, 3);
  assert.notEqual(view.sequence, view.certified.reviewSequence);
  assert.equal(view.result.status, 'incomplete'); assert.equal(view.result.coverage[1]!.state, 'unknown');
  assert.equal(view.result.coverage[1]!.qualified, false); assert.equal(view.result.metrics.cpuMillis, null);
  assert.equal(view.result.findings[0]!.fixHint, null); assert.equal(view.result.rawDiagnostics[0]!.location, null);
  assert.deepEqual(view.result.findings[0]!.verification[0]!.command, []);
  assert.equal(Object.hasOwn(view, 'contractVersion'), false);
  assert.deepEqual(parsePolicyReassessmentView({ ...view, contractVersion: PREVIEW_CONTRACT }), { ...view, contractVersion: PREVIEW_CONTRACT });
  const aba = wire(); aba.capture.currentPolicyDigest = aba.capture.originalPolicyDigest;
  aba.request.expectedPolicyDigest = aba.capture.originalPolicyDigest;
  assert.deepEqual(parsePolicyReassessmentView(reseal(aba)), reseal(aba)); // A later ABA view may revisit the original policy.
});

test('official in-memory SDK discovers exactly the two constrained policy tools and no authority tools', async t => {
  const { client, port } = await setup(t); const listed = (await client.listTools()).tools;
  assert.deepEqual(listed.map(tool => tool.name).sort(),
    ['review.cancel', 'review.events', 'review.policy.reassess', 'review.policy.status', 'review.status', 'review.submit']);
  const reassess = listed.find(tool => tool.name === 'review.policy.reassess')!;
  const status = listed.find(tool => tool.name === 'review.policy.status')!;
  assert.deepEqual(Object.keys(reassess.inputSchema.properties ?? {}).sort(), ['expectedPolicyDigest', 'generation', 'idempotencyKey', 'reviewId']);
  assert.deepEqual([...(reassess.inputSchema.required ?? [])].sort(), ['expectedPolicyDigest', 'generation', 'idempotencyKey', 'reviewId']);
  assert.equal(reassess.inputSchema.additionalProperties, false); assert.equal(status.inputSchema.additionalProperties, false);
  assert.deepEqual(Object.keys(status.inputSchema.properties ?? {}), ['idempotencyKey']);
  const generation = (reassess.inputSchema.properties as Record<string, JsonObject>).generation!;
  assert.equal(generation.type, 'integer'); assert.equal(generation.minimum, 1); assert.equal(generation.maximum, Number.MAX_SAFE_INTEGER);
  assert.equal(reassess.annotations?.readOnlyHint, false); assert.equal(reassess.annotations?.idempotentHint, true);
  assert.equal(reassess.annotations?.destructiveHint, false); assert.equal(status.annotations?.readOnlyHint, true);
  for (const forbidden of ['review.ack', 'review.observe', 'gate.prepare', 'gate.complete', 'policy.edit', 'execution.prepare', 'client.register']) {
    assert.equal(listed.some(tool => tool.name === forbidden), false, forbidden);
  }
  assert.equal(port.calls.length, 0);
});

test('nested reassess routing and flat status use configured client only; exact replay is immutable', async t => {
  const { client, port } = await setup(t); const request = wire().request;
  const first = await client.callTool({ name: 'review.policy.reassess', arguments: request });
  const replay = await client.callTool({ name: 'review.policy.reassess', arguments: request });
  const status = await client.callTool({ name: 'review.policy.status', arguments: { idempotencyKey: request.idempotencyKey } });
  assert.equal(first.isError, undefined); assert.equal(replay.isError, undefined); assert.equal(status.isError, undefined);
  assert.deepEqual(application(first), { ...completeView(), contractVersion: PREVIEW_CONTRACT });
  assert.deepEqual(application(replay), application(first)); assert.deepEqual(application(status), application(first));
  assert.deepEqual(port.calls, [
    { method: 'review.policy.reassess', params: { request, credential } },
    { method: 'review.policy.reassess', params: { request, credential } },
    { method: 'review.policy.status', params: { idempotencyKey: request.idempotencyKey, credential } },
  ]);
  assert.equal(port.published, 1); assert.equal(port.stored.size, 1);
  assert.equal((application(first) as unknown as PolicyReassessmentView).auditOnly, false); // Never an allow decision.
  assert.equal(Object.hasOwn(application(first), 'allowed'), false);
  assert.equal(JSON.stringify([first, replay, status]).includes(credential.token), false);
  const conflict = await client.callTool({ name: 'review.policy.reassess', arguments: { ...request, expectedPolicyDigest: hash('b') } });
  assert.equal(codeOf(conflict), 'policy_reassessment_idempotency_conflict'); assert.equal(port.published, 1);
});

test('strict policy requests reject evidence/config/receipt/credential authority and unsafe identity before RPC', async t => {
  const { client, port } = await setup(t); const request = wire().request;
  for (const key of ['action', 'input', 'execution', 'raw', 'evidence', 'certificate', 'policyDocument', 'policy', 'consent',
    'permit', 'receipt', 'credential', 'adapter', 'originalCapture', 'currentCapture']) {
    const result = await client.callTool({ name: 'review.policy.reassess', arguments: { ...request, [key]: { forbidden: 'fixture-data' } } });
    assert.equal(result.isError, true, key);
    const status = await client.callTool({ name: 'review.policy.status', arguments: { idempotencyKey: request.idempotencyKey, [key]: 'fixture-data' } });
    assert.equal(status.isError, true, key);
  }
  const invalid: JsonObject[] = [
    ...[0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, Number('9223372036854775807'), '1', null].map(generation => ({ ...request, generation })),
    ...['wrong', hash('A'), hash('1').slice(0, -1), ''].map(expectedPolicyDigest => ({ ...request, expectedPolicyDigest })),
    { ...request, reviewId: '' }, { ...request, idempotencyKey: 'x'.repeat(257) },
  ];
  for (const field of Object.keys(request)) { const missing: JsonObject = { ...request }; drop(missing, field); invalid.push(missing); }
  for (const arguments_ of invalid) {
    assert.equal(policyReassessmentRequestSchema.safeParse(arguments_).success, false);
    assert.equal((await client.callTool({ name: 'review.policy.reassess', arguments: arguments_ })).isError, true);
  }
  assert.equal(policyReassessmentStatusSchema.safeParse({ idempotencyKey: request.idempotencyKey, generation: 1 }).success, false);
  assert.equal((await client.callTool({ name: 'review.policy.status', arguments: { idempotencyKey: '' } })).isError, true);
  assert.equal((await client.callTool({ name: 'review.policy.status', arguments: { idempotencyKey: request.idempotencyKey, reviewId: request.reviewId } })).isError, true);
  assert.equal(port.calls.length, 0); assert.equal(port.published, 0); assert.equal(port.stored.size, 0);
});

test('not-found/stale/conflict failures preserve safe codes without private backend messages or retry', async t => {
  const { client, port } = await setup(t); const request = wire().request;
  const missing = await client.callTool({ name: 'review.policy.status', arguments: { idempotencyKey: request.idempotencyKey } });
  assert.equal(codeOf(missing), 'policy_reassessment_not_found');
  const codes = ['policy_reassessment_generation_mismatch', 'policy_reassessment_idempotency_conflict',
    'policy_reassessment_currentness_unavailable', 'policy_reassessment_capture_mismatch'];
  for (const code of codes) {
    port.reply = () => { throw new BrokerError(code, 'PRIVATE-CAPTURE-DOCUMENT ' + credential.token); };
    const result = await client.callTool({ name: 'review.policy.reassess', arguments: request });
    assert.equal(result.isError, true); assert.equal(codeOf(result), code);
    assert.equal(JSON.stringify(result).includes('PRIVATE-CAPTURE-DOCUMENT'), false);
    assert.equal(JSON.stringify(result).includes(credential.token), false);
  }
  port.reply = () => { throw new BrokerError('Unsafe/private-message', 'PRIVATE-CAPTURE-DOCUMENT'); };
  assert.equal(codeOf(await client.callTool({ name: 'review.policy.reassess', arguments: request })), 'bridge_failure');
  assert.equal(port.calls.length, codes.length + 2); assert.equal(port.published, 0);
});

test('both new tool outputs are closed full DTOs with exact request/client/default/null bindings', async t => {
  const { client, port } = await setup(t); const request = wire().request;
  const cases: [string, (view: PolicyReassessmentView) => void][] = [
    ['wrong client', view => { view.clientId = 'client-b'; }],
    ['wrong key', view => { view.request.idempotencyKey = 'another-request'; }],
    ['wrong nested review', view => { view.reviewId = 'another-review'; }],
    ['wrong nested generation', view => { view.generation = 2; }],
    ['wrong capture policy', view => { view.capture.currentPolicyDigest = hash('b'); }],
    ['wrong state', view => { Object.assign(view, { state: 'complete' }); }],
    ['wrong format', view => { Object.assign(view, { format: 'ktchecker/review-status/1' }); }],
    ['wrong view ID', view => { view.viewId = 'review-not-policy'; }],
    ['raw issuer name', view => { view.issuerIdentity = 'owner/name'; }],
    ['archive in capture', view => { Object.assign(view.capture, { fullPolicyDocument: 'PRIVATE-DOCUMENT' }); }],
    ['raw evidence field', view => { Object.assign(view, { suppliedRaw: {} }); }],
    ['extra result data', view => { Object.assign(view.result, { policyDocument: 'PRIVATE-DOCUMENT' }); }],
    ['missing explicit null', view => { drop(view.result.metrics, 'cpuMillis'); }],
    ['missing default', view => { drop(view.result.findings[0]!, 'disposition'); }],
    ['missing evidence null', view => { drop(view.result.findings[0]!.evidence[0]!, 'artifactRef'); }],
    ['unsafe event sequence', view => { view.sequence = Number.MAX_SAFE_INTEGER + 1; }],
    ['unsafe original sequence', view => { view.certified.reviewSequence = Number('9007199254740993'); }],
    ['unsafe elapsed metric', view => { view.result.metrics.elapsedMillis = Number('9223372036854775807'); }],
    ['unsafe negative metric', view => { view.result.metrics.outputBytes = Number.MIN_SAFE_INTEGER - 1; }],
    ['non-Int coordinate', view => { view.result.findings[0]!.location.line = 2_147_483_648; }],
  ];
  for (const [label, mutate] of cases) {
    const view = completeView(); mutate(view); port.reply = () => reseal(view);
    for (const name of ['review.policy.reassess', 'review.policy.status']) {
      const result = await client.callTool({ name, arguments: name.endsWith('reassess') ? request : { idempotencyKey: request.idempotencyKey } });
      assert.equal(result.isError, true, label); assert.equal(codeOf(result), 'invalid_policy_reassessment_view', label);
      assert.equal(Object.hasOwn(application(result), 'result'), false, label);
      assert.equal(JSON.stringify(result).includes('PRIVATE-DOCUMENT'), false, label);
    }
  }
  const altered = completeView(); altered.result.findings[0]!.disposition = 'suppressed';
  assert.throws(() => parsePolicyReassessmentView(altered), invalidView); // No checksum update: content mutation is rejected.
  port.reply = () => altered;
  assert.equal(codeOf(await client.callTool({ name: 'review.policy.status', arguments: { idempotencyKey: request.idempotencyKey } })), 'invalid_policy_reassessment_view');
  port.reply = () => ({ ...completeView(), contractVersion: 'different-contract' });
  assert.equal(codeOf(await client.callTool({ name: 'review.policy.reassess', arguments: request })), 'incompatible_broker_contract');
  assert.equal(port.published, 0);
});

test('MCP reassess binds all four supplied fields; historical status invents no absent trusted generation', async t => {
  const { client, port } = await setup(t); const request = wire().request;
  for (const mutate of [
    (view: PolicyReassessmentView) => { view.generation = 2; view.request.generation = 2; },
    (view: PolicyReassessmentView) => { view.reviewId = 'other-review'; view.request.reviewId = view.reviewId; },
    (view: PolicyReassessmentView) => { view.capture.currentPolicyDigest = hash('b'); view.request.expectedPolicyDigest = hash('b'); },
  ]) {
    const view = completeView(); mutate(view); const reply = reseal(view); port.reply = () => reply;
    assert.equal(codeOf(await client.callTool({ name: 'review.policy.reassess', arguments: request })), 'invalid_policy_reassessment_view');
    const status = await client.callTool({ name: 'review.policy.status', arguments: { idempotencyKey: request.idempotencyKey } });
    assert.equal(status.isError, undefined); assert.deepEqual(application(status), { ...reply, contractVersion: PREVIEW_CONTRACT });
  }
  assert.equal(port.published, 0); // Client/key validation is not a current-policy or current-snapshot claim.
});

test('MCP complete policy/ordinary/mixed/empty pages retain their exact shape and ordinary payload bytes', async t => {
  const { client, port } = await setup(t); const event = eventOf(), ordinary = ordinaryEvent();
  const pages: JsonObject[] = [
    pageOf(event), pageOf(ordinary),
    { contractVersion: PREVIEW_CONTRACT, events: [ordinary, event], cursor: 'client-a:2', hasMore: true },
    { contractVersion: PREVIEW_CONTRACT, events: ['accepted', 'plan_accepted', 'progress', 'result', 'cancelled', 'future-public-kind'].map(ordinaryEvent), cursor: 'client-a:6', hasMore: false },
    { contractVersion: PREVIEW_CONTRACT, events: [], cursor: 'client-a:0', hasMore: false },
  ];
  for (const page of pages) {
    port.reply = () => page;
    const result = await client.callTool({ name: 'review.events', arguments: {} });
    assert.equal(result.isError, undefined); assert.deepEqual(application(result), page);
    assert.deepEqual(result.content, [{ type: 'text', text: JSON.stringify(page) }]);
    assert.equal(checkedReviewEventPage(page, credential.clientId, 1), page); // Identity, not normalization.
  }
  assert.deepEqual(checkedPolicyReassessmentEvent(event, credential.clientId), completeView());
  assert.equal(checkedPolicyReassessmentEvent(ordinary, credential.clientId), null);
  assert.deepEqual(port.calls, pages.map(() => ({ method: 'review.events', params: { cursor: null, limit: 100, credential } })));
});

test('MCP rejects malformed EventPage wrappers whole without exposing private fields or advancing the requested cursor', async t => {
  const { client, port } = await setup(t), cursor = 'client-a:before-rejected-page';
  const cases: [string, (page: JsonObject) => void, string?][] = [
    ['unknown policy document beside valid policy event', page => { page.policyDocument = 'PRIVATE-DOCUMENT'; }],
    ['unknown top-level capture beside valid policy event', page => { page.capture = { policyDocument: 'PRIVATE-DOCUMENT' }; }],
    ['wrong version', page => { page.contractVersion = 'ktchecker/not-this-contract'; }, 'incompatible_broker_contract'],
    ['null version', page => { page.contractVersion = null; }, 'incompatible_broker_contract'],
    ['numeric version', page => { page.contractVersion = 1; }, 'incompatible_broker_contract'],
  ];
  for (const key of ['contractVersion', 'events', 'cursor', 'hasMore']) cases.push(['missing ' + key, page => drop(page, key)]);
  for (const value of [null, {}, 'PRIVATE-DOCUMENT', 1, true]) cases.push(['non-array events ' + JSON.stringify(value), page => { page.events = value; }]);
  for (const value of [null, {}, [], 1, true]) cases.push(['non-string cursor ' + JSON.stringify(value), page => { page.cursor = value; }]);
  for (const value of [null, {}, [], 'false', 0]) cases.push(['non-boolean hasMore ' + JSON.stringify(value), page => { page.hasMore = value; }]);
  for (const [label, mutate, earlierCode] of cases) {
    const page = pageOf(); page.cursor = 'PRIVATE-CURSOR'; page.hasMore = true; mutate(page); port.reply = () => page;
    const before = port.calls.length;
    const result = await client.callTool({ name: 'review.events', arguments: { cursor, limit: 2 } });
    const expected = { contractVersion: PREVIEW_CONTRACT, error: { code: earlierCode ?? 'invalid_review_event_page',
      message: 'Review operation did not yield a verified result. Consult the trusted host controller.' } };
    assert.equal(result.isError, true, label); assert.deepEqual(application(result), expected, label);
    assert.deepEqual(result.content, [{ type: 'text', text: JSON.stringify(expected) }], label);
    assert.equal(Object.hasOwn(application(result), 'events'), false, label); assert.equal(Object.hasOwn(application(result), 'cursor'), false, label);
    assert.equal(JSON.stringify(result).includes('PRIVATE-DOCUMENT'), false, label); assert.equal(JSON.stringify(result).includes('PRIVATE-CURSOR'), false, label);
    assert.equal(JSON.stringify(result).includes(credential.token), false, label);
    assert.equal(port.calls.length, before + 1, label); // No hidden retry or follow-up page/ACK.
    assert.deepEqual(port.calls.at(-1), { method: 'review.events', params: { cursor, limit: 2, credential } }, label);
  }
  const nonObjects = [null, [], 1, true, 'PRIVATE-DOCUMENT'];
  for (const page of nonObjects) {
    port.reply = () => page; const before = port.calls.length;
    const result = await client.callTool({ name: 'review.events', arguments: { cursor, limit: 2 } });
    assert.equal(result.isError, true); assert.equal(codeOf(result), 'invalid_broker_response');
    assert.deepEqual(Object.keys(application(result)).sort(), ['contractVersion', 'error']);
    assert.equal(JSON.stringify(result).includes('PRIVATE-DOCUMENT'), false); assert.equal(port.calls.length, before + 1);
  }
  const valid = pageOf(); port.reply = () => valid;
  assert.deepEqual(application(await client.callTool({ name: 'review.events', arguments: { cursor, limit: 2 } })), valid);
  assert.equal(port.calls.length, cases.length + nonObjects.length + 1); assert.equal(port.published, 0); assert.equal(port.stored.size, 0);
});

test('MCP rejects incomplete/extra/mistyped or misbound ordinary event metadata even after a valid policy entry', async t => {
  const { client, port } = await setup(t);
  const cases: [string, unknown][] = [
    ['null entry', null], ['array entry', []], ['string entry', 'PRIVATE-DOCUMENT'], ['numeric entry', 1],
    ['old invalid minimal carrier', { kind: 'terminal', payload: ordinaryEvent().payload }],
  ];
  for (const key of Object.keys(ordinaryEvent())) { const event: JsonObject = ordinaryEvent(); drop(event, key); cases.push(['missing ' + key, event]); }
  for (const key of ['eventId', 'clientId', 'reviewId', 'patchId', 'sourceSnapshotId', 'kind', 'payloadDigest', 'payload']) {
    for (const value of [null, 1, {}, [], true]) cases.push(['wrong type ' + key + ':' + JSON.stringify(value), { ...ordinaryEvent(), [key]: value }]);
  }
  for (const key of ['sequence', 'generation']) {
    for (const value of [null, '1', 1.5, Number.MAX_SAFE_INTEGER + 1, Number.MIN_SAFE_INTEGER - 1]) {
      cases.push(['unsafe/noninteger ' + key + ':' + JSON.stringify(value), { ...ordinaryEvent(), [key]: value }]);
    }
  }
  cases.push(['unknown policy document field', { ...ordinaryEvent(), policyDocument: 'PRIVATE-DOCUMENT' }],
    ['unknown capture field', { ...ordinaryEvent(), capture: { policyDocument: 'PRIVATE-DOCUMENT' } }],
    ['wrong event version', { ...ordinaryEvent(), contractVersion: 'ktchecker/not-this-contract' }],
    ['nonboolean deliveryRequired', { ...ordinaryEvent(), deliveryRequired: 1 }],
    ['sibling client', { ...ordinaryEvent(), clientId: 'client-b' }]);
  for (const [label, bad] of cases) {
    port.reply = () => ({ ...pageOf(), events: [eventOf(), bad, ordinaryEvent('progress')], cursor: 'PRIVATE-CURSOR', hasMore: true });
    const before = port.calls.length, result = await client.callTool({ name: 'review.events', arguments: {} });
    assert.equal(result.isError, true, label); assert.equal(codeOf(result), 'invalid_review_event_page', label);
    assert.deepEqual(Object.keys(application(result)).sort(), ['contractVersion', 'error'], label);
    assert.equal(JSON.stringify(result).includes('PRIVATE-DOCUMENT'), false, label); assert.equal(JSON.stringify(result).includes('PRIVATE-CURSOR'), false, label);
    assert.equal(port.calls.length, before + 1, label);
  }
  const ordinaryPage = pageOf(ordinaryEvent());
  assert.throws(() => checkedReviewEventPage(ordinaryPage, credential.clientId, 2), error => error instanceof BrokerError && error.code === 'invalid_review_event_page');
  assert.throws(() => checkedReviewEventPage(ordinaryPage, 'client-b', 1), error => error instanceof BrokerError && error.code === 'invalid_review_event_page');
  assert.equal(checkedReviewEventPage(ordinaryPage, credential.clientId, 1), ordinaryPage);
  assert.equal(port.calls.length, cases.length); assert.equal(port.published, 0);
});

test('MCP event reads cannot bypass policy validation or leak private documents/cursors via partial filtering', async t => {
  const { client, port } = await setup(t);
  const cases: [string, (event: ReturnType<typeof eventOf>) => void][] = [
    ['kind mismatch', event => { event.kind = 'terminal'; }],
    ['optional policy', event => { event.deliveryRequired = false; }],
    ['event client', event => { event.clientId = 'client-b'; }],
    ['event generation', event => { event.generation = 2; }],
    ['event review', event => { event.reviewId = 'other-review'; }],
    ['event patch', event => { event.patchId = 'other-patch'; }],
    ['event snapshot', event => { event.sourceSnapshotId = hash('b'); }],
    ['event ID', event => { event.eventId = 'policy-' + hash('f'); }],
    ['event sequence', event => { event.sequence++; }],
    ['unsafe sequence', event => { event.sequence = Number('9007199254740993'); }],
    ['unsafe generation', event => { event.generation = Number('9223372036854775807'); }],
    ['payload digest', event => { event.payloadDigest = hash('f'); }],
    ['unknown event field', event => { Object.assign(event, { fullPolicyDocument: 'PRIVATE-DOCUMENT' }); }],
    ['invalid JSON', event => { event.payload = '{'; event.payloadDigest = sha(event.payload); }],
    ['noncanonical bytes', event => { event.payload = JSON.stringify(JSON.parse(event.payload), null, 2); event.payloadDigest = sha(event.payload); }],
    ['MCP version inside event', event => { event.payload = canonical({ ...completeView(), contractVersion: PREVIEW_CONTRACT }); event.payloadDigest = sha(event.payload); }],
    ['nested archive', event => { const view = completeView(); Object.assign(view.capture, { policyDocument: 'PRIVATE-DOCUMENT' });
      event.payload = canonical(reseal(view)); event.payloadDigest = sha(event.payload); }],
  ];
  for (const [label, mutate] of cases) {
    const event = eventOf(); mutate(event); port.reply = () => pageOf(event);
    const result = await client.callTool({ name: 'review.events', arguments: {} });
    assert.equal(result.isError, true, label); assert.equal(codeOf(result), 'invalid_policy_reassessment_view', label);
    assert.equal(Object.hasOwn(application(result), 'cursor'), false, label);
    assert.equal(Object.hasOwn(application(result), 'events'), false, label);
    assert.equal(JSON.stringify(result).includes('PRIVATE-DOCUMENT'), false, label);
  }
  assert.equal(port.calls.length, cases.length); assert.equal(port.published, 0);
});

test('full policy views obey UTF-8 model output budget without truncating result/coverage or leaking credentials', async t => {
  const { client, port } = await setup(t); const request = wire().request;
  const view = completeView(); view.result.findings[0]!.diagnosis = '\u00e9'.repeat(18_000); const large = reseal(view);
  // This is a byte-bound control only, NOT cross-platform Unicode qualification.
  assert(canonical(large).length < 32_768); assert(Buffer.byteLength(canonical(large), 'utf8') > 32_768);
  for (const name of ['review.policy.reassess', 'review.policy.status', 'review.events']) {
    port.reply = () => name === 'review.events' ? pageOf(eventOf(large)) : large;
    const result = await client.callTool({ name, arguments: name.endsWith('reassess') ? request : name.endsWith('status') ? { idempotencyKey: request.idempotencyKey } : {} });
    assert.equal(result.isError, true); assert.equal(codeOf(result), 'model_output_exceeds_budget');
    assert.equal(Object.hasOwn(application(result), 'result'), false); assert.equal(Object.hasOwn(application(result), 'events'), false);
  }
  const secret = completeView(); secret.result.reasons = [credential.token]; port.reply = () => reseal(secret);
  const rejected = await client.callTool({ name: 'review.policy.reassess', arguments: request });
  assert.equal(codeOf(rejected), 'private_data_rejected'); assert.equal(JSON.stringify(rejected).includes(credential.token), false);
  assert.equal(port.published, 0);
});
