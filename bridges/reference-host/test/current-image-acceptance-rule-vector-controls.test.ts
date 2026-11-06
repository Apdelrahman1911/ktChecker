/** SYNTHETIC SOURCE/DATA controls only. No owner, host, journal, compiler, model,
 * ticket, subprocess, native image or retained runtime record is opened. A valid
 * data join below remains unqualified; it is not delivery or execution evidence. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { canonical, digest } from '../src/journal.js';
import { DeliveryReceiptCapture, fullCurrentRules, type ProducedRuleVectorProfile } from './delivery-receipts.js';
import { fullRealRules, REAL_MODEL_RULE_VECTOR_DIGEST } from './real-model-delivery.js';
import { CURRENT_ONLY_RULE_IDS, CURRENT_RULE_IDS, HISTORICAL_RULE_IDS } from './required-rule-vector-fixtures.js';
import { freezeCompletedPatch, verifyParentAdmission, verifyParentCompletion } from './current-image-acceptance-evidence.js';
// Only the pure SOURCE data constructors are used, NEVER controlFixture or any runtime component.
import { CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE, CONTROL_RULES, controlParent, controlPatch, controlRegistration,
  type FixtureValue } from './current-image-acceptance-fixtures.js';

const linuxOnly = { skip: process.platform !== 'linux' ? 'platform_unavailable:linux_current_image_support_required' : false };
const mutations: readonly [string, (ids: readonly string[]) => unknown][] = [
  ['null', () => null],
  ['non-array shape', ids => ({ length: ids.length, ids })],
  ['empty vector', () => []],
  ['missing ID', ids => ids.slice(1)],
  ['extra ID', ids => [...ids, 'zzz.synthetic.extra_rule'].sort()],
  ['equal-size wrong ID', ids => [...ids.slice(1), 'zzz.synthetic.wrong_rule'].sort()],
  ['equal-size duplicate ID', ids => [...ids.slice(1), ids[1]!].sort()],
  ['reordered IDs', ids => [...ids].reverse()],
  ['non-string member', ids => [null, ...ids.slice(1)]],
  ['sparse vector', ids => { const sparse = [...ids]; delete sparse[0]; return sparse; }],
];
for (const [profile, ids, validate, count, compilerCount] of [
  ['historical-full1663', HISTORICAL_RULE_IDS, fullRealRules, 1663, 443],
  ['current-full1665', CURRENT_RULE_IDS, fullCurrentRules, 1665, 445],
] as const) {
  test(`${profile} accepts only its exact sorted SOURCE vector without replacing it`, () => {
    const input = [...ids]; assert.equal(validate(input), input); assert.equal(input.length, count);
    assert.equal(new Set(input).size, count); assert.deepEqual(input, [...input].sort());
    assert.equal(input.filter(id => id.startsWith('ktc.compiler.')).length, compilerCount);
    assert.deepEqual(input, ids);
  });
  for (const [name, mutate] of mutations) test(`${profile} rejects ${name} without repairing input`, () => {
    const value = mutate(ids), before = structuredClone(value);
    assert.throws(() => validate(value)); assert.deepEqual(value, before);
  });
}
test('current1665 and historical1663 validators never infer, widen or fall back to the other catalog', () => {
  assert.throws(() => fullCurrentRules(HISTORICAL_RULE_IDS), { code: 'current_acceptance_full_rule_vector_required' });
  assert.throws(() => fullRealRules(CURRENT_RULE_IDS), { code: 'real_model_full_rule_vector_required' });
  assert.equal(REAL_MODEL_RULE_VECTOR_DIGEST, 'sha256:fc45c20ddecf87113dc392a3127f4dfa9d2dd75177a71252f8fd9c67929e316b');
  assert.equal(digest(canonical(CURRENT_RULE_IDS)), 'sha256:121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5');
  assert.deepEqual(CONTROL_RULES, CURRENT_RULE_IDS); // The existing current runner fixture is not narrowed.
  assert.deepEqual(CURRENT_RULE_IDS.filter(id => !HISTORICAL_RULE_IDS.includes(id)), CURRENT_ONLY_RULE_IDS);
  assert.deepEqual(CURRENT_RULE_IDS.filter(id => !CURRENT_ONLY_RULE_IDS.includes(id)), HISTORICAL_RULE_IDS);
});
test('only closed explicit capture profile names are accepted; count/digest-shaped or auto selectors are not', () => {
  for (const profile of [null, false, 1665, {}, [], 'auto', 'current', 'historical', '__proto__',
    { count: 1665, digest: digest(canonical(CURRENT_RULE_IDS)) }]) {
    assert.throws(() => new DeliveryReceiptCapture({ expectedProducedRuleVector: profile as ProducedRuleVectorProfile }),
      { code: 'delivery_capture_produced_rule_profile' });
  }
});
for (const added of CURRENT_ONLY_RULE_IDS) test(`current1665 requires ${added}, not a same-size replacement`, () => {
  assert.throws(() => fullCurrentRules(CURRENT_RULE_IDS.filter(id => id !== added)), { code: 'current_acceptance_full_rule_vector_required' });
  assert.throws(() => fullCurrentRules(CURRENT_RULE_IDS.map(id => id === added ? 'zzz.synthetic.substitute' : id).sort()),
    { code: 'current_acceptance_full_rule_vector_changed' });
});

function currentParent() {
  const registration = controlRegistration(), patch = controlPatch(registration, 1, null), raw = controlParent(patch);
  const frozenPatch = freezeCompletedPatch(patch);
  const bound = verifyParentAdmission(frozenPatch, raw.data, null, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE);
  return { raw, frozenPatch, bound };
}
test('current parent declaration, status and both full required coverages retain1665/445 with no qualification upgrade', linuxOnly, () => {
  const f = currentParent(); assert.equal(verifyParentCompletion(f.bound, f.raw.completion), true);
  for (const raw of f.bound.data.declaredPlan.slots as FixtureValue[]) assert.deepEqual(raw.input.requiredRuleIds, CURRENT_RULE_IDS);
  for (const raw of f.bound.data.parent.slots as FixtureValue[]) assert.deepEqual(raw.requiredRuleIds, CURRENT_RULE_IDS);
  for (const child of f.raw.completion.children) {
    const result = child.result as FixtureValue, rows = result.coverage as FixtureValue[];
    assert.deepEqual(rows.map(row => row.ruleId).sort(), CURRENT_RULE_IDS); assert.equal(rows.length, 1665);
    assert.equal(rows.filter(row => row.ruleId.startsWith('ktc.compiler.')).length, 445);
    assert(rows.every(row => row.required === true && row.qualified === false && row.certificateId === null));
    for (const id of CURRENT_ONLY_RULE_IDS) {
      const row = rows.find(row => row.ruleId === id)!;
      assert.equal(row.provenance, 'not_executed'); assert.equal(row.implemented, false); assert.equal(row.tested, false);
    }
    assert.equal(result.status, 'incomplete');
  }
});
const admissionMutations: readonly (readonly [string, unknown, string])[] = [
  ['historical1663', HISTORICAL_RULE_IDS, 'required'],
  ...CURRENT_ONLY_RULE_IDS.map(id => ['missing ' + id, CURRENT_RULE_IDS.filter(value => value !== id), 'required'] as const),
  ['extra ID', [...CURRENT_RULE_IDS, 'zzz.synthetic.extra_rule'].sort(), 'required'],
  ['equal-size wrong ID', [...CURRENT_RULE_IDS.slice(1), 'zzz.synthetic.wrong_rule'].sort(), 'changed'],
  ['equal-size duplicate ID', [...CURRENT_RULE_IDS.slice(1), CURRENT_RULE_IDS[1]!].sort(), 'changed'],
  ['reordered IDs', [...CURRENT_RULE_IDS].reverse(), 'changed'],
  ['null vector', null, 'required'],
  ['object vector', {}, 'required'],
  ['non-string member', [null, ...CURRENT_RULE_IDS.slice(1)], 'required'],
];
for (const [name, rules, code] of admissionMutations) test('current admission rejects ' + name + ' even with all vector and plan hashes rebound', linuxOnly, () => {
  const f = currentParent(), data = structuredClone(f.raw.data), plan = data.declaredPlan as FixtureValue, parent = data.parent as FixtureValue;
  for (const slot of plan.slots) slot.input.requiredRuleIds = structuredClone(rules);
  for (const slot of parent.slots) slot.requiredRuleIds = structuredClone(rules);
  plan.jvmProduced.requiredRulesDigest = digest(canonical(rules)); parent.planDigest = digest(canonical(plan));
  assert.throws(() => verifyParentAdmission(f.frozenPatch, data, null, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE),
    { code: 'current_acceptance_full_rule_vector_' + code });
});
for (const index of [0, 1]) for (const stage of ['declaration', 'status'] as const) {
  test(`current admission independently checks ${stage} slot${index} against current1665`, linuxOnly, () => {
    for (const missing of [false, true]) {
      const f = currentParent(), data = structuredClone(f.raw.data), plan = data.declaredPlan as FixtureValue, parent = data.parent as FixtureValue;
      const slot = stage === 'declaration' ? plan.slots[index].input : parent.slots[index];
      if (missing) delete slot.requiredRuleIds;
      else slot.requiredRuleIds = [...CURRENT_RULE_IDS.slice(1), 'zzz.synthetic.wrong_rule'].sort();
      parent.planDigest = digest(canonical(plan));
      assert.throws(() => verifyParentAdmission(f.frozenPatch, data, null, CONTROL_COMPILER_RULES, CONTROL_OWNER_IMAGE),
        { code: 'current_acceptance_full_rule_vector_' + (missing ? 'required' : 'changed') });
    }
  });
}
const coverageMutations: readonly [string, (result: FixtureValue) => void, string][] = [
  ['missing row', result => { result.coverage.pop(); }, 'completed_whole_vector'],
  ['extra row', result => { result.coverage.push({ ...result.coverage[0], ruleId: 'zzz.synthetic.extra_rule' }); }, 'completed_whole_vector'],
  ['equal-size wrong ID', result => { result.coverage[0].ruleId = 'zzz.synthetic.wrong_rule'; }, 'completed_full_rule_coverage'],
  ['equal-size duplicate ID', result => { result.coverage[0].ruleId = result.coverage[1].ruleId; }, 'completed_full_rule_coverage'],
  ['optional substitute', result => { result.coverage[0].required = false; }, 'completed_whole_vector'],
  ['malformed row', result => { result.coverage[0] = null; }, 'object'],
  ['non-array coverage', result => { result.coverage = null; }, 'completed_whole_vector'],
  ['missing row ID', result => { delete result.coverage[0].ruleId; }, 'identity'],
];
for (const index of [0, 1]) for (const [name, mutate, code] of coverageMutations) {
  test(`current terminal join rejects ${name} in child${index} without narrowing required1665`, linuxOnly, () => {
    const f = currentParent(), completion = structuredClone(f.raw.completion);
    mutate(completion.children[index]!.result as FixtureValue);
    assert.throws(() => verifyParentCompletion(f.bound, completion), { code: 'current_acceptance_' + code });
  });
}
