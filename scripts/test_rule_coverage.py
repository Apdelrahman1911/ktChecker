"""Coverage bookkeeping tests; these are not detector integration fixtures."""

import copy
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from rule_coverage import CASE_KINDS, QUALIFICATION_DIGESTS, QUALIFICATION_IDENTITIES, CoverageError, catalog_visibility, digest, generate_coverage, main, publish_inventory, read_json


CONTEXT = {
    "id": ":fixture/main/jvm/main", "target": "jvm", "source_set": "main",
    "kotlin_version": "2.4.21", "jdk_version": "21", "library_versions": {},
}
QUALIFIED_CONTEXT = {
    **CONTEXT, "module": ":fixture", "compilation": "main", "compiler_version": "2.4.21",
    **{field: "sha256:" + digest(field.encode()) for field in QUALIFICATION_DIGESTS},
}


def frozen_1663_catalog_root(test):
    """Exact old catalog SOURCE DATA, not a current selector or artifact reader."""
    repository = Path(__file__).resolve().parents[1]
    frozen = repository / "providers/compiler-fixtures/mapping-contracts/legacy443-data"
    selector_bytes = (frozen / "current-inputs.json.txt").read_bytes()
    test.assertEqual("08d88559f95f86557efaa9b7663c8b16e66991f3548d119ec71af1b756f4b7a3", digest(selector_bytes))
    selection = json.loads(selector_bytes)
    catalog_paths = {"data/rules-core.json", "data/rules-ecosystem.json",
                     "data/rules-libraries.json", "data/rules-platform.json"}
    test.assertEqual(catalog_paths, {item["path"] for item in selection["catalogs"]})
    temporary = tempfile.TemporaryDirectory(prefix="frozen1663-catalog-data-")
    test.addCleanup(temporary.cleanup)
    root = Path(temporary.name)
    # Only these four catalog bodies are copied. All evidence, mapping, context
    # and reference fields in the frozen selector remain opaque metadata.
    for item in selection["catalogs"]:
        relative = item["path"]
        source = frozen / "rules-core.json.txt" if relative == "data/rules-core.json" else repository / relative
        body = source.read_bytes()
        test.assertEqual((item["bytes"], item["sha256"]), (len(body), digest(body)))
        destination = root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(body)
    return root


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

    def test_withdrawn_and_native_digest_bound_sidecars_coexist_without_renewal(self):
        native = copy.deepcopy(self.sidecar)
        native["context"] = {
            **CONTEXT, "execution_backend": "owned_child_native_supervisor_preview1",
            "supervisor_protocol_version": "ktchecker-owned-child-supervisor/preview-1",
            "supervisor_target": "linux-x86_64",
            **{name: "sha256:" + digest(name.encode()) for name in (
                "supervisor_source_digest", "supervisor_binary_digest",
                "runtime_execution_source_digest", "runtime_code_image_digest",
            )},
        }
        original_native_context = copy.deepcopy(native["context"])
        for stage in ("implemented", "tested", "qualified", "attempted"):
            self.sidecar["rules"][0][stage] = False
        self.sidecar["rules"][0]["cases"] = []
        self.sidecar["rules"][0]["implementation_evidence"] = []
        self.emit()
        self.write("providers/example-fixtures/evidence/run-native-preview1.json", native)
        output = generate_coverage(self.root)
        self.assertEqual(2, output["row_count"])
        withdrawn, executed = sorted(output["rows"], key=lambda row: row["tested"])
        self.assertEqual(CONTEXT, withdrawn["context"])
        self.assertFalse(withdrawn["implemented"])
        self.assertFalse(withdrawn["tested"])
        self.assertEqual(original_native_context, executed["context"])
        self.assertTrue(executed["implemented"])
        self.assertTrue(executed["tested"])
        self.assertEqual(1, output["summary"]["tested"])
        self.assertFalse(output["release_qualified"])

    def test_a_new_filename_does_not_isolate_duplicate_withdrawn_context(self):
        for stage in ("implemented", "tested", "qualified", "attempted"):
            self.sidecar["rules"][0][stage] = False
        self.sidecar["rules"][0]["cases"] = []
        self.sidecar["rules"][0]["implementation_evidence"] = []
        self.emit()
        self.write("providers/example-fixtures/evidence/a-different-name.json", copy.deepcopy(self.sidecar))
        with self.assertRaisesRegex(CoverageError, "duplicate evidence"):
            generate_coverage(self.root)

    def test_changed_native_or_runtime_digests_cannot_borrow_tested_evidence(self):
        digest_names = (
            "supervisor_source_digest", "supervisor_binary_digest",
            "runtime_execution_source_digest", "runtime_code_image_digest",
        )
        self.sidecar["context"] = {
            **CONTEXT, "execution_backend": "owned_child_native_supervisor_preview1",
            "supervisor_protocol_version": "ktchecker-owned-child-supervisor/preview-1",
            "supervisor_target": "linux-x86_64",
            **{name: "sha256:" + digest(name.encode()) for name in digest_names},
        }
        original = copy.deepcopy(self.sidecar["context"])
        self.emit()
        for name in digest_names:
            with self.subTest(changed_digest=name):
                changed = {**original, name: "sha256:" + digest((name + " changed").encode())}
                rows = generate_coverage(self.root, contexts=[original, changed])["rows"]
                by_context = {row["context"][name]: row for row in rows}
                self.assertTrue(by_context[original[name]]["tested"])
                self.assertFalse(by_context[changed[name]]["implemented"])
                self.assertFalse(by_context[changed[name]]["tested"])
                self.assertFalse(by_context[changed[name]]["qualified"])

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

    def test_absolute_input_root_aliases_canonicalize_only_repository_prefix(self):
        self.emit()
        with tempfile.TemporaryDirectory() as outside:
            alias = Path(outside) / "repository-root"
            alias.symlink_to(self.root, target_is_directory=True)
            output = generate_coverage(self.root.resolve(),
                mapping_paths=[alias / self.mapping_path.relative_to(self.root)],
                evidence_paths=[alias / self.evidence_path.relative_to(self.root)])
            self.assertTrue(output["rows"][0]["tested"])
            self.assertFalse(output["release_qualified"])

    def test_root_alias_does_not_hide_in_repository_symlink_ancestors(self):
        self.emit()
        with tempfile.TemporaryDirectory() as outside:
            alias = Path(outside) / "repository-root"
            alias.symlink_to(self.root, target_is_directory=True)
            resources = self.root / "linked-resources"
            resources.symlink_to(self.mapping_path.parent, target_is_directory=True)
            reentrant = self.root / "back-to-root"
            reentrant.symlink_to(self.root, target_is_directory=True)
            for path in (alias / "linked-resources" / self.mapping_path.name,
                         alias / "back-to-root" / self.mapping_path.relative_to(self.root)):
                with self.subTest(path=path), self.assertRaisesRegex(CoverageError, "symlink"):
                    generate_coverage(self.root.resolve(), mapping_paths=[path])
            with self.assertRaisesRegex(CoverageError, "symlink"):
                generate_coverage(self.root.resolve(),
                    evidence_paths=[alias / "back-to-root" / self.evidence_path.relative_to(self.root)])

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

    def test_failed_refresh_replaces_previous_counts_with_explicit_unavailable(self):
        self.emit()
        output_path = self.root / "coverage.json"
        args = ["--root", str(self.root), "--output", str(output_path)]
        self.assertEqual(0, main(args))
        self.assertTrue(read_json(output_path)["inventory_valid"])
        self.assertEqual(1, read_json(output_path)["summary"]["tested"])
        (self.root / "src/implementation.kt").write_text("changed after the attested run")
        with contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(2, main(args))
        self.assertIn("digest mismatch", error.getvalue())
        failed = read_json(output_path)
        self.assertEqual("unavailable", failed["inventory_status"])
        self.assertFalse(failed["inventory_valid"])
        self.assertFalse(failed["all_requested_cells_qualified"])
        self.assertFalse(failed["release_qualified"])
        for key in ("summary", "row_count", "rows", "contexts", "catalog_records", "states"):
            self.assertIsNone(failed[key], key)
        self.assertNotIn(str(self.root), output_path.read_text())
        self.assertNotIn("implementation.kt", output_path.read_text())

    def test_interrupted_generation_leaves_pending_not_previous_success(self):
        output_path = self.root / "coverage.json"
        output_path.write_text('{"inventory_valid":true,"summary":{"qualified":999}}')
        with patch("rule_coverage.generate_coverage", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                main(["--root", str(self.root), "--output", str(output_path)])
        pending = read_json(output_path)
        self.assertEqual("pending", pending["inventory_status"])
        self.assertFalse(pending["inventory_valid"])
        self.assertIsNone(pending["summary"])
        self.assertIsNone(pending["rows"])

    def test_missing_context_invalidates_old_output_before_input_read(self):
        output_path = self.root / "coverage.json"
        self.assertEqual(0, main(["--root", str(self.root), "--output", str(output_path)]))
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, main(["--root", str(self.root), "--context", str(self.root / "missing-context.json"),
                                      "--output", str(output_path)]))
        self.assertEqual("unavailable", read_json(output_path)["inventory_status"])
        self.assertFalse(read_json(output_path)["inventory_valid"])

    def test_valid_but_incomplete_requirement_gate_keeps_truthful_inventory(self):
        output_path = self.root / "coverage.json"
        self.assertEqual(1, main(["--root", str(self.root), "--require-tested", "--output", str(output_path)]))
        report = read_json(output_path)
        self.assertEqual("valid", report["inventory_status"])
        self.assertTrue(report["inventory_valid"])
        self.assertEqual(1, report["row_count"])
        self.assertFalse(report["rows"][0]["tested"])
        self.assertFalse(report["release_qualified"])

    def test_atomic_report_replace_failure_does_not_expose_partial_json_or_leave_temp(self):
        output_path = self.root / "coverage.json"
        original = b'{"inventory_status":"pending","inventory_valid":false}\n'
        output_path.write_bytes(original)
        with patch("rule_coverage.os.replace", side_effect=OSError("injected publication failure")):
            with self.assertRaises(OSError):
                publish_inventory(output_path, {"inventory_valid": True, "rows": ["new"]})
        self.assertEqual(original, output_path.read_bytes())
        self.assertEqual([], list(self.root.glob(".ktchecker-coverage-*.tmp")))

    def test_failed_output_publication_is_reported_not_assumed_to_invalidate_file(self):
        output_path = self.root / "coverage.json"
        output_path.write_text('{"historical":true}')
        with patch("rule_coverage.os.replace", side_effect=OSError("injected failure")), \
                contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(2, main(["--root", str(self.root), "--output", str(output_path)]))
        self.assertIn("do not use an existing output as current evidence", error.getvalue())
        self.assertEqual({"historical": True}, read_json(output_path))
        self.assertEqual([], list(self.root.glob(".ktchecker-coverage-*.tmp")))

    def test_invalid_input_without_output_does_not_emit_success_shaped_json(self):
        with contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(2, main(["--root", str(self.root / "missing-repository")]))
        self.assertEqual("", out.getvalue())
        self.assertIn("rule coverage error", error.getvalue())

    def test_repository_inventory_retains_all_1663_records(self):
        # Frozen predecessor regression, deliberately NOT current1665 admission.
        root = frozen_1663_catalog_root(self)
        output = generate_coverage(root, mapping_paths=[], evidence_paths=[])
        self.assertEqual(1663, output["catalog_records"])
        self.assertEqual(1663, output["row_count"])
        self.assertEqual(0, output["summary"]["qualified"])

    # These projections contain declared metadata only, never rescued execution
    # credit. Every fixture below is private synthetic bookkeeping, not a detector.
    def failed_visibility(self, *extra):
        output = self.root / "failed-coverage.json"
        with contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(2, main(["--root", str(self.root), "--output", str(output), *extra]))
        self.assertTrue(errors.getvalue())
        report = read_json(output)
        self.assertEqual("unavailable", report["inventory_status"])
        for field in ("inventory_valid", "all_requested_cells_qualified", "release_qualified"):
            self.assertFalse(report[field])
        for field in ("catalog_records", "contexts", "row_count", "summary", "states", "rows"):
            self.assertIsNone(report[field])
        self.assertNotIn(str(self.root), output.read_text())
        self.assertNotIn("implementation.kt", output.read_text())
        visibility = report["catalog_visibility"]
        for field in ("evidence_valid", "current_coverage_known", "all_requested_cells_qualified", "release_qualified"):
            self.assertFalse(visibility[field])
        for row in visibility["rows"] or []:
            self.assertIsNone(row["mapped"])
            for field in ("implemented", "tested", "qualified", "runtime_default_enabled"):
                self.assertFalse(row[field])
            self.assertEqual("unavailable", row["evidence_status"])
            self.assertNotIn("cases", row)
            self.assertNotIn("implementation_evidence", row)
        return visibility

    def assert_unknown_visibility_scope(self, visibility):
        self.assertTrue(visibility["catalog_valid"])
        self.assertEqual(len(self.catalog), visibility["catalog_records"])
        self.assertEqual(len(self.catalog), len(visibility["catalog_rules"]))
        self.assertFalse(visibility["context_scope_known"])
        self.assertEqual("catalog_only_context_scope_unavailable", visibility["status"])
        for field in ("contexts", "context_scope_source", "context_count", "row_count", "excluded_cells", "candidate_cells", "rows"):
            self.assertIsNone(visibility[field])

    def test_stale_source_keeps_all_declared_records_but_strict_generator_still_rejects(self):
        self.catalog += [
            {"id": "ktc.excluded.alias", "default_activation": "excluded", "disposition_reason": "Alias, not another detector."},
            {"id": "ktc.opt.in", "default_activation": "opt_in", "disposition_reason": "Explicit owner policy."},
        ]
        self.write("data/rules-example.json", self.catalog)
        self.emit()
        (self.root / "src/implementation.kt").write_text("changed after attestation")
        with self.assertRaisesRegex(CoverageError, "digest mismatch"):
            generate_coverage(self.root)
        visibility = self.failed_visibility()
        self.assertEqual((3, 1, 2), tuple(visibility[field] for field in ("catalog_records", "excluded_records", "candidate_records")))
        self.assertEqual((3, 1, 2), tuple(visibility[field] for field in ("row_count", "excluded_cells", "candidate_cells")))
        self.assertEqual({rule["id"] for rule in self.catalog}, {row["rule_id"] for row in visibility["rows"]})
        self.assertEqual(1, sum(row["state"] == "excluded" for row in visibility["rows"]))
        self.assertEqual(2, sum(row["state"] == "current_evidence_unavailable" for row in visibility["rows"]))
        self.assertEqual("observed_declarations", visibility["context_scope_source"])

    def test_stale_early_sidecar_does_not_hide_later_declared_contexts(self):
        self.emit()
        later = copy.deepcopy(self.sidecar)
        later["context"] = {**CONTEXT, "kotlin_version": "2.4.10"}
        self.write("providers/example-fixtures/evidence/z-later.json", later)
        (self.root / "src/implementation.kt").write_text("stale current source")
        visibility = self.failed_visibility()
        self.assertEqual(2, visibility["context_count"])
        self.assertEqual(2, visibility["row_count"])
        self.assertEqual({"2.4.21", "2.4.10"}, {context["kotlin_version"] for context in visibility["contexts"]})

    def test_explicit_context_selection_remains_exact_despite_failed_other_evidence(self):
        self.emit()
        selected = {**QUALIFIED_CONTEXT, "kotlin_version": "2.4.10", "extra_identity": {"opaque": "preserved"}}
        path = self.write("selected-context.json", selected)
        (self.root / "src/implementation.kt").write_text("stale current source")
        visibility = self.failed_visibility("--context", str(path))
        self.assertEqual([selected], visibility["contexts"])
        self.assertEqual("explicit", visibility["context_scope_source"])
        self.assertEqual(digest(json.dumps(selected, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()),
                         visibility["rows"][0]["context_sha256"])

    def test_missing_explicit_context_keeps_catalog_only_never_falls_back_to_observed(self):
        self.emit()
        visibility = self.failed_visibility("--context", str(self.root / "missing-context.json"))
        self.assert_unknown_visibility_scope(visibility)

    def test_malformed_or_structurally_invalid_explicit_context_never_invents_scope(self):
        self.emit()
        path = self.root / "invalid-context.json"
        for data in ('{"id":"first","id":"duplicate"}', json.dumps({**CONTEXT, "target": ""}), "[]", "null"):
            with self.subTest(data=data):
                path.write_text(data)
                visibility = self.failed_visibility("--context", str(path))
                self.assert_unknown_visibility_scope(visibility)

    def test_partially_read_explicit_context_list_is_not_a_complete_requested_scope(self):
        self.emit()
        good = self.write("good-context.json", CONTEXT)
        visibility = self.failed_visibility("--context", str(good), "--context", str(self.root / "missing.json"))
        self.assert_unknown_visibility_scope(visibility)

    def test_invalid_or_missing_discovered_envelope_keeps_context_scope_unknown(self):
        self.emit()
        original = self.evidence_path.read_bytes()
        for bad in (b"not JSON", b'{"context":{},"context":{}}', b'{"schema_version":1,"context":null}',
                    json.dumps({**self.sidecar, "rules": {}}).encode(),
                    json.dumps({**self.sidecar, "provider_version": ""}).encode()):
            self.evidence_path.write_bytes(bad)
            self.assert_unknown_visibility_scope(self.failed_visibility())
        self.evidence_path.write_bytes(original)
        visibility = self.failed_visibility("--evidence", "providers/example-fixtures/evidence/missing.json")
        self.assert_unknown_visibility_scope(visibility)

    def test_symlinked_evidence_envelope_cannot_supply_a_rescued_context(self):
        self.emit()
        alias = self.root / "evidence-alias.json"
        alias.symlink_to(self.evidence_path)
        self.assert_unknown_visibility_scope(self.failed_visibility("--evidence", str(alias)))

    def test_invalid_catalog_has_unknown_denominators_not_zero_or_inherited_records(self):
        self.emit()
        for bad in (self.catalog + self.catalog, {"not": "a catalog array"}, [{"id": 42}]):
            self.write("data/rules-example.json", bad)
            visibility = self.failed_visibility()
            self.assertFalse(visibility["catalog_valid"])
            self.assertFalse(visibility["context_scope_known"])
            for field in ("catalog_records", "excluded_records", "candidate_records", "catalog_rules", "row_count", "rows"):
                self.assertIsNone(visibility[field])

    def test_catalog_symlink_is_not_a_literal_independent_visibility_basis(self):
        self.emit()
        original = self.root / "data/rules-example.json"
        target = self.write("other-catalog.json", self.catalog)
        original.unlink()
        original.symlink_to(target)
        (self.root / "src/implementation.kt").write_text("stale current source")
        visibility = self.failed_visibility()
        self.assertFalse(visibility["catalog_valid"])
        self.assertIsNone(visibility["catalog_records"])

    def test_mapping_catalog_drift_is_still_an_error_without_mapping_or_stage_salvage(self):
        self.emit()
        self.map["mappings"][0]["catalog_record_sha256"] = "0" * 64
        self.write(self.mapping_path.relative_to(self.root), self.map)
        with self.assertRaisesRegex(CoverageError, "catalog drift"):
            generate_coverage(self.root)
        visibility = self.failed_visibility()
        self.assertEqual(1, visibility["row_count"])
        self.assertIsNone(visibility["rows"][0]["mapped"])

    def test_malformed_mapping_and_unknown_rule_still_fail_with_only_declared_visibility(self):
        self.emit()
        original = self.mapping_path.read_bytes()
        for bad in (b"not JSON", b'{"schema_version":1,"mappings":[],"mappings":[]}',
                    json.dumps({**self.map, "mappings": [{"rule_id": "ktc.unknown", "relation": "exact"}]}).encode()):
            self.mapping_path.write_bytes(bad)
            self.assertEqual(1, self.failed_visibility()["row_count"])
        self.mapping_path.write_bytes(original)

    def test_invalid_reference_categories_are_not_accepted_as_usable_stale_evidence(self):
        self.emit()
        original = copy.deepcopy(self.sidecar["rules"][0]["cases"][0]["evidence"][0])
        alias = self.root / "reference-alias.json"
        alias.symlink_to(self.root / original["path"])
        for ref in ({**original, "sha256": "0" * 64}, {**original, "sha256": "not-a-hash"},
                    {**original, "path": "missing-original.json"}, {**original, "path": "../outside.json"},
                    {**original, "path": "/outside.json"}, {**original, "path": "reference-alias.json"}):
            with self.subTest(reference_category=ref["path"]):
                self.sidecar["rules"][0]["cases"][0]["evidence"] = [ref]
                self.write(self.evidence_path.relative_to(self.root), self.sidecar)
                with self.assertRaises(CoverageError):
                    generate_coverage(self.root)
                self.assertEqual(1, self.failed_visibility()["row_count"])

    def test_duplicate_and_unknown_rule_evidence_remain_errors_without_case_credit(self):
        self.emit()
        duplicate = self.write("providers/example-fixtures/evidence/duplicate.json", self.sidecar)
        with self.assertRaisesRegex(CoverageError, "duplicate evidence"):
            generate_coverage(self.root)
        self.assertEqual(1, self.failed_visibility()["row_count"])
        duplicate.unlink()
        bad = copy.deepcopy(self.sidecar)
        bad["rules"][0]["rule_id"] = "ktc.unknown"
        self.write(self.evidence_path.relative_to(self.root), bad)
        with self.assertRaisesRegex(CoverageError, "unknown catalog rule"):
            generate_coverage(self.root)
        self.assertEqual(1, self.failed_visibility()["row_count"])

    def test_explicit_qualified_claim_is_not_preserved_in_unavailable_visibility(self):
        self.sidecar["context"] = QUALIFIED_CONTEXT
        self.sidecar["rules"][0]["qualified"] = True
        self.sidecar["rules"][0]["qualification_evidence"] = [self.artifact("review/qualified.json", b"fabricated control only")]
        self.assertTrue(self.emit()["rows"][0]["qualified"])
        (self.root / "src/implementation.kt").write_text("stale current source")
        visibility = self.failed_visibility("--require-tested", "--require-qualified")
        self.assertEqual([QUALIFIED_CONTEXT], visibility["contexts"])
        self.assertFalse(visibility["rows"][0]["qualified"])

    def test_changed_native_and_code_context_identities_are_preserved_not_relabelled(self):
        original = {**CONTEXT, "supervisor_binary_digest": "sha256:" + "a" * 64,
                    "runtime_code_image_digest": "sha256:" + "b" * 64}
        changed = {**original, "supervisor_binary_digest": "sha256:" + "c" * 64,
                   "runtime_code_image_digest": "sha256:" + "d" * 64}
        self.sidecar["context"] = original
        self.emit()
        first = self.write("original-context.json", original)
        second = self.write("changed-context.json", changed)
        (self.root / "src/implementation.kt").write_text("stale current source")
        visibility = self.failed_visibility("--context", str(first), "--context", str(second))
        self.assertEqual({json.dumps(context, sort_keys=True) for context in (original, changed)},
                         {json.dumps(context, sort_keys=True) for context in visibility["contexts"]})
        self.assertEqual(2, len({row["context_sha256"] for row in visibility["rows"]}))
        self.assertEqual(original, read_json(self.evidence_path)["context"])

    def test_visibility_does_not_rewrite_sidecars_references_or_expected_hashes(self):
        self.emit()
        (self.root / "src/implementation.kt").write_text("changed source remains changed")
        paths = [path for path in self.root.rglob("*") if path.is_file()]
        before = {path: path.read_bytes() for path in paths}
        self.failed_visibility()
        self.assertEqual(before, {path: path.read_bytes() for path in paths})
        self.assertEqual(self.sidecar, read_json(self.evidence_path))

    def test_unreferenced_source_or_module_registration_edits_do_not_invent_staleness(self):
        before = self.emit()
        self.artifact("modules/unreferenced/build.gradle.kts", b"// unrelated registration, not an attested input\n")
        self.artifact("unreferenced-source.kt", b"// unrelated source, not evidence\n")
        after = generate_coverage(self.root)
        self.assertEqual(before, after)
        self.assertTrue(after["rows"][0]["tested"])
        self.assertFalse(after["release_qualified"])

    def test_pending_report_never_carries_yesterdays_catalog_visibility(self):
        self.emit()
        output = self.root / "coverage.json"
        output.write_text('{"catalog_visibility":{"catalog_records":999,"rows":["old"]}}')
        with patch("rule_coverage.generate_coverage", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                main(["--root", str(self.root), "--output", str(output)])
        pending = read_json(output)
        self.assertEqual("pending", pending["inventory_status"])
        self.assertFalse(pending["inventory_valid"])
        self.assertIsNone(pending["catalog_visibility"])

    def test_no_output_error_does_not_run_visibility_or_emit_success_shaped_json(self):
        self.emit()
        (self.root / "src/implementation.kt").write_text("stale current source")
        with patch("rule_coverage.catalog_visibility") as projection, \
                contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, main(["--root", str(self.root)]))
        self.assertEqual("", out.getvalue())
        projection.assert_not_called()

    def test_valid_but_incomplete_strict_gate_does_not_use_failed_visibility(self):
        self.emit()
        output = self.root / "coverage.json"
        with patch("rule_coverage.catalog_visibility") as projection:
            self.assertEqual(1, main(["--root", str(self.root), "--require-qualified", "--output", str(output)]))
        report = read_json(output)
        self.assertTrue(report["inventory_valid"])
        self.assertEqual(1, report["row_count"])
        self.assertTrue(report["rows"][0]["tested"])
        self.assertFalse(report["rows"][0]["qualified"])
        projection.assert_not_called()

    def test_full_declared_catalog_counts_are_real_records_not_context_multiplication(self):
        # Preserve the old1663 arithmetic instead of silently rebasing its oracle.
        root = frozen_1663_catalog_root(self)
        contexts = [CONTEXT, {**CONTEXT, "kotlin_version": "2.4.10"}]
        visibility = catalog_visibility(root, evidence_paths=[], contexts=contexts)
        self.assertEqual((1663, 46, 1617), tuple(visibility[field] for field in ("catalog_records", "excluded_records", "candidate_records")))
        self.assertEqual((3326, 92, 3234), tuple(visibility[field] for field in ("row_count", "excluded_cells", "candidate_cells")))
        rule_ids = {row["rule_id"] for row in visibility["catalog_rules"]}
        self.assertEqual(1663, len(rule_ids))
        by_context = {}
        for row in visibility["rows"]:
            by_context.setdefault(row["context_sha256"], set()).add(row["rule_id"])
            self.assertFalse(row["implemented"] or row["tested"] or row["qualified"])
        self.assertEqual(2, len(by_context))
        self.assertTrue(all(ids == rule_ids for ids in by_context.values()))

    def failed_mapping_report(self, *extra):
        self.failed_visibility(*extra)
        report = read_json(self.root / "failed-coverage.json")
        diagnostics = report["failure_diagnostics"]
        self.assertEqual("incomplete", diagnostics["status"])
        self.assertFalse(diagnostics["current_coverage_known"])
        errors = diagnostics["mapping_reference_errors"]
        self.assertEqual(len(errors), diagnostics["mapping_reference_error_count"])
        self.assertEqual(list(range(len(errors))), [error["error_index"] for error in errors])
        for error in errors:
            for field in ("error_index", "mapping_input_index", "mapping_index", "reference_index"):
                self.assertTrue(error[field] is None or type(error[field]) is int)
        for row in report["catalog_visibility"]["rows"] or []:
            self.assertEqual("incomplete", row["evaluation_state"])
            for index in row["mapping_reference_error_indexes"]:
                self.assertIs(type(index), int)
                self.assertLess(index, len(errors))
                self.assertEqual(row["rule_id"], errors[index]["rule_id"])
        return report, diagnostics

    def test_invalid_mapping_diagnostics_preserve_all_rows_and_multiple_mismatches_per_context(self):
        self.catalog += [
            {"id": "ktc.example.opt-in", "default_activation": "opt_in"},
            {"id": "ktc.example.excluded", "default_activation": "excluded"},
        ]
        self.write("data/rules-example.json", self.catalog)
        first = self.artifact("src/first-adapter.kt", b"// first bound adapter\n")
        second = self.artifact("src/second-adapter.kt", b"// second bound adapter\n")
        self.map["mappings"][0]["implementation_evidence"] = [first, second]
        self.emit()
        (self.root / first["path"]).write_bytes(b"// first changed\n")
        (self.root / second["path"]).write_bytes(b"// second changed\n")
        other_context = {**CONTEXT, "kotlin_version": "2.4.10", "opaque_identity": {"retained": True}}
        contexts = [self.write("first-context.json", CONTEXT), self.write("second-context.json", other_context)]
        unchanged_paths = [self.mapping_path, self.evidence_path, self.root / first["path"], self.root / second["path"]]
        before = {path: path.read_bytes() for path in unchanged_paths}
        report, diagnostics = self.failed_mapping_report("--context", str(contexts[0]), "--context", str(contexts[1]))
        self.assertTrue(diagnostics["mapping_reference_scan_complete"])
        self.assertEqual((3, 2, 6), tuple(diagnostics[field] for field in ("declared_catalog_records", "declared_context_count", "declared_row_count")))
        errors = diagnostics["mapping_reference_errors"]
        self.assertEqual(["reference_digest_mismatch", "reference_digest_mismatch"], [error["code"] for error in errors])
        self.assertEqual([first["path"], second["path"]], [error["reference_path"] for error in errors])
        self.assertEqual([0, 1], [error["reference_index"] for error in errors])
        visibility = report["catalog_visibility"]
        self.assertEqual({json.dumps(context, sort_keys=True) for context in (CONTEXT, other_context)},
                         {json.dumps(context, sort_keys=True) for context in visibility["contexts"]})
        self.assertEqual(6, len(visibility["rows"]))
        self.assertEqual(2, sum(row["state"] == "excluded" for row in visibility["rows"]))
        for row in visibility["rows"]:
            expected = [0, 1] if row["rule_id"] == "ktc.example.rule" else []
            self.assertEqual(expected, row["mapping_reference_error_indexes"])
        self.assertEqual(before, {path: path.read_bytes() for path in unchanged_paths})
        with self.assertRaisesRegex(CoverageError, "digest mismatch"):
            generate_coverage(self.root)

    def test_invalid_mapping_diagnostics_continue_into_later_manifest(self):
        early = self.artifact("src/early-adapter.kt", b"// early\n")
        late = self.artifact("src/late-adapter.kt", b"// late\n")
        self.map["mappings"][0]["implementation_evidence"] = [early]
        self.emit()
        later = {**self.map, "provider_id": "later", "mappings": [
            {"rule_id": "ktc.example.rule", "relation": "exact", "implementation_evidence": [late]},
        ]}
        later_path = self.write("modules/provider-zlater/src/main/resources/later-rule-map.json", later)
        (self.root / early["path"]).write_text("changed early adapter")
        (self.root / late["path"]).write_text("changed late adapter")
        _, diagnostics = self.failed_mapping_report()
        self.assertTrue(diagnostics["mapping_reference_scan_complete"])
        self.assertEqual(2, diagnostics["mapping_manifest_count"])
        self.assertEqual(2, diagnostics["mapping_reference_error_count"])
        self.assertEqual({self.mapping_path.relative_to(self.root).as_posix(), later_path.relative_to(self.root).as_posix()},
                         {error["mapping_manifest"] for error in diagnostics["mapping_reference_errors"]})
        self.assertEqual([0, 1], [error["mapping_input_index"] for error in diagnostics["mapping_reference_errors"]])

    def test_invalid_mapping_diagnostics_windows_drive_paths_are_redacted(self):
        self.emit()
        bad_paths = ("C:/PRIVATE-drive-absolute", "C:PRIVATE-drive-relative",
                     "z:/PRIVATE-drive-absolute", "z:PRIVATE-drive-relative")
        self.map["mappings"][0]["implementation_evidence"] = [{"path": path, "sha256": "a" * 64} for path in bad_paths]
        self.write(self.mapping_path.relative_to(self.root), self.map)
        report, diagnostics = self.failed_mapping_report()
        self.assertFalse(diagnostics["mapping_reference_scan_complete"])
        self.assertEqual(len(bad_paths), diagnostics["mapping_reference_error_count"])
        self.assertTrue(all(error["code"] == "reference_path_invalid" and error["reference_path"] is None
                            for error in diagnostics["mapping_reference_errors"]))
        self.assertNotIn("PRIVATE", json.dumps(report))
        self.assertEqual(list(range(len(bad_paths))), report["catalog_visibility"]["rows"][0]["mapping_reference_error_indexes"])

    def test_invalid_mapping_diagnostics_unc_paths_are_redacted(self):
        self.emit()
        bad_paths = ("//PRIVATE-server/share/file", r"\\PRIVATE-server\share\file",
                     "//?/C:/PRIVATE-extended", r"\\?\C:\PRIVATE-extended")
        self.map["mappings"][0]["implementation_evidence"] = [{"path": path, "sha256": "a" * 64} for path in bad_paths]
        self.write(self.mapping_path.relative_to(self.root), self.map)
        report, diagnostics = self.failed_mapping_report()
        self.assertFalse(diagnostics["mapping_reference_scan_complete"])
        self.assertEqual(len(bad_paths), diagnostics["mapping_reference_error_count"])
        self.assertTrue(all(error["code"] == "reference_path_invalid" and error["reference_path"] is None
                            for error in diagnostics["mapping_reference_errors"]))
        self.assertNotIn("PRIVATE", json.dumps(report))
        self.assertEqual(list(range(len(bad_paths))), report["catalog_visibility"]["rows"][0]["mapping_reference_error_indexes"])

    def test_invalid_mapping_diagnostics_traversal_and_absolute_paths_are_redacted(self):
        self.emit()
        bad_paths = ("../PRIVATE-outside", "/PRIVATE-absolute", "src/../PRIVATE-traversal", "src\\PRIVATE-backslash", "PRIVATE\x00nul")
        self.map["mappings"][0]["implementation_evidence"] = [{"path": path, "sha256": "a" * 64} for path in bad_paths]
        self.write(self.mapping_path.relative_to(self.root), self.map)
        report, diagnostics = self.failed_mapping_report()
        self.assertFalse(diagnostics["mapping_reference_scan_complete"])
        self.assertEqual(len(bad_paths), diagnostics["mapping_reference_error_count"])
        self.assertTrue(all(error["code"] == "reference_path_invalid" and error["reference_path"] is None
                            for error in diagnostics["mapping_reference_errors"]))
        self.assertNotIn("PRIVATE", json.dumps(report))
        self.assertEqual(list(range(len(bad_paths))), report["catalog_visibility"]["rows"][0]["mapping_reference_error_indexes"])

    def test_invalid_mapping_diagnostics_broken_reference_objects_and_digests_are_typed(self):
        self.emit()
        safe = self.artifact("src/safe-adapter.kt", b"// safe\n")
        references = [None, ["PRIVATE-list"], "PRIVATE-scalar", {**safe, "sha256": None}, {**safe, "sha256": "PRIVATE-hash"}, 42]
        self.map["mappings"][0]["implementation_evidence"] = references
        self.write(self.mapping_path.relative_to(self.root), self.map)
        report, diagnostics = self.failed_mapping_report()
        self.assertFalse(diagnostics["mapping_reference_scan_complete"])
        self.assertEqual(6, diagnostics["mapping_reference_count"])
        self.assertCountEqual(["reference_object_invalid"] * 4 + ["reference_digest_invalid"] * 2,
                              [error["code"] for error in diagnostics["mapping_reference_errors"]])
        self.assertNotIn("PRIVATE", json.dumps(report))
        self.assertEqual(list(range(6)), report["catalog_visibility"]["rows"][0]["mapping_reference_error_indexes"])

    def test_invalid_mapping_diagnostics_broken_reference_array_is_not_a_zero_error_scan(self):
        self.emit()
        self.map["mappings"][0]["implementation_evidence"] = {"PRIVATE": "not an array"}
        self.write(self.mapping_path.relative_to(self.root), self.map)
        report, diagnostics = self.failed_mapping_report()
        self.assertFalse(diagnostics["mapping_reference_scan_complete"])
        self.assertEqual(0, diagnostics["mapping_reference_count"])
        self.assertEqual(1, diagnostics["mapping_reference_error_count"])
        error = diagnostics["mapping_reference_errors"][0]
        self.assertEqual("reference_array_invalid", error["code"])
        self.assertIsNone(error["reference_index"])
        self.assertEqual([0], report["catalog_visibility"]["rows"][0]["mapping_reference_error_indexes"])
        self.assertNotIn("PRIVATE", json.dumps(report))

    def test_invalid_mapping_diagnostics_malformed_manifest_does_not_hide_later_mismatch(self):
        self.emit()
        late = self.artifact("src/later-clean-input.kt", b"// sealed later input\n")
        self.write("modules/provider-zlater/src/main/resources/later-rule-map.json", {
            "schema_version": 1, "provider_id": "later", "provider_version": "1.0",
            "mappings": [{"rule_id": "ktc.example.rule", "relation": "exact", "implementation_evidence": [late]}],
        })
        (self.root / late["path"]).write_text("changed later input")
        bad_manifests = (
            b"PRIVATE malformed JSON",
            b'{"schema_version":1,"mappings":[],"mappings":[]}',
            b"[]",
            json.dumps({**self.map, "mappings": {"PRIVATE": "not an array"}}).encode(),
        )
        for bad in bad_manifests:
            with self.subTest(manifest_kind=type(bad).__name__):
                self.mapping_path.write_bytes(bad)
                report, diagnostics = self.failed_mapping_report()
                self.assertFalse(diagnostics["mapping_reference_scan_complete"])
                codes = [error["code"] for error in diagnostics["mapping_reference_errors"]]
                self.assertIn("reference_digest_mismatch", codes)
                self.assertEqual(2, len(codes))
                self.assertTrue(any(code in {"mapping_manifest_unavailable", "mapping_manifest_invalid", "mapping_records_invalid"} for code in codes))
                self.assertNotIn("PRIVATE", json.dumps(report))
                self.assertEqual(1, report["catalog_visibility"]["row_count"])

    def test_invalid_mapping_diagnostics_unknown_rule_and_catalog_drift_never_echo_raw_values(self):
        self.emit()
        self.map["mappings"][0]["catalog_record_sha256"] = "0" * 64
        self.map["mappings"].append({"rule_id": "PRIVATE-unknown-rule", "relation": "PRIVATE-invalid-relation"})
        self.write(self.mapping_path.relative_to(self.root), self.map)
        before = self.mapping_path.read_bytes()
        report, diagnostics = self.failed_mapping_report()
        self.assertFalse(diagnostics["mapping_reference_scan_complete"])
        codes = [error["code"] for error in diagnostics["mapping_reference_errors"]]
        self.assertCountEqual(["catalog_reference_digest_mismatch", "mapping_rule_invalid", "mapping_relation_invalid"], codes)
        self.assertEqual([0], report["catalog_visibility"]["rows"][0]["mapping_reference_error_indexes"])
        self.assertTrue(all(error["rule_id"] is None for error in diagnostics["mapping_reference_errors"][1:]))
        self.assertNotIn("PRIVATE", json.dumps(report))
        self.assertEqual(before, self.mapping_path.read_bytes())

    def test_invalid_mapping_diagnostics_outside_input_path_has_no_arbitrary_label(self):
        self.emit()
        report, diagnostics = self.failed_mapping_report("--mapping", "../PRIVATE-mapping.json")
        self.assertFalse(diagnostics["mapping_reference_scan_complete"])
        self.assertEqual(1, diagnostics["mapping_reference_error_count"])
        error = diagnostics["mapping_reference_errors"][0]
        self.assertEqual("mapping_manifest_unavailable", error["code"])
        self.assertIsNone(error["mapping_manifest"])
        self.assertIsNone(error["reference_path"])
        self.assertIsNone(error["rule_id"])
        self.assertEqual([], report["catalog_visibility"]["rows"][0]["mapping_reference_error_indexes"])
        self.assertNotIn("PRIVATE", json.dumps(report))

    def test_invalid_mapping_diagnostics_unknown_catalog_or_context_keeps_unknown_denominators(self):
        self.emit()
        self.write("data/rules-example.json", self.catalog + self.catalog)
        report, diagnostics = self.failed_mapping_report()
        self.assertFalse(diagnostics["mapping_reference_scan_complete"])
        for field in ("declared_catalog_records", "declared_context_count", "declared_row_count"):
            self.assertIsNone(diagnostics[field])
        self.assertIsNone(report["catalog_visibility"]["rows"])
        self.write("data/rules-example.json", self.catalog)
        report, diagnostics = self.failed_mapping_report("--context", str(self.root / "missing-context.json"))
        self.assertEqual(1, diagnostics["declared_catalog_records"])
        self.assertIsNone(diagnostics["declared_context_count"])
        self.assertIsNone(diagnostics["declared_row_count"])
        self.assertIsNone(report["catalog_visibility"]["rows"])

    def test_valid_and_pending_inventory_never_runs_failure_mapping_diagnostics(self):
        self.emit()
        output = self.root / "coverage.json"
        with patch("rule_coverage.mapping_reference_diagnostics", side_effect=AssertionError("failure-only collector called")) as probe:
            self.assertEqual(0, main(["--root", str(self.root), "--output", str(output)]))
            report = read_json(output)
            self.assertTrue(report["inventory_valid"])
            self.assertNotIn("failure_diagnostics", report)
            self.assertEqual(1, main(["--root", str(self.root), "--require-qualified", "--output", str(output)]))
            with patch("rule_coverage.generate_coverage", side_effect=KeyboardInterrupt):
                with self.assertRaises(KeyboardInterrupt):
                    main(["--root", str(self.root), "--output", str(output)])
            probe.assert_not_called()
        pending = read_json(output)
        self.assertEqual("pending", pending["inventory_status"])
        self.assertNotIn("failure_diagnostics", pending)
        self.assertIsNone(pending["catalog_visibility"])

    def test_no_output_failure_never_collects_mapping_diagnostics(self):
        self.emit()
        (self.root / "src/implementation.kt").write_text("stale sidecar source")
        with patch("rule_coverage.mapping_reference_diagnostics") as probe, \
                contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, main(["--root", str(self.root)]))
        probe.assert_not_called()
        self.assertEqual("", out.getvalue())


