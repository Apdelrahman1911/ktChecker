#!/usr/bin/env python3
"""Inert observability source/data checks; never Kotlin/provider/native/model execution."""

from collections import Counter
from copy import deepcopy
import json
from pathlib import Path
import re
import unittest

from test_syntax_expansion import (
    EXPECTED_COHORTS as PRESERVED_EXPANSIONS, OLD_IDENTITIES, STANDARD,
    canonical, digest, expected_schedule, matrix_case, read, unique_pairs, validate_case,
)
from test_syntax_protocol import compiled_pins, GAPS, PREVIOUS_IDENTITIES as PRE_PROTOCOL_IDENTITIES


ROOT = Path(__file__).resolve().parents[2]
RELATIVE = "providers/detekt-fixtures/corpus/syntax-exception-observability-v1"
OBSERVABILITY = ROOT / RELATIVE
KOTLIN = ROOT / "modules/provider-detekt/src/corpusTest/kotlin/dev/ktchecker/providers/detekt"
NAME = "exception-observability-policy-v1"
RULES = (
    "ktc.kotlin.detekt.exceptions.rethrow-caught-exception",
    "ktc.kotlin.detekt.exceptions.print-stack-trace",
    "ktc.kotlin.detekt.exceptions.throwing-exceptions-without-message-or-cause",
)
IDENTITY = "54c13bc47181b6922365fc37b718b9d6d87a057add32b1a9170972484b95155b"
PREVIOUS_IDENTITIES = dict(PRE_PROTOCOL_IDENTITIES, **{
    "protocol-and-catch-boundaries-v1": "c832b3938eb553dad22bf483db5780dde8571b86b83679f20064c95fdf2356ad",
})
FIXTURE_PINS = {
    RULES[0]: "acbec925685ca96468e85efb333bcde2084714b219c400a857c2683cc9318209",
    RULES[1]: "19f00be9222d0d9f2dc7d383732f50c49f045825b86d80c9126f04f0cfe4b5e8",
    RULES[2]: "fd40be8293bb11848f72a814c77ba65dbd4010b031375cf81f0fbc689e9c3081",
}
PREDICATE_PINS = {
    RULES[0]: "1c9d3fad5e751ee828dedd15c2883ed525f4a685ba39a6e4d160b59476ffdbf2",
    RULES[1]: "091de705446bc09bfefdfb770ce9f6894d6951921d643fa763dbcdfe14bc6d5a",
    RULES[2]: "a3d03787c817ecda9047093f7f92f5613af1db230e4ab46da1e3f8a54295f4d0",
}
WRAPPER_PINS = {
    RULES[0]: "sha256:ebab09167005829edf9c685603d38c488264ca69b3d4627f7f55c1a30e495ce5",
    RULES[1]: "sha256:90186a3d3d3a5aeb3cf64bba0d399026de69eca075f0e16768d8a36f44358505",
    RULES[2]: "sha256:4b14cad465e94d62f471c1597f65205c81ee437ce55648247273ed9f447dbcaf",
}


def validate_identity(plan):
    """Independent inert hash/opt-in check, not the Kotlin loader or execution authority."""
    identity = plan["plan_identity"]
    if (identity.get("requires_explicit_owner_opt_in") is not True or identity.get("opt_in_rule_ids") != list(RULES) or
            identity.get("cohort_id") != NAME or plan.get("requires_explicit_owner_opt_in") is not True or
            plan.get("opt_in_rule_ids") != list(RULES)):
        raise ValueError("explicit all-three opt-in identity required")
    if plan["plan_identity_sha256"] != IDENTITY or digest(canonical(identity)) != IDENTITY:
        raise ValueError("pinned plan identity mismatch")


class SyntaxObservabilityAuthoringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.paths = sorted((OBSERVABILITY / "fixtures").glob("*.json"))
        cls.fixtures = [read(path) for path in cls.paths]
        cls.by_id = {item["rule_id"]: item for item in cls.fixtures}
        cls.scope = read(OBSERVABILITY / "scope.json")
        cls.registry = read(OBSERVABILITY / "cohorts.json")
        cls.contract = read(OBSERVABILITY / "integration-contract.json")
        cls.matrix = read(OBSERVABILITY / "cohorts" / (NAME + ".json"))
        cls.mapping = {row["rule_id"]: row for row in read(ROOT / "modules/provider-detekt/src/main/resources/detekt-rule-map.json")["mappings"]}

    def test_exact_three_existing_syntax_rules_keep_info_intent_dependent_and_all_opt_in(self):
        self.assertEqual(3, len(self.paths))
        self.assertEqual(set(RULES), self.by_id.keys())
        self.assertEqual(276, len(self.mapping))
        included = sorted(rule for rule, row in self.mapping.items()
                          if row["relation"] == "exact" and row["depth"] == "syntax" and row["default_activation"] != "excluded")
        self.assertEqual(156, len(included))
        self.assertEqual(included, self.scope["existing_detekt_mapping"]["included156_rule_ids"])
        self.assertTrue(set(RULES) <= set(included))
        self.assertEqual(["opt_in"] * 3, [self.by_id[rule]["default_activation"] for rule in RULES])
        self.assertEqual(["style", "policy", "style"], [self.mapping[rule]["kind"] for rule in RULES])
        for item in self.fixtures:
            self.assertEqual("detekt-syntax-exception-observability-v1", item["corpus_id"])
            self.assertEqual("unexecuted", item["authoring_state"])
            self.assertEqual(("detekt", "2.0.0-alpha.6", "2.4.10"), tuple(item[key] for key in ("provider_id", "provider_version", "embedded_kotlin_version")))
            self.assertEqual(("info", "intent_dependent"), tuple(item[key] for key in ("severity", "certainty")))
            for key in ("upstream_id", "relation", "depth", "default_activation", "severity", "kind", "certainty", "catalog_record_sha256"):
                self.assertEqual(self.mapping[item["rule_id"]][key], item[key])
            self.assertFalse({"passed", "tested", "qualified"} & item.keys())

    def test_primary_predicate_and_light_pins_are_research_only_and_do_not_fabricate_options(self):
        for path in self.paths:
            item = read(path)
            self.assertEqual(FIXTURE_PINS[item["rule_id"]], digest(path.read_bytes()))
            self.assertEqual(PREDICATE_PINS[item["rule_id"]], item["source_citation"]["source_file_sha256"])
            self.assertTrue(item["source_citation"]["research_only"])
            self.assertTrue(item["source_citation"]["url"].startswith("https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/"))
            eligibility = item["light_eligibility"]
            self.assertFalse(eligibility["requires_analysis_api"])
            self.assertFalse(eligibility["qualification"])
            self.assertEqual("pinned_classfile_ancestry_only_not_execution", eligibility["evidence_kind"])
            self.assertEqual("detekt-cli-2.0.0-alpha.6-all.jar", eligibility["wrapper"]["artifact"])
            self.assertEqual(WRAPPER_PINS[item["rule_id"]], eligibility["wrapper"]["entry_sha256"])
            self.assertEqual("dev/detekt/rules/exceptions/" + item["upstream_id"].split(":")[1],
                             eligibility["wrapper"]["class"])
        self.assertEqual([], self.by_id[RULES[0]]["upstream_rule_options"])
        self.assertEqual([], self.by_id[RULES[1]]["upstream_rule_options"])
        self.assertEqual(1, len(self.by_id[RULES[2]]["upstream_rule_options"]))

    def test_preserved_nineteen_fixture_bytes_thirty_data_pins_and_seven_identities_are_exact(self):
        previous = self.scope["preserved_baseline"]
        self.assertEqual((19, 172), (previous["rule_count"], previous["authored_analyses"]))
        self.assertEqual(19, len(previous["fixture_files"]))
        self.assertEqual(172, sum(row["analyses"] for row in previous["fixture_files"]))
        self.assertEqual(12, len(previous["preserved_expansion_namespace_files"]))
        self.assertEqual(8, len(previous["preserved_protocol_namespace_files"]))
        for row in previous["fixture_files"] + previous["preserved_expansion_namespace_files"] + previous["preserved_protocol_namespace_files"]:
            data = (ROOT / row["path"]).read_bytes()
            self.assertEqual(row["sha256"], digest(data))
            self.assertEqual(row["bytes"], len(data))
        self.assertEqual(PREVIOUS_IDENTITIES, {row["cohort_id"]: row["plan_identity_sha256"] for row in previous["cohorts"]})
        self.assertEqual([38, 38, 24, 25, 37, 37, 37], [row["planned_invocations"] for row in previous["cohorts"]])
        self.assertTrue(all(not row["modified_by_this_packet"] for row in previous["cohorts"]))
        old_rules = {row["rule_id"] for row in previous["fixture_files"]}
        self.assertFalse(old_rules & set(RULES))
        self.assertEqual(22, len(old_rules | set(RULES)))
        self.assertEqual(0, previous["execution_stage_transfer"])
        self.assertEqual("88780bbcfbbbfb429ce8021eb3aa732c1f8a53771b9f3960bc6937866649b107",
                         digest((KOTLIN / "DetektCorpusExpansionPins.kt").read_bytes()))
        self.assertEqual("fafcee2359211d1220398f2d2c1b1063ff5df4517068ea27350799973bbfb773",
                         digest((KOTLIN / "DetektCorpusProtocolPins.kt").read_bytes()))

    def test_eight_new_compiled_pins_bind_all_three_opt_in_ids_in_canonical_identity(self):
        pins, plans, controls = compiled_pins("DetektCorpusObservabilityPins.kt")
        actual = {str(path.relative_to(ROOT)): digest(path.read_bytes()) for path in OBSERVABILITY.rglob("*") if path.is_file()}
        self.assertEqual(8, len(pins))
        self.assertEqual(actual, pins)
        self.assertEqual({rule: [row["id"] for row in self.by_id[rule]["controls"]] for rule in RULES}, controls)
        self.assertEqual([NAME], self.registry["allowed_cohort_names"])
        self.assertEqual(1, len(self.registry["cohorts"]))
        plan = self.registry["cohorts"][0]
        validate_identity(plan)
        self.assertEqual({NAME: canonical(plan["plan_identity"]).decode()}, plans)
        self.assertEqual(digest((OBSERVABILITY / "scope.json").read_bytes()), self.registry["scope_sha256"])
        self.assertEqual(digest((OBSERVABILITY / "integration-contract.json").read_bytes()), self.registry["integration_contract_sha256"])
        self.assertEqual(digest((ROOT / plan["case_matrix_path"]).read_bytes()), plan["case_matrix_sha256"])
        self.assertEqual(list(RULES), [row["rule_id"] for row in plan["selected_rules"]])
        for row in plan["selected_rules"]:
            self.assertEqual(FIXTURE_PINS[row["rule_id"]], row["fixture_sha256"])
            self.assertEqual(9, row["analyses"])
            self.assertEqual("opt_in", row["default_activation"])
        self.assertEqual(list(RULES), self.matrix["explicit_opt_in_policy_rule_ids"])
        self.assertEqual(list(RULES), self.contract["selection"]["opt_in_rule_ids"])
        self.assertTrue(self.contract["selection"]["all_rules_opt_in"])
        self.assertFalse(self.contract["selection"]["mixed_advisory_and_opt_in"])

    def test_fourteen_literal_ranges_and_twenty_one_source_entries_keep_real_suppression(self):
        range_count = source_entries = 0
        for item in self.fixtures:
            self.assertEqual(set(STANDARD), item["six_case_plan"].keys())
            self.assertTrue(item["positive"]["expected"])
            self.assertEqual([], item["negative"]["expected"])
            self.assertEqual(3, len(item["controls"]))
            self.assertEqual(3, len({row["id"] for row in item["controls"]}))
            self.assertFalse(set(STANDARD) & {row["id"] for row in item["controls"]})
            for node in [item["positive"], item["negative"], item["suppression"], *item["controls"]]:
                validate_case(node)
                range_count += len(node["expected"])
                source_entries += len(node["sources"])
            positive, suppression = item["positive"], item["suppression"]
            self.assertEqual(positive["expected"], suppression["expected"])
            self.assertEqual(positive["sources"]["Case.kt"], suppression["sources"]["Case.kt"])
            copy = suppression["sources"]["SuppressedCase.kt"]
            self.assertTrue(copy.startswith('@file:Suppress("' + item["upstream_id"].split(":")[1] + '")\n\n'))
            self.assertEqual(positive["sources"]["Case.kt"], copy.split("\n\n", 1)[1].replace("package suppressed.", "package ", 1))
        self.assertEqual((14, 21), (range_count, source_entries))  # Literal sources, never detector observations.

    def test_rethrow_retains_exact_text_miss_and_ordered_suffix_not_any_work_exemption(self):
        item = self.by_id[RULES[0]]
        positive = item["positive"]
        self.assertEqual(["throw failure"], [row["span"] for row in positive["expected"]])
        self.assertEqual((6, 4, 6, 17), tuple(positive["expected"][0][key] for key in ("line", "column", "end_line", "end_column")))
        self.assertIn("record(failure)\n    throw failure", item["negative"]["sources"]["Case.kt"])
        parenthesized, following, earlier = item["controls"]
        self.assertEqual(["anticipated_upstream_false_negative", "predicate_boundary", "opt_in_policy_limit"],
                         [row["assessment"] for row in item["controls"]])
        self.assertEqual(positive["sources"]["Case.kt"].replace("throw failure", "throw (failure)"), parenthesized["sources"]["Case.kt"])
        self.assertEqual([], parenthesized["expected"])
        for node in (following, earlier):
            self.assertIn("catch (failure: java.io.IOException)", node["sources"]["Case.kt"])
            self.assertIn("catch (failure: IllegalStateException)", node["sources"]["Case.kt"])
        self.assertLess(following["sources"]["Case.kt"].index("throw failure"), following["sources"]["Case.kt"].index("recover(failure)"))
        self.assertEqual([], following["expected"])
        self.assertLess(earlier["sources"]["Case.kt"].index("recover(failure)"), earlier["sources"]["Case.kt"].index("throw failure"))
        self.assertEqual(["throw failure"], [row["span"] for row in earlier["expected"]])
        self.assertEqual((8, 4), tuple(earlier["expected"][0][key] for key in ("line", "column")))
        self.assertIn("takeLastWhile", item["bounded_predicate"])
        self.assertTrue(any("documentation" in text and "ordered predicate" in text for text in item["limitations"]))

    def test_print_stack_trace_keeps_two_entity_shapes_and_real_type_alias_qualification_gaps(self):
        item = self.by_id[RULES[1]]
        positive = item["positive"]
        self.assertEqual(["dumpStack()", "failure"], [row["span"] for row in positive["expected"]])
        self.assertEqual([(4, 11, 22, 0), (11, 8, 15, 1)],
                         [tuple(row[key] for key in ("line", "column", "end_column", "occurrence")) for row in positive["expected"]])
        custom, alias, qualified = item["controls"]
        self.assertEqual(["anticipated_upstream_false_positive", "anticipated_upstream_false_negative", "anticipated_upstream_false_negative"],
                         [row["assessment"] for row in item["controls"]])
        self.assertIn("private object Thread", custom["sources"]["Case.kt"])
        self.assertIn('fun dumpStack(): List<String> = listOf("queued-work")', custom["sources"]["Case.kt"])
        self.assertEqual(("dumpStack()", 7, 45, 56, 1), tuple(custom["expected"][0][key] for key in ("span", "line", "column", "end_column", "occurrence")))
        self.assertIn("val alias = failure\n        alias.printStackTrace()", alias["sources"]["Case.kt"])
        self.assertEqual([], alias["expected"])
        self.assertIn("java.lang.Thread.dumpStack()", qualified["sources"]["Case.kt"])
        self.assertEqual([], qualified["expected"])
        self.assertIn("report(failure)", item["negative"]["sources"]["Case.kt"])
        self.assertTrue(any("redaction" in text for text in item["limitations"]))

    def test_message_cause_policy_retains_string_factory_fp_alias_fn_and_real_enabled_option(self):
        item = self.by_id[RULES[2]]
        positive = item["positive"]
        self.assertEqual(["IllegalArgumentException()", "IllegalStateException()"], [row["span"] for row in positive["expected"]])
        self.assertIn('IllegalArgumentException("value must be positive")', item["negative"]["sources"]["Case.kt"])
        self.assertIn("IllegalStateException(cause)", item["negative"]["sources"]["Case.kt"])
        factory, alias, configured = item["controls"]
        self.assertEqual(["anticipated_upstream_false_positive", "anticipated_upstream_false_negative", "owner_configured_policy_boundary"],
                         [row["assessment"] for row in item["controls"]])
        self.assertIn('fun IllegalArgumentException(): String = "invalid input"', factory["sources"]["Case.kt"])
        self.assertIn("StatusFactory.IllegalArgumentException()", factory["sources"]["Case.kt"])
        self.assertNotIn("throw ", factory["sources"]["Case.kt"])
        self.assertEqual(("IllegalArgumentException()", 7, 46, 72, 1),
                         tuple(factory["expected"][0][key] for key in ("span", "line", "column", "end_column", "occurrence")))
        self.assertIn("import java.lang.IllegalArgumentException as InvalidInput", alias["sources"]["Case.kt"])
        self.assertIn("throw InvalidInput()", alias["sources"]["Case.kt"])
        self.assertEqual([], alias["expected"])
        self.assertEqual(positive["sources"], configured["sources"])
        self.assertEqual({"exceptions": ["IllegalStateException"]}, configured["options"])
        self.assertEqual([positive["expected"][1]], configured["expected"])
        self.assertEqual("configuration", configured["case_kind"])
        self.assertEqual([dict(name="exceptions", type="List<String>", defaults=[
            "ArrayIndexOutOfBoundsException", "Exception", "IllegalArgumentException", "IllegalMonitorStateException",
            "IllegalStateException", "IndexOutOfBoundsException", "NullPointerException", "RuntimeException", "Throwable",
        ])], item["upstream_rule_options"])
        row = next(row for row in self.matrix["analyses"] if row["case_id"] == configured["id"])
        self.assertEqual((False, True, True, 1, None), tuple(row[key] for key in (
            "rule_disabled_for_this_configuration_control", "require_active_upstream_descriptor",
            "selected_sarif_descriptor_enabled_required", "authored_expected_count", "expected_override")))
        self.assertTrue(any("initCause" in text for text in item["limitations"]))
        self.assertTrue(any("nonempty null" in text for text in item["limitations"]))

    def test_all_twenty_seven_matrix_rows_bind_intent_options_membership_and_literal_ranges(self):
        rows = self.matrix["analyses"]
        self.assertEqual(27, len(rows))
        self.assertEqual("AUTHORED_NOT_RUN", self.matrix["state"])
        for rule_index, rule in enumerate(RULES):
            fixture = self.by_id[rule]
            for index in range(9):
                row = rows[rule_index * 9 + index]
                case_id, pointer, node, assessment = matrix_case(fixture, index)
                disabled = case_id == "configuration"
                self.assertEqual((rule, case_id, pointer, assessment), tuple(row[key] for key in ("rule_id", "case_id", "fixture_json_pointer", "assessment")))
                self.assertEqual(case_id if index < 6 else node["case_kind"], row["case_kind"])
                self.assertEqual(RELATIVE + "/fixtures/" + rule.rsplit(".", 1)[1] + ".json", row["fixture_path"])
                self.assertEqual(node["intent"], row["intent"])
                self.assertEqual([] if disabled else node["expected"], row["authored_expected_ranges"])
                self.assertEqual(len(row["authored_expected_ranges"]), row["authored_expected_count"])
                self.assertEqual(node.get("options", {}), row["upstream_options"])
                self.assertEqual(disabled, row["rule_disabled_for_this_configuration_control"])
                self.assertEqual(not disabled, row["require_active_upstream_descriptor"])
                self.assertEqual(not disabled, row["selected_sarif_descriptor_enabled_required"])
                self.assertEqual([] if disabled else None, row["expected_override"])
                self.assertTrue(row["require_actual_native_launch"])
                self.assertEqual("detekt." + fixture["upstream_id"].replace(":", "."), row["selected_sarif_rule_id"])
                self.assertEqual(digest(canonical(node["sources"])), row["source_set_sha256"])
                self.assertEqual([dict(path=path, sha256=digest(source.encode()), bytes=len(source.encode()))
                                  for path, source in sorted(node["sources"].items())], row["source_files"])
                self.assertEqual(("not_run", False, None, False), tuple(row[key] for key in ("state", "attempted", "observed_findings", "natural_project_observation")))

    def test_exact_schedule_counts_preserve_fp_fn_limits_and_do_not_count_replays_as_new_sources(self):
        self.assertEqual(expected_schedule(RULES, self.by_id), self.matrix["invocation_schedule"])
        schedule, rows, counts = self.matrix["invocation_schedule"], self.matrix["analyses"], self.matrix["counts"]
        self.assertEqual((27, 37, 28, 31, 113, 9), tuple(counts[key] for key in
                         ("authored_analyses", "all_calls", "native_launch_ceiling", "expected_runtime_callbacks", "planned_progress_events", "independent_controls")))
        self.assertEqual(28, sum(row["native_launch_required"] for row in schedule))
        self.assertEqual(31, sum(row["runtime_callback_required"] for row in schedule))
        self.assertEqual((13, 14, 19, 17, 21), tuple(counts[key] for key in ("authored_alert_bearing_analyses", "authored_zero_finding_analyses",
                         "authored_expected_finding_observations", "unique_rule_source_sets", "unique_rule_source_configuration_sets")))
        self.assertEqual(13, sum(bool(row["authored_expected_count"]) for row in rows))
        self.assertEqual(19, sum(row["authored_expected_count"] for row in rows))
        self.assertEqual(17, len({(row["rule_id"], row["source_set_sha256"]) for row in rows}))
        self.assertEqual(21, len({(row["rule_id"], row["source_set_sha256"], canonical(row["upstream_options"]),
                                 row["rule_disabled_for_this_configuration_control"]) for row in rows}))
        expected_assessments = dict(anticipated_upstream_false_positive=2, anticipated_upstream_false_negative=4,
                                    predicate_boundary=1, opt_in_policy_limit=1, owner_configured_policy_boundary=1)
        self.assertEqual(expected_assessments, counts["independent_control_assessments"])
        self.assertEqual(expected_assessments, dict(Counter(c["assessment"] for item in self.fixtures for c in item["controls"])))
        self.assertEqual(0, counts["actual_executions"])
        self.assertIsNone(counts["actual_findings"])
        self.assertEqual(0, counts["natural_project_observations"])
        self.assertEqual(0, counts["precision_reviewed_natural_observations"])

    def test_named_semantic_and_graph_gaps_stay_context_local_with_no_observability_stage_transfer(self):
        gaps = self.scope["named_coroutine_and_resource_gaps"]
        self.assertEqual(list(GAPS), [row["rule_id"] for row in gaps])
        self.assertEqual(set(GAPS), set(self.contract["non_delivered_rules"]))
        for row in gaps:
            self.assertEqual(GAPS[row["rule_id"]], (row["relation"], row["depth"]))
            for key in ("upstream_id", "relation", "depth", "default_activation", "catalog_record_sha256"):
                self.assertEqual(self.mapping[row["rule_id"]][key], row[key])
            self.assertEqual(self.mapping[row["rule_id"]]["gaps"], row["mapping_gaps"])
            self.assertNotIn(row["rule_id"], self.scope["existing_detekt_mapping"]["included156_rule_ids"])
            self.assertEqual("unavailable_in_this_light_context", row["state"])
            self.assertTrue(row["no_stage_transfer_from_broad_catch_or_other_syntax_rules"])
            self.assertTrue(row["no_stage_transfer_from_observability_or_other_syntax_rules"])
            for key in ("delivered_by_this_cohort", "attempted", "implemented", "tested", "qualified"):
                self.assertFalse(row[key])
        next_capability = self.scope["next_reusable_capability"]
        self.assertEqual("ktc.kotlin.detekt.potential-bugs.missing-use-call", next_capability["first_exact_rule_id"])
        self.assertEqual("potential-bugs:MissingUseCall", next_capability["first_upstream_id"])
        self.assertIn("LIGHT-only", next_capability["current_boundary"])
        self.assertIn("not inferred, transferred or claimed", next_capability["current_boundary"])
        self.assertTrue(next_capability["required_inputs"])
        self.assertEqual(2, len(next_capability["remaining_refinements"]))
        self.assertFalse(next_capability["semantic_execution_claimed"])
        self.assertFalse(next_capability["qualification_claimed"])
        self.assertIn("does not inspect", self.contract["no_taint_or_redaction_proof"])

    def test_no_execution_authority_catalog_expansion_scope_exclusion_or_qualification_is_authored(self):
        self.assertIsNone(self.registry["default_cohort"])
        self.assertFalse(self.registry["multi_cohort_or_all_selector_allowed"])
        self.assertFalse(self.registry["old_data_registry_or_fixture_bytes_changed"])
        self.assertFalse(self.registry["old_fixture_expectations_or_plan_identities_changed"])
        for key in ("actual_tests_executed", "actual_provider_invocations", "historical_stage_transfer"):
            self.assertEqual(0, self.registry[key])
        current = self.scope["current_scope"]
        self.assertEqual((1663, 8, 0, 0), tuple(current[key] for key in ("ids", "contexts", "new_contexts", "historical_stage_transfer")))
        self.assertEqual([], current["scope_exclusions"])
        self.assertEqual([], current["changed_catalog_or_activation_files"])
        combined = self.scope["combined_proposed_authoring_scope"]
        self.assertEqual((22, 199, 7, 1), tuple(combined[key] for key in ("rule_count", "authored_analyses", "baseline_cohort_count", "additional_cohort_count")))
        self.assertFalse(combined["scope_reduction"])
        self.assertFalse(combined["aggregate_execution_or_sidecar_created"])
        self.assertEqual(0, combined["baseline_execution_stage_transfer"])
        self.assertFalse(self.registry["cohorts"][0]["execution_admitted"])
        self.assertFalse(self.contract["execution_admitted"])
        self.assertFalse(self.contract["input_contract"]["genuine_imported_model_execution"])
        self.assertEqual("LEGACY_EXPLICIT_INPUT", self.contract["input_contract"]["provider_input_mode"])
        self.assertEqual("light", self.contract["input_contract"]["analysis_mode"])

    def test_closed_eight_selector_preserves_seven_constructors_and_all_previous_scope_subsets(self):
        controls = (KOTLIN / "DetektCorpusControls.kt").read_text()
        build = (ROOT / "modules/provider-detekt/build.gradle.kts").read_text()
        old = re.findall(r'val [A-Z_]+ = DetektCorpusCohort\("([^"]+)", linkedMapOf\((.*?)\)\)', controls, re.DOTALL)
        expanded = re.findall(r'val [A-Z_]+ = expansion\("([^"]+)", linkedMapOf\((.*?)\)\)', controls, re.DOTALL)
        protocol = re.findall(r'val [A-Z_]+ = protocol\("([^"]+)", linkedMapOf\((.*?)\)\)', controls, re.DOTALL)
        added = re.findall(r'val [A-Z_]+ = observability\("([^"]+)", linkedMapOf\((.*?)\)\)', controls, re.DOTALL)
        self.assertEqual(list(OLD_IDENTITIES), [name for name, _ in old])
        self.assertEqual(list(PRESERVED_EXPANSIONS), [name for name, _ in expanded])
        self.assertEqual(["protocol-and-catch-boundaries-v1"], [name for name, _ in protocol])
        self.assertEqual([NAME], [name for name, _ in added])
        self.assertEqual([(rule, "9") for rule in RULES], re.findall(r'"(ktc\.[^"]+)" to ([0-9]+)', added[0][1]))
        prior_pairs = [pair for _, body in old + expanded + protocol for pair in re.findall(r'"(ktc\.[^"]+)" to ([0-9]+)', body)]
        self.assertEqual((19, 172), (len(prior_pairs), sum(int(count) for _, count in prior_pairs)))
        all_pairs = prior_pairs + re.findall(r'"(ktc\.[^"]+)" to ([0-9]+)', added[0][1])
        self.assertEqual((22, 199), (len(all_pairs), sum(int(count) for _, count in all_pairs)))
        self.assertEqual(22, len({rule for rule, _ in all_pairs}))
        gate = re.search(r'val detektCorpusCohortNames = setOf\((.*?)\)', build, re.DOTALL)
        self.assertEqual(list(PREVIOUS_IDENTITIES) + [NAME], re.findall(r'"([^"]+)"', gate.group(1)))
        for anchor in ("val expansion: Boolean get() = expansionPlan != null", "val protocol: Boolean get() = protocolPlan != null",
                       "val observability: Boolean get() = observabilityPlan != null", "BASELINE_AUTHORED_RULES = 10",
                       "BASELINE_AUTHORED_ANALYSES = 91", "PRE_PROTOCOL_AUTHORED_RULES = 16", "PRE_PROTOCOL_AUTHORED_ANALYSES = 145",
                       "PRE_OBSERVABILITY_AUTHORED_RULES = 19", "PRE_OBSERVABILITY_AUTHORED_ANALYSES = 172",
                       "FULL_AUTHORED_RULES = 22", "FULL_AUTHORED_ANALYSES = 199",
                       "Collections.unmodifiableList(preProtocol + PROTOCOL_AND_CATCH_BOUNDARIES)",
                       "Collections.unmodifiableList(preObservability + EXCEPTION_OBSERVABILITY_POLICY)",
                       "all.singleOrNull { it.name == name }", "listOf(expansionPlan, protocolPlan, observabilityPlan).count { it != null } <= 1",
                       "if (!expansion && !protocol && !observability) null", "independentControlIds.getValue(rule)"):
            self.assertIn(anchor, controls)
        self.assertNotIn('tasks.named("check")', build)
        self.assertNotIn('tasks.named("test").dependsOn', build)

    def test_loader_binds_thirty_eight_files_and_explicit_historical_scope_membership_before_decoding(self):
        inventory = (KOTLIN / "DetektCorpusInventory.kt").read_text()
        old, _, _ = compiled_pins("DetektCorpusExpansionPins.kt")
        protocol, _, _ = compiled_pins("DetektCorpusProtocolPins.kt")
        added, _, _ = compiled_pins("DetektCorpusObservabilityPins.kt")
        self.assertEqual((22, 8, 8), (len(old), len(protocol), len(added)))
        self.assertFalse(old.keys() & protocol.keys())
        self.assertFalse(added.keys() & (old.keys() | protocol.keys()))
        self.assertEqual(38, len(old | protocol | added))
        for path, sha in (old | protocol | added).items():
            self.assertEqual(sha, digest((ROOT / path).read_bytes()))
        for anchor in ("DetektCorpusExpansionPins.files + DetektCorpusProtocolPins.files + DetektCorpusObservabilityPins.files", "it.size == 38",
                       "val bytes = filePins.keys.associateWith { readPinned(root, it) }", "hash(bytes) == filePins.getValue(relative)",
                       "bytes.contentEquals(readPinned(repository, path))", "listOf(BASELINE, EXPANSION, PROTOCOL, OBSERVABILITY)",
                       "Files.walk(repository.resolve(root), 3)", "it.limit(65)", "bytes.size in 1..1_048_576",
                       "Files.newInputStream(path, NOFOLLOW_LINKS)", "!Files.isSymbolicLink", "validateNamespace(EXPANSION",
                       "validateNamespace(PROTOCOL", "validateNamespace(OBSERVABILITY", "boundRuleCount", "boundAnalysisCount",
                       "DetektCorpusCohort.preProtocol, DetektCorpusCohort.PRE_OBSERVABILITY_AUTHORED_RULES",
                       "DetektCorpusCohort.preObservability, DetektCorpusCohort.FULL_AUTHORED_RULES",
                       "validateNamedGaps(PROTOCOL)", "validateNamedGaps(OBSERVABILITY)", "preserved_protocol_namespace_files",
                       'it.text("rule_id") in previousIds', 'it.text("rule_id") in priorIds',
                       'identity.flag("requires_explicit_owner_opt_in")', "cohort.independentControlIds.getValue"):
            self.assertIn(anchor, inventory)
        self.assertNotIn('it.text("corpus_id") != "detekt-syntax-protocol-v1"', inventory)
        self.assertNotIn("catch (", inventory)

    def test_real_driver_retains_three_analyze_sites_strict_descriptors_and_four_namespace_witnesses(self):
        driver = (KOTLIN / "DetektSyntaxCorpusIntegrationTest.kt").read_text()
        for anchor in ("DetektCorpusInventory.load(repository)", "inventory.verify()", "DetektCorpusInventory.OBSERVABILITY",
                       '"providers/detekt-fixtures/test_syntax_observability.py"', "DetektCorpusObservabilityPins::class.java",
                       "path.startsWith(observabilityFixtures)", "DetektCorpusCohort.PRE_PROTOCOL_AUTHORED_ANALYSES",
                       "DetektCorpusCohort.PRE_OBSERVABILITY_AUTHORED_ANALYSES", "it.ruleId in previousIds", "it.ruleId in priorIds",
                       'assertEquals(13, approved.count { it.activation == "advisory" })',
                       'assertEquals(9, approved.count { it.activation == "opt_in" })',
                       'assertEquals(6, prior.count { it.activation == "opt_in" })',
                       'assertEquals(corpus.any { it.activation == "opt_in" }, cohort.requiresExplicitOwnerOptIn)',
                       "assertEquals(!case.disabled, selectedSarifId in parsedReport.enabledSarifIds",
                       "assertEquals(input.context.scope, coverage.context)", "assertEquals(desired, actual",
                       "assertEquals(fixture.ruleId, replayCoverage.ruleId)", "assertNull(replayCoverage.certificateId)",
                       "assertEquals(request.context.scope, unsupportedCoverage.context)", "assertNull(unsupportedCoverage.certificateId)",
                       'put("provider_status", "not_run")', 'put("attempted", false)'):
            self.assertIn(anchor, driver)
        self.assertEqual(3, len(re.findall(r"\.analyze\([^\n]+limits\.cancellation\)", driver)))
        self.assertEqual(1, len(re.findall(r"\.run\(probeInput, authority, limits\.cancellation\)", driver)))
        for kind in ("ANALYSIS", "CLI_PROBE", "AUTHORIZATION_DENIAL", "ABSTENTION"):
            self.assertIn("limits.reserve(CorpusInvocationKind." + kind, driver)
        for prohibited in ("ProcessBuilder", "ProcessHandle", "CancellationToken.NONE", "verifiedModelLight", "@TempDir", ".destroy("):
            self.assertNotIn(prohibited, driver)

    def test_runtime_bounds_custody_source_and_external_completion_limits_stay_unchanged(self):
        controls = (KOTLIN / "DetektCorpusControls.kt").read_text()
        evidence = (KOTLIN / "DetektCorpusEvidence.kt").read_text()
        self.assertEqual("41a88cb600eca87200a5510883e0b52942d0b6a88baeca2ef18f7303e23eae9e", digest((KOTLIN / "DetektCorpusEvidence.kt").read_bytes()))
        for anchor in ("MAX_INVOCATIONS = 40", "MAX_AGGREGATE_MILLIS = 1_200_000L", "MAX_CHILD_MILLIS = 60_000L",
                       "schedule.getOrNull(reserved) == CorpusInvocationSpec(kind, ruleId, caseId)", "Duplicate invocation is a forbidden retry",
                       "confirmedExecutions == cohort.nativeLaunchCeiling", "observedRuntimeCallbacks == cohort.expectedRuntimeCallbacks",
                       "minOf(settings.childMillis, remainingMillis())", 'observability -> "syntax-exception-observability"',
                       'protocol -> "syntax-protocol"'):
            self.assertIn(anchor, controls)
        for anchor in ("SecureDirectoryStream", "CREATE_NEW, WRITE, NOFOLLOW_LINKS", "requireSingleLink(path)", "path == root.resolve(liveRelative)"):
            self.assertIn(anchor, evidence)
        self.assertNotIn("CURRENT", evidence)
        self.assertNotIn("TRUNCATE_EXISTING", evidence)
        self.assertEqual({"max_invocations": 40, "aggregate_millis": 1200000, "child_millis": 60000, "progress_events": 124},
                         self.contract["budgets"]["unchanged_hard_maxima"])
        self.assertIn("not independent task completion proof", self.contract["evidence_custody"]["publication"])
        self.assertFalse(self.contract["evidence_custody"]["may_touch_other_cohort_evidence_or_current_pointer"])

    def test_inert_validators_reject_wrong_ranges_duplicate_json_and_missing_any_opt_in_binding(self):
        original = self.by_id[RULES[0]]["positive"]
        wrong = deepcopy(original)
        wrong["expected"][0]["line"] = 1
        with self.assertRaisesRegex(ValueError, "coordinates"):
            validate_case(wrong)
        wrong = deepcopy(original)
        wrong["expected"][0]["occurrence"] = 1
        with self.assertRaisesRegex(ValueError, "span absent"):
            validate_case(wrong)
        with self.assertRaisesRegex(ValueError, "duplicate JSON"):
            json.loads('{"requires_explicit_owner_opt_in":true,"requires_explicit_owner_opt_in":false}', object_pairs_hook=unique_pairs)
        for field in ("requires_explicit_owner_opt_in", "opt_in_rule_ids"):
            for in_identity in (False, True):
                plan = deepcopy(self.registry["cohorts"][0])
                target = plan["plan_identity"] if in_identity else plan
                target[field] = False if field == "requires_explicit_owner_opt_in" else list(RULES[:2])
                with self.assertRaisesRegex(ValueError, "all-three opt-in"):
                    validate_identity(plan)
        plan = deepcopy(self.registry["cohorts"][0])
        plan["plan_identity"]["scope_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "pinned plan"):
            validate_identity(plan)

    def test_new_kotlin_controls_are_inert_source_and_keep_all_fifty_four_prior_test_methods(self):
        source = (KOTLIN / "DetektCorpusObservabilityTest.kt").read_text()
        self.assertEqual(13, source.count("@Test fun"))
        for anchor in ("observability budget refuses fabricated or reordered controls", '"control-6"',
                       '"configured-state-exception-only-keeps-one-active-finding"', "assertEquals(14, ranges)",
                       "assertEquals(21, sourceEntries)", "Pinned authored corpus bytes changed", 'read("$root/scope.json")',
                       "DetektCorpusInventory.filePins", "listOf(13, 14, 19, 17, 21, 113)"):
            self.assertIn(anchor, source)
        files = (("DetektCorpusControlsTest.kt", 16), ("DetektCorpusEvidenceTest.kt", 12),
                 ("DetektCorpusInventoryTest.kt", 13), ("DetektCorpusProtocolTest.kt", 13))
        self.assertEqual(54, sum(count for _, count in files))
        for file, count in files:
            self.assertEqual(count, (KOTLIN / file).read_text().count("@Test fun"))
        for file in ("DetektCorpusObservabilityTest.kt", *(file for file, _ in files)):
            body = (KOTLIN / file).read_text()
            self.assertNotIn(".analyze(", body)
            self.assertNotIn("ProcessBuilder", body)
        self.assertEqual(1, (KOTLIN / "DetektSyntaxCorpusIntegrationTest.kt").read_text().count("@Test fun"))


if __name__ == "__main__":
    unittest.main()
