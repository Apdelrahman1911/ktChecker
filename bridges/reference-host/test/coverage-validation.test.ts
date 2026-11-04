import { test, type TestContext } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ReferenceHost } from '../src/adapter.js';
import { KernelBroker } from '../src/broker.js';
import { projectDiagnostic } from '../src/context-projection.js';
import { coverageSuccessful } from '../src/coverage-validation-projection.js';
import { observationProjection } from '../src/codex-observation.js';
import { canonical, digest, InboxJournal } from '../src/journal.js';
import { binding, event, FixtureHost, FixtureKernel, registration } from './fixtures.js';

type Value = Record<string, any>;
function failedRow(index: number): Value {
  return { ruleId: 'fixture.rule.' + index, context: { module: ':fixture', sourceSet: 'main', target: 'jvm', compilation: 'main' },
    state: 'unavailable', reason: 'no_compatible_installed_rule_implementation', required: true, depth: 'build',
    provenance: 'not_executed', certificateId: null, implemented: false, tested: false, qualified: false };
}
function fixture(count = 2) {
  const r = registration(), e = event(r);
  e.kind = 'result';
  const payload: Value = JSON.parse(e.payload);
  Object.assign(payload, { contractVersion: e.contractVersion, reviewId: e.reviewId, clientId: e.clientId,
    patchId: e.patchId, sourceSnapshotId: e.sourceSnapshotId, state: 'incomplete', cancelled: false, sequence: 17 });
  payload.result.coverage = Array.from({ length: count }, (_, i) => failedRow(i));
  payload.reasons = payload.result.coverage.flatMap((row: Value) => [
    'coverage_unimplemented:' + row.ruleId, 'coverage_unqualified:' + row.ruleId, 'coverage_unsuccessful:' + row.ruleId,
  ]).concat(['provider_reported_incompleteness', 'required_coverage_incomplete_or_wrong_context', 'unaccounted_raw_diagnostics']).sort();
  payload.result.reasons = ['coverage_unimplemented:fixture.rule.0', 'provider_configuration_missing'];
  payload.result.rawDiagnostics = [{ message: 'A required provider is unavailable.', severity: 'error' }];
  Object.assign(payload.result.findings[0], { whyItMatters: 'Compilation cannot succeed.', fixHint: 'Implement the required member, then recheck.' });
  const refresh = () => { e.payload = canonical(payload); e.payloadDigest = digest(e.payload); };
  refresh();
  return { r, e, payload, refresh };
}
async function blocked(t: TestContext, change: (payload: Value) => void, code: string): Promise<void> {
  const f = fixture(); change(f.payload); f.refresh();
  const directory = await mkdtemp(join(tmpdir(), 'ktchecker-validation-proof-'));
  const journal = new InboxJournal(directory, binding(f.r));
  t.after(async () => { journal.close(); await rm(directory, { recursive: true, force: true }); });
  const kernel = new FixtureKernel(f.r), host = new FixtureHost(binding(f.r));
  kernel.eventsByClient.set(f.r.binding.clientId, [f.e]);
  const adapter = new ReferenceHost(journal, new KernelBroker(kernel, f.r), host);
  await adapter.pollOnce(); await assert.rejects(adapter.drainContext(), { code });
  assert.equal(host.insertionCalls, 0); assert.equal(kernel.acked.size, 0);
  assert.equal(journal.read().inbox[f.e.eventId]!.observed, false);
  assert.equal(journal.readPayload(f.e.eventId, f.e.payloadDigest), f.e.payload);
}

