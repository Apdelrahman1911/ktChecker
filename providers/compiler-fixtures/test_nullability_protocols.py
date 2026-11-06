#!/usr/bin/env python3
"""Pure source inventory/selector tests; no compiler, provider, native helper or process execution."""
from collections import Counter
from contextlib import contextmanager, redirect_stdout, redirect_stderr
from dataclasses import replace
from io import StringIO
from pathlib import Path, PureWindowsPath
import ast
import copy
import hashlib
import json
import re
import tempfile
import unittest
from unittest.mock import patch

import compiler_mapping_contracts as contracts

import nullability_protocols as plan

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / 'providers/compiler-fixtures'
REVIEWED = (
    ('ktc.compiler.common.unsafe_implicit_invoke_call', 'UNSAFE_IMPLICIT_INVOKE_CALL'),
    ('ktc.compiler.common.unsafe_infix_call', 'UNSAFE_INFIX_CALL'),
    ('ktc.compiler.common.unsafe_operator_call', 'UNSAFE_OPERATOR_CALL'),
    ('ktc.compiler.common.unsafe_callable_reference', 'UNSAFE_CALLABLE_REFERENCE'),
    ('ktc.compiler.common.iterator_on_nullable', 'ITERATOR_ON_NULLABLE'),
    ('ktc.compiler.common.unexpected_safe_call', 'UNEXPECTED_SAFE_CALL'),
)


@contextmanager
def legacy_authoring_source_data():
    """Only the exact generator SOURCE inverse; no catalog substitution or executable tree."""
    original_reader = plan.read_existing_source
    relative_generator = 'providers/compiler-fixtures/generate_fixtures.py'
    generator_data = contracts.historical_source_bytes(ROOT, relative_generator)

    def read_with_legacy_authoring(directory, relative):
        if Path(directory).resolve() == ROOT and relative == relative_generator:
            return generator_data
        return original_reader(directory, relative)

    with patch.object(plan, 'read_existing_source', side_effect=read_with_legacy_authoring):
        yield


@contextmanager
def legacy_pre_activation_mapping_data():
    """Closed legacy mapping DATA and exact authoring SOURCE inverse; never a runtime443 loader."""
    original_reader = plan.read_existing_source
    mapping_data = contracts.pre_activation_mapping_bytes(ROOT)

    def read_with_legacy_mapping(directory, relative):
        if Path(directory).resolve() == ROOT and relative == contracts.MAP_REL:
            return mapping_data
        if Path(directory).resolve() == ROOT and relative == 'providers/compiler-fixtures/generate_fixtures.py':
            return contracts.historical_source_bytes(ROOT, relative)
        return original_reader(directory, relative)

    with patch.object(plan, 'read_existing_source', side_effect=read_with_legacy_mapping):
        yield


class NullabilityProtocolsSourceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Affirmative445 production contract, distinct from every frozen legacy443 oracle below.
        cls.current_mapping = contracts.current445(ROOT)
        contracts.require_legacy_runner_refusal_source(ROOT)
        cls.manifest = json.loads((FIXTURES / plan.MANIFEST_PATH).read_bytes())
        cls.gaps = json.loads((FIXTURES / plan.GAP_INVENTORY_PATH).read_bytes())
        cls.evidence = json.loads((FIXTURES / plan.SOURCE_EVIDENCE_PATH).read_bytes())
        cls.helper = (FIXTURES / 'nullability_protocols.py').read_text(encoding='utf-8')
        cls.selected = plan.select_sources(plan.SELECTOR, cls.manifest,
            lambda relative: plan.read_existing_source(FIXTURES, relative))

    def select(self, manifest=None, reader=None, selector=plan.SELECTOR):
        return plan.select_sources(selector, self.manifest if manifest is None else manifest,
            (lambda relative: plan.read_existing_source(FIXTURES, relative)) if reader is None else reader)

    def test_exact_six_common_direct_error_ids_have_existing_maps_not_new_catalog_rows(self):
        self.assertEqual(REVIEWED, tuple((rule_id, upstream_id) for rule_id, upstream_id, _ in plan.REVIEWED_RULES))
        mapped = self.current_mapping['mappings']
        self.assertEqual(445, len(mapped))
        self.assertEqual(443, len(contracts.legacy_mapping(ROOT)['mappings']))
        self.assertFalse(set(contracts.NEW_IDS) & set(plan.RULE_IDS))
        by_id = {row['rule_id']: row for row in mapped}
        ordinary = json.loads((FIXTURES / 'manifest.json').read_bytes())['fixtures']
        graphs = json.loads((FIXTURES / 'planned-kmp-manifest.json').read_bytes())['fixtures']
        represented = {row['rule_id'] for row in ordinary + graphs}
        self.assertEqual((98, 16), (len(ordinary), len(graphs)))
        self.assertFalse(set(plan.RULE_IDS) & represented)
        for rule_id, upstream_id in REVIEWED:
            row = by_id[rule_id]
            self.assertEqual(('common', 'ERROR', 'exact', upstream_id),
                (row['backend'], row['declared_severity'], row['relation'], row['upstream_id']))
            self.assertEqual([], row['known_checker_activation_flags'])

    def test_all_thirty_literal_paths_digests_are_kind_major_distinct_read_once_and_not_attempts(self):
        calls = []
        selected = self.select(reader=lambda relative: calls.append(relative) or plan.read_existing_source(FIXTURES, relative))
        self.assertEqual(30, len(selected))
        self.assertEqual([kind for kind in plan.KINDS for _ in REVIEWED], [entry.kind for entry in selected])
        self.assertEqual(list(range(1, 31)), [entry.source_position for entry in selected])
        self.assertEqual(30, len(set(calls)))
        self.assertEqual([entry.path for entry in selected], calls)
        self.assertEqual(Counter({kind: 6 for kind in plan.KINDS}), Counter(entry.kind for entry in selected))
        for entry in selected:
            expected_path = 'cohort-plans/nullability-protocols-cases/' + entry.upstream_id.lower() + '/' + entry.kind + '.kt'
            self.assertEqual(expected_path, entry.path)
            self.assertEqual(entry.source_sha256, hashlib.sha256(entry.source_bytes).hexdigest())
            self.assertIs(True, entry.description()['position_is_not_an_attempt_number'])
            self.assertIs(False, entry.description()['compiler_execution_witness'])

    def test_full_five_variant_expectations_are_proposals_and_preserve_initial_unrun_fields(self):
        self.assertEqual(('positive', 'negative', 'boundary', 'applicability', 'suppression'), plan.KINDS)
        self.assertEqual(['positive', 'negative', 'configuration', 'version', 'suppression', 'regression'],
                         self.manifest['ordinary_case_kinds'])
        for row in self.manifest['fixtures']:
            self.assertIs(False, row['ordinary_six_kind_assessment'])
            self.assertEqual('unrun_additive_cohort_source_plan', row['status'])
            for entry in row['inputs']:
                kind = entry['kind']
                self.assertEqual('unrun_independent_next_cohort_source_plan', entry['status'])
                self.assertEqual('not_run', entry['execution_state'])
                self.assertIs(False, entry['executed_by_current_pair_harness'])
                self.assertIsNone(entry['expected_outcome_matched'])
                self.assertEqual('none', entry['qualification'])
                self.assertTrue(entry['proposed_expectation'].startswith('Proposed: '))
                if kind == 'positive':
                    self.assertEqual('proposed_nonzero_not_observed', entry['compilation_expectation'])
                    self.assertEqual('proposed_selected_id_present_not_observed', entry['diagnostic_expectation'])
                elif kind == 'suppression':
                    self.assertEqual('unknown_preserve_actual_failure_or_compiler_accepted_suppression', entry['compilation_expectation'])
                    self.assertEqual('unknown_preserve_actual_emission_or_suppression', entry['diagnostic_expectation'])
                else:
                    self.assertEqual('proposed_exit0_not_observed', entry['compilation_expectation'])
                    self.assertEqual('proposed_selected_id_absent_not_observed', entry['diagnostic_expectation'])
                    self.assertIsNone(entry['proposed_source_anchor'])

    def test_pinned_target_language_api_and_applicability_are_explicit_without_feature_plugin_java_graph_traps(self):
        self.assertEqual(('-jvm-target', '17', '-language-version', '2.4', '-api-version', '2.4'), plan.FLAGS)
        profile = self.manifest['target_profile']
        self.assertEqual(('2.4.21', '2.4', '2.4', 'jvm', '17'),
            tuple(profile[k] for k in ('compiler_version', 'language_version', 'api_version', 'backend', 'jvm_target')))
        self.assertEqual('one_independent_Kotlin_file_single_JVM_module', profile['module_mode'])
        self.assertEqual([], profile['plugins'])
        self.assertEqual([], profile['java_sources'])
        self.assertEqual([], profile['user_or_test_libraries'])
        self.assertEqual([], profile['additional_checker_activation_flags'])
        self.assertEqual(['jvm', 'android_jvm', 'desktop_jvm'], profile['potential_launch_targets_requiring_separate_actual_validation'])
        self.assertIs(False, profile['project_settings_authority'])
        for entry in self.selected:
            self.assertEqual(list(plan.FLAGS), entry.description()['compiler_flags'])
            text = entry.source_bytes.decode('utf-8')
            for forbidden in ('context(', 'expect ', 'actual ', '@Jvm', 'java.', '<!', '// FILE:', '// MODULE:', '// LANGUAGE:'):
                self.assertNotIn(forbidden, text)
            self.assertTrue(entry.source_bytes.endswith(b'\n'))
            self.assertNotIn(b'\r', entry.source_bytes)

    def test_tagged_factories_renderer_predicates_and_active_seed_markers_are_source_only(self):
        self.assertEqual('v2.4.21', self.evidence['source_tag'])
        self.assertEqual('6ee2c2026ab8fdde96e6f14b84262dfa3ac22ea7510e82b42489d846735b9202',
                         self.evidence['source_archive']['sha256'])
        self.assertIs(True, self.evidence['source_archive']['whole_existing_archive_rehashed_before_source_read'])
        self.assertIs(True, self.evidence['reused_existing_public_source_cache_without_network_download'])
        self.assertEqual(REVIEWED, tuple((r['rule_id'], r['upstream_id']) for r in self.evidence['records']))
        for row in self.evidence['records']:
            name = row['upstream_id']
            self.assertIn('val ' + name + ':', row['factory']['quote'])
            self.assertIn('"' + name + '", ERROR,', row['factory']['quote'])
            self.assertIs(False, row['factory']['feature_factory'])
            for kind in ('factory', 'renderer', 'predicate_api', 'tagged_seed'):
                reference = row[kind]
                self.assertTrue(reference['url'].startswith('https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/'))
                self.assertRegex(reference['sha256'], '^[0-9a-f]{64}$')
                self.assertIn(name, reference['quote'])
            self.assertEqual(['// RUN_PIPELINE_TILL: FRONTEND'], row['tagged_seed']['harness_directives'])
            self.assertIs(False, row['actual_compiler_execution_observed'])
            self.assertIs(True, row['tagged_seed']['not_a_replayed_full_upstream_suite'])
        quotes = '\n'.join(r['quote'] for r in self.evidence['API_scope_references'])
        self.assertIn('val LATEST_STABLE = KOTLIN_2_4', quotes)
        self.assertIn('public annotation class Suppress(vararg val names: String)', quotes)
        self.assertIn('UNSPECIFIED and WILL NOT BE PRESERVED', quotes)

    def test_authored_boundary_and_applicability_sources_cover_real_neighboring_receiver_forms(self):
        sources = {(entry.upstream_id, entry.kind): entry.source_bytes.decode('utf-8') for entry in self.selected}
        checks = {
            ('UNSAFE_IMPLICIT_INVOKE_CALL', 'positive'): 'box.action()',
            ('UNSAFE_IMPLICIT_INVOKE_CALL', 'boundary'): 'if (stableAction == null) return 0',
            ('UNSAFE_IMPLICIT_INVOKE_CALL', 'applicability'): 'box.action?.invoke() ?: 0',
            ('UNSAFE_INFIX_CALL', 'positive'): 'receiver add 1',
            ('UNSAFE_INFIX_CALL', 'applicability'): 'infix fun NullableInfixValue?.addOrZero',
            ('UNSAFE_OPERATOR_CALL', 'positive'): 'receiver + 1',
            ('UNSAFE_OPERATOR_CALL', 'boundary'): 'typealias OperatorAlias = OperatorValue',
            ('UNSAFE_OPERATOR_CALL', 'applicability'): 'operator fun NullableOperatorValue?.plus',
            ('UNSAFE_CALLABLE_REFERENCE', 'positive'): 'receiver::score',
            ('UNSAFE_CALLABLE_REFERENCE', 'boundary'): 'if (receiver == null) return null',
            ('UNSAFE_CALLABLE_REFERENCE', 'applicability'): '(ReferenceValue, Int) -> Int = ReferenceValue::score',
            ('ITERATOR_ON_NULLABLE', 'positive'): 'for (value in range)',
            ('ITERATOR_ON_NULLABLE', 'applicability'): 'operator fun NullableRangeValue?.iterator()',
            ('UNEXPECTED_SAFE_CALL', 'positive'): 'super?.score()',
            ('UNEXPECTED_SAFE_CALL', 'negative'): 'super.score()',
            ('UNEXPECTED_SAFE_CALL', 'boundary'): 'this.score()',
            ('UNEXPECTED_SAFE_CALL', 'applicability'): 'receiver?.score() ?: 0',
        }
        for key, token in checks.items():
            self.assertIn(token, sources[key], key)
        for name in ('UNSAFE_INFIX_CALL', 'UNSAFE_OPERATOR_CALL'):
            self.assertNotIn('fun add(', sources[(name, 'applicability')])
        self.assertNotIn('?.score()', sources[('UNEXPECTED_SAFE_CALL', 'boundary')])
        for row in self.manifest['fixtures']:
            self.assertEqual(5, len({hashlib.sha256((FIXTURES / r['path']).read_bytes()).hexdigest() for r in row['inputs']}))

    def test_suppression_is_independently_authored_exact_file_annotation_with_unknown_hard_error_outcomes(self):
        sources = {(entry.upstream_id, entry.kind): entry.source_bytes for entry in self.selected}
        self.assertIn('hard errors may remain or fail', self.manifest['suppression_policy'])
        self.assertIn('Even exit0 is unvalidated', self.manifest['suppression_policy'])
        self.assertIn('UNSPECIFIED and WILL NOT BE PRESERVED', self.manifest['suppression_policy'])
        for _, name in REVIEWED:
            expected = ('@file:kotlin.Suppress("' + name + '")\n').encode() + sources[(name, 'positive')]
            self.assertEqual(expected, sources[(name, 'suppression')])
            selected = next(entry for entry in self.selected if entry.upstream_id == name and entry.kind == 'suppression')
            self.assertEqual('unknown_unvalidated', selected.description()['suppression_expectation_validation'])
            self.assertIs(False, selected.description()['qualified'])

    def test_explicit_source_selector_has_no_default_all_and_rejects_foreign_duplicate_or_missing_ids_kinds(self):
        for selector in ('all', 'original', 'type_boundaries', 'type_boundaries_supplemental', '', None):
            with self.subTest(selector=selector), self.assertRaises(ValueError):
                self.select(selector=selector)
        variants = []
        removed = copy.deepcopy(self.manifest); removed['fixtures'].pop(); variants.append(removed)
        doubled = copy.deepcopy(self.manifest); doubled['fixtures'][0] = doubled['fixtures'][1]; variants.append(doubled)
        foreign = copy.deepcopy(self.manifest); foreign['fixtures'][0]['rule_id'] = 'ktc.compiler.common.unsafe_call'; variants.append(foreign)
        missing_kind = copy.deepcopy(self.manifest); missing_kind['fixtures'][0]['inputs'].pop(); variants.append(missing_kind)
        duplicate_kind = copy.deepcopy(self.manifest); duplicate_kind['fixtures'][0]['inputs'][0] = duplicate_kind['fixtures'][0]['inputs'][1]; variants.append(duplicate_kind)
        for manifest in variants:
            with self.subTest(manifest=manifest['fixtures'][0]['rule_id']), self.assertRaises(ValueError):
                self.select(manifest)

    def test_manifest_reordering_is_normalized_without_retries_or_source_batching(self):
        reordered = copy.deepcopy(self.manifest)
        reordered['fixtures'].reverse()
        for row in reordered['fixtures']:
            row['inputs'].reverse()
        self.assertEqual(self.selected, self.select(reordered))
        self.assertEqual(30, len(self.selected))
        self.assertEqual(30, len({entry.path for entry in self.selected}))

    def test_source_hash_path_flags_severity_initial_status_unknown_expectation_and_anchor_tampering_fail_closed_under_O(self):
        mutators = (
            lambda m: m['fixtures'][0]['inputs'][0].update(path='cases/unsafe_call/positive.kt'),
            lambda m: m['fixtures'][0]['inputs'][0].update(source_sha256='0' * 64),
            lambda m: m['fixtures'][0]['inputs'][0].update(compiler_flags=['-jvm-target', '21']),
            lambda m: m['fixtures'][0].update(declared_severity='warning'),
            lambda m: m['fixtures'][0]['inputs'][0].update(status='observed_pass'),
            lambda m: m['fixtures'][0]['inputs'][0].update(execution_state='executed'),
            lambda m: m['fixtures'][0]['inputs'][0].update(expected_outcome_matched=True),
            lambda m: m['fixtures'][0]['inputs'][-1].update(compilation_expectation='proposed_exit0_not_observed'),
            lambda m: m['target_profile'].update(plugins=['fake']),
            lambda m: m.update(run_complete=True),
            lambda m: m.update(execution_schedule='run_everything_now'),
            lambda m: m['bookkeeping'].update(new_qualification_count=1),
            lambda m: m['fixtures'][0]['inputs'][0]['proposed_source_anchor'].update(token=''),
            lambda m: m['fixtures'][0]['inputs'][0]['proposed_source_anchor'].update(line=True),
        )
        for index, mutate in enumerate(mutators):
            manifest = copy.deepcopy(self.manifest); mutate(manifest)
            with self.subTest(index=index), self.assertRaises(ValueError):
                self.select(manifest)
        def wrong_bytes(relative):
            data = plan.read_existing_source(FIXTURES, relative)
            return data + b'\n' if relative == self.selected[0].path else data
        with self.assertRaises(ValueError): self.select(reader=wrong_bytes)
        with self.assertRaises(ValueError): self.select(reader=lambda relative: plan.read_existing_source(FIXTURES, relative).decode())

    def test_bookkeeping_keeps_existing98_graph16_map443_supplemental18_and_all278_bytes_unchanged(self):
        # Actual445 must refuse the unchanged443 inventory before any source description or admission.
        with self.assertRaisesRegex(ValueError, 'Preexisting source authoring bytes changed: providers/compiler-fixtures/generate_fixtures.py'):
            plan.validate_bookkeeping(ROOT, self.manifest)
        # Restoring only legacy authoring SOURCE DATA must still refuse the actual445 map.
        with legacy_authoring_source_data(), self.assertRaisesRegex(ValueError, 'Do not overwrite or pad the existing98, graph16 or exact443 map'):
            plan.validate_bookkeeping(ROOT, self.manifest)
        # The old nullability ledger predates four common-checker corrections; use exact0db3 DATA only.
        with legacy_pre_activation_mapping_data():
            counts = plan.validate_bookkeeping(ROOT, self.manifest)
        self.assertEqual((98, 16, 443, 18, 278, 30, 308, 104, 329, 323), tuple(counts[key] for key in (
            'existing_main_manifest_ordinary_pairs', 'existing_planned_unsupported_graphs', 'existing_exact_mappings',
            'existing_type_boundary_authored_supplemental_files', 'preexisting_kotlin_fixture_sources', 'new_authored_files',
            'combined_authored_kotlin_sources_in_this_sparse_copy', 'prospective_ordinary_pairs_only_if_lead_approves_append',
            'current_remaining_mapped_gaps', 'prospective_remaining_mapped_gaps_if_source_plans_append')))
        self.assertEqual({'common': 202, 'ir': 8, 'jvm': 76, 'native': 43}, counts['current_remaining_mapped_by_backend'])
        self.assertEqual({'common': 196, 'ir': 8, 'jvm': 76, 'native': 43}, counts['prospective_remaining_mapped_by_backend'])
        self.assertEqual('378127f94364be6ac94bf84d56769ea25126f28840686f03fa5adafc55c7bc00', counts['preexisting_fixture_inventory_sha256'])
        self.assertIs(False, counts['new_six_kind_completion'])
        self.assertEqual(0, counts['new_qualification_count'])
        self.assertEqual(0, counts['new_source_selector_provider_reservations'])
        historical = list((FIXTURES / 'cases').rglob('*.kt')) + list((FIXTURES / 'planned-kmp-cases').rglob('*.kt'))
        standalone = list((FIXTURES / 'cohort-plans/nullability-protocols-cases').rglob('*.kt'))
        self.assertEqual((278, 30), (len(historical), len(standalone)))
        self.assertEqual({entry.path for entry in self.selected}, {p.relative_to(FIXTURES).as_posix() for p in standalone})
        self.assertFalse(set(historical) & set(standalone))

    def test_remaining_gap_inventory_enumerates_every329_mapped_and609_unmapped_JVM_relevant_factories_without_support_claims(self):
        mapped = self.gaps['remaining_mapped_gaps']
        unmapped = self.gaps['unmapped_common_and_JVM_factory_gaps']
        self.assertEqual((329, 609), (len(mapped), len(unmapped)))
        self.assertEqual(set(plan.RULE_IDS), {r['rule_id'] for r in mapped if r['selected_for_this_additive_source_plan']})
        self.assertEqual(329, len({r['rule_id'] for r in mapped}))
        self.assertEqual(609, len({(r['backend'], r['name']) for r in unmapped}))
        self.assertTrue(all(r['actual_execution_or_qualification_claim'] is False for r in mapped))
        self.assertTrue(all(r['backend'] in ('common', 'jvm') and r['disposition'].startswith('unmapped ') for r in unmapped))
        ledger = self.gaps['bounded_upstream_factory_ledger']
        self.assertEqual((1061, 443, 612, 5, 1), tuple(ledger[k] for k in ('factory_entries', 'mapped_planned_entries', 'unmapped_entries', 'excluded_task_or_meta_contracts', 'same_owner_aliases')))
        self.assertEqual({'common': 598, 'jvm': 11, 'native': 3}, ledger['unmapped_entries_by_backend'])
        self.assertIs(True, self.gaps['classification_is_not_implementation_support_or_execution_proof'])
        self.assertEqual('COMPONENT_FUNCTION_ON_NULLABLE', self.gaps['deliberately_deferred_neighbor']['upstream_id'])
        self.assertIn('does not force', self.gaps['deliberately_deferred_neighbor']['reason'])

    def test_pure_source_description_cannot_launder_thirty_counts_into_execution_or_promote_tampered_id_bytes_order(self):
        # Actual445 must refuse the unchanged443 inventory before any source description or admission.
        with self.assertRaisesRegex(ValueError, 'Preexisting source authoring bytes changed: providers/compiler-fixtures/generate_fixtures.py'):
            plan.validate_bookkeeping(ROOT, self.manifest)
        # Restoring only legacy authoring SOURCE DATA must still refuse the actual445 map.
        with legacy_authoring_source_data(), self.assertRaisesRegex(ValueError, 'Do not overwrite or pad the existing98, graph16 or exact443 map'):
            plan.validate_bookkeeping(ROOT, self.manifest)
        # The old nullability ledger predates four common-checker corrections; use exact0db3 DATA only.
        with legacy_pre_activation_mapping_data():
            counts = plan.validate_bookkeeping(ROOT, self.manifest)
        description = plan.describe_source_selection(self.selected, counts)
        for field in ('run_complete', 'qualified', 'ordinary_six_kind_assessments_complete', 'ordinary_external_latest_sidecar_writes'):
            self.assertIs(False, description[field])
        self.assertIsNone(description['executable_schedule'])
        self.assertEqual(0, description['compiler_calls_launched_by_this_helper'])
        self.assertEqual('unknown_unvalidated', description['suppression_expectations'])
        for altered in (self.selected[:-1], self.selected[::-1], (self.selected[0],) * 30,
                        (replace(self.selected[0], source_bytes=b'fake'),) + self.selected[1:],
                        (replace(self.selected[0], rule_id='ktc.compiler.common.unsafe_call'),) + self.selected[1:]):
            with self.assertRaises(ValueError): plan.describe_source_selection(altered, counts)
        bad_counts = counts.copy(); bad_counts['new_six_kind_completion'] = True
        with self.assertRaises(ValueError): plan.describe_source_selection(self.selected, bad_counts)

    def test_source_cli_is_read_only_and_never_prints_bodies_or_schedules_processes(self):
        pins = {relative: (ROOT / relative).read_bytes() for relative, _ in plan.BASELINE_SOURCE_PINS}
        refused_output = StringIO()
        with redirect_stdout(refused_output), self.assertRaisesRegex(ValueError, 'Preexisting source authoring bytes changed: providers/compiler-fixtures/generate_fixtures.py'):
            plan.main(['--selector', plan.SELECTOR, '--root', str(ROOT)])
        self.assertEqual('', refused_output.getvalue())
        with legacy_authoring_source_data(), redirect_stdout(refused_output), self.assertRaisesRegex(ValueError, 'exact443 map'):
            plan.main(['--selector', plan.SELECTOR, '--root', str(ROOT)])
        self.assertEqual('', refused_output.getvalue())
        output = StringIO()
        with legacy_pre_activation_mapping_data(), redirect_stdout(output):
            self.assertEqual(0, plan.main(['--selector', plan.SELECTOR, '--root', str(ROOT)]))
        description = json.loads(output.getvalue())
        self.assertEqual(30, len(description['sources']))
        self.assertTrue(all('source_bytes' not in row and 'source_body' not in row for row in description['sources']))
        self.assertIs(True, description['no_compiler_execution_authority'])
        for relative, before in pins.items(): self.assertEqual(before, (ROOT / relative).read_bytes())
        for argv in ([], ['--selector', 'all'], ['--selector', 'type_boundaries_supplemental']):
            with redirect_stderr(StringIO()), self.assertRaises(SystemExit): plan.main(argv)

    def test_source_helper_has_no_process_provider_generator_evidence_writer_or_safety_asserts(self):
        tree = ast.parse(self.helper)
        self.assertFalse(any(isinstance(node, ast.Assert) for node in ast.walk(tree)))
        forbidden_imports = ('subprocess', 'socket', 'urllib', 'requests', 'generate_fixtures', 'cohort_expansion')
        for node in ast.walk(tree):
            if isinstance(node, ast.Import): self.assertTrue(all(alias.name not in forbidden_imports for alias in node.names))
            if isinstance(node, ast.ImportFrom): self.assertNotIn(node.module, forbidden_imports)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                self.assertNotIn(node.func.attr, ('write_bytes', 'write_text', 'mkdir', 'unlink', 'remove', 'replace', 'Popen', 'run', 'execute', 'analyze', 'analyzeObserved'))
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
                self.assertNotIn(node.func.id, ('exec', 'eval', 'compile', 'open'))
        for token in ('ProcessBuilder', 'KotlinJvmCompilerProvider', 'CompilerFixtureRunner', 'ProcessOutcome', 'latest.json'):
            self.assertNotIn(token, self.helper)
        self.assertNotIn('"supplemental_complete"', self.helper)

    def test_portable_paths_are_exact_POSIX_and_no_symlink_traversal_drive_absolute_or_NUL_sources(self):
        for entry in self.selected:
            windows = PureWindowsPath('cohort-plans', 'nullability-protocols-cases', entry.upstream_id.lower(), entry.kind + '.kt')
            self.assertEqual(entry.path, windows.as_posix())
            self.assertEqual(entry.path, plan.logical_path(entry.path).as_posix())
        for path in ('../outside.kt', '/tmp/outside.kt', 'cases//x/positive.kt', './cases/x.kt', 'C:/outside.kt',
                     'cases\\x\\positive.kt', 'cases/x/\x00.kt', ''):
            with self.subTest(path=path), self.assertRaises(ValueError): plan.logical_path(path)
        with tempfile.TemporaryDirectory(prefix='nullability-source-path-test-') as td:
            base = Path(td)
            (base / 'actual.kt').write_bytes(b'package example\n')
            (base / 'alias.kt').symlink_to('actual.kt')
            self.assertEqual(b'package example\n', plan.read_existing_source(base, 'actual.kt'))
            with self.assertRaises(ValueError): plan.read_existing_source(base, 'alias.kt')
            with self.assertRaises(ValueError): plan.read_existing_source(base, 'absent.kt')
            (base / 'actual-dir').mkdir()
            (base / 'actual-dir/source.kt').write_bytes(b'package example\n')
            (base / 'alias-dir').symlink_to('actual-dir', target_is_directory=True)
            with self.assertRaises(ValueError): plan.read_existing_source(base, 'alias-dir/source.kt')


if __name__ == '__main__':
    unittest.main()
