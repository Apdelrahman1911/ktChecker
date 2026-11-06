import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  checkedReviewEventPage, parsePolicyReassessmentView, policyReassessmentViewSchema,
  POLICY_REASSESSMENT_FORMAT, POLICY_REASSESSMENT_KIND,
} from '../src/policy-reassessment.js';

// Independent fixture encoding, not a call into the production checksum implementation.
function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value !== null && typeof value === 'object') return '{' + Object.keys(value).sort().map(key =>
    JSON.stringify(key) + ':' + canonical((value as Record<string, unknown>)[key])).join(',') + '}';
  const text = JSON.stringify(value);
  if (text === undefined) throw new Error('fixture is not JSON');
  return text;
}
const digest = (text: string): string => 'sha256:' + createHash('sha256').update(text, 'utf8').digest('hex');
const reportDigest = digest('complete upstream report bytes');
const context = { target: 'jvm', sourceSet: 'main', module: ':fixture', compilation: 'main' };
const attachment = (artifactRef = '/outputs/attempt-1/provider-a/detekt.sarif') =>
  ({ kind: 'upstream_sarif', digest: reportDigest, artifactRef });
const material = (kind: unknown = 'upstream_sarif', hash: unknown = reportDigest) =>
  [{ kind, summary: 'Complete upstream report', artifactRef: null, digest: hash }];
function seal<T extends { certificateDigest: string }>(view: T): T {
  return { ...view, certificateDigest: digest(canonical({ ...view, certificateDigest: '' })) };
}
function viewWith(attachments?: unknown) {
  const finding: Record<string, unknown> = {
    ruleId: 'test/rule', title: 'Synthetic finding', diagnosis: 'Fixture only', whyItMatters: 'Contract control',
    location: { path: 'Main.kt', fileDigest: digest('source'), line: 1, column: 0, endLine: 1, endColumn: 0, excerpt: '' },
    fingerprint: digest('display fingerprint'), providerId: 'synthetic-test', context,
    severity: 'warning', kind: 'probable_defect', certainty: 'medium', evidence: material(),
    fixHint: null, verification: [], upstreamId: null, symbolId: null, semanticDigest: null,
    relatedLocations: [], disposition: 'open',
  };
  if (attachments !== undefined) finding.retrievalAttachments = attachments;
  // Closed synthetic DTO with deliberately unqualified coverage; no authority/currentness claim.
  return seal({
    format: POLICY_REASSESSMENT_FORMAT, state: POLICY_REASSESSMENT_KIND, viewId: 'policy-' + digest('view'),
    request: { reviewId: 'review', idempotencyKey: 'request', generation: 1, expectedPolicyDigest: digest('current policy') },
    clientId: 'client', reviewId: 'review', patchId: 'patch', sourceSnapshotId: digest('snapshot'), sequence: 1, generation: 1,
    certified: {
      reviewSequence: 1, inputKey: digest('input'), workKey: digest('work'), effectiveInputKey: digest('effective'),
      workCertificateId: digest('fixture work certificate'), originalPolicyCertificateDigest: digest('original certificate'),
      rawResultDigest: digest('raw result'), originalEffectiveResultDigest: digest('original result'), subjectDigest: digest('subject'),
    },
    capture: {
      format: 'ktchecker/policy-reassessment-capture/1', originalPolicyDigest: digest('original policy'),
      currentPolicyDigest: digest('current policy'), originalCaptureId: digest('original capture'), currentCaptureId: digest('current capture'),
      analysisAuthorityDigest: digest('fixture authority identity'),
    },
    evaluatorIdentity: digest('fixture evaluator'), issuerIdentity: digest('fixture issuer'),
    result: {
      providerId: 'synthetic-test', status: 'partial', findings: [finding], coverage: [{
        ruleId: 'test/rule', context, state: 'findings', reason: 'synthetic_projection_unqualified', required: true, depth: 'syntax',
        provenance: 'not_executed', certificateId: null, implemented: false, tested: false, qualified: false,
      }], reasons: ['synthetic_projection_unqualified'], rawDiagnostics: [],
      metrics: { elapsedMillis: 0, cpuMillis: null, peakRssBytes: null, outputBytes: 0 }, providerVersion: 'fixture',
    },
    auditOnly: true, reasons: ['source_fixture_only'], certificateDigest: '',
  });
}
type FixtureView = ReturnType<typeof viewWith>;
function eventFor(view: FixtureView) {
  const payload = canonical(view);
  return {
    contractVersion: 'ktchecker/preview-1', eventId: view.viewId, clientId: view.clientId, sequence: view.sequence,
    reviewId: view.reviewId, patchId: view.patchId, sourceSnapshotId: view.sourceSnapshotId, kind: POLICY_REASSESSMENT_KIND,
    deliveryRequired: true, payloadDigest: digest(payload), payload, generation: view.generation,
  };
}
function pageFor(event: ReturnType<typeof eventFor>) {
  return { contractVersion: 'ktchecker/preview-1', events: [event], cursor: 'fixture-cursor', hasMore: false };
}
function rejectResealed(view: FixtureView, label: string): void {
  // Recompute BOTH content checksums: rejection must be schema/linkage, not stale hashing.
  const candidate = seal(view);
  assert.equal(policyReassessmentViewSchema.safeParse(candidate).success, false, label);
  assert.throws(() => parsePolicyReassessmentView(candidate), label);
  assert.throws(() => checkedReviewEventPage(pageFor(eventFor(candidate)), 'client', 1), label);
}

