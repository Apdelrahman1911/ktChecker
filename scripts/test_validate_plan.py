"""Adversarial tests for the planning validator, not for ktChecker diagnostics."""

import copy
import json
from pathlib import Path
import tempfile
import unittest

from validate_plan import (
    check_catalog_baseline, check_json_examples, check_markdown_links, read_registry,
    render_catalog, validate_rule,
)


def example_rule():
    return {
        "id": "ktc.example.owned_resource", "category": "resources",
        "problem": "A resource with local, unambiguous ownership is not closed.",
        "rationale": "A leaked descriptor can exhaust the process resource limit.",
        "sources": ["https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io/use.html"],
        "applicability": "Resolved Closeable; local ownership proved; JVM only.",
        "platforms": ["android", "desktop"], "version_scope": "Planned fixture tuple only.",
        "implementation": "adapted", "provider": "example-provider",
        "depth": "semantic", "cost": "medium", "severity": "warning",
        "kind": "probable_defect", "certainty": "medium",
        "limitations": "Escaping resources require ownership analysis; abstain if unknown.",
        "validation": {
            "positive": "Opened local resource discarded without closing.",
            "negative": "use {} closes the owned resource.",
            "boundary": "Ownership returned to caller is not a leak.",
            "platform_version": "Exclude unresolved Native Closeable lookalikes.",
            "suppression": "Scoped suppression only hides the named rule.",
            "regression": "Custom factory with transferred ownership must not trigger.",
        },
        "status": "planned", "default_activation": "enabled_when_validated",
        "disposition_reason": "Proposed default only after ownership and integration validation.",
    }


