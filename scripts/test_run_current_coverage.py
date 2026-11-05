"""Synthetic selector/coverage-bookkeeping tests, not provider execution evidence.

All generated reports use a fresh three-rule/two-context fixture. The fixed-repo
selection test reads metadata only; it never generates the 1,663-by-eight report.
No subprocesses, Gradle, network, JVM or archived provider runs are invoked.
"""

import contextlib
import copy
import importlib.util
import io
import json
import marshal
import os
from pathlib import Path
import struct
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

import run_current_coverage as current


class CurrentCoverageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="current coverage fixture ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.output = self.root / current.DEFAULT_OUTPUT
        self.catalog = [
            {"id": "ktc.synthetic.a", "default_activation": "enabled_when_validated", "platforms": ["android", "ios", "desktop"]},
            {"id": "ktc.synthetic.b", "default_activation": "excluded"},
            {"id": "ktc.synthetic.c", "default_activation": "opt_in"},
        ]
        self.contexts = [{"id": name, "target": "jvm", "source_set": "synthetic", "kotlin_version": "fixture",
                          "jdk_version": "fixture", "library_versions": {}, "complete_extra_field": {"kept": name}}
                         for name in ("historical", "current")]
        implementation = self.artifact("src/implementation.kt", b"// synthetic bookkeeping reference only\n")
        mapping = {"schema_version": 1, "provider_id": "synthetic", "provider_version": "fixture",
                   "mappings": [{"rule_id": record["id"], "relation": "exact",
                                 "implementation_evidence": [{key: implementation[key] for key in ("path", "sha256")}]}
                                for record in self.catalog]}
        evidence = {"schema_version": 1, "provider_id": "synthetic", "provider_version": "fixture",
                    "context": self.contexts[1], "rules": [
                        {"rule_id": self.catalog[0]["id"], "implemented": True, "tested": False,
                         "qualified": False, "provider_status": "executed", "cases": []}]}
        archived_path = "providers/synthetic-fixtures/evidence/withdrawn.json"
        archived = {"bytes": 20, "sha256": current.sha(b"synthetic old bytes\n")}
        self.withdrawals = {
            "schema": "ktchecker/repository-coverage-withdrawals/1", "withdrawn_provider_rule_context_cells": 1,
            "historical_envelopes": [{"repository_path": archived_path, "selected_for_current_evidence": False,
                                      "original_archive": archived, "integration_variant_at_selection": archived}],
            "cells": [{"rule_id": self.catalog[0]["id"], "context_id": self.contexts[0]["id"],
                       "context_sha256": current.sha(current.canonical(self.contexts[0])),
                       "provider_id": "synthetic", "provider_version": "fixture", "sidecar_path": archived_path,
                       "sidecar_sha256": archived["sha256"], "current_evidence_selected": False,
                       "current_implemented": False, "current_tested": False, "current_qualified": False,
                       "stage_transfer_allowed": False, "scope_reduced": False, "disabled": False,
                       "all_catalog_rule_rows_and_complete_context_retained": True,
                       "historical_declared_stage_claims": {"implemented": True, "tested": True, "qualified": False}}],
        }
        self.selection = {
            "schema": "ktchecker/current-coverage-selection/1",
            "scope": {"catalog_records": 3, "contexts": 2, "rows": 6, "withdrawn_provider_rule_context_cells": 1},
            "catalogs": [self.json_artifact("data/rules-synthetic.json", self.catalog)],
            "mappings": [self.json_artifact("modules/provider-synthetic/src/main/resources/synthetic-rule-map.json", mapping)],
            "selected_evidence": [self.json_artifact("providers/synthetic-fixtures/evidence/current.json", evidence)],
            "reference_files": [implementation],
            "contexts": [{**self.json_artifact("providers/coverage/contexts/" + context["id"] + ".json", context),
                          "context_id": context["id"], "context_sha256": current.sha(current.canonical(context))}
                         for context in self.contexts],
            "withdrawals": self.json_artifact("providers/coverage/withdrawals.json", self.withdrawals),
            "reporter": self.artifact("scripts/rule_coverage.py", Path(__file__).with_name("rule_coverage.py").read_bytes()),
        }
        self.artifact("scripts/run_current_coverage.py", Path(__file__).with_name("run_current_coverage.py").read_bytes())
        self.save_selection()

    def artifact(self, relative, body):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)
        return {"path": relative, "bytes": len(body), "sha256": current.sha(body)}

    def json_artifact(self, relative, value):
        return self.artifact(relative, (json.dumps(value, indent=2, sort_keys=True) + "\n").encode())

    def save_selection(self):
        self.json_artifact(current.SELECTION, self.selection)

    def save_withdrawals(self):
        self.selection["withdrawals"] = self.json_artifact("providers/coverage/withdrawals.json", self.withdrawals)
        self.save_selection()

    def main(self, extra=()):
        with contextlib.redirect_stderr(io.StringIO()), patch.object(
                current, "__file__", str(self.root / "scripts/run_current_coverage.py")):
            return current.main(list(extra))

    def assert_invalid(self):
        self.assertEqual(2, self.main())
        output = json.loads(self.output.read_bytes())
        self.assertIs(output["inventory_valid"], False)
        self.assertEqual("unavailable", output["inventory_status"])
        for key in ("summary", "rows", "row_count"):
            self.assertIsNone(output[key])

    def report(self):
        checked = current.checked_selection(self.root)
        reporter = current.load_reporter(self.root, checked["reporter_source"])
        selection = checked["selection"]
        report = reporter.generate_coverage(self.root,
            [Path(item["path"]) for item in selection["mappings"]],
            [Path(item["path"]) for item in selection["selected_evidence"]], list(checked["contexts"].values()))
        return checked, report

    def test_success_preserves_entire_cartesian_scope_and_no_historical_transfer(self):
        self.assertEqual(0, self.main())
        report = json.loads(self.output.read_bytes())
        self.assertEqual((3, 2, 6), (report["catalog_records"], len(report["contexts"]), report["row_count"]))
        self.assertEqual({"mapped": 6, "implemented": 1, "tested": 0, "qualified": 0}, report["summary"])
        self.assertEqual({"excluded": 2, "implemented_untested": 1, "unimplemented": 3}, report["states"])
        old = next(row for row in report["rows"] if row["rule_id"] == "ktc.synthetic.a" and row["context"]["id"] == "historical")
        self.assertFalse(old["implemented"])
        self.assertEqual("not_executed", old["providers"][0]["provider_status"])
        self.assertFalse(report["release_qualified"])
        self.assertFalse(any(row["runtime_default_enabled"] for row in report["rows"]))

    def test_default_root_and_output_are_independent_of_callers_cwd(self):
        elsewhere = self.root / "elsewhere"
        elsewhere.mkdir()
        old_cwd = Path.cwd()
        try:
            os.chdir(elsewhere)
            with patch.object(current, "__file__", str(self.root / "scripts/run_current_coverage.py")):
                self.assertEqual(0, current.main([]))
        finally:
            os.chdir(old_cwd)
        self.assertTrue(self.output.is_file())
        self.assertFalse((elsewhere / current.DEFAULT_OUTPUT).exists())

    def test_unselected_stale_sidecar_is_not_discovered_or_required(self):
        archive = self.withdrawals["historical_envelopes"][0]["repository_path"]
        self.assertFalse((self.root / archive).exists())
        self.assertEqual(0, self.main())
        self.artifact(archive, b"not JSON; unselected archival body must not be opened")
        self.artifact("providers/synthetic-fixtures/evidence/another-old.json", b"stale")
        self.assertEqual(0, self.main())

    def test_success_invokes_strict_function_once_with_nonempty_explicit_inputs(self):
        checked, expected = self.report()
        generate = Mock(return_value=expected)
        with patch.object(current, "load_reporter", return_value=types.SimpleNamespace(generate_coverage=generate)):
            self.assertEqual(0, self.main())
        generate.assert_called_once_with(self.root,
            [Path(item["path"]) for item in self.selection["mappings"]],
            [Path(item["path"]) for item in self.selection["selected_evidence"]], list(checked["contexts"].values()))

    def test_reporter_uses_checked_source_bytes_not_matching_timestamp_pyc(self):
        source = self.root / "scripts/rule_coverage.py"
        cached = source.parent / "__pycache__" / ("rule_coverage." + sys.implementation.cache_tag + ".pyc")
        cached.parent.mkdir()
        wrong = compile("raise AssertionError('unselected cached code must not execute')", str(source), "exec")
        header = importlib.util.MAGIC_NUMBER + struct.pack("<III", 0, int(source.stat().st_mtime), source.stat().st_size)
        cached.write_bytes(header + marshal.dumps(wrong))
        self.assertEqual(0, self.main())

    def test_pending_invalidates_old_success_before_any_selection_read(self):
        self.json_artifact(current.DEFAULT_OUTPUT, {"inventory_valid": True, "old_counts": 999})
        def reject(_root):
            pending = json.loads(self.output.read_bytes())
            self.assertFalse(pending["inventory_valid"])
            self.assertEqual("pending", pending["inventory_status"])
            raise current.SelectionError("synthetic selection failure")
        with patch.object(current, "checked_selection", side_effect=reject):
            self.assert_invalid()

    def test_missing_selector_fails_without_reporter_or_discovery_fallback(self):
        (self.root / current.SELECTION).unlink()
        with patch.object(current, "load_reporter") as loader:
            self.assert_invalid()
        loader.assert_not_called()

    def test_malformed_duplicate_key_and_nonfinite_selector_fail(self):
        for body in (b"{", b'{"schema": 1, "schema": 2}', b'{"schema": NaN}'):
            with self.subTest(body=body):
                self.artifact(current.SELECTION, body)
                self.assert_invalid()

    def test_empty_explicit_groups_are_rejected_not_discovered(self):
        original = copy.deepcopy(self.selection)
        for name in ("catalogs", "mappings", "selected_evidence", "contexts"):
            with self.subTest(group=name):
                self.selection = copy.deepcopy(original)
                self.selection[name] = []
                self.save_selection()
                self.assert_invalid()

    def test_duplicate_selected_input_path_is_rejected(self):
        self.selection["mappings"].append(copy.deepcopy(self.selection["mappings"][0]))
        self.save_selection()
        self.assert_invalid()

    def test_missing_or_changed_reference_is_rejected_before_reporting(self):
        path = self.root / self.selection["reference_files"][0]["path"]
        for missing in (False, True):
            with self.subTest(missing=missing):
                if missing:
                    path.unlink()
                else:
                    path.write_bytes(b"different bytes")
                with patch.object(current, "load_reporter") as loader:
                    self.assert_invalid()
                loader.assert_not_called()

    def test_changed_selected_evidence_or_reporter_is_rejected(self):
        for item in (self.selection["selected_evidence"][0], self.selection["reporter"]):
            with self.subTest(path=item["path"]):
                path = self.root / item["path"]
                old = path.read_bytes()
                path.write_bytes(old + b" ")
                self.assert_invalid()
                path.write_bytes(old)

    def test_noncanonical_or_wrong_digest_and_size_pins_are_rejected(self):
        original = copy.deepcopy(self.selection)
        for field, value in (("sha256", "A" * 64), ("sha256", "sha256:" + "0" * 64),
                             ("sha256", 1), ("bytes", True), ("bytes", -1),
                             ("bytes", current.MAX_INPUT_BYTES + 1)):
            with self.subTest(field=field, value=value):
                self.selection = copy.deepcopy(original)
                self.selection["mappings"][0][field] = value
                self.save_selection()
                self.assert_invalid()

    def test_reference_closure_rejects_missing_or_extra_pin(self):
        original = copy.deepcopy(self.selection)
        self.selection["reference_files"] = []
        self.save_selection()
        self.assert_invalid()
        self.selection = original
        self.selection["reference_files"].append(self.artifact("src/unused.kt", b"unused"))
        self.save_selection()
        self.assert_invalid()

    def test_additional_catalog_file_is_rejected(self):
        self.json_artifact("data/rules-unselected.json", [])
        self.assert_invalid()

    def test_duplicate_catalog_id_is_rejected_even_with_updated_file_pin(self):
        self.catalog.append(copy.deepcopy(self.catalog[0]))
        self.selection["catalogs"][0] = self.json_artifact("data/rules-synthetic.json", self.catalog)
        self.save_selection()
        self.assert_invalid()

    def test_scope_counts_cannot_drop_rules_contexts_or_withdrawals(self):
        original = copy.deepcopy(self.selection)
        for field, value in (("catalog_records", 2), ("contexts", 1), ("rows", 5),
                             ("withdrawn_provider_rule_context_cells", 0), ("contexts", True), ("rows", -1)):
            with self.subTest(field=field, value=value):
                self.selection = copy.deepcopy(original)
                self.selection["scope"][field] = value
                self.save_selection()
                self.assert_invalid()

    def test_complete_context_digest_includes_extra_metadata_not_just_label(self):
        item = self.selection["contexts"][0]
        context = copy.deepcopy(self.contexts[0])
        context["complete_extra_field"]["kept"] = "changed"
        item.update(self.json_artifact(item["path"], context))
        self.save_selection()
        self.assert_invalid()

    def test_duplicate_complete_context_is_rejected(self):
        item = self.selection["contexts"][1]
        item.update(self.json_artifact(item["path"], self.contexts[0]))
        item["context_id"] = self.contexts[0]["id"]
        item["context_sha256"] = self.selection["contexts"][0]["context_sha256"]
        self.save_selection()
        self.assert_invalid()

    def test_selected_evidence_must_have_a_declared_complete_context(self):
        item = self.selection["selected_evidence"][0]
        document = json.loads((self.root / item["path"]).read_bytes())
        document["context"]["complete_extra_field"]["kept"] = "unselected tuple"
        item.update(self.json_artifact(item["path"], document))
        self.save_selection()
        self.assert_invalid()

    def test_unsafe_repository_paths_are_rejected(self):
        original = copy.deepcopy(self.selection)
        for value in ("/etc/passwd", "../outside", "src/../outside", "src//implementation.kt",
                      "./src/implementation.kt", "C:/outside", "C:outside", "src\\implementation.kt", "", "src/\x00"):
            with self.subTest(path=value):
                self.selection = copy.deepcopy(original)
                self.selection["reference_files"][0]["path"] = value
                self.save_selection()
                self.assert_invalid()

    def test_input_leaf_and_ancestor_symlinks_are_rejected(self):
        for relative in ("src/implementation.kt", "src"):
            with self.subTest(path=relative):
                path = self.root / relative
                original = path.with_name(path.name + ".original")
                path.rename(original)
                try:
                    try:
                        path.symlink_to(original, target_is_directory=original.is_dir())
                    except OSError as error:
                        self.skipTest("symlinks unavailable: " + str(error))
                    self.assert_invalid()
                finally:
                    if path.is_symlink():
                        path.unlink()
                    original.rename(path)

    def test_output_must_be_canonical_relative_json_below_build(self):
        for value in ("../outside.json", "/tmp/outside.json", "data/rules-synthetic.json", "build/../source.json",
                      "build//report.json", "build/./report.json", "build/report.txt", "build", "C:/outside.json"):
            with self.subTest(output=value):
                self.assertEqual(2, self.main(("--output", value)))
        self.assertEqual(self.catalog, json.loads((self.root / "data/rules-synthetic.json").read_bytes()))

    def test_output_symlink_does_not_overwrite_aliased_file(self):
        outside = self.root / "untouched.json"
        outside.write_bytes(b"keep")
        self.output.parent.mkdir(parents=True)
        try:
            self.output.symlink_to(outside)
        except OSError as error:
            self.skipTest("symlinks unavailable: " + str(error))
        self.assertEqual(2, self.main())
        self.assertEqual(b"keep", outside.read_bytes())

    def test_output_ancestor_symlink_does_not_publish_outside_build(self):
        outside = self.root / "not-build"
        outside.mkdir()
        kept = outside / "report.json"
        kept.write_bytes(b"keep")
        alias = self.root / "build/aliased"
        alias.parent.mkdir()
        try:
            alias.symlink_to(outside, target_is_directory=True)
        except OSError as error:
            self.skipTest("symlinks unavailable: " + str(error))
        self.assertEqual(2, self.main(("--output", "build/aliased/report.json")))
        self.assertEqual(b"keep", kept.read_bytes())

    def test_withdrawn_archival_path_cannot_be_reselected(self):
        archive = self.withdrawals["historical_envelopes"][0]["repository_path"]
        body = (self.root / self.selection["selected_evidence"][0]["path"]).read_bytes()
        self.selection["selected_evidence"].append(self.artifact(archive, body))
        self.save_selection()
        self.assert_invalid()

    def test_duplicate_or_unknown_withdrawal_identity_is_rejected(self):
        original = copy.deepcopy(self.withdrawals)
        self.withdrawals["cells"].append(copy.deepcopy(self.withdrawals["cells"][0]))
        self.withdrawals["withdrawn_provider_rule_context_cells"] = 2
        self.selection["scope"]["withdrawn_provider_rule_context_cells"] = 2
        self.save_withdrawals()
        self.assert_invalid()
        self.withdrawals = original
        self.selection["scope"]["withdrawn_provider_rule_context_cells"] = 1
        self.withdrawals["cells"][0]["rule_id"] = "ktc.synthetic.unknown"
        self.save_withdrawals()
        self.assert_invalid()

    def test_withdrawal_cannot_enable_stages_or_reduce_scope(self):
        original = copy.deepcopy(self.withdrawals)
        for field in ("current_evidence_selected", "current_implemented", "current_tested", "current_qualified",
                      "stage_transfer_allowed", "scope_reduced", "disabled"):
            with self.subTest(field=field):
                self.withdrawals = copy.deepcopy(original)
                self.withdrawals["cells"][0][field] = True
                self.save_withdrawals()
                self.assert_invalid()

    def test_reconciliation_rejects_missing_duplicate_or_extra_rows(self):
        checked, original = self.report()
        variants = []
        missing = copy.deepcopy(original)
        missing["rows"].pop()
        missing["row_count"] -= 1
        variants.append(missing)
        duplicate = copy.deepcopy(original)
        duplicate["rows"][-1] = copy.deepcopy(duplicate["rows"][0])
        variants.append(duplicate)
        extra = copy.deepcopy(original)
        extra["rows"][-1]["rule_id"] = "ktc.synthetic.extra"
        variants.append(extra)
        for report in variants:
            with self.subTest(rows=report["row_count"]):
                with self.assertRaises(current.SelectionError):
                    current.reconcile(report, checked)

    def test_reconciliation_rejects_changed_context_catalog_or_activation(self):
        checked, original = self.report()
        for field, value in (("context", {"id": "incomplete"}), ("catalog_record_sha256", "0" * 64),
                             ("default_activation", "excluded"), ("runtime_default_enabled", True), ("mapped", 1)):
            with self.subTest(field=field):
                report = copy.deepcopy(original)
                row = next(row for row in report["rows"] if row["rule_id"] == "ktc.synthetic.a")
                row[field] = value
                with self.assertRaises(current.SelectionError):
                    current.reconcile(report, checked)

    def test_reconciliation_rejects_changed_top_level_context_set(self):
        checked, report = self.report()
        report["contexts"][0] = {**report["contexts"][0], "unexpected": "not the selected complete tuple"}
        with self.assertRaises(current.SelectionError):
            current.reconcile(report, checked)

    def test_reconciliation_rejects_any_withdrawn_provider_stage_or_sidecar(self):
        checked, original = self.report()
        for field, value in (("implemented", True), ("tested", True), ("qualified", True),
                             ("evidence_sidecar", "providers/synthetic-fixtures/evidence/withdrawn.json"),
                             ("provider_status", "executed")):
            with self.subTest(field=field):
                report = copy.deepcopy(original)
                row = next(row for row in report["rows"] if row["rule_id"] == "ktc.synthetic.a" and row["context"]["id"] == "historical")
                row["providers"][0][field] = value
                with self.assertRaises(current.SelectionError):
                    current.reconcile(report, checked)

    def test_reconciliation_rejects_removed_or_duplicate_withdrawn_provider(self):
        checked, original = self.report()
        for duplicate in (False, True):
            with self.subTest(duplicate=duplicate):
                report = copy.deepcopy(original)
                row = next(row for row in report["rows"] if row["rule_id"] == "ktc.synthetic.a" and row["context"]["id"] == "historical")
                row["providers"] = row["providers"] * 2 if duplicate else []
                with self.assertRaises(current.SelectionError):
                    current.reconcile(report, checked)

    def test_reconciliation_rejects_disagreeing_totals_or_qualification(self):
        checked, original = self.report()
        for field, value in (("summary", {}), ("states", {}), ("release_qualified", True),
                             ("all_requested_cells_qualified", True), ("inventory_valid", False)):
            with self.subTest(field=field):
                report = copy.deepcopy(original)
                report[field] = value
                with self.assertRaises(current.SelectionError):
                    current.reconcile(report, checked)

    def test_reporter_exception_leaves_invalid_output(self):
        self.json_artifact(current.DEFAULT_OUTPUT, {"inventory_valid": True})
        generate = Mock(side_effect=RuntimeError("synthetic reporter failure"))
        with patch.object(current, "load_reporter", return_value=types.SimpleNamespace(generate_coverage=generate)):
            self.assert_invalid()
        generate.assert_called_once()

    def test_reconciliation_failure_never_publishes_valid_report(self):
        checked, report = self.report()
        report["rows"][-1] = copy.deepcopy(report["rows"][0])
        generate = Mock(return_value=report)
        with patch.object(current, "load_reporter", return_value=types.SimpleNamespace(generate_coverage=generate)):
            self.assert_invalid()

    def test_input_or_selector_mutation_during_reporting_is_rejected(self):
        checked, report = self.report()
        original_input = (self.root / self.selection["reference_files"][0]["path"]).read_bytes()
        original_selection = (self.root / current.SELECTION).read_bytes()
        for relative in (self.selection["reference_files"][0]["path"], current.SELECTION):
            with self.subTest(path=relative):
                def generate(*_args):
                    path = self.root / relative
                    path.write_bytes(path.read_bytes() + b" ")
                    return report
                with patch.object(current, "load_reporter", return_value=types.SimpleNamespace(generate_coverage=generate)):
                    self.assert_invalid()
                (self.root / self.selection["reference_files"][0]["path"]).write_bytes(original_input)
                (self.root / current.SELECTION).write_bytes(original_selection)

    def test_atomic_valid_publication_failure_does_not_retain_old_success(self):
        self.json_artifact(current.DEFAULT_OUTPUT, {"inventory_valid": True})
        original_replace = os.replace
        def replace(source, target):
            if json.loads(Path(source).read_bytes()).get("inventory_valid") is True:
                raise OSError("synthetic final publication failure")
            return original_replace(source, target)
        with patch.object(current.os, "replace", side_effect=replace):
            self.assert_invalid()
        self.assertEqual([], list(self.output.parent.glob(".ktchecker-current-coverage-*.tmp")))


