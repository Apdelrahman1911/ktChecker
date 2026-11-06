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
                # The fixture must retain intentionally unsafe spellings. ZipInfo
                # normalizes native separators on Windows before writestr sees
                # them; otherwise that host tests a different, harmless archive.
                entry.filename = name
                entry.orig_filename = name
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

    def test_zip_fixture_keeps_literal_names_in_local_and_central_headers(self):
        name = "dev\\backslash.class"
        actual_zip_info = zipfile.ZipInfo

        class NormalizingZipInfo(actual_zip_info):
            def __init__(self, filename="NoName", *args, **kwargs):
                # Model the Windows constructor on every test host. This is a
                # fixture-construction control, not Windows filesystem evidence.
                super().__init__(filename.replace("\\", "/"), *args, **kwargs)

        for implementation in (actual_zip_info, NormalizingZipInfo):
            with self.subTest(constructor=implementation.__name__):
                with patch.object(zipfile, "ZipInfo", implementation):
                    synthetic_worker(self.built, extra={name: JAVA21})
                raw = self.built.read_bytes()
                with zipfile.ZipFile(self.built) as archive:
                    local_names = []
                    for entry in archive.infolist():
                        offset = entry.header_offset
                        self.assertEqual(b"PK\x03\x04", raw[offset:offset + 4])
                        size = struct.unpack_from("<H", raw, offset + 26)[0]
                        local_names.append(raw[offset + 30:offset + 30 + size])
                    offset = archive.start_dir
                    central_names = []
                    for _ in archive.infolist():
                        self.assertEqual(b"PK\x01\x02", raw[offset:offset + 4])
                        size, extra, comment = struct.unpack_from("<HHH", raw, offset + 28)
                        central_names.append(raw[offset + 46:offset + 46 + size])
                        offset += 46 + size + extra + comment
                self.assertEqual(local_names, central_names)
                self.assertEqual(1, local_names.count(name.encode("ascii")))
                self.assertNotIn(name.replace("\\", "/").encode("ascii"), local_names)
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

    # The following controls model API facts only. Their mocks do not change
    # the original positive/path/identity/CLI controls above or establish that
    # a real Windows runtime implements the declared native observation ABI.
    def stat_fixture(self, initial, **changes):
        return SimpleNamespace(**{**{name: getattr(initial, name) for name in
                                     ("st_dev", "st_ino", "st_mode", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")},
                                  **changes})

    def native_fixture(self):
        return ((1 << 48) | 7, (1 << 100) | 13, 0x20, 1, self.built.stat().st_size, 300, 400, 100)

    def native_structures_fixture(self):
        # Pure Python buffers: even importing ctypes can initialize a native
        # process-library handle. This suite does not do that. Real structure
        # declarations are checked as AST below, not qualified by these stubs.
        class BasicInfo(SimpleNamespace):
            _fixture_bytes = 40

            def __init__(self):
                super().__init__(CreationTime=0, LastAccessTime=0, LastWriteTime=0, ChangeTime=0, FileAttributes=0)

        class StandardInfo(SimpleNamespace):
            _fixture_bytes = 24

            def __init__(self):
                super().__init__(AllocationSize=0, EndOfFile=0, NumberOfLinks=0, DeletePending=0, Directory=0)

        class FileIdInfo(SimpleNamespace):
            _fixture_bytes = 24

            def __init__(self):
                super().__init__(VolumeSerialNumber=0, Identifier=bytearray(16))

        ctypes_stub = SimpleNamespace(sizeof=lambda value: value._fixture_bytes,
                                      byref=lambda value: SimpleNamespace(_obj=value),
                                      c_void_p=lambda value: SimpleNamespace(value=value % (1 << (8 * struct.calcsize("P")))))
        return ctypes_stub, BasicInfo, StandardInfo, FileIdInfo

    def native_api_fixture(self, **changes):
        calls = []
        native = self.native_fixture()

        def create(*args):
            calls.append(("create", args))
            return changes.get("handle", 19)

        def file_type(handle):
            calls.append(("type", handle))
            return changes.get("file_type", 1)

        def query(handle, kind, pointer, size):
            calls.append(("query", handle, kind, size))
            if kind == changes.get("failed_query"):
                return 0
            values = {
                0: {"CreationTime": 100, "LastAccessTime": 200, "LastWriteTime": 300,
                    "ChangeTime": 400, "FileAttributes": 0x20},
                1: {"AllocationSize": 8192, "EndOfFile": native[4], "NumberOfLinks": 1,
                    "DeletePending": 0, "Directory": 0},
                18: {"VolumeSerialNumber": native[0], "Identifier": native[1].to_bytes(16, "little")},
            }[kind]
            values.update(changes.get("fields", {}))
            for name, value in values.items():
                if hasattr(pointer._obj, name):
                    if name == "Identifier":
                        pointer._obj.Identifier[:] = value
                    else:
                        setattr(pointer._obj, name, value)
            return 1

        def close(handle):
            calls.append(("close", handle))
            return changes.get("close_result", 1)

        return SimpleNamespace(CreateFileW=create, GetFileType=file_type,
                               GetFileInformationByHandleEx=query, CloseHandle=close), calls

    def test_nonwindows_read_keeps_every_exact_cross_api_stat_field(self):
        initial = self.built.stat()
        for name in vars(self.stat_fixture(initial)):
            changed = self.stat_fixture(initial, **{name: getattr(initial, name) + 1})
            with self.subTest(field=name), patch.object(verifier, "windows_observation_required", return_value=False), \
                    patch.object(verifier.os, "fstat", return_value=changed), \
                    patch.object(verifier, "_windows_file_observation", side_effect=AssertionError("native call forbidden")):
                with self.assertRaises(verifier.WorkerVerificationError) as raised:
                    verifier.read_file(self.built, verifier.MAX_ARCHIVE_BYTES)
                self.assertEqual("observed_input_drift", raised.exception.code)

    def test_windows_stat_view_encodings_require_full_independent_native_binding(self):
        initial = self.built.stat()
        # Deliberately unlike path stat: this is an API-contract fixture, not
        # an assertion about the exact fields of any observed Windows runtime.
        descriptor = self.stat_fixture(initial, st_dev=0, st_ino=0, st_mode=stat.S_IFREG | 0o666,
                                       st_mtime_ns=initial.st_mtime_ns - 101, st_ctime_ns=initial.st_ctime_ns - 103)
        native = self.native_fixture()
        with patch.object(verifier, "windows_observation_required", return_value=True), \
                patch.object(verifier.os, "fstat", return_value=descriptor), \
                patch.object(verifier, "_windows_file_observation", return_value=native) as observe:
            raw, observed = verifier.read_file(self.built, verifier.MAX_ARCHIVE_BYTES)
        self.assertEqual(self.built.read_bytes(), raw)
        self.assertEqual(verifier.fingerprint(initial), observed)
        self.assertEqual(4, observe.call_count)
        self.assertEqual({"path": self.built}, observe.call_args_list[0].kwargs)
        self.assertEqual({"path": self.built}, observe.call_args_list[3].kwargs)
        self.assertEqual(observe.call_args_list[1], observe.call_args_list[2])
        self.assertEqual({"descriptor"}, set(observe.call_args_list[1].kwargs))

    def test_windows_native_binding_compares_every_field_at_every_boundary(self):
        native = self.native_fixture()
        for boundary in (1, 2, 3):
            for field in range(len(native)):
                calls = []

                def observe(**kwargs):
                    value = list(native)
                    if len(calls) == boundary:
                        value[field] += 1 << 96 if field == 1 else 1
                    calls.append(kwargs)
                    return tuple(value)

                with self.subTest(boundary=boundary, field=field), \
                        patch.object(verifier, "windows_observation_required", return_value=True), \
                        patch.object(verifier, "_windows_file_observation", side_effect=observe):
                    with self.assertRaises(verifier.WorkerVerificationError) as raised:
                        verifier.read_file(self.built, verifier.MAX_ARCHIVE_BYTES)
                    self.assertEqual("observed_input_drift", raised.exception.code)
                    self.assertEqual(boundary + 1, len(calls))

    def test_windows_read_retains_every_descriptor_stat_drift_check(self):
        initial = self.built.stat()
        for name in vars(self.stat_fixture(initial)):
            changed = self.stat_fixture(initial, **{name: getattr(initial, name) + 1})
            with self.subTest(field=name), patch.object(verifier, "windows_observation_required", return_value=True), \
                    patch.object(verifier.os, "fstat", side_effect=[initial, changed]), \
                    patch.object(verifier, "_windows_file_observation", return_value=self.native_fixture()):
                with self.assertRaises(verifier.WorkerVerificationError) as raised:
                    verifier.read_file(self.built, verifier.MAX_ARCHIVE_BYTES)
                self.assertEqual("observed_input_drift", raised.exception.code)

    def test_windows_read_retains_every_path_stat_drift_check(self):
        initial, original = self.built.stat(), verifier.checked_path
        for boundary in (2, 3, 4):
            for name in vars(self.stat_fixture(initial)):
                calls = []

                def checked(path, **kwargs):
                    value, info = original(path, **kwargs)
                    calls.append(value)
                    if len(calls) == boundary:
                        info = self.stat_fixture(info, **{name: getattr(info, name) + 1})
                    return value, info

                with self.subTest(boundary=boundary, field=name), \
                        patch.object(verifier, "windows_observation_required", return_value=True), \
                        patch.object(verifier, "checked_path", side_effect=checked), \
                        patch.object(verifier, "_windows_file_observation", return_value=self.native_fixture()):
                    with self.assertRaises(verifier.WorkerVerificationError) as raised:
                        verifier.read_file(self.built, verifier.MAX_ARCHIVE_BYTES)
                    self.assertEqual("observed_input_drift", raised.exception.code)
                    self.assertEqual(boundary, len(calls))

    def test_windows_native_facts_do_not_override_common_descriptor_type_links_or_size(self):
        initial = self.built.stat()
        for changes in ({"st_mode": stat.S_IFDIR | 0o700}, {"st_nlink": 0}, {"st_nlink": 2},
                        {"st_size": initial.st_size + 1}, {"st_size": initial.st_size - 1}):
            with self.subTest(changes=changes), patch.object(verifier, "windows_observation_required", return_value=True), \
                    patch.object(verifier.os, "fstat", return_value=self.stat_fixture(initial, **changes)), \
                    patch.object(verifier, "_windows_file_observation", return_value=self.native_fixture()):
                with self.assertRaises(verifier.WorkerVerificationError) as raised:
                    verifier.read_file(self.built, verifier.MAX_ARCHIVE_BYTES)
                self.assertEqual("observed_input_drift", raised.exception.code)

    def test_windows_native_size_must_match_path_before_open(self):
        native = list(self.native_fixture())
        native[4] += 1
        with patch.object(verifier, "windows_observation_required", return_value=True), \
                patch.object(verifier, "_windows_file_observation", return_value=tuple(native)), \
                patch.object(verifier.os, "open", side_effect=AssertionError("must not open mismatched input")):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier.read_file(self.built, verifier.MAX_ARCHIVE_BYTES)
        self.assertEqual("observed_input_drift", raised.exception.code)

    def test_windows_unavailable_path_identity_is_not_replaced_by_a_native_fallback(self):
        initial = self.built.stat()
        with patch.object(verifier, "windows_observation_required", return_value=True), \
                patch.object(verifier, "checked_path", return_value=(self.built, self.stat_fixture(initial, st_ino=0))), \
                patch.object(verifier, "_windows_file_observation", side_effect=AssertionError("path identity required first")):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier.read_file(self.built, verifier.MAX_ARCHIVE_BYTES)
        self.assertEqual("physical_identity_unavailable", raised.exception.code)

    def test_windows_unavailable_native_observation_cannot_fall_back_to_equal_bytes(self):
        for code in ("windows_observation_unavailable", "physical_identity_unavailable", "linked_input_path"):
            with self.subTest(code=code), patch.object(verifier, "windows_observation_required", return_value=True), \
                    patch.object(verifier, "_windows_file_observation", side_effect=verifier.WorkerVerificationError(code)), \
                    patch.object(verifier.os, "open", side_effect=AssertionError("must not open unbound input")):
                with self.assertRaises(verifier.WorkerVerificationError) as raised:
                    verifier.read_file(self.built, verifier.MAX_ARCHIVE_BYTES)
                self.assertEqual(code, raised.exception.code)

    def test_windows_observer_declares_fixed_width_layouts_and_runtime_abi_size_guard(self):
        tree = ast.parse(Path(verifier.__file__).read_text())
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "_windows_structures")
        expected = {
            "BasicInfo": [("CreationTime", "ctypes.c_int64"), ("LastAccessTime", "ctypes.c_int64"),
                          ("LastWriteTime", "ctypes.c_int64"), ("ChangeTime", "ctypes.c_int64"), ("FileAttributes", "ctypes.c_uint32")],
            "StandardInfo": [("AllocationSize", "ctypes.c_int64"), ("EndOfFile", "ctypes.c_int64"),
                             ("NumberOfLinks", "ctypes.c_uint32"), ("DeletePending", "ctypes.c_ubyte"), ("Directory", "ctypes.c_ubyte")],
            "FileIdInfo": [("VolumeSerialNumber", "ctypes.c_uint64"), ("Identifier", "ctypes.c_ubyte * 16")],
        }
        classes = {node.name: node for node in function.body if isinstance(node, ast.ClassDef)}
        self.assertEqual(set(expected), set(classes))
        for name, fields in expected.items():
            with self.subTest(structure=name):
                declaration = next(node for node in classes[name].body if isinstance(node, ast.Assign)
                                   and any(isinstance(target, ast.Name) and target.id == "_fields_" for target in node.targets))
                self.assertEqual(["ctypes.Structure"], [ast.unparse(base) for base in classes[name].bases])
                self.assertEqual(fields, [(ast.literal_eval(row.elts[0]), ast.unparse(row.elts[1])) for row in declaration.value.elts])
        guards = [node for node in function.body if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call)
                  and isinstance(node.value.func, ast.Name) and node.value.func.id == "require"
                  and isinstance(node.value.args[0], ast.Compare)]
        self.assertEqual(1, len(guards))
        expected_guard = ast.parse("tuple(ctypes.sizeof(kind) for kind in (BasicInfo, StandardInfo, FileIdInfo)) == (40, 24, 24)", mode="eval").body
        self.assertEqual(ast.dump(expected_guard), ast.dump(guards[0].value.args[0]))
        self.assertEqual("windows_observation_unavailable", ast.literal_eval(guards[0].value.args[1]))
        with patch.object(verifier, "windows_observation_required", return_value=False):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier._windows_structures()
        self.assertEqual("windows_observation_unavailable", raised.exception.code)

    def test_windows_native_loader_is_lazy_fixed_name_and_system32_only(self):
        tree = ast.parse(Path(verifier.__file__).read_text())
        loader = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "_windows_api")
        loads = [node for node in ast.walk(loader) if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                 and node.func.attr == "WinDLL"]
        self.assertEqual(1, len(loads))
        self.assertEqual("kernel32.dll", ast.literal_eval(loads[0].args[0]))
        self.assertEqual({"use_last_error": True, "winmode": 0x00000800},
                         {entry.arg: ast.literal_eval(entry.value) for entry in loads[0].keywords})
        with patch.object(verifier, "windows_observation_required", return_value=False):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier._windows_api()
        self.assertEqual("windows_observation_unavailable", raised.exception.code)

    def test_windows_observer_opens_metadata_only_without_following_leaf_and_keeps_full_identity(self):
        api, calls = self.native_api_fixture()
        with patch.object(verifier, "_windows_api", return_value=api), \
                patch.object(verifier, "_windows_structures", return_value=self.native_structures_fixture()):
            observed = verifier._windows_file_observation(path=self.built)
        self.assertEqual(self.native_fixture(), observed)
        self.assertEqual([("create", (str(self.built), 0x80, 0x7, None, 3, 0x02200000, None)),
                          ("type", 19), ("query", 19, 0, 40), ("query", 19, 1, 24),
                          ("query", 19, 18, 24), ("close", 19)], calls)
        self.assertGreater(observed[0], 2 ** 32)
        self.assertGreater(observed[1], 2 ** 64)

    def test_windows_observer_borrows_descriptor_without_reopening_or_closing_it(self):
        api, calls = self.native_api_fixture()
        with patch.object(verifier, "_windows_api", return_value=api), \
                patch.object(verifier, "_windows_structures", return_value=self.native_structures_fixture()), \
                patch.object(verifier, "_windows_descriptor_handle", return_value=23) as borrowed:
            observed = verifier._windows_file_observation(descriptor=77)
        borrowed.assert_called_once_with(77)
        self.assertEqual(self.native_fixture(), observed)
        self.assertEqual([("type", 23), ("query", 23, 0, 40), ("query", 23, 1, 24), ("query", 23, 18, 24)], calls)

    def test_windows_observer_rejects_reparse_nonregular_multiple_links_and_pending_delete(self):
        cases = [({"file_type": value}, "independent_regular_file_required") for value in (0, 2, 3)]
        cases += [({"fields": {"FileAttributes": 0x400}}, "linked_input_path")]
        cases += [({"fields": changes}, "independent_regular_file_required") for changes in
                  ({"FileAttributes": 0x10}, {"FileAttributes": 0x40}, {"Directory": 1},
                   {"NumberOfLinks": 0}, {"NumberOfLinks": 2}, {"EndOfFile": -1})]
        cases += [({"fields": {"DeletePending": 1}}, "observed_input_drift"),
                  ({"fields": {"Identifier": bytes(16)}}, "physical_identity_unavailable")]
        for changes, code in cases:
            api, calls = self.native_api_fixture(**changes)
            with self.subTest(changes=changes), patch.object(verifier, "_windows_api", return_value=api), \
                    patch.object(verifier, "_windows_structures", return_value=self.native_structures_fixture()):
                with self.assertRaises(verifier.WorkerVerificationError) as raised:
                    verifier._windows_file_observation(path=self.built)
                self.assertEqual(code, raised.exception.code)
                self.assertEqual([("close", 19)], [row for row in calls if row[0] == "close"])

    def test_windows_observer_requires_all_native_queries_without_retry_or_fallback(self):
        for kind in (0, 1, 18):
            api, calls = self.native_api_fixture(failed_query=kind)
            with self.subTest(kind=kind), patch.object(verifier, "_windows_api", return_value=api), \
                    patch.object(verifier, "_windows_structures", return_value=self.native_structures_fixture()):
                with self.assertRaises(verifier.WorkerVerificationError) as raised:
                    verifier._windows_file_observation(path=self.built)
                self.assertEqual("windows_observation_unavailable", raised.exception.code)
                self.assertEqual(1, sum(row[0] == "query" and row[2] == kind for row in calls))
                self.assertEqual([("close", 19)], [row for row in calls if row[0] == "close"])

    def test_windows_observer_invalid_handles_and_loader_errors_never_become_success(self):
        for handle in (None, 0, -1, (1 << (8 * struct.calcsize("P"))) - 1):
            api, calls = self.native_api_fixture(handle=handle)
            with self.subTest(handle=handle), patch.object(verifier, "_windows_api", return_value=api), \
                    patch.object(verifier, "_windows_structures", return_value=self.native_structures_fixture()):
                with self.assertRaises(verifier.WorkerVerificationError) as raised:
                    verifier._windows_file_observation(path=self.built)
                self.assertEqual("windows_observation_unavailable", raised.exception.code)
                self.assertEqual(["create"], [row[0] for row in calls])
        with patch.object(verifier, "_windows_api", side_effect=OSError("private unavailable API")), \
                patch.object(verifier, "_windows_structures", return_value=self.native_structures_fixture()):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier._windows_file_observation(path=self.built)
        self.assertEqual("windows_observation_unavailable", str(raised.exception))

    def test_windows_observer_failed_close_cannot_report_a_completed_observation(self):
        api, calls = self.native_api_fixture(close_result=0)
        with patch.object(verifier, "_windows_api", return_value=api), \
                patch.object(verifier, "_windows_structures", return_value=self.native_structures_fixture()):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier._windows_file_observation(path=self.built)
        self.assertEqual("windows_observation_unavailable", raised.exception.code)
        self.assertEqual([("close", 19)], [row for row in calls if row[0] == "close"])

    def test_windows_observer_api_exception_closes_owned_handle_and_is_sanitized(self):
        api, calls = self.native_api_fixture()
        with patch.object(verifier, "_windows_api", return_value=api), \
                patch.object(verifier, "_windows_structures", return_value=self.native_structures_fixture()), \
                patch.object(api, "GetFileInformationByHandleEx", side_effect=OSError("private native detail")):
            with self.assertRaises(verifier.WorkerVerificationError) as raised:
                verifier._windows_file_observation(path=self.built)
        self.assertEqual("windows_observation_unavailable", str(raised.exception))
        self.assertEqual([("close", 19)], [row for row in calls if row[0] == "close"])

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
