#!/usr/bin/env python3
"""Static authored-input contract only; no compiler/provider/JVM process is used."""
from pathlib import Path, PurePosixPath, PureWindowsPath
import ast
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest

from cohort_expansion import TYPE_BOUNDARY_INPUTS


ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / 'providers/compiler-fixtures'


class TypeBoundaryInputContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((FIXTURES / 'manifest.json').read_text())
        cls.mappings = {row['upstream_id']: row for row in json.loads(
            (ROOT / 'modules/provider-compiler/src/main/resources/compiler-rule-map.json').read_text())['mappings']}
        cls.rows = [row for row in cls.manifest['fixtures']
                    if row.get('authored_cohort') == '2026-11-04-type_boundaries']

    def source(self, relative):
        path = FIXTURES / relative
        self.assertEqual(path.resolve(), path)
        self.assertFalse(path.is_symlink())
        self.assertTrue(path.is_file())
        return path.read_bytes()

    def test_six_exact_common_errors_extend_ninety_two_pairs_without_padding(self):
        self.assertEqual(98, len(self.manifest['fixtures']))
        self.assertEqual(98, len({row['rule_id'] for row in self.manifest['fixtures']}))
        self.assertEqual(set(TYPE_BOUNDARY_INPUTS), {row['upstream_id'] for row in self.rows})
        self.assertEqual(92, len([row for row in self.manifest['fixtures'] if row not in self.rows]))
        for row in self.rows:
            mapping = self.mappings[row['upstream_id']]
            self.assertEqual(row['rule_id'], mapping['rule_id'])
            self.assertEqual(('exact', 'common', 'ERROR'),
                             (mapping['relation'], mapping['backend'], mapping['declared_severity']))
            self.assertEqual([], mapping['known_checker_activation_flags'])
            self.assertEqual('error', row['declared_severity'])
            self.assertEqual(['-jvm-target', '17'], row['flags'])

    def test_original_positive_and_corrected_control_are_distinct_unrun_sources(self):
        for row in self.rows:
            self.assertEqual('unrun_synthetic_input', row['status'])
            self.assertNotEqual(self.source(row['positive']), self.source(row['negative']))
            plan = row['validation_plan']
            self.assertTrue({'positive', 'negative', 'configuration', 'version', 'suppression', 'regression'} <= plan.keys())
            self.assertEqual('not_run; source-only authorship', plan['evidence_state'])
            self.assertTrue(plan['legitimate_or_intentional_alternative'])

    def test_supplemental_inputs_are_independent_hashed_plans_never_executed_pair_jobs(self):
        paths = []
        for row in self.rows:
            self.assertEqual({'boundary', 'applicability', 'suppression'},
                             {variant['kind'] for variant in row['supplemental_inputs']})
            self.assertEqual(3, len(row['supplemental_inputs']))
            for variant in row['supplemental_inputs']:
                paths.append(variant['path'])
                self.assertNotIn(variant['path'], [row['positive'], row['negative']])
                self.assertEqual('unrun_supplemental_source_plan', variant['status'])
                self.assertEqual('not_run', variant['execution_state'])
                self.assertIs(False, variant['executed_by_current_pair_harness'])
                self.assertEqual(row['flags'], variant['compiler_flags'])
                self.assertEqual(hashlib.sha256(self.source(variant['path'])).hexdigest(), variant['source_sha256'])
                self.assertTrue(variant['proposed_expectation'].startswith('Proposed:'))
        self.assertEqual(18, len(paths))
        self.assertEqual(18, len(set(paths)))

    def test_error_suppression_bytes_match_transform_without_claiming_successful_compilation(self):
        for row in self.rows:
            variant = next(v for v in row['supplemental_inputs'] if v['kind'] == 'suppression')
            expected = ('@file:Suppress("' + row['upstream_id'] + '")\n').encode() + self.source(row['positive'])
            self.assertEqual(expected, self.source(variant['path']))
            self.assertTrue(variant['compilation_expectation'].startswith('unknown_'))
            self.assertTrue(variant['diagnostic_expectation'].startswith('unknown_'))

    def test_boundary_and_applicability_absence_are_proposals_not_observed_diagnostics(self):
        for row in self.rows:
            for variant in row['supplemental_inputs']:
                if variant['kind'] == 'suppression':
                    continue
                self.assertEqual('proposed_exit0_not_observed', variant['compilation_expectation'])
                self.assertEqual('proposed_selected_id_absent_not_observed', variant['diagnostic_expectation'])
                self.assertNotIn(b'@file:Suppress', self.source(variant['path']))

    def test_unsupported_common_jvm_graphs_stay_separate_and_unexecuted(self):
        planned = json.loads((FIXTURES / 'planned-kmp-manifest.json').read_text())
        self.assertEqual(16, len(planned['fixtures']))
        self.assertTrue(all(row['status'] == 'planned_unsupported_sealed_source_roles_not_available'
                            for row in planned['fixtures']))
        self.assertTrue({row['rule_id'] for row in planned['fixtures']}.isdisjoint(
            {row['rule_id'] for row in self.manifest['fixtures']}))

    def test_original_partition_selectors_are_source_only_with_independent_admission_bounds(self):
        proposal = json.loads((FIXTURES / 'original42-partition-proposal.json').read_text())
        self.assertEqual('source_only_partition_selectors_implemented_not_executed', proposal['status'])
        self.assertIs(False, proposal['execution_or_selection_authority'])
        self.assertTrue(all(value is False for value in proposal['evidence_promotion'].values()))
        original = {row['rule_id']: row for row in self.manifest['fixtures'] if 'authored_cohort' not in row}
        self.assertEqual(42, len(original))
        groups = proposal['recommended_bounded_subcohorts']
        self.assertEqual(10, len(groups))
        ids = [member['rule_id'] for group in groups for member in group['members']]
        self.assertEqual(42, len(ids))
        self.assertEqual(42, len(set(ids)))
        self.assertEqual(set(original), set(ids))
        for group in groups:
            self.assertEqual('source_selector_implemented_not_executed', group['status'])
            rows = [original[member['rule_id']] for member in group['members']]
            for member in group['members']:
                row = original[member['rule_id']]
                self.assertEqual(row['upstream_id'], member['upstream_id'])
                self.assertEqual(row['flags'], member['unchanged_flags'])
                self.assertEqual(self.mappings[row['upstream_id']]['backend'], member['mapped_backend'])
                for kind in ('positive', 'negative'):
                    self.assertEqual(row[kind], member[kind]['path'])
                    self.assertEqual(hashlib.sha256(self.source(row[kind])).hexdigest(), member[kind]['sha256'])
            schedule = group['static_schedule']
            phase_counts = {}
            for kind in ('positive', 'negative', 'warning-disabled', 'configuration', 'version', 'suppression', 'regression'):
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
                              self.mappings[row['upstream_id']]['backend'] if split else None))
                phase_counts[kind] = len(keys)
            self.assertEqual(phase_counts, schedule['phase_group_provider_calls'])
            errors = sum(row['declared_severity'] == 'error' for row in rows)
            baseline = sum(phase_counts.values()) + errors + 5
            conditional = 5 * len(rows) + len(rows) - errors
            self.assertEqual(baseline, schedule['no_fallback_provider_admissions'])
            self.assertEqual(baseline - 1, schedule['no_fallback_child_capable_calls'])
            self.assertEqual(conditional, schedule['conditional_single_rerun_upper_bound'])
            self.assertEqual(baseline + conditional, schedule['all_conditional_reruns_provider_admissions_bound'])
            self.assertLessEqual(baseline + conditional, 48)
            self.assertIs(True, schedule['counts_are_source_plan_not_execution'])
        retained = proposal['empty_range_failure_retention']
        self.assertEqual(original[retained['rule_id']]['flags'], retained['unchanged_flags'])
        self.assertEqual(['negative', 'configuration'], retained['historical_failed_assessment_kinds'])

    def test_authored_inventory_is_independent_of_actual_execution_status(self):
        inventory_path = FIXTURES / 'authored-input-inventory.json'
        self.assertEqual(FIXTURES, inventory_path.parent)  # Outside the strict evidence/ namespace.
        inventory = json.loads(inventory_path.read_text())
        self.assertEqual('authored_input_inventory_source_plan', inventory['kind'])
        self.assertIs(True, inventory['source_only'])
        self.assertIs(False, inventory['execution_or_project_policy_authority'])
        self.assertIs(False, inventory['execution_receipts_modified_by_generator'])
        self.assertEqual({'implemented': False, 'tested': False, 'qualified': False, 'attempted': False},
                         inventory['per_id_evidence_promotion_from_authorship'])
        self.assertEqual(443, inventory['catalog_mappings'])
        self.assertEqual((98, 42, 56, 16), (inventory['ordinary_pairs'], inventory['original_ordinary_pairs'],
                                          inventory['expanded_ordinary_pairs'], inventory['planned_unsupported_kmp_graphs']))
        self.assertEqual(18, inventory['supplemental_type_boundary_source_plans'])
        self.assertIs(False, inventory['supplemental_source_plans_executed_by_current_pair_harness'])
        self.assertEqual(hashlib.sha256((FIXTURES / 'manifest.json').read_bytes()).hexdigest(),
                         inventory['ordinary_manifest']['sha256'])
        self.assertEqual(hashlib.sha256((FIXTURES / 'planned-kmp-manifest.json').read_bytes()).hexdigest(),
                         inventory['planned_kmp_manifest']['sha256'])

    def test_generator_serializes_portable_logical_keys_and_explicit_utf8_lf(self):
        # Pure path models and source AST only: no Windows host emulation or
        # compiler execution, and no cross-platform execution certificate.
        def canonical_expressions(source, root_name, expected_count):
            tree = ast.parse(source)
            parents = {child: node for node in ast.walk(tree) for child in ast.iter_child_nodes(node)}
            relative_calls = [node for node in ast.walk(tree)
                              if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                              and node.func.attr == 'relative_to' and len(node.args) == 1
                              and isinstance(node.args[0], ast.Name) and node.args[0].id == root_name]
            self.assertEqual(expected_count, len(relative_calls))
            expressions = []
            for relative in relative_calls:
                method = parents[relative]
                self.assertIsInstance(method, ast.Attribute)
                self.assertEqual('as_posix', method.attr)
                expression = parents[method]
                self.assertIsInstance(expression, ast.Call)
                self.assertEqual([], expression.args)
                self.assertEqual([], expression.keywords)
                expressions.append(expression)
            return tree, expressions

        generator, generated_keys = canonical_expressions(
            (FIXTURES / 'generate_fixtures.py').read_text(encoding='utf-8'), 'FIXTURES', 4)
        _, preserved_keys = canonical_expressions(
            Path(__file__).read_text(encoding='utf-8'), 'temporary_fixtures', 3)
        for fixture_root in (PurePosixPath('/authored-fixtures'), PureWindowsPath('C:/authored-fixtures')):
            folder = fixture_root / 'cases/logical_path_contract'
            namespace = {'FIXTURES': fixture_root, 'temporary_fixtures': fixture_root,
                         'folder': folder, 'destination': folder / 'boundary.kt', 'path': folder / 'positive.kt'}
            def values(expressions):
                return [eval(compile(ast.Expression(body=node), '<logical-source-key>', 'eval'),
                             {'__builtins__': {}}, namespace) for node in expressions]
            self.assertCountEqual(['cases/logical_path_contract/positive.kt', 'cases/logical_path_contract/negative.kt',
                                   'cases/logical_path_contract/boundary.kt', 'cases/logical_path_contract/boundary.kt'],
                                  values(generated_keys))
            self.assertEqual(['cases/logical_path_contract/positive.kt'] * 3, values(preserved_keys))
        writes = [node for node in ast.walk(generator)
                  if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                  and node.func.attr == 'write_text']
        self.assertEqual(6, len(writes))
        for write in writes:
            self.assertEqual({'encoding': 'utf-8', 'newline': '\n'},
                             {keyword.arg: ast.literal_eval(keyword.value) for keyword in write.keywords})

    def test_source_generation_preserves_independently_seeded_execution_receipts(self):
        # These temporary opaque bytes are test inputs, not real execution evidence.
        # True and false flags must both survive: generation may neither promote
        # authored sources nor downgrade independently retained actual evidence.
        seeds = {
            'evidence/compiler-jvm-2.4.21.json': json.dumps({
                'static_test_seed_not_actual_evidence': True,
                'rules': [
                    {'rule_id': self.rows[0]['rule_id'], 'implemented': True, 'tested': True,
                     'qualified': False, 'attempted': True},
                    {'rule_id': self.rows[1]['rule_id'], 'implemented': False, 'tested': False,
                     'qualified': False, 'attempted': False},
                ],
            }).encode(),
            'runs/static-test-seed/process.json': b'{"static_test_seed_not_execution":true,"retained":"process"}\n',
            'runs/static-test-seed/assessments/positive.json': b'{"static_test_seed_not_execution":true,"retained":"assessment"}\n',
            'runs/static-test-seed/latest-checkpoint.json': b'{"static_test_seed_not_execution":true,"retained":"selector"}\n',
            'unit-evidence/static-test-seed/verification.json': b'{"static_test_seed_not_execution":true,"retained":"unit"}\n',
        }
        with tempfile.TemporaryDirectory(prefix='ktc-authorship-static-') as directory:
            temporary_root = Path(directory)
            temporary_fixtures = temporary_root / 'providers/compiler-fixtures'
            temporary_fixtures.mkdir(parents=True)
            (temporary_root / 'data').mkdir()
            for name in ('generate_fixtures.py', 'cohort_expansion.py'):
                shutil.copyfile(FIXTURES / name, temporary_fixtures / name)
            shutil.copyfile(ROOT / 'data/rules-core.json', temporary_root / 'data/rules-core.json')
            for relative, content in seeds.items():
                destination = temporary_fixtures / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(content)
            generated = None
            for _ in range(2):
                command = [sys.executable] + (['-O'] if sys.flags.optimize else []) + [str(temporary_fixtures / 'generate_fixtures.py')]
                completed = subprocess.run(command,
                                           cwd=temporary_root, check=True, capture_output=True, text=True, timeout=30)
                self.assertIn('no execution or qualification claimed', completed.stdout)
                receipt_paths = {path.relative_to(temporary_fixtures).as_posix()
                                 for folder in ('evidence', 'runs', 'unit-evidence')
                                 for path in (temporary_fixtures / folder).rglob('*') if path.is_file()}
                self.assertEqual(set(seeds), receipt_paths)
                for relative, content in seeds.items():
                    self.assertEqual(content, (temporary_fixtures / relative).read_bytes())
                source_bytes = {path.relative_to(temporary_fixtures).as_posix(): path.read_bytes()
                                for path in temporary_fixtures.rglob('*')
                                if path.is_file() and path.suffix in ('.kt', '.json')
                                and path.relative_to(temporary_fixtures).as_posix() not in seeds}
                if generated is not None:
                    self.assertEqual(generated, source_bytes)
                generated = source_bytes
            for relative, content in generated.items():
                self.assertEqual((FIXTURES / relative).read_bytes(), content)


if __name__ == '__main__':
    unittest.main()
