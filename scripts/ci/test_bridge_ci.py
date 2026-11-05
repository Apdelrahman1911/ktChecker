"""Bridge CI infrastructure tests; no npm, SDK, JVM, or model execution."""

import argparse
import copy
import io
import json
import os
from pathlib import Path
import re
import tarfile
import tempfile
import unittest
from unittest.mock import patch

try:
    from . import ci_common, run_bridge_checks
except ImportError:
    import ci_common
    import run_bridge_checks


IDENTITY = {"commit": "1" * 40, "dirty": False, "working_tree_digest": "sha256:" + "2" * 64}


class BridgeInfrastructureTests(unittest.TestCase):
    def pack(self, name="mcp"):
        return [{"name": run_bridge_checks.PACKAGE_NAMES[name], "version": run_bridge_checks.PACKAGE_VERSION,
                 "filename": run_bridge_checks.PACKAGE_NAMES[name].removeprefix("@").replace("/", "-") + "-" + run_bridge_checks.PACKAGE_VERSION + ".tgz",
                 "bundled": [], "files": [{"path": "package.json", "size": 4, "mode": 420},
                                             {"path": "dist/src/main.js", "size": 4, "mode": 420}]}]

    def package(self, name="mcp"):
        return {"name": run_bridge_checks.PACKAGE_NAMES[name], "version": run_bridge_checks.PACKAGE_VERSION,
                "private": True, **copy.deepcopy(run_bridge_checks.PINS[name])}

    def archive(self, path, name="mcp", extra=None):
        content = {"package.json": json.dumps(self.package(name)).encode(), "dist/src/main.js": b"// public built code\n"}
        with tarfile.open(path, "w:gz") as archive:
            for relative, raw in content.items():
                entry = tarfile.TarInfo("package/" + relative)
                entry.size = len(raw)
                archive.addfile(entry, io.BytesIO(raw))
            if extra:
                archive.addfile(extra)
        return {"files": [{"path": relative, "size": len(raw)} for relative, raw in content.items()]}

    def test_windows_npm_cmd_is_not_interpolated_or_executed(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / "Node Δ runtime"
            directory.mkdir()
            node = directory / "node.exe"
            command = directory / "npm.cmd"
            cli = directory / "node_modules/npm/bin/npm-cli.js"
            node.write_bytes(b"public node fixture")
            command.write_bytes(b"must not execute batch")
            cli.parent.mkdir(parents=True)
            cli.write_bytes(b"actual npm JS entrypoint fixture")
            with patch.object(run_bridge_checks.shutil, "which", side_effect=lambda name: str(node if name == "node" else command)):
                node_args, npm_args = run_bridge_checks.node_npm_argv()
            self.assertEqual([str(node.resolve())], node_args)
            self.assertEqual([str(node.resolve()), str(cli.resolve())], npm_args)
            self.assertNotIn(str(command), npm_args)

    def test_exact_package_lock_direct_pins_are_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            package = self.package()
            lock = {"lockfileVersion": 3, "packages": {"": package}}
            for scope in run_bridge_checks.PINS["mcp"].values():
                for dependency, version in scope.items():
                    lock["packages"]["node_modules/" + dependency] = {"version": version, "integrity": "sha512-actual-locked-integrity", "license": "declared-only"}
            (directory / "package.json").write_text(json.dumps(package))
            (directory / "package-lock.json").write_text(json.dumps(lock))
            (directory / ".node-version").write_text(run_bridge_checks.NODE_VERSION)
            result, errors = run_bridge_checks.lock_profile(directory, "mcp")
            self.assertEqual([], errors)
            self.assertTrue(result["lock_digest"].startswith("sha256:"))
            package["dependencies"]["@modelcontextprotocol/server"] = "^2.4.0"
            (directory / "package.json").write_text(json.dumps(package))
            _, errors = run_bridge_checks.lock_profile(directory, "mcp")
            self.assertTrue(errors)

    def test_node_summary_counts_actual_occurrences_not_qualifications(self):
        for prefix in ("#", "ℹ"):
            output = "\n".join(f"{prefix} {key} {count}" for key, count in
                               (("tests", 4), ("suites", 0), ("pass", 2), ("fail", 0), ("cancelled", 0), ("skipped", 1), ("todo", 1)))
            counts, errors = run_bridge_checks.test_counts(output)
            self.assertEqual([], errors)
            self.assertEqual(1, counts["skipped"])
            self.assertEqual(2, counts["pass"])
        self.assertTrue(run_bridge_checks.test_counts("npm test succeeded")[1])
        self.assertTrue(run_bridge_checks.test_counts(output.replace("tests 4", "tests 100"))[1])
        self.assertTrue(run_bridge_checks.test_counts(output.replace("pass 2", "pass 1").replace("fail 0", "fail 1"))[1])
        self.assertTrue(run_bridge_checks.test_counts(output + "\n# tests 4")[1])

    def test_node_junit_retains_public_failed_identity_and_known_code_not_private_details(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "node.xml"
            path.write_text('<testsuites><testsuite tests="999"><testcase name="public protocol case" classname="PRIVATE class">'
                            '<failure type="testCodeFailure" message="PRIVATE token private_state_platform_unqualified">'
                            'PRIVATE stack source terminal credential</failure><system-out>PRIVATE stdout</system-out></testcase>'
                            '<testcase name="public pass"/><testcase name="public skip"><skipped/></testcase></testsuite></testsuites>',
                            encoding="utf-8")
            result, errors = run_bridge_checks.junit_failures(path, {"public protocol case": "bridges/mcp/test/protocol.test.ts"},
                                                             {"tests": 3, "pass": 1, "fail": 1, "skipped": 1})
            self.assertEqual([], errors)
            self.assertEqual(ci_common.digest_file(path), result["report_digest"])
            self.assertEqual(3, result["case_counts"]["tests"])
            self.assertEqual(["private_state_platform_unqualified"], result["failed_cases"][0]["reason_codes"])
            self.assertEqual(["testCodeFailure"], result["failed_cases"][0]["failure_types"])
            self.assertNotIn("PRIVATE", json.dumps(result))
            self.assertFalse(result["raw_report_uploaded"])

    def test_unknown_or_excess_failed_node_names_are_omitted_not_uploaded(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "node.xml"
            known = {"public " + str(index): "bridges/mcp/test/protocol.test.ts" for index in range(70)}
            cases = ['<testcase name="' + name + '"><failure message="PRIVATE secret"/></testcase>' for name in known]
            cases.append('<testcase name="PRIVATE unknown runtime name"><error type="PRIVATE type"/></testcase>')
            path.write_text("<testsuite>" + "".join(cases) + "</testsuite>", encoding="utf-8")
            result, errors = run_bridge_checks.junit_failures(path, known, {"tests": 71, "pass": 0, "fail": 71})
            self.assertEqual([], errors)
            self.assertEqual(64, len(result["failed_cases"]))
            self.assertEqual(1, result["unknown_failed_case_occurrences"])
            self.assertEqual(6, result["bounded_omitted_failed_case_occurrences"])
            self.assertNotIn("PRIVATE", json.dumps(result))

    def test_native_helper_fixed_failure_codes_do_not_upload_lookalikes_or_private_details(self):
        codes = {"private_path_unsupported", "private_path_alias_unsupported", "private_acl_unavailable", "unsafe_state_path",
                 "state_permissions", "private_parent_permissions", "state_directory_in_use", "private_operation_unsupported",
                 "native_windows_required", "private_helper_command_bound", "native_windows_control_setup_failed"}
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "native.xml"
            path.write_text('<testsuite><testcase name="public native ACL case"><failure message="PRIVATE token">'
                            + " ".join(sorted(codes)) + ' PRIVATE_private_acl_unavailable_suffix PRIVATE source path'
                            + '</failure></testcase></testsuite>', encoding="utf-8")
            result, errors = run_bridge_checks.junit_failures(path, {"public native ACL case": "bridges/mcp/test/windows-private-state.test.ts"},
                                                             {"tests": 1, "pass": 0, "fail": 1})
            self.assertEqual([], errors)
            self.assertEqual(sorted(codes), result["failed_cases"][0]["reason_codes"])
            self.assertNotIn("PRIVATE", json.dumps(result))

    def test_node_junit_count_mismatch_unsafe_xml_and_wrong_roots_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "node.xml"
            path.write_text('<testsuite><testcase name="public case"/></testsuite>', encoding="utf-8")
            self.assertTrue(run_bridge_checks.junit_failures(path, {}, {"tests": 100, "pass": 100})[1])
            for raw in (b'<!DOCTYPE testsuite [<!ENTITY private SYSTEM "file:///private">]><testsuite/>',
                        b'<arbitrary/>', b'x' * (run_bridge_checks.MAX_JUNIT_BYTES + 1)):
                path.write_bytes(raw)
                with self.assertRaises(ValueError):
                    run_bridge_checks.junit_failures(path, {}, {})

    def test_node_report_options_are_quoted_data_not_shell_or_newline_interpolation(self):
        path = Path("public Node Δ reports/node.junit.xml")
        result = run_bridge_checks.test_report_environment({"TOKEN_SECRET": "PRIVATE", "NODE_OPTIONS": "--max-old-space-size=512"}, path)
        self.assertIn("--test-reporter=spec", result["NODE_OPTIONS"])
        self.assertIn("--test-reporter=junit", result["NODE_OPTIONS"])
        self.assertIn(json.dumps(path.resolve().as_posix(), ensure_ascii=False), result["NODE_OPTIONS"])
        self.assertTrue(result["NODE_OPTIONS"].startswith("--max-old-space-size=512 "))
        self.assertNotIn("PRIVATE", result["NODE_OPTIONS"])
        with self.assertRaises(ValueError):
            run_bridge_checks.test_report_environment({}, Path("report\nmalicious.xml"))

    def test_only_unique_authored_literal_public_node_cases_are_inventoried(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            test_dir = root / "bridges/mcp/test"
            test_dir.mkdir(parents=True)
            (test_dir / "first.test.ts").write_text("test('public case',()=>{});\ntest('ambiguous',()=>{});\n"
                                                 "test(dynamicPrivateName,()=>{});\n", encoding="utf-8")
            (test_dir / "second.test.ts").write_text("test('ambiguous',()=>{});\n", encoding="utf-8")
            with patch.object(run_bridge_checks, "REPO_ROOT", root):
                cases = run_bridge_checks.public_test_cases("mcp")
            self.assertEqual({"public case": "bridges/mcp/test/first.test.ts"}, cases)
            self.assertNotIn("ambiguous", cases)

    def test_actual_audit_must_have_zero_consistent_vulnerability_counts(self):
        audit = {"auditReportVersion": 2, "metadata": {"vulnerabilities": {key: 0 for key in ("info", "low", "moderate", "high", "critical", "total")}}}
        self.assertEqual([], run_bridge_checks.audit_summary(json.dumps(audit))[1])
        audit["metadata"]["vulnerabilities"].update(high=1, total=1)
        self.assertTrue(run_bridge_checks.audit_summary(json.dumps(audit))[1])
        self.assertTrue(run_bridge_checks.audit_summary('{"error":{"code":"network_unavailable"}}')[1])
        self.assertTrue(run_bridge_checks.audit_summary('[]')[1])

    def test_npm_dry_run_requires_safe_built_package_and_pinned_identity(self):
        value = self.pack()
        result, errors = run_bridge_checks.pack_manifest(json.dumps(value), "mcp")
        self.assertEqual([], errors)
        self.assertFalse(result["release_qualified"])
        for bad in ("evidence/public-but-unapproved-request.json", "dist/test/fixture.js", "../secret", "config.private.json", "node_modules/sdk/index.js"):
            value = self.pack()
            value[0]["files"].append({"path": bad, "size": 1})
            self.assertTrue(run_bridge_checks.pack_manifest(json.dumps(value), "mcp")[1])
        value = self.pack()
        value[0]["version"] = "1.0.0"
        self.assertTrue(run_bridge_checks.pack_manifest(json.dumps(value), "mcp")[1])

    def test_real_tarball_hashes_actual_files_and_preserves_private_preview_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "preview.tgz"
            dry = self.archive(path)
            result, errors = run_bridge_checks.verify_tarball(path, "mcp", dry)
            self.assertEqual([], errors)
            self.assertEqual(ci_common.digest_file(path), result["digest"])
            self.assertEqual(2, len(result["files"]))
            self.assertFalse(result["release_qualified"])
            self.assertTrue(run_bridge_checks.verify_tarball(path, "mcp", {"files": []})[1])

    def test_tarball_links_and_traversal_are_rejected_without_extracting(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "preview.tgz"
            for entry in (tarfile.TarInfo("package/../private"), tarfile.TarInfo("package/dist/src/link.js")):
                entry.type = tarfile.SYMTYPE
                entry.linkname = "/private"
                dry = self.archive(path, extra=entry)
                with self.assertRaises(ValueError):
                    run_bridge_checks.verify_tarball(path, "mcp", dry)

    def test_failed_or_stale_job_cannot_finalize_passing_bridge_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "bridge.json"
            for status, source in (("failure", IDENTITY), ("success", {**IDENTITY, "working_tree_digest": "stale"})):
                path.write_text(json.dumps({"state": "passed", "errors": [], "source": source, "stages": []}))
                with patch.object(run_bridge_checks, "source_identity", return_value=IDENTITY), patch.dict(os.environ, {"KT_CI_JOB_STATUS": status}):
                    result = run_bridge_checks.finalize(path)
                self.assertEqual("incomplete", result["state"])
                self.assertTrue(result["errors"])


class BridgeUploadWorkflowTests(unittest.TestCase):
    """Fixed authored stanzas only; no YAML evaluation, npm launch or publication."""

    build_name = "Execute npm ci, actual SDK/synthetic-host tests/audits and validated unsigned npm packs"
    finalize_name = "Bind sanitized bridge evidence to actual source and job outcome"
    evidence_name = "Retain sanitized case counts, exact lock pins and audit outcomes"
    upload_name = "Hold npm archive upload pending bridge-specific publication clearance"
    runner = "python scripts/ci/run_bridge_checks.py"
    evidence_path = "build/ci-evidence/bridges.json"
    archive_paths = (
        "build/ci-packages/ktchecker-mcp-preview-0.1.0-preview.1.tgz",
        "build/ci-packages/ktchecker-reference-host-preview-0.1.0-preview.1.tgz",
    )
    upload_action = "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02"

    def document(self):
        return (ci_common.REPO_ROOT / ".github/workflows/bridges-validation.yml").read_text(encoding="utf-8")

    def step(self, document, name):
        # This is deliberately not a general YAML parser.
        rows = re.findall(r"^      - name: " + re.escape(name) + r"\n([\s\S]*?)(?=^      - |\Z)",
                          document, re.MULTILINE)
        self.assertEqual(1, len(rows), name)
        return rows[0]

    def conditions(self, step):
        return re.findall(r"^        if: ([^\n]+)$", step, re.MULTILINE)

    def artifact_paths(self, step):
        fields = re.findall(r"^          path: ([^\n]+)$", step, re.MULTILINE)
        self.assertEqual(1, len(fields))
        if fields == ["|"]:
            body = re.findall(r"^          path: \|\n((?:            [^\n]*\n)+)", step, re.MULTILINE)
            self.assertEqual(1, len(body))
            return [line.strip() for line in body[0].splitlines()]
        return fields

    def assert_upload_hold(self, document):
        evidence = self.step(document, self.evidence_name)
        archive = self.step(document, self.upload_name)
        # No variable, unsigned/preview label, or unrelated JVM review can opt in.
        self.assertEqual(["${{ false }}"], self.conditions(archive))
        self.assertEqual(["always()"], self.conditions(evidence))
        self.assertEqual([self.evidence_path], self.artifact_paths(evidence))
        self.assertEqual(list(self.archive_paths), self.artifact_paths(archive))
        # Pin every authored action so a second upload path cannot silently appear.
        self.assertEqual([
            "actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09",
            "actions/setup-python@ece7cb06caefa5fff74198d8649806c4678c61a1",
            "actions/setup-node@249970729cb0ef3589644e2896645e5dc5ba9c38",
            self.upload_action, self.upload_action,
        ], re.findall(r"^\s*(?:-\s+)?uses:\s+([^\s]+)", document, re.MULTILINE))
        for step in (evidence, archive):
            self.assertNotIn("continue-on-error:", step)
            self.assertEqual([self.upload_action], re.findall(r"^        uses: ([^\s]+)", step, re.MULTILINE))
            self.assertEqual(["error"], re.findall(r"^          if-no-files-found: ([^\n]+)$", step, re.MULTILINE))
            self.assertEqual(["14"], re.findall(r"^          retention-days: ([^\n]+)$", step, re.MULTILINE))

    def assert_validation_scope(self, document):
        jobs = document.split("\njobs:\n")
        self.assertEqual(2, len(jobs))
        self.assertEqual(["bridge-controls"], re.findall(r"^  ([a-z][a-z0-9-]*):$", jobs[1], re.MULTILINE))
        self.assertNotRegex(jobs[1], r"(?m)^    if:")
        self.assertNotIn("continue-on-error:", document)
        self.assertEqual(1, document.count("        os: [ubuntu-24.04, windows-2025, macos-26]\n"))
        build = self.step(document, self.build_name)
        finalize = self.step(document, self.finalize_name)
        self.assertEqual([], self.conditions(build))
        self.assertEqual([self.runner + " --output " + self.evidence_path],
                         re.findall(r"^        run: ([^\n]+)$", build, re.MULTILINE))
        self.assertEqual(["always()"], self.conditions(finalize))
        self.assertEqual([self.runner + " --finalize --output " + self.evidence_path],
                         re.findall(r"^        run: ([^\n]+)$", finalize, re.MULTILINE))
        self.assertIn("          KT_CI_JOB_STATUS: ${{ job.status }}\n", finalize)
        self.assertEqual(["always()"], self.conditions(self.step(document, self.evidence_name)))

    def test_bridge_package_upload_is_held_without_claiming_delivery(self):
        self.assert_upload_hold(self.document())

    def test_upload_hold_preserves_all_host_controls_and_failure_evidence(self):
        self.assert_validation_scope(self.document())

    def test_upload_guard_rejects_reenablement_and_alternate_archive_paths(self):
        document = self.document()
        hold = "        if: ${{ false }}\n"
        evidence = "          path: " + self.evidence_path + "\n"
        candidates = {
            "success": document.replace(hold, "        if: success()\n", 1),
            "true": document.replace(hold, "        if: ${{ true }}\n", 1),
            "missing_hold": document.replace(hold, "", 1),
            "unrelated_jvm_approval": document.replace(hold,
                "        if: success() && vars.KTC_PREVIEW_DISTRIBUTION_UPLOAD_APPROVED == 'true'"
                " && steps.distribution_upload_license_review.outcome == 'success'\n", 1),
            "broad_evidence_glob": document.replace(evidence, "          path: build/**\n", 1),
            "archive_as_evidence": document.replace(evidence, "          path: " + self.archive_paths[0] + "\n", 1),
            "archive_in_evidence_list": document.replace(evidence,
                "          path: |\n            " + self.evidence_path + "\n            build/ci-packages/*.tgz\n", 1),
            "extra_upload": document + "      - uses: " + self.upload_action
                + "\n        with:\n          name: unapproved-packages\n          path: build/ci-packages/*.tgz\n",
        }
        for name, candidate in candidates.items():
            with self.subTest(mutation=name):
                self.assertNotEqual(document, candidate)
                with self.assertRaises(AssertionError):
                    self.assert_upload_hold(candidate)

    def test_scope_guard_rejects_skipping_controls_or_failure_evidence(self):
        document = self.document()
        run = "        run: " + self.runner + " --output " + self.evidence_path + "\n"
        mutations = (
            ("  bridge-controls:\n", "  bridge-controls:\n    if: ${{ false }}\n"),
            ("os: [ubuntu-24.04, windows-2025, macos-26]", "os: [ubuntu-24.04, macos-26]"),
            (run, "        if: ${{ false }}\n" + run),
            (run, "        continue-on-error: true\n" + run),
            ("- name: " + self.finalize_name + "\n        if: always()",
             "- name: " + self.finalize_name + "\n        if: success()"),
            ("- name: " + self.evidence_name + "\n        if: always()",
             "- name: " + self.evidence_name + "\n        if: success()"),
        )
        for old, new in mutations:
            with self.subTest(mutation=old):
                candidate = document.replace(old, new, 1)
                self.assertNotEqual(document, candidate)
                with self.assertRaises(AssertionError):
                    self.assert_validation_scope(candidate)


if __name__ == "__main__":
    unittest.main()
