/** SYNTHETIC component controls only: actual ReferenceHost + a NEW local InboxJournal,
 * but no JVM, compiler, provider, Codex process, owner session or model execution.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonical, digest, HostError } from '../src/journal.js';
import { deliveryBinding } from './delivery-receipts.js';
import { OBSERVED_GAP, freezeCompletedPatch, verifyParentAdmission, verifyOriginalContextJoin,
  type BoundParent, type ParentCompletionData } from './current-image-acceptance-evidence.js';
import { OriginalReviewObservationClock, type AcceptanceReport, type SyntheticControlOptions } from './current-image-acceptance-runner.js';
import { CONTROL_COMPILER_RULES, CONTROL_IMAGE, CONTROL_INVOCATION, CONTROL_OWNER_IMAGE, CONTROL_PATH, CONTROL_RULE, CONTROL_RULES,
  controlFixture, controlPatch, SyntheticAcceptanceClock, type FixtureValue, type SyntheticParent } from './current-image-acceptance-fixtures.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
const failed = (report: AcceptanceReport) => {
  assert.equal(report.status, 'incomplete_or_failed'); assert.equal(report.actualHostDeliveryVerified, false);
  assert.equal(report.qualified, false); assert.equal(report.usefulClean, false); assert.equal(report.actionPerformed, false);
  assert.equal(report.lifecycleDisposition, 'retained_no_quiescence_claim'); assert(report.failure);
};
function synchronizeCompletionEvents(parent: SyntheticParent): void {
  for (const event of parent.events) {
    const payload = JSON.parse(event.payload) as FixtureValue;
    if (event.kind === 'result') Object.assign(payload, parent.completion.children.find(child => child.reviewId === event.reviewId));
    if (event.kind === 'plan_resolved' || event.kind === 'plan_unavailable') payload.parentPlan = structuredClone(parent.completion.parent);
    event.payload = canonical(payload); event.payloadDigest = digest(event.payload);
  }
}

test('two full1665 parents, explicit synthetic14, same finding fixed, historical delivery and captured broker-denied final join', linuxOnly, async t => {
  const f = await controlFixture(t), report = await f.run();
  assert.equal(report.status, 'verified_delivery_final_denied'); assert.equal(report.failure, null); assert.equal(report.unknownOperation, null);
  assert.equal(report.origin, 'synthetic-control'); assert.equal(report.actualHostDeliveryVerified, false); assert.equal(report.launch, null);
  assert.deepEqual([report.qualified, report.usefulClean, report.fullProject, report.actionPerformed, report.internalInferenceRequestTrace], [false, false, false, false, false]);
  assert.equal(report.finalBarrier, 'denied'); assert.equal(f.host.requests.length, 2); assert.equal(f.adapter.parents.length, 2);
  assert.equal(f.adapter.calls.filter(method => method === 'gate.complete').length, 1);
  assert.equal(CONTROL_RULES.length, 1665); assert.equal(CONTROL_COMPILER_RULES.length, 14);
  assert.equal(digest(canonical(CONTROL_RULES)), 'sha256:121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5');
  assert.equal(f.adapter.publishedEvents.length, 12); assert.equal(f.adapter.observed.size, 12);
  const state = f.journal.read(); assert.equal(Object.keys(state.inbox).length, 12);
  assert(Object.values(state.inbox).every(entry => entry.acknowledged && entry.observed && entry.inserted && !entry.stale));
  assert(report.patches.every(patch => patch.allChildComputationsEvidenced && patch.exact14CompilerExecutionsEvidenced && patch.expectedFindingChangesMatched));
  assert.deepEqual(report.patches.map(patch => patch.originalEventJoin.requiredEventIds.length), [6, 6]);
  for (const parent of f.adapter.parents) {
    const graph = parent.data.declaredPlan.jvmProduced as FixtureValue;
    assert.equal(graph.producer.compilationId, ':||main'); assert.equal(graph.consumer.compilationId, ':||test');
    for (const slot of (parent.data.parent.slots as FixtureValue[])) assert.equal(slot.context.target, '');
  }
  assert.equal(f.adapter.parents[1]!.patch.baseSnapshotId, f.adapter.parents[0]!.lineage.snapshotId);
  for (const scope of f.adapter.clockScopes) assert.equal(scope.overall, f.clock);
  assert.notEqual(f.adapter.clockScopes[0]!.reviewObservation, f.adapter.clockScopes[1]!.reviewObservation);
});

const rejectedSelections: readonly [string, (value: FixtureValue) => void][] = [
  ['multiple required clients', value => value.requiredClientIds.push('another-client')],
  ['only one scheduled parent', value => value.scheduledPatches.pop()],
  ['duplicate patch ID', value => { value.scheduledPatches[1].patchId = value.scheduledPatches[0].patchId; }],
  ['missing intended finding', value => { value.scheduledPatches[0].findings = []; }],
  ['unrelated second-patch finding', value => { value.scheduledPatches[1].findings[0].upstreamId = 'ANOTHER_FINDING'; }],
  ['second patch does not remove finding', value => { value.scheduledPatches[1].findings[0].present = true; }],
  ['non-exact14 expectation', value => value.compilerRuleIds.pop()],
  ['policy is not separately selected', value => { value.ownerCompilerPolicySelectionDigest = digest('different-policy'); }],
];
for (const [name, change] of rejectedSelections) test('rejects ' + name + ' before owner submission/model', linuxOnly, async t => {
  const f = await controlFixture(t), authorization = structuredClone(f.options.authorization) as unknown as FixtureValue; change(authorization);
  const report = await f.run({ authorization: authorization as SyntheticControlOptions['authorization'] }); failed(report);
  assert.equal(f.adapter.parents.length, 0); assert.equal(f.host.requests.length, 0);
});
test('scalar-only adapter has no fallback and cannot submit', linuxOnly, async t => {
  const f = await controlFixture(t); f.adapter.profile = 'scalar-only'; const report = await f.run(); failed(report);
  assert.equal(f.adapter.parents.length, 0); assert.equal(f.host.requests.length, 0);
});
test('changed owner binding stops and cannot rewrite the report original lineage', linuxOnly, async t => {
  const f = await controlFixture(t), original = deliveryBinding(f.registration.binding);
  f.adapter.admissionHook = () => { f.registration.binding.lineageId = 'synthetic-substituted-lineage'; };
  const report = await f.run(); failed(report); assert.equal(report.failure, 'current_acceptance_owner_binding_changed');
  assert.deepEqual(report.binding, original); assert.equal(f.host.requests.length, 0); assert.equal(f.adapter.parents.length, 1);
});
test('a caller omitting the scheduled repair cannot request a second model observation or final', linuxOnly, async t => {
  const f = await controlFixture(t), report = await f.run({}, async (ordinal, previous) => {
    const patch = controlPatch(f.registration, ordinal, previous); return ordinal === 1 ? patch : { ...patch, patchId: 'unscheduled-repair' };
  });
  failed(report); assert.equal(f.host.requests.length, 1); assert.equal(f.adapter.parents.length, 1); assert.equal(report.finalBarrier, 'not_attempted');
});
test('progressive non-null result does not count as terminal and invokes no model until converged', linuxOnly, async t => {
  const f = await controlFixture(t); f.adapter.progressive = true;
  f.host.invokeHook = () => { assert(f.adapter.statusCalls >= 2); };
  assert.equal((await f.run()).status, 'verified_delivery_final_denied'); assert.equal(f.host.requests.length, 2); assert(f.adapter.statusCalls >= 3);
});
test('normal parent/child read-transaction race polls again rather than completing early', linuxOnly, async t => {
  const f = await controlFixture(t);
  f.adapter.statusHook = (parent, count) => {
    const value = structuredClone(parent.completion) as unknown as FixtureValue;
    if (count === 1) { value.parent.state = 'pending'; value.parent.slots[0].state = 'running'; value.parent.slots[1].state = 'deferred'; }
    return value as ParentCompletionData;
  };
  f.host.invokeHook = () => { assert(f.adapter.statusCalls >= 2); };
  assert.equal((await f.run()).status, 'verified_delivery_final_denied');
});
test('honest unavailable child retains whole parent delivery but never successful patch or qualification', linuxOnly, async t => {
  const f = await controlFixture(t);
  f.adapter.admissionHook = parent => {
    const value = parent.completion as unknown as FixtureValue;
    value.children[1].result = null; value.children[1].reasons = ['produced_plan_preview_unqualified', OBSERVED_GAP, 'synthetic_explicit_unavailable'];
    value.parent.slots[1].state = 'unavailable'; value.parent.slots[1].reasons = value.children[1].reasons;
    const index = parent.events.findIndex(event => event.kind === 'result' && event.reviewId === value.children[1].reviewId);
    parent.events.splice(index, 1); parent.events.forEach((event, ordinal) => { event.sequence = ordinal + 1; });
    synchronizeCompletionEvents(parent);
  };
  const report = await f.run(); failed(report); assert.equal(f.host.requests.length, 1); assert.equal(f.adapter.parents.length, 1);
  assert.equal(report.patches[0]!.allChildComputationsEvidenced, false); assert.equal(report.finalBarrier, 'not_attempted');
  assert.equal(f.adapter.observed.size, 5);
});

const malformedParents: readonly [string, (parent: SyntheticParent) => void][] = [
  ['one-child parent vector', parent => { (parent.data.parent as FixtureValue).slots.pop(); }],
  ['no-test declared plan', parent => { (parent.data.declaredPlan as FixtureValue).slots.pop(); }],
  ['changed source mode', parent => { (parent.data.snapshotCommit as FixtureValue).manifest.entries[0].executable = true; }],
  ['changed source bytes', parent => { (parent.data.snapshotCommit as FixtureValue).manifest.entries[0].digest = digest('replacement'); }],
  ['changed snapshot delta', parent => { (parent.data.snapshotCommit as FixtureValue).manifest.delta.pop(); }],
  ['different owner code image', parent => { (parent.data.observed as FixtureValue).ownerCodeImageDigest = digest('replacement-owner'); }],
  ['replayed snapshot admission', parent => { (parent.data.snapshotCommit as FixtureValue).replayed = true; }],
];
for (const [name, mutate] of malformedParents) test('rejects ' + name + ' before insertion/model', linuxOnly, async t => {
  const f = await controlFixture(t); f.adapter.admissionHook = mutate; failed(await f.run()); assert.equal(f.host.requests.length, 0); assert.equal(f.host.items.size, 0);
});
test('all parent contexts are bound to selected policy audit, not just an internally consistent new digest', linuxOnly, async t => {
  const f = await controlFixture(t);
  f.adapter.admissionHook = parent => {
    const plan = parent.data.declaredPlan as FixtureValue;
    for (const slot of plan.slots) {
      const context = slot.input.context; context.configurationDigest = digest('unselected-policy');
      const policies = Object.fromEntries(Object.entries(context.policies).filter(([key]) =>
        !['ktchecker.catalog-inventory', 'ktchecker.activation-plan', 'ktchecker.observed-export-evidence'].includes(key)));
      plan.jvmProduced[slot.slotId === 'main' ? 'producer' : 'consumer'].rawContextDigest = digest(canonical({ ...context, policies }));
    }
    (parent.data.parent as FixtureValue).planDigest = digest(canonical(plan));
  };
  const report = await f.run(); failed(report); assert.equal(report.failure, 'current_acceptance_parent_policy_not_selected_audit');
  assert.equal(f.host.requests.length, 0);
});
test('extra executed compiler rule cannot masquerade as exact14', linuxOnly, async t => {
  const f = await controlFixture(t);
  f.adapter.admissionHook = parent => {
    const result = parent.completion.children[0]!.result as FixtureValue;
    const extra = result.coverage.find((row: FixtureValue) => row.ruleId.startsWith('ktc.compiler.') && !CONTROL_COMPILER_RULES.includes(row.ruleId));
    assert(extra); extra.provenance = 'executed'; // Keep original unqualified row and exact redundant reasons consistent.
    synchronizeCompletionEvents(parent);
  };
  const report = await f.run(); failed(report); assert.equal(report.patches[0]!.exact14CompilerExecutionsEvidenced, false);
  assert.equal(f.host.requests.length, 1); assert.equal(f.adapter.parents.length, 1);
});
for (const [name, change] of [
  ['missing full1665 row', (value: FixtureValue) => value.coverage.pop()],
  ['qualification upgrade', (value: FixtureValue) => { value.coverage[0].qualified = true; }],
  ['nonnull certificate', (value: FixtureValue) => { value.coverage[0].certificateId = 'synthetic-certificate'; }],
  ['stripped provider gap', (value: FixtureValue) => { value.reasons = []; }],
  ['finding source digest substituted', (value: FixtureValue) => { value.findings[0].location.fileDigest = digest('wrong-source'); }],
] as const) test('rejects ' + name + ' before model', linuxOnly, async t => {
  const f = await controlFixture(t);
  f.adapter.admissionHook = parent => { change(parent.completion.children[1]!.result as FixtureValue); synchronizeCompletionEvents(parent); };
  failed(await f.run()); assert.equal(f.host.requests.length, 0);
});

for (const [name, change] of [
  ['sequence gap', (events: FixtureValue[]) => { events[0]!.sequence = 2; }],
  ['duplicate event identity', (events: FixtureValue[]) => { events[1]!.eventId = events[0]!.eventId; }],
  ['changed payload digest', (events: FixtureValue[]) => { events[0]!.payloadDigest = digest('not-the-original'); }],
  ['event replay inside page', (events: FixtureValue[]) => { events.push({ ...events[0]! }); }],
] as const) test('rejects original ' + name + ' before insertion/model', linuxOnly, async t => {
  const f = await controlFixture(t);
  f.adapter.responseHook = (method, response) => { if (method === 'review.events' && (response.events as unknown[]).length) change(response.events as FixtureValue[]); return response; };
  failed(await f.run()); assert.equal(f.host.requests.length, 0); assert.equal(f.host.items.size, 0);
});
for (const which of ['known-client-token', 'explicit-private-path'] as const) test(which + ' is rejected in original event before insertion', linuxOnly, async t => {
  const f = await controlFixture(t), secret = which === 'known-client-token' ? f.registration.client.token : '/private-explicit-not-standard/location';
  f.adapter.admissionHook = parent => {
    const result = parent.completion.children[1]!.result as FixtureValue; result.findings[0].diagnosis += secret; synchronizeCompletionEvents(parent);
  };
  failed(await f.run({ forbidden: which === 'known-client-token' ? [] : [secret] })); assert.equal(f.host.items.size, 0); assert.equal(f.host.requests.length, 0);
});
test('late required event for a delivered old snapshot blocks the next mutation/model', linuxOnly, async t => {
  const f = await controlFixture(t); f.evidence.hook = (stage, _value) => {
    if (stage === 'private_joined_delivery' && f.adapter.parents.length === 1) {
      const old = structuredClone(f.adapter.publishedEvents[0]!); old.eventId = 'synthetic-late-old-event'; old.sequence = 7; f.adapter.publishedEvents.push(old);
    }
  };
  const report = await f.run(); failed(report); assert.equal(report.failure, 'current_acceptance_late_event_for_delivered_snapshot');
  assert.equal(f.adapter.parents.length, 1); assert.equal(f.host.requests.length, 1);
});
test('application request IDs may not repeat across patch observations', linuxOnly, async t => {
  const f = await controlFixture(t); f.host.reuseApplicationId = true; failed(await f.run()); assert.equal(f.host.requests.length, 2);
  assert.equal(f.adapter.calls.includes('gate.complete'), false);
});
test('non-actual witness never becomes actual or synthetic successful delivery', linuxOnly, async t => {
  const f = await controlFixture(t); f.host.unavailable = true; failed(await f.run()); assert.equal(f.host.requests.length, 1);
});
test('lost submit return poisons exact components without retry, recovery, close or second submit', linuxOnly, async t => {
  const f = await controlFixture(t); f.adapter.loseSubmitReturn = true; const report = await f.run(); failed(report);
  assert.equal(report.unknownOperation, 'parent_submit'); assert.equal(f.adapter.parents.length, 1); assert.equal(f.host.requests.length, 0);
  assert.throws(() => f.run(), /components_previously_attempted/); assert.equal(f.adapter.parents.length, 1);
});
test('lost observation confirmation is not retried even when synthetic receiver already accepted it', linuxOnly, async t => {
  const f = await controlFixture(t); f.adapter.responseHook = (method, response) => {
    if (method === 'review.observe') throw new HostError('synthetic_observation_return_lost'); return response;
  };
  const report = await f.run(); failed(report); assert.equal(f.host.requests.length, 1);
  assert.equal(f.adapter.calls.filter(method => method === 'review.observe').length, 1); assert.equal(f.adapter.parents.length, 1);
});

for (const [name, method, change] of [
  ['historical frontier omitted', 'gate.prepare', (value: FixtureValue) => { value.requiredEventIds = value.requiredEventIds.slice(6); }],
  ['gap stripped from cut', 'gate.prepare', (value: FixtureValue) => { value.reasons = []; }],
  ['gap stripped from final', 'gate.complete', (value: FixtureValue) => { value.reasons = []; }],
  ['allowed final', 'gate.complete', (value: FixtureValue) => { value.allowed = true; }],
  ['action performed by final', 'gate.complete', (value: FixtureValue) => { value.actionPerformed = true; }],
  ['delivery missing in final', 'gate.complete', (value: FixtureValue) => { value.reasons.push('insertion_missing:synthetic-event-1'); }],
  ['local-denial substitution for missing cut context', 'gate.prepare', (value: FixtureValue) => { value.requiredEventIds.push('not-delivered'); }],
] as const) test('cannot pass denied barrier with ' + name, linuxOnly, async t => {
  const f = await controlFixture(t); f.adapter.responseHook = (called, response) => { if (called === method) change(response); return response; };
  const report = await f.run(); failed(report); assert.equal(f.host.requests.length, 2); assert.notEqual(report.finalBarrier, 'denied');
  if (name === 'local-denial substitution for missing cut context') assert.equal(f.adapter.calls.includes('gate.complete'), false);
});

test('original300 phase includes all insertion work and is not refreshed by individual waits', linuxOnly, async t => {
  const f = await controlFixture(t); f.host.insertHook = () => { f.clock.now += 60_000; };
  failed(await f.run()); assert.equal(f.host.requests.length, 0); assert.equal(f.adapter.parents.length, 1);
  assert(f.clock.waits.some(wait => wait.cap === 300_000)); assert(f.clock.now >= 300_000);
});
test('original300 phase also includes returned model/public evidence and private evidence write', linuxOnly, async t => {
  const f = await controlFixture(t); f.host.invokeHook = () => { f.clock.now += 180_000; };
  f.host.evidenceHook = phase => { f.clock.now += 119_000; return phase; };
  f.evidence.hook = stage => { if (stage === 'private_joined_delivery') f.clock.now += 2_000; };
  const report = await f.run(); failed(report); assert.equal(f.host.requests.length, 1); assert.equal(f.adapter.parents.length, 1);
});
test('already-running900 clock is not renewed when first300 begins', () => {
  const overall = new SyntheticAcceptanceClock(); overall.now = 850_000; const review = new OriginalReviewObservationClock(overall);
  assert.equal(review.remainingMs(), 50_000); overall.now = 900_000; assert.throws(() => review.checkpoint(), /overall_expired/);
});
test('a final summary write crossing original900 never returns a verified acceptance report', linuxOnly, async t => {
  const f = await controlFixture(t);
  f.evidence.hook = stage => { if (stage === 'acceptance_summary') f.clock.now = 900_001; };
  await assert.rejects(f.run(), /synthetic_original_overall_expired/);
  assert.equal(f.host.requests.length, 2); assert.equal(f.adapter.calls.filter(method => method === 'gate.complete').length, 1);
  assert.throws(() => f.run(), /components_previously_attempted/);
});
test('image bytes and ordered compiler selection cannot drift between admission boundaries', linuxOnly, async t => {
  const f = await controlFixture(t); let count = 0;
  const report = await f.run({ image: { assertCurrent: () => ({ ...CONTROL_IMAGE, ownerCodeImageDigest: ++count > 1 ? digest('changed-image') : CONTROL_OWNER_IMAGE }),
    assertCompilerArtifactPaths: paths => assert.deepEqual(paths, CONTROL_INVOCATION.compilerArtifactPaths) } });
  failed(report); assert.equal(f.adapter.parents.length, 0); assert.equal(f.host.requests.length, 0);
});

test('offline original/request/public/ACK/observation mutations all fail joined delivery', linuxOnly, async t => {
  const f = await controlFixture(t), captures: { parent: BoundParent; row: FixtureValue; state: ReturnType<typeof f.journal.read> }[] = [];
  f.evidence.hook = (stage, value) => {
    if (stage !== 'private_joined_delivery') return;
    const actual = f.adapter.parents.at(-1)!;
    const parent = verifyParentAdmission(freezeCompletedPatch(actual.patch), actual.data, captures.at(-1)?.parent ?? null, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE);
    captures.push({ parent, row: structuredClone(value) as FixtureValue, state: f.journal.read() });
  };
  assert.equal((await f.run()).status, 'verified_delivery_final_denied'); assert.equal(captures.length, 2);
  const mutations: readonly [string, (value: FixtureValue) => void][] = [
    ['original payload bytes', value => { value.row.originalEvents[0].payload += ' '; }],
    ['missing original event', value => value.row.originalEvents.pop()],
    ['durable original event bytes', value => { Object.values<FixtureValue>(value.state.inbox)[0]!.event.payload += ' '; }],
    ['durable projection', value => { Object.values<FixtureValue>(value.state.inbox).at(-1)!.item.text += ' '; }],
    ['durable ACK', value => { Object.values<FixtureValue>(value.state.inbox).at(-1)!.acknowledged = false; }],
    ['durable observation', value => { Object.values<FixtureValue>(value.state.inbox).at(-1)!.observed = false; }],
    ['serialized application bytes', value => { value.row.phase.serializedApplicationRequest += ' '; }],
    ['public response bytes', value => { value.row.phase.verifiedPublicResponseText += ' '; }],
    ['captured ACK link', value => { value.row.deliveryReceipts.rows.at(-1).ack.contextDigest = digest('wrong-ack'); }],
    ['captured observation link', value => { value.row.deliveryReceipts.rows.at(-1).observation.observationIdDigest = digest('wrong-observation'); }],
  ];
  for (const [name, change] of mutations) {
    const original = captures[1]!, copy = { row: structuredClone(original.row), state: structuredClone(original.state) }; change(copy);
    assert.throws(() => verifyOriginalContextJoin(original.parent, copy.row.completion, copy.state, copy.row.originalEvents,
      copy.row.phase, copy.row.deliveryReceipts, []), name);
  }
  // No replays or extra model invocations were needed for the offline negative controls.
  assert.equal(f.host.requests.length, 2);
  const finding = (f.adapter.parents[0]!.completion.children[1]!.result as FixtureValue).findings[0];
  assert.equal(finding.ruleId, CONTROL_RULE); assert.equal(finding.location.path, CONTROL_PATH);
});
