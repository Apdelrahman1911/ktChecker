/** SYNTHETIC SOURCE fixtures only. No owner/host/compiler/model operation or authority.
 * Current IDs always come from ALL four current catalogs. Historical IDs come from the
 * separately frozen kernel SOURCE fixture, never by narrowing the current runner fixture. */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { canonical, digest } from '../src/journal.js';
import { fullCurrentRules, type ProducedRuleVectorProfile } from './delivery-receipts.js';
import { fullRealRules } from './real-model-delivery.js';

export const CURRENT_RULE_IDS = Object.freeze(fullCurrentRules(['core', 'libraries', 'platform', 'ecosystem'].flatMap(name =>
  (JSON.parse(readFileSync(new URL('../../../../data/rules-' + name + '.json', import.meta.url), 'utf8')) as { id: string }[])
    .map(row => row.id)).sort()));
const historicalText = readFileSync(new URL('../../../../modules/kernel/src/test/resources/joint-completion-full-catalog-1663.txt', import.meta.url), 'utf8');
assert.equal(digest(historicalText), 'sha256:4c00ec83059325b8ee2fac672e6c53948746bda6920d900a9d1942ce1b03520d');
assert(historicalText.endsWith('\n'));
export const HISTORICAL_RULE_IDS = Object.freeze(fullRealRules(historicalText.slice(0, -1).split('\n')));
const currentText = readFileSync(new URL('../../../../modules/kernel/src/test/resources/joint-completion-full-catalog-1665.txt', import.meta.url), 'utf8');
assert.equal(digest(currentText), 'sha256:f7bc5eb975499c10ced7fc10ec7938984f98eb19079f9fcce32d451a8a5de431');
assert(currentText.endsWith('\n'));
assert.deepEqual(CURRENT_RULE_IDS, currentText.slice(0, -1).split('\n'));
export const CURRENT_ONLY_RULE_IDS = Object.freeze([
  'ktc.compiler.common.error_suppression', 'ktc.compiler.common.inapplicable_operator_modifier',
]);
assert.deepEqual(CURRENT_RULE_IDS.filter(id => !HISTORICAL_RULE_IDS.includes(id)), CURRENT_ONLY_RULE_IDS);
assert.deepEqual(CURRENT_RULE_IDS.filter(id => !CURRENT_ONLY_RULE_IDS.includes(id)), HISTORICAL_RULE_IDS);
assert.equal(digest(canonical(CURRENT_RULE_IDS)), 'sha256:121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5');
assert.equal(digest(canonical(HISTORICAL_RULE_IDS)), 'sha256:fc45c20ddecf87113dc392a3127f4dfa9d2dd75177a71252f8fd9c67929e316b');
export function syntheticRuleVector(profile: ProducedRuleVectorProfile): readonly string[] {
  if (profile === 'historical-full1663') return HISTORICAL_RULE_IDS;
  if (profile === 'current-full1665') return CURRENT_RULE_IDS;
  throw new Error('synthetic_rule_vector_profile_required');
}
