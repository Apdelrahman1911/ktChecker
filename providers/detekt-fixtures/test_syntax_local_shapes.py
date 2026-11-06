#!/usr/bin/env python3
"""Source/data controls only. No detector, compiler, provider, native process or model invocation."""
from collections import Counter
from copy import deepcopy
import json
from pathlib import Path
import re
import unittest

from test_syntax_expansion import canonical, digest, expected_schedule, matrix_case, read, validate_case
from test_syntax_protocol import compiled_pins, GAPS
from test_syntax_observability import PREVIOUS_IDENTITIES as PRE_OBSERVABILITY, IDENTITY as OBSERVABILITY_IDENTITY

ROOT = Path(__file__).resolve().parents[2]
RELATIVE = "providers/detekt-fixtures/corpus/syntax-local-shapes-v1"
LOCAL = ROOT / RELATIVE
KOTLIN = ROOT / "modules/provider-detekt/src/corpusTest/kotlin/dev/ktchecker/providers/detekt"
PREFIX = "ktc.kotlin.detekt.style."
COHORTS = {
    "local-declaration-use-v1": {PREFIX + "unused-parameter": 10, PREFIX + "unused-private-class": 9,
                                 PREFIX + "protected-member-in-final-class": 10},
    "guarded-expression-policy-v1": {PREFIX + "collapsible-if-statements": 9, PREFIX + "safe-cast": 10,
                                     PREFIX + "use-if-instead-of-when": 10},
    "constant-and-body-policy-v1": {PREFIX + "function-only-returning-constant": 10,
                                   PREFIX + "expression-body-syntax": 10, PREFIX + "may-be-constant": 10},
}
PREVIOUS_IDENTITIES = dict(PRE_OBSERVABILITY, **{"exception-observability-policy-v1": OBSERVABILITY_IDENTITY})
# Literal pins are authored before execution and separately reviewed; they are not observed diagnostic oracles.
IDENTITIES = {'local-declaration-use-v1': '6d25a25bf350e4a4ed97e0514bc08b9e2fa0feb6f27420cf29ac42f847e0b8c2', 'guarded-expression-policy-v1': '9785b432ee33540b262e4ae1f94e38c80cae2e14a909ade85303f6ad30c3f38c', 'constant-and-body-policy-v1': '3b2dd4bc06d68d4f67ffed571aa07df91abbba5e7b19976f6aeb613d903bcc4b'}
SOURCE_PINS = {'UnusedParameter': 'f09347da0c460d7cdf5255f3d5a83ca2e70f3f7aaa50f9a7ce42f8cecd420dcb', 'UnusedPrivateClass': 'd113ae6c9a7b84337e803f94cdf5e6d542636b9417ba8daef437669743ee05ab', 'ProtectedMemberInFinalClass': 'fbfd3dbb38c1a1ed1476c51bd849f4e6830fff304a0aa336ea2c9be5dfc6bd08', 'CollapsibleIfStatements': '3383d8be6f4f489598e409ab8ead17a2582e124e84686cac0390fa52ecc98f44', 'SafeCast': '49dafa7fff1b3e37f277713ea46b2c11a1b66754c41df2bdcd8049f649502263', 'UseIfInsteadOfWhen': '3ac327d62aa6d0daf0b9152376c6e5017c8a60fa67b12c3224830bd8adb11eca', 'FunctionOnlyReturningConstant': 'ca8d485460ab98ed6227dbf23b3e8092707e8e1c77b4726a5724da33ee84e71f', 'ExpressionBodySyntax': 'fd4c8a68eaa98bc580e164fc0d1ccf7b73aafc63381d25a0547a35ba3b3f5aa9', 'MayBeConstant': 'ae0b166452da891d1d583bcd528652027b0acafe58bb80690783a9170506dae0'}
WRAPPER_PINS = {'UnusedParameter': 'sha256:740af64cf296b817d3779e99fa8f541c6713428d333cc9a642f209a73a705e23', 'UnusedPrivateClass': 'sha256:1b8dbf007dbbad2d8260edb32b0ae4089d1567d2f9b15768a8835a24a9e7dabc', 'ProtectedMemberInFinalClass': 'sha256:b28a4a646846eab67b16e124bd991b5e9c04ed3a557b5310ba3e04393f029152', 'CollapsibleIfStatements': 'sha256:570d47aa5202a99972b7ea681fbafc10594737629ce79fc0cf559ccfff4ae50b', 'SafeCast': 'sha256:0f6b21b78115c607aeb6794ba7d6e7a84f6d47115c6aabb8e2eb5b713d83d38f', 'UseIfInsteadOfWhen': 'sha256:22dfef90aa92a2690f8885ea2786de72bbda2583d383b10ade44040222c5a857', 'FunctionOnlyReturningConstant': 'sha256:ee74068256ed0e93b33a6ed4aa48f253c90b3be6b4dbd06dea3a43a29ff21875', 'ExpressionBodySyntax': 'sha256:aed6dc0d28313fa2c43e8c630219ab335036a8beeeba9ce4369403fcf38dc6c4', 'MayBeConstant': 'sha256:10af708fa83bfc82168bc4b24a2fe18ba2a5821f81628e74713aba1ad75e84a5'}
UNCHANGED_BUDGET_SUFFIX = "967bb3b70c8332b9394933fc4acdb5541204952655115f201989fee2c9c182aa"
UNCHANGED_EVIDENCE_SOURCE = "41a88cb600eca87200a5510883e0b52942d0b6a88baeca2ef18f7303e23eae9e"