class RegistryValidationTests(unittest.TestCase):
    def test_valid_planned_record_is_not_runtime_enablement(self):
        self.assertEqual([], validate_rule(example_rule()))

    def test_required_fields(self):
        rule = example_rule()
        del rule["limitations"]
        self.assertTrue(any("missing limitations" in e for e in validate_rule(rule)))

    def test_each_validation_case_is_required(self):
        for field in example_rule()["validation"]:
            with self.subTest(field=field):
                rule = example_rule()
                rule["validation"][field] = " "
                self.assertTrue(any(field in e for e in validate_rule(rule)))

    def test_unsupported_platform_cannot_be_claimed(self):
        rule = example_rule()
        rule["platforms"] = ["all"]
        self.assertTrue(any("platform" in e for e in validate_rule(rule)))

    def test_platform_duplicates_rejected(self):
        rule = example_rule()
        rule["platforms"] = ["ios", "ios"]
        self.assertTrue(any("duplicate" in e for e in validate_rule(rule)))

    def test_kind_and_certainty_are_separate(self):
        rule = example_rule()
        rule["certainty"] = "policy"
        self.assertTrue(any("certainty" in e for e in validate_rule(rule)))

    def test_structured_enum_does_not_crash(self):
        rule = example_rule()
        rule["cost"] = {"pretend": "fast"}
        self.assertTrue(validate_rule(rule))

    def test_structured_status_does_not_crash(self):
        for invalid in [{"pretend": "validated"}, ["tested"], None]:
            with self.subTest(status=invalid):
                rule = example_rule()
                rule["status"] = invalid
                self.assertTrue(any("status" in e for e in validate_rule(rule)))

    def test_malformed_url_is_reported(self):
        rule = example_rule()
        rule["sources"] = ["https://[malformed-address"]
        self.assertTrue(any("invalid source" in e for e in validate_rule(rule)))

    def test_invalid_id(self):
        rule = example_rule()
        rule["id"] = "../../secret"
        self.assertTrue(any("identifier" in e for e in validate_rule(rule)))

    def test_credentialed_source_rejected(self):
        rule = example_rule()
        rule["sources"] = ["https://token:secret@example.org/spec"]
        self.assertTrue(any("credentials" in e for e in validate_rule(rule)))

    def test_unsupported_status_rejected(self):
        rule = example_rule()
        rule["status"] = "apparently_tested"
        self.assertTrue(any("status" in e for e in validate_rule(rule)))

    def test_tested_cannot_mean_upstream_tests_only(self):
        rule = example_rule()
        rule["status"] = "tested"
        rule["evidence"] = {"test_run": "https://github.com/example/upstream/actions"}
        self.assertTrue(any("integration_tests" in e for e in validate_rule(rule)))

    def test_planned_rule_cannot_be_enabled(self):
        rule = example_rule()
        rule["runtime_enabled"] = True
        self.assertTrue(any("cannot be runtime enabled" in e for e in validate_rule(rule)))

    def test_runtime_enabled_is_not_a_truthy_string(self):
        rule = example_rule()
        rule["runtime_enabled"] = "true"
        self.assertTrue(any("boolean" in e for e in validate_rule(rule)))

    def test_evidence_is_required_for_every_claimed_stage(self):
        for status in ["implemented", "tested", "validated"]:
            with self.subTest(status=status):
                rule = example_rule()
                rule["status"] = status
                self.assertTrue(any("evidence" in e for e in validate_rule(rule)))

    def test_evidence_cannot_escape_repository(self):
        with tempfile.TemporaryDirectory() as temp:
            outside = Path(temp)
            root = outside / "repo"
            root.mkdir()
            (outside / "claim.txt").write_text("not evidence", encoding="utf-8")
            rule = example_rule()
            rule["status"] = "implemented"
            rule["evidence"] = {"implementation": "../claim.txt"}
            self.assertTrue(validate_rule(rule, root))

    def test_duplicate_id_across_catalogs(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "data").mkdir()
            for name in ["a", "b"]:
                (root / "data" / f"rules-{name}.json").write_text(
                    json.dumps([example_rule()]), encoding="utf-8",
                )
            _, errors = read_registry(root)
            self.assertTrue(any("duplicate id" in e for e in errors))

    def test_invalid_json_is_reported(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "data").mkdir()
            (root / "data" / "rules-bad.json").write_text("{not json", encoding="utf-8")
            _, errors = read_registry(root)
            self.assertTrue(any("invalid JSON" in e for e in errors))

    def test_object_instead_of_array_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "data").mkdir()
            (root / "data" / "rules-bad.json").write_text("{}", encoding="utf-8")
            _, errors = read_registry(root)
            self.assertTrue(any("array" in e for e in errors))

    def test_scope_baseline_detects_missing_catalog_and_removed_rule(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "data").mkdir()
            (root / "data" / "catalog-baseline.json").write_text(json.dumps({
                "release_qualified": False, "change_reason": "Initial plan inventory.",
                "rule_modes": {"ktc.example.removed": "enabled_when_validated"},
            }), encoding="utf-8")
            errors = check_catalog_baseline({"core": [example_rule()]}, root)
            self.assertTrue(any("rules-platform.json" in e for e in errors))
            self.assertTrue(any("rule removed" in e for e in errors))
            self.assertTrue(any("missing new rule" in e for e in errors))

    def test_scope_baseline_detects_silent_disabling(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "data").mkdir()
            rule = example_rule()
            (root / "data" / "catalog-baseline.json").write_text(json.dumps({
                "release_qualified": False, "change_reason": "Initial plan inventory.",
                "rule_modes": {rule["id"]: "enabled_when_validated"},
            }), encoding="utf-8")
            rule["default_activation"] = "excluded"
            groups = {"core": [rule], "libraries": [], "ecosystem": [], "platform": []}
            self.assertTrue(any("disposition changed" in e for e in check_catalog_baseline(groups, root)))

    def test_markdown_is_deterministic_sorted_and_escaped(self):
        a = example_rule()
        b = copy.deepcopy(a)
        b["id"] = "ktc.example.aaa"
        b["problem"] = "A | B\nsecond line"
        output = render_catalog("example", [a, b])
        self.assertEqual(output, render_catalog("example", [b, a]))
        self.assertLess(output.index("`ktc.example.aaa`"), output.index("`ktc.example.owned_resource`"))
        self.assertIn("A \\| B second line", output)
        self.assertIn("not implemented or validated coverage", output)

    def test_missing_local_link_detected_but_external_not_fetched(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "docs").mkdir()
            (root / "README.md").write_text(
                "[missing](docs/missing.md) [external](https://example.invalid) "
                "[section](#local)\n```\n[future](not-created.md)\n```\n",
                encoding="utf-8",
            )
            errors = check_markdown_links(root)
            self.assertEqual(1, len(errors))
            self.assertIn("missing.md", errors[0])

    def test_json_examples_parse_but_pseudocode_is_not_json(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "docs").mkdir()
            (root / "docs" / "protocol.md").write_text(
                '```json\n{"state":"incomplete"}\n```\n'
                '```text\nnot json\n```\n', encoding="utf-8",
            )
            self.assertEqual([], check_json_examples(root))

    def test_malformed_contract_example_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "docs").mkdir()
            (root / "docs" / "protocol.md").write_text(
                '```json\n{"state": ...}\n```\n', encoding="utf-8",
            )
            self.assertTrue(any("invalid JSON" in e for e in check_json_examples(root)))

    def test_schema_metadata_matches_validator(self):
        import validate_plan
        schema = json.loads((Path(validate_plan.ROOT) / "data" / "rule-record.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(set(schema["required"]), validate_plan.FIELDS)
        for name, values in validate_plan.ENUMS.items():
            self.assertEqual(set(schema["properties"][name]["enum"]), values)


if __name__ == "__main__":
    unittest.main()
