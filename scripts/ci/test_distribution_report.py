"""Synthetic inventory controls and read-only distribution build-configuration contracts."""

import argparse
import copy
import io
import json
import re
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

try:
    from . import ci_common, report_distribution
    from .test_model_worker_distribution import synthetic_worker
except ImportError:
    import ci_common
    import report_distribution
    from test_model_worker_distribution import synthetic_worker


IDENTITY = {"commit": "1" * 40, "dirty": False, "working_tree_digest": "sha256:" + "2" * 64}
PUBLIC_TEXT = b"Synthetic retained license text for infrastructure testing, not a license grant.\n"


class DistributionInventoryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.distribution = self.root / "modules/cli/build/install/ktchecker"
        self.distribution.mkdir(parents=True)
        self.resolved = self.root / "resolved"
        self.resolved.mkdir()
        self.maven = self.resolved / "actual-bytes-not-a-coordinate.jar"
        self.owner = self.resolved / "owner-output.jar"
        self.worker = self.resolved / report_distribution.model_worker_distribution.WORKER_FILENAME
        self.jar(self.maven)
        self.jar(self.owner, {"Owner.class": b"synthetic owner bytecode"})
        synthetic_worker(self.worker)
        self.pom = self.resolved / "actual-module.pom"
        self.pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license>'
                            '<name>The Apache Software License, Version 2.0</name>'
                            '<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>'
                            '</license></licenses></project>')
        for file, relative in ((self.maven, "lib/actual-bytes-not-a-coordinate.jar"),
                               (self.maven, "providers/compiler/actual-bytes-not-a-coordinate.jar"),
                               (self.worker, report_distribution.model_worker_distribution.WORKER_RELATIVE),
                               (self.owner, "lib/owner-output.jar")):
            destination = self.distribution / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(file, destination)
        self.asset = {"path": "third_party/licenses/Apache-2.0.txt", "digest": ci_common.digest_bytes(PUBLIC_TEXT)}
        for base in (self.root, self.distribution):
            path = base / self.asset["path"]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(PUBLIC_TEXT)
            (base / "THIRD_PARTY_NOTICES.md").write_text("Synthetic public attribution inventory.\n")
        self.manifest = {"schemaVersion": 1, "producer": "gradle-resolved-distribution-artifacts/1",
                         "gradleVersion": "9.6.1", "project": ":cli", "distribution": str(self.distribution),
                         "artifacts": [self.artifact(), self.artifact(configuration="bundledCompiler"),
                                       self.artifact(coordinate="dev.example:provider-compiler:0.1.0-preview.1", origin="owner_project",
                                                     configuration="bundledCompilerModel", file=str(self.worker), pom=None,
                                                     artifactIdentity=report_distribution.model_worker_distribution.APPLICATION_TASK),
                                       self.artifact(coordinate="dev.example:owner:0.1.0-preview.1", origin="owner_project",
                                                     configuration="application", file=str(self.owner), pom=None)]}
        self.policy = {"schema_version": 1, "owner_license": None,
                       "known_license_assets": {"Apache-2.0": [self.asset]},
                       "notice_rules": [{"coordinate_pattern": r"^example\.actual:module:1\.2\.3$", "required_assets": [], "review_complete": False}],
                       "shaded_review_rules": [{"coordinate_pattern": r"^example\.actual:module:1\.2\.3$", "review_complete": False}],
                       "required_distribution_files": ["THIRD_PARTY_NOTICES.md"]}
        output = self.root / "build/ci-evidence"
        self.args = argparse.Namespace(distribution=self.distribution, dependencies=output / "raw/dependencies.json",
                                       policy=output / "policy.json", output=output / "package-report.json",
                                       sbom=output / "distribution.cdx.json", notice=output / "NOTICE.dependency-report.txt",
                                       require_license_review=False)
        self.write_inputs()

    def jar(self, path, content=None):
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, raw in (content or {"Example.class": b"synthetic public bytecode"}).items():
                archive.writestr(name, raw)

    def artifact(self, **changes):
        return {"coordinate": "example.actual:module:1.2.3", "origin": "maven", "configuration": "runtimeClasspath",
                "artifactIdentity": "actual Gradle component fixture", "file": str(self.maven), "pom": str(self.pom), **changes}

    def write_inputs(self):
        ci_common.write_json(self.args.dependencies, self.manifest)
        ci_common.write_json(self.args.policy, self.policy)

    def generate(self):
        self.write_inputs()
        with patch.object(report_distribution, "source_identity", return_value=IDENTITY):
            return report_distribution.generate(self.args, self.root)

    def cli(self, strict=False):
        actual = report_distribution.generate
        argv = ["report_distribution.py", "--dependencies", str(self.args.dependencies),
                "--distribution", str(self.distribution), "--policy", str(self.args.policy),
                "--output", str(self.args.output), "--sbom", str(self.args.sbom), "--notice", str(self.args.notice)]
        if strict:
            argv.append("--require-license-review")
        with patch.object(report_distribution, "generate", side_effect=lambda args: actual(args, self.root)), \
                patch.object(report_distribution, "source_identity", return_value=IDENTITY), \
                patch("sys.argv", argv), patch("sys.stdout", new_callable=io.StringIO):
            return report_distribution.main()

    def test_actual_bytes_are_mapped_to_component_ids_not_filenames(self):
        result = self.generate()
        self.assertEqual("passed", result["state"])
        self.assertEqual([], result["errors"])
        self.assertEqual(3, len(result["components"]))
        component = next(row for row in result["components"] if row["origin"] == "maven")
        self.assertEqual("example.actual:module:1.2.3", component["coordinate"])
        self.assertEqual(ci_common.digest_file(self.maven), component["digest"])
        self.assertEqual(["bundledCompiler", "runtimeClasspath"], component["configurations"])
        self.assertEqual(2, len(component["distribution_paths"]))
        self.assertEqual("Apache-2.0", component["pom"]["declared_licenses"][0]["spdx_id"])
        self.assertTrue(component["required_retained_assets"][0]["preserved_in_distribution"])
        self.assertEqual("blocked", result["license_review_status"])
        self.assertFalse(result["release_qualified"])
        self.assertNotIn(str(self.root), json.dumps(result))

    def test_mismatching_installed_bytes_are_never_given_a_guessed_coordinate(self):
        self.jar(self.distribution / "lib/actual-bytes-not-a-coordinate.jar", {"Changed.class": b"different bytes"})
        result = self.generate()
        self.assertEqual("failed", result["state"])
        self.assertTrue(any("absent/ambiguous" in error for error in result["errors"]))

    def test_resolved_but_undistributed_runtime_artifacts_fail_inventory(self):
        other = self.resolved / "other.jar"
        self.jar(other)
        self.manifest["artifacts"].append(self.artifact(file=str(other), coordinate="example.actual:other:1.0"))
        self.assertTrue(any("absent from installed" in error for error in self.generate()["errors"]))

    def test_ambiguous_real_coordinates_or_pom_bytes_fail_closed(self):
        original = copy.deepcopy(self.manifest)
        self.manifest["artifacts"].append(self.artifact(coordinate="example.actual:another:1.2.3"))
        self.assertEqual("failed", self.generate()["state"])
        self.manifest = original
        other = self.resolved / "another.pom"
        other.write_text("<project><licenses/></project>")
        self.manifest["artifacts"].append(self.artifact(pom=str(other)))
        self.assertEqual("failed", self.generate()["state"])

    def test_missing_queried_pom_remains_an_unknown_license_blocker(self):
        for row in self.manifest["artifacts"]:
            if row["origin"] == "maven":
                row["pom"] = None
        result = self.generate()
        self.assertEqual("passed", result["state"])
        self.assertIn("declared_license_unknown:example.actual:module:1.2.3", result["license_review_blockers"])
        self.assertFalse(result["release_qualified"])

    def test_generic_lgpl_version_does_not_invent_only_or_later_spdx(self):
        self.pom.write_text("<project><licenses><license><name>GNU Lesser General Public License 2.1</name>"
                            "<url>https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html</url></license></licenses></project>")
        result = self.generate()
        component = next(row for row in result["components"] if row["origin"] == "maven")
        declaration = component["pom"]["declared_licenses"][0]
        self.assertIsNone(declaration["spdx_id"])
        self.assertIn("license_id_unresolved:example.actual:module:1.2.3", result["license_review_blockers"])
        sbom_component = next(row for row in report_distribution.cyclone_dx(result)["components"] if row["group"] == "example.actual")
        self.assertNotIn("id", sbom_component["licenses"][0]["license"])
        self.assertEqual(declaration["name"], sbom_component["licenses"][0]["license"]["name"])

    def test_conflicting_exact_name_and_url_aliases_are_not_silently_resolved(self):
        self.assertIsNone(report_distribution.license_id("MIT", "https://www.apache.org/licenses/LICENSE-2.0.txt"))
        self.assertIsNone(report_distribution.license_id("Unknown license", "https://unreviewed.invalid/license"))

    def test_pom_dtd_entity_non_utf8_oversize_and_malformed_input_are_rejected(self):
        values = (b'<!DOCTYPE project [<!ENTITY owner "private">]><project/>',
                  '<!DOCTYPE project><project/>'.encode("utf-16"), b"<project>", b"<not-project/>", b"x" * (2 * 1024 * 1024 + 1))
        for raw in values:
            with self.subTest(size=len(raw)):
                self.pom.write_bytes(raw)
                with self.assertRaises((ValueError, ET.ParseError)):
                    report_distribution.pom_licenses(self.pom)

    def test_required_retained_asset_source_and_distribution_bytes_are_exact(self):
        for base in (self.root, self.distribution):
            with self.subTest(source=base == self.root):
                asset = base / self.asset["path"]
                asset.write_bytes(b"not the retained pinned text")
                result = self.generate()
                self.assertEqual("failed", result["state"])
                self.assertTrue(any("bytes differ" in error for error in result["errors"]))
                asset.write_bytes(PUBLIC_TEXT)

    def test_required_attribution_file_is_compared_not_merely_present(self):
        (self.distribution / "THIRD_PARTY_NOTICES.md").write_text("wrong attribution text")
        result = self.generate()
        self.assertEqual("failed", result["state"])
        self.assertFalse(result["required_attribution_files"][0]["preserved_in_distribution"])

    def test_parent_and_leaf_symlink_decisions_fail_closed_without_native_acl_claim(self):
        # Pure path-validation infrastructure test on all hosts, not evidence of
        # Windows ACL enforcement or an authenticated host's private-state check.
        for linked in (self.root / self.asset["path"], self.root / "third_party"):
            with patch.object(Path, "is_symlink", new=lambda path: path == linked):
                _, errors = report_distribution.required_asset(self.asset, self.distribution, self.root)
            self.assertTrue(any("absent/unsafe" in error for error in errors))

    def test_policy_schema_types_paths_digests_and_regexes_are_fail_closed(self):
        changes = ({"schema_version": True}, {"owner_license": {}}, {"known_license_assets": []},
                   {"known_license_assets": {"Apache-2.0": [{"path": "../private", "digest": self.asset["digest"]}]}},
                   {"known_license_assets": {"Apache-2.0": [{"path": "safe", "digest": "sha256:missing"}]}},
                   {"notice_rules": [{"coordinate_pattern": "[", "required_assets": [], "review_complete": False}]},
                   {"notice_rules": [{"coordinate_pattern": ".*", "required_assets": [], "review_complete": "true"}]},
                   {"shaded_review_rules": {}}, {"required_distribution_files": "THIRD_PARTY_NOTICES.md"},
                   {"required_distribution_files": ["third_party//LICENSE"]}, {"required_distribution_files": ["private\x00"]})
        for change in changes:
            with self.subTest(keys=list(change)):
                with self.assertRaises(ValueError):
                    report_distribution.validate_policy({**self.policy, **change})
        with self.assertRaises(ValueError):
            report_distribution.validate_policy([])

    def test_manifest_schema_and_artifact_types_are_explicit(self):
        for change in ({"schemaVersion": True}, {"gradleVersion": "9.6.2"}, {"project": ":other"},
                       {"distribution": []}, {"artifacts": []}):
            with self.subTest(keys=list(change)), self.assertRaises(ValueError):
                report_distribution.validate_manifest({**self.manifest, **change}, self.distribution, self.root)
        for change in ({"coordinate": "filename-guessed"}, {"origin": []}, {"configuration": {}},
                       {"artifactIdentity": None}, {"file": []}, {"file": "relative.jar"}, {"pom": []}):
            with self.subTest(keys=list(change)), self.assertRaises(ValueError):
                report_distribution.validate_manifest({**self.manifest, "artifacts": [self.artifact(**change)]}, self.distribution, self.root)

    def test_original_owner_code_cannot_inherit_an_unrelated_maven_license(self):
        self.manifest["artifacts"][-1]["pom"] = str(self.pom)
        with self.assertRaises(ValueError):
            self.generate()

    def test_worker_inventory_uses_actual_task_bytes_without_a_license_grant(self):
        result = self.generate()
        worker = next(row for row in result["components"] if row["configurations"] == ["bundledCompilerModel"])
        self.assertEqual("dev.example:provider-compiler:0.1.0-preview.1", worker["coordinate"])
        self.assertEqual([report_distribution.model_worker_distribution.WORKER_RELATIVE], worker["distribution_paths"])
        self.assertEqual([], worker["pom"]["declared_licenses"])
        self.assertEqual("verified", result["compiler_model_worker"]["state"])
        self.assertEqual(worker["digest"], result["compiler_model_worker"]["built_digest"])
        self.assertFalse(result["compiler_model_worker"]["worker_execution"])
        self.assertFalse(result["release_qualified"])
        self.assertEqual("blocked", result["license_review_status"])

    def test_current_inventory_requires_one_exact_isolated_task_record(self):
        original = copy.deepcopy(self.manifest)
        worker = self.manifest["artifacts"][-2]
        rows = original["artifacts"]
        cases = [rows[:2] + rows[-1:], rows + [copy.deepcopy(worker)]]
        for change in ({"origin": "maven"}, {"artifactIdentity": ":provider-compiler:compilerApplicationWorkerJar"},
                       {"configuration": "runtimeClasspath"}, {"pom": str(self.pom)}, {"file": str(self.owner)}):
            cases.append(rows[:2] + [{**worker, **change}] + rows[-1:])
        cases.append(rows + [{**worker, "configuration": "runtimeClasspath", "artifactIdentity": "another mapping"}])
        for artifacts in cases:
            with self.subTest(index=cases.index(artifacts)), self.assertRaises(ValueError):
                report_distribution.validate_manifest({**original, "artifacts": artifacts}, self.distribution, self.root)

    def test_wrong_worker_bytes_or_location_cannot_pass_inventory(self):
        installed = self.distribution / report_distribution.model_worker_distribution.WORKER_RELATIVE
        original = installed.read_bytes()
        installed.write_bytes(original + b"different")
        result = self.generate()
        self.assertEqual("failed", result["state"])
        self.assertEqual("failed", result["compiler_model_worker"]["state"])
        installed.unlink()
        (self.distribution / "lib" / self.worker.name).write_bytes(original)
        result = self.generate()
        self.assertEqual("failed", result["state"])
        self.assertTrue(any("Compiler-model worker packaging failed" in error for error in result["errors"]))

    def test_foreign_worker_content_fails_even_when_task_and_installed_bytes_match(self):
        synthetic_worker(self.worker, extra={"dev/ktchecker/cli/Host.class": b"synthetic host"})
        shutil.copyfile(self.worker, self.distribution / report_distribution.model_worker_distribution.WORKER_RELATIVE)
        result = self.generate()
        self.assertEqual("failed", result["state"])
        self.assertEqual(["unreviewed_worker_file"], result["compiler_model_worker"]["errors"])

    def test_duplicate_json_keys_invalid_utf8_and_byte_limits_are_rejected(self):
        for raw in (b'{"schemaVersion":1,"schemaVersion":2}', b'{"text":"\xff"}'):
            self.args.dependencies.write_bytes(raw)
            with self.assertRaises(ValueError):
                report_distribution.document(self.args.dependencies, 1024)
        self.args.dependencies.write_bytes(b"{}")
        with self.assertRaises(ValueError):
            report_distribution.document(self.args.dependencies, 1)

    def test_embedded_legal_candidate_hashes_preserve_bytes_without_guessing_license(self):
        path = self.resolved / "embedded.jar"
        self.jar(path, {"META-INF/LICENSE.txt": b"public synthetic legal bytes", "Some.class": b"not a license"})
        assets = report_distribution.embedded_notices(path)
        self.assertEqual(1, len(assets))
        self.assertEqual(ci_common.digest_bytes(b"public synthetic legal bytes"), assets[0]["digest"])
        self.assertEqual("not_inferred_from_filename", assets[0]["license_interpretation"])
        self.assertNotIn("public synthetic legal bytes", json.dumps(assets))

    def test_unsafe_or_oversized_embedded_legal_entries_are_rejected(self):
        path = self.resolved / "unsafe.jar"
        for name, raw in (("../LICENSE.txt", b"public"), ("META-INF/NOTICE.txt", b"x" * (2 * 1024 * 1024 + 1))):
            self.jar(path, {name: raw})
            with self.assertRaises(ValueError):
                report_distribution.embedded_notices(path)

    def test_generated_sbom_notice_and_preview_vs_legal_gate_are_distinct(self):
        self.assertEqual(0, self.cli())
        result = json.loads(self.args.output.read_text())
        sbom = json.loads(self.args.sbom.read_text())
        self.assertEqual("CycloneDX", sbom["bomFormat"])
        self.assertEqual("1.6", sbom["specVersion"])
        self.assertEqual(3, len(sbom["components"]))
        self.assertIn({"name": "ktchecker:releaseQualified", "value": "false"}, sbom["metadata"]["properties"])
        self.assertIn("owner_license_unresolved", self.args.notice.read_text())
        self.assertNotIn(str(self.root), self.args.notice.read_text())
        self.assertEqual({ci_common.digest_file(self.args.sbom), ci_common.digest_file(self.args.notice)},
                         {row["digest"] for row in result["generated_reports"]})
        self.assertEqual(1, self.cli(strict=True))
        self.assertFalse(json.loads(self.args.output.read_text())["release_qualified"])

    def test_cli_inventory_errors_are_sanitized_failures_not_tracebacks_or_passes(self):
        self.policy["notice_rules"] = [{"coordinate_pattern": "[", "review_complete": False, "required_assets": []}]
        self.write_inputs()
        self.assertEqual(1, self.cli())
        result = json.loads(self.args.output.read_text())
        self.assertEqual("failed", result["state"])
        self.assertEqual(["Actual dependency inventory failed: ValueError"], result["errors"])
        self.assertNotIn(str(self.root), json.dumps(result))
        self.assertFalse(result["release_qualified"])


