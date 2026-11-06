"""Stdlib SOURCE contracts only; never imports candidate modules or executes compiler/native tools."""
import hashlib
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = "modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/"
PLAN = "providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-source-v1.json"
BINDING = "providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-execution-v1.json"
PLAN_SHA256 = "ac16e9b94c9bfe50b6d1c1799c7c216ed907219d272e88383192dc3a7f0bea5a"
LEGACY_SOURCE_PINS = {'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureRunner.kt': '968429a1a48a5cb04c5a12aec55f10f48ff9a1d4b6655408748cd005f8e68770', 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureControls.kt': 'bde583248f23979fee1c78d78b77f976dad9a302bb2c8f3e33185e7067ff8be0', 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureProvenance.kt': '9b3602227e4275e514aa9cae2335de4d471cc657a783fb1a890a9569f236d72d', 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureMappingContract.kt': 'db7d522a88ce7e5e557376623d7304c6e68ea0055ed6c70cc0afadb8944f58be', 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerMappingContractTest.kt': 'a94347c5f5c6fed165447b915a343654a151249ebabf789ab5b2960af6d42e50', 'providers/compiler-fixtures/compiler_mapping_contracts.py': '7335825a32cfe5fe2a813eaf1054990106cecef71e344f6d10eb66726beab712', 'providers/compiler-fixtures/test_compiler_mapping_contracts.py': '8bd0ae27c876c4235d14dcf3d58fec02b34fe9dc26760023ccf1de7e38ac8da1'}
# Explicit SOURCE-control successors; the independent preimage pins above remain frozen.
SOURCE_GUARD_PREIMAGE_ROOT = 'providers/compiler-fixtures/mapping-contracts/authored-inventory-guard-preimages-v1/'
SOURCE_GUARD_SUCCESSOR_PINS = {'providers/compiler-fixtures/compiler_mapping_contracts.py': '9bc13e73d61dacac37278a26b53a609905dfad2331709f8555776d4124cca1cf', 'providers/compiler-fixtures/test_compiler_mapping_contracts.py': 'db6a4335b24b4ccfd3effeead0b08a8dd2d718081e82600ca7c03ab0e80a4bd1'}
BUILD_PREFIX_BYTES = 19997
BUILD_PREFIX_SHA256 = 'bd8b2c76fb54563b89ddb7d85f7173c1fc7017677219f625ae48c87d8fe77ddd'

RULE_IDS = ["ktc.compiler.common.error_suppression", "ktc.compiler.common.inapplicable_operator_modifier"]
CASE_IDS = [
    "error_suppression_positive_valid_body", "error_suppression_negative_warning_name",
    "error_suppression_self_suppression_resists", "error_suppression_resolved_annotation_negative",
    "error_suppression_option_off_same_positive_bytes", "inapplicable_operator_positive_no_loop",
    "inapplicable_operator_negative_boolean", "inapplicable_operator_negative_no_operator",
    "inapplicable_operator_suppression_unknown",
]


def source(path):
    data = (ROOT / path).read_bytes()
    if len(data) > 8_388_608:
        raise AssertionError("SOURCE file bound exceeded")
    return data


