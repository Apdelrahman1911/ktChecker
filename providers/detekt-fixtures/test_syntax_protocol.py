#!/usr/bin/env python3
"""Inert protocol source/data authoring checks, never Kotlin/provider/native execution."""

from collections import Counter
from copy import deepcopy
import json
from pathlib import Path
import re
import unittest

from test_syntax_expansion import (
    ASSESSMENTS, EXPECTED_COHORTS as PRESERVED_EXPANSIONS, OLD_IDENTITIES, STANDARD,
    canonical, digest, expected_schedule, matrix_case, read, unique_pairs, validate_case,
)


ROOT = Path(__file__).resolve().parents[2]
RELATIVE = "providers/detekt-fixtures/corpus/syntax-protocol-v1"
PROTOCOL = ROOT / RELATIVE
KOTLIN = ROOT / "modules/provider-detekt/src/corpusTest/kotlin/dev/ktchecker/providers/detekt"
NAME = "protocol-and-catch-boundaries-v1"
RULES = (
    "ktc.kotlin.detekt.potential-bugs.iterator-not-throwing-no-such-element-exception",
    "ktc.kotlin.detekt.potential-bugs.wrong-equals-type-parameter",
    "ktc.kotlin.detekt.exceptions.too-generic-exception-caught",
)
POLICY = RULES[2]
IDENTITY = "c832b3938eb553dad22bf483db5780dde8571b86b83679f20064c95fdf2356ad"
PREVIOUS_IDENTITIES = dict(OLD_IDENTITIES, **{
    "performance-shapes-v1": "b9c1f5300aa5eb0fa12ba772bd42f5c5b49c1ca96c95a62b3191b15698b375dd",
    "public-contract-policy-v1": "75ec7045f1c36c6a01ea9e479acf8672aca29a62b964cc35972a84f606523515",
})
FIXTURE_PINS = {
    RULES[0]: "948e040ddb8dc8ee7b4b4a03f28b5f99c286488ac067cf2b8de0bd0f9d94a421",
    RULES[1]: "7d61a3327c1711387d2afafe2632518930fe9e1e01ca3f6403776ca9273465e6",
    RULES[2]: "e6ec648a998557360fc0f102021b174465174074ecb63f95f724cfc770e50ff9",
}
PREDICATE_PINS = {
    RULES[0]: "027df9c850dcda0cf4335940abd256b5e227317c0ea5655deb160aefeef0e1b1",
    RULES[1]: "90c3ea58e885b36c37113bea7a1b77246316ba556e27f3e6167d9868b685f7ef",
    RULES[2]: "637f4fa0334f4009a9407f4baf21705801868c0a0b50b96e1a76f9740af88fa6",
}
GAPS = {
    "ktc.coroutines.swallowed-cancellation": ("requires_refinement", "graph"),
    "ktc.coroutines.result-swallowing-cancellation": ("requires_refinement", "graph"),
    "ktc.coroutines.suspending-finally-cleanup": ("requires_refinement", "graph"),
    "ktc.resources.owned_autocloseable_exit": ("requires_refinement", "semantic"),
    "ktc.kotlin.detekt.potential-bugs.missing-use-call": ("exact", "semantic"),
}


def compiled_pins(filename):
    source = (KOTLIN / filename).read_text()
    files, remaining = source.split("val files:", 1)[1].split("val planIdentities:", 1)
    plans, controls = remaining.split("val controlIds:", 1)
    return (
        dict(re.findall(r'"([^"]+)" to "([0-9a-f]{64})"', files)),
        {name: json.loads(value) for name, value in re.findall(r'"([a-z0-9-]+)" to ("(?:\\.|[^"\\])*")', plans)},
        {rule: re.findall(r'"([a-z0-9-]+)"', values) for rule, values in
         re.findall(r'"(ktc\.[^"]+)" to Collections.unmodifiableList\(listOf\((.*?)\)\)', controls)},
    )


def validate_identity(plan):
    """Independent inert identity check, not a call to the Kotlin loader or an execution grant."""
    identity = plan["plan_identity"]
    if (identity.get("requires_explicit_owner_opt_in") is not True or identity.get("opt_in_rule_ids") != [POLICY] or
            identity.get("cohort_id") != NAME or plan.get("requires_explicit_owner_opt_in") is not True or
            plan.get("opt_in_rule_ids") != [POLICY]):
        raise ValueError("explicit mixed opt-in identity required")
    if plan["plan_identity_sha256"] != IDENTITY or digest(canonical(identity)) != IDENTITY:
        raise ValueError("pinned plan identity mismatch")


class SyntaxProtocolAuthoringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.paths = sorted((PROTOCOL / "fixtures").glob("*.json"))
        cls.fixtures = [read(path) for path in cls.paths]
        cls.by_id = {item["rule_id"]: item for item in cls.fixtures}
        cls.scope = read(PROTOCOL / "scope.json")
        cls.registry = read(PROTOCOL / "cohorts.json")
        cls.contract = read(PROTOCOL / "integration-contract.json")
        cls.matrix = read(PROTOCOL / "cohorts" / (NAME + ".json"))
        cls.mapping = {row["rule_id"]: row for row in read(ROOT / "modules/provider-detekt/src/main/resources/detekt-rule-map.json")["mappings"]}

    def test_exact_three_existing_syntax_mappings_preserve_mixed_policy_classification(self):
        self.assertEqual(3, len(self.paths))
        self.assertEqual(set(RULES), self.by_id.keys())
        self.assertEqual(276, len(self.mapping))
        included = sorted(rule for rule, row in self.mapping.items()
                          if row["relation"] == "exact" and row["depth"] == "syntax" and row["default_activation"] != "excluded")
        self.assertEqual(156, len(included))
        self.assertEqual(included, self.scope["existing_detekt_mapping"]["included156_rule_ids"])
        self.assertTrue(set(RULES) <= set(included))
        self.assertEqual(["advisory", "advisory", "opt_in"], [self.by_id[rule]["default_activation"] for rule in RULES])
        self.assertEqual(["probable_defect", "probable_defect", "style"], [self.mapping[rule]["kind"] for rule in RULES])
        self.assertEqual(("info", "intent_dependent"), tuple(self.mapping[POLICY][key] for key in ("severity", "certainty")))
        for item in self.fixtures:
            self.assertEqual("detekt-syntax-protocol-v1", item["corpus_id"])
            self.assertEqual("unexecuted", item["authoring_state"])
            self.assertEqual(("detekt", "2.0.0-alpha.6", "2.4.10"), tuple(item[key] for key in ("provider_id", "provider_version", "embedded_kotlin_version")))
            for key in ("upstream_id", "relation", "depth", "default_activation", "catalog_record_sha256"):
                self.assertEqual(self.mapping[item["rule_id"]][key], item[key])
            self.assertFalse({"passed", "tested", "qualified"} & item.keys())

    def test_primary_predicates_and_light_eligibility_are_research_not_runtime_evidence(self):
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
        self.assertEqual("d65162aebf6693adc9a7a0b2633246f5f75e823b326142d0be04fbbb295bb38b",
                         self.by_id[RULES[1]]["predicate_helper_citation"]["source_file_sha256"])
        self.assertEqual([30, 31], self.by_id[RULES[1]]["predicate_helper_citation"]["predicate_line_numbers"])
        self.assertEqual("51354031f6b950805418520ffda2cf6f81775f4df04a05d954ce793ef297ae48",
                         self.by_id[POLICY]["predicate_helper_citation"]["source_file_sha256"])

    def test_preserved_sixteen_fixture_bytes_twelve_namespace_files_and_six_identities(self):
        previous = self.scope["preserved_baseline"]
        self.assertEqual((16, 145), (previous["rule_count"], previous["authored_analyses"]))
        self.assertEqual(16, len(previous["fixture_files"]))
        self.assertEqual(145, sum(row["analyses"] for row in previous["fixture_files"]))
        self.assertEqual(12, len(previous["preserved_expansion_namespace_files"]))
        for row in previous["fixture_files"] + previous["preserved_expansion_namespace_files"]:
            data = (ROOT / row["path"]).read_bytes()
            self.assertEqual(row["sha256"], digest(data))
            self.assertEqual(row["bytes"], len(data))
        self.assertEqual(PREVIOUS_IDENTITIES, {row["cohort_id"]: row["plan_identity_sha256"] for row in previous["cohorts"]})
        self.assertEqual([38, 38, 24, 25, 37, 37], [row["planned_invocations"] for row in previous["cohorts"]])
        self.assertTrue(all(not row["modified_by_this_packet"] for row in previous["cohorts"]))
        old_rules = {row["rule_id"] for row in previous["fixture_files"]}
        self.assertFalse(old_rules & set(RULES))
        self.assertEqual(19, len(old_rules | set(RULES)))
        self.assertEqual(0, previous["execution_stage_transfer"])
        self.assertEqual("88780bbcfbbbfb429ce8021eb3aa732c1f8a53771b9f3960bc6937866649b107",
                         digest((KOTLIN / "DetektCorpusExpansionPins.kt").read_bytes()))

    def test_eight_new_compiled_pins_and_explicit_canonical_identity_bind_the_whole_plan(self):
        pins, plans, controls = compiled_pins("DetektCorpusProtocolPins.kt")
        actual = {str(path.relative_to(ROOT)): digest(path.read_bytes()) for path in PROTOCOL.rglob("*") if path.is_file()}
        self.assertEqual(8, len(pins))
        self.assertEqual(actual, pins)
        self.assertEqual({rule: [row["id"] for row in self.by_id[rule]["controls"]] for rule in RULES}, controls)
        self.assertEqual([NAME], self.registry["allowed_cohort_names"])
        self.assertEqual(1, len(self.registry["cohorts"]))
        plan = self.registry["cohorts"][0]
        validate_identity(plan)
        self.assertEqual({NAME: canonical(plan["plan_identity"]).decode()}, plans)
        self.assertEqual(digest((PROTOCOL / "scope.json").read_bytes()), self.registry["scope_sha256"])
        self.assertEqual(digest((PROTOCOL / "integration-contract.json").read_bytes()), self.registry["integration_contract_sha256"])
        self.assertEqual(digest((ROOT / plan["case_matrix_path"]).read_bytes()), plan["case_matrix_sha256"])
        self.assertEqual(list(RULES), [row["rule_id"] for row in plan["selected_rules"]])
        for row in plan["selected_rules"]:
            self.assertEqual(FIXTURE_PINS[row["rule_id"]], row["fixture_sha256"])
            self.assertEqual(9, row["analyses"])
        self.assertEqual([POLICY], self.matrix["explicit_opt_in_policy_rule_ids"])

    def test_ranges_lifecycle_cases_and_suppression_are_independent_source_expectations(self):
        range_count = 0
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
            positive, suppression = item["positive"], item["suppression"]
            self.assertEqual(positive["expected"], suppression["expected"])
            self.assertEqual(positive["sources"]["Case.kt"], suppression["sources"]["Case.kt"])
            copy = suppression["sources"]["SuppressedCase.kt"]
            self.assertTrue(copy.startswith('@file:Suppress("' + item["upstream_id"].split(":")[1] + '")\n\n'))
            self.assertEqual(positive["sources"]["Case.kt"], copy.split("\n\n", 1)[1].replace("package suppressed.", "package ", 1))
        self.assertEqual(14, range_count)  # Source spans, never detector observations.

    def test_iterator_exhaustion_controls_preserve_delegation_qualification_and_reachability_gaps(self):
        item = self.by_id[RULES[0]]
        positive = item["positive"]
        self.assertEqual(["ExhaustedSentinel"], [row["span"] for row in positive["expected"]])
        self.assertIn("override fun hasNext(): Boolean = index < values.size", positive["sources"]["Case.kt"])
        self.assertIn("if (!hasNext()) return 0", positive["sources"]["Case.kt"])
        self.assertIn('throw NoSuchElementException("No elements")', item["negative"]["sources"]["Case.kt"])
        delegate, dormant, qualified = item["controls"]
        self.assertEqual(["anticipated_upstream_false_positive", "anticipated_upstream_false_negative", "anticipated_upstream_false_positive"],
                         [row["assessment"] for row in item["controls"]])
        self.assertIn("private val delegate = values.iterator()", delegate["sources"]["Case.kt"])
        self.assertIn("override fun next(): Int = delegate.next()", delegate["sources"]["Case.kt"])
        self.assertEqual(["DelegatingIterator"], [row["span"] for row in delegate["expected"]])
        self.assertIn("fun unusedExhaustion(): Nothing = throw NoSuchElementException", dormant["sources"]["Case.kt"])
        self.assertEqual(1, dormant["sources"]["Case.kt"].count("unusedExhaustion"))
        self.assertIn("if (!hasNext()) return 0", dormant["sources"]["Case.kt"])
        self.assertEqual([], dormant["expected"])
        self.assertIn("throw java.util.NoSuchElementException", qualified["sources"]["Case.kt"])
        self.assertEqual(["QualifiedExhaustionGuard"], [row["span"] for row in qualified["expected"]])

    def test_equals_typed_overloads_are_valid_declarations_and_ranges_are_not_package_substrings(self):
        item = self.by_id[RULES[1]]
        positive = item["positive"]
        self.assertIn("fun equals(other: DomainKey): Boolean", positive["sources"]["Case.kt"])
        self.assertNotIn("override fun equals", positive["sources"]["Case.kt"])
        self.assertEqual((4, 8, 0), tuple(positive["expected"][0][key] for key in ("line", "column", "occurrence")))
        self.assertNotIn("equals", positive["sources"]["Case.kt"].splitlines()[0])
        self.assertIn("override fun equals(other: kotlin.Any?)", item["negative"]["sources"]["Case.kt"])
        self.assertIn("override fun hashCode()", item["negative"]["sources"]["Case.kt"])
        typed, alias, interface = item["controls"]
        self.assertIn("override fun equals(other: Any?)", typed["sources"]["Case.kt"])
        self.assertIn("fun equals(other: ComparedKey)", typed["sources"]["Case.kt"])
        self.assertNotIn("override fun equals(other: ComparedKey)", typed["sources"]["Case.kt"])
        self.assertEqual((6, 8, 1), tuple(typed["expected"][0][key] for key in ("line", "column", "occurrence")))
        self.assertIn("typealias AnyValue = kotlin.Any?", alias["sources"]["Case.kt"])
        self.assertIn("override fun equals(other: AnyValue)", alias["sources"]["Case.kt"])
        self.assertEqual((6, 17, 0), tuple(alias["expected"][0][key] for key in ("line", "column", "occurrence")))
        self.assertEqual("anticipated_upstream_false_positive", alias["assessment"])
        self.assertIn("interface StringMatcher", interface["sources"]["Case.kt"])
        self.assertEqual("predicate_boundary", interface["assessment"])
        self.assertEqual([], interface["expected"])

    def test_generic_catch_is_opt_in_style_even_with_cancellation_rethrow_and_one_active_option_finding(self):
        item = self.by_id[POLICY]
        positive = item["positive"]
        self.assertEqual(["failure: Exception", "failure: Throwable"], [row["span"] for row in positive["expected"]])
        rethrow, qualified, configured = item["controls"]
        self.assertIn("if (failure is java.util.concurrent.CancellationException) throw failure", rethrow["sources"]["Case.kt"])
        self.assertNotIn("suspend ", rethrow["sources"]["Case.kt"])
        self.assertEqual("opt_in_policy_limit", rethrow["assessment"])
        self.assertEqual(["failure: Exception"], [row["span"] for row in rethrow["expected"]])
        self.assertIn("catch (failure: java.lang.Exception)", qualified["sources"]["Case.kt"])
        self.assertEqual("anticipated_upstream_false_negative", qualified["assessment"])
        self.assertEqual([], qualified["expected"])
        self.assertEqual(positive["sources"], configured["sources"])
        self.assertEqual({"exceptionNames": ["Throwable"]}, configured["options"])
        self.assertEqual([positive["expected"][1]], configured["expected"])
        self.assertEqual("configuration", configured["case_kind"])
        self.assertTrue(any("allowedExceptionNameRegex" in limit for limit in item["limitations"]))
        self.assertIn("not cancellation swallowing or leak proof", (PROTOCOL / "README.md").read_text())

    def test_all_twenty_seven_matrix_rows_bind_case_intent_options_hashes_and_exact_ranges(self):
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

    def test_exact_schedule_counts_do_not_inflate_source_diversity_or_borrow_observations(self):
        self.assertEqual(expected_schedule(RULES, self.by_id), self.matrix["invocation_schedule"])
        schedule, rows, counts = self.matrix["invocation_schedule"], self.matrix["analyses"], self.matrix["counts"]
        self.assertEqual((27, 37, 28, 31, 113, 9), tuple(counts[key] for key in
                         ("authored_analyses", "all_calls", "native_launch_ceiling", "expected_runtime_callbacks", "planned_progress_events", "independent_controls")))
        self.assertEqual(28, sum(row["native_launch_required"] for row in schedule))
        self.assertEqual(31, sum(row["runtime_callback_required"] for row in schedule))
        self.assertEqual((15, 12, 18, 17, 21), tuple(counts[key] for key in ("authored_alert_bearing_analyses", "authored_zero_finding_analyses",
                         "authored_expected_finding_observations", "unique_rule_source_sets", "unique_rule_source_configuration_sets")))
        self.assertEqual(15, sum(bool(row["authored_expected_count"]) for row in rows))
        self.assertEqual(18, sum(row["authored_expected_count"] for row in rows))
        self.assertEqual(17, len({(row["rule_id"], row["source_set_sha256"]) for row in rows}))
        self.assertEqual(21, len({(row["rule_id"], row["source_set_sha256"], canonical(row["upstream_options"]),
                                 row["rule_disabled_for_this_configuration_control"]) for row in rows}))
        self.assertEqual(dict(Counter(c["assessment"] for item in self.fixtures for c in item["controls"])), counts["independent_control_assessments"])
        self.assertEqual(0, counts["actual_executions"])
        self.assertIsNone(counts["actual_findings"])
        self.assertEqual(0, counts["natural_project_observations"])
        self.assertEqual(0, counts["precision_reviewed_natural_observations"])

    def test_named_coroutine_and_resource_gaps_are_not_transferred_to_catch_syntax(self):
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
            for key in ("delivered_by_this_cohort", "attempted", "implemented", "tested", "qualified"):
                self.assertFalse(row[key])
        next_capability = self.scope["next_reusable_capability"]
        self.assertEqual("ktc.kotlin.detekt.potential-bugs.missing-use-call", next_capability["first_exact_rule_id"])
        self.assertEqual("potential-bugs:MissingUseCall", next_capability["first_upstream_id"])
        self.assertIn("LIGHT-only", next_capability["current_boundary"])
        self.assertTrue(next_capability["required_inputs"])
        self.assertEqual(2, len(next_capability["remaining_refinements"]))
        self.assertFalse(next_capability["semantic_execution_claimed"])
        self.assertFalse(next_capability["qualification_claimed"])

    def test_no_execution_authority_stage_transfer_scope_exclusion_or_natural_qualification_is_authored(self):
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
        self.assertEqual((19, 172, 6, 1), tuple(combined[key] for key in ("rule_count", "authored_analyses", "baseline_cohort_count", "additional_cohort_count")))
        self.assertFalse(combined["scope_reduction"])
        self.assertFalse(combined["aggregate_execution_or_sidecar_created"])
        self.assertEqual(0, combined["baseline_execution_stage_transfer"])
        self.assertFalse(self.registry["cohorts"][0]["execution_admitted"])
        self.assertFalse(self.contract["execution_admitted"])
        self.assertFalse(self.contract["input_contract"]["genuine_imported_model_execution"])
        self.assertEqual("LEGACY_EXPLICIT_INPUT", self.contract["input_contract"]["provider_input_mode"])

    def test_closed_seven_selector_preserves_old_constructors_and_separate_expansion_meaning(self):
        controls = (KOTLIN / "DetektCorpusControls.kt").read_text()
        build = (ROOT / "modules/provider-detekt/build.gradle.kts").read_text()
        old = re.findall(r'val [A-Z_]+ = DetektCorpusCohort\("([^"]+)", linkedMapOf\((.*?)\)\)', controls, re.DOTALL)
        expanded = re.findall(r'val [A-Z_]+ = expansion\("([^"]+)", linkedMapOf\((.*?)\)\)', controls, re.DOTALL)
        protocol = re.findall(r'val [A-Z_]+ = protocol\("([^"]+)", linkedMapOf\((.*?)\)\)', controls, re.DOTALL)
        self.assertEqual(list(OLD_IDENTITIES), [name for name, _ in old])
        self.assertEqual(list(PRESERVED_EXPANSIONS), [name for name, _ in expanded])
        self.assertEqual([NAME], [name for name, _ in protocol])
        self.assertEqual([(rule, "9") for rule in RULES], re.findall(r'"(ktc\.[^"]+)" to ([0-9]+)', protocol[0][1]))
        gate = re.search(r'val detektCorpusCohortNames = setOf\((.*?)\)', build, re.DOTALL)
        selected_names = re.findall(r'"([^"]+)"', gate.group(1))
        self.assertEqual(list(PREVIOUS_IDENTITIES) + [NAME], selected_names[:7])
        self.assertEqual(list(PREVIOUS_IDENTITIES) + [NAME, "exception-observability-policy-v1"], selected_names)
        for anchor in ("val expansion: Boolean get() = expansionPlan != null", "val protocol: Boolean get() = protocolPlan != null",
                       "BASELINE_AUTHORED_RULES = 10", "BASELINE_AUTHORED_ANALYSES = 91", "PRE_PROTOCOL_AUTHORED_RULES = 16",
                       "PRE_PROTOCOL_AUTHORED_ANALYSES = 145", "PRE_OBSERVABILITY_AUTHORED_RULES = 19", "PRE_OBSERVABILITY_AUTHORED_ANALYSES = 172",
                       "FULL_AUTHORED_RULES = 22", "FULL_AUTHORED_ANALYSES = 199",
                       "Collections.unmodifiableList(preProtocol + PROTOCOL_AND_CATCH_BOUNDARIES)",
                       "Collections.unmodifiableList(preObservability + EXCEPTION_OBSERVABILITY_POLICY)", "all.singleOrNull { it.name == name }"):
            self.assertIn(anchor, controls)
        self.assertIn("requiresExplicitOwnerOptIn", controls)
        self.assertIn("if (!expansion && !protocol && !observability) null", controls)
        self.assertIn("independentControlIds.getValue(rule)", controls)
        self.assertNotIn('tasks.named("check")', build)
        self.assertNotIn('tasks.named("test").dependsOn', build)

    def test_loader_binds_thirty_files_before_parsing_and_validates_fixed_old_and_new_scopes(self):
        inventory = (KOTLIN / "DetektCorpusInventory.kt").read_text()
        pins, _, _ = compiled_pins("DetektCorpusProtocolPins.kt")
        old, _, _ = compiled_pins("DetektCorpusExpansionPins.kt")
        self.assertEqual(22, len(old))
        self.assertFalse(pins.keys() & old.keys())
        self.assertEqual(30, len(pins | old))
        added, _, _ = compiled_pins("DetektCorpusObservabilityPins.kt")
        self.assertEqual(8, len(added))
        self.assertFalse(added.keys() & (old.keys() | pins.keys()))
        self.assertEqual(38, len(old | pins | added))
        for path, sha in (old | pins | added).items():
            self.assertEqual(sha, digest((ROOT / path).read_bytes()))
        for anchor in ("DetektCorpusExpansionPins.files + DetektCorpusProtocolPins.files + DetektCorpusObservabilityPins.files", "it.size == 38", "files.size == 8",
                       "val bytes = filePins.keys.associateWith { readPinned(root, it) }",
                       "hash(bytes) == filePins.getValue(relative)", "bytes.contentEquals(readPinned(repository, path))",
                       "listOf(BASELINE, EXPANSION, PROTOCOL, OBSERVABILITY)", "Files.walk(repository.resolve(root), 3)", "it.limit(65)",
                       "bytes.size in 1..1_048_576", "Files.newInputStream(path, NOFOLLOW_LINKS)", "!Files.isSymbolicLink",
                       "validateNamespace(EXPANSION", "validateNamespace(PROTOCOL", "boundRuleCount", "boundAnalysisCount",
                       "DetektCorpusCohort.preProtocol, DetektCorpusCohort.PRE_OBSERVABILITY_AUTHORED_RULES",
                       "DetektCorpusCohort.preObservability, DetektCorpusCohort.FULL_AUTHORED_RULES",
                       "validateNamespace(OBSERVABILITY", "validateNamedGaps(PROTOCOL)", "validateNamedGaps(OBSERVABILITY)",
                       "identity.flag(\"requires_explicit_owner_opt_in\")", "cohort.independentControlIds.getValue"):
            self.assertIn(anchor, inventory)
        self.assertNotIn("catch (", inventory)

    def test_real_driver_keeps_exact_call_sites_active_descriptors_and_fresh_three_namespace_witnesses(self):
        driver = (KOTLIN / "DetektSyntaxCorpusIntegrationTest.kt").read_text()
        for anchor in ("DetektCorpusInventory.load(repository)", "inventory.verify()", "DetektCorpusInventory.PROTOCOL",
                       '"providers/detekt-fixtures/test_syntax_protocol.py"', "DetektCorpusProtocolPins::class.java",
                       "path.startsWith(protocolFixtures)", "DetektCorpusCohort.PRE_PROTOCOL_AUTHORED_ANALYSES",
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

    def test_runtime_bounds_evidence_namespaces_and_external_completion_limit_are_not_weakened(self):
        controls = (KOTLIN / "DetektCorpusControls.kt").read_text()
        evidence = (KOTLIN / "DetektCorpusEvidence.kt").read_text()
        self.assertEqual("41a88cb600eca87200a5510883e0b52942d0b6a88baeca2ef18f7303e23eae9e", digest((KOTLIN / "DetektCorpusEvidence.kt").read_bytes()))
        for anchor in ("MAX_INVOCATIONS = 40", "MAX_AGGREGATE_MILLIS = 1_200_000L", "MAX_CHILD_MILLIS = 60_000L",
                       "schedule.getOrNull(reserved) == CorpusInvocationSpec(kind, ruleId, caseId)", "Duplicate invocation is a forbidden retry",
                       "confirmedExecutions == cohort.nativeLaunchCeiling", "observedRuntimeCallbacks == cohort.expectedRuntimeCallbacks",
                       "minOf(settings.childMillis, remainingMillis())", 'protocol -> "syntax-protocol"'):
            self.assertIn(anchor, controls)
        for anchor in ("SecureDirectoryStream", "CREATE_NEW, WRITE, NOFOLLOW_LINKS", "requireSingleLink(path)", "path == root.resolve(liveRelative)"):
            self.assertIn(anchor, evidence)
        self.assertNotIn("CURRENT", evidence)
        self.assertNotIn("TRUNCATE_EXISTING", evidence)
        self.assertEqual({"max_invocations": 40, "aggregate_millis": 1200000, "child_millis": 60000, "progress_events": 124},
                         self.contract["budgets"]["unchanged_hard_maxima"])
        self.assertIn("not independent task completion proof", self.contract["evidence_custody"]["publication"])
        self.assertFalse(self.contract["evidence_custody"]["may_touch_other_cohort_evidence_or_current_pointer"])

    def test_inert_validators_reject_wrong_ranges_duplicate_keys_and_removed_opt_in_or_scope_binding(self):
        original = self.by_id[RULES[1]]["positive"]
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
        plan = deepcopy(self.registry["cohorts"][0])
        plan["plan_identity"]["requires_explicit_owner_opt_in"] = False
        with self.assertRaisesRegex(ValueError, "mixed opt-in"):
            validate_identity(plan)
        plan = deepcopy(self.registry["cohorts"][0])
        plan["plan_identity"]["scope_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "pinned plan"):
            validate_identity(plan)

    def test_kotlin_controls_are_authored_not_executed_and_keep_old_suite_rosters(self):
        source = (KOTLIN / "DetektCorpusProtocolTest.kt").read_text()
        self.assertEqual(13, source.count("@Test fun"))
        for anchor in ("protocol budget refuses fabricated independent control IDs", '"control-6"',
                       '"configured-throwable-only-keeps-one-active-finding"', "assertEquals(14, observations)",
                       "typed control identifies the second declaration only", "Pinned authored corpus bytes changed",
                       'read("$root/scope.json")', "DetektCorpusInventory.filePins"):
            self.assertIn(anchor, source)
        for file, count in (("DetektCorpusControlsTest.kt", 16), ("DetektCorpusEvidenceTest.kt", 12), ("DetektCorpusInventoryTest.kt", 13)):
            self.assertEqual(count, (KOTLIN / file).read_text().count("@Test fun"))
        for file in ("DetektCorpusProtocolTest.kt", "DetektCorpusControlsTest.kt", "DetektCorpusEvidenceTest.kt", "DetektCorpusInventoryTest.kt"):
            body = (KOTLIN / file).read_text()
            self.assertNotIn(".analyze(", body)
            self.assertNotIn("ProcessBuilder", body)
        self.assertEqual(1, (KOTLIN / "DetektSyntaxCorpusIntegrationTest.kt").read_text().count("@Test fun"))


if __name__ == "__main__":
    unittest.main()
