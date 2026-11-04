#!/usr/bin/env python3
"""Synthetic receipt-validation tests; not Lint detector or release evidence."""

import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("retain_lint_evidence", Path(__file__).with_name("retain_lint_evidence.py"))
retention = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retention)


class RetainLintEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.root_patch = patch.object(retention, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.artifact = self.root / "runtime.jar"
        self.artifact.write_bytes(b"synthetic validator artifact, not an executed detector")
        self.artifact_identity = {"coordinate": "isolated-lint-runtime:runtime.jar", "path": str(self.artifact),
                                  "digest": "sha256:" + retention.digest(self.artifact)}
        self.paths = {}
        for case in ("positive", "negative", "suppression"):
            source = self.root / "providers/platform-fixtures/android" / case / "res/layout/example.xml"
            source.parent.mkdir(parents=True)
            source.write_text("<layout>\n  synthetic source\n</layout>\n")
            rows = [{"ruleId": rule_id, "context": retention.XML_SCOPE.copy(), "state": "incomplete", "reason": retention.PENDING,
                     "required": True, "depth": "syntax", "provenance": "executed", "certificateId": "sha256:" + "a" * 64,
                     "implemented": True, "tested": False, "qualified": False} for rule_id in retention.RULES]
            findings = [{"ruleId": rule_id, "providerId": "android-lint", "upstreamId": upstream, "severity": "warning",
                         "context": retention.XML_SCOPE.copy(), "fingerprint": "sha256:" + "b" * 64,
                         "diagnosis": "sensitive-marker", "location": {"path": "res/layout/example.xml",
                             "fileDigest": "sha256:" + retention.digest(source), "line": 2, "column": 2,
                             "endLine": 2, "endColumn": 2, "excerpt": "sensitive-marker"}}
                        for rule_id, upstream in retention.RULES.items()] if case == "positive" else []
            value = {"schemaVersion": 1, "state": "passed", "case": case, "target": "android", "snapshotId": "sha256:" + "c" * 64,
                     "providerResult": {"providerId": "android-lint", "providerVersion": "32.3.1", "status": "incomplete",
                         "coverage": rows, "reasons": [retention.PENDING], "findings": findings, "rawDiagnostics": [],
                         "metrics": {"elapsedMillis": 0, "cpuMillis": None, "peakRssBytes": None, "outputBytes": 0,
                                     "private": "sensitive-marker"}},
                     "artifactIdentities": [self.artifact_identity], "assertions": ["sensitive-marker"],
                     "limitations": ["sensitive-marker"]}
            path = self.root / (case + ".json")
            path.write_text(json.dumps(value))
            self.paths[case] = path

    def mutate(self, change):
        path = self.paths["positive"]
        value = json.loads(path.read_text())
        change(value)
        path.write_text(json.dumps(value))

    def test_receipt_sanitization_omits_arbitrary_private_strings(self):
        safe = retention.receipt(self.paths["positive"], "positive")
        self.assertNotIn("sensitive-marker", json.dumps(safe))
        self.assertNotIn(str(self.root), json.dumps(safe))
        self.assertTrue(all(row["tested"] is False and row["qualified"] is False for row in safe["providerResult"]["coverage"]))

    def test_failed_or_wrong_version_receipts_are_rejected(self):
        baseline = json.loads(self.paths["positive"].read_text())
        for field in ("state", "providerVersion"):
            with self.subTest(field=field):
                value = copy.deepcopy(baseline)
                if field == "state":
                    value["state"] = "failed"
                else:
                    value["providerResult"]["providerVersion"] = "32.5.0"
                self.paths["positive"].write_text(json.dumps(value))
                with self.assertRaises(ValueError):
                    retention.receipt(self.paths["positive"], "positive")

    def test_qualification_cannot_be_imported_from_receipt_flags(self):
        self.mutate(lambda value: value["providerResult"]["coverage"][0].update(qualified=True))
        with self.assertRaises(ValueError):
            retention.receipt(self.paths["positive"], "positive")

    def test_duplicate_rule_rows_are_rejected(self):
        self.mutate(lambda value: value["providerResult"]["coverage"].append(copy.deepcopy(value["providerResult"]["coverage"][0])))
        with self.assertRaises(ValueError):
            retention.receipt(self.paths["positive"], "positive")

    def test_source_and_actual_artifact_drift_are_rejected(self):
        original = self.artifact.read_bytes()
        self.artifact.write_bytes(b"different bytes")
        with self.assertRaises(ValueError):
            retention.receipt(self.paths["positive"], "positive")
        self.artifact.write_bytes(original)
        self.mutate(lambda value: value["providerResult"]["findings"][0]["location"].update(fileDigest="sha256:" + "d" * 64))
        with self.assertRaises(ValueError):
            retention.receipt(self.paths["positive"], "positive")

    def test_other_variant_or_private_reason_is_rejected(self):
        self.mutate(lambda value: value["providerResult"]["coverage"][0]["context"].update(compilation="release"))
        with self.assertRaises(ValueError):
            retention.receipt(self.paths["positive"], "positive")
        self.mutate(lambda value: value["providerResult"]["coverage"][0].update(context=retention.XML_SCOPE.copy()))
        self.mutate(lambda value: value["providerResult"].update(reasons=["sensitive-marker"]))
        with self.assertRaises(ValueError):
            retention.receipt(self.paths["positive"], "positive")

    def test_source_ranges_reject_private_strings_booleans_and_invalid_order(self):
        baseline = json.loads(self.paths["positive"].read_text())
        changes = [{field: invalid} for field in ("line", "column", "endLine", "endColumn")
                   for invalid in ("sensitive-marker", True, 1.5, None)]
        changes += [{"line": 1}, {"line": 999}, {"column": -1}, {"column": 999},
                    {"endLine": 1}, {"endLine": 999}, {"endColumn": -1}, {"endColumn": 999}, {"endColumn": 1}]
        for change in changes:
            with self.subTest(change=change):
                value = copy.deepcopy(baseline)
                value["providerResult"]["findings"][0]["location"].update(change)
                self.paths["positive"].write_text(json.dumps(value))
                with self.assertRaises(ValueError):
                    retention.receipt(self.paths["positive"], "positive")

    def test_junit_names_timestamp_and_testcase_outcomes_are_validated_before_retention(self):
        for names, timestamp, outcome in (
            ([retention.TEST_NAMES[0], "sensitive-marker"], "2026-11-04T00:00:00Z", None),
            ([retention.TEST_NAMES[0]] * 2, "2026-11-04T00:00:00Z", None),
            (retention.TEST_NAMES, "sensitive-marker", None),
            (retention.TEST_NAMES, "2026-99-99T00:00:00Z", None),
            (retention.TEST_NAMES, "2026-11-04T00:00:00Z", "failure"),
        ):
            with self.subTest(names=names, timestamp=timestamp, outcome=outcome):
                junit = self.write_junit(names, timestamp, outcome)
                with patch.object(sys, "argv", self.arguments(junit)), self.assertRaises(ValueError):
                    retention.main()
                self.assertFalse((self.root / "providers/platform-fixtures/runs").exists())

    def test_main_archives_exact_post_run_source_without_upgrading_execution(self):
        source = self.root / retention.IMPLEMENTATION
        source.parent.mkdir(parents=True)
        source.write_bytes(b"synthetic post-run source bytes; no bytecode attribution")
        junit = self.write_junit()
        with patch.object(sys, "argv", self.arguments(junit)):
            retention.main()
        run = self.root / "providers/platform-fixtures/runs/synthetic-validator-only"
        self.assertEqual(source.read_bytes(), (run / "post-run-source/AndroidLintProvider.kt").read_bytes())
        sidecar = json.loads((self.root / "providers/platform-fixtures/evidence/synthetic-validator-only.json").read_text())
        self.assertTrue(all(row["tested"] is False and row["qualified"] is False for row in sidecar["rules"]))
        reference = sidecar["rules"][0]["implementation_evidence"][0]
        self.assertEqual(retention.digest(source), reference["sha256"])
        self.assertEqual("providers/platform-fixtures/runs/synthetic-validator-only/post-run-source/AndroidLintProvider.kt", reference["path"])
        self.assertIn("manually associated", (run / "run.json").read_text())

    def write_junit(self, names=retention.TEST_NAMES, timestamp="2026-11-04T00:00:00Z", outcome=None):
        # Synthetic XML descriptor only; matching names cannot prove an actual detector run.
        suite = retention.ET.Element("testsuite", name="dev.ktchecker.providers.lint.RealLintFixtureTest",
                                     tests="2", failures="0", errors="0", skipped="0", timestamp=timestamp)
        for name in names:
            case = retention.ET.SubElement(suite, "testcase", name=name)
            if outcome is not None:
                retention.ET.SubElement(case, outcome)
        junit = self.root / "junit.xml"
        retention.ET.ElementTree(suite).write(junit)
        return junit

    def arguments(self, junit):
        arguments = ["retain_lint_evidence.py", "--junit", str(junit), "--run-id", "synthetic-validator-only"]
        for case, path in self.paths.items():
            arguments.extend(["--" + case, str(path)])
        return arguments


if __name__ == "__main__":
    unittest.main()