class Current445RouteSourceTest(unittest.TestCase):
    def test_source_v1_stays_unbound_unrun_and_is_not_execution_authority(self):
        data = source(PLAN)
        self.assertEqual(PLAN_SHA256, hashlib.sha256(data).hexdigest())
        plan = json.loads(data)
        self.assertEqual("private_candidate_unadmitted_unrun", plan["status"])
        self.assertIsNone(plan["mapping_contract_requirement"]["expected_resource_sha256"])
        self.assertFalse(plan["ordered_cases_are_execution_authority"])
        self.assertFalse(plan["ordinary_six_kind_assessment"])
        self.assertEqual("none", plan["qualification"])

    def test_bound_pin_roster_names_exact_new_route_source_and_current_inputs(self):
        binding = json.loads(source(BINDING))
        self.assertEqual("diagnostic-gaps-current445-v1", binding["execution_binding_id"])
        self.assertEqual(CASE_IDS, binding["ordered_case_ids"])
        self.assertEqual(RULE_IDS, binding["selected_rule_ids"])
        pins = binding["pins"]
        self.assertEqual(24, len(pins))
        self.assertEqual(24, len({p["path"] for p in pins}))
        for pin in pins:
            self.assertNotIn("..", Path(pin["path"]).parts)
            data = source(pin["path"])
            self.assertEqual(pin["bytes"], len(data), pin["path"])
            self.assertEqual(pin["sha256"], hashlib.sha256(data).hexdigest(), pin["path"])
        self.assertIn(KOTLIN + "CompilerCurrent445FixtureRunner.kt", {p["path"] for p in pins})
        self.assertIn("modules/provider-compiler/build.gradle.kts", {p["path"] for p in pins})

    def test_all_nine_cases_keep_exact_source_bytes_and_utf16_regions(self):
        cases = json.loads(source(PLAN))["cases"]
        self.assertEqual(CASE_IDS, [c["case_id"] for c in cases])
        self.assertEqual(8, len({c["path"] for c in cases}))
        for c in cases:
            data = source(c["path"])
            self.assertEqual(c["source_bytes"], len(data))
            self.assertEqual(c["source_sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(RULE_IDS, c["selected_rule_ids"])
            lines = data.decode("utf-8").split("\n")
            for expectation in c["source_derived_expectations"]:
                self.assertEqual(expectation["expected_full_parser_count"], expectation["expected_selected_finding_count"])
                for region in expectation["point_regions"]:
                    line = lines[region["line"] - 1].removesuffix("\r").encode("utf-16-le")
                    token = line[region["start_column"] * 2:region["end_column_exclusive"] * 2].decode("utf-16-le")
                    self.assertEqual(region["source_token"], token)

    def test_unknown_suppression_and_option_off_are_not_hidden_successes(self):
        cases = json.loads(source(PLAN))["cases"]
        self.assertEqual(cases[0]["path"], cases[4]["path"])
        self.assertEqual(cases[0]["source_sha256"], cases[4]["source_sha256"])
        self.assertEqual("-Xwarning-level=ERROR_SUPPRESSION:disabled", cases[4]["compiler_flags"][-1])
        self.assertEqual("nonzero", cases[5]["compiler_exit_expectation"])
        self.assertEqual("failed", cases[5]["provider_status_expectation"])
        self.assertTrue(cases[-1]["hard_error_suppression_observation_only"])
        self.assertIsNone(cases[-1]["source_derived_expectations"][-1]["expected_full_parser_count"])
        self.assertEqual("unknown", cases[-1]["compiler_exit_expectation"])
        self.assertTrue(all(c["expected_outcome_matched"] is None for c in cases))

    def test_legacy_source_and_old_build_blocks_are_byte_identical(self):
        self.assertEqual({"providers/compiler-fixtures/compiler_mapping_contracts.py",
                          "providers/compiler-fixtures/test_compiler_mapping_contracts.py"},
                         set(SOURCE_GUARD_SUCCESSOR_PINS))
        for path, digest in LEGACY_SOURCE_PINS.items():
            data = source(path)
            if path in SOURCE_GUARD_SUCCESSOR_PINS:
                self.assertEqual(SOURCE_GUARD_SUCCESSOR_PINS[path], hashlib.sha256(data).hexdigest(), path)
                self.assertNotEqual(digest, hashlib.sha256(data).hexdigest(), path)
                # Retained SOURCE preimages are DATA, never imported or accepted as current.
                data = source(SOURCE_GUARD_PREIMAGE_ROOT + Path(path).name + ".txt")
            self.assertEqual(digest, hashlib.sha256(data).hexdigest(), path)
        build = source("modules/provider-compiler/build.gradle.kts")
        self.assertEqual(BUILD_PREFIX_SHA256, hashlib.sha256(build[:BUILD_PREFIX_BYTES]).hexdigest())
        old_controls = source(KOTLIN + "CompilerFixtureControls.kt")
        self.assertNotIn(b"diagnostic-gaps-current445-v1", old_controls)

    def test_scope_preserves44540243_and1665_catalogs46_exclusions(self):
        mapping = json.loads(source("modules/provider-compiler/src/main/resources/compiler-rule-map.json"))
        self.assertEqual("kotlin-compiler-2.4.21-common445-u2-v1", mapping["mapping_contract"])
        rows = mapping["mappings"]
        self.assertEqual(445, len(rows))
        self.assertEqual(402, sum(r["backend"] in ("common", "jvm", "ir") for r in rows))
        self.assertEqual(43, sum(r["backend"] == "native" for r in rows))
        selected = {r["rule_id"]: r for r in rows if r["rule_id"] in RULE_IDS}
        self.assertEqual(2, len(selected))
        self.assertEqual("common", selected[RULE_IDS[0]]["backend"])
        self.assertEqual("intent_dependent", selected[RULE_IDS[0]]["certainty"])
        self.assertNotIn("INAPPLICABLE_OPERATOR_MODIFIER_WARNING", {r["upstream_id"] for r in rows})
        catalogs = []
        for name, count in (("core", 799), ("ecosystem", 25), ("libraries", 125), ("platform", 716)):
            rows = json.loads(source("data/rules-" + name + ".json"))
            self.assertEqual(count, len(rows))
            catalogs.extend(rows)
        self.assertEqual(1665, len(catalogs))
        self.assertEqual(1665, len({r["id"] for r in catalogs}))
        self.assertTrue(all(r["status"] == "planned" for r in catalogs))
        self.assertEqual(46, sum(r["default_activation"] == "excluded" for r in catalogs))
        self.assertEqual(98, len(json.loads(source("providers/compiler-fixtures/manifest.json"))["fixtures"]))
        self.assertEqual(16, len(json.loads(source("providers/compiler-fixtures/planned-kmp-manifest.json"))["fixtures"]))

    def test_opt_in_task_excludes_native_integration_from_ordinary_test(self):
        build = source("modules/provider-compiler/build.gradle.kts")[BUILD_PREFIX_BYTES:].decode()
        self.assertIn('tasks.register<Test>("compilerCurrent445Fixtures")', build)
        self.assertIn('exclude("**/CompilerCurrent445FixtureIntegrationTest.class")', build)
        self.assertIn('excludeTags("compiler-current445-native-integration")', build)
        self.assertIn('includeTags("compiler-current445-native-integration")', build)
        self.assertIn('compilerCurrent445Consent', build)
        self.assertIn('compilerCurrent445BindingSha256', build)
        self.assertIn('outputs.upToDateWhen { false }', build)
        self.assertIn('outputs.cacheIf { false }', build)
        self.assertNotIn('dependsOn("compilerCurrent445Fixtures")', build)
        self.assertNotIn('dependsOn(tasks.named("compilerCurrent445Fixtures"))', build)

    def test_authored_kotlin_tests_are_contracts_plus_one_explicit_observation_method(self):
        unit = source(KOTLIN + "CompilerCurrent445FixtureContractTest.kt").decode()
        integration = source(KOTLIN + "CompilerCurrent445FixtureIntegrationTest.kt").decode()
        self.assertEqual(20, len(re.findall(r"@Test\s+fun\s+", unit)))
        self.assertEqual(1, len(re.findall(r"@Test\s+fun\s+", integration)))
        self.assertIn('@Tag("compiler-current445-native-integration")', integration)
        self.assertIn('sourcePlanRawByteRetentionCapabilityResolved', integration)
        self.assertIn('assertFalse(receipt.runComplete)', integration)
        self.assertIn('assertEquals(JsonNull, cases.last()', integration)

    def test_string_capture_limit_and_no_rule_promotion_remain_explicit(self):
        oracle = source(KOTLIN + "CompilerCurrent445FixtureOracle.kt").decode()
        runner = source(KOTLIN + "CompilerCurrent445FixtureRunner.kt").decode()
        self.assertIn('"source_plan_raw_byte_retention_capability_resolved" to false', oracle)
        self.assertIn('"invalid_utf8_raw_bytes_available" to false', oracle)
        self.assertIn('if (c.observationOnly) null else knownMatched', oracle)
        self.assertIn('".runtime-string.utf8"', runner)
        self.assertIn('"run_complete" to false', runner)
        self.assertIn('"counts_as_current_test_or_implementation_evidence" to false', runner)
        self.assertNotIn('CompilerFixtureSettings(', runner)
        self.assertNotIn('CompilerFixtureRunner.main', runner)
        self.assertNotIn('CompilerQualification(', runner)


if __name__ == "__main__":
    unittest.main()
