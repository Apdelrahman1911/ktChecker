#!/usr/bin/env python3
"""Inert authored-data/harness checks. Never import providers or execute Detekt/JVMs."""

from __future__ import annotations

from collections import Counter
from copy import deepcopy
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
RELATIVE = "providers/detekt-fixtures/corpus/syntax-expansion-v1"
EXPANSION = ROOT / RELATIVE
BASELINE = ROOT / "providers/detekt-fixtures/corpus/syntax-v1"
KOTLIN = ROOT / "modules/provider-detekt/src/corpusTest/kotlin/dev/ktchecker/providers/detekt"
STANDARD = ("positive", "negative", "configuration", "version", "suppression", "regression")
ASSESSMENTS = (
    "independent_positive", "independent_valid_alternative",
    "explicit_disabled_control_not_scope_reduction",
    "source_replay_for_pinned_engine_tuple_not_new_predicate",
    "real_upstream_suppression_with_unsuppressed_neighbor",
    "negative_child_snapshot_replay_not_new_predicate",
)
EXPECTED_COHORTS = {
    "performance-shapes-v1": (
        "ktc.performance.detekt.performance.for-each-on-range",
        "ktc.performance.detekt.performance.unnecessary-init-on-array",
        "ktc.performance.detekt.performance.unnecessary-part-of-binary-expression",
    ),
    "public-contract-policy-v1": (
        "ktc.maintainability.detekt.comments.outdated-documentation",
        "ktc.maintainability.detekt.complexity.complex-condition",
        "ktc.maintainability.detekt.libraries.forbidden-public-data-class",
    ),
}
OLD_IDENTITIES = {
    "exceptions-core-v1": "1a04a745f3a90b093f07725c33f7aa5e4f68dcb4e08300e7498eabb23046469f",
    "iteration-core-v1": "855ccc2174e1497b74560aa5041923b870dbb289c68728eeffaf58140eb58890",
    "value-core-v1": "c6e6311078fe7d849755351e936326f00db5c508482f9830ac2367268a41c0c6",
    "opt-in-policy-v1": "83266a809d5e1e5ec7591a36882f7435ff7f108ea28a3426b418ce7d0f9ee421",
}


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_pairs)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def locate(source, offset):
    prefix = source[:offset]
    return prefix.count("\n") + 1, len(prefix.rsplit("\n", 1)[-1])


def validate_case(case):
    sources = case["sources"]
    if not sources or not isinstance(case["intent"], str) or not case["intent"]:
        raise ValueError("case requires nonempty sources and authored intent")
    for path, source in sources.items():
        relative = PurePosixPath(path)
        if (relative.is_absolute() or "\\" in path or
                any(part in ("", ".", "..") for part in path.split("/")) or
                re.match(r"^[A-Za-z]:", path) or relative.suffix != ".kt"):
            raise ValueError("unsafe source path")
        if (not isinstance(source, str) or not source or not source.endswith("\n") or
                any(ord(char) < 1 or ord(char) > 127 or char == "\r" for char in source)):
            raise ValueError("source must preserve ASCII UTF-8 and final LF")
    seen = set()
    for row in case["expected"]:
        source = sources[row["path"]]
        span, occurrence = row["span"], row["occurrence"]
        if not span or type(occurrence) is not int or occurrence < 0:
            raise ValueError("invalid independent diagnostic span")
        offset = -len(span)
        for _ in range(occurrence + 1):
            offset = source.find(span, offset + len(span))
            if offset < 0:
                raise ValueError("expected span absent from exact source")
        start, end = locate(source, offset), locate(source, offset + len(span))
        if start != (row["line"], row["column"]) or end != (row["end_line"], row["end_column"]):
            raise ValueError("authored coordinates differ from exact source")
        identity = row["path"], *start, *end
        if identity in seen:
            raise ValueError("duplicate diagnostic range")
        seen.add(identity)


def matrix_case(fixture, index):
    if index >= 6:
        node = fixture["controls"][index - 6]
        return node["id"], f"/controls/{index - 6}", node, node["assessment"]
    case_id = STANDARD[index]
    key = {"configuration": "positive", "version": "positive", "regression": "negative"}.get(case_id, case_id)
    return case_id, "/" + key, fixture[key], ASSESSMENTS[index]