test('1663 complete rows and 4989 exact validation reasons fit honestly without discarding provider failures or findings', async t => {
  const f = fixture(1663), projection = projectDiagnostic(f.e), compact = JSON.parse(projection.diagnosticPayload);
  const summary = compact.result.coverageSummary.validationReasonProjection;
  assert.equal(summary.originalReasonCount, 4992); assert.equal(summary.omittedReasonCount, 4989);
  assert.equal(summary.retainedReasonCount, 3); assert.equal(summary.hasCoverageValidationFailures, true);
  assert.equal(summary.fullReasonArrayDigest, digest(canonical(f.payload.reasons)));
  const omitted = f.payload.reasons.filter((reason: string) => reason.startsWith('coverage_'));
  assert.equal(summary.omittedReasonArrayDigest, digest(canonical(omitted)));
  assert.equal(summary.fullCoverageDigest, compact.result.coverageSummary.fullCoverageDigest);
  assert.equal(summary.fullReasonsRetainedInOriginalPayload, true);
  for (const group of summary.groups) {
    assert.equal(group.reasonCount, 1663); assert.equal(group.matchingCoverageRows, 1663); assert.equal(group.contextCount, 1);
    assert.equal(group.generatedReasonSetDigest, digest(canonical(omitted.filter((reason: string) => reason.startsWith(group.code + ':')).sort())));
  }
  assert.deepEqual(compact.reasons, ['provider_reported_incompleteness', 'required_coverage_incomplete_or_wrong_context', 'unaccounted_raw_diagnostics']);
  assert.deepEqual(compact.result.reasons, f.payload.result.reasons);
  assert.deepEqual(compact.result.findings, f.payload.result.findings);
  assert.deepEqual(compact.result.rawDiagnostics, f.payload.result.rawDiagnostics);
  assert.equal(compact.state, 'incomplete'); assert.equal(compact.result.coverageSummary.requiredUnsuccessfulRows, 1663);
  assert(Buffer.byteLength(projection.diagnosticPayload) < 8000);
  assert.equal(projectDiagnostic(f.e).diagnosticPayload, projection.diagnosticPayload);
  const directory = await mkdtemp(join(tmpdir(), 'ktchecker-validation-complete-'));
  const journal = new InboxJournal(directory, binding(f.r));
  t.after(async () => { journal.close(); await rm(directory, { recursive: true, force: true }); });
  const kernel = new FixtureKernel(f.r), host = new FixtureHost(binding(f.r)); kernel.eventsByClient.set(f.r.binding.clientId, [f.e]);
  const adapter = new ReferenceHost(journal, new KernelBroker(kernel, f.r), host);
  await adapter.pollOnce(); await adapter.drainContext();
  const saved = journal.read().inbox[f.e.eventId]!;
  assert(saved.acknowledged); assert.equal(saved.observed, false); assert(Buffer.byteLength(saved.item!.text) < 12000);
  assert.equal(journal.readPayload(f.e.eventId, f.e.payloadDigest), f.e.payload);
  assert.deepEqual((observationProjection(saved.item!).coverageSummary as Value).validationReasonProjection, summary);
});

test('exact reason families cannot swallow provider mimics, substring matches, recommendations or unknown failures', () => {
  const f = fixture();
  const unknown = ['coverage_unimplemented:fixture.rule.0:extra', 'prefix coverage_unqualified:fixture.rule.0', '__proto__', 'constructor'];
  f.payload.reasons.push(...unknown); f.payload.result.reasons.push(...unknown); f.refresh();
  const compact = JSON.parse(projectDiagnostic(f.e).diagnosticPayload);
  for (const reason of unknown) assert(compact.reasons.includes(reason));
  assert.deepEqual(compact.result.reasons, f.payload.result.reasons);
  assert.deepEqual(compact.result.findings, f.payload.result.findings);
  // The exact same text on non-final traffic is never treated as final validation.
  f.e.kind = 'progress';
  assert.deepEqual(JSON.parse(projectDiagnostic(f.e).diagnosticPayload).reasons, f.payload.reasons);
});

test('reason proof refuses missing explicit row fields and unknown coverage fields before insertion or ACK', async t => {
  await blocked(t, payload => { delete payload.result.coverage[0].implemented; }, 'coverage_validation_proof_unavailable');
  await blocked(t, payload => { payload.result.coverage[0].qualified = 'false'; }, 'coverage_validation_proof_unavailable');
  await blocked(t, payload => { delete payload.result.coverage[0].context.compilation; }, 'coverage_validation_proof_unavailable');
  await blocked(t, payload => { payload.result.coverage[0].futureFailure = 'do not omit'; }, 'coverage_validation_proof_unavailable');
});