def validate_identity(plan):
    name = plan["cohort_id"]
    if name not in IDENTITIES or digest(canonical(plan["plan_identity"])) != IDENTITIES[name] or plan["plan_identity_sha256"] != IDENTITIES[name]:
        raise ValueError("closed source-authored identity required")
    identity = plan["plan_identity"]
    policies = [r for r in COHORTS[name] if r not in (PREFIX + "unused-parameter", PREFIX + "unused-private-class")]
    if identity.get("opt_in_rule_ids") != policies or plan.get("opt_in_rule_ids") != policies or not identity.get("requires_explicit_owner_opt_in") or not plan.get("requires_explicit_owner_opt_in"):
        raise ValueError("selected policy acknowledgement mismatch")


class SyntaxLocalShapesAuthoringTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.paths = sorted((LOCAL / "fixtures").glob("*.json"))
        cls.fixtures = [read(p) for p in cls.paths]
        cls.by_id = {f["rule_id"]: f for f in cls.fixtures}
        cls.by_name = {f["upstream_id"].split(":")[1]: f for f in cls.fixtures}
        cls.registry = read(LOCAL / "cohorts.json")
        cls.scope = read(LOCAL / "scope.json")
        cls.contract = read(LOCAL / "integration-contract.json")
        cls.breadth = read(LOCAL / "breadth-inventory.json")
        cls.matrices = {name: read(LOCAL / "cohorts" / (name + ".json")) for name in COHORTS}
        cls.mapping = {r["rule_id"]: r for r in read(ROOT / "modules/provider-detekt/src/main/resources/detekt-rule-map.json")["mappings"]}

    def test_exact_nine_local_routes_keep_two_advisory_seven_opt_in_and_no_stages(self):
        self.assertEqual({r for rules in COHORTS.values() for r in rules}, self.by_id.keys())
        self.assertEqual(9, len(self.paths))
        self.assertEqual(88, sum(6 + len(f["controls"]) for f in self.fixtures))
        self.assertEqual(34, sum(len(f["controls"]) for f in self.fixtures))
        self.assertEqual(Counter(advisory=2, opt_in=7), Counter(f["default_activation"] for f in self.fixtures))
        for f in self.fixtures:
            self.assertEqual("detekt-syntax-local-shapes-v1", f["corpus_id"])
            self.assertEqual("unexecuted", f["authoring_state"])
            self.assertEqual(("detekt", "2.0.0-alpha.6", "2.4.10"), tuple(f[k] for k in ("provider_id", "provider_version", "embedded_kotlin_version")))
            m = self.mapping[f["rule_id"]]
            self.assertEqual(("exact", "syntax", "intent_dependent"), tuple(m[k] for k in ("relation", "depth", "certainty")))
            for k in ("upstream_id", "relation", "depth", "default_activation", "severity", "kind", "certainty", "catalog_record_sha256"):
                self.assertEqual(m[k], f[k])
            self.assertFalse({"passed", "tested", "qualified"} & f.keys())
            self.assertTrue(f["selection_rationale"])

    def test_primary_alpha6_source_and_class_header_pins_are_not_execution_or_binding_evidence(self):
        for name, f in self.by_name.items():
            citation = f["source_citation"]
            self.assertEqual(SOURCE_PINS[name], citation["source_file_sha256"])
            self.assertEqual("4f18e9ac263a157229db846ac18d3a71b8861d74d415da3ebd0fa085dee461a6", citation["source_archive_sha256"])
            self.assertTrue(citation["research_only"])
            self.assertEqual("dev/detekt/rules/style/" + name + ".kt", citation["source_archive_entry"])
            self.assertTrue(citation["url"].startswith("https://github.com/detekt/detekt/blob/v2.0.0-alpha.6/"))
            eligibility = f["light_eligibility"]
            self.assertEqual(WRAPPER_PINS[name], eligibility["wrapper"]["entry_sha256"])
            self.assertEqual("detekt-cli-2.0.0-alpha.6-all.jar", eligibility["wrapper"]["artifact"])
            self.assertFalse(eligibility["requires_analysis_api"])
            self.assertFalse(eligibility["qualification"])
            self.assertEqual("pinned_classfile_ancestry_only_not_execution", eligibility["evidence_kind"])

    def test_all_38_previous_pins_22_fixtures_and_eight_identities_are_retained(self):
        old = {}
        for file in ("DetektCorpusExpansionPins.kt", "DetektCorpusProtocolPins.kt", "DetektCorpusObservabilityPins.kt"):
            pins, _, _ = compiled_pins(file)
            self.assertFalse(old.keys() & pins.keys())
            old.update(pins)
        self.assertEqual(38, len(old))
        for path, expected in old.items():
            self.assertEqual(expected, digest((ROOT / path).read_bytes()))
        prior = self.scope["preserved_baseline"]
        self.assertEqual((22, 199, 0), tuple(prior[k] for k in ("rule_count", "authored_analyses", "execution_stage_transfer")))
        self.assertEqual(22, len(prior["fixture_files"]))
        self.assertEqual(199, sum(f["analyses"] for f in prior["fixture_files"]))
        self.assertEqual(PREVIOUS_IDENTITIES, {p["cohort_id"]: p["plan_identity_sha256"] for p in prior["cohorts"]})
        self.assertTrue(all(not p["modified_by_this_packet"] for p in prior["cohorts"]))
        for key, count in (("preserved_expansion_namespace_files", 12), ("preserved_protocol_namespace_files", 8), ("preserved_observability_namespace_files", 8)):
            self.assertEqual(count, len(prior[key]))
            for row in prior[key]:
                data = (ROOT / row["path"]).read_bytes()
                self.assertEqual((row["sha256"], row["bytes"]), (digest(data), len(data)))
        self.assertFalse({r["rule_id"] for r in prior["fixture_files"]} & self.by_id.keys())

    def test_17_additive_closed_pins_bind_all_case_controls_without_old_namespace_edits(self):
        pins, identities, controls = compiled_pins("DetektCorpusLocalShapesPins.kt")
        actual = {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in LOCAL.rglob("*") if p.is_file()}
        self.assertEqual(17, len(pins))
        self.assertEqual(actual, pins)
        self.assertEqual({rid: [c["id"] for c in f["controls"]] for rid, f in self.by_id.items()}, controls)
        self.assertEqual({p["cohort_id"]: canonical(p["plan_identity"]).decode() for p in self.registry["cohorts"]}, identities)
        self.assertEqual(digest((LOCAL / "scope.json").read_bytes()), self.registry["scope_sha256"])
        self.assertEqual(digest((LOCAL / "integration-contract.json").read_bytes()), self.registry["integration_contract_sha256"])
        self.assertEqual(digest((LOCAL / "breadth-inventory.json").read_bytes()), self.scope["breadth_inventory"]["sha256"])

    def test_three_explicit_cohorts_pin_costs_policy_subsets_and_non_admission(self):
        self.assertEqual(list(COHORTS), self.registry["allowed_cohort_names"])
        self.assertIsNone(self.registry["default_cohort"])
        self.assertFalse(self.registry["multi_cohort_or_all_selector_allowed"])
        self.assertEqual([39, 39, 40], [p["counts"]["all_calls"] for p in self.registry["cohorts"]])
        self.assertEqual([30, 30, 31], [p["counts"]["native_launch_ceiling"] for p in self.registry["cohorts"]])
        self.assertEqual([33, 33, 34], [p["counts"]["expected_runtime_callbacks"] for p in self.registry["cohorts"]])
        for p in self.registry["cohorts"]:
            name = p["cohort_id"]
            validate_identity(p)
            self.assertEqual(list(COHORTS[name]), [r["rule_id"] for r in p["selected_rules"]])
            self.assertEqual(list(COHORTS[name].values()), [r["analyses"] for r in p["selected_rules"]])
            self.assertEqual(p["opt_in_rule_ids"], self.matrices[name]["explicit_opt_in_policy_rule_ids"])
            self.assertFalse(p["execution_admitted"])
            self.assertFalse(p["plan_identity"]["previous_stages_accepted"])
            self.assertFalse(p["plan_identity"]["aggregate_context_activation"])
            self.assertEqual({"max_invocations": 40, "aggregate_millis": 1200000, "child_millis": 60000}, p["plan_identity"]["limits"])

    def test_heterogeneous_29_29_30_analysis_matrices_join_every_fixture_case_and_exact_schedule(self):
        for plan in self.registry["cohorts"]:
            name = plan["cohort_id"]
            matrix = self.matrices[name]
            self.assertEqual(sum(COHORTS[name].values()), len(matrix["analyses"]))
            self.assertEqual(expected_schedule(list(COHORTS[name]), self.by_id), matrix["invocation_schedule"])
            self.assertEqual(digest((ROOT / plan["case_matrix_path"]).read_bytes()), plan["case_matrix_sha256"])
            offset = 0
            for selection in plan["selected_rules"]:
                f = self.by_id[selection["rule_id"]]
                self.assertEqual(selection["fixture_sha256"], digest((ROOT / selection["fixture_path"]).read_bytes()))
                for index in range(selection["analyses"]):
                    row = matrix["analyses"][offset]
                    offset += 1
                    case_id, pointer, node, assessment = matrix_case(f, index)
                    disabled = case_id == "configuration"
                    self.assertEqual((f["rule_id"], case_id, pointer, assessment), tuple(row[k] for k in ("rule_id", "case_id", "fixture_json_pointer", "assessment")))
                    self.assertEqual(node.get("options", {}), row["upstream_options"])
                    self.assertEqual(digest(canonical(node["sources"])), row["source_set_sha256"])
                    self.assertEqual([] if disabled else node["expected"], row["authored_expected_ranges"])
                    self.assertEqual(len(row["authored_expected_ranges"]), row["authored_expected_count"])
                    self.assertEqual(disabled, row["rule_disabled_for_this_configuration_control"])
                    self.assertEqual(not disabled, row["require_active_upstream_descriptor"])
                    self.assertEqual(not disabled, row["selected_sarif_descriptor_enabled_required"])
                    self.assertTrue(row["require_actual_native_launch"])
                    self.assertFalse(row["attempted"])
                    self.assertIsNone(row["observed_findings"])
                    self.assertEqual("not_run", row["state"])
                self.assertEqual(6 + len(f["controls"]), selection["analyses"])
            self.assertEqual(len(matrix["analyses"]), offset)

    def test_matrix_counts_keep_lifecycle_replay_duplicates_and_zero_natural_observations_explicit(self):
        for matrix in self.matrices.values():
            rows, counts = matrix["analyses"], matrix["counts"]
            self.assertEqual(len(rows) + 10, counts["all_calls"])
            self.assertEqual(2 + 3 * counts["all_calls"], counts["planned_progress_events"])
            self.assertEqual(sum(r["authored_expected_count"] for r in rows), counts["authored_expected_finding_observations"])
            self.assertEqual(sum(r["authored_expected_count"] > 0 for r in rows), counts["authored_alert_bearing_analyses"])
            self.assertEqual(sum(r["authored_expected_count"] == 0 for r in rows), counts["authored_zero_finding_analyses"])
            self.assertEqual(len({(r["rule_id"], r["source_set_sha256"]) for r in rows}), counts["unique_rule_source_sets"])
            self.assertEqual(len({(r["rule_id"], r["source_set_sha256"], canonical(r["upstream_options"]), r["rule_disabled_for_this_configuration_control"]) for r in rows}), counts["unique_rule_source_configuration_sets"])
            self.assertEqual((0, 0, 0), tuple(counts[k] for k in ("actual_executions", "natural_project_observations", "precision_reviewed_natural_observations")))
            for rid in {r["rule_id"] for r in rows}:
                selected = {r["case_id"]: r for r in rows if r["rule_id"] == rid}
                self.assertEqual(selected["positive"]["source_set_sha256"], selected["version"]["source_set_sha256"])
                self.assertEqual(selected["positive"]["source_set_sha256"], selected["configuration"]["source_set_sha256"])
                self.assertEqual(selected["negative"]["source_set_sha256"], selected["regression"]["source_set_sha256"])

    def test_32_authored_literal_ranges_and_70_ASCII_source_entries_preserve_end_exclusive_entity_bounds(self):
        ranges = entries = 0
        for f in self.fixtures:
            for node in [f[k] for k in ("positive", "negative", "suppression")] + f["controls"]:
                validate_case(node)
                ranges += len(node["expected"])
                entries += len(node["sources"])
        self.assertEqual((32, 70), (ranges, entries))

    def test_real_file_suppression_isolated_copy_keeps_unsuppressed_neighbor(self):
        for name, f in self.by_name.items():
            source = f["positive"]["sources"]["Case.kt"]
            suppression = f["suppression"]
            self.assertEqual({"Case.kt", "SuppressedCase.kt"}, suppression["sources"].keys())
            self.assertEqual(source, suppression["sources"]["Case.kt"])
            self.assertEqual('@file:Suppress("' + name + '")\n\n' + source.replace("package ", "package suppressed.", 1), suppression["sources"]["SuppressedCase.kt"])
            self.assertEqual(f["positive"]["expected"], suppression["expected"])
            self.assertEqual([], f["negative"]["expected"])

    def test_only_real_upstream_options_are_authored_and_no_option_disables_rule(self):
        options = {name: {r["name"] for r in f["upstream_rule_options"]} for name, f in self.by_name.items()}
        self.assertEqual({"allowedNames"}, options["UnusedParameter"])
        self.assertEqual({"ignoreWhenContainingVariableDeclaration"}, options["UseIfInsteadOfWhen"])
        self.assertEqual({"ignoreOverridableFunction", "ignoreActualFunction", "excludedFunctions"}, options["FunctionOnlyReturningConstant"])
        self.assertEqual({"includeLineWrapping"}, options["ExpressionBodySyntax"])
        genuine = 0
        for name, f in self.by_name.items():
            for c in f["controls"]:
                if "options" in c:
                    self.assertLessEqual(c["options"].keys(), options[name])
                    self.assertNotIn("active", c["options"])
                    self.assertEqual("genuine_upstream_option", c["assessment"])
                    genuine += 1
        self.assertEqual(5, genuine)
        for name in ("UnusedPrivateClass", "ProtectedMemberInFinalClass", "CollapsibleIfStatements", "SafeCast", "MayBeConstant"):
            self.assertEqual(set(), options[name])

    def test_unused_parameter_named_label_regex_and_nested_binding_counterexamples(self):
        f = self.by_name["UnusedParameter"]
        c = f["controls"]
        self.assertEqual([0, 1, 1, 0], [len(n["expected"]) for n in c])
        self.assertEqual({"allowedNames": "reserved.*"}, c[1]["options"])
        self.assertEqual("stale", c[1]["expected"][0]["span"])
        self.assertEqual(("width", 2), (c[2]["expected"][0]["span"], c[2]["expected"][0]["occurrence"]))
        self.assertIn("render(width = 2)", c[2]["sources"]["Case.kt"])
        self.assertIn("fun label(width: Int)", c[3]["sources"]["Case.kt"])
        self.assertEqual("anticipated_upstream_false_negative", c[3]["assessment"])

    def test_private_classes_do_not_turn_simple_names_or_reflection_text_into_binding_proof(self):
        c = self.by_name["UnusedPrivateClass"]["controls"]
        self.assertEqual([0, 0, 1], [len(n["expected"]) for n in c])
        self.assertIn("value: Pending", c[0]["sources"]["Case.kt"])
        self.assertIn("class LocalIndex(val name: String)", c[1]["sources"]["Case.kt"])
        self.assertIn('Class.forName("corpus.privateclass.ReflectiveAdapter")', c[2]["sources"]["Case.kt"])
        self.assertEqual("private class ReflectiveAdapter", c[2]["expected"][0]["span"])
        self.assertEqual("anticipated_upstream_false_positive", c[2]["assessment"])

    def test_protected_members_preserve_open_override_sealed_and_factory_intent(self):
        c = self.by_name["ProtectedMemberInFinalClass"]["controls"]
        self.assertEqual([0, 0, 0, 1], [len(n["expected"]) for n in c])
        for index, anchor in enumerate(("open class ExtensibleSlot", "protected override fun suffix", "sealed class ClosedSlots")):
            self.assertIn(anchor, c[index]["sources"]["Case.kt"])
        self.assertEqual("protected constructor(val value: String)", c[3]["expected"][0]["span"])
        self.assertEqual("opt_in_policy_limit", c[3]["assessment"])

    def test_collapse_policy_preserves_outer_inner_else_and_exact_brace_boundary(self):
        f = self.by_name["CollapsibleIfStatements"]
        self.assertIn("ready && permitted", f["negative"]["sources"]["Case.kt"])
        self.assertTrue(all(not c["expected"] for c in f["controls"]))
        self.assertIn("waitForReady()", f["controls"][0]["sources"]["Case.kt"])
        self.assertIn("send() else deny()", f["controls"][1]["sources"]["Case.kt"])
        self.assertIn("if (ready) if (permitted) send()", f["controls"][2]["sources"]["Case.kt"])
        self.assertEqual("anticipated_upstream_false_negative", f["controls"][2]["assessment"])

    def test_safe_cast_does_not_certify_unstable_getter_rewrite_or_qualified_receiver(self):
        c = self.by_name["SafeCast"]["controls"]
        self.assertEqual([1, 0, 0, 1], [len(n["expected"]) for n in c])
        self.assertIn("!is String", c[0]["sources"]["Case.kt"])
        self.assertIn("record()\n    value", c[1]["sources"]["Case.kt"])
        self.assertIn("holder.item is String", c[2]["sources"]["Case.kt"])
        self.assertIn("val observed: Any get() = source()", c[3]["sources"]["Case.kt"])
        self.assertEqual("if (observed is String) observed else null", c[3]["expected"][0]["span"])
        self.assertEqual("anticipated_upstream_false_positive", c[3]["assessment"])

    def test_binary_when_subject_binding_option_is_real_and_single_evaluation_remains_explicit(self):
        c = self.by_name["UseIfInsteadOfWhen"]["controls"]
        self.assertEqual([0, 0, 1, 0], [len(n["expected"]) for n in c])
        self.assertIn("0, 1 ->", c[0]["sources"]["Case.kt"])
        self.assertNotIn("else", c[1]["sources"]["Case.kt"])
        self.assertEqual(c[2]["sources"], c[3]["sources"])
        self.assertEqual({"ignoreWhenContainingVariableDeclaration": True}, c[3]["options"])
        self.assertIn("when (val code = read())", c[2]["sources"]["Case.kt"])

    def test_constant_return_policy_retains_override_null_and_name_glob_limits(self):
        c = self.by_name["FunctionOnlyReturningConstant"]["controls"]
        self.assertEqual([0, 1, 1, 1], [len(n["expected"]) for n in c])
        self.assertEqual(c[0]["sources"], c[1]["sources"])
        self.assertEqual({"ignoreOverridableFunction": False}, c[1]["options"])
        self.assertIn("String? = null", c[2]["sources"]["Case.kt"])
        self.assertEqual("anticipated_upstream_false_positive", c[2]["assessment"])
        self.assertEqual("manualRetries", c[3]["expected"][0]["span"])
        self.assertEqual({"excludedFunctions": ["generated*"]}, c[3]["options"])

    def test_expression_body_option_and_elvis_return_and_accessor_entities_are_distinct(self):
        c = self.by_name["ExpressionBodySyntax"]["controls"]
        self.assertEqual([0, 1, 0, 1], [len(n["expected"]) for n in c])
        self.assertEqual(c[0]["sources"], c[1]["sources"])
        self.assertEqual({"includeLineWrapping": True}, c[1]["options"])
        self.assertEqual("return prefix +\n        key", c[1]["expected"][0]["span"])
        self.assertIn("value ?: return 0", c[2]["sources"]["Case.kt"])
        self.assertEqual("return key", c[3]["expected"][0]["span"])

    def test_maybe_constant_does_not_claim_nullable_type_local_scope_or_getter_eligibility(self):
        c = self.by_name["MayBeConstant"]["controls"]
        self.assertEqual([1, 0, 1, 0], [len(n["expected"]) for n in c])
        self.assertIn("optionalLimit: Int? = 3", c[0]["sources"]["Case.kt"])
        self.assertEqual("anticipated_upstream_false_positive", c[0]["assessment"])
        self.assertIn("val localLimit = 3", c[1]["sources"]["Case.kt"])
        self.assertEqual("totalLimit", c[2]["expected"][0]["span"])
        self.assertIn("get() = 3", c[3]["sources"]["Case.kt"])

    def test_all_125_deferred_syntax_routes_and_120_other_rows_stay_in_scope_with_explicit_gaps(self):
        b = self.breadth
        self.assertEqual((276, 156, 22, 9, 125, 120), tuple(b["counts"][k] for k in ("mapping_rows", "exact_syntax_rows", "preserved_authored_rules", "new_source_authored_rules", "deferred_exact_syntax_rules", "outside_exact_light_scope")))
        syntax = b["exact_syntax_rules"]
        self.assertEqual(sorted(r for r, m in self.mapping.items() if m["relation"] == "exact" and m["depth"] == "syntax"), [r["rule_id"] for r in syntax])
        other = b["other_mapping_rows"]
        self.assertEqual(set(self.mapping), {r["rule_id"] for r in syntax + other})
        self.assertEqual(276, len(syntax + other))
        for row in syntax + other:
            self.assertFalse(row["scope_exclusion"])
            self.assertEqual(0, row["execution_or_stage_credit"])
            for key in ("upstream_id", "relation", "depth", "default_activation", "catalog_record_sha256"):
                self.assertEqual(self.mapping[row["rule_id"]][key], row[key])
        deferred = [r for r in syntax if r["disposition"] == "deferred_no_new_execution"]
        self.assertEqual(125, len(deferred))
        self.assertEqual(Counter(r["reason_category"] for r in deferred), b["deferred_category_counts"])
        self.assertTrue(all(r["rationale"] and r["primary_predicate_review"] == "existing_frozen_mapping_and_prior_corpus_inventory_not_a_new_exhaustive_rule_review" for r in deferred))
        self.assertFalse(b["qualification_claimed"])
        self.assertEqual((1663, 8, 13304), tuple(b["full_scope"][k] for k in ("ids", "contexts", "cells")))

    def test_named_semantic_graph_and_safe_rewrite_gaps_remain_not_delivered(self):
        gaps = self.scope["named_coroutine_and_resource_gaps"]
        self.assertEqual(list(GAPS), [r["rule_id"] for r in gaps])
        for row in gaps:
            self.assertNotIn(row["rule_id"], self.by_id)
            self.assertEqual("unavailable_in_this_light_context", row["state"])
            for flag in ("attempted", "implemented", "tested", "qualified", "delivered_by_this_cohort"):
                self.assertFalse(row[flag])
            self.assertTrue(row["no_stage_transfer_from_local_shapes_or_other_syntax_rules"])
        self.assertTrue(self.contract["no_safe_rewrite_or_binding_proof"])
        self.assertFalse(self.contract["execution_admitted"])
        self.assertFalse(self.contract["input_contract"]["genuine_imported_model_execution"])
        self.assertFalse(self.contract["input_contract"]["parser_compatibility_qualified"])
        self.assertEqual("light", self.contract["input_contract"]["analysis_mode"])

    def test_complete_11_selector_extends_infrastructure_without_aggregate_entry_or_automatic_task(self):
        source = (KOTLIN / "DetektCorpusControls.kt").read_text()
        build = (ROOT / "modules/provider-detekt/build.gradle.kts").read_text()
        gate = re.search(r"val detektCorpusCohortNames = setOf\((.*?)\)", build, re.DOTALL)
        self.assertIsNotNone(gate)
        self.assertEqual(list(PREVIOUS_IDENTITIES) + list(COHORTS), re.findall(r'"([^"]+)"', gate.group(1)))
        added = re.findall(r'val [A-Z_]+ = localShapes\("([^"]+)", linkedMapOf\((.*?)\)\)', source, re.DOTALL)
        self.assertEqual(list(COHORTS), [name for name, _ in added])
        for name, body in added:
            self.assertEqual(list(COHORTS[name].items()), [(r, int(n)) for r, n in re.findall(r'"(ktc\.[^"]+)" to (\d+)', body)])
        for anchor in ("PRE_LOCAL_SHAPES_AUTHORED_RULES = 22", "PRE_LOCAL_SHAPES_AUTHORED_ANALYSES = 199", "FULL_AUTHORED_RULES = 31", "FULL_AUTHORED_ANALYSES = 287", "Collections.unmodifiableList(preLocalShapes + localShapeCohorts)", "all.singleOrNull { it.name == name }"):
            self.assertIn(anchor, source)
        self.assertNotIn('tasks.named("check")', build)
        self.assertNotIn('tasks.named("test").dependsOn', build)
        self.assertIn('outputs.doNotCacheIf("Explicit native corpus runs require fresh actual process outcomes")', build)

    def test_loader_pins_before_parse_and_driver_witnesses_new_source_code_and_data(self):
        inventory = (KOTLIN / "DetektCorpusInventory.kt").read_text()
        driver = (KOTLIN / "DetektSyntaxCorpusIntegrationTest.kt").read_text()
        for anchor in ("DetektCorpusLocalShapesPins.files", "it.size == 55", "files.size == 17", "validateBreadthInventory()", "validateNamedGaps(LOCAL_SHAPES)", "DetektCorpusCohort.preLocalShapes", "val analysisCount = cohort.analysesByRule.values.sum()", "repeat(ruleAnalysisCount)", "analysisOffset += ruleAnalysisCount", "hash(bytes) == filePins.getValue(relative)", "bytes.contentEquals(readPinned(repository, path))"):
            self.assertIn(anchor, inventory)
        for anchor in ("DetektCorpusInventory.LOCAL_SHAPES", "DetektCorpusLocalShapesPins::class.java", '"providers/detekt-fixtures/test_syntax_local_shapes.py"', "path.startsWith(localShapeFixtures)", "inventory.verify()", "assertEquals(15, approved.count", "assertEquals(16, approved.count", "DetektCorpusCohort.PRE_LOCAL_SHAPES_AUTHORED_ANALYSES", 'put("provider_status", "not_run")', "assertEquals(!case.disabled, selectedSarifId in parsedReport.enabledSarifIds"):
            self.assertIn(anchor, driver)
        self.assertEqual(3, len(re.findall(r"\.analyze\([^\n]+limits\.cancellation\)", driver)))

    def test_budget_and_evidence_implementation_bytes_are_reused_not_bypassed(self):
        source = (KOTLIN / "DetektCorpusControls.kt").read_text()
        suffix = source[source.index("internal data class CorpusInvocationSpec"):]
        self.assertEqual(UNCHANGED_BUDGET_SUFFIX, digest(suffix.encode()))
        self.assertEqual(UNCHANGED_EVIDENCE_SOURCE, digest((KOTLIN / "DetektCorpusEvidence.kt").read_bytes()))
        for anchor in ("schedule.getOrNull(reserved) == CorpusInvocationSpec(kind, ruleId, caseId)", "minOf(settings.childMillis, remainingMillis())", "actual.supervisorIdentity == expected", "Signal.UNVERIFIED", "terminal_or_no_execution_unverified"):
            self.assertIn(anchor, source)

    def test_mutated_plan_even_with_rehashed_identity_cannot_admit_changes(self):
        original = self.registry["cohorts"][0]
        for key, value in (("aggregate_context_activation", True), ("previous_stages_accepted", True), ("opt_in_rule_ids", []), ("scope_sha256", "0" * 64)):
            changed = deepcopy(original)
            changed["plan_identity"][key] = value
            changed["plan_identity_sha256"] = digest(canonical(changed["plan_identity"]))
            with self.assertRaises(ValueError):
                validate_identity(changed)

    def test_independent_range_and_path_validator_rejects_wrong_oracle_and_unsafe_sources(self):
        original = self.by_name["SafeCast"]["positive"]
        wrong = deepcopy(original)
        wrong["expected"][0]["end_column"] += 1
        with self.assertRaises(ValueError):
            validate_case(wrong)
        wrong = deepcopy(original)
        wrong["sources"]["../Case.kt"] = wrong["sources"].pop("Case.kt")
        with self.assertRaises(ValueError):
            validate_case(wrong)


if __name__ == "__main__":
    unittest.main()
