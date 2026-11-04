"""Tiny synthetic files only. These tests do not run Java or copy an installed JDK."""

import hashlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import prepare_jdk_image as image


@unittest.skipUnless(os.name == "posix", "Private Windows ACL preparation is explicitly unavailable")
class PreparedJdkImageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ktchecker-prep-unit-")
        self.base = Path(self.temporary.name).resolve(strict=True)
        self.source = self.base / "source"
        self.source.mkdir()
        self.destination = self.base / "prepared"
        self.receipt = self.base / "prepared.receipt.json"
        self.write("release", 'JAVA_VERSION="21.0.12"\nOS_NAME="Linux"\nOS_ARCH="amd64"\n')
        self.write("bin/java", "synthetic bytes, not a runnable JDK", mode=0o755)
        self.write("lib/modules", "synthetic modules")
        self.write("lib/server/libjvm.so", "synthetic native VM")
        self.write("lib/jvm.cfg", "synthetic runtime configuration")
        self.write("conf/security/java.security", "synthetic security configuration")

    def tearDown(self):
        # The product deliberately removes write bits. Undo only our temporary fixture
        # permissions so non-root POSIX test runners can remove their own test images.
        for parent, directories, files in os.walk(self.base, followlinks=False):
            os.chmod(parent, 0o700)
            for name in directories + files:
                path = Path(parent) / name
                if not path.is_symlink():
                    os.chmod(path, 0o700 if path.is_dir() else 0o600)
        self.temporary.cleanup()

    def write(self, relative, text, mode=0o644, root=None):
        path = (root or self.source) / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        path.chmod(mode)
        return path

    def link(self, relative, target, root=None):
        path = (root or self.source) / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            path.symlink_to(target)
        except (OSError, NotImplementedError) as error:
            self.skipTest(f"Symbolic-link fixture unavailable: {type(error).__name__}")
        return path

    def prepare(self, **options):
        return image.prepare_image(self.source, self.destination, receipt=self.receipt, **options)

    def body(self):
        return json.loads(self.receipt.read_text(encoding="utf-8"))

    def failure(self, code, **options):
        with self.assertRaises(image.PreparationError) as caught:
            self.prepare(**options)
        self.assertEqual("prepare_jdk_" + code, caught.exception.code)
        self.assertNotIn(str(self.base), str(caught.exception))
        self.assertFalse(self.destination.exists(), "Failed copy leaked its owned partial image")
        self.assertFalse(self.receipt.exists(), "Failed copy leaked its owned partial receipt")
        return caught.exception

    def test_default_copies_and_rehashes_real_tiny_files_without_any_execution(self):
        with mock.patch.object(image.subprocess, "Popen") as process:
            result = self.prepare()
        process.assert_not_called()
        self.assertEqual("not_requested", result.runtime_status)
        body = self.body()
        self.assertEqual("ktchecker/prepared-jdk-receipt-1", body["schema"])
        self.assertEqual(result.receipt_digest, "sha256:" + hashlib.sha256(self.receipt.read_bytes()).hexdigest())
        self.assertEqual(result.image_digest, body["image"]["digest"])
        self.assertTrue(body["claims"]["source_and_image_rehashed"])
        self.assertFalse(body["claims"]["original_entire_jdk_equivalence"])
        self.assertFalse(body["claims"]["release_qualified"])
        self.assertFalse(body["claims"]["immutable_against_owner"])
        self.assertEqual("unverified", body["claims"]["compiler_compatibility"])
        self.assertTrue(body["claims"]["all_source_logical_entries_represented"])
        self.assertEqual("21.0.12", body["image"]["release"]["JAVA_VERSION"])
        for path in self.source.rglob("*"):
            copied = self.destination / path.relative_to(self.source)
            self.assertFalse(copied.is_symlink())
            if path.is_file():
                self.assertEqual(path.read_bytes(), copied.read_bytes())
        if os.name == "posix":
            self.assertEqual(0o500, stat.S_IMODE(self.destination.stat().st_mode))
            self.assertEqual(0o511, stat.S_IMODE((self.destination / "bin/java").stat().st_mode))
            self.assertEqual(0o400, stat.S_IMODE((self.destination / "lib/modules").stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(self.receipt.stat().st_mode))

    def test_image_identity_is_content_bound_not_relocation_or_timestamp(self):
        first = self.prepare()
        second = image.prepare_image(self.source, self.base / "relocated")
        self.assertEqual(first.image_digest, second.image_digest)
        source = self.source / "lib/modules"
        old = source.stat()
        source.write_text("replacement data!")  # Same length as synthetic modules.
        os.utime(source, ns=(old.st_atime_ns, old.st_mtime_ns))
        self.assertEqual(old.st_size, source.stat().st_size)
        third = image.prepare_image(self.source, self.base / "changed")
        self.assertNotEqual(first.image_digest, third.image_digest)

    def test_internal_file_and_directory_links_are_expanded_with_topology_receipt(self):
        self.write("legal/base/LICENSE", "license")
        self.link("legal/alias", "base")
        self.link("lib/license.txt", "../legal/base/LICENSE")
        self.prepare()
        self.assertEqual("license", (self.destination / "legal/alias/LICENSE").read_text())
        self.assertFalse((self.destination / "legal/alias").is_symlink())
        self.assertTrue((self.source / "legal/alias").is_symlink(), "Preparation mutated its source")
        objects = self.body()["source"]["objects"]
        links = [entry for entry in objects if entry["kind"] == "symlink"]
        self.assertEqual({"base", "../legal/base/LICENSE"}, {entry["raw_target"] for entry in links})
        mapped = {entry["path"]: entry for entry in self.body()["image"]["copied_source_mapping"]}
        self.assertEqual("legal/base/LICENSE", mapped["lib/license.txt"]["source"]["path"])
        self.assertEqual("../legal/base/LICENSE", mapped["lib/license.txt"]["links"][0]["target"])

    def test_explicit_external_directory_is_only_a_reachable_target_lookup_domain(self):
        external = self.base / "external"
        external.mkdir()
        self.write("docs/readme.txt", "documentation", root=external)
        self.write("not-reached/private.txt", "not copied or read", root=external)
        self.link("docs", external / "docs")
        self.prepare(external_roots=[external])
        body = self.body()
        self.assertEqual("documentation", (self.destination / "docs/readme.txt").read_text())
        self.assertFalse((self.destination / "not-reached").exists())
        self.assertFalse(any("private.txt" in item["path"] for item in body["source"]["objects"]))
        self.assertEqual("explicit_reachable_target_lookup_domain", body["source"]["roots"][1]["selection"])

    def test_external_multihop_configuration_requires_all_declared_domains(self):
        config = self.base / "configuration"
        other = self.base / "certificates"
        config.mkdir()
        other.mkdir()
        self.write("certs", "trusted certificate fixture", root=other)
        self.link("pointer", other / "certs", root=config)
        self.link("lib/cacerts", config / "pointer")
        self.failure("external_unapproved", external_roots=[config])
        self.prepare(external_roots=[config, other])
        self.assertEqual("trusted certificate fixture", (self.destination / "lib/cacerts").read_text())

    def test_intermediate_escape_is_rejected_even_when_final_realpath_returns_inside(self):
        external = self.base / "unapproved"
        external.mkdir()
        self.link("back", self.source / "lib/modules", root=external)
        self.link("alias", external / "back")
        self.assertEqual((self.source / "lib/modules").resolve(), (self.source / "alias").resolve())
        self.failure("external_unapproved")

    def test_embedded_parent_after_link_target_component_is_not_lexically_erased(self):
        self.link("alias", "lib/../release")
        self.failure("link_ambiguous")

    def test_link_cycle_and_directory_expansion_cycle_are_rejected(self):
        first = self.link("first", "second")
        second = self.link("second", "first")
        self.failure("cycle")
        first.unlink()
        second.unlink()
        self.link("loop", ".")
        self.failure("cycle")

    def test_dangling_link_is_never_silently_omitted(self):
        self.link("lib/src.zip", "missing.zip")
        self.failure("dangling")

    def test_exact_reasoned_dangling_omission_is_auditable_not_original_jdk_success(self):
        external = self.base / "source-archive"
        external.mkdir()
        self.link("src.zip", "missing/src.zip", root=external)
        self.link("lib/src.zip", external / "src.zip")
        reason = "Owner explicitly omits unavailable source archive; no source browsing capability claimed"
        self.failure("external_unapproved", exclusions={"lib/src.zip": reason})
        self.prepare(external_roots=[external], exclusions={"lib/src.zip": reason})
        body = self.body()
        self.assertFalse((self.destination / "lib/src.zip").exists())
        omission = body["source"]["omissions"][0]
        self.assertEqual("lib/src.zip", omission["path"])
        self.assertEqual(reason, omission["reason"])
        self.assertEqual("missing", omission["absent_component"]["path"])
        self.assertFalse(body["claims"]["all_source_logical_entries_represented"])
        self.assertEqual(1, body["claims"]["omissions_count"])
        self.assertEqual(2, len([o for o in body["source"]["objects"] if o["kind"] == "symlink"]))

    def test_existing_files_directories_and_resolving_links_cannot_be_excluded(self):
        for relative in ("lib/modules", "lib"):
            with self.subTest(relative=relative):
                self.failure("exclusion_not_dangling", exclusions={relative: "not permitted"})
        self.link("legal-copy", "lib/modules")
        self.failure("exclusion_not_dangling", exclusions={"legal-copy": "not permitted"})

    def test_extraneous_glob_parent_and_unreasoned_exclusions_are_rejected(self):
        self.failure("exclusion_unused", exclusions={"missing": "not an existing logical link"})
        for path in ("lib/*", "../elsewhere", "/absolute", "lib/./src.zip", "", "lib//src.zip"):
            with self.subTest(path=path):
                self.failure("path_invalid", exclusions={path: "invalid"})
        self.failure("exclusion_invalid", exclusions={"lib/src.zip": "  "})

    def test_mandatory_runtime_input_cannot_be_removed_through_dangling_omission(self):
        (self.source / "lib/modules").unlink()
        self.link("lib/modules", "missing-modules")
        self.failure("layout_invalid", exclusions={"lib/modules": "owner exclusion cannot manufacture a runtime"})

    def test_duplicate_and_overlapping_root_grants_are_rejected(self):
        external = self.base / "external"
        external.mkdir()
        (external / "nested").mkdir()
        for roots in ([self.source], [self.source / "lib"], [external, external], [external, external / "nested"]):
            with self.subTest(roots=roots):
                self.failure("root_overlap", external_roots=roots)

    def test_root_symlinks_and_dot_root_bypasses_are_rejected(self):
        alias = self.link("source-alias", self.source, root=self.base)
        with self.assertRaises(image.PreparationError) as caught:
            image.prepare_image(alias, self.destination)
        self.assertEqual("prepare_jdk_root_invalid", caught.exception.code)
        self.assertFalse(self.destination.exists())
        with self.assertRaises(image.PreparationError) as caught:
            image.prepare_image(str(self.source) + "/.", self.destination)
        self.assertEqual("prepare_jdk_path_invalid", caught.exception.code)

    def test_existing_destination_and_receipt_are_never_overwritten_or_removed(self):
        self.destination.mkdir()
        marker = self.destination / "unowned"
        marker.write_text("retain")
        with self.assertRaises(image.PreparationError) as caught:
            self.prepare()
        self.assertEqual("prepare_jdk_output_exists", caught.exception.code)
        self.assertEqual("retain", marker.read_text())
        marker.unlink()
        self.destination.rmdir()
        self.receipt.write_text("unowned receipt")
        with self.assertRaises(image.PreparationError) as caught:
            self.prepare()
        self.assertEqual("prepare_jdk_output_exists", caught.exception.code)
        self.assertEqual("unowned receipt", self.receipt.read_text())
        self.assertFalse(self.destination.exists())

    def test_outputs_and_receipt_must_be_outside_source_and_approved_domains(self):
        for destination, receipt in ((self.source / "copy", self.receipt),
                                     (self.destination, self.source / "receipt.json"),
                                     (self.destination, self.destination / "receipt.json")):
            with self.subTest(destination=destination, receipt=receipt):
                with self.assertRaises(image.PreparationError):
                    image.prepare_image(self.source, destination, receipt=receipt)
                self.assertFalse(destination.exists())
        external = self.base / "external"
        external.mkdir()
        with self.assertRaises(image.PreparationError) as caught:
            image.prepare_image(self.source, external / "out", receipt=self.receipt, external_roots=[external])
        self.assertEqual("prepare_jdk_output_overlap", caught.exception.code)

    @unittest.skipUnless(hasattr(os, "mkfifo"), "FIFO fixture unavailable")
    def test_nonregular_fifo_is_rejected_without_blocking_on_read(self):
        os.mkfifo(self.source / "unexpected-fifo")
        self.failure("nonregular")

    def test_source_bytes_same_size_and_mtime_change_is_detected_before_receipt(self):
        source = self.source / "lib/modules"
        original = image.Preparation.verify_sources

        def mutate(preparation):
            before = source.stat()
            source.write_text("replacement data!")
            os.utime(source, ns=(before.st_atime_ns, before.st_mtime_ns))
            original(preparation)

        with mock.patch.object(image.Preparation, "verify_sources", mutate):
            self.failure("changed")

    def test_same_content_link_retargeting_is_detected(self):
        self.write("one", "identical")
        self.write("two", "identical")
        alias = self.link("alias", "one")
        original = image.Preparation.verify_sources

        def mutate(preparation):
            alias.unlink()
            alias.symlink_to("two")
            original(preparation)

        with mock.patch.object(image.Preparation, "verify_sources", mutate):
            self.failure("changed")

    def test_excluded_target_appearing_during_copy_is_a_conflict(self):
        self.link("lib/src.zip", "missing.zip")
        original = image.Preparation.verify_sources

        def mutate(preparation):
            self.write("lib/missing.zip", "now present")
            original(preparation)

        with mock.patch.object(image.Preparation, "verify_sources", mutate):
            self.failure("changed", exclusions={"lib/src.zip": "absent archive"})

    def test_copy_failure_cleans_only_the_exclusively_created_partial_image(self):
        original = image.Preparation.read_file

        def broken(preparation, source, target=None):
            result = original(preparation, source, target)
            if target is not None:
                raise OSError("simulated local write failure with private path")
            return result

        with mock.patch.object(image.Preparation, "read_file", broken):
            self.failure("filesystem_failed")
        self.assertEqual("synthetic modules", (self.source / "lib/modules").read_text())

    def test_final_image_is_independently_rehashed_not_assumed_from_copy(self):
        original = image.Preparation.verify_image

        def mutate(preparation):
            target = preparation.destination / "lib/modules"
            mode = target.stat().st_mode
            target.chmod(0o600)
            target.write_text("replacement data!")
            target.chmod(stat.S_IMODE(mode))
            return original(preparation)

        with mock.patch.object(image.Preparation, "verify_image", mutate):
            self.failure("image_changed")

    def test_added_destination_entry_is_detected_before_receipt(self):
        original = image.Preparation.verify_image

        def mutate(preparation):
            root = preparation.destination
            root.chmod(0o700)
            (root / "unexpected").write_text("added")
            root.chmod(0o500)
            return original(preparation)

        with mock.patch.object(image.Preparation, "verify_image", mutate):
            self.failure("image_changed")

    def test_file_total_expansion_entry_name_and_depth_budgets(self):
        baseline_bytes = sum(path.stat().st_size for path in self.source.rglob("*") if path.is_file())
        limits = [image.Limits(max_file_bytes=8), image.Limits(max_total_bytes=baseline_bytes - 1),
                  image.Limits(max_entries=2), image.Limits(max_depth=1), image.Limits(max_name_bytes=8),
                  image.Limits(max_total_name_bytes=12), image.Limits(max_receipt_bytes=10)]
        for selected in limits:
            with self.subTest(limits=selected):
                self.failure("limit", limits=selected)

    def test_duplicate_aliases_consume_expanded_byte_and_entry_budgets(self):
        baseline_bytes = sum(path.stat().st_size for path in self.source.rglob("*") if path.is_file())
        self.link("copy-one", "lib/modules")
        self.link("copy-two", "lib/modules")
        self.failure("limit", limits=image.Limits(max_total_bytes=baseline_bytes + 1))

    def test_link_hop_bounds_are_not_bypassed_by_final_in_root_target(self):
        self.link("one", "two")
        self.link("two", "three")
        self.link("three", "lib/modules")
        self.failure("limit", limits=image.Limits(max_link_hops=2))

    def test_cooperative_deadline_and_invalid_numeric_limits(self):
        with mock.patch.object(image.time, "monotonic", side_effect=[0.0, 2.0]):
            self.failure("timeout", limits=image.Limits(max_seconds=1))
        for parameters in ({"max_seconds": float("nan")}, {"max_file_bytes": float("inf")},
                           {"max_entries": True}, {"max_entries": 1.5}, {"max_total_bytes": 0}):
            with self.subTest(parameters=parameters), self.assertRaises(image.PreparationError):
                image.Limits(**parameters)

    def test_explicit_probe_is_recorded_without_treating_startup_as_qualification(self):
        with mock.patch.object(image, "probe_runtime", return_value={"status": "passed", "executed": True,
                                                                    "qualification": False, "command": ["synthetic explicit probe"]}) as probe:
            result = self.prepare(verify_runtime=True)
        probe.assert_called_once()
        self.assertEqual(self.destination / "bin/java", probe.call_args.args[0])
        self.assertEqual("passed", result.runtime_status)
        self.assertFalse(self.body()["claims"]["release_qualified"])
        self.assertEqual("unverified", self.body()["claims"]["compiler_compatibility"])

    def test_failed_explicit_probe_retains_honest_image_and_failure_receipt(self):
        with mock.patch.object(image, "probe_runtime", return_value={"status": "failed", "executed": True,
                                                                    "reason": "synthetic failure", "qualification": False}):
            result = self.prepare(verify_runtime=True)
        self.assertEqual("failed", result.runtime_status)
        self.assertTrue(result.destination.exists())
        self.assertEqual("failed", self.body()["runtime_probe"]["status"])
        self.assertFalse(self.body()["claims"]["release_qualified"])

    def test_missing_nonexecutable_and_malformed_runtime_layouts_fail_closed(self):
        java = self.source / "bin/java"
        java.chmod(0o644)
        self.failure("layout_invalid")
        java.chmod(0o755)
        self.write("release", 'JAVA_VERSION="21"\nJAVA_VERSION="25"\nOS_NAME="Linux"\nOS_ARCH="amd64"\n')
        self.failure("layout_invalid")

    @unittest.skipUnless(os.name == "posix", "POSIX explicit mode fixture")
    def test_unreadable_input_does_not_pass_merely_because_tests_run_privileged(self):
        (self.source / "lib/modules").chmod(0)
        self.failure("unreadable")

    @unittest.skipUnless(os.name == "posix", "POSIX can construct case-colliding names")
    def test_case_collisions_and_malformed_unicode_are_rejected(self):
        first = self.write("conf/Case", "one")
        second = self.write("conf/case", "two")
        if first.samefile(second):
            self.skipTest("Filesystem is case-insensitive")
        self.failure("path_ambiguous")
        with self.assertRaises(image.PreparationError) as caught:
            image.encoded("broken\ud800")
        self.assertEqual("prepare_jdk_path_invalid", caught.exception.code)

    def test_trusted_outer_alias_is_pinned_and_retargeting_is_detected(self):
        parent = self.base / "parent-one"
        parent.mkdir()
        other = self.base / "parent-two"
        other.mkdir()
        self.source.rename(parent / "source")
        alias = self.link("parent-alias", parent, root=self.base)
        self.source = alias / "source"
        original = image.Preparation.verify_sources

        def mutate(preparation):
            alias.unlink()
            alias.symlink_to(other)
            original(preparation)

        with mock.patch.object(image.Preparation, "verify_sources", mutate):
            self.failure("filesystem_failed")

    def fake_java(self, body):
        # Explicitly selected tiny Python child, not a JVM and not a toolchain qualification.
        return self.write("bin/java", f"#!{sys.executable}\n" + body, mode=0o755)

    @unittest.skipUnless(all(hasattr(os, name) for name in ("waitid", "WNOWAIT")), "Unreaped POSIX child ownership unavailable")
    def test_actual_fake_probe_clears_environment_and_retains_hashes_not_raw_output(self):
        java = self.fake_java("import os, sys\n"
                              "assert 'JAVA_TOOL_OPTIONS' not in os.environ\n"
                              "assert 'LD_PRELOAD' not in os.environ\n"
                              "assert os.environ['HOME'] == os.getcwd()\n"
                              "sys.stdout.write('RAW_CHILD_TEXT_MUST_NOT_BE_RETAINED')\n"
                              "sys.stderr.write('openjdk version \\\"21.0.12\\\"\\n')\n")
        with mock.patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "harmless unit-test marker", "LD_PRELOAD": "not-forwarded"}):
            report = image.probe_runtime(java, 1, 4096, "21.0.12")
        self.assertEqual("passed", report["status"], report)
        self.assertTrue(report["executed"])
        self.assertFalse(report["qualification"])
        self.assertEqual("21.0.12", report["java_version"])
        self.assertEqual(str(java), report["command"][0])
        self.assertEqual("-version", report["command"][-1])
        self.assertNotIn("RAW_CHILD_TEXT_MUST_NOT_BE_RETAINED", json.dumps(report))
        self.assertEqual(image.digest(b"RAW_CHILD_TEXT_MUST_NOT_BE_RETAINED"), report["captured_output"]["stdout"]["sha256"])
        self.assertFalse(Path(report["cwd"]).exists(), "Probe temporary working directory leaked")

    @unittest.skipUnless(all(hasattr(os, name) for name in ("waitid", "WNOWAIT")), "Unreaped POSIX child ownership unavailable")
    def test_actual_fake_probe_output_is_bounded(self):
        java = self.fake_java("import sys\nsys.stdout.write('x' * 200000)\nsys.stdout.flush()\n")
        report = image.probe_runtime(java, 1, 512, "21.0.12")
        self.assertEqual("failed", report["status"])
        self.assertEqual("runtime_output_limit", report["reason"])
        self.assertTrue(report["output_truncated"])
        self.assertLessEqual(sum(item["bytes"] for item in report["captured_output"].values()), 512)
        self.assertLess(report["elapsed_millis"], 2000)

    @unittest.skipUnless(all(hasattr(os, name) for name in ("waitid", "WNOWAIT")), "Unreaped POSIX child ownership unavailable")
    def test_actual_fake_probe_timeout_terminates_its_unreaped_child(self):
        java = self.fake_java("import time\ntime.sleep(5)\n")
        report = image.probe_runtime(java, 0.15, 512, "21.0.12")
        self.assertEqual("failed", report["status"])
        self.assertEqual("runtime_timeout", report["reason"])
        self.assertEqual(-image.signal.SIGKILL, report["exit_code"])
        self.assertLess(report["elapsed_millis"], 2000)

    @unittest.skipUnless(all(hasattr(os, name) for name in ("waitid", "WNOWAIT")), "Unreaped POSIX child ownership unavailable")
    def test_actual_fake_probe_version_must_match_captured_release(self):
        java = self.fake_java("import sys\nsys.stderr.write('openjdk version \\\"25.0.1\\\"\\n')\n")
        report = image.probe_runtime(java, 1, 512, "21.0.12")
        self.assertEqual("failed", report["status"])
        self.assertEqual("runtime_release_version_mismatch", report["reason"])
        self.assertEqual("25.0.1", report["java_version"])

    @unittest.skipUnless(all(hasattr(os, name) for name in ("waitid", "WNOWAIT")), "Unreaped POSIX child ownership unavailable")
    def test_actual_fake_probe_failure_is_retained_in_preparation_receipt(self):
        self.fake_java("import sys\nsys.stderr.write('openjdk version \\\"21.0.12\\\"\\n')\nsys.exit(7)\n")
        result = self.prepare(verify_runtime=True, probe_timeout=1)
        self.assertEqual("failed", result.runtime_status)
        report = self.body()["runtime_probe"]
        self.assertEqual(7, report["exit_code"])
        self.assertEqual("runtime_version_probe_failed", report["reason"])
        self.assertTrue(self.destination.exists())
        self.assertFalse(self.body()["claims"]["release_qualified"])

    def test_probe_with_custom_child_reaper_abstains_without_starting_any_process(self):
        with mock.patch.object(image.signal, "getsignal", return_value=image.signal.SIG_IGN), mock.patch.object(image.subprocess, "Popen") as process:
            report = image.probe_runtime(self.source / "bin/java", 1, 512, "21.0.12")
        process.assert_not_called()
        self.assertEqual("unavailable", report["status"])
        self.assertFalse(report["executed"])

    @unittest.skipUnless(all(hasattr(os, name) for name in ("waitid", "WNOWAIT")), "Unreaped POSIX child ownership unavailable")
    def test_successful_exit_observed_after_probe_deadline_is_not_passed(self):
        child = SimpleNamespace(pid=12345, stdout=io.BytesIO(), stderr=io.BytesIO(b'openjdk version "21.0.12"\n'),
                                returncode=0, wait=mock.Mock(return_value=0))
        with mock.patch.object(image.subprocess, "Popen", return_value=child), \
                mock.patch.object(image.os, "waitid", return_value=object()), \
                mock.patch.object(image.os, "killpg") as signal_group, \
                mock.patch.object(image.time, "monotonic", side_effect=[0.0, 1.1, 1.1]):
            report = image.probe_runtime(self.source / "bin/java", 1, 512, "21.0.12")
        signal_group.assert_called_once_with(child.pid, image.signal.SIGKILL)  # Mock only; no such process was launched/signaled.
        self.assertEqual("failed", report["status"])
        self.assertEqual("runtime_timeout", report["reason"])
        self.assertEqual(0, report["exit_code"])


class PreparationAvailabilityTests(unittest.TestCase):
    def test_non_posix_preparation_abstains_without_creating_outputs(self):
        source, destination = Path("explicit-unused-source"), Path("new-unused-destination")
        with mock.patch.object(image.os, "name", "nt"), self.assertRaises(image.PreparationError) as caught:
            image.prepare_image(source, destination)
        self.assertEqual("prepare_jdk_platform_unavailable", caught.exception.code)


if __name__ == "__main__":
    unittest.main()