class Successor1665CoverageTests(unittest.TestCase):
    """New-generation SOURCE/declared-ledger bookkeeping, not old-report validation."""

    def test_current_1665_vector_is_explicit_and_legacy1663_stays_frozen(self):
        root = Path(__file__).resolve().parents[1]
        old = (root / "modules/kernel/src/test/resources/joint-completion-full-catalog-1663.txt").read_bytes()
        new = (root / "modules/kernel/src/test/resources/joint-completion-full-catalog-1665.txt").read_bytes()
        self.assertEqual("4c00ec83059325b8ee2fac672e6c53948746bda6920d900a9d1942ce1b03520d", digest(old))
        self.assertEqual("f7bc5eb975499c10ced7fc10ec7938984f98eb19079f9fcce32d451a8a5de431", digest(new))
        old_ids, new_ids = old.decode().splitlines(), new.decode().splitlines()
        self.assertEqual((1663, 1665), (len(old_ids), len(new_ids)))
        self.assertEqual(sorted(set(new_ids)), new_ids)
        self.assertEqual({"ktc.compiler.common.error_suppression", "ktc.compiler.common.inapplicable_operator_modifier"},
                         set(new_ids) - set(old_ids))
        self.assertTrue(set(old_ids) < set(new_ids))
        compact = json.dumps(new_ids, separators=(",", ":"), ensure_ascii=False).encode()
        self.assertEqual("121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5", digest(compact))

    def test_current_1665_keeps_all_frozen_records_and_two_planned_additions(self):
        root = Path(__file__).resolve().parents[1]
        frozen = frozen_1663_catalog_root(self)
        before, after = {}, {}
        for name in ("core", "ecosystem", "libraries", "platform"):
            relative = "data/rules-" + name + ".json"
            before.update({record["id"]: record for record in json.loads((frozen / relative).read_bytes())})
            after.update({record["id"]: record for record in json.loads((root / relative).read_bytes())})
        self.assertEqual((1663, 1665), (len(before), len(after)))
        self.assertEqual(before, {key: after[key] for key in before})
        additions = {
            "ktc.compiler.common.error_suppression": ("advisory", "probable_defect", "intent_dependent", "warning"),
            "ktc.compiler.common.inapplicable_operator_modifier": ("enabled_when_validated", "correctness", "high", "error"),
        }
        self.assertEqual(set(additions), set(after) - set(before))
        for identifier, expected in additions.items():
            record = after[identifier]
            self.assertEqual("planned", record["status"])
            self.assertEqual(expected, tuple(record[key] for key in ("default_activation", "kind", "certainty", "severity")))

    def test_current_1665_declared_eight_contexts_retain_all_obligations_without_credit(self):
        root = Path(__file__).resolve().parents[1]
        selection = json.loads((root / "providers/coverage/current-inputs-1665-v1.json").read_bytes())
        self.assertEqual("ktchecker/owner-full-catalog/1665-u2/1", selection["contract_id"])
        contexts = []
        for item in selection["contexts"]:
            body = (root / item["path"]).read_bytes()
            self.assertEqual((item["bytes"], item["sha256"]), (len(body), digest(body)))
            contexts.append(json.loads(body))
        # This explicit SOURCE metadata projection never reads provider envelopes,
        # strict mapping references, historical reports or case artifacts.
        visible = catalog_visibility(root, evidence_paths=[], contexts=contexts)
        self.assertEqual((1665, 46, 1619), tuple(visible[key] for key in ("catalog_records", "excluded_records", "candidate_records")))
        self.assertEqual((8, 13320, 368, 12952), tuple(visible[key] for key in ("context_count", "row_count", "excluded_cells", "candidate_cells")))
        ids = {record["rule_id"] for record in visible["catalog_rules"]}
        by_context = {}
        for row in visible["rows"]:
            by_context.setdefault(row["context_sha256"], set()).add(row["rule_id"])
            self.assertIsNone(row["mapped"])
            self.assertFalse(row["implemented"] or row["tested"] or row["qualified"] or row["runtime_default_enabled"])
            self.assertEqual("unavailable", row["evidence_status"])
        self.assertEqual(8, len(by_context))
        self.assertTrue(all(identifiers == ids for identifiers in by_context.values()))
        self.assertFalse(visible["current_coverage_known"] or visible["release_qualified"])


if __name__ == "__main__":
    unittest.main()