class FixedCurrentSelectionMetadataTests(unittest.TestCase):
    def test_fixed_selection_retains_all_scope_identities_and_no_private_paths(self):
        root = Path(__file__).resolve().parents[1]
        selection = json.loads((root / current.SELECTION).read_bytes())
        self.assertEqual({"catalog_records": 1663, "contexts": 8, "rows": 13304,
                          "withdrawn_provider_rule_context_cells": 450}, selection["scope"])
        self.assertEqual([4, 6, 5, 35], [len(selection[name]) for name in ("catalogs", "mappings", "selected_evidence", "reference_files")])
        self.assertEqual("dacd925b0acf586b50f62efa14e76cb51811ccc5e2d9151f9c4f3b223888da44", selection["reporter"]["sha256"])
        identities = {
            "94912ebf56905d89484e11dc51af8e7887d92fdaec9db13db5af776943caf91f",
            "f1160d18fa35a92f788a8f1cdb430ff8f8be724a21a2935fafb1ae47a0677242",
            "8011124690347fa1d70b8b980a8dbf43ae7b6268223742626ef801a9b033cfcd",
            "57467ee86e3c708733009baf9fbf12c01089de33bea47738d4f0f069c02d4400",
            "e3e18aef6515d7d7e46cc77f46f3a49f0c655e5cea684039a4f319b604c50a91",
            "ee1e7854d2bd55474a766802d26a0912523565540d701aa8ba766c0645dd526f",
            "0da96fde4587a338e4d167b68cf02ff4c2ebc0a218cd750747890e66bb374359",
            "764d9187526fb34921e03cf118e45fa8c6cd30ade244cca3291c4a8ba5f9fef3",
        }
        self.assertEqual(identities, {item["context_sha256"] for item in selection["contexts"]})
        for item in selection["contexts"]:
            body = (root / item["path"]).read_bytes()
            self.assertEqual((item["bytes"], item["sha256"]), (len(body), current.sha(body)))
            self.assertEqual(item["context_sha256"], current.sha(current.canonical(json.loads(body))))
        item = selection["withdrawals"]
        body = (root / item["path"]).read_bytes()
        self.assertEqual((item["bytes"], item["sha256"]), (len(body), current.sha(body)))
        withdrawals = json.loads(body)
        self.assertEqual(450, len(withdrawals["cells"]))
        self.assertEqual({"kotlin-compiler-jvm": 443, "detekt": 3, "ktlint": 4},
                         dict(current.Counter(cell["provider_id"] for cell in withdrawals["cells"])))
        for document in (selection, withdrawals):
            serialized = json.dumps(document)
            self.assertNotIn("/tmp/", serialized)
            self.assertNotIn("/root/", serialized)
        variants = next(item for item in withdrawals["historical_envelopes"]
                        if item["repository_path"] == "providers/compiler-fixtures/evidence/compiler-jvm-2.4.21.json")
        self.assertNotEqual(variants["original_archive"]["sha256"], variants["integration_variant_at_selection"]["sha256"])


if __name__ == "__main__":
    unittest.main()
