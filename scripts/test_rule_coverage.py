"""Coverage bookkeeping tests; these are not detector integration fixtures."""

import copy
import json
from pathlib import Path
import tempfile
import unittest

from rule_coverage import CASE_KINDS, QUALIFICATION_DIGESTS, QUALIFICATION_IDENTITIES, CoverageError, digest, generate_coverage, main, read_json


CONTEXT = {
    "id": ":fixture/main/jvm/main", "target": "jvm", "source_set": "main",
    "kotlin_version": "2.4.21", "jdk_version": "21", "library_versions": {},
}
QUALIFIED_CONTEXT = {
    **CONTEXT, "module": ":fixture", "compilation": "main", "compiler_version": "2.4.21",
    **{field: "sha256:" + digest(field.encode()) for field in QUALIFICATION_DIGESTS},
}


class CoverageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "data").mkdir()
        self.catalog = [{
            "id": "ktc.example.rule", "status": "planned", "depth": "semantic",
            "default_activation": "enabled_when_validated", "disposition_reason": "Requires actual validation.",
        }]
        self.write("data/rules-example.json", self.catalog)
        self.mapping_path = self.root / "modules/provider-example/src/main/resources/example-rule-map.json"
        self.evidence_path = self.root / "providers/example-fixtures/evidence/run.json"
        self.map = {
            "schema_version": 1, "provider_id": "example", "provider_version": "1.0",
            "mappings": [{"rule_id": "ktc.example.rule", "upstream_id": "RealRule", "relation": "exact"}],
        }
        self.sidecar = {
            "schema_version": 1, "provider_id": "example", "provider_version": "1.0", "context": CONTEXT,
            "rules": [{
                "rule_id": "ktc.example.rule", "implemented": True, "tested": True, "qualified": False,
                "provider_status": "executed", "implementation_evidence": [self.artifact("src/implementation.kt", b"// integration adapter\n")],
                "cases": [{"kind": kind, "passed": True, "evidence": [self.artifact(f"runs/{kind}.json", json.dumps({"real_fixture_kind": kind}).encode())]} for kind in sorted(CASE_KINDS)],
            }],
        }

    def write(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def artifact(self, relative, data):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return {"path": relative, "sha256": digest(data)}

    def emit(self, sidecar=True):
        self.write(self.mapping_path.relative_to(self.root), self.map)
        if sidecar:
            self.write(self.evidence_path.relative_to(self.root), self.sidecar)
        return generate_coverage(self.root)

    def test_planned_metadata_does_not_mean_implementation(self):
        output = generate_coverage(self.root)
        self.assertEqual(1, output["catalog_records"])
        self.assertEqual(1, output["row_count"])
        row = output["rows"][0]
        self.assertEqual("unimplemented", row["state"])
        self.assertFalse(row["mapped"])
        self.assertFalse(row["runtime_default_enabled"])
        self.assertFalse(output["release_qualified"])

    def test_mapping_is_not_implementation_or_testing(self):
        row = self.emit(sidecar=False)["rows"][0]
        self.assertTrue(row["mapped"])
        for stage in ("implemented", "tested", "qualified"):
            self.assertFalse(row[stage], stage)

    def test_explicit_hash_backed_tested_is_not_qualified(self):
        output = self.emit()
        row = output["rows"][0]
        self.assertTrue(row["implemented"])
        self.assertTrue(row["tested"])
        self.assertFalse(row["qualified"])
        self.assertEqual("tested_unqualified", row["state"])
        self.assertFalse(output["release_qualified"])

    def test_each_integration_case_kind_is_required(self):
        for kind in CASE_KINDS:
            with self.subTest(kind=kind):
                original = copy.deepcopy(self.sidecar)
                self.sidecar["rules"][0]["cases"] = [case for case in original["rules"][0]["cases"] if case["kind"] != kind]
                self.assertFalse(self.emit()["rows"][0]["tested"])
                self.sidecar = original

    def test_failed_case_cannot_be_counted(self):
        self.sidecar["rules"][0]["cases"][0]["passed"] = False
        self.assertFalse(self.emit()["rows"][0]["tested"])

    def test_passed_case_without_artifact_cannot_be_counted(self):
        self.sidecar["rules"][0]["cases"][0]["evidence"] = []
        self.assertFalse(self.emit()["rows"][0]["tested"])

    def test_source_artifact_and_explicit_implementation_claim_both_needed(self):
        self.sidecar["rules"][0]["implementation_evidence"] = []
        self.assertFalse(self.emit()["rows"][0]["implemented"])
        self.sidecar["rules"][0]["implementation_evidence"] = [self.artifact("src/implementation.kt", b"adapter")]
        self.sidecar["rules"][0]["implemented"] = False
        self.assertFalse(self.emit()["rows"][0]["implemented"])

    def test_refined_library_contract_not_implemented_by_upstream_mapping(self):
        self.map["mappings"][0]["relation"] = "requires_refinement"
        row = self.emit()["rows"][0]
        self.assertTrue(row["mapped"])
        self.assertFalse(row["implemented"])
        self.assertFalse(row["tested"])

    def test_excluded_alias_is_retained_not_silently_qualified(self):
        self.catalog[0]["default_activation"] = "excluded"
        self.write("data/rules-example.json", self.catalog)
        self.map["mappings"][0]["relation"] = "excluded_alias"
        output = self.emit()
        self.assertEqual(1, output["row_count"])
        self.assertEqual("excluded", output["rows"][0]["state"])
        self.assertFalse(output["rows"][0]["implemented"])
        self.assertFalse(output["release_qualified"])

    def test_all_activation_modes_are_included(self):
        self.write("data/rules-example.json", [{**self.catalog[0], "id": f"ktc.example.{mode}", "default_activation": mode} for mode in ("enabled_when_validated", "advisory", "opt_in", "excluded")])
        output = generate_coverage(self.root)
        self.assertEqual(4, output["row_count"])

    def test_all_catalog_rules_emitted_for_each_exact_context(self):
        self.emit()
        other = {**CONTEXT, "kotlin_version": "2.4.10"}
        output = generate_coverage(self.root, contexts=[CONTEXT, other])
        self.assertEqual(2, output["row_count"])
        versions = {row["context"]["kotlin_version"]: row for row in output["rows"]}
        self.assertTrue(versions["2.4.21"]["tested"])
        self.assertFalse(versions["2.4.10"]["tested"])

    def test_provider_version_cannot_borrow_evidence(self):
        self.sidecar["provider_version"] = "2.0"
        with self.assertRaisesRegex(CoverageError, "unmapped provider/version"):
            self.emit()

    def test_multiple_providers_do_not_merge_partial_case_sets(self):
        self.sidecar["rules"][0]["cases"] = self.sidecar["rules"][0]["cases"][:3]
        self.emit()
        second = copy.deepcopy(self.sidecar)
        second["provider_id"] = "second"
        second["rules"][0]["cases"] = [{"kind": kind, "passed": True, "evidence": [self.artifact(f"second/{kind}.txt", kind.encode())]} for kind in sorted(CASE_KINDS)[3:]]
        self.write("providers/second-fixtures/evidence/run.json", second)
        second_map = {**self.map, "provider_id": "second"}
        self.write("modules/provider-second/src/main/resources/second-rule-map.json", second_map)
        row = generate_coverage(self.root)["rows"][0]
        self.assertEqual(2, len(row["providers"]))
        self.assertFalse(row["tested"])

    def test_hash_mismatch_invalidates_inventory_instead_of_hiding_gap(self):
        reference = self.sidecar["rules"][0]["cases"][0]["evidence"][0]
        (self.root / reference["path"]).write_text("mutated report")
        with self.assertRaisesRegex(CoverageError, "digest mismatch"):
            self.emit()

    def test_missing_file_invalidates_evidence(self):
        reference = self.sidecar["rules"][0]["implementation_evidence"][0]
        (self.root / reference["path"]).unlink()
        with self.assertRaisesRegex(CoverageError, "missing/outside"):
            self.emit()

    def test_traversal_absolute_path_and_symlink_are_rejected(self):
        reference = self.sidecar["rules"][0]["implementation_evidence"][0]
        for bad in ("../escape", "/etc/passwd", "src/../implementation.kt", "src\\implementation.kt"):
            with self.subTest(path=bad):
                reference["path"] = bad
                with self.assertRaises(CoverageError):
                    self.emit()
        (self.root / "alias.kt").symlink_to(self.root / "src/implementation.kt")
        reference["path"] = "alias.kt"
        with self.assertRaisesRegex(CoverageError, "symlink"):
            self.emit()

    def test_claims_are_booleans_not_truthy_strings_or_numbers(self):
        for value in ("true", 1, None, {}):
            with self.subTest(value=value):
                self.sidecar["rules"][0]["tested"] = value
                with self.assertRaisesRegex(CoverageError, "boolean"):
                    self.emit()

    def test_duplicate_catalog_and_evidence_are_rejected(self):
        self.write("data/rules-other.json", self.catalog)
        with self.assertRaisesRegex(CoverageError, "duplicate catalog"):
            generate_coverage(self.root)
        (self.root / "data/rules-other.json").unlink()
        self.sidecar["rules"].append(copy.deepcopy(self.sidecar["rules"][0]))
        with self.assertRaisesRegex(CoverageError, "duplicate evidence"):
            self.emit()

    def test_duplicate_json_fields_are_rejected(self):
        path = self.root / "bad.json"
        path.write_text('{"qualified":false,"qualified":true}')
        with self.assertRaisesRegex(CoverageError, "duplicate JSON"):
            read_json(path)

    def test_unknown_rule_and_mapping_relation_are_rejected(self):
        self.map["mappings"][0]["rule_id"] = "ktc.missing.rule"
        with self.assertRaisesRegex(CoverageError, "unknown catalog"):
            self.emit(sidecar=False)
        self.map["mappings"][0]["rule_id"] = "ktc.example.rule"
        self.map["mappings"][0]["relation"] = "semantic-by-regex"
        with self.assertRaisesRegex(CoverageError, "mapping relation"):
            self.emit(sidecar=False)

    def test_frozen_record_digest_detects_catalog_drift(self):
        self.map["mappings"][0]["catalog_record_sha256"] = "0" * 64
        with self.assertRaisesRegex(CoverageError, "catalog drift"):
            self.emit(sidecar=False)

    def test_frozen_record_digest_rejects_nonstring_values(self):
        self.map["mappings"][0]["catalog_record_sha256"] = 42
        with self.assertRaisesRegex(CoverageError, "catalog_record_sha256 must be a string"):
            self.emit(sidecar=False)

    def test_relative_input_manifests_are_repository_relative(self):
        self.emit()
        output_path = self.root / "coverage.json"
        self.assertEqual(0, main([
            "--root", str(self.root),
            "--mapping", self.mapping_path.relative_to(self.root).as_posix(),
            "--evidence", self.evidence_path.relative_to(self.root).as_posix(),
            "--output", str(output_path),
        ]))
        self.assertTrue(read_json(output_path)["rows"][0]["tested"])

    def test_input_manifests_cannot_escape_repository_or_use_symlinks(self):
        self.emit()
        outside = self.root.parent / "outside-rule-map.json"
        for bad in (outside, Path("../outside-rule-map.json")):
            with self.subTest(path=bad):
                with self.assertRaises(CoverageError):
                    generate_coverage(self.root, mapping_paths=[bad])
        alias = self.root / "alias-rule-map.json"
        alias.symlink_to(self.mapping_path)
        with self.assertRaisesRegex(CoverageError, "symlink"):
            generate_coverage(self.root, mapping_paths=[alias])
        alias = self.root / "alias-evidence.json"
        alias.symlink_to(self.evidence_path)
        with self.assertRaisesRegex(CoverageError, "symlink"):
            generate_coverage(self.root, evidence_paths=[alias])

    def test_qualification_requires_known_tuple_and_separate_evidence(self):
        self.sidecar["context"] = QUALIFIED_CONTEXT
        self.sidecar["rules"][0]["qualified"] = True
        self.assertFalse(self.emit()["rows"][0]["qualified"])
        self.sidecar["rules"][0]["qualification_evidence"] = [self.artifact("review/qualification.json", b'{"reviewed":true}')]
        self.assertTrue(self.emit()["rows"][0]["qualified"])
        self.sidecar["context"] = {**QUALIFIED_CONTEXT, "target": "unknown"}
        self.assertFalse(self.emit()["rows"][0]["qualified"])
        self.sidecar["context"] = QUALIFIED_CONTEXT
        self.sidecar["rules"][0]["gaps"] = ["Native coverage not qualified."]
        self.assertFalse(self.emit()["rows"][0]["qualified"])

    def test_every_qualification_identity_and_digest_is_required(self):
        self.sidecar["rules"][0]["qualified"] = True
        self.sidecar["rules"][0]["qualification_evidence"] = [self.artifact("review/qualification.json", b'{"reviewed":true}')]
        for field in (*QUALIFICATION_IDENTITIES, *QUALIFICATION_DIGESTS, "library_versions"):
            with self.subTest(field=field):
                self.sidecar["context"] = {key: value for key, value in QUALIFIED_CONTEXT.items() if key != field}
                if field in ("target", "source_set", "kotlin_version"):
                    self.sidecar["context"][field] = "unknown"
                self.assertFalse(self.emit()["rows"][0]["qualified"])
        self.sidecar["context"] = {**QUALIFIED_CONTEXT, "library_versions": {"some-library": "unknown"}}
        self.assertFalse(self.emit()["rows"][0]["qualified"])

    def test_local_requested_cells_never_certify_a_complete_release(self):
        self.sidecar["context"] = QUALIFIED_CONTEXT
        self.sidecar["rules"][0]["qualified"] = True
        self.sidecar["rules"][0]["qualification_evidence"] = [self.artifact("review/qualification.json", b'{"reviewed":true}')]
        output = self.emit()
        self.assertTrue(output["all_requested_cells_qualified"])
        self.assertTrue(output["rows"][0]["qualified"])
        self.assertFalse(output["release_qualified"])
        self.assertIn("complete required release matrix", output["release_qualification_reason"])
        output_path = self.root / "coverage.json"
        self.assertEqual(0, main(["--root", str(self.root), "--require-qualified", "--output", str(output_path)]))
        self.assertFalse(read_json(output_path)["release_qualified"])

    def test_cli_strict_failure_keeps_complete_report(self):
        output_path = self.root / "coverage.json"
        self.assertEqual(1, main(["--root", str(self.root), "--require-qualified", "--output", str(output_path)]))
        self.assertEqual(1, read_json(output_path)["row_count"])
        self.assertEqual(1, main(["--root", str(self.root), "--require-tested", "--output", str(output_path)]))
        self.assertEqual(0, main(["--root", str(self.root), "--output", str(output_path)]))

    def test_repository_inventory_retains_all_1663_records(self):
        root = Path(__file__).resolve().parents[1]
        # Ignore transient worker sidecars in this structural count test.
        output = generate_coverage(root, mapping_paths=[], evidence_paths=[])
        self.assertEqual(1663, output["catalog_records"])
        self.assertEqual(1663, output["row_count"])
        self.assertEqual(0, output["summary"]["qualified"])


if __name__ == "__main__":
    unittest.main()
