"""Independent bookkeeping regressions, NOT rule or release qualification.

The miniature catalog and every referenced artifact below are deliberately
synthetic.  They exercise the inventory's trust/identity boundaries only.
Run: python3 -m unittest discover -s tests/audit -p 'test_*_audit.py' -v
"""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


_ROOT = Path(__file__).resolve().parents[2]
_SPEC = importlib.util.spec_from_file_location(
    "ktchecker_coverage_under_audit", _ROOT / "scripts/rule_coverage.py"
)
assert _SPEC is not None and _SPEC.loader is not None
coverage = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(coverage)


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


class CoverageQualificationAudit(unittest.TestCase):
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory(prefix="ktchecker-coverage-audit-")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.modes = ("enabled_when_validated", "advisory", "opt_in", "excluded")
        self.catalog = [
            {"id": f"audit/{mode}", "default_activation": mode,
             "status": "planned", "depth": "semantic"}
            for mode in self.modes
        ]
        self.write_json("data/rules-audit.json", self.catalog)
        self.context = {
            "id": "audit-local-only", "target": "jvm", "source_set": "main",
            "module": ":audit", "compilation": "main", "kotlin_version": "2.4.21",
            "compiler_version": "2.4.21", "jdk_version": "21",
            "library_versions": {"audit-library": "1.0"},
        }
        for dimension in (
            "model", "dependency", "classpath", "toolchain", "configuration",
            "policy", "compilation_members",
        ):
            self.context[f"{dimension}_digest"] = "sha256:" + sha256(
                f"TEST-ONLY:{dimension}".encode()
            )
        self.mapping = {
            "schema_version": 1, "provider_id": "audit-synthetic",
            "provider_version": "mechanics-only-1",
            "mappings": [
                {"rule_id": row["id"], "upstream_id": row["id"],
                 "relation": "excluded_alias" if row["default_activation"] == "excluded" else "exact"}
                for row in self.catalog
            ],
        }
        self.sidecar = {
            "schema_version": 1, "provider_id": "audit-synthetic",
            "provider_version": "mechanics-only-1", "context": copy.deepcopy(self.context),
            "rules": [self.attestation(row["id"]) for row in self.catalog],
        }

    def write_json(self, name: str, value: object) -> Path:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def artifact(self, name: str) -> dict:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        content = f"SYNTHETIC AUDIT ARTIFACT, NO RULE/HOST QUALIFICATION: {name}\n".encode()
        path.write_bytes(content)
        return {"path": name, "sha256": sha256(content)}

    def attestation(self, rule_id: str) -> dict:
        prefix = f"evidence/{rule_id}"
        return {
            "rule_id": rule_id, "implemented": True, "tested": True, "qualified": True,
            "implementation_evidence": [self.artifact(f"{prefix}/implementation.txt")],
            "qualification_evidence": [self.artifact(f"{prefix}/local-attestation.txt")],
            "cases": [
                {"kind": kind, "passed": True, "evidence": [self.artifact(f"{prefix}/{kind}.txt")]}
                for kind in ("positive", "negative", "configuration", "version", "suppression", "regression")
            ],
        }

    def emit(self, *, include_evidence: bool = True, contexts: list[dict] | None = None) -> dict:
        mapping = self.write_json("audit-map.json", self.mapping)
        sidecar = self.write_json("audit-attestation.json", self.sidecar)
        return coverage.generate_coverage(
            self.root, mapping_paths=[mapping],
            evidence_paths=[sidecar] if include_evidence else [], contexts=contexts,
        )

    def test_fully_attested_single_jvm_tuple_never_certifies_release_or_default_activation(self) -> None:
        report = self.emit()
        self.assertEqual(4, report["catalog_records"])
        self.assertEqual(4, report["row_count"])
        self.assertTrue(report["all_requested_cells_qualified"])
        self.assertFalse(report["release_qualified"])
        self.assertTrue(report["release_qualification_reason"])
        self.assertEqual(set(self.modes), {row["default_activation"] for row in report["rows"]})
        self.assertTrue(all(not row["runtime_default_enabled"] for row in report["rows"]))
        excluded = next(row for row in report["rows"] if row["default_activation"] == "excluded")
        self.assertEqual("excluded", excluded["state"])
        self.assertFalse(excluded["qualified"])

    def test_missing_or_unknown_dimensions_cannot_qualify_even_with_all_hash_backed_claims(self) -> None:
        dimensions = (
            "source_set", "module", "compilation", "compiler_version", "jdk_version",
            "model_digest", "dependency_digest", "classpath_digest", "toolchain_digest",
            "configuration_digest", "policy_digest", "compilation_members_digest",
            "library_versions",
        )
        for field in dimensions:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.context)
                if field == "source_set":
                    changed[field] = "unknown"
                else:
                    changed.pop(field)
                self.sidecar["context"] = changed
                report = self.emit()
                self.assertFalse(report["all_requested_cells_qualified"])
                self.assertTrue(all(not row["qualified"] for row in report["rows"]))
        for field, value in (("compilation", "*"), ("jdk_version", "unavailable"),
                             ("library_versions", {"audit-library": "unknown"})):
            with self.subTest(field=field, value=value):
                self.sidecar["context"] = {**self.context, field: value}
                self.assertFalse(self.emit()["all_requested_cells_qualified"])

    def test_another_context_cannot_borrow_attestations_from_the_same_display_id(self) -> None:
        changed = {**self.context, "configuration_digest": "sha256:" + sha256(b"another-policy")}
        report = self.emit(contexts=[self.context, changed])
        self.assertEqual(8, report["row_count"])
        self.assertEqual(2, len({row["context_sha256"] for row in report["rows"]}))
        original = [row for row in report["rows"] if row["context"] == self.context and row["default_activation"] != "excluded"]
        other = [row for row in report["rows"] if row["context"] == changed]
        self.assertTrue(all(row["qualified"] for row in original))
        self.assertTrue(all(not row["implemented"] and not row["tested"] and not row["qualified"] for row in other))
        self.assertFalse(report["all_requested_cells_qualified"])
        self.assertFalse(report["release_qualified"])

    def test_mapping_and_implementation_paths_alone_are_not_execution_evidence(self) -> None:
        for mapping in self.mapping["mappings"]:
            mapping["implementation_evidence"] = [self.artifact(f"mapping/{mapping['rule_id']}.txt")]
        report = self.emit(include_evidence=False)
        self.assertTrue(all(row["mapped"] for row in report["rows"]))
        self.assertTrue(all(not row["implemented"] and not row["tested"] and not row["qualified"] for row in report["rows"]))
        self.assertFalse(report["release_qualified"])

    def test_changed_reference_or_provider_version_is_an_error_not_dropped_coverage(self) -> None:
        reference = self.sidecar["rules"][0]["cases"][0]["evidence"][0]
        path = self.root / reference["path"]
        original = path.read_bytes()
        path.write_bytes(b"changed bytes after the claimed run")
        with self.assertRaisesRegex(coverage.CoverageError, "digest mismatch"):
            self.emit()
        path.write_bytes(original)
        self.sidecar["provider_version"] = "another-version"
        with self.assertRaisesRegex(coverage.CoverageError, "unmapped provider/version"):
            self.emit()


if __name__ == "__main__":
    unittest.main()