class DistributionBuildConfigurationTests(unittest.TestCase):
    """Read-only source contracts, not Gradle resolution or installed/runtime evidence."""

    COMPILER_ROOTS = (
        "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21",
        "org.jetbrains.kotlin:kotlin-build-tools-compat:2.4.21",
        "org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.21",
    )

    def test_preselected_model_compiler_roots_are_fixed_packaging_dependencies(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "modules/cli/build.gradle.kts").read_text(encoding="utf-8")
        # Match all direct declarations for the selected compiler/BTA families,
        # including a forbidden implementation/api/runtimeOnly duplicate.
        declarations = re.findall(
            r'^\s*(\w+)\("(org\.jetbrains\.kotlin:kotlin-'
            r'(?:compiler-(?:embeddable|runner)|build-tools-[\w-]+|tooling-core|daemon-client):[^"\n]+)"\)\s*$',
            source, re.MULTILINE,
        )
        self.assertEqual([("bundledCompiler", coordinate) for coordinate in self.COMPILER_ROOTS], declarations)

    def test_compiler_configuration_has_only_isolated_copy_and_manifest_uses(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "modules/cli/build.gradle.kts").read_text(encoding="utf-8")
        # This intentionally covers only the reviewed literal DSL surface, not
        # arbitrary executable Gradle syntax. New wiring requires renewed review.
        uses = [line.strip() for line in source.splitlines()
                if re.search(r'\bbundledCompiler\b', line) and not line.lstrip().startswith("//")]
        self.assertEqual([
            'val bundledCompiler = configurations.create("bundledCompiler") {',
            *(f'bundledCompiler("{coordinate}")' for coordinate in self.COMPILER_ROOTS),
            'from(bundledCompiler) { into("providers/compiler") }',
        ], uses)
        configuration = re.search(
            r'^val bundledCompiler = configurations\.create\("bundledCompiler"\) \{\n(.*?)^\}',
            source, re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(configuration)
        self.assertEqual(["isCanBeConsumed = false", "isCanBeResolved = true"],
                         [line.strip() for line in configuration.group(1).splitlines() if line.strip()])
        self.assertIn('from(bundledCompilerModel) { into("providers/compiler-model") }', source)
        manifest = (root / "scripts/ci/distribution-dependencies.init.gradle").read_text(encoding="utf-8")
        self.assertIn("['runtimeClasspath', 'bundledCompiler'].each { name ->", manifest)
        self.assertIn("configuration: 'bundledCompilerModel', artifactIdentity: modelWorker.get().path,", manifest)


if __name__ == "__main__":
    unittest.main()
