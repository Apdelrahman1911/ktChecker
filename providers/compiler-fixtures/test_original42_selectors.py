#!/usr/bin/env python3
"""Static source/selection contract; never a Kotlin/compiler/provider execution test."""
from collections import Counter
from pathlib import Path
import hashlib
import json
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / 'providers/compiler-fixtures'
KOTLIN = ROOT / 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler'
REVIEWED_BOUNDS = {
    'original_calls_a': (6, 17, 47),
    'original_calls_b': (6, 17, 47),
    'original_calls_c': (2, 13, 23),
    'original_flow_errors': (6, 17, 47),
    'original_flow_warnings': (6, 12, 48),
    'original_flow_extra': (1, 12, 18),
    'original_jvm_errors_a': (6, 17, 47),
    'original_jvm_errors_b': (6, 17, 47),
    'original_jvm_warnings': (2, 12, 24),
    'original_experimental': (1, 12, 18),
}
LEGACY_SELECTORS = {'original', 'overrides', 'visibility', 'types_conventions',
                    'inline_constructors', 'opt_in', 'jvm', 'type_boundaries', 'all'}


def literal_original_id_map(controls):
    """Require a literal map, rejecting unmatched expressions rather than evaluating Kotlin."""
    start = controls.index('val ORIGINAL_ID_COHORTS:')
    end = controls.index('val ORIGINAL_RULE_IDS:', start)
    expression = re.search(r'=\s*linkedMapOf\((.*)\)\s*$', controls[start:end], re.DOTALL)
    if expression is None:
        raise ValueError('Original subsets must be an explicit literal stable-ID map')
    entries = re.compile(r'"([a-z_]+)"\s+to\s+setOf\((.*?)\)\s*,', re.DOTALL)
    body = expression.group(1)
    if entries.sub('', body).strip():
        raise ValueError('Unmatched expression in original subset map')
    result = {}
    for match in entries.finditer(body):
        name, members = match.groups()
        if name in result:
            raise ValueError('Duplicate original subset name')
        ids = re.findall(r'"(ktc\.compiler\.(?:common|jvm)\.[a-z0-9_]+)"', members)
        remaining = re.sub(r'"ktc\.compiler\.(?:common|jvm)\.[a-z0-9_]+"\s*,', '', members)
        if remaining.strip() or not ids or len(ids) != len(set(ids)):
            raise ValueError('Original subset members must be unique explicit stable IDs')
        result[name] = set(ids)
    return result


