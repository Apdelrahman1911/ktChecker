/** SYNTHETIC source-domain controls: genuine RuleContext/ReviewStatus field shapes and
 * full catalog IDs, not a kernel/compiler/host/model run or qualification certificate. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { PREVIEW_CONTRACT } from '@ktchecker/mcp-preview/rpc';
import { canonical, digest } from '../src/journal.js';
import { prepareDiagnosticContext, projectDiagnostic } from '../src/context-projection.js';
import { expectedPublicResponse, verifyPublicResponse } from '../src/codex-observation.js';
import type { ModelRequest, ReviewEvent } from '../src/types.js';
import { binding, registration, S1 } from './fixtures.js';

type Value = Record<string, any>;
const r = registration(), b = binding(r), gap = 'configuration_input_closure_unproven';
const rules = ['core', 'libraries', 'platform', 'ecosystem'].flatMap(name =>
  (JSON.parse(readFileSync(new URL('../../../../data/rules-' + name + '.json', import.meta.url), 'utf8')) as { id: string }[]).map(row => row.id)).sort();
function fixture(compilation = 'main', target = '') {
  const scope = { target, module: ':fixture', sourceSet: compilation, compilation };
  const coverage = rules.map(ruleId => ({ ruleId, context: scope, state: 'unavailable',
    reason: 'no_compatible_installed_rule_implementation', required: true, depth: 'build', provenance: 'not_executed',
    certificateId: null, implemented: false, tested: false, qualified: false }));
  const payload: Value = { contractVersion: PREVIEW_CONTRACT, reviewId: 'synthetic-review-' + compilation,
    clientId: r.binding.clientId, patchId: 'synthetic-child-' + compilation, sequence: compilation === 'main' ? 1 : 2,
    sourceSnapshotId: S1, state: 'incomplete', cancelled: false, parentPlan: null,
    result: { providerId: 'synthetic-shape-only', providerVersion: 'preview', status: 'incomplete', findings: [], coverage,
      reasons: [gap, 'provider_still_unavailable'], rawDiagnostics: [{ message: 'Required analysis remains unavailable.', severity: 'error',
        upstreamId: null, location: null }], metrics: { elapsedMillis: 1, cpuMillis: null, peakRssBytes: null, outputBytes: 0 } },
    reasons: [...coverage.flatMap(row => ['coverage_unimplemented:' + row.ruleId, 'coverage_unqualified:' + row.ruleId,
      'coverage_unsuccessful:' + row.ruleId]), gap, 'produced_plan_preview_unqualified', 'provider_reported_incompleteness',
      'required_coverage_incomplete_or_wrong_context', 'unaccounted_raw_diagnostics'].sort() };
  const event: ReviewEvent = { contractVersion: PREVIEW_CONTRACT, clientId: r.binding.clientId, generation: r.binding.generation,
    reviewId: payload.reviewId, patchId: payload.patchId, sourceSnapshotId: S1, sequence: 7,
    eventId: 'synthetic-final-' + compilation, kind: 'result', deliveryRequired: true, payload: '', payloadDigest: '' };
  const refresh = () => { event.payload = canonical(payload); event.payloadDigest = digest(event.payload); };
  refresh(); return { event, payload, refresh };
}
function request(event: ReviewEvent): ModelRequest {
  const contexts = [prepareDiagnosticContext(event, b, S1)];
  return { invocationId: 'synthetic-invocation', hostThreadId: b.hostThreadId, generation: b.client.generation, contexts,
    requestDigest: digest(canonical({ hostThreadId: b.hostThreadId, generation: b.client.generation, contexts })) };
}

for (const compilation of ['main', 'test']) test(`actual empty ${compilation} target retains all1665 identities and4995 exact validation predicates`, () => {
  assert.equal(rules.length, 1665);
  assert.equal(digest(canonical(rules)), 'sha256:121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5');
  const f = fixture(compilation), original = f.event.payload, projected = projectDiagnostic(f.event);
  const value = JSON.parse(projected.diagnosticPayload), summary = value.result.coverageSummary;
  assert.equal(summary.totalRows, 1665); assert.equal(summary.requiredUnsuccessfulRows, 1665);
  assert.equal(summary.requiredUnqualifiedRows, 1665); assert.equal(summary.contextCount, 1);
  assert.equal(summary.fullCoverageDigest, digest(canonical(f.payload.result.coverage)));
  assert.equal(summary.contextsDigest, digest(canonical([canonical(f.payload.result.coverage[0].context)])));
  assert.equal(summary.validationReasonProjection.omittedReasonCount, 4995);
  assert.equal(summary.validationReasonProjection.fullReasonArrayDigest, digest(canonical(f.payload.reasons)));
  for (const group of summary.validationReasonProjection.groups) assert.equal(group.matchingCoverageRows, 1665);
  assert.equal(value.state, 'incomplete'); assert(value.reasons.includes(gap)); assert(value.reasons.includes('produced_plan_preview_unqualified'));
  assert.deepEqual(value.result.reasons, f.payload.result.reasons); assert.deepEqual(value.result.rawDiagnostics, f.payload.result.rawDiagnostics);
  assert.deepEqual(value.result.findings, f.payload.result.findings); assert.equal(f.event.payload, original);
  assert.equal(projected.originalPayloadRef.payloadDigest, digest(original));
  assert.equal(f.payload.result.coverage[0].context.target, ''); // Not normalized to jvm.
  const modelRequest = request(f.event), response = canonical(expectedPublicResponse(modelRequest));
  assert(verifyPublicResponse(response, modelRequest));
  assert.equal(JSON.parse(response).observations[0].receiptScope, 'projected_context_only');
});

test('empty target exception does not admit missing/nonstring target, empty other scopes or extra fields', () => {
  const changes: ((row: Value) => void)[] = [
    row => { delete row.context.target; },
    ...[null, true, 7, [], {}].map(target => (row: Value) => { row.context.target = target; }),
    ...['module', 'sourceSet', 'compilation'].map(key => (row: Value) => { row.context[key] = ''; }),
    row => { row.context.extra = ''; }, row => { delete row.required; }, row => { row.qualified = 'false'; },
    row => { row.extra = 'not silently removed'; },
  ];
  for (const change of changes) {
    const f = fixture(); change(f.payload.result.coverage[0]); f.refresh();
    assert.throws(() => prepareDiagnosticContext(f.event, b, S1), { code: 'coverage_validation_proof_unavailable' });
  }
});

test('empty and jvm remain distinct identities: stale payload digest and prior public witness cannot cross either retarget', () => {
  for (const [from, to] of [['', 'jvm'], ['jvm', '']] as const) {
    const f = fixture('main', from), original = request(f.event), publicText = canonical(expectedPublicResponse(original));
    const originalCoverage = JSON.parse(projectDiagnostic(f.event).diagnosticPayload).result.coverageSummary;
    for (const row of f.payload.result.coverage) row.context = { ...row.context, target: to };
    f.event.payload = canonical(f.payload); // Retargeted bytes cannot reuse old digest.
    assert.throws(() => prepareDiagnosticContext(f.event, b, S1), { code: 'event_digest_mismatch' });
    f.refresh(); const changed = request(f.event), changedCoverage = JSON.parse(projectDiagnostic(f.event).diagnosticPayload).result.coverageSummary;
    assert.notEqual(changed.contexts[0]!.digest, original.contexts[0]!.digest);
    assert.notEqual(changedCoverage.contextsDigest, originalCoverage.contextsDigest);
    assert.notEqual(changedCoverage.fullCoverageDigest, originalCoverage.fullCoverageDigest);
    assert.equal(verifyPublicResponse(publicText, changed), false);
  }
});

test('same rule in empty and jvm contexts stays ambiguous, never merged by target normalization', () => {
  const f = fixture(), alias = structuredClone(f.payload.result.coverage[0]); alias.context.target = 'jvm';
  f.payload.result.coverage.push(alias); f.refresh();
  assert.throws(() => prepareDiagnosticContext(f.event, b, S1), { code: 'coverage_validation_ambiguous_context' });
});