test('reason proof refuses duplicate reasons or rows rather than silently normalizing them', async t => {
  await blocked(t, payload => { payload.reasons.push(payload.reasons[0]); }, 'coverage_validation_duplicate_reason');
  await blocked(t, payload => { payload.result.coverage.push(structuredClone(payload.result.coverage[0])); }, 'coverage_validation_duplicate_row');
});

test('reason proof refuses same-rule alias contexts instead of narrowing their obligations', async t => {
  await blocked(t, payload => {
    const alias = structuredClone(payload.result.coverage[0]); alias.context.sourceSet = 'other'; payload.result.coverage.push(alias);
    payload.reasons.push('coverage_outside_required_plan');
  }, 'coverage_validation_ambiguous_context');
});

test('reason proof requires exact predicates and complete generated reason set, including nonrequired rows', async t => {
  await blocked(t, payload => { payload.result.coverage[0].implemented = true; }, 'coverage_validation_reason_set_mismatch');
  await blocked(t, payload => { payload.reasons.shift(); }, 'coverage_validation_reason_set_mismatch');
  await blocked(t, payload => { payload.reasons.push('coverage_unimplemented:unknown.rule'); }, 'coverage_validation_reason_set_mismatch');
  const f = fixture(); f.payload.result.coverage[0].required = false; f.payload.reasons.push('coverage_outside_required_plan'); f.refresh();
  const compact = JSON.parse(projectDiagnostic(f.e).diagnosticPayload);
  assert.equal(compact.result.coverageSummary.requiredRows, 1);
  assert.equal(compact.result.coverageSummary.validationReasonProjection.groups[0].matchingCoverageRows, 2);
  assert(compact.reasons.includes('coverage_outside_required_plan'));
});

test('reason proof binds terminal review identity, not event sequence, and refuses field collisions', async t => {
  for (const key of ['clientId', 'reviewId', 'patchId', 'sourceSnapshotId', 'contractVersion']) {
    await blocked(t, payload => { payload[key] = 'wrong'; }, 'coverage_validation_origin_unconfirmed');
  }
  await blocked(t, payload => { payload.state = 'complete'; }, 'coverage_validation_origin_unconfirmed');
  await blocked(t, payload => { payload.result.coverageSummary = { failure: 'must not be overwritten' }; }, 'projection_field_collision');
  const f = fixture(); assert.notEqual(f.payload.sequence, f.e.sequence); assert.doesNotThrow(() => projectDiagnostic(f.e));
});

test('Kotlin JVM blank certificate semantics distinguish FEFF and record separators, preventing false derivation', async t => {
  const row = { state: 'passed', provenance: 'executed', certificateId: '\u001c' };
  assert.equal(coverageSuccessful(row), false); assert.equal(coverageSuccessful({ ...row, certificateId: '\uFEFF' }), true);
  assert.equal(coverageSuccessful({ ...row, certificateId: '\u00a0\u2007\u202f' }), false);
  assert.equal(coverageSuccessful({ ...row, certificateId: '\u0085' }), true);
  await blocked(t, payload => {
    Object.assign(payload.result.coverage[0], { state: 'passed', provenance: 'executed', certificateId: '\uFEFF' });
  }, 'coverage_validation_reason_set_mismatch');
  const f = fixture(); Object.assign(f.payload.result.coverage[0], { state: 'passed', provenance: 'executed', certificateId: '\u001c' }); f.refresh();
  assert.doesNotThrow(() => projectDiagnostic(f.e));
});

test('an oversized nonredundant reason remains fully retained and blocks insertion instead of being compacted', async t => {
  await blocked(t, payload => { payload.reasons.push('provider_action_required:' + 'x'.repeat(40_000)); }, 'required_context_exceeds_budget');
});
