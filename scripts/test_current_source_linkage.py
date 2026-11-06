"""Bounded source/JSON controls only. No provider, compiler or historical run is invoked."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import report_current_source_linkage as linkage

ROOT = Path(__file__).resolve().parents[1]
SELECTION = "providers/coverage/current-source-linkage-v1.json"
SUBSET = "providers/coverage/fixtures/round14-selected-source-subset.json"
COMPILER_MAP = "modules/provider-compiler/src/main/resources/compiler-rule-map.json"


def reference(path, data):
    return {"path": path, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def put(files, path, value):
    data = (json.dumps(value, sort_keys=True, indent=2) + "\n").encode()
    files[path] = data
    return reference(path, data)


class MemoryReader(linkage.Reader):
    """Same pinned-reader interface with an explicit immutable test byte mirror."""
    def __init__(self, inputs):
        self.inputs = inputs
        self.files = {}
        self.total = 0

    def _read(self, ref):
        linkage.need(ref["path"] in self.inputs, "test_unselected_read")
        data = self.inputs[ref["path"]]
        linkage.need(len(data) == ref["bytes"] and linkage.sha(data) == ref["sha256"], "input_digest_mismatch")
        return data


class SourceLinkageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = (ROOT / SELECTION).read_bytes()
        cls.selection_ref = reference(SELECTION, data)
        reader = linkage.Reader(ROOT)
        cls.report = linkage.create_report(reader, cls.selection_ref)
        cls.inputs = {path: content for path, (_ref, content) in reader.files.items()}
        cls.selection = json.loads(data)
        cls.subset = json.loads(cls.inputs[SUBSET])

    def variant(self, mutate):
        files = dict(self.inputs)
        selection = copy.deepcopy(self.selection)
        mutate(files, selection)
        selected = put(files, SELECTION, selection)
        return linkage.create_report(MemoryReader(files), selected)

    def subset_variant(self, mutate):
        def change(files, selection):
            path = selection["selected_source_subset"]["path"]
            subset = json.loads(files[path])
            mutate(subset)
            selection["selected_source_subset"] = put(files, path, subset)
        return self.variant(change)

    def test_complete_1665_by_eight_denominator_and_exclusions_are_preserved(self):
        report = self.report
        self.assertEqual((1665, 8, 13320, 46), tuple(report["scope"][key] for key in
                         ("catalog_records", "contexts", "rows", "excluded_rules")))
        self.assertEqual(450, report["scope"]["withdrawn_provider_rule_context_cells_declared"])
        self.assertEqual(1665, len(report["rules"]))
        self.assertEqual(13320, len(report["cells"]))
        self.assertEqual(13320, len({(r["rule_id"], r["context_sha256"]) for r in report["cells"]}))
        self.assertEqual(368, sum(c["state"] == "excluded" for c in report["cells"]))
        for context in report["contexts"]:
            self.assertEqual(context["context_sha256"], linkage.sha(linkage.canonical(context["context"])))

    def test_source_mapping_is_measured_but_implementation_stage_is_not(self):
        summary = self.report["source_summary"]
        self.assertEqual((1433, 232, 1812, 402, 23), tuple(summary[k] for k in
            ("mapped_rule_ids", "unmapped_rule_ids", "provider_rule_rows", "source_implementation_linked_rule_ids",
             "selected_compiler_implementation_source_files")))
        self.assertEqual({stage: None for stage in linkage.STAGES}, self.report["current_evidence_stage_totals"])
        self.assertTrue(all(c[stage] is None for c in self.report["cells"] for stage in linkage.STAGES))
        self.assertFalse(self.report["coverage_inventory_valid"])
        self.assertFalse(self.report["release_qualified"])
        self.assertFalse(self.report["activation_changed"])

    def test_all_98_ordinary_pairs_are_linked_to_real_legacy_refusal_not_current_pass(self):
        summary = self.report["source_summary"]
        self.assertEqual(98, summary["ordinary_authored_pairs"])
        self.assertEqual(98, summary["ordinary_pairs_blocked_by_mapping_contract"])
        ordinary = self.report["routes"][0]
        self.assertTrue(ordinary["source_route_linked"])
        self.assertFalse(ordinary["compatible_with_selected_mapping"])
        rules = {r["rule_id"]: r for r in self.report["rules"]}
        for rule_id in ordinary["rule_ids"]:
            row = rules[rule_id]
            self.assertFalse(row["route_linked"])
            self.assertFalse(row["ordinary_six_kind_route_linked"])
            self.assertEqual(["positive", "negative"], [c["kind"] for c in row["authored_source_cases"]["ordinary_cases"]])
            self.assertEqual(["configuration", "version", "suppression", "regression"], row["ordinary_case_kinds_without_selected_source_link"])
            self.assertIn("ordinary_fixture_route_mapping_contract_mismatch", row["gaps"])

    def test_nine_observations_are_not_six_kind_or_qualification_credit(self):
        route = self.report["routes"][1]
        self.assertEqual(9, len(route["case_ids"]))
        self.assertEqual(["ktc.compiler.common.error_suppression", "ktc.compiler.common.inapplicable_operator_modifier"], route["rule_ids"])
        self.assertTrue(route["compatible_with_selected_mapping"])
        self.assertFalse(route["ordinary_six_kind_source_route"])
        self.assertFalse(route["coverage_stage_credit"])
        self.assertFalse(route["execution_selected"])
        self.assertTrue(route["unknown_expectations_preserved"])
        for row in self.report["rules"]:
            if row["rule_id"] in route["rule_ids"]:
                self.assertTrue(row["route_linked"])
                self.assertFalse(row["ordinary_six_kind_route_linked"])

    def test_android_mapping_without_per_record_hash_stays_explicitly_unpinned(self):
        lint = [m for r in self.report["rules"] for m in r["mappings"] if m["provider_id"] == "android-lint"]
        self.assertEqual(609, len(lint))
        self.assertTrue(all(m["catalog_record_pin_verified"] is None and m["source_implementation_linked"] is None for m in lint))

    def test_derived_subset_distinguishes_selected_bytes_from_historical_owner_assertions(self):
        snapshot = self.report["source_snapshot"]
        self.assertEqual("ktchecker/current-source-linkage-report/2", self.report["schema"])
        self.assertEqual("ktchecker/derived-selected-source-subset/1", snapshot["selected_subset_schema"])
        self.assertEqual(self.selection["selected_source_subset"], snapshot["selected_subset_reference"])
        self.assertEqual("a14cab05a330dc785c15239d77d9364b509c72eb", snapshot["snapshot_head_commit"])
        self.assertIs(True, snapshot["snapshot_dirty"])
        self.assertEqual(254, snapshot["selected_subset_file_count"])
        self.assertEqual(16036972, snapshot["selected_subset_bytes"])
        self.assertEqual(4788, snapshot["owner_original_snapshot_file_count"])
        self.assertEqual(1255489, snapshot["owner_original_snapshot_bytes"])
        self.assertEqual("74ab480acf0ffca8da64146d2182eb1b5a8cc54d218f89754ddf9b6a023d8df5",
                         snapshot["owner_original_snapshot_sha256"])
        self.assertEqual("f361130736f4dbfb1c5b5f8e0a7f7c7e4e4d5149c95cf49ec618c68f0c3b06a7",
                         snapshot["owner_original_snapshot_aggregate_sha256"])
        self.assertTrue(snapshot["snapshot_identity_is_historical_owner_assertion"])
        self.assertTrue(snapshot["original_snapshot_metadata_is_historical_owner_assertion"])
        self.assertTrue(snapshot["selected_subset_matches"])
        self.assertFalse(snapshot["original_snapshot_bytes_verified_here"])
        self.assertFalse(snapshot["whole_tree_recomputed"])
        self.assertFalse(snapshot["snapshot_aggregate_is_current_tree_claim"])
        self.assertFalse(snapshot["runtime_execution_credit"])
        self.assertNotIn("snapshot_file_count", snapshot)

    def test_real_root_uses_exact_public_subset_and_no_build_output(self):
        # setUpClass uses the actual ROOT selection, never a manufactured snapshot
        # or a missing-file skip. This also runs when ROOT is a clean checkout.
        self.assertEqual(256, len(self.inputs))
        self.assertEqual(256, self.report["read_set"]["files"])
        self.assertFalse(any(path.startswith("build/") for path in self.inputs))
        self.assertNotIn("owner_source_snapshot", self.selection)
        selected = {r["path"]: r for r in self.report["read_set"]["references"]
                    if r["path"] not in {SELECTION, SUBSET}}
        self.assertEqual(254, len(selected))
        self.assertEqual(selected, {r["path"]: r for r in self.subset["files"]})
        self.assertEqual(16036972, sum(r["bytes"] for r in selected.values()))

    def test_real_reader_reproduces_without_build_tree_and_rejects_missing_members(self):
        # Byte-for-byte mirror of the real, successfully pinned selected inputs;
        # not substitute provenance and not a claim that Git tracking was tested.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            for relative, data in self.inputs.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            self.assertFalse((root / "build").exists())
            report = linkage.create_report(linkage.Reader(root), self.selection_ref)
            self.assertEqual(self.report, report)
            self.assertTrue(all(cell[stage] is None for cell in report["cells"] for stage in linkage.STAGES))
            for relative in (SUBSET, COMPILER_MAP):
                with self.subTest(missing=relative):
                    (root / relative).unlink()
                    with self.assertRaises(FileNotFoundError):
                        linkage.create_report(linkage.Reader(root), self.selection_ref)
                    (root / relative).write_bytes(self.inputs[relative])

    def test_unselected_legacy_artifacts_binary_refs_and_held_runner_are_not_opened(self):
        paths = set(self.inputs)
        self.assertNotIn("scripts/run_current_coverage.py", paths)
        self.assertFalse(any(p.endswith((".jar", ".class", ".so", ".dll")) or "/runs/" in p for p in paths))
        self.assertNotIn("providers/coverage/current-inputs.json", paths)
        self.assertNotIn("providers/coverage/legacy-evidence-1665-v1.json", paths)
        self.assertNotIn("providers/coverage/withdrawals.json", paths)
        self.assertFalse(any("positive.json" in p or "negative.json" in p or "suppression.json" in p for p in paths))

    def test_generic_passed_attestation_is_not_a_per_rule_selection(self):
        with self.assertRaisesRegex(linkage.LinkageError, "current_rule_evidence_requires_separate_verifier"):
            self.variant(lambda _files, s: s.update(selected_current_rule_evidence=[{"tested": True, "passed": True, "count": 10000}]))

    def test_duplicate_provider_rule_is_rejected_before_denominator_counts(self):
        def mutate(files, selection):
            mapping = json.loads(files[COMPILER_MAP])
            mapping["mappings"].append(copy.deepcopy(mapping["mappings"][0]))
            changed = put(files, COMPILER_MAP, mapping)
            selection["mapping_files"] = [changed if r["path"] == COMPILER_MAP else r for r in selection["mapping_files"]]
        with self.assertRaisesRegex(linkage.LinkageError, "duplicate_provider_rule"):
            self.variant(mutate)

    def test_nonempty_wrong_record_pin_is_not_downgraded_to_unpinned(self):
        def mutate(files, selection):
            mapping = json.loads(files[COMPILER_MAP])
            mapping["mappings"][0]["catalog_record_sha256"] = "0" * 64
            changed = put(files, COMPILER_MAP, mapping)
            selection["mapping_files"] = [changed if r["path"] == COMPILER_MAP else r for r in selection["mapping_files"]]
        with self.assertRaisesRegex(linkage.LinkageError, "mapping_catalog_record_drift"):
            self.variant(mutate)

    def test_full_context_change_cannot_reuse_its_old_hash(self):
        def mutate(files, selection):
            declared_path = selection["declared_selection"]["path"]
            declared = json.loads(files[declared_path])
            old = declared["contexts"][0]
            context = json.loads(files[old["path"]])
            context["id"] += "-currentized"
            declared["contexts"][0] = {**old, **put(files, old["path"], context)}
            selection["declared_selection"] = put(files, declared_path, declared)
        with self.assertRaisesRegex(linkage.LinkageError, "whole_context_identity_mismatch"):
            self.variant(mutate)

    def test_declared_scope_cannot_shrink_catalog_denominator(self):
        with self.assertRaisesRegex(linkage.LinkageError, "declared_scope_mismatch"):
            self.variant(lambda _files, s: s["expected_scope"].update(catalog_records=1663, rows=13304))

    def test_selection_schema_rejects_legacy_snapshot_reference_and_old_version(self):
        for change in ({"owner_source_snapshot": {"path": "build/unselected.json"}},
                       {"schema": "ktchecker/current-source-linkage-selection/1"}):
            with self.subTest(change=change), self.assertRaisesRegex(linkage.LinkageError, "selection_schema"):
                self.variant(lambda _files, selection: selection.update(change))

    def test_subset_missing_required_fields_and_extra_fields_are_rejected(self):
        for key in self.subset:
            with self.subTest(missing=key), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_schema"):
                self.subset_variant(lambda subset: subset.pop(key))
        with self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_schema"):
            self.subset_variant(lambda subset: subset.update(extra="unselected"))

    def test_subset_schema_wrong_version_or_nonobject_is_rejected(self):
        with self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_schema"):
            self.subset_variant(lambda subset: subset.update(schema="ktchecker/derived-selected-source-subset/0"))
        for value in ([], None, True):
            def mutate(files, selection):
                selection["selected_source_subset"] = put(files, SUBSET, value)
            with self.subTest(value=value), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_schema"):
                self.variant(mutate)

    def test_subset_identity_requires_exact_full_owner_head(self):
        for value in ("a14cab0", "0" * 40, None, True):
            with self.subTest(value=value), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_identity"):
                self.subset_variant(lambda subset: subset.update(head=value))

    def test_dirty_is_an_exact_boolean_not_an_inferred_clean_state(self):
        for value in (False, 1, "true", None):
            with self.subTest(value=value), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_dirty_identity"):
                self.subset_variant(lambda subset: subset.update(dirty=value))
        for value in (False, 1, "true", None):
            with self.subTest(expected=value), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_dirty_identity"):
                self.variant(lambda _files, selection: selection.update(expected_source_dirty=value))

    def test_historical_snapshot_key_spellings_are_not_a_fallback(self):
        def mutate(subset):
            subset["baseCommit"] = subset.pop("head")
            original = subset["owner_original_snapshot_assertion"]
            original["aggregateSha256"] = original.pop("aggregate_sha256")
        with self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_schema"):
            self.subset_variant(mutate)

    def test_original_snapshot_assertion_has_exact_schema_without_a_read_path(self):
        for key in self.subset["owner_original_snapshot_assertion"]:
            with self.subTest(missing=key), self.assertRaisesRegex(linkage.LinkageError, "owner_original_snapshot_assertion_schema"):
                self.subset_variant(lambda subset: subset["owner_original_snapshot_assertion"].pop(key))
        with self.assertRaisesRegex(linkage.LinkageError, "owner_original_snapshot_assertion_schema"):
            self.subset_variant(lambda subset: subset["owner_original_snapshot_assertion"].update(path="build/unselected.json"))
        with self.assertRaisesRegex(linkage.LinkageError, "owner_original_snapshot_assertion_schema"):
            self.variant(lambda _files, selection: selection.update(expected_owner_original_snapshot_assertion=[]))

    def test_original_snapshot_assertion_wrong_values_and_boolean_sizes_are_rejected(self):
        for key, value in (("sha256", "0" * 64), ("sha256", "invalid"),
                           ("aggregate_sha256", "0" * 64), ("aggregate_sha256", None),
                           ("bytes", True), ("bytes", 1), ("bytes", -1),
                           ("file_count", True), ("file_count", 254), ("file_count", 0)):
            with self.subTest(key=key, value=value), self.assertRaisesRegex(linkage.LinkageError, "owner_original_snapshot_assertion_identity"):
                self.subset_variant(lambda subset: subset["owner_original_snapshot_assertion"].update({key: value}))
        for key in ("bytes", "file_count"):
            with self.subTest(expected_boolean=key), self.assertRaisesRegex(linkage.LinkageError, "owner_original_snapshot_assertion_identity"):
                self.variant(lambda _files, selection: selection["expected_owner_original_snapshot_assertion"].update({key: True}))

    def test_selected_file_count_is_exactly_254_not_original_4788(self):
        for value in (True, 253, 255, 4788, None):
            with self.subTest(value=value), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_file_count"):
                self.subset_variant(lambda subset: subset.update(selected_source_file_count=value))
            with self.subTest(expected=value), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_file_count"):
                self.variant(lambda _files, selection: selection.update(expected_selected_source_file_count=value))

    def test_selected_byte_count_is_exact_and_not_boolean(self):
        for value in (True, 1, 1255489, 16036973, None):
            with self.subTest(value=value), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_byte_count"):
                self.subset_variant(lambda subset: subset.update(selected_source_bytes=value))
            with self.subTest(expected=value), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_byte_count"):
                self.variant(lambda _files, selection: selection.update(expected_selected_source_bytes=value))

    def test_subset_roster_cannot_omit_or_add_a_member(self):
        for change in (lambda subset: subset["files"].pop(),
                       lambda subset: subset["files"].append(copy.deepcopy(subset["files"][0])),
                       lambda subset: subset.update(files={})):
            with self.subTest(change=change), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_file_roster"):
                self.subset_variant(change)

    def test_subset_extra_member_does_not_get_followed_or_replace_a_selected_member(self):
        def mutate(subset):
            entry = next(r for r in subset["files"] if r["path"] == COMPILER_MAP)
            entry["path"] = "providers/coverage/fixtures/never-selected.kt"
            subset["files"].sort(key=lambda r: r["path"])
        with self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_membership"):
            self.subset_variant(mutate)

    def test_subset_duplicate_and_unsorted_members_are_rejected(self):
        def duplicate(subset):
            subset["files"][-1] = copy.deepcopy(subset["files"][0])
        with self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_duplicate_path"):
            self.subset_variant(duplicate)
        def reorder(subset):
            subset["files"][0], subset["files"][1] = subset["files"][1], subset["files"][0]
        with self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_file_order"):
            self.subset_variant(reorder)

    def test_subset_entry_schema_and_source_pin_types_are_strict(self):
        for value, code in ((None, "selected_source_subset_file_entry"),
                            ({"path": "source.kt", "bytes": 0, "sha256": "0" * 64, "tested": True},
                             "selected_source_subset_file_entry"),
                            ({"path": "../source.kt", "bytes": 0, "sha256": "0" * 64}, "unsafe_path"),
                            ({"path": "source.kt", "bytes": True, "sha256": "0" * 64}, "invalid_reference_size"),
                            ({"path": "source.kt", "bytes": 0, "sha256": "bad"}, "invalid_digest")):
            def mutate(subset):
                subset["files"][0] = value
            with self.subTest(value=value), self.assertRaisesRegex(linkage.LinkageError, code):
                self.subset_variant(mutate)

    def test_subset_source_pin_drift_is_rejected_even_after_repinning_the_receipt(self):
        for key, value in (("sha256", "0" * 64), ("bytes", 1)):
            with self.subTest(key=key), self.assertRaisesRegex(linkage.LinkageError, "selected_source_subset_pin_mismatch"):
                self.subset_variant(lambda subset: subset["files"][0].update({key: value}))

    def test_changed_subset_bytes_cannot_reuse_the_old_receipt_pin(self):
        def mutate(files, _selection):
            files[SUBSET] += b" "
        with self.assertRaisesRegex(linkage.LinkageError, "input_digest_mismatch"):
            self.variant(mutate)

    def test_abbreviated_commit_is_not_a_source_identity(self):
        with self.assertRaisesRegex(linkage.LinkageError, "invalid_digest"):
            self.variant(lambda _files, s: s.update(expected_source_commit="c364"))


class ReaderSafetyTest(unittest.TestCase):
    def test_duplicate_json_keys_and_nonfinite_values_fail(self):
        for data in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}'):
            with self.subTest(data=data), self.assertRaises(linkage.LinkageError):
                linkage.document(data)

    def test_paths_and_boolean_size_are_rejected(self):
        for value in ("../secret", "a/../secret", "/secret", "C:secret", "a\\b", "a//b", "./a", "a/", "a\x00b"):
            with self.subTest(value=value), self.assertRaises(linkage.LinkageError):
                linkage.name(value)
        with self.assertRaisesRegex(linkage.LinkageError, "invalid_reference_size"):
            linkage.pin({"path": "source.kt", "bytes": True, "sha256": "0" * 64})

    def test_actual_reader_pins_bytes_and_rechecks_changes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            path = root / "source.kt"
            path.write_bytes(b"before")
            reader = linkage.Reader(root)
            self.assertEqual(b"before", reader.read(reference("source.kt", b"before")))
            path.write_bytes(b"after!")
            with self.assertRaisesRegex(linkage.LinkageError, "input_digest_mismatch"):
                reader.recheck()

    def test_descendant_symlink_is_rejected_without_following_it(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            (root / "source.kt").write_bytes(b"source")
            (root / "alias.kt").symlink_to("source.kt")
            with self.assertRaisesRegex(linkage.LinkageError, "symlink_input"):
                linkage.Reader(root).read(reference("alias.kt", b"source"))


if __name__ == "__main__":
    unittest.main()