test('absent and explicit empty attachment arrays retain their distinct exact wire checksums', () => {
  const absent = viewWith();
  const parsed = parsePolicyReassessmentView(absent);
  assert.equal(Object.hasOwn(parsed.result.findings[0]!, 'retrievalAttachments'), false);
  assert.deepEqual(parsed, absent);
  const empty = viewWith([]);
  const decodedEmpty = parsePolicyReassessmentView(empty);
  assert.equal(Object.hasOwn(decodedEmpty.result.findings[0]!, 'retrievalAttachments'), true);
  assert.deepEqual(decodedEmpty.result.findings[0]!.retrievalAttachments, []);
  assert.notEqual(absent.certificateDigest, empty.certificateDigest);
  for (const view of [absent, empty]) {
    const page = pageFor(eventFor(view));
    assert.equal(checkedReviewEventPage(page, 'client', 1), page);
    assert.equal(page.events[0]!.payload, canonical(view));
  }
});

test('linked attachment values order duplicates and locator whitespace are preserved', () => {
  const exact = attachment('  /outputs/attempt-2/provider-b/detekt.sarif  ');
  const values = [exact, exact, attachment('/outputs/attempt-3/provider-c/detekt.sarif')];
  const view = viewWith(values);
  const parsed = parsePolicyReassessmentView(view);
  assert.deepEqual(parsed.result.findings[0]!.retrievalAttachments, values);
  assert.equal(canonical(parsed), canonical(view));
  const page = pageFor(eventFor(view));
  assert.equal(checkedReviewEventPage(page, 'client', 1), page);
  assert.deepEqual(JSON.parse(page.events[0]!.payload).result.findings[0].retrievalAttachments, values);
});

test('closed attachment schema rejects malformed values even with exactly linked material and fresh checksums', () => {
  const valid = attachment();
  const malformed: [string, Record<string, unknown>][] = [
    ['empty kind', { ...valid, kind: '' }], ['blank kind', { ...valid, kind: ' \t\n' }],
    ['nonbreaking blank kind', { ...valid, kind: '\u00a0' }], ['NUL kind', { ...valid, kind: 'upstream\u0000sarif' }],
    ['empty locator', { ...valid, artifactRef: '' }], ['blank locator', { ...valid, artifactRef: ' \r\n' }],
    ['figure-space locator', { ...valid, artifactRef: '\u2007' }], ['NUL locator', { ...valid, artifactRef: '/report\u0000.sarif' }],
    ['malformed digest', { ...valid, digest: 'sha256:not-a-digest' }], ['uppercase digest', { ...valid, digest: reportDigest.toUpperCase() }],
    ['digest trailing newline', { ...valid, digest: reportDigest + '\n' }], ['unknown key', { ...valid, authority: true }],
  ];
  for (const [label, item] of malformed) {
    const view = viewWith([item]);
    view.result.findings[0]!.evidence = material(item.kind, item.digest);
    rejectResealed(view, label);
  }
  for (const [label, values] of [
    ['null array', null], ['nonarray', valid], ['null item', [null]], ['string item', ['report']],
    ['number locator', [{ ...valid, artifactRef: 1 }]], ['null digest', [{ ...valid, digest: null }]],
    ['missing locator', [{ kind: valid.kind, digest: valid.digest }]],
    ['missing digest', [{ kind: valid.kind, artifactRef: valid.artifactRef }]],
    ['missing kind', [{ digest: valid.digest, artifactRef: valid.artifactRef }]],
  ] as [string, unknown][]) rejectResealed(viewWith(values), label);
});

test('a link needs the exact kind and digest together on one material Evidence entry', () => {
  const good = viewWith([attachment()]);
  assert.deepEqual(parsePolicyReassessmentView(good), good);
  for (const [label, evidence] of [
    ['no evidence', []], ['different kind', material('other')], ['different digest', material('upstream_sarif', digest('other'))],
    ['null material digest', material('upstream_sarif', null)],
    ['kind and digest on separate entries', [...material('upstream_sarif', digest('other')), ...material('other', reportDigest)]],
  ] as [string, unknown][]) {
    const view = viewWith([attachment()]);
    view.result.findings[0]!.evidence = evidence;
    rejectResealed(view, label);
  }
});

test('locator-only changes remain full view and event checksum material', () => {
  const first = viewWith([attachment()]);
  const stale = viewWith([attachment('/outputs/attempt-2/provider-b/detekt.sarif')]);
  stale.certificateDigest = first.certificateDigest;
  assert.equal(policyReassessmentViewSchema.safeParse(stale).success, true);
  assert.throws(() => parsePolicyReassessmentView(stale));
  assert.throws(() => checkedReviewEventPage(pageFor(eventFor(stale)), 'client', 1));
  const second = seal(stale);
  assert.deepEqual(parsePolicyReassessmentView(second), second);
  assert.notEqual(first.certificateDigest, second.certificateDigest);
  const firstEvent = eventFor(first), secondEvent = eventFor(second);
  const stalePayloadDigest = { ...secondEvent, payloadDigest: firstEvent.payloadDigest };
  assert.throws(() => checkedReviewEventPage(pageFor(stalePayloadDigest), 'client', 1));
  assert.notEqual(firstEvent.payloadDigest, secondEvent.payloadDigest);
  for (const event of [firstEvent, secondEvent]) {
    const page = pageFor(event);
    assert.equal(checkedReviewEventPage(page, 'client', 1), page);
  }
});
