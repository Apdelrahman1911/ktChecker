"""Synthetic selector/coverage-bookkeeping tests, not provider execution evidence.

Legacy generated reports use a fresh three-rule/two-context fixture; the explicit
successor tests use separate five-rule/two-context bookkeeping fixtures. Fixed-repo
selection tests read metadata only, never the historical 1,663-by-eight report.
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
import run_declared_coverage as declared


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
        self.assertEqual([4, 6, 5, 47], [len(selection[name]) for name in ("catalogs", "mappings", "selected_evidence", "reference_files")])
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


class DeclaredLedgerSuccessorTests(unittest.TestCase):
    """Synthetic new-generation controls, not execution of any historical provider."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="declared ledger fixture ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.output = self.root / declared.DEFAULT_OUTPUT
        self.catalogs = {
            "data/rules-core.json": [
                {"id": "ktc.synthetic.a", "status": "planned", "default_activation": "enabled_when_validated"},
                {"id": "ktc.synthetic.d", "status": "planned", "default_activation": "advisory"},
                {"id": "ktc.synthetic.e", "status": "planned", "default_activation": "enabled_when_validated"},
            ],
            "data/rules-ecosystem.json": [{"id": "ktc.synthetic.b", "default_activation": "excluded"}],
            "data/rules-libraries.json": [{"id": "ktc.synthetic.c", "default_activation": "opt_in"}],
            "data/rules-platform.json": [],
        }
        self.added = {"ktc.synthetic.d", "ktc.synthetic.e"}
        self.contexts = [{"id": name, "target": "jvm", "source_set": "synthetic", "kotlin_version": "fixture",
                          "jdk_version": "fixture", "library_versions": {}, "extra_identity": {"kept": name}}
                         for name in ("historical", "current")]
        context_pins = [{**self.json_artifact("providers/coverage/contexts/" + context["id"] + ".json", context),
                         "context_id": context["id"], "context_sha256": declared.sha(declared.canonical(context))}
                        for context in self.contexts]
        archive = "providers/synthetic-fixtures/evidence/withdrawn.json"
        archive_pin = {"bytes": len(b"not read\n"), "sha256": declared.sha(b"not read\n")}
        self.withdrawals = {
            "schema": "ktchecker/repository-coverage-withdrawals/1", "withdrawn_provider_rule_context_cells": 1,
            "historical_envelopes": [{"repository_path": archive, "selected_for_current_evidence": False,
                                      "original_archive": archive_pin, "integration_variant_at_selection": archive_pin}],
            "cells": [{"rule_id": "ktc.synthetic.a", "context_id": self.contexts[0]["id"],
                       "context_sha256": context_pins[0]["context_sha256"], "provider_id": "synthetic",
                       "provider_version": "fixture", "sidecar_path": archive, "sidecar_sha256": archive_pin["sha256"],
                       "current_evidence_selected": False, "current_implemented": False, "current_tested": False,
                       "current_qualified": False, "stage_transfer_allowed": False, "scope_reduced": False,
                       "disabled": False, "all_catalog_rule_rows_and_complete_context_retained": True,
                       "historical_declared_stage_claims": {"implemented": True, "tested": True, "qualified": False}}],
        }
        reporter_pin = self.artifact(declared.FIXED_FILES["reporter"], Path(__file__).with_name("rule_coverage.py").read_bytes())
        runner_pin = self.artifact(declared.FIXED_FILES["runner"], Path(__file__).with_name("run_declared_coverage.py").read_bytes())
        withdrawal_pin = self.json_artifact(declared.FIXED_FILES["withdrawals"], self.withdrawals)
        # Read the frozen selector metadata only. Its five envelopes, six maps and
        # 47 reference paths are intentionally absent from this synthetic root.
        repository = Path(__file__).resolve().parents[1]
        self.legacy = json.loads((repository / current.SELECTION).read_bytes())
        self.legacy.update({"scope": {"catalog_records": 3, "contexts": 2, "rows": 6,
                                      "withdrawn_provider_rule_context_cells": 1},
                            "contexts": context_pins, "withdrawals": withdrawal_pin, "reporter": reporter_pin})
        self.legacy["catalogs"] = [self.json_artifact(path, [r for r in records if r["id"] not in self.added])
                                   for path, records in self.catalogs.items()]
        legacy_pin = self.json_artifact(declared.LEGACY_SELECTION_PIN["path"], self.legacy)
        catalog_pins = [self.json_artifact(path, records) for path, records in self.catalogs.items()]
        record_rows = sorted(({
            "rule_id": r["id"], "catalog_path": path, "catalog_record_sha256": declared.sha(declared.canonical(r)),
            "default_activation": r.get("default_activation", "unknown"),
        } for path, records in self.catalogs.items() for r in records), key=lambda r: r["rule_id"])
        ids = [row["rule_id"] for row in record_rows]
        old_rows = [row for row in record_rows if row["rule_id"] not in self.added]
        old_ids = [row["rule_id"] for row in old_rows]
        old_records_sha = declared.sha(declared.canonical([[row["rule_id"], row["catalog_record_sha256"]] for row in old_rows]))
        self.scope = {"catalog_records": 5, "contexts": 2, "rows": 10, "withdrawn_provider_rule_context_cells": 1}
        constants = patch.multiple(declared, CONTRACT_ID="synthetic/declared-ledger-test/1", EXPECTED_SCOPE=self.scope,
                                   IDS_SHA256=declared.sha(declared.canonical(ids)), ADDED_RULE_IDS=frozenset(self.added),
                                   PREDECESSOR_IDS_SHA256=declared.sha(declared.canonical(old_ids)),
                                   PREDECESSOR_RECORDS_SHA256=old_records_sha, LEGACY_SELECTION_PIN=legacy_pin)
        constants.start()
        self.addCleanup(constants.stop)
        baseline = {"schema": "ktchecker/catalog-record-baseline/1", "contract_id": declared.CONTRACT_ID,
                    "catalog_records": 5, "ids_sha256": declared.IDS_SHA256, "records": record_rows,
                    "added_rule_ids": sorted(self.added), "predecessor": {"catalog_records": 3,
                    "ids_sha256": declared.PREDECESSOR_IDS_SHA256, "record_identities_sha256": old_records_sha}}
        disposition = {"schema": "ktchecker/legacy-coverage-evidence-disposition/1",
                       "legacy_selection_sha256": legacy_pin["sha256"], "disposition": "unavailable_in_declared_only_successor",
                       "all_catalog_context_obligations_retained": True, "current_evidence_selected": False,
                       "stage_transfer_allowed": False, "scope_reduced": False, "rule_disabling_authorized": False,
                       "denied_reference_paths": sorted(declared.DENIED_REFERENCE_PATHS),
                       **{name: copy.deepcopy(self.legacy[name]) for name in ("selected_evidence", "reference_files", "mappings")}}
        historical = {"schema": "ktchecker/historical-inventory-reference/1", "status": "source_reference_unavailable",
                      "report_path": "build/historical/never-read.json", "report_sha256": declared.sha(b"body not supplied"),
                      "source_sha256": None, "source_binding_verified": False, "implemented_cell_identities": None,
                      "historical_report_read": False, "current_stage_transfer_allowed": False, "current_counts": None,
                      "historical_scope": {"catalog_records": 3, "contexts": 2, "rows": 6},
                      "historical_claims": {"mapped": 4, "implemented": 1, "tested": 0, "qualified": 0,
                                            "runtime_defaults_enabled": False}}
        self.documents = {"baseline": baseline, "withdrawals": self.withdrawals, "legacy_disposition": disposition,
                          "historical_inventory_reference": historical}
        self.selection = {"schema": "ktchecker/declared-coverage-ledger-selection/1", "contract_id": declared.CONTRACT_ID,
                          "scope": dict(self.scope), "catalogs": catalog_pins, "contexts": context_pins,
                          "legacy_selection": legacy_pin, "reporter": reporter_pin, "runner": runner_pin,
                          "selected_evidence": [], "selected_reference_files": [], "selection_note": "Synthetic bookkeeping only."}
        for name, document in self.documents.items():
            self.selection[name] = self.json_artifact(declared.FIXED_FILES[name], document)
        self.save_selection()

    def artifact(self, relative, body):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)
        return {"path": relative, "bytes": len(body), "sha256": declared.sha(body)}

    def json_artifact(self, relative, value):
        return self.artifact(relative, (json.dumps(value, sort_keys=True, indent=2) + "\n").encode())

    def save_selection(self):
        self.json_artifact(declared.SELECTION, self.selection)

    def save_document(self, name):
        self.selection[name] = self.json_artifact(declared.FIXED_FILES[name], self.documents[name])
        self.save_selection()

    def save_catalog(self, path="data/rules-core.json"):
        item = self.json_artifact(path, self.catalogs[path])
        self.selection["catalogs"] = [item if p["path"] == path else p for p in self.selection["catalogs"]]
        self.save_selection()

    def main(self, extra=()):
        with contextlib.redirect_stderr(io.StringIO()), patch.object(
                declared, "__file__", str(self.root / declared.FIXED_FILES["runner"])):
            return declared.main(list(extra))

    def assert_unavailable(self):
        self.assertEqual(2, self.main())
        output = json.loads(self.output.read_bytes())
        self.assertFalse(output["ledger_valid"] or output["inventory_valid"] or output["current_coverage_known"])
        self.assertEqual("unavailable", output["ledger_status"])
        for key in ("summary", "states", "rows", "row_count", "catalog_visibility"):
            self.assertIsNone(output[key])

    def visibility(self):
        checked = declared.checked_inputs(self.root)
        reporter = declared.load_visibility_reporter(self.root, checked["documents"]["reporter"])
        visible = reporter.catalog_visibility(self.root, evidence_paths=[], contexts=list(checked["contexts"].values()))
        return checked, visible

    def test_success_is_complete_declared_scope_but_never_current_coverage_or_zero_claim(self):
        self.assertEqual(0, self.main())
        output = json.loads(self.output.read_bytes())
        self.assertEqual("ktchecker_declared_coverage_ledger", output["kind"])
        self.assertTrue(output["ledger_valid"])
        self.assertFalse(output["inventory_valid"] or output["current_coverage_known"] or output["release_qualified"])
        for key in ("summary", "rows", "catalog_records", "row_count", "contexts", "states"):
            self.assertIsNone(output[key])
        visible = output["catalog_visibility"]
        self.assertEqual((5, 2, 10, 2), (visible["catalog_records"], visible["context_count"], visible["row_count"], visible["excluded_cells"]))
        self.assertEqual(10, len({(row["rule_id"], row["context_sha256"]) for row in visible["rows"]}))
        for row in visible["rows"]:
            self.assertEqual("current_evidence_unavailable", row["state"])
            self.assertTrue(row["obligation_retained"])
            self.assertIsNone(row["mapped"])
            self.assertFalse(any(row[key] for key in (*declared.STAGES, "runtime_default_enabled", "scope_reduced", "rule_disabled_by_this_ledger")))
        self.assertEqual(2, sum(row["catalog_state"] == "excluded" for row in visible["rows"]))
        self.assertIsNone(output["source_binding"]["whole_worktree_sha256"])
        self.assertEqual(self.documents["historical_inventory_reference"], output["historical_inventory_reference"])
        self.assertEqual(self.withdrawals, output["legacy_withdrawals"])

    def test_legacy_targets_are_not_opened_hashed_statted_or_required(self):
        forbidden = {self.root / item["path"] for name in ("selected_evidence", "reference_files", "mappings")
                     for item in self.legacy[name]}
        forbidden.add(self.root / self.documents["historical_inventory_reference"]["report_path"])
        forbidden.add(self.root / self.withdrawals["historical_envelopes"][0]["repository_path"])
        for path in forbidden:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"synthetic forbidden target, not a historical artifact\n")
        original_open, original_stat = Path.open, Path.stat

        def guarded_open(path, *args, **kwargs):
            self.assertNotIn(path, forbidden, "opaque historical target opened")
            return original_open(path, *args, **kwargs)

        def guarded_stat(path, *args, **kwargs):
            self.assertNotIn(path, forbidden, "opaque historical target statted")
            return original_stat(path, *args, **kwargs)

        with patch.object(Path, "open", guarded_open), patch.object(Path, "stat", guarded_stat):
            self.assertEqual(0, self.main())
        output = json.loads(self.output.read_bytes())
        self.assertFalse({self.root / p for p in output["source_binding"]["inputs"]} & forbidden)

    def test_only_explicit_visibility_is_called_not_strict_generator_or_reference_scan(self):
        checked, visible = self.visibility()
        projection = Mock(return_value=visible)
        strict = Mock(side_effect=AssertionError("strict evidence generator forbidden"))
        diagnostics = Mock(side_effect=AssertionError("reference scan forbidden"))
        with patch.object(declared, "load_visibility_reporter", return_value=types.SimpleNamespace(
                catalog_visibility=projection, generate_coverage=strict, mapping_reference_diagnostics=diagnostics)):
            self.assertEqual(0, self.main())
        projection.assert_called_once_with(self.root, evidence_paths=[], contexts=list(checked["contexts"].values()))
        strict.assert_not_called()
        diagnostics.assert_not_called()

    def test_legacy_catalog_count_is_rejected_not_implicitly_extended(self):
        self.catalogs["data/rules-core.json"] = self.catalogs["data/rules-core.json"][:1]
        self.save_catalog()
        self.assert_unavailable()

    def test_same_count_replacement_or_duplicate_cannot_hide_an_omitted_id(self):
        original = copy.deepcopy(self.catalogs["data/rules-core.json"])
        for replacement in ("ktc.synthetic.unapproved", "ktc.synthetic.a"):
            with self.subTest(replacement=replacement):
                self.catalogs["data/rules-core.json"] = copy.deepcopy(original)
                self.catalogs["data/rules-core.json"][-1]["id"] = replacement
                self.save_catalog()
                self.assert_unavailable()

    def test_changed_catalog_record_cannot_be_hidden_by_refreshing_its_file_pin(self):
        self.catalogs["data/rules-core.json"][0]["status"] = "implemented"
        self.save_catalog()
        self.assert_unavailable()

    def test_even_rewritten_baseline_cannot_rebase_old_record_identity_contract(self):
        self.catalogs["data/rules-core.json"][0]["status"] = "edited old source"
        self.save_catalog()
        baseline = self.documents["baseline"]
        baseline["records"][0]["catalog_record_sha256"] = declared.sha(declared.canonical(self.catalogs["data/rules-core.json"][0]))
        baseline["predecessor"]["record_identities_sha256"] = declared.sha(declared.canonical(
            [[row["rule_id"], row["catalog_record_sha256"]] for row in baseline["records"] if row["rule_id"] not in self.added]))
        self.save_document("baseline")
        self.assert_unavailable()

    def test_missing_changed_or_oversized_pinned_inputs_fail_before_projection(self):
        originals = {self.selection[name]["path"]: (self.root / self.selection[name]["path"]).read_bytes()
                     for name in ("baseline", "reporter", "runner", "withdrawals", "historical_inventory_reference")}
        for relative, body in originals.items():
            with self.subTest(relative=relative):
                (self.root / relative).write_bytes(body + b"changed\n")
                with patch.object(declared, "load_visibility_reporter") as reporter:
                    self.assert_unavailable()
                reporter.assert_not_called()
                (self.root / relative).write_bytes(body)
        original = copy.deepcopy(self.selection)
        for field, value in (("bytes", True), ("bytes", declared.MAX_INPUT_BYTES + 1),
                             ("sha256", "A" * 64), ("sha256", "0" * 64)):
            self.selection = copy.deepcopy(original)
            self.selection["baseline"][field] = value
            self.save_selection()
            self.assert_unavailable()
        self.selection = original
        self.save_selection()
        (self.root / self.selection["baseline"]["path"]).unlink()
        self.assert_unavailable()

    def test_added_catalog_is_not_discovered_as_a_new_scope(self):
        self.json_artifact("data/rules-surprise.json", [])
        self.assert_unavailable()

    def test_empty_or_changed_scope_is_not_accepted(self):
        original = copy.deepcopy(self.selection)
        for key, value in (("catalogs", []), ("contexts", []), ("scope", {**self.scope, "rows": 9}),
                           ("scope", {**self.scope, "withdrawn_provider_rule_context_cells": True})):
            with self.subTest(key=key, value=value):
                self.selection = copy.deepcopy(original)
                self.selection[key] = value
                self.save_selection()
                self.assert_unavailable()

    def test_selector_cannot_reselect_any_case_reference_or_evidence_envelope(self):
        original = copy.deepcopy(self.selection)
        for key in ("selected_evidence", "selected_reference_files"):
            self.selection = copy.deepcopy(original)
            self.selection[key] = [self.legacy["selected_evidence"][0]]
            self.save_selection()
            with patch.object(declared, "load_visibility_reporter") as reporter:
                self.assert_unavailable()
            reporter.assert_not_called()

    def test_frozen_selector_cannot_be_replaced_or_reinterpreted(self):
        legacy = copy.deepcopy(self.legacy)
        legacy["selected_evidence"] = []
        self.json_artifact(declared.LEGACY_SELECTION_PIN["path"], legacy)
        self.assert_unavailable()

    def test_entire_context_metadata_is_pinned_not_just_its_label(self):
        item = self.selection["contexts"][0]
        changed = {**self.contexts[0], "extra_identity": {"new": True}}
        replacement = self.json_artifact(item["path"], changed)
        self.selection["contexts"][0] = {**item, **replacement, "context_sha256": declared.sha(declared.canonical(changed))}
        self.save_selection()
        self.assert_unavailable()

    def test_duplicate_or_missing_complete_context_cannot_reduce_obligations(self):
        original = copy.deepcopy(self.selection)
        for replacement in ([original["contexts"][0]] * 2, original["contexts"][:1]):
            self.selection = copy.deepcopy(original)
            self.selection["contexts"] = replacement
            self.save_selection()
            self.assert_unavailable()

    def test_unsafe_pins_and_catalog_symlinks_are_rejected(self):
        original = copy.deepcopy(self.selection)
        for path in ("/tmp/not-a-ledger-input", "../escape", "data/../rules-core.json", "C:/outside", "data\\rules-core.json"):
            self.selection = copy.deepcopy(original)
            self.selection["catalogs"][0]["path"] = path
            self.save_selection()
            self.assert_unavailable()
        self.selection = original
        self.save_selection()
        source = self.root / "data/rules-core.json"
        target = self.root / "old-core.txt"
        source.rename(target)
        source.symlink_to(target)
        self.assert_unavailable()

    def test_missing_malformed_duplicate_key_or_nonfinite_selector_never_falls_back(self):
        for body in (None, b"not JSON", b'{"schema":1,"schema":2}', b'{"x":NaN}'):
            if body is None:
                (self.root / declared.SELECTION).unlink()
            else:
                self.artifact(declared.SELECTION, body)
            with patch.object(declared, "load_visibility_reporter") as reporter:
                self.assert_unavailable()
            reporter.assert_not_called()

    def test_unknown_selector_fields_do_not_enable_alternate_root_or_discovery(self):
        self.selection["root"] = "/tmp/unselected-root"
        self.save_selection()
        self.assert_unavailable()

    def test_default_repository_is_cwd_independent_and_no_selector_override_is_exposed(self):
        elsewhere = self.root / "different-cwd"
        elsewhere.mkdir()
        previous = Path.cwd()
        try:
            os.chdir(elsewhere)
            self.assertEqual(0, self.main())
        finally:
            os.chdir(previous)
        self.assertTrue(self.output.is_file())
        self.assertFalse((elsewhere / declared.DEFAULT_OUTPUT).exists())
        with self.assertRaises(SystemExit) as error:
            self.main(("--selection", "providers/unapproved.json"))
        self.assertEqual(2, error.exception.code)

    def test_removed_legacy_metadata_or_denied_reference_remains_a_visible_error(self):
        original = copy.deepcopy(self.documents["legacy_disposition"])
        for key in ("selected_evidence", "reference_files", "mappings", "denied_reference_paths"):
            self.documents["legacy_disposition"] = copy.deepcopy(original)
            self.documents["legacy_disposition"][key] = []
            self.save_document("legacy_disposition")
            self.assert_unavailable()

    def test_legacy_disposition_cannot_grant_stages_disable_rules_or_reduce_scope(self):
        original = copy.deepcopy(self.documents["legacy_disposition"])
        for key in ("current_evidence_selected", "stage_transfer_allowed", "scope_reduced", "rule_disabling_authorized"):
            self.documents["legacy_disposition"] = copy.deepcopy(original)
            self.documents["legacy_disposition"][key] = True
            self.save_document("legacy_disposition")
            self.assert_unavailable()

    def test_all_withdrawn_cell_identities_and_false_stage_flags_are_checked(self):
        checked = declared.checked_inputs(self.root)
        for key in ("current_implemented", "current_tested", "current_qualified", "stage_transfer_allowed", "scope_reduced", "disabled"):
            changed = copy.deepcopy(self.withdrawals)
            changed["cells"][0][key] = True
            with self.assertRaises(declared.LedgerError):
                declared.validate_withdrawals(changed, self.legacy, checked["records"], checked["contexts"])
        for cells in ([], self.withdrawals["cells"] * 2):
            changed = {**self.withdrawals, "cells": cells}
            with self.assertRaises(declared.LedgerError):
                declared.validate_withdrawals(changed, self.legacy, checked["records"], checked["contexts"])

    def test_unknown_historical_source_and_implemented_identities_cannot_be_filled_or_transferred(self):
        original = copy.deepcopy(self.documents["historical_inventory_reference"])
        for key, value in (("source_sha256", "a" * 64), ("implemented_cell_identities", []),
                           ("source_binding_verified", True), ("historical_report_read", True),
                           ("current_stage_transfer_allowed", True), ("current_counts", {"implemented": 1})):
            self.documents["historical_inventory_reference"] = copy.deepcopy(original)
            self.documents["historical_inventory_reference"][key] = value
            self.save_document("historical_inventory_reference")
            self.assert_unavailable()

    def test_reconciliation_rejects_missing_duplicate_and_extra_cells(self):
        checked, visible = self.visibility()
        for mode in ("missing", "duplicate", "extra"):
            changed = copy.deepcopy(visible)
            if mode == "missing":
                changed["rows"].pop()
            elif mode == "duplicate":
                changed["rows"][-1] = copy.deepcopy(changed["rows"][0])
            else:
                changed["rows"][-1]["rule_id"] = "ktc.synthetic.outside"
            with self.assertRaises(declared.LedgerError):
                declared.reconcile_visibility(changed, checked)

    def test_reconciliation_rejects_any_credit_reference_or_activation(self):
        checked, visible = self.visibility()
        for key, value in (("mapped", True), ("implemented", True), ("tested", True), ("qualified", True),
                           ("runtime_default_enabled", True), ("cases", []), ("providers", []),
                           ("mapping_reference_error_indexes", [0]), ("evidence_status", "valid")):
            changed = copy.deepcopy(visible)
            changed["rows"][0][key] = value
            with self.assertRaises(declared.LedgerError):
                declared.reconcile_visibility(changed, checked)
        for key in ("evidence_valid", "current_coverage_known", "release_qualified", "all_requested_cells_qualified"):
            changed = copy.deepcopy(visible)
            changed[key] = True
            with self.assertRaises(declared.LedgerError):
                declared.reconcile_visibility(changed, checked)

    def test_reconciliation_rejects_changed_context_catalog_and_disposition_totals(self):
        checked, visible = self.visibility()
        variants = []
        changed = copy.deepcopy(visible)
        changed["contexts"][0]["extra_identity"] = {"altered": True}
        variants.append(changed)
        changed = copy.deepcopy(visible)
        changed["catalog_rules"][0]["default_activation"] = "excluded"
        variants.append(changed)
        changed = copy.deepcopy(visible)
        changed["rows"][0]["catalog_record_sha256"] = "0" * 64
        variants.append(changed)
        changed = copy.deepcopy(visible)
        changed["excluded_cells"] = 0
        variants.append(changed)
        for value in variants:
            with self.assertRaises(declared.LedgerError):
                declared.reconcile_visibility(value, checked)

    def test_pending_precedes_input_reads_and_never_contains_yesterdays_scope(self):
        self.json_artifact(declared.DEFAULT_OUTPUT, {"ledger_valid": True, "old_counts": 999})

        def reject(_root):
            pending = json.loads(self.output.read_bytes())
            self.assertEqual("pending", pending["ledger_status"])
            self.assertFalse(pending["ledger_valid"])
            self.assertIsNone(pending["catalog_visibility"])
            self.assertNotIn("old_counts", pending)
            raise declared.LedgerError("synthetic rejection")

        with patch.object(declared, "checked_inputs", side_effect=reject):
            self.assert_unavailable()

    def test_projection_exception_or_reconciliation_error_never_publishes_valid_ledger(self):
        with patch.object(declared, "load_visibility_reporter", side_effect=RuntimeError("synthetic failure")):
            self.assert_unavailable()
        with patch.object(declared, "reconcile_visibility", side_effect=declared.LedgerError("synthetic mismatch")):
            self.assert_unavailable()

    def test_interruption_leaves_pending_without_a_current_coverage_claim(self):
        with patch.object(declared, "generate_ledger", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.main()
        pending = json.loads(self.output.read_bytes())
        self.assertEqual("pending", pending["ledger_status"])
        self.assertFalse(pending["inventory_valid"] or pending["ledger_valid"])
        self.assertIsNone(pending["summary"])

    def test_source_and_selector_mutation_during_generation_fail_closed(self):
        generate = declared.generate_ledger
        original = {relative: (self.root / relative).read_bytes() for relative in (declared.SELECTION, "data/rules-core.json")}
        for relative in original:
            def mutate(root, checked):
                output = generate(root, checked)
                (root / relative).write_bytes(b"mutated after preflight\n")
                return output

            with patch.object(declared, "generate_ledger", side_effect=mutate):
                self.assert_unavailable()
            (self.root / relative).write_bytes(original[relative])

    def test_checked_reporter_source_not_timestamp_matching_pyc_is_executed(self):
        source = self.root / declared.FIXED_FILES["reporter"]
        cached = source.parent / "__pycache__" / ("rule_coverage." + sys.implementation.cache_tag + ".pyc")
        cached.parent.mkdir()
        wrong = compile("raise AssertionError('unselected pyc must not execute')", str(source), "exec")
        header = importlib.util.MAGIC_NUMBER + struct.pack("<III", 0, int(source.stat().st_mtime), source.stat().st_size)
        cached.write_bytes(header + marshal.dumps(wrong))
        self.assertEqual(0, self.main())

    def test_output_is_dedicated_and_cannot_overwrite_legacy_inventory(self):
        old_path = "build/ci-evidence/current-coverage-integrated-20261105-r1/inventory.json"
        old = self.artifact(old_path, b"preserve historical bytes\n")
        for path in (old_path, "build/reports/rule-coverage.json", "../escape.json", "/tmp/ledger.json",
                     declared.OUTPUT_DIRECTORY + "/../other.json", declared.OUTPUT_DIRECTORY + "/not-json.txt"):
            self.assertEqual(2, self.main(("--output", path)))
        self.assertEqual(old["sha256"], declared.sha((self.root / old_path).read_bytes()))
        self.assertFalse(self.output.exists())

    def test_output_leaf_and_ancestor_symlinks_do_not_publish_outside_dedicated_directory(self):
        target = self.root / "keep.json"
        target.write_bytes(b"keep")
        self.output.parent.mkdir(parents=True)
        self.output.symlink_to(target)
        self.assertEqual(2, self.main())
        self.assertEqual(b"keep", target.read_bytes())
        self.output.unlink()
        self.output.parent.rmdir()
        elsewhere = self.root / "elsewhere"
        elsewhere.mkdir()
        self.output.parent.symlink_to(elsewhere, target_is_directory=True)
        self.assertEqual(2, self.main())
        self.assertEqual([], list(elsewhere.iterdir()))

    def test_bounded_atomic_writer_removes_temporary_file_and_preserves_previous_bytes(self):
        self.artifact(declared.DEFAULT_OUTPUT, b"previous bytes\n")
        with patch.object(declared, "MAX_OUTPUT_BYTES", 100):
            with self.assertRaises(declared.LedgerError):
                declared.publish(self.root, declared.DEFAULT_OUTPUT, {"large": "x" * 1000})
        self.assertEqual(b"previous bytes\n", self.output.read_bytes())
        self.assertEqual([], list(self.output.parent.glob(".ktchecker-declared-coverage-*.tmp")))

    def test_output_size_limit_leaves_unavailable_not_a_partial_or_old_ledger(self):
        self.json_artifact(declared.DEFAULT_OUTPUT, {"ledger_valid": True, "old_counts": 999})
        with patch.object(declared, "MAX_OUTPUT_BYTES", 4096):
            self.assert_unavailable()
        self.assertEqual([], list(self.output.parent.glob(".ktchecker-declared-coverage-*.tmp")))

    def test_final_publication_failure_leaves_invalid_not_previous_valid_ledger(self):
        original_replace = declared.os.replace
        calls = []

        def replace(source, destination):
            calls.append((source, destination))
            if len(calls) == 2:
                raise OSError("synthetic final publication failure")
            return original_replace(source, destination)

        self.json_artifact(declared.DEFAULT_OUTPUT, {"ledger_valid": True, "old_counts": 999})
        with patch.object(declared.os, "replace", side_effect=replace):
            self.assert_unavailable()
        self.assertEqual(3, len(calls))
        self.assertEqual([], list(self.output.parent.glob(".ktchecker-declared-coverage-*.tmp")))


class FixedDeclared1665MetadataTests(unittest.TestCase):
    """SOURCE/DATA contracts only; no current or historical full report execution."""

    def test_separate_current_contract_does_not_rewrite_frozen1663_selection(self):
        root = Path(__file__).resolve().parents[1]
        old = (root / current.SELECTION).read_bytes()
        self.assertEqual("08d88559f95f86557efaa9b7663c8b16e66991f3548d119ec71af1b756f4b7a3", declared.sha(old))
        selection = json.loads((root / declared.SELECTION).read_bytes())
        self.assertEqual("ktchecker/declared-coverage-ledger-selection/1", selection["schema"])
        self.assertEqual("ktchecker/owner-full-catalog/1665-u2/1", selection["contract_id"])
        self.assertEqual({"catalog_records": 1665, "contexts": 8, "rows": 13320,
                          "withdrawn_provider_rule_context_cells": 450}, selection["scope"])
        self.assertEqual(selection["scope"], declared.EXPECTED_SCOPE)
        self.assertEqual([], selection["selected_evidence"])
        self.assertEqual([], selection["selected_reference_files"])
        legacy = json.loads(old)
        for key in ("contexts", "withdrawals", "reporter"):
            self.assertEqual(legacy[key], selection[key])
        self.assertNotEqual(current.SELECTION, declared.SELECTION)
        self.assertNotEqual(current.DEFAULT_OUTPUT, declared.DEFAULT_OUTPUT)

    def test_baseline_is_exact1665_plus_two_with_frozen1663_record_identities(self):
        root = Path(__file__).resolve().parents[1]
        baseline = json.loads((root / declared.FIXED_FILES["baseline"]).read_bytes())
        ids = [row["rule_id"] for row in baseline["records"]]
        self.assertEqual(1665, len(ids))
        self.assertEqual(sorted(set(ids)), ids)
        self.assertEqual("121660fc2a5aed084688d0c549f9e1bece2ec514e9ace4748211b55fa87cc1c5", declared.sha(declared.canonical(ids)))
        self.assertEqual(declared.IDS_SHA256, baseline["ids_sha256"])
        self.assertEqual(sorted(declared.ADDED_RULE_IDS), baseline["added_rule_ids"])
        self.assertEqual(1663, baseline["predecessor"]["catalog_records"])
        self.assertEqual("ecf306be6eca28ec1afbadc56641492418da39a787f401ed5179cc020802346c",
                         baseline["predecessor"]["record_identities_sha256"])

    def test_owner_historical_summary_stays_separate_with_unknown_binding_and_cell_ids(self):
        root = Path(__file__).resolve().parents[1]
        data = json.loads((root / declared.FIXED_FILES["historical_inventory_reference"]).read_bytes())
        self.assertEqual("26a0aeb9875a1ceeb34f873da230805e8dc94d4dacceb98d58aa5dd11ce65f98", data["report_sha256"])
        self.assertEqual({"catalog_records": 1663, "contexts": 8, "rows": 13304}, data["historical_scope"])
        self.assertEqual({"mapped": 11448, "implemented": 2, "tested": 0, "qualified": 0,
                          "runtime_defaults_enabled": False}, data["historical_claims"])
        self.assertEqual("source_reference_unavailable", data["status"])
        self.assertIsNone(data["source_sha256"])
        self.assertIsNone(data["implemented_cell_identities"])
        self.assertIsNone(data["current_counts"])
        self.assertFalse(data["source_binding_verified"] or data["historical_report_read"] or data["current_stage_transfer_allowed"])
        # Deliberately never resolve/stat/open data['report_path'] or any sidecar.


if __name__ == "__main__":
    unittest.main()
