"""Bridge CI infrastructure tests; no npm, SDK, JVM, or model execution."""

import argparse
import copy
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import re
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

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
        reporter = (run_bridge_checks.REPO_ROOT / "scripts/ci/node_test_events.mjs").resolve().as_uri()
        self.assertIn("--test-reporter=" + json.dumps(reporter, ensure_ascii=False), result["NODE_OPTIONS"])
        self.assertIn(json.dumps(path.with_suffix(".events.jsonl").resolve().as_posix(), ensure_ascii=False), result["NODE_OPTIONS"])
        self.assertEqual(3, result["NODE_OPTIONS"].count("--test-reporter-destination="))
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


class BridgeTerminalEventTests(unittest.TestCase):
    """Synthetic DATA for the pinned Node reporters; no Node process is launched."""

    source = "bridges/mcp/test/protocol.test.ts"

    def row(self, name, nesting=0, **updates):
        return {"ordinal": 0, "name": name,
                "file": str(run_bridge_checks.REPO_ROOT / "bridges/mcp/dist/test/protocol.test.js"),
                "nesting": nesting, "test_type": "test", "event": "pass", "skip": False, "todo": False,
                "junit_skip": False, "junit_todo": False, "failure_type": None, "reason_codes": [], **updates}

    def leaf(self, name, failure=False, directives=(), file=None):
        node = ET.Element("testcase", {"name": name, "file": file or self.row(name)["file"]})
        for directive in directives:
            ET.SubElement(node, "skipped", {"type": directive, "message": "PRIVATE skip reason"})
        if failure:
            ET.SubElement(node, "failure", {"type": "testCodeFailure", "message": "PRIVATE body ERR_ASSERTION"}).text = "PRIVATE stack"
        return node

    def parent(self, name, children, failures=0, skipped=0):
        # These are direct-child counters, not counts of all descendants or of
        # the parent's own outcome (Node v24.20.0's junit.js).
        node = ET.Element("testsuite", {"name": name, "tests": str(len(children)), "failures": str(failures),
                                       "skipped": str(skipped), "errors": "0", "disabled": "0"})
        node.extend(children)
        return node

    def counts(self, **updates):
        return {"tests": 1, "suites": 0, "pass": 1, "fail": 0, "cancelled": 0, "skipped": 0, "todo": 0, **updates}

    def reports(self, directory, nodes, rows):
        document = ET.Element("testsuites")
        document.extend(nodes)
        report = Path(directory) / "node.junit.xml"
        report.write_bytes(ET.tostring(document, encoding="utf-8"))
        values = [{"schema": "ktchecker-node-terminal-events/1", "node_version": run_bridge_checks.NODE_VERSION},
                  *({**row, "ordinal": index} for index, row in enumerate(rows, 1)),
                  {"complete": True, "occurrences": len(rows)}]
        events = report.with_suffix(".events.jsonl")
        events.write_text("".join(json.dumps(value) + "\n" for value in values), encoding="utf-8")
        return report, events

    def test_parent_pass_is_observed_not_inferred_from_two_passing_junit_leaves(self):
        nodes = [self.parent("public parent", [self.leaf("child one"), self.leaf("child two")])]
        rows = [self.row("child one", 1), self.row("child two", 1), self.row("public parent")]
        with tempfile.TemporaryDirectory() as temporary:
            report, events = self.reports(temporary, nodes, rows)
            counts = self.counts(tests=3, **{"pass": 3})
            result, errors = run_bridge_checks.junit_failures(report, {"public parent": self.source}, counts, events)
            self.assertEqual([], errors)
            self.assertTrue(run_bridge_checks.junit_failures(report, {}, counts)[1])
            self.assertEqual(2, result["case_counts"]["tests"])
            terminal = result["terminal_events"]
            self.assertEqual(ci_common.digest_file(events), terminal["report_digest"])
            self.assertEqual(3, terminal["counts"]["tests"])
            self.assertEqual(2, terminal["leaf_test_occurrences"])
            self.assertEqual(1, terminal["parent_test_occurrences"])
            self.assertEqual(3, terminal["terminal_occurrences"])
            self.assertEqual((3, "pass", "pass"), tuple(terminal["public_parent_occurrences"][0][key]
                                                      for key in ("occurrence", "state", "terminal_event")))
            self.assertFalse(terminal["raw_report_uploaded"])

    def test_nested_parents_can_fail_after_all_junit_leaf_children_pass(self):
        nodes = [self.parent("public outer", [self.parent("public inner", [self.leaf("public child")])])]
        rows = [self.row("public child", 2),
                self.row("public inner", 1, event="fail", failure_type="testCodeFailure", reason_codes=["ERR_ASSERTION"]),
                self.row("public outer", event="fail", failure_type="subtestsFailed", reason_codes=["ERR_TEST_FAILURE"])]
        with tempfile.TemporaryDirectory() as temporary:
            report, events = self.reports(temporary, nodes, rows)
            result, errors = run_bridge_checks.junit_failures(report, {name: self.source for name in ("public outer", "public inner")},
                                                             self.counts(tests=3, fail=2), events)
            self.assertEqual([], errors)
            self.assertEqual(0, result["case_counts"]["failure"])
            self.assertEqual(["public inner", "public outer"], [row["name"] for row in result["failed_cases"]])
            self.assertEqual([2, 3], [row["occurrence"] for row in result["failed_cases"]])
            self.assertEqual([["testCodeFailure"], ["subtestsFailed"]], [row["failure_types"] for row in result["failed_cases"]])
            self.assertEqual(2, result["terminal_events"]["counts"]["fail_or_cancelled"])

    def test_skipped_todo_and_cancelled_parent_outcomes_do_not_come_from_junit_rollups(self):
        variants = (({"skip": True, "junit_skip": True}, {"skipped": 1}, "skipped"),
                    ({"todo": True, "junit_todo": True, "event": "fail"}, {"todo": 1}, "todo"),
                    ({"event": "fail", "failure_type": "cancelledByParent"}, {"cancelled": 1}, "fail_or_cancelled"))
        for event, extra_counts, state in variants:
            with self.subTest(state=state), tempfile.TemporaryDirectory() as temporary:
                nodes = [self.parent("public parent", [self.leaf("child")])]
                report, events = self.reports(temporary, nodes, [self.row("child", 1), self.row("public parent", **event)])
                result, errors = run_bridge_checks.junit_failures(report, {"public parent": self.source},
                                                                 self.counts(tests=2, **extra_counts), events)
                self.assertEqual([], errors)
                parent = result["terminal_events"]["public_parent_occurrences"][0]
                self.assertEqual(state, parent["state"])
                self.assertEqual(event.get("event", "pass"), parent["terminal_event"])
                self.assertEqual(0, result["case_counts"]["failure"])
                self.assertEqual(0, result["case_counts"]["skipped"])
                if state == "fail_or_cancelled":
                    self.assertEqual(["cancelledByParent"], parent["failure_types"])
                    self.assertIn("fail/cancelled split remains the ordinary Node summary", result["terminal_events"]["failure_count_scope"])

    def test_empty_suite_is_a_junit_leaf_but_not_a_node_test(self):
        rows = [self.row("public empty suite", test_type="suite"), self.row("public test")]
        with tempfile.TemporaryDirectory() as temporary:
            report, events = self.reports(temporary, [self.leaf(row["name"]) for row in rows], rows)
            result, errors = run_bridge_checks.junit_failures(report, {}, self.counts(suites=1), events)
            self.assertEqual([], errors)
            terminal = result["terminal_events"]
            self.assertEqual(2, result["case_counts"]["tests"])
            self.assertEqual(1, terminal["leaf_test_occurrences"])
            self.assertEqual(1, terminal["leaf_suite_occurrences"])
            self.assertEqual(0, terminal["parent_suite_occurrences"])

    def test_failed_suite_is_not_hidden_by_passing_child_or_zero_node_test_failures(self):
        for directives, failed_suites in (({}, 1), ({"skip": True, "junit_skip": True}, 1),
                                          ({"todo": True, "junit_todo": True}, 0)):
            with self.subTest(directives=directives), tempfile.TemporaryDirectory() as temporary:
                nodes = [self.parent("public suite", [self.leaf("public child")])]
                rows = [self.row("public child", 1), self.row("public suite", test_type="suite", event="fail", **directives)]
                report, events = self.reports(temporary, nodes, rows)
                result, errors = run_bridge_checks.junit_failures(report, {"public suite": self.source}, self.counts(suites=1), events)
                self.assertEqual(bool(failed_suites), bool(errors))
                self.assertEqual(failed_suites, result["terminal_events"]["failed_suite_occurrences"])
                self.assertEqual(1, result["terminal_events"]["parent_suite_occurrences"])
                self.assertEqual(0, result["terminal_events"]["parent_test_occurrences"])
                self.assertEqual("fail", result["failed_cases"][0]["terminal_event"])
                self.assertEqual(0, result["case_counts"]["failure"])

    def test_empty_string_directives_have_node_presence_but_no_junit_skipped_tag(self):
        rows = [self.row("public skip", skip=True), self.row("public todo", todo=True, event="fail"), self.row("public pass")]
        nodes = [self.leaf("public skip"), self.leaf("public todo", failure=True), self.leaf("public pass")]
        with tempfile.TemporaryDirectory() as temporary:
            report, events = self.reports(temporary, nodes, rows)
            result, errors = run_bridge_checks.junit_failures(report, {"public todo": self.source},
                                                             self.counts(tests=3, skipped=1, todo=1), events)
            self.assertEqual([], errors)
            self.assertEqual(0, result["case_counts"]["skipped"])
            self.assertEqual(1, result["terminal_events"]["counts"]["skipped"])
            self.assertEqual(1, result["terminal_events"]["counts"]["todo"])
            self.assertEqual(0, result["terminal_events"]["counts"]["fail_or_cancelled"])

    def test_duplicate_names_remain_distinct_ordered_terminal_occurrences(self):
        nodes = [self.parent("public parent", [self.leaf("public child")]),
                 self.parent("public parent", [self.leaf("public child", failure=True)], failures=1)]
        rows = [self.row("public child", 1), self.row("public parent"),
                self.row("public child", 1, event="fail"), self.row("public parent", event="fail", failure_type="subtestsFailed")]
        known = {"public parent": self.source, "public child": self.source}
        counts = self.counts(tests=4, **{"pass": 2, "fail": 2})
        with tempfile.TemporaryDirectory() as temporary:
            report, events = self.reports(temporary, nodes, rows)
            result, errors = run_bridge_checks.junit_failures(report, known, counts, events)
            self.assertEqual([], errors)
            self.assertEqual([2, 4], [row["occurrence"] for row in result["terminal_events"]["public_parent_occurrences"]])
            self.assertEqual([3, 4], [row["occurrence"] for row in result["failed_cases"]])
            self.assertEqual(4, result["terminal_events"]["terminal_occurrences"])
            mutations = [rows[:-1], rows + [rows[-1]], [rows[2], rows[1], rows[0], rows[3]],
                         [{**rows[0], "name": "different"}, *rows[1:]], [{**rows[0], "nesting": 0}, *rows[1:]],
                         [{**rows[0], "file": "PRIVATE wrong runtime file"}, *rows[1:]]]
            for mutation in mutations:
                with self.subTest(mutation=mutations.index(mutation)):
                    report, events = self.reports(temporary, nodes, mutation)
                    with self.assertRaises(ValueError):
                        run_bridge_checks.junit_failures(report, known, counts, events)

    def test_junit_rollup_file_outcome_and_tree_mutations_are_rejected(self):
        base = self.parent("public parent", [self.leaf("public child")])
        rows = [self.row("public child", 1), self.row("public parent")]
        mutations = []
        for key in ("tests", "failures", "skipped", "errors", "disabled"):
            node = copy.deepcopy(base)
            node.set(key, "99")
            mutations.append(node)
        for tag in ("error", "system-out", "testcase"):
            node = copy.deepcopy(base)
            ET.SubElement(node[0], tag)
            mutations.append(node)
        node = copy.deepcopy(base)
        node[0].set("file", "PRIVATE other file")
        mutations.append(node)
        mutations.append(self.parent("public parent", [self.leaf("public child", failure=True)], failures=1))
        mutations.append(self.parent("public parent", [self.leaf("public child", directives=("skipped",))], skipped=1))
        mutations.append(self.parent("public parent", []))
        for index, node in enumerate(mutations):
            with self.subTest(mutation=index), tempfile.TemporaryDirectory() as temporary:
                report, events = self.reports(temporary, [node], rows)
                with self.assertRaises(ValueError):
                    run_bridge_checks.junit_failures(report, {}, self.counts(tests=2, **{"pass": 2}), events)

    def test_private_unknown_and_wrong_source_names_are_counted_but_never_projected(self):
        for name in ("PRIVATE runtime parent", "public parent"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                private_file = "PRIVATE foreign runtime file"
                nodes = [self.parent(name, [self.leaf("public leaf", failure=True, file=private_file)], failures=1)]
                rows = [self.row("public leaf", 1, file=private_file, event="fail"),
                        self.row(name, file=private_file, event="fail", failure_type="subtestsFailed", reason_codes=["ERR_TEST_FAILURE"])]
                report, events = self.reports(temporary, nodes, rows)
                result, errors = run_bridge_checks.junit_failures(report, {"public parent": self.source, "public leaf": self.source},
                                                                 self.counts(tests=2, **{"pass": 0, "fail": 2}), events)
                self.assertEqual([], errors)
                self.assertEqual([], result["failed_cases"])
                self.assertEqual(2, result["unknown_failed_case_occurrences"])
                self.assertEqual([], result["terminal_events"]["public_parent_occurrences"])
                self.assertEqual(1, result["terminal_events"]["unknown_parent_occurrences"])
                self.assertNotIn("PRIVATE", json.dumps(result))

    def test_public_parent_and_failure_caps_do_not_drop_occurrence_counts(self):
        names = ["public parent " + str(index) for index in range(70)] + ["PRIVATE unknown parent"]
        nodes, rows = [], []
        for name in names:
            nodes.append(self.parent(name, [self.leaf("PRIVATE unknown child")]))
            rows.extend((self.row("PRIVATE unknown child", 1), self.row(name, event="fail")))
        with tempfile.TemporaryDirectory() as temporary:
            report, events = self.reports(temporary, nodes, rows)
            result, errors = run_bridge_checks.junit_failures(report, {name: self.source for name in names[:-1]},
                                                             self.counts(tests=142, **{"pass": 71, "fail": 71}), events)
            self.assertEqual([], errors)
            self.assertEqual(142, result["terminal_events"]["terminal_occurrences"])
            self.assertEqual(64, len(result["terminal_events"]["public_parent_occurrences"]))
            self.assertEqual(6, result["terminal_events"]["bounded_omitted_public_parent_occurrences"])
            self.assertEqual(1, result["terminal_events"]["unknown_parent_occurrences"])
            self.assertEqual(64, len(result["failed_cases"]))
            self.assertEqual(6, result["bounded_omitted_failed_case_occurrences"])
            self.assertEqual(1, result["unknown_failed_case_occurrences"])
            self.assertNotIn("PRIVATE", json.dumps(result))

    def test_all_terminal_counter_categories_are_checked_against_ordinary_node_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            report, events = self.reports(temporary, [self.leaf("public test")], [self.row("public test")])
            for key in ("tests", "suites", "pass", "fail", "cancelled", "skipped", "todo"):
                counts = self.counts()
                counts[key] += 1
                with self.subTest(counter=key):
                    _, errors = run_bridge_checks.junit_failures(report, {}, counts, events)
                    self.assertIn("Actual Node terminal occurrences differ from the ordinary test summary", errors)

    def test_sidecar_shape_types_ordinals_and_fixed_code_vocabulary_fail_closed(self):
        malformed = {"ordinal": (True, 2), "name": (None, "x" * 4097, "\U0001f642" * 2049), "file": (1, "x" * 8193, "\U0001f642" * 4097),
                     "nesting": (True, -1, 129), "test_type": ([], "hook"), "event": ("complete", None),
                     "skip": ("yes",), "todo": (0,), "junit_skip": (True,), "junit_todo": (True,),
                     "failure_type": ([], {}, "PRIVATE unknown type"),
                     "reason_codes": ({}, [None], [{}], ["PRIVATE secret"], ["ERR_ASSERTION", "ERR_ASSERTION"], ["EPERM", "ENOENT"])}
        with tempfile.TemporaryDirectory() as temporary:
            report, events = self.reports(temporary, [self.leaf("public test")], [self.row("public test")])
            original = [json.loads(line) for line in events.read_text(encoding="utf-8").splitlines()]
            for key, candidates in malformed.items():
                for candidate in candidates:
                    with self.subTest(field=key, value_type=type(candidate).__name__):
                        values = copy.deepcopy(original)
                        values[1][key] = candidate
                        events.write_text("".join(json.dumps(value) + "\n" for value in values), encoding="utf-8")
                        with self.assertRaises(ValueError):
                            run_bridge_checks.junit_failures(report, {}, self.counts(), events)
            for row in (["not an object"], {**original[1], "PRIVATE extra": 1}, {key: value for key, value in original[1].items() if key != "name"},
                        {**original[1], "skip": True, "todo": True}):
                events.write_text("".join(json.dumps(value) + "\n" for value in (original[0], row, original[2])), encoding="utf-8")
                with self.assertRaises(ValueError):
                    run_bridge_checks.junit_failures(report, {}, self.counts(), events)

    def test_sidecar_header_footer_duplicate_keys_truncation_and_bounds_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            report, events = self.reports(temporary, [self.leaf("public test")], [self.row("public test")])
            raw = events.read_bytes()
            lines = raw.splitlines(keepends=True)
            malformed = [b"", raw[:-1], b"".join(lines[:-1]), raw + b"\n", raw.replace(b'"ordinal": 1', b'"ordinal": 1, "ordinal": 1'),
                         raw.replace(b'"complete": true', b'"complete": 1'), raw.replace(b'"occurrences": 1', b'"occurrences": true'),
                         raw.replace(b'"occurrences": 1', b'"occurrences": 2'), raw.replace(b'"complete": true', b'"complete": false'),
                         raw.replace(run_bridge_checks.NODE_VERSION.encode(), b"0.0.0"),
                         raw.replace(b"ktchecker-node-terminal-events/1", b"ktchecker-node-terminal-events/9"),
                         lines[0] + b'{}\n' + lines[1] + lines[2],
                         lines[0] + b'{"padding":"' + b'x' * 65536 + b'"}\n' + lines[2],
                         b'{"nested":' + b'[' * 1100 + b'0' + b']' * 1100 + b'}\n' + lines[2]]
            for index, content in enumerate(malformed):
                with self.subTest(mutation=index):
                    events.write_bytes(content)
                    with self.assertRaises(ValueError):
                        run_bridge_checks.junit_failures(report, {}, self.counts(), events)
            events.write_bytes(raw)
            with patch.object(run_bridge_checks, "MAX_JUNIT_BYTES", len(raw) - 1), self.assertRaises(ValueError):
                run_bridge_checks.junit_failures(report, {}, self.counts(), events)
            report, events = self.reports(temporary, [self.leaf("one"), self.leaf("two")], [self.row("one"), self.row("two")])
            with patch.object(run_bridge_checks, "MAX_TERMINAL_EVENTS", 1), self.assertRaises(ValueError):
                run_bridge_checks.junit_failures(report, {}, self.counts(tests=2, **{"pass": 2}), events)
            events.unlink()
            with self.assertRaises(OSError):
                run_bridge_checks.junit_failures(report, {}, {}, events)


class BridgeStageReportTests(unittest.TestCase):
    """Harness wiring uses mocked commands and synthetic DATA only."""

    def execute_fixture(self, omit_mcp_events=False, timeout=300, reference_timeout=600):
        observed = {}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "evidence/bridges.json"
            raw = output.parent / "raw"
            raw.mkdir(parents=True)
            for name in ("mcp", "reference-host"):
                report = raw / (name + "-test.junit.xml")
                report.write_bytes(b"stale JUnit DATA")
                report.with_suffix(".events.jsonl").write_bytes(b"stale terminal DATA")

            def command(argv, **kwargs):
                name = kwargs["log_base"].name
                observed[name] = kwargs
                stdout, return_code = "", 0
                if name.endswith("-test"):
                    report = raw / (name + ".junit.xml")
                    events = report.with_suffix(".events.jsonl")
                    self.assertEqual((report, events), kwargs["additional_output_paths"])
                    self.assertFalse(report.exists())
                    self.assertFalse(events.exists())
                    fixture = BridgeTerminalEventTests()
                    created_report, created_events = fixture.reports(root, [fixture.leaf("public test")], [fixture.row("public test")])
                    report.write_bytes(created_report.read_bytes())
                    if not (omit_mcp_events and name == "mcp-test"):
                        events.write_bytes(created_events.read_bytes())
                    stdout = "\n".join(f"# {key} {value}" for key, value in fixture.counts().items())
                else:
                    self.assertEqual((), kwargs["additional_output_paths"])
                    if name == "node-version":
                        stdout = "v" + run_bridge_checks.NODE_VERSION + "\n"
                    elif name == "npm-version":
                        stdout = "11.19.0\n"
                    elif "-audit-" in name:
                        # Keep packaging outside this narrowly mocked test.
                        stdout, return_code = '{"error":{"code":"synthetic_no_audit"}}', 1
                return {"state": "completed", "return_code": return_code}, stdout, ""

            with patch.object(run_bridge_checks, "REPO_ROOT", root), \
                    patch.object(run_bridge_checks, "node_npm_argv", return_value=(["FIXED_NODE"], ["FIXED_NPM"])), \
                    patch.object(run_bridge_checks, "source_identity", return_value=IDENTITY), \
                    patch.object(run_bridge_checks, "lock_profile", return_value=({}, [])), \
                    patch.object(run_bridge_checks, "public_test_cases", return_value={}), \
                    patch.object(run_bridge_checks, "run_logged", side_effect=command), redirect_stdout(io.StringIO()):
                result = run_bridge_checks.execute(argparse.Namespace(output=output, timeout=timeout, reference_host_test_timeout=reference_timeout))
        return result, observed

    def test_both_raw_reports_are_fresh_monitored_required_and_never_uploaded(self):
        result, observed = self.execute_fixture()
        self.assertEqual(10, len(observed))
        for stage in (row for row in result["stages"] if row["name"].endswith("-test")):
            self.assertEqual("passed", stage["state"])
            self.assertFalse(stage["test_failures"]["raw_report_uploaded"])
            self.assertFalse(stage["test_failures"]["terminal_events"]["raw_report_uploaded"])
            self.assertIn("including parents", stage["test_count_scope"])
        result, _ = self.execute_fixture(omit_mcp_events=True)
        stage = next(row for row in result["stages"] if row["name"] == "mcp-test")
        self.assertEqual("failed", stage["state"])
        self.assertIn("Actual Node JUnit/terminal-event reports are absent/unsafe/malformed", stage["errors"])
        self.assertNotIn("test_failures", stage)

    def test_only_reference_host_test_gets_its_separate_timeout(self):
        for general, reference in ((300, 600), (41, 79)):
            result, observed = self.execute_fixture(timeout=general, reference_timeout=reference)
            for name, kwargs in observed.items():
                expected = reference if name == "reference-host-test" else general
                self.assertEqual(expected, kwargs["timeout"])
                stage = next(row for row in result["stages"] if row["name"] == name)
                self.assertEqual(expected, stage["timeout_seconds"])

    def test_cli_keeps_default_300_seconds_except_reference_600_and_bounds_both(self):
        result = {"state": "passed", "stages": [], "errors": []}
        with patch("sys.argv", ["run_bridge_checks.py", "--output", "unused.json"]), \
                patch.object(run_bridge_checks, "execute", return_value=result) as execute, redirect_stdout(io.StringIO()):
            self.assertEqual(0, run_bridge_checks.main())
        args = execute.call_args.args[0]
        self.assertEqual(300, args.timeout)
        self.assertEqual(600, args.reference_host_test_timeout)
        for flag in ("--timeout", "--reference-host-test-timeout"):
            for value in ("0", "1201", "not-a-number"):
                with self.subTest(flag=flag, value=value), patch("sys.argv", ["run_bridge_checks.py", "--output", "unused.json", flag, value]), \
                        patch.object(run_bridge_checks, "execute") as execute, redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    run_bridge_checks.main()
                self.assertEqual(2, error.exception.code)
                execute.assert_not_called()


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