class Original42SelectorSourceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((FIXTURES / 'manifest.json').read_text())
        cls.proposal = json.loads((FIXTURES / 'original42-partition-proposal.json').read_text())
        cls.controls = (KOTLIN / 'CompilerFixtureControls.kt').read_text()
        cls.runner = (KOTLIN / 'CompilerFixtureRunner.kt').read_text()
        cls.selectors = literal_original_id_map(cls.controls)
        cls.original = {row['rule_id']: row for row in cls.manifest['fixtures'] if 'authored_cohort' not in row}
        cls.mappings = {row['rule_id']: row for row in json.loads(
            (ROOT / 'modules/provider-compiler/src/main/resources/compiler-rule-map.json').read_text())['mappings']}

    def test_ten_literal_selectors_match_reviewed_ids_and_partition_exactly_original42(self):
        descriptor = {group['proposed_selector_name']: {member['rule_id'] for member in group['members']}
                      for group in self.proposal['recommended_bounded_subcohorts']}
        self.assertEqual(set(REVIEWED_BOUNDS), set(self.selectors))
        self.assertEqual(descriptor, self.selectors)
        self.assertEqual(42, len(self.original))
        self.assertEqual(42, sum(len(ids) for ids in self.selectors.values()))
        self.assertEqual(set(self.original), set().union(*self.selectors.values()))
        self.assertEqual(98, len(self.manifest['fixtures']))
        self.assertEqual(443, len(self.mappings))
        self.assertTrue(set(self.original) <= self.mappings.keys())
        for name, ids in self.selectors.items():
            self.assertEqual(REVIEWED_BOUNDS[name][0], len(ids))
            self.assertFalse(ids & {row['rule_id'] for row in self.manifest['fixtures'] if 'authored_cohort' in row})
        planned = json.loads((FIXTURES / 'planned-kmp-manifest.json').read_text())
        self.assertEqual(16, len(planned['fixtures']))
        self.assertFalse(set(self.original) & {row['rule_id'] for row in planned['fixtures']})

    def test_source_status_preserves_bytes_flags_and_historical_failed_controls(self):
        self.assertEqual('source_only_partition_selectors_implemented_not_executed', self.proposal['status'])
        self.assertIs(False, self.proposal['execution_or_selection_authority'])
        self.assertEqual({'implemented': False, 'tested': False, 'qualified': False, 'attempted': False},
                         self.proposal['evidence_promotion'])
        implementation = self.proposal['source_only_selector_implementation']
        self.assertIs(True, implementation['trusted_harness_source_support'])
        self.assertEqual('not_run_in_this_worktree', implementation['compiler_or_subgroup_execution_state'])
        for group in self.proposal['recommended_bounded_subcohorts']:
            self.assertEqual('source_selector_implemented_not_executed', group['status'])
            for member in group['members']:
                row = self.original[member['rule_id']]
                self.assertEqual(row['flags'], member['unchanged_flags'])
                self.assertEqual(row['declared_severity'], member['declared_severity'])
                self.assertEqual(self.mappings[row['rule_id']]['backend'], member['mapped_backend'])
                for kind in ('positive', 'negative'):
                    self.assertEqual(row[kind], member[kind]['path'])
                    path = FIXTURES / row[kind]
                    self.assertEqual(path, path.resolve())
                    self.assertFalse(path.is_symlink())
                    self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), member[kind]['sha256'])
        extra = 'ktc.compiler.common.unreachable_code'
        empty = 'ktc.compiler.common.empty_range'
        self.assertEqual({extra}, self.selectors['original_flow_extra'])
        self.assertEqual(['-jvm-target', '17', '-Wextra'], self.original[extra]['flags'])
        self.assertEqual({empty}, self.selectors['original_experimental'])
        self.assertEqual(['-jvm-target', '17', '-Xuse-fir-experimental-checkers'], self.original[empty]['flags'])
        retained = self.proposal['empty_range_failure_retention']
        self.assertEqual(['negative', 'configuration'], retained['historical_failed_assessment_kinds'])
        self.assertEqual(self.original[empty]['flags'], retained['unchanged_flags'])
        self.assertRegex(retained['historical_run_evidence']['sha256'], r'^[0-9a-f]{64}$')

    def test_exactly_eleven_runner_selection_calls_bind_the_same_fixture_stable_id(self):
        calls = re.findall(r'\bsettings\.selects\(([^)]*)\)', self.runner)
        self.assertEqual(Counter({'it.authoredCohort, it.ruleId': 4,
                                  'fixture.authoredCohort, fixture.ruleId': 7}), Counter(calls))
        self.assertIn('allFixtures.size == 98', self.runner)
        self.assertIn('allFixtures.map { fixture ->', self.runner)  # Full visible input inventory precedes selection.
        self.assertIn('"planned_unsupported_common_jvm_graphs" to 16', self.runner)
        self.assertIn('"catalog_mappings" to 443', self.runner)
        self.assertRegex(self.controls, r'fun selects\(authoredCohort: String\?, ruleId: String\? = null\)')

    def test_legacy_predicates_and_no_default_all_remain_while_new_id_subsets_fail_closed(self):
        selection = self.controls[self.controls.index('    fun selects('):self.controls.index('    fun description(')]
        self.assertIn('val name = authoredName(authoredCohort)', selection)
        self.assertIn('ORIGINAL_ID_COHORTS[cohort] ?: return cohort == "all" || cohort == name', selection)
        self.assertIn('requireNotNull(ruleId)', selection)
        self.assertIn('stableId.isNotBlank()', selection)
        self.assertIn("'\\u0000' !in stableId", selection)
        self.assertIn('if (name != "original") return false', selection)
        self.assertIn('require(stableId in ORIGINAL_RULE_IDS)', selection)
        self.assertIn('return stableId in selectedIds', selection)
        legacy = re.search(r'AUTHORED_COHORT_SELECTORS = setOf\((.*?)\)', self.controls, re.DOTALL)
        self.assertIsNotNone(legacy)
        self.assertEqual(LEGACY_SELECTORS, set(re.findall(r'"([a-z_]+)"', legacy.group(1))))
        self.assertIn('val COHORTS = AUTHORED_COHORT_SELECTORS + ORIGINAL_ID_COHORTS.keys', self.controls)
        for name in ('original_calls', 'original_flow_types', 'original_jvm', 'original_calls_d'):
            self.assertNotIn(name, LEGACY_SELECTORS | self.selectors.keys())
        self.assertIn('options["cohort"] ?: error("Explicit --cohort=', self.controls)
        for hard_bound in ('MAX_ATTEMPTS = 48', 'MAX_OVERALL_MILLIS = 1_200_000L',
                           'MAX_PER_JOB_MILLIS = 90_000L', 'MAX_EVIDENCE_BYTES = 268_435_456L'):
            self.assertIn(hard_bound, self.controls)

    def test_each_full_conditional_source_schedule_recomputes_within_48_admissions(self):
        phases = ('positive', 'negative', 'warning-disabled', 'configuration', 'version', 'suppression', 'regression')
        for group in self.proposal['recommended_bounded_subcohorts']:
            name = group['proposed_selector_name']
            rows = [self.original[rule_id] for rule_id in self.selectors[name]]
            phase_counts = {}
            for kind in phases:
                selected = [row for row in rows if row['declared_severity'] == 'warning'] if kind == 'warning-disabled' else rows
                keys = set()
                for row in selected:
                    flags = row['flags'].copy()
                    if kind == 'configuration' and row['declared_severity'] == 'error':
                        flags[flags.index('-jvm-target') + 1] = '21'
                    if kind == 'version':
                        flags += ['-language-version', '2.4', '-api-version', '2.4']
                    split = kind != 'negative'
                    keys.add((tuple(flags), row['declared_severity'] if split else None,
                              self.mappings[row['rule_id']]['backend'] if split else None))
                phase_counts[kind] = len(keys)
            errors = sum(row['declared_severity'] == 'error' for row in rows)
            warnings = len(rows) - errors
            baseline = sum(phase_counts.values()) + errors + 1 + 4
            conditional = 5 * len(rows) + warnings
            plan = group['static_schedule']
            self.assertEqual(phase_counts, plan['phase_group_provider_calls'])
            self.assertEqual(REVIEWED_BOUNDS[name], (len(rows), baseline, baseline + conditional))
            self.assertEqual(baseline, plan['no_fallback_provider_admissions'])
            self.assertEqual(baseline - 1, plan['no_fallback_child_capable_calls'])
            self.assertEqual(conditional, plan['conditional_single_rerun_upper_bound'])
            self.assertEqual(baseline + conditional, plan['all_conditional_reruns_provider_admissions_bound'])
            self.assertLessEqual(baseline + conditional, 48)
            self.assertIs(True, plan['counts_are_source_plan_not_execution'])

    def test_authoring_inventory_is_separate_from_mutable_execution_aggregates(self):
        inventory_path = FIXTURES / 'authored-input-inventory.json'
        self.assertEqual(FIXTURES, inventory_path.parent)
        inventory = json.loads(inventory_path.read_text())
        self.assertIs(True, inventory['source_only'])
        self.assertEqual('authored_input_inventory_source_plan', inventory['kind'])
        self.assertIs(False, inventory['execution_receipts_modified_by_generator'])
        self.assertIs(False, inventory['execution_or_project_policy_authority'])
        self.assertEqual({'implemented': False, 'tested': False, 'qualified': False, 'attempted': False},
                         inventory['per_id_evidence_promotion_from_authorship'])
        for name, relative in (('ordinary_manifest', 'manifest.json'), ('planned_kmp_manifest', 'planned-kmp-manifest.json')):
            self.assertEqual(hashlib.sha256((FIXTURES / relative).read_bytes()).hexdigest(), inventory[name]['sha256'])
        contract = (FIXTURES / 'test_type_boundaries.py').read_text()
        self.assertNotIn('test_current_sidecar_has_no_per_id_evidence_promotion', contract)
        self.assertNotIn('current_authored_input_inventory', contract)
        self.assertIn('test_source_generation_preserves_independently_seeded_execution_receipts', contract)


if __name__ == '__main__':
    unittest.main()
