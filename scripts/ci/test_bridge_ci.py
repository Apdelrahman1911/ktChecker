"""Bridge CI infrastructure tests; no npm, SDK, JVM, or model execution."""

import argparse
import copy
import io
import json
import os
from pathlib import Path
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


if __name__ == "__main__":
    unittest.main()
