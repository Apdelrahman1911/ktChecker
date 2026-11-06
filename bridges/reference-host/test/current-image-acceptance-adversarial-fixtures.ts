/** SYNTHETIC TEST DATA ONLY. Uses a NEW local test journal and fake owner/broker/host.
 * No selected executable, launch ticket, owner session, compiler/provider or model is run.
 * Rehashing below deliberately demonstrates only unprivileged JSON consistency, not authority.
 */
import assert from 'node:assert/strict';
import { canonical, digest } from '../src/journal.js';
import { expectedPublicResponse } from '../src/codex-observation.js';
import type { JournalState, ModelRequest } from '../src/types.js';
import { freezeCompletedPatch, verifyOriginalContextJoin, verifyParentAdmission,
  type BoundParent, type ParentCompletionData } from './current-image-acceptance-evidence.js';
import type { AcceptanceReport } from './current-image-acceptance-runner.js';
import { CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE, controlFixture, type FixtureValue } from './current-image-acceptance-fixtures.js';

export interface SyntheticDeliveryCopy { row: FixtureValue; state: JournalState }
export interface CapturedSyntheticDelivery extends SyntheticDeliveryCopy { parent: BoundParent }

export function assertNoActualClaim(report: AcceptanceReport): void {
  assert.equal(report.origin, 'synthetic-control'); assert.equal(report.actualHostDeliveryVerified, false);
  assert.equal(report.launch, null); assert.equal(report.lifecycleDisposition, 'retained_no_quiescence_claim');
  assert.deepEqual([report.qualified, report.usefulClean, report.fullProject, report.actionPerformed, report.internalInferenceRequestTrace],
    [false, false, false, false, false]);
}
export function assertSyntheticFailure(report: AcceptanceReport): void {
  assertNoActualClaim(report); assert.equal(report.status, 'incomplete_or_failed'); assert(report.failure);
  assert.notEqual(report.finalBarrier, 'denied');
}
export async function captureSyntheticTwoPatchRun(t: { after(fn: () => Promise<unknown> | void): void }) {
  const fixture = await controlFixture(t), deliveries: CapturedSyntheticDelivery[] = [], finals: FixtureValue[] = [];
  fixture.evidence.hook = (stage, value) => {
    if (stage === 'private_joined_delivery') {
      const actual = fixture.adapter.parents.at(-1)!;
      const parent = verifyParentAdmission(freezeCompletedPatch(actual.patch), actual.data, deliveries.at(-1)?.parent ?? null,
        CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE);
      deliveries.push({ parent, row: structuredClone(value) as FixtureValue, state: fixture.journal.read() });
    }
    if (stage === 'denied_final_barrier') finals.push(structuredClone(value) as FixtureValue);
  };
  const report = await fixture.run(); assertNoActualClaim(report);
  assert.equal(report.status, 'verified_delivery_final_denied'); assert.equal(report.failure, null);
  assert.equal(deliveries.length, 2); assert.equal(finals.length, 1); assert.equal(fixture.host.requests.length, 2);
  assert.equal(fixture.adapter.calls.filter(method => method === 'gate.complete').length, 1);
  return { fixture, report, deliveries, final: finals[0]! };
}
export function copySyntheticDelivery(capture: CapturedSyntheticDelivery): SyntheticDeliveryCopy {
  return { row: structuredClone(capture.row), state: structuredClone(capture.state) };
}
export function verifySyntheticDelivery(capture: CapturedSyntheticDelivery, copy: SyntheticDeliveryCopy) {
  return verifyOriginalContextJoin(capture.parent, copy.row.completion as ParentCompletionData, copy.state,
    copy.row.originalEvents, copy.row.phase, copy.row.deliveryReceipts, []);
}

/** Rebuild the synthetic application/request/public-response hashes after a controlled mutation.
 * Does NOT rewrite immutable source/admission/original events, journal, receipt or expected binding.
 * This lets an external join fail even when its subordinate public byte digests are consistent.
 */
export function rehashSyntheticApplication(copy: SyntheticDeliveryCopy,
  change: (application: FixtureValue, output: FixtureValue) => void): ModelRequest {
  const phase = copy.row.phase as FixtureValue, application = JSON.parse(phase.serializedApplicationRequest) as FixtureValue;
  const output = JSON.parse(application.params.toolOutput.output) as FixtureValue;
  change(application, output);
  const contexts = (output.contexts as FixtureValue[]).map(context => {
    context.contextDigest = digest(context.text);
    const envelope = JSON.parse(context.text.slice(context.text.indexOf('\n') + 1)) as FixtureValue;
    return { marker: envelope.marker as string, text: context.text as string, digest: context.contextDigest as string,
      hostThreadId: application.params.threadId as string, generation: envelope.generation as number };
  });
  const request: ModelRequest = { invocationId: output.invocationId, hostThreadId: application.params.threadId,
    generation: copy.state.binding.client.generation, contexts, requestDigest: digest(canonical({
      hostThreadId: application.params.threadId, generation: copy.state.binding.client.generation, contexts })) };
  output.requestDigest = request.requestDigest; application.params.toolOutput.output = canonical(output);
  phase.invocationId = request.invocationId; phase.requestDigest = request.requestDigest; phase.contextDigests = contexts.map(item => item.digest);
  phase.serializedApplicationRequest = JSON.stringify(application);
  phase.applicationRequest.applicationRequestId = application.id;
  phase.applicationRequest.serializedRequestDigest = digest(phase.serializedApplicationRequest);
  phase.verifiedPublicResponseText = canonical(expectedPublicResponse(request)); phase.publicResponseDigest = digest(phase.verifiedPublicResponseText);
  return request;
}
export function rehashSyntheticReceiptRows(snapshot: FixtureValue): void {
  for (const row of snapshot.rows as FixtureValue[]) {
    row.responseProjectionDigest = digest(canonical(row.response));
    row.requestProjectionDigest = digest(canonical({ method: row.method, clientIdDigest: row.binding.clientIdDigest,
      eventIdDigests: row.response.eventIdDigests, receipt: row.method === 'review.ack' ? row.ack : row.observation }));
  }
}
/** Repair only public fixture group accounting after deleting a delivered-context group.
 * No signed/opaque capability exists here. Never use this to amend captured actual evidence.
 */
export function regroupSyntheticReceiptRows(snapshot: FixtureValue): void {
  const groups = new Map<number, FixtureValue[]>();
  for (const row of snapshot.rows as FixtureValue[]) groups.set(row.confirmationOrder, [...(groups.get(row.confirmationOrder) ?? []), row]);
  const orders = new Map([...groups.keys()].map((old, index) => [old, index + 1]));
  const occurrences = new Map<string, number>();
  for (const row of snapshot.rows as FixtureValue[]) {
    const group = groups.get(row.confirmationOrder)!;
    row.confirmationOrder = orders.get(row.confirmationOrder)!;
    if (row.priorAckConfirmationOrder !== null) {
      assert(orders.has(row.priorAckConfirmationOrder)); row.priorAckConfirmationOrder = orders.get(row.priorAckConfirmationOrder)!;
    }
    row.eventOccurrence = (occurrences.get(row.event.eventIdDigest) ?? 0) + 1; occurrences.set(row.event.eventIdDigest, row.eventOccurrence);
    row.response.eventIdDigests = group.map(other => other.event.eventIdDigest).sort();
    if (row.observation) row.observation.contextDigests = [...new Set(group.map(other => other.ack.contextDigest))].sort();
  }
  snapshot.successfulConfirmationCalls = groups.size; rehashSyntheticReceiptRows(snapshot);
}