def expected_schedule(rules, fixtures):
    schedule = []
    matrix_index = 0

    def append(kind, rule, case, native, callback, **extra):
        schedule.append(dict(ordinal=len(schedule) + 1, kind=kind, rule_id=rule, case_id=case,
                             native_launch_required=native, runtime_callback_required=callback, **extra))

    for rule_index, rule in enumerate(rules):
        for index in range(6 + len(fixtures[rule]["controls"])):
            case_id = matrix_case(fixtures[rule], index)[0]
            append("detector_analysis", rule, case_id, True, True, matrix_index=matrix_index)
            matrix_index += 1
            if case_id == "positive":
                append("changed_configuration_authorization_denial", rule, "configuration-permit-replay", False, True,
                       required_no_execution_code="execution_not_authorized")
            if case_id == "version":
                if rule_index == 0:
                    append("cli_probe", rule, "cli-version-probe", True, True, expected_version="2.0.0-alpha.6")
                for target in ("common", "native"):
                    append("unsupported_target_abstention", rule, "unsupported-" + target, False, False, request_target=target)
    return schedule


class SyntaxExpansionAuthoringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.paths = sorted((EXPANSION / "fixtures").glob("*.json"))
        cls.fixtures = [read(path) for path in cls.paths]
        cls.by_id = {item["rule_id"]: item for item in cls.fixtures}
        cls.by_slug = {item["rule_id"].rsplit(".", 1)[-1]: item for item in cls.fixtures}
        cls.scope = read(EXPANSION / "scope.json")
        cls.registry = read(EXPANSION / "cohorts.json")
        cls.contract = read(EXPANSION / "integration-contract.json")
        cls.matrices = {name: read(EXPANSION / "cohorts" / (name + ".json")) for name in EXPECTED_COHORTS}
        cls.mapping = read(ROOT / "modules/provider-detekt/src/main/resources/detekt-rule-map.json")["mappings"]

    def test_exact_six_existing_light_mappings_and_activation_are_not_catalog_expansion(self):
        self.assertEqual(6, len(self.paths))
        expected = {rule for rules in EXPECTED_COHORTS.values() for rule in rules}
        self.assertEqual(expected, self.by_id.keys())
        self.assertEqual(3, sum(item["default_activation"] == "advisory" for item in self.fixtures))
        self.assertEqual(3, sum(item["default_activation"] == "opt_in" for item in self.fixtures))
        self.assertEqual(276, len(self.mapping))
        self.assertEqual(self.scope["existing_detekt_mapping"]["map_sha256"],
                         digest((ROOT / self.scope["existing_detekt_mapping"]["map_path"]).read_bytes()))
        included = sorted(row["rule_id"] for row in self.mapping
                          if row["relation"] == "exact" and row["depth"] == "syntax" and row["default_activation"] != "excluded")
        self.assertEqual(156, len(included))
        self.assertEqual(included, self.scope["existing_detekt_mapping"]["included156_rule_ids"])
        self.assertTrue(expected <= set(included))
        mapping = {row["rule_id"]: row for row in self.mapping}
        for item in self.fixtures:
            with self.subTest(rule=item["rule_id"]):
                self.assertEqual(1, item["schema_version"])
                self.assertEqual("detekt-syntax-expansion-v1", item["corpus_id"])
                self.assertEqual("unexecuted", item["authoring_state"])
                self.assertEqual("detekt", item["provider_id"])
                self.assertEqual("2.0.0-alpha.6", item["provider_version"])
                self.assertEqual("2.4.10", item["embedded_kotlin_version"])
                for key in ("upstream_id", "relation", "depth", "default_activation", "catalog_record_sha256"):
                    self.assertEqual(mapping[item["rule_id"]][key], item[key])
                self.assertEqual("exact", item["relation"])
                self.assertEqual("syntax", item["depth"])
                self.assertEqual(set(STANDARD), item["six_case_plan"].keys())
                self.assertEqual("pinned_classfile_ancestry_only_not_execution", item["light_eligibility"]["evidence_kind"])
                self.assertFalse(item["light_eligibility"]["requires_analysis_api"])
                self.assertFalse(item["light_eligibility"]["qualification"])
                self.assertTrue(item["limitations"])
                self.assertFalse({"passed", "tested", "qualified"} & item.keys())

    def test_all_independent_ranges_and_active_negatives_preserve_exact_bytes(self):
        for item in self.fixtures:
            self.assertTrue(item["positive"]["expected"])
            self.assertEqual([], item["negative"]["expected"])
            self.assertEqual(3, len(item["controls"]))
            controls = [control["id"] for control in item["controls"]]
            self.assertEqual(3, len(set(controls)))
            self.assertFalse(set(STANDARD) & set(controls))
            for node in [item["positive"], item["negative"], item["suppression"], *item["controls"]]:
                with self.subTest(rule=item["rule_id"], intent=node["intent"]):
                    validate_case(node)
            citation = item["source_citation"]
            self.assertTrue(citation["research_only"])
            self.assertRegex(citation["source_file_sha256"], r"^[0-9a-f]{64}$")
            self.assertTrue(citation["url"].startswith("https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/"))
            self.assertTrue(citation["predicate_line_numbers"])

    def test_real_file_suppression_keeps_unsuppressed_neighbor_and_does_not_filter_diagnostics(self):
        for item in self.fixtures:
            positive, suppression = item["positive"], item["suppression"]
            self.assertEqual(positive["expected"], suppression["expected"])
            self.assertEqual(2 * len(positive["sources"]), len(suppression["sources"]))
            for path, source in positive["sources"].items():
                self.assertEqual(source, suppression["sources"][path])
                copy = suppression["sources"]["Suppressed" + path]
                name = item["upstream_id"].split(":")[1]
                self.assertTrue(copy.startswith(f'@file:Suppress("{name}")\n'))
                self.assertNotIn('Suppress("ktc.', copy)
                self.assertEqual(source, copy.split("\n\n", 1)[1].replace("package suppressed.", "package ", 1))

    def test_genuine_options_contrast_same_sources_and_do_not_become_disabled_controls(self):
        complex_case = self.by_slug["complex-condition"]
        configured = complex_case["controls"][0]
        self.assertEqual(complex_case["positive"]["sources"], configured["sources"])
        self.assertEqual({"allowedConditions": 4}, configured["options"])
        self.assertEqual([], configured["expected"])
        docs = self.by_slug["outdated-documentation"]["controls"]
        self.assertEqual(docs[0]["sources"], docs[1]["sources"])
        self.assertEqual({"matchDeclarationsOrder": False}, docs[1]["options"])
        self.assertEqual(["render", "invalidTag"], [row["span"] for row in docs[0]["expected"]])
        self.assertEqual(["invalidTag"], [row["span"] for row in docs[1]["expected"]])
        visibility = self.by_slug["forbidden-public-data-class"]["controls"]
        self.assertEqual(visibility[0]["sources"], visibility[1]["sources"])
        self.assertEqual({"ignorePackages": []}, visibility[1]["options"])
        self.assertEqual([], visibility[0]["expected"])
        self.assertEqual(["InternalNamespaceRecord"], [row["span"] for row in visibility[1]["expected"]])
        option_rows = [row for matrix in self.matrices.values() for row in matrix["analyses"] if row["upstream_options"]]
        self.assertEqual(3, len(option_rows))
        for row in option_rows:
            self.assertFalse(row["rule_disabled_for_this_configuration_control"])
            self.assertTrue(row["selected_sarif_descriptor_enabled_required"])

    def test_anticipated_false_positives_misses_and_policy_limits_remain_visible(self):
        controls = {row["id"]: row for item in self.fixtures for row in item["controls"]}
        self.assertEqual(18, len(controls))
        false_positives = {"materialized-list-is-not-a-range-receiver", "shadowed-array-constructor-requires-lambda",
                           "stateful-second-poll-is-not-redundant", "whitespace-inside-string-literals-is-significant",
                           "literal-operator-text-is-not-four-decisions"}
        false_negatives = {"stored-range-origin-not-followed", "computed-default-expression-not-evaluated",
                           "parenthesized-duplicate-is-not-flattened"}
        self.assertEqual(false_positives, {key for key, row in controls.items() if row["assessment"] == "anticipated_upstream_false_positive"})
        self.assertEqual(false_negatives, {key for key, row in controls.items() if row["assessment"] == "anticipated_upstream_false_negative"})
        for key in false_positives:
            self.assertTrue(controls[key]["expected"])
        for key in false_negatives:
            self.assertEqual([], controls[key]["expected"])
        self.assertIn('events.poll() || events.poll()', controls["stateful-second-poll-is-not-redundant"]["sources"]["Case.kt"])
        self.assertIn('value == "a b" || value == "ab"', controls["whitespace-inside-string-literals-is-significant"]["sources"]["Case.kt"])
        self.assertIn('value == "&&||" && ready', controls["literal-operator-text-is-not-four-decisions"]["sources"]["Case.kt"])
        application = controls["application-state-has-no-library-abi-promise"]
        self.assertEqual("opt_in_policy_limit", application["assessment"])
        self.assertEqual(["ScreenState"], [row["span"] for row in application["expected"]])
        self.assertEqual([], controls["multi-statement-default-initializer-retains-audit"]["expected"])

    def test_baseline_fixture_bytes_controls_counts_and_four_identities_are_unchanged(self):
        old = self.scope["preserved_baseline"]
        self.assertEqual(10, old["rule_count"])
        self.assertEqual(91, old["authored_analyses"])
        self.assertEqual(10, len(list(BASELINE.glob("*.json"))))
        self.assertEqual({str(path.relative_to(ROOT)) for path in BASELINE.glob("*.json")}, {row["path"] for row in old["fixture_files"]})
        self.assertEqual(91, sum(row["analyses"] for row in old["fixture_files"]))
        for row in old["fixture_files"]:
            data = (ROOT / row["path"]).read_bytes()
            self.assertEqual(row["sha256"], digest(data))
            self.assertEqual(row["bytes"], len(data))
            item = read(ROOT / row["path"])
            self.assertEqual("detekt-syntax-corpus-v1", item["corpus_id"])
            self.assertEqual(row["rule_id"], item["rule_id"])
            self.assertEqual(row["control_ids"], [control["id"] for control in item["controls"]])
            self.assertEqual(row["analyses"], 6 + len(item["controls"]))
        self.assertEqual(list(OLD_IDENTITIES), [row["cohort_id"] for row in old["cohorts"]])
        for row in old["cohorts"]:
            selected = row["analyses_by_rule"]
            text = f'detekt-corpus-cohort/1\n{row["cohort_id"]}\n'
            text += "".join(f"{rule}={count}\n" for rule, count in selected.items())
            text += f"detector_analysis={sum(selected.values())}\ncli_probe=1\n"
            text += f"unsupported_target_abstention={2 * len(selected)}\nchanged_configuration_authorization_denial={len(selected)}\n"
            text += "full_authoring_scope=10/91/not_run\nlimits=40/1200000/60000\n"
            self.assertEqual(OLD_IDENTITIES[row["cohort_id"]], digest(text.encode()))
            self.assertEqual(OLD_IDENTITIES[row["cohort_id"]], row["plan_identity_sha256"])
            self.assertFalse(row["modified_by_this_packet"])

    def test_full_scope_and_unexecuted_denominators_do_not_transfer_success(self):
        current = self.scope["current_scope"]
        self.assertEqual((1663, 8, 0), (current["ids"], current["contexts"], current["new_contexts"]))
        self.assertEqual([], current["scope_exclusions"])
        self.assertEqual([], current["changed_catalog_or_activation_files"])
        self.assertEqual(0, current["historical_stage_transfer"])
        combined = self.scope["combined_proposed_authoring_scope"]
        self.assertEqual((16, 145, 4, 2), tuple(combined[key] for key in
                         ("rule_count", "authored_analyses", "baseline_cohort_count", "additional_cohort_count")))
        self.assertFalse(combined["aggregate_execution_or_sidecar_created"])
        self.assertFalse(combined["scope_reduction"])
        self.assertEqual(0, combined["baseline_execution_stage_transfer"])
        added = self.scope["additional_proposed_scope"]
        self.assertEqual((6, 54, 18), (added["rule_count"], added["authored_analyses"], added["independent_controls"]))
        self.assertEqual("not_run", added["state"])
        for key in ("attempted", "implemented", "tested", "qualified"):
            self.assertFalse(added[key])
        for key in ("actual_new_detector_analyses", "natural_project_observations", "precision_reviewed_natural_observations", "historical_stage_transfer"):
            self.assertEqual(0, self.contract["denominators"][key])

    def test_registry_identities_bind_fixture_matrix_scope_and_contract_bytes(self):
        registry = self.registry
        self.assertEqual(list(EXPECTED_COHORTS), registry["allowed_cohort_names"])
        self.assertIsNone(registry["default_cohort"])
        self.assertFalse(registry["multi_cohort_or_all_selector_allowed"])
        self.assertEqual("CONNECTED_SOURCE_CANDIDATE_UNCOMPILED_UNEXECUTED_NOT_ADMITTED", registry["state"])
        for key in ("actual_tests_executed", "actual_provider_invocations", "historical_stage_transfer"):
            self.assertEqual(0, registry[key])
        self.assertEqual(digest((EXPANSION / "scope.json").read_bytes()), registry["scope_sha256"])
        self.assertEqual(digest((EXPANSION / "integration-contract.json").read_bytes()), registry["integration_contract_sha256"])
        self.assertEqual(list(EXPECTED_COHORTS), [plan["cohort_id"] for plan in registry["cohorts"]])
        for plan in registry["cohorts"]:
            self.assertFalse(plan["execution_admitted"])
            self.assertEqual("connected_source_candidate_uncompiled_unexecuted", plan["runtime_registration_state"])
            self.assertEqual(plan["cohort_id"] == "public-contract-policy-v1", plan["requires_explicit_owner_opt_in"])
            self.assertEqual(list(EXPECTED_COHORTS[plan["cohort_id"]]), [row["rule_id"] for row in plan["selected_rules"]])
            self.assertEqual(digest((ROOT / plan["case_matrix_path"]).read_bytes()), plan["case_matrix_sha256"])
            identity = plan["plan_identity"]
            self.assertEqual(digest(canonical(identity)), plan["plan_identity_sha256"])
            self.assertEqual(plan["selected_rules"], identity["selected_rules"])
            self.assertEqual(plan["case_matrix_sha256"], identity["case_matrix_sha256"])
            self.assertEqual(registry["scope_sha256"], identity["scope_sha256"])
            self.assertEqual(registry["integration_contract_sha256"], identity["integration_contract_sha256"])
            self.assertEqual({"max_invocations": 40, "aggregate_millis": 1200000, "child_millis": 60000}, identity["limits"])
            self.assertFalse(identity["previous_stages_accepted"])
            self.assertFalse(identity["aggregate_context_activation"])
            self.assertEqual(f'evidence/verified-jvm-syntax-expansion-{plan["cohort_id"]}-native-preview1.json', plan["proposed_sidecar"])
            for selected in plan["selected_rules"]:
                self.assertEqual(9, selected["analyses"])
                self.assertEqual(digest((ROOT / selected["fixture_path"]).read_bytes()), selected["fixture_sha256"])
                self.assertEqual(self.by_id[selected["rule_id"]]["upstream_id"], selected["upstream_id"])

    def test_each_matrix_resolves_every_case_hash_option_and_enabled_descriptor(self):
        for name, rules in EXPECTED_COHORTS.items():
            matrix = self.matrices[name]
            self.assertEqual("AUTHORED_NOT_RUN", matrix["state"])
            self.assertTrue(matrix["candidate_only"])
            self.assertEqual(27, len(matrix["analyses"]))
            for rule_index, rule in enumerate(rules):
                fixture = self.by_id[rule]
                for case_index in range(9):
                    row = matrix["analyses"][9 * rule_index + case_index]
                    case_id, pointer, node, assessment = matrix_case(fixture, case_index)
                    disabled = case_id == "configuration"
                    expected = [] if disabled else node["expected"]
                    with self.subTest(cohort=name, rule=rule, case=case_id):
                        self.assertEqual(rule, row["rule_id"])
                        self.assertEqual(case_id, row["case_id"])
                        self.assertEqual(pointer, row["fixture_json_pointer"])
                        self.assertEqual(case_id if case_index < 6 else node["case_kind"], row["case_kind"])
                        self.assertEqual(assessment, row["assessment"])
                        self.assertEqual(node["intent"], row["intent"])
                        self.assertEqual(expected, row["authored_expected_ranges"])
                        self.assertEqual(len(expected), row["authored_expected_count"])
                        self.assertEqual([] if disabled else None, row["expected_override"])
                        self.assertEqual(node.get("options", {}), row["upstream_options"])
                        self.assertEqual(disabled, row["rule_disabled_for_this_configuration_control"])
                        self.assertEqual(not disabled, row["require_active_upstream_descriptor"])
                        self.assertEqual(not disabled, row["selected_sarif_descriptor_enabled_required"])
                        self.assertEqual("detekt." + fixture["upstream_id"].replace(":", "."), row["selected_sarif_rule_id"])
                        self.assertTrue(row["require_actual_native_launch"])
                        self.assertEqual("not_run", row["state"])
                        self.assertFalse(row["attempted"])
                        self.assertIsNone(row["observed_findings"])
                        self.assertFalse(row["natural_project_observation"])
                        self.assertEqual(digest(canonical(node["sources"])), row["source_set_sha256"])
                        self.assertEqual([dict(path=path, sha256=digest(source.encode()), bytes=len(source.encode()))
                                          for path, source in sorted(node["sources"].items())], row["source_files"])

    def test_exact_call_order_counts_and_noninflated_source_denominators(self):
        totals = Counter()
        for plan in self.registry["cohorts"]:
            name = plan["cohort_id"]
            matrix = self.matrices[name]
            analyses, schedule, counts = matrix["analyses"], matrix["invocation_schedule"], matrix["counts"]
            self.assertEqual(expected_schedule(EXPECTED_COHORTS[name], self.by_id), schedule)
            self.assertEqual(counts, plan["counts"])
            self.assertEqual((27, 37, 28, 31, 113, 9), tuple(counts[key] for key in
                             ("authored_analyses", "all_calls", "native_launch_ceiling", "expected_runtime_callbacks", "planned_progress_events", "independent_controls")))
            self.assertEqual({"detector_analysis": 27, "cli_probe": 1, "unsupported_target_abstention": 6,
                              "changed_configuration_authorization_denial": 3}, dict(Counter(row["kind"] for row in schedule)))
            self.assertEqual(28, sum(row["native_launch_required"] for row in schedule))
            self.assertEqual(31, sum(row["runtime_callback_required"] for row in schedule))
            self.assertLessEqual(counts["all_calls"], 40)
            self.assertEqual(len({(row["rule_id"], row["source_set_sha256"]) for row in analyses}), counts["unique_rule_source_sets"])
            self.assertEqual(len({(row["rule_id"], row["source_set_sha256"], row["rule_disabled_for_this_configuration_control"], canonical(row["upstream_options"]))
                                  for row in analyses}), counts["unique_rule_source_configuration_sets"])
            controls = [row for row in analyses if row["case_id"] not in STANDARD]
            self.assertEqual(dict(Counter(row["assessment"] for row in controls)), counts["independent_control_assessments"])
            self.assertEqual(sum(row["authored_expected_count"] for row in analyses), counts["authored_expected_finding_observations"])
            self.assertEqual(14, counts["authored_alert_bearing_analyses"])
            self.assertEqual(13, counts["authored_zero_finding_analyses"])
            self.assertEqual(0, counts["actual_executions"])
            self.assertIsNone(counts["actual_findings"])
            self.assertEqual(0, counts["natural_project_observations"])
            self.assertEqual(0, counts["precision_reviewed_natural_observations"])
            totals.update({key: value for key, value in counts.items() if type(value) is int})
        self.assertEqual((54, 18, 33, 42, 41), tuple(totals[key] for key in
                         ("authored_analyses", "independent_controls", "unique_rule_source_sets", "unique_rule_source_configuration_sets", "authored_expected_finding_observations")))

    def test_compiled_pins_bind_closed_namespace_and_exact_canonical_plan_strings(self):
        source = (KOTLIN / "DetektCorpusExpansionPins.kt").read_text()
        files_body = source.split("val files:", 1)[1].split("val planIdentities:", 1)[0]
        pins = dict(re.findall(r'"([^"]+)" to "([0-9a-f]{64})"', files_body))
        expected = {str(path.relative_to(ROOT)) for path in EXPANSION.rglob("*") if path.is_file()}
        expected.update(str(path.relative_to(ROOT)) for path in BASELINE.glob("*.json"))
        self.assertEqual(22, len(pins))
        self.assertEqual(expected, pins.keys())
        for path, pin in pins.items():
            self.assertEqual(pin, digest((ROOT / path).read_bytes()), path)
        identity_body = source.split("val planIdentities:", 1)[1].split("val controlIds:", 1)[0]
        identities = {name: json.loads(encoded) for name, encoded in
                      re.findall(r'"([a-z0-9-]+)" to ("(?:\\.|[^"\\])*")', identity_body)}
        self.assertEqual({plan["cohort_id"]: canonical(plan["plan_identity"]).decode() for plan in self.registry["cohorts"]}, identities)
        controls_body = source.split("val controlIds:", 1)[1]
        controls = {rule: re.findall(r'"([a-z0-9-]+)"', values) for rule, values in
                    re.findall(r'"(ktc\.[^"]+)" to Collections.unmodifiableList\(listOf\((.*?)\)\)', controls_body)}
        self.assertEqual({rule: [row["id"] for row in item["controls"]] for rule, item in self.by_id.items()}, controls)
        self.assertIn("Collections.unmodifiableMap", source)
        self.assertIn("Collections.unmodifiableList", source)

    def test_loader_registry_and_gradle_connect_six_plans_without_an_aggregate_path(self):
        controls = (KOTLIN / "DetektCorpusControls.kt").read_text()
        inventory = (KOTLIN / "DetektCorpusInventory.kt").read_text()
        driver = (KOTLIN / "DetektSyntaxCorpusIntegrationTest.kt").read_text()
        build = (ROOT / "modules/provider-detekt/build.gradle.kts").read_text()
        old = re.findall(r'val [A-Z_]+ = DetektCorpusCohort\("([^"]+)", linkedMapOf\((.*?)\)\)', controls, re.DOTALL)
        self.assertEqual(list(OLD_IDENTITIES), [name for name, _ in old])
        added = re.findall(r'val [A-Z_]+ = expansion\("([^"]+)", linkedMapOf\((.*?)\)\)', controls, re.DOTALL)
        self.assertEqual(list(EXPECTED_COHORTS), [name for name, _ in added])
        for name, body in added:
            self.assertEqual([(rule, "9") for rule in EXPECTED_COHORTS[name]], re.findall(r'"(ktc\.[^"]+)" to ([0-9]+)', body))
        gate = re.search(r'val detektCorpusCohortNames = setOf\((.*?)\)', build, re.DOTALL)
        self.assertIsNotNone(gate)
        selected_names = re.findall(r'"([^"]+)"', gate.group(1))
        self.assertEqual(list(OLD_IDENTITIES) + list(EXPECTED_COHORTS) + ["protocol-and-catch-boundaries-v1"], selected_names[:7])
        self.assertEqual(list(OLD_IDENTITIES) + list(EXPECTED_COHORTS) + ["protocol-and-catch-boundaries-v1", "exception-observability-policy-v1"], selected_names[:8])
        for anchor in ("BASELINE_AUTHORED_RULES = 10", "BASELINE_AUTHORED_ANALYSES = 91", "PRE_PROTOCOL_AUTHORED_RULES = 16", "PRE_PROTOCOL_AUTHORED_ANALYSES = 145",
                       "PRE_OBSERVABILITY_AUTHORED_RULES = 19", "PRE_OBSERVABILITY_AUTHORED_ANALYSES = 172",
                       "PRE_LOCAL_SHAPES_AUTHORED_RULES = 22", "PRE_LOCAL_SHAPES_AUTHORED_ANALYSES = 199",
                       "all.singleOrNull { it.name == name }", "schedule.getOrNull(reserved) == CorpusInvocationSpec(kind, ruleId, caseId)"):
            self.assertIn(anchor, controls)
        self.assertIn("DetektCorpusInventory.load(repository)", driver)
        self.assertIn("inventory.fixtures.map", driver)
        self.assertIn("inventory.verify()", driver)
        self.assertIn("DetektCorpusInventory.EXPANSION", driver)
        self.assertIn('"providers/detekt-fixtures/test_syntax_expansion.py"', driver)
        self.assertIn("full_corpus_state", driver)
        self.assertIn('put("provider_status", "not_run")', driver)
        self.assertIn('put("attempted", false)', driver)
        self.assertIn("hash(bytes) == filePins.getValue(relative)", inventory)
        self.assertIn("DetektCorpusExpansionPins.files + DetektCorpusProtocolPins.files", inventory)
        self.assertIn("DetektCorpusExpansionPins.files.size == 22", inventory)
        self.assertIn("bytes.contentEquals(readPinned(repository, path))", inventory)
        self.assertIn("Files.walk(repository.resolve(root), 3)", inventory)
        self.assertIn("it.limit(65)", inventory)
        self.assertIn("!Files.isSymbolicLink", inventory)
        self.assertIn(".orNull in detektCorpusCohortNames", build)
        self.assertIn("detektCorpusAuthoredInputs", build)
        self.assertIn('outputs.doNotCacheIf("Explicit native corpus runs require fresh actual process outcomes")', build)
        self.assertNotIn('tasks.named("check")', build)
        self.assertNotIn('tasks.named("test").dependsOn', build)

    def test_real_native_driver_requires_exact_descriptor_findings_coverage_and_no_execution_tuples(self):
        driver = (KOTLIN / "DetektSyntaxCorpusIntegrationTest.kt").read_text()
        controls = (KOTLIN / "DetektCorpusControls.kt").read_text()
        for anchor in ("DetektReportParser().parse(actual.report(\"detekt.sarif\"), input)",
                       "assertEquals(!case.disabled, selectedSarifId in parsedReport.enabledSarifIds",
                       "exact_upstream_executed_but_compatibility_and_release_qualification_missing",
                       "rule_disabled_by_owner_selected_configuration", "assertEquals(input.context.scope, coverage.context)",
                       'assertEquals(if (case.disabled) "not_executed" else "executed", coverage.provenance)', "assertEquals(desired, actual",
                       "val replayCoverage = replay.coverage.single()", "assertEquals(fixture.ruleId, replayCoverage.ruleId)",
                       "assertEquals(input.context.scope, replayCoverage.context)", "assertNull(replayCoverage.certificateId)",
                       "assertEquals(request.context.scope, unsupportedCoverage.context)", "assertNull(unsupportedCoverage.certificateId)",
                       'assertEquals(listOf("detekt_jvm_provider_does_not_qualify_common_or_native_targets"), abstained.reasons)'):
            self.assertIn(anchor, driver)
        for anchor in ('result.status == "partial"', 'result.reasons == listOf("detekt_jvm_provider_does_not_qualify_common_or_native_targets")',
                       "result.coverage.size == 1", "it.certificateId == null", "actual.reasons.isEmpty()", "signal != expected"):
            self.assertIn(anchor, controls)
        self.assertEqual(3, len(re.findall(r"\.analyze\([^\n]+limits\.cancellation\)", driver)))
        self.assertEqual(1, len(re.findall(r"\.run\(probeInput, authority, limits\.cancellation\)", driver)))
        for kind in ("ANALYSIS", "CLI_PROBE", "AUTHORIZATION_DENIAL", "ABSTENTION"):
            self.assertIn("limits.reserve(CorpusInvocationKind." + kind, driver)
        for prohibited in ("CancellationToken.NONE", "ProcessBuilder", "ProcessHandle", "@TempDir", "Files.deleteIfExists", ".destroy("):
            self.assertNotIn(prohibited, driver)
        self.assertNotIn("verifiedModelLight", driver)
        self.assertEqual("LEGACY_EXPLICIT_INPUT", self.contract["input_contract"]["provider_input_mode"])
        self.assertFalse(self.contract["input_contract"]["genuine_imported_model_execution"])

    def test_bounds_retained_evidence_and_two_namespaces_do_not_touch_legacy_pointers(self):
        controls = (KOTLIN / "DetektCorpusControls.kt").read_text()
        evidence = (KOTLIN / "DetektCorpusEvidence.kt").read_text()
        driver = (KOTLIN / "DetektSyntaxCorpusIntegrationTest.kt").read_text()
        for anchor in ("MAX_INVOCATIONS = 40", "MAX_AGGREGATE_MILLIS = 1_200_000L", "MAX_CHILD_MILLIS = 60_000L",
                       "confirmedExecutions == cohort.nativeLaunchCeiling", "observedRuntimeCallbacks == cohort.expectedRuntimeCallbacks",
                       "minOf(settings.childMillis, remainingMillis())", "Duplicate invocation is a forbidden retry"):
            self.assertIn(anchor, controls)
        self.assertIn('expansion -> "syntax-expansion"; else -> "syntax-corpus"', controls)
        self.assertIn('"evidence/verified-jvm-${cohort.evidenceStem}-${cohort.name}-native-preview1.json"', evidence)
        for anchor in ("SecureDirectoryStream", "CREATE_NEW, WRITE, NOFOLLOW_LINKS", "requireSingleLink(path)", "path == root.resolve(liveRelative)"):
            self.assertIn(anchor, evidence)
        self.assertNotIn("TRUNCATE_EXISTING", evidence)
        self.assertNotIn("CURRENT", evidence)
        self.assertIn("temporary = owned.createRetainedScratch()", driver)
        self.assertIn('artifact(run, "candidate-sidecar.json"', driver)
        self.assertIn("owned.publish(assertNotNull(pendingSidecar), limits::requireWithinDeadline)", driver)
        self.assertEqual({"max_invocations": 40, "aggregate_millis": 1200000, "child_millis": 60000, "progress_events": 124},
                         self.contract["budgets"]["unchanged_hard_maxima"])
        for name in ("DetektCorpusInventoryTest.kt", "DetektCorpusControlsTest.kt", "DetektCorpusEvidenceTest.kt"):
            source = (KOTLIN / name).read_text()
            self.assertIn("@Test", source)
            self.assertNotIn(".analyze(", source)
            self.assertNotIn("ProcessBuilder", source)

    def test_validator_rejects_changed_ranges_duplicate_keys_and_unsafe_sources(self):
        original = self.by_slug["complex-condition"]["positive"]
        wrong = deepcopy(original)
        wrong["expected"][0]["column"] += 1
        with self.assertRaisesRegex(ValueError, "coordinates"):
            validate_case(wrong)
        duplicate = deepcopy(original)
        duplicate["expected"].append(deepcopy(duplicate["expected"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate diagnostic"):
            validate_case(duplicate)
        unsafe = deepcopy(original)
        unsafe["sources"] = {"../escape.kt": "fun sample() = Unit\n"}
        with self.assertRaisesRegex(ValueError, "unsafe"):
            validate_case(unsafe)
        with self.assertRaisesRegex(ValueError, "duplicate JSON"):
            json.loads('{"state":"not_run","state":"passed"}', object_pairs_hook=unique_pairs)


if __name__ == "__main__":
    unittest.main()
