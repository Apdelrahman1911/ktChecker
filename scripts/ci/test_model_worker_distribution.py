"""Inert packaging/path decisions only: synthetic ZIPs and Java21 header bytes.

No compiled Java, JVM, Gradle, provider, native helper, network or model launch.
Source assertions below do not substitute for a fresh actual installDist build.
"""

import ast
import io
import json
from pathlib import Path
import shutil
import stat
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import warnings
import zipfile

try:
    from . import verify_model_worker_distribution as verifier
except ImportError:
    import verify_model_worker_distribution as verifier


JAVA21 = b"\xca\xfe\xba\xbe\x00\x00\x00\x41synthetic-header-only-not-runnable"
MANIFEST = ("Manifest-Version: 1.0\r\nMain-Class: " + verifier.MAIN_CLASS + "\r\n\r\n").encode()
SOURCE_ROOT = Path(__file__).resolve().parents[2]


def synthetic_worker(path, *, extra=None, omit=(), manifest=MANIFEST, compression=zipfile.ZIP_STORED):
    entries = {"META-INF/MANIFEST.MF": manifest, **{family + ".class": JAVA21 for family in verifier.MODEL_BRIDGE_CLASSES}}
    entries.update(extra or {})
    with zipfile.ZipFile(path, "w", compression=compression) as archive:
        for name, raw in entries.items():
            if name not in omit:
                entry = zipfile.ZipInfo(name)
                entry.compress_type = compression
                archive.writestr(entry, raw)


class ModelWorkerDistributionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.home = self.root / "installation with spaces \u03bb"
        self.installed = self.home / verifier.WORKER_RELATIVE
        self.installed.parent.mkdir(parents=True)
        self.built = self.root / "built" / verifier.WORKER_FILENAME
        self.built.parent.mkdir()
        synthetic_worker(self.built)
        self.copy_worker()
        for relative in ("lib/engine.jar", "providers/compiler/compiler.jar"):
            path = self.home / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            self.ordinary_jar(path)
        self.output = self.root / "new-report.json"

    def ordinary_jar(self, path, entries=None):
        with zipfile.ZipFile(path, "w") as archive:
            for name, raw in (entries or {"Example.class": b"synthetic ordinary file"}).items():
                archive.writestr(name, raw)

    def copy_worker(self):
        shutil.copyfile(self.built, self.installed)

    def reject(self, code=None):
        with self.assertRaises(verifier.WorkerVerificationError) as raised:
            verifier.verify(self.home, self.built)
        if code:
            self.assertEqual(code, raised.exception.code)

    def cli(self, output=None):
        stream = io.StringIO()
        with patch("sys.stdout", stream):
            status = verifier.main(["--installation", str(self.home), "--built-worker", str(self.built),
                                    "--output", str(output or self.output)])
        return status, stream.getvalue()

    def test_exact_independent_installed_bytes_headers_and_inner_classes_pass(self):
        extra = {family + "$Inner.class": JAVA21 for family in verifier.MODEL_BRIDGE_CLASSES}
        synthetic_worker(self.built, extra={"META-INF/": b"", "dev/": b"", **extra})
        self.copy_worker()
        result = verifier.verify(self.home, self.built)
        self.assertEqual("verified", result["state"])
        self.assertEqual(result["built_digest"], result["installed_digest"])
        self.assertEqual(8, len(result["class_entries"]))
        self.assertEqual(65, result["class_file_major_version"])
        self.assertEqual(2, result["ordinary_jars_checked"])
        self.assertFalse(result["worker_execution"])
        self.assertFalse(result["compiler_provider_execution"])
        self.assertFalse(result["release_qualified"])
        self.assertNotIn(str(self.root), json.dumps(result))

    def test_standard_manifest_continuation_is_unfolded(self):
        manifest = MANIFEST.replace(b"compilerworker.live.", b"compilerworker.\r\n live.")
        synthetic_worker(self.built, manifest=manifest)
        self.copy_worker()
        self.assertEqual("verified", verifier.verify(self.home, self.built)["state"])

    def test_installed_bytes_must_equal_selected_built_bytes(self):
        self.installed.write_bytes(self.installed.read_bytes() + b"different")
        self.reject("installed_worker_bytes_differ")

    def test_missing_inputs_are_non_success(self):
        for path, code in ((self.built, "input_unreadable_or_malformed"), (self.installed, "installed_worker_missing")):
            with self.subTest(kind=path.parent.name):
                raw = path.read_bytes()
                path.unlink()
                self.reject(code)
                path.write_bytes(raw)

    def test_built_worker_cannot_be_the_installed_file(self):
        with self.assertRaises(verifier.WorkerVerificationError) as raised:
            verifier.verify(self.home, self.installed)
        self.assertEqual("built_worker_must_be_separate", raised.exception.code)

    def test_different_spelling_of_same_physical_worker_is_not_independent(self):
        actual = verifier.read_file
        built_identity = verifier.fingerprint(self.built.stat())[:2]

        def read_alias(path, limit):
            raw, observed = actual(path, limit)
            if path == self.installed:
                observed = (*built_identity, *observed[2:])
            return raw, observed

        # Inert identity decision, not a case-insensitive filesystem/bind-mount
        # execution claim. Equal bytes alone must not bypass independence.
        with patch.object(verifier, "read_file", side_effect=read_alias), \
                patch.object(verifier, "worker_archive", side_effect=AssertionError("must reject before archive parsing")):
            self.reject("built_worker_must_be_independent")

    def test_unavailable_file_identity_never_establishes_independence(self):
        actual = verifier.read_file
        for identity in ((0, 0), (-1, 12), (1, None), (True, 12)):
            with self.subTest(identity=identity):
                def read_unavailable(path, limit):
                    raw, observed = actual(path, limit)
                    if path == self.built:
                        observed = (*identity, *observed[2:])
                    return raw, observed
                with patch.object(verifier, "read_file", side_effect=read_unavailable):
                    self.reject("physical_identity_unavailable")

    def test_isolated_directory_requires_exactly_one_expected_file(self):
        for name in ("test.jar", "host-resource.txt", "nested"):
            with self.subTest(name=name):
                extra = self.installed.parent / name
                if name == "nested":
                    extra.mkdir()
                else:
                    extra.write_bytes(b"synthetic extra")
                self.reject("isolated_worker_directory_not_exact")
                if extra.is_dir():
                    extra.rmdir()
                else:
                    extra.unlink()

    def test_every_reviewed_root_family_is_required(self):
        for family in verifier.MODEL_BRIDGE_CLASSES:
            with self.subTest(family=family):
                synthetic_worker(self.built, omit=(family + ".class",))
                self.copy_worker()
                self.reject("required_worker_family_missing")

    def test_test_host_m0_dependency_resource_and_multi_release_files_are_rejected(self):
        forbidden = ("dev/ktchecker/cli/Host.class", "dev/ktchecker/compilerworker/live/CompilerModelApplicationMainTest.class",
                     "dev/ktchecker/workerprotocol/CompilerJobWire.class", "org/junit/Test.class", "kotlin/Unit.class",
                     "dev/ktchecker/compilerworker/live/CompilerApplicationMain.class", "unused.txt",
                     "META-INF/versions/21/" + verifier.MODEL_BRIDGE_CLASSES[0] + ".class",
                     verifier.MODEL_BRIDGE_CLASSES[0] + "$nested/Hidden.class")
        for name in forbidden:
            with self.subTest(name=name):
                synthetic_worker(self.built, extra={name: JAVA21})
                self.copy_worker()
                self.reject("unreviewed_worker_file")

    def test_foreign_directories_and_symlink_zip_modes_are_rejected(self):
        synthetic_worker(self.built, extra={"META-INF/versions/": b""})
        self.copy_worker()
        self.reject("unreviewed_worker_directory")
        synthetic_worker(self.built)
        with zipfile.ZipFile(self.built, "a") as archive:
            entry = zipfile.ZipInfo(verifier.MODEL_BRIDGE_CLASSES[0] + "$Linked.class")
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, JAVA21)
        self.copy_worker()
        self.reject("nonregular_worker_entry")

    def test_literal_zip_path_aliases_are_not_normalized_into_allowed_names(self):
        for name in ("/absolute.class", "../parent.class", "dev//Empty.class", "dev/./Dot.class",
                     "C:drive.class", "dev\\backslash.class"):
            with self.subTest(name=name):
                synthetic_worker(self.built, extra={name: JAVA21})
                self.copy_worker()
                self.reject("unsafe_worker_entry")
        synthetic_worker(self.built, extra={"nulXalias.class": JAVA21})
        self.built.write_bytes(self.built.read_bytes().replace(b"nulXalias.class", b"nul\0alias.class"))
        self.copy_worker()
        self.reject("unsafe_worker_entry")

    def test_duplicate_class_entries_are_rejected(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.built, "a") as archive:
                archive.writestr(verifier.MODEL_BRIDGE_CLASSES[0] + ".class", JAVA21)
        self.copy_worker()
        self.reject("duplicate_worker_entry")

    def test_truncated_and_trailing_zip_bytes_are_non_success(self):
        original = self.built.read_bytes()
        for raw in (b"not a ZIP", original[:-10], original + b"trailing"):
            with self.subTest(size=len(raw)):
                self.built.write_bytes(raw)
                self.copy_worker()
                self.reject()

    def test_crc_corruption_after_valid_class_header_is_rejected(self):
        raw = self.built.read_bytes()
        raw = raw.replace(b"synthetic-header-only-not-runnable", b"synthetic-header-only-not-runnablX", 1)
        self.built.write_bytes(raw)
        self.copy_worker()
        self.reject("input_unreadable_or_malformed")

    def test_local_and_central_entry_names_must_agree(self):
        name = (verifier.MODEL_BRIDGE_CLASSES[0] + ".class").encode()
        self.built.write_bytes(self.built.read_bytes().replace(name, name[:-6] + b"Xclass", 1))
        self.copy_worker()
        self.reject("input_unreadable_or_malformed")

    def test_manifest_cannot_add_classpath_agents_multirelease_sections_or_wrong_main(self):
        candidates = [MANIFEST.replace(b"\r\n\r\n", b"\r\n" + header + b"\r\n\r\n") for header in
                      (b"Class-Path: host.jar", b"Multi-Release: true", b"Launcher-Agent-Class: Host", b"Main-Class: Duplicate")]
        candidates += [MANIFEST + b"Name: Host.class\r\nX: y\r\n\r\n", MANIFEST.replace(b"Main-Class", b"Missing-Class"),
                       MANIFEST.replace(b"CompilerModelApplicationMain", b"CompilerApplicationMain"),
                       MANIFEST.replace(b"Manifest-Version", b"\xff"), MANIFEST.replace(b"1.0", b"1.0\rbroken"),
                       MANIFEST.replace(b"1.0", b"1.0\0")]
        for index, manifest in enumerate(candidates):
            with self.subTest(index=index):
                synthetic_worker(self.built, manifest=manifest)
                self.copy_worker()
                self.reject()

    def test_manifest_is_required(self):
        synthetic_worker(self.built, omit=("META-INF/MANIFEST.MF",))
        self.copy_worker()
        self.reject("worker_manifest_missing")

    def test_java21_nonpreview_class_headers_are_required(self):
        candidates = (b"bad!" + JAVA21[4:], JAVA21[:7], JAVA21[:6] + struct.pack(">H", 64) + JAVA21[8:],
                      JAVA21[:6] + struct.pack(">H", 66) + JAVA21[8:], JAVA21[:4] + b"\xff\xff" + JAVA21[6:])
        for index, raw in enumerate(candidates):
            with self.subTest(index=index):
                synthetic_worker(self.built, extra={verifier.MODEL_BRIDGE_CLASSES[0] + ".class": raw})
                self.copy_worker()
                self.reject("java21_class_header_required")

    def test_parent_leaf_symlink_and_reparse_decisions_fail_closed(self):
        actual = Path.lstat
        for target in (self.built, self.installed, self.home, self.installed.parent):
            with self.subTest(location=target.name):
                linked = SimpleNamespace(st_mode=stat.S_IFLNK | 0o700, st_file_attributes=0)
                with patch.object(Path, "lstat", new=lambda path: linked if path == target else actual(path)):
                    self.reject("linked_input_path")
        # Pure Windows reparse-policy decision, not a Windows filesystem/ACL claim.
        linked = SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_file_attributes=0x400)
        with patch.object(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400, create=True), \
                patch.object(Path, "lstat", new=lambda path: linked if path == self.home else actual(path)):
            self.reject("linked_input_path")

    def test_hardlinked_worker_decision_is_rejected(self):
        actual = Path.lstat
        linked = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_file_attributes=0, st_nlink=2)
        with patch.object(Path, "lstat", new=lambda path: linked if path == self.built else actual(path)):
            self.reject("independent_regular_file_required")

    def test_observed_descriptor_drift_is_not_a_pass(self):
        initial = self.built.stat()
        changed = SimpleNamespace(**{name: getattr(initial, name) for name in
                                    ("st_dev", "st_ino", "st_mode", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")})
        changed.st_mtime_ns += 1
        with patch.object(verifier.os, "fstat", side_effect=[initial, changed]):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier.read_file(self.built, verifier.MAX_ARCHIVE_BYTES)
        self.assertEqual("observed_input_drift", raised.exception.code)

    def test_archive_tree_and_ordinary_scan_have_finite_bounds(self):
        for constant, value in (("MAX_ARCHIVE_BYTES", 1), ("MAX_WORKER_ENTRIES", 4), ("MAX_TREE_ENTRIES", 1),
                                ("MAX_ORDINARY_JARS", 1), ("MAX_ORDINARY_BYTES", 1), ("MAX_ORDINARY_JAR_BYTES", 1)):
            with self.subTest(constant=constant), patch.object(verifier, constant, value):
                self.reject()

    def test_expanded_bytes_are_bounded_before_decompression(self):
        synthetic_worker(self.built, extra={verifier.MODEL_BRIDGE_CLASSES[0] + ".class": JAVA21 + b"x" * (1024 * 1024)},
                         compression=zipfile.ZIP_DEFLATED)
        self.copy_worker()
        with patch.object(verifier, "MAX_ARCHIVE_BYTES", 64 * 1024):
            self.reject("expanded_worker_byte_limit")

    def test_worker_copy_cannot_enter_engine_or_ordinary_compiler_scan(self):
        for directory in (self.home / "lib", self.home / "providers/compiler"):
            for name in (self.built.name, "renamed.jar"):
                with self.subTest(directory=directory.name, name=name):
                    extra = directory / name
                    shutil.copyfile(self.built, extra)
                    self.reject("worker_in_ordinary_scan")
                    extra.unlink()

    def test_repacked_worker_classes_in_ordinary_jars_are_rejected(self):
        extra = self.home / "lib/repacked.jar"
        for family in verifier.MODEL_BRIDGE_CLASSES[2:]:
            for name in (family + ".class", family + "$Inner.class", "META-INF/versions/21/" + family + ".class"):
                with self.subTest(name=name):
                    self.ordinary_jar(extra, {name: JAVA21})
                    self.reject("worker_class_in_ordinary_scan")
        extra.unlink()

    def test_engine_wire_libraries_remain_allowed(self):
        self.ordinary_jar(self.home / "lib/wire.jar", {family + ".class": JAVA21 for family in verifier.MODEL_BRIDGE_CLASSES[:2]})
        self.assertEqual("verified", verifier.verify(self.home, self.built)["state"])

    def test_cli_success_requires_new_report_and_does_not_modify_inputs(self):
        before = {path: path.read_bytes() for path in (self.built, self.installed)}
        status, text = self.cli()
        self.assertEqual(0, status)
        result = json.loads(self.output.read_text())
        self.assertEqual("verified", result["state"])
        self.assertIn("new report delivered", text)
        self.assertEqual(before, {path: path.read_bytes() for path in before})
        saved = self.output.read_bytes()
        self.assertEqual(1, self.cli()[0])
        self.assertEqual(saved, self.output.read_bytes())

    def test_report_destination_cannot_write_into_installation_or_over_built_input(self):
        original = self.built.read_bytes()
        for output in (self.home / "not-a-report.json", self.built):
            with self.subTest(kind=output.name):
                self.assertEqual(1, self.cli(output)[0])
        self.assertFalse((self.home / "not-a-report.json").exists())
        self.assertEqual(original, self.built.read_bytes())

    def test_output_case_or_bind_alias_of_any_installed_directory_is_rejected(self):
        outside = self.root / "apparently-outside-report-parent"
        outside.mkdir()
        extra = self.home / "third_party/licenses"
        extra.mkdir(parents=True)
        actual = verifier.checked_path
        before = {path: path.read_bytes() for path in self.home.rglob("*") if path.is_file()}
        for target in (self.home, self.home / "lib", self.installed.parent, extra):
            with self.subTest(target=target.relative_to(self.home).as_posix()):
                alias = target.stat()

                def aliased_path(path, **kwargs):
                    value, info = actual(path, **kwargs)
                    return value, alias if value == outside else info

                with patch.object(verifier, "checked_path", side_effect=aliased_path):
                    self.assertEqual(1, self.cli(outside / "report.json")[0])
                self.assertFalse((outside / "report.json").exists())
        self.assertEqual(before, {path: path.read_bytes() for path in self.home.rglob("*") if path.is_file()})

    def test_output_parent_without_usable_identity_cannot_receive_a_report(self):
        actual = verifier.checked_path
        initial = self.root.stat()
        unavailable = SimpleNamespace(**{name: getattr(initial, name) for name in
                                        ("st_dev", "st_ino", "st_mode", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")})
        unavailable.st_ino = 0

        def unavailable_parent(path, **kwargs):
            value, info = actual(path, **kwargs)
            return value, unavailable if value == self.root else info

        with patch.object(verifier, "checked_path", side_effect=unavailable_parent):
            self.assertEqual(1, self.cli()[0])
        self.assertFalse(self.output.exists())

    def test_report_image_metadata_walk_is_bounded_and_never_writes_on_failure(self):
        with patch.object(verifier, "MAX_TREE_ENTRIES", 1):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier.write_new_report(self.output, verifier.failure("synthetic_failure"), self.home, self.built)
        self.assertEqual("installation_tree_entry_limit", raised.exception.code)
        self.assertFalse(self.output.exists())

    def test_aliased_or_linked_image_directory_never_receives_a_report(self):
        actual = verifier.checked_path
        alias = self.home.stat()

        def aliased_directory(path, **kwargs):
            value, info = actual(path, **kwargs)
            return value, alias if value == self.home / "lib" else info

        with patch.object(verifier, "checked_path", side_effect=aliased_directory):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier.write_new_report(self.output, verifier.failure("synthetic_failure"), self.home, self.built)
        self.assertEqual("aliased_installation_directory", raised.exception.code)
        self.assertFalse(self.output.exists())
        original_stat = Path.lstat
        linked = SimpleNamespace(st_mode=stat.S_IFLNK | 0o700, st_file_attributes=0)
        with patch.object(Path, "lstat", new=lambda path: linked if path == self.home / "lib" else original_stat(path)):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier.write_new_report(self.output, verifier.failure("synthetic_failure"), self.home, self.built)
        self.assertEqual("linked_input_path", raised.exception.code)
        self.assertFalse(self.output.exists())

    def test_cli_failure_is_sanitized_and_never_claims_execution(self):
        secret = "PRIVATE-CREDENTIAL-CONTENT"
        synthetic_worker(self.built, extra={secret + ".class": secret.encode()})
        self.copy_worker()
        status, text = self.cli()
        self.assertEqual(1, status)
        result = json.loads(self.output.read_text())
        self.assertEqual("failed", result["state"])
        self.assertEqual(["unreviewed_worker_file"], result["errors"])
        for forbidden in (secret, str(self.root), "Traceback"):
            self.assertNotIn(forbidden, text + json.dumps(result))
        self.assertFalse(result["worker_execution"])
        self.assertFalse(result["release_qualified"])

    def test_report_delivery_failure_cannot_return_zero(self):
        with patch.object(verifier, "write_new_report", side_effect=OSError("private destination")):
            status, text = self.cli()
        self.assertEqual(1, status)
        self.assertIn("report delivery failed", text)
        self.assertNotIn("private destination", text)
        self.assertFalse(self.output.exists())

    def test_source_wires_only_reviewed_task_output_into_separate_distribution_directory(self):
        source = (SOURCE_ROOT / "modules/cli/build.gradle.kts").read_text()
        self.assertIn('val bundledCompilerModel = files(providers.provider {', source)
        self.assertIn('project(":provider-compiler").tasks.named<Jar>("compilerModelApplicationWorkerJar").get().archiveFile.get()', source)
        self.assertIn('}).builtBy(":provider-compiler:compilerModelApplicationWorkerJar")', source)
        self.assertIn('from(bundledCompilerModel) { into("providers/compiler-model") }', source)
        self.assertIn('from(bundledCompiler) { into("providers/compiler") }', source)
        dependencies = source.split("dependencies {", 1)[1].split("\n}", 1)[0]
        self.assertNotIn("bundledCompilerModel", dependencies)
        self.assertNotIn("compilerModelApplicationWorkerJar", dependencies)
        self.assertNotIn("evaluationDependsOn", source)

    def test_source_manifest_uses_actual_producer_identity_task_and_archive(self):
        source = (SOURCE_ROOT / "scripts/ci/distribution-dependencies.init.gradle").read_text()
        self.assertIn("def modelWorker = compiler.tasks.named('compilerModelApplicationWorkerJar')", source)
        self.assertIn("dependsOn(cli.tasks.named('installDist'), modelWorker)", source)
        self.assertIn("def modelArchive = modelWorker.get().archiveFile.get().asFile", source)
        self.assertIn('coordinate: "${compiler.group}:${compiler.name}:${compiler.version}", origin: \'owner_project\'', source)
        self.assertIn("configuration: 'bundledCompilerModel', artifactIdentity: modelWorker.get().path", source)
        self.assertIn("file: modelArchive.absolutePath, pom: null", source)
        self.assertIn("['runtimeClasspath', 'bundledCompiler'].each", source)

    def test_source_family_filename_and_entrypoint_contract_matches_reviewed_producer(self):
        source = (SOURCE_ROOT / "modules/provider-compiler/build.gradle.kts").read_text()
        block = source.split('tasks.register<Jar>("compilerModelApplicationWorkerJar") {', 1)[1].split("// Persistent application bridge:", 1)[0]
        self.assertIn('archiveFileName.set("' + verifier.WORKER_FILENAME + '")', block)
        self.assertIn('attributes["Main-Class"] = "' + verifier.MAIN_CLASS + '"', block)
        self.assertIn("duplicatesStrategy = DuplicatesStrategy.FAIL", block)
        for family in verifier.MODEL_BRIDGE_CLASSES:
            self.assertIn('"' + family + '"', source)
        self.assertEqual(4, len(verifier.MODEL_BRIDGE_CLASSES))
        self.assertNotIn("CompilerJobWire", str(verifier.MODEL_BRIDGE_CLASSES))

    def test_acceptance_script_has_no_launch_or_network_imports(self):
        tree = ast.parse(Path(verifier.__file__).read_text())
        imports = {node.name.split(".")[0] for statement in ast.walk(tree) if isinstance(statement, ast.Import) for node in statement.names}
        self.assertFalse(imports & {"subprocess", "socket", "urllib", "requests", "multiprocessing"})
        calls = {node.func.attr for node in ast.walk(tree) if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)}
        self.assertFalse(calls & {"system", "popen", "spawn", "execv", "execve", "extract", "extractall"})


if __name__ == "__main__":
    unittest.main()
