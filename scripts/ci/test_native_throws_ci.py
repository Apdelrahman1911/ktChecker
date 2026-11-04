"""Python source, synthetic pins/receipts, and fake CI-process tests ONLY.

No test compiles/executes Java, Kotlin, Gradle, C, the Native supervisor, or an
Apple provider. Inert JAR/class/Mach-O bytes and fake process receipts exercise
collector validation, never stand in for Native/compiler/runtime evidence.
"""

import argparse
import ast
from collections import Counter
from contextlib import ExitStack, contextmanager, redirect_stderr, redirect_stdout
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import signal
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

try:
    from . import run_native_throws_checks as checks
except ImportError:
    import run_native_throws_checks as checks


IDENTITY = {"commit": "1" * 40, "dirty": False, "file_count": 100,
            "working_tree_digest": "sha256:" + "2" * 64, "digest_scope": checks.SOURCE_SCOPE,
            "unsupported_directories": 0}
CONTEXT = {"run_id": "1234", "run_attempt": "1", "job": "synthetic_native_controls"}
PRIVATE = "PRIVATE hostile diagnostic, source excerpt, /private/owner/path, token=not-a-secret"
FAKE_SIGCHLD, FAKE_SIGTERM, FAKE_SIGKILL = 20, 15, 9
STAMP_FIELDS = ("st_dev", "st_ino", "st_mode", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")


class SyntheticPosixFixtureIO:
    """Owned real fixture bytes with explicitly SYNTHETIC POSIX handle stamps.

    Windows path/CRT descriptor stats need not describe identical POSIX fields.
    An opened read handle therefore retains the path-stat captured at open ONLY
    while every actual handle-stat field and its actual byte fingerprint remain
    unchanged. Mutation produces a synthetic ctime change witness. Replacement
    never refreshes the opened identity from a later path lookup: the collector's
    separate final real path-stat check must still reject a changed path.

    This is fake filesystem physics, not Windows/POSIX handle-identity evidence.
    All descriptor IO is binary, confined to the declared fresh owned root, and
    accounted for until close. No process API is provided by this object.
    """

    def __init__(self, root):
        self.root = Path(root).resolve(strict=True) if root is not None else None
        self.opened = {}
        self.binary_flag = getattr(os, "O_BINARY", 0)
        self.host = SimpleNamespace(**{name: getattr(os, name) for name in (
            "open", "close", "fstat", "read", "write", "lseek", "ftruncate", "fsync", "access", "readlink", "walk")})

    def owned_path(self, value):
        path = Path(value)
        if self.root is None or not path.is_absolute() or ".." in path.parts \
                or not path.is_relative_to(self.root) or path.resolve(strict=False) != path:
            raise AssertionError("No unowned or aliased fixture IO authorized")
        return path

    def owned_fd(self, fd):
        if type(fd) is not int or fd not in self.opened:
            raise AssertionError("No unowned fixture descriptor IO authorized")
        return self.opened[fd]

    def fingerprint(self, fd):
        self.owned_fd(fd)
        position = self.host.lseek(fd, 0, os.SEEK_CUR)
        try:
            self.host.lseek(fd, 0, os.SEEK_SET)
            digest, total = hashlib.sha256(), 0
            while True:
                part = self.host.read(fd, 65_536)
                if not part:
                    return digest.digest()
                total += len(part)
                if total > checks.MAX_FILE:
                    raise AssertionError("Synthetic owned fixture byte bound exceeded")
                digest.update(part)
        finally:
            self.host.lseek(fd, position, os.SEEK_SET)

    def open(self, value, flags, mode=0o777):
        path = self.owned_path(value)
        fd = self.host.open(path, flags | self.binary_flag, mode)
        try:
            read_only = (flags & (os.O_WRONLY | os.O_RDWR)) == os.O_RDONLY
            self.opened[fd] = SimpleNamespace(path_stat=path.lstat(), host_stamp=checks.stamp(self.host.fstat(fd)),
                                             fingerprint=None)
            if read_only:
                self.opened[fd].fingerprint = self.fingerprint(fd)
        except BaseException:
            self.host.close(fd)
            self.opened.pop(fd, None)
            raise
        return fd

    def fstat(self, fd):
        opened = self.owned_fd(fd)
        changed = checks.stamp(self.host.fstat(fd)) != opened.host_stamp
        if opened.fingerprint is not None:
            changed = self.fingerprint(fd) != opened.fingerprint or changed
        if not changed:
            return opened.path_stat
        witness = {name: getattr(opened.path_stat, name) for name in STAMP_FIELDS}
        witness["st_ctime_ns"] += 1  # Explicit fake POSIX change witness, not a host timestamp claim.
        return SimpleNamespace(**witness)

    def close(self, fd):
        self.owned_fd(fd)
        self.host.close(fd)
        del self.opened[fd]

    def descriptor_io(self, name, fd, *args):
        self.owned_fd(fd)
        return getattr(self.host, name)(fd, *args)

    def assert_closed(self):
        remaining = list(self.opened)
        for fd in remaining:
            self.close(fd)
        if remaining:
            raise AssertionError("Synthetic fixture leaked owned descriptors")


def synthetic_posix_os(root=None, **overrides):
    """Explicit fake process capabilities plus declared owned-fixture IO only."""
    filesystem = ("O_CREAT", "O_EXCL", "O_RDONLY", "O_WRONLY", "O_NOFOLLOW", "SEEK_SET", "X_OK",
                  "environ", "fsdecode", "fsencode", "stat_result")
    values = {name: getattr(os, name) for name in filesystem if hasattr(os, name)}
    fixture_io = SyntheticPosixFixtureIO(root)
    values.update(open=fixture_io.open, close=fixture_io.close, fstat=fixture_io.fstat, fixture_io=fixture_io,
                  access=lambda value, mode: fixture_io.host.access(fixture_io.owned_path(value), mode),
                  readlink=lambda value: fixture_io.host.readlink(fixture_io.owned_path(value)),
                  walk=lambda value, **kwargs: fixture_io.host.walk(fixture_io.owned_path(value), **kwargs))
    for name in ("read", "write", "lseek", "ftruncate", "fsync"):
        values[name] = lambda fd, *args, name=name: fixture_io.descriptor_io(name, fd, *args)

    def no_process(*args, **kwargs):
        raise AssertionError("Unconfigured synthetic process API; no real process authorized")

    def successful_status(status):
        if status != 0:
            raise AssertionError("Only the authored fake successful wait status is supported")
        return 0

    values.update(name="posix", P_PID=1, WEXITED=4, WNOHANG=1, WNOWAIT=0x01000000,
                  waitid=no_process, waitpid=no_process, killpg=no_process,
                  waitstatus_to_exitcode=successful_status)
    values.update(overrides)
    return SimpleNamespace(**values)


def synthetic_posix_signal():
    return SimpleNamespace(SIGCHLD=FAKE_SIGCHLD, SIGTERM=FAKE_SIGTERM, SIGKILL=FAKE_SIGKILL,
                           SIG_DFL=0, SIG_IGN=1, getsignal=lambda number: 0)


def write(path, raw, executable=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    path.chmod(0o700 if executable else 0o600)


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False) + "\n").encode("utf-8")


def pin(path, absolute=False):
    value = {"size": path.stat().st_size, "digest": checks.digest_bytes(path.read_bytes())}
    return {"path": str(path), **value} if absolute else value


def tree_pins(path):
    return {file.relative_to(path).as_posix(): pin(file) for file in sorted(path.rglob("*")) if file.is_file()}


def inert_class(role):
    return b"\xca\xfe\xba\xbe\x00\x00\x00\x41" + role.encode("ascii") + (
        checks.MANIFEST_DIGEST.encode("ascii") if role == "fixture_model" else b"")


def inert_macho():
    raw = bytearray(32)
    raw[:4] = b"\xcf\xfa\xed\xfe"
    raw[4:8] = (0x100000C).to_bytes(4, "little")
    raw[12:16] = (2).to_bytes(4, "little")
    return bytes(raw)  # Merely a header for byte-validation tests; never executable.


class Fixture:
    def __init__(self, root, cohort="first-mac", targets=None):
        self.root = root
        self.args = argparse.Namespace(
            authority=root / "enrollment.json", authority_digest=None, fixture=root / checks.FIXTURE,
            broker_java_home=root / "jdk21", project_java_home=root / "jdk25", native_home=root / "native",
            gradle=root / "gradlew", gradle_user_home=root / "gradle-user", konan_data_dir=root / "konan-data",
            developer_dir=root / "SyntheticXcode.app/Contents/Developer", home=root / "home",
            private_dir=root / "build/private-native-throws", output=root / "build/metadata/native-throws.json",
            target=targets or ["iosArm64"], cohort=cohort, global_timeout=1200 if cohort == "first-mac" else 7200,
            case_timeout=180, max_output_bytes=32 * 1024 * 1024, max_cohort_bytes=256 * 1024 * 1024,
        )
        shutil.copytree(checks.REPO_ROOT / checks.FIXTURE, self.args.fixture)
        self.cohort = checks.strict_json((self.args.fixture / "cohort.json").read_bytes())
        self.pins = tree_pins(self.args.fixture)
        self.pins.pop("source-manifest.json")
        self.git = root / "git"
        write(self.git, b"inert selected Git bytes; never execute", True)
        self.java = {}
        for major, home in ((21, self.args.broker_java_home), (25, self.args.project_java_home)):
            version = "21.0.13" if major == 21 else "25.0.5"
            for name in checks.JDK_FILES:
                write(home / name, (f'JAVA_VERSION="{version}"\n'.encode("ascii") if name == "release"
                                   else b"inert JDK input; never execute"), name == "bin/java")
            self.java[major] = {"home": str(home), "version": version, "files": tree_pins(home)}
        wrapper_text = "\n".join(f"{key}={value}" for key, value in {
            "distributionUrl": checks.GRADLE_URL.replace(":", "\\:"), "distributionSha256Sum": checks.GRADLE_SHA256,
            "distributionBase": "GRADLE_USER_HOME", "distributionPath": "wrapper/dists",
            "zipStoreBase": "GRADLE_USER_HOME", "zipStorePath": "wrapper/dists"}.items()) + "\n"
        for name in checks.WRAPPER_FILES:
            write(root / name, wrapper_text.encode("ascii") if name.endswith(".properties") else b"inert wrapper", name == "gradlew")
        cache = checks.wrapper_cache(self.args.gradle_user_home)
        distribution = cache / "gradle-9.6.1"
        write(distribution / "bin/gradle", b"inert Gradle; never execute", True)
        write(distribution / "lib/gradle-launcher-9.6.1.jar", b"inert distribution input")
        marker = cache / "gradle-9.6.1-bin.zip.ok"
        write(marker, b"")
        native_config = (
            "airplaneMode=true\nllvmHome.macos_arm64=$llvm.macos_arm64.dev\nllvm.macos_arm64.dev=synthetic-llvm\n"
            "libffiDir.macos_arm64=synthetic-libffi\ntargetSysRoot.ios_arm64=synthetic-ios\n"
            "targetSysRoot.ios_simulator_arm64=synthetic-simulator\n"
        )
        native = self.args.native_home
        for name in ("bin/konanc", "tools/env_blacklist", "konan/konan.properties", "konan/lib/compiler.jar",
                     "klib/common/stdlib/manifest", "klib/platform/ios_arm64/platform/manifest",
                     "klib/platform/ios_simulator_arm64/platform/manifest"):
            write(native / name, native_config.encode("ascii") if name.endswith("konan.properties") else b"inert Native input",
                  name == "bin/konanc")
        dependencies = {"llvm": "synthetic-llvm", "libffi": "synthetic-libffi", "sysroot-iosArm64": "synthetic-ios",
                        "sysroot-iosSimulatorArm64": "synthetic-simulator"}
        for package in dependencies.values():
            (self.args.konan_data_dir / "dependencies" / package).mkdir(parents=True)
        self.args.developer_dir.mkdir(parents=True)
        self.args.home.mkdir()
        self.xcodebuild, self.xcrun = root / "xcodebuild", root / "xcrun"
        for path in (self.xcodebuild, self.xcrun, self.args.developer_dir.parent / "Info.plist"):
            write(path, b"inert Xcode input; never execute", path != self.args.developer_dir.parent / "Info.plist")
        self.jars, components = [], {}
        binary = inert_macho()
        supervisor = {"protocolVersion": checks.PROTOCOL, "target": "macos-aarch64",
                      "sourceDigest": checks.digest_bytes(b"synthetic C source; not compiled"), "binaryDigest": checks.digest_bytes(binary)}
        role_groups = (("driver", "fixture_model", "provider"), ("runner", "supervisor_loader"), ("snapshot",), ("contracts",))
        for index, roles in enumerate(role_groups):
            path = root / f"classpath-{index}.jar"
            with zipfile.ZipFile(path, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
                for role in roles:
                    raw = inert_class(role)
                    jar.writestr(checks.COMPONENT_ENTRIES[role], raw)
                    components[role] = {"classpath_index": index, "digest": checks.digest_bytes(raw)}
                if index == 1:
                    jar.writestr(checks.NATIVE_RESOURCE + "manifest", "\n".join(supervisor.values()) + "\n")
                    jar.writestr(checks.NATIVE_RESOURCE + "macos-aarch64/supervisor", binary)
            self.jars.append(path)
        self.authority = {
            "schema_version": 1, "evidence_kind": "owner_enrolled_native_throws_build", "context": CONTEXT,
            "source": IDENTITY, "fixture_manifest_digest": checks.MANIFEST_DIGEST, "git": pin(self.git, True),
            "classpath": [pin(path, True) for path in self.jars], "components": components, "java21": self.java[21], "java25": self.java[25],
            "gradle": {"wrapper": str(self.args.gradle), "user_home": str(self.args.gradle_user_home),
                       "distribution_home": str(distribution), "wrapper_files": {name: pin(root / name) for name in checks.WRAPPER_FILES},
                       "distribution_files": tree_pins(distribution), "cache_marker": pin(marker, True)},
            "native": {"home": str(native), "data_dir": str(self.args.konan_data_dir), "targets": list(checks.TARGETS),
                       "files": tree_pins(native), "dependencies": dependencies},
            "xcode": {"developer_dir": str(self.args.developer_dir), "version": "26.4.1", "build": "17E202",
                      "iphoneos_sdk": "26.4", "iphonesimulator_sdk": "26.4", "files": {
                          "xcodebuild": pin(self.xcodebuild, True), "xcrun": pin(self.xcrun, True),
                          "info_plist": pin(self.args.developer_dir.parent / "Info.plist", True)}},
            "supervisor_identity": supervisor,
        }
        self.save_authority()

    def save_authority(self):
        raw = json_bytes(self.authority)
        write(self.args.authority, raw)
        self.args.authority_digest = checks.digest_bytes(raw)

    @contextmanager
    def context(self):
        facade = synthetic_posix_os(self.root)
        with ExitStack() as stack:
            stack.callback(facade.fixture_io.assert_closed)
            stack.enter_context(patch.object(checks, "os", facade))
            stack.enter_context(patch.object(checks, "signal", synthetic_posix_signal()))
            stack.enter_context(patch.object(checks.platform, "system", return_value="Darwin"))
            stack.enter_context(patch.object(checks.platform, "machine", return_value="arm64"))
            stack.enter_context(patch.object(checks, "XCODEBUILD", self.xcodebuild))
            stack.enter_context(patch.object(checks, "XCRUN", self.xcrun))
            stack.enter_context(patch.dict(os.environ, dict(zip(("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_JOB"), CONTEXT.values()))))
            stack.enter_context(patch.object(checks, "UNREAPED_CHILDREN", []))
            stack.enter_context(patch.object(checks, "sigchld_kernel_disposition", return_value=None))
            yield

    def case(self, name="empty-list"):
        return next(case for case in self.cohort["cases"] if case["name"] == name)

    def envelope(self, directory, case, target="iosArm64", task_proof=True):
        snapshot = checks.digest_bytes(("SYNTHETIC " + case["name"] + target).encode("ascii"))
        root = directory / "tmp/work/snapshot"
        output = directory / "tmp/work/outputs/provider"
        receipts = []
        for phase in ("jdk-version", "native-version", "provider"):
            stdout = ('openjdk version "25.0.5"\n' if phase == "jdk-version" else
                      "kotlinc-native 2.4.21 (synthetic metadata)\n" if phase == "native-version" else "")
            command = [str(self.args.project_java_home / "bin/java"), "-version"] if phase == "jdk-version" else \
                [str(self.args.native_home / "bin/konanc"), "-version"] if phase == "native-version" else \
                checks.provider_command(self.args, case, target, root, output)
            exit_code = 0
            if phase == "provider":
                named = case["expectation"] == "named_error"
                exit_code = 1 if named or not task_proof else 0
                if task_proof:
                    stdout = "> Task " + checks.tasks(target)[0] + "\n> Task " + checks.tasks(target)[1] + (" FAILED" if named else "") + "\n"
                if named:
                    anchor = case["anchors"][0]
                    stdout += f"e: {root / case['sourcePath']}:{anchor['line']}:{anchor['firstColumn'] + 1}: [{case['upstreamId']}] {PRIVATE}\n"
                elif task_proof:
                    stdout += "BUILD SUCCESSFUL\n"
                else:
                    stdout += "error: prohibited severity change (synthetic observation)\n"
            receipts.append({"phase": phase, "snapshotId": snapshot, "analysisKey": checks.digest_bytes((phase + snapshot).encode("ascii")),
                "status": "executed", "reasons": [], "exitCode": exit_code, "executionConfirmed": True,
                "supervisorIdentity": copy.deepcopy(self.authority["supervisor_identity"]), "cancelled": False, "timedOut": False,
                "truncated": False, "metrics": {"elapsedMillis": 1, "cpuMillis": None, "peakRssBytes": None,
                    "outputBytes": len(stdout.encode("utf-8"))}, "command": command,
                "outputDirectory": str(output if phase == "provider" else output.parent / phase),
                "stdoutDigest": checks.digest_bytes(stdout.encode("utf-8")), "stderrDigest": checks.digest_bytes(b""), "stdout": stdout, "stderr": ""})
        process = receipts[-1]
        findings = []
        if case["expectation"] == "named_error":
            anchor = case["anchors"][0]
            findings = [{"ruleId": case["ruleId"], "upstreamId": case["upstreamId"], "providerId": "kotlin-native",
                         "context": checks.scope(target), "severity": "error", "semanticDigest": process["analysisKey"],
                         "fingerprint": checks.digest_bytes(b"synthetic finding"), "diagnosis": PRIVATE, "location": {
                             "path": case["sourcePath"], "fileDigest": self.pins[case["sourcePath"]]["digest"], "line": anchor["line"],
                             "endLine": anchor["line"], "column": anchor["firstColumn"], "endColumn": anchor["firstColumn"]}}]
        coverage = {"ruleId": case["ruleId"], "context": checks.scope(target), "state": "incomplete", "reason": checks.QUALIFICATION_REASON,
                    "required": True, "depth": "build", "provenance": "executed", "certificateId": checks.digest_bytes(
                        (process["analysisKey"] + "\n" + process["stdout"] + "\n" + process["stderr"]).encode("utf-8")),
                    "implemented": True, "tested": False, "qualified": False}
        observation = case["expectation"] == "observation_only"
        return {"schemaVersion": 1, "cohortId": checks.COHORT, "case": case["name"], "target": target, "snapshotId": snapshot,
                "releaseQualified": False, "targetQualificationEstablished": False, "sourceManifestDigest": checks.MANIFEST_DIGEST,
                "requestedRuleIds": [case["ruleId"]], "modelBinding": checks.model_binding(snapshot, case, target, self.pins),
                "processReceipts": receipts, "providerProcessExecutionConfirmed": True, "nativeCompilationTaskExecutionObserved": task_proof,
                "artifactIdentities": list(checks.expected_artifacts(self.authority, self.args, target).values()),
                "assertionFailures": ["native_throws_observation_only_not_a_passing_control"] if observation else [],
                "state": "observed_incomplete" if observation else "passed", "providerResult": {
                    "providerId": "kotlin-native", "providerVersion": checks.VERSION, "findings": findings,
                    "rawDiagnostics": [], "reasons": [checks.QUALIFICATION_REASON], "status": "incomplete", "coverage": [coverage]}}

    def fake_driver(self, argv, *, directory, **kwargs):
        # This function is a deliberately FAKE CI process, not a JVM stand-in.
        self.calls.append(argv)
        options = dict(zip(argv[argv.index(checks.MAIN_CLASS) + 1::2], argv[argv.index(checks.MAIN_CLASS) + 2::2]))
        private = Path(options["--result"]).parent
        case = self.case(options["--case"])
        value = self.envelope(private, case, options["--target"],
                              task_proof=case["name"] != "override-filter-demotion-denied")
        command = value["processReceipts"][-1]["command"]
        root = Path(command[command.index("-p") + 1])
        if not root.exists():
            shutil.copytree(self.args.fixture, root)
        for row in value["processReceipts"]:
            Path(row["outputDirectory"]).mkdir(parents=True, exist_ok=True)
        if self.alter:
            self.alter(value, len(self.calls), private)
        directory.mkdir(mode=0o700)
        write(directory / "stdout.log", (PRIVATE + "\n").encode("utf-8"))
        write(directory / "stderr.log", b"")
        write(private / "driver.json", json_bytes(value))
        return {"state": "completed", "return_code": 0, "execution_started": True, "elapsed_seconds": 1.0,
                "cleanup_seconds": 4.0, "cleanup_protocol_completed": True, "escaped_descendants_proven_absent": False,
                "group_quiescence_observed": True,
                "output_bytes": len(PRIVATE) + 1, "stdout_digest": pin(directory / "stdout.log")["digest"],
                "stderr_digest": checks.digest_bytes(b""), "code": None, "stop_further_launches": False}

    def run_fake(self, alter=None, sources=None, runner=None):
        self.calls, self.alter = [], alter
        with self.context(), patch.object(checks, "source_identity", side_effect=sources, return_value=IDENTITY), \
                patch.object(checks, "metadata_preflight"), patch.object(checks, "run_bounded", side_effect=runner or self.fake_driver), \
                redirect_stdout(io.StringIO()) as stdout:
            result = checks.run(self.args, self.root)
        self.public_stdout = stdout.getvalue()
        return result


class SyntheticPosixFixtureIOTests(unittest.TestCase):
    """Portable fake-physics controls, never real Windows/POSIX identity evidence."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve(strict=True)
        self.facade = synthetic_posix_os(self.root)
        self.addCleanup(self.facade.fixture_io.assert_closed)

    @staticmethod
    def stat_model(info, **changes):
        values = {name: getattr(info, name) for name in STAMP_FIELDS}
        values.update(changes)
        return SimpleNamespace(**values)

    def test_binary_flag_routes_read_and_write_descriptors_and_preserves_exact_bytes(self):
        raw = b"first\r\nsecond\n\x1aafter-control-z\n"
        path = self.root / "input"
        write(path, raw)
        real_open, actual_binary = os.open, getattr(os, "O_BINARY", 0)
        fake_binary, recorded = actual_binary or (1 << 29), []

        def binary_open(value, flags, mode=0o777):
            recorded.append(flags)
            # The flag-routing fake strips only its invented POSIX-host bit.
            return real_open(value, (flags & ~fake_binary) | actual_binary, mode)

        with patch.object(os, "O_BINARY", fake_binary, create=True), patch.object(os, "open", side_effect=binary_open):
            facade = synthetic_posix_os(self.root)
            with patch.object(checks, "os", facade):
                self.assertEqual(pin(path), checks.file_pin(path, checks.Budget.start(30)))
                self.assertEqual(raw, checks.bounded_read(path, checks.Budget.start(30), len(raw)))
                output = self.root / "output"
                fd = facade.open(output, facade.O_WRONLY | facade.O_CREAT | facade.O_EXCL, 0o600)
                try:
                    self.assertEqual(len(raw), facade.write(fd, raw))
                    facade.fsync(fd)
                finally:
                    facade.close(fd)
            facade.fixture_io.assert_closed()
        self.assertEqual(raw, output.read_bytes())
        self.assertEqual(3, len(recorded))
        self.assertTrue(all(flags & fake_binary for flags in recorded))

    def test_explicit_synthetic_crt_stat_difference_maps_only_unchanged_opened_bytes(self):
        path = self.root / "input"
        raw = b"inert exact enrolled fixture bytes\r\n\x1aend\n"
        write(path, raw)
        actual_fstat = self.facade.fixture_io.host.fstat

        def crt_model(fd):
            info = actual_fstat(fd)
            return self.stat_model(info, st_dev=info.st_dev + 11, st_ino=info.st_ino + 13,
                                   st_mtime_ns=info.st_mtime_ns // 1_000_000_000 * 1_000_000_000, st_ctime_ns=0)

        with patch.object(self.facade.fixture_io.host, "fstat", side_effect=crt_model), patch.object(checks, "os", self.facade):
            self.assertEqual(pin(path), checks.file_pin(path, checks.Budget.start(30)))
            self.assertEqual(raw, checks.bounded_read(path, checks.Budget.start(30), len(raw)))
        self.assertEqual({}, self.facade.fixture_io.opened)

    def test_undeclared_root_aliased_path_and_unknown_fds_never_delegate_io(self):
        outside = self.root.parent / "not-owned-synthetic-input"
        aliased = self.root / ".." / self.root.name / "input"
        no_root = synthetic_posix_os()
        with patch.object(self.facade.fixture_io.host, "open") as opened, patch.object(no_root.fixture_io.host, "open") as undeclared:
            for facade, path in ((self.facade, outside), (self.facade, aliased), (no_root, self.root / "input")):
                with self.subTest(path_kind="owned" if facade is no_root else "outside-or-alias"), \
                        self.assertRaisesRegex(AssertionError, "No unowned or aliased fixture IO"):
                    facade.open(path, os.O_RDONLY)
            opened.assert_not_called()
            undeclared.assert_not_called()
        with ExitStack() as stack:
            host_calls = [stack.enter_context(patch.object(self.facade.fixture_io.host, name))
                          for name in ("fstat", "read", "write", "lseek", "ftruncate", "fsync", "close")]
            for name, args in (("fstat", ()), ("read", (1,)), ("write", (b"x",)), ("lseek", (0, os.SEEK_SET)),
                               ("ftruncate", (0,)), ("fsync", ()), ("close", ())):
                with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "No unowned fixture descriptor IO"):
                    getattr(self.facade, name)(123456, *args)
            with self.assertRaisesRegex(AssertionError, "No unowned fixture descriptor IO"):
                self.facade.fixture_io.fingerprint(123456)
            for call in host_calls:
                call.assert_not_called()

    def test_same_size_restored_mtime_byte_mutation_during_read_still_changes_stamp(self):
        for operation in ("file_pin", "bounded_read"):
            with self.subTest(operation=operation):
                path = self.root / operation
                write(path, b"A" * 32)
                before = path.stat()
                facade = synthetic_posix_os(self.root)
                actual_fstat, actual_read = facade.fixture_io.host.fstat, facade.read
                held_stat, mutated, observed_stamps = [], [False], []

                def fixed_host_stat(fd):
                    if not held_stat:
                        held_stat.append(actual_fstat(fd))
                    return held_stat[0]  # Explicit lossy host-stat model; bytes must still witness mutation.

                def mutate_after_read(fd, size):
                    part = actual_read(fd, size)
                    if part and not mutated[0]:
                        write(path, b"B" * 32)
                        os.utime(path, ns=(before.st_atime_ns, before.st_mtime_ns))
                        mutated[0] = True
                    return part

                original_fstat = facade.fstat

                def observed_fstat(fd):
                    info = original_fstat(fd)
                    observed_stamps.append(checks.stamp(info))
                    return info

                with patch.object(facade.fixture_io.host, "fstat", side_effect=fixed_host_stat), \
                        patch.object(facade, "read", side_effect=mutate_after_read), \
                        patch.object(facade, "fstat", side_effect=observed_fstat), patch.object(checks, "os", facade):
                    with self.assertRaises(checks.Problem) as caught:
                        if operation == "file_pin":
                            checks.file_pin(path, checks.Budget.start(30))
                        else:
                            checks.bounded_read(path, checks.Budget.start(30), 32)
                self.assertEqual("native_throws_file_changed", str(caught.exception))
                self.assertTrue(mutated[0])
                self.assertNotEqual(observed_stamps[0], observed_stamps[-1])
                self.assertEqual(b"B" * 32, path.read_bytes())
                facade.fixture_io.assert_closed()

    def test_each_actual_handle_stamp_field_change_is_preserved_as_a_synthetic_negative(self):
        for field in STAMP_FIELDS:
            with self.subTest(field=field):
                path = self.root / field
                write(path, b"unchanged owned bytes")
                facade = synthetic_posix_os(self.root)
                actual_fstat, actual_read = facade.fixture_io.host.fstat, facade.read
                changed = [False]

                def handle_model(fd):
                    info = actual_fstat(fd)
                    return self.stat_model(info, **{field: getattr(info, field) + 1}) if changed[0] else info

                def mark_read(fd, size):
                    part = actual_read(fd, size)
                    changed[0] = changed[0] or bool(part)
                    return part

                with patch.object(facade.fixture_io.host, "fstat", side_effect=handle_model), \
                        patch.object(facade, "read", side_effect=mark_read), patch.object(checks, "os", facade):
                    with self.assertRaises(checks.Problem) as caught:
                        checks.file_pin(path, checks.Budget.start(30))
                self.assertEqual("native_throws_file_changed", str(caught.exception))
                facade.fixture_io.assert_closed()

    def test_synthetic_replacement_path_identity_never_refreshes_the_held_opened_stamp(self):
        # Explicit replacement physics avoid claiming Windows permits renaming a
        # real CRT-open handle. Real owned bytes remain unchanged; only the final
        # path identity is modeled as replaced, independently of the held stamp.
        for operation in ("file_pin", "bounded_read"):
            with self.subTest(operation=operation):
                path = self.root / operation
                write(path, b"original opened bytes")
                original_info, actual_lstat = path.lstat(), Path.lstat
                replaced, observed_stamps = [False], []
                facade = synthetic_posix_os(self.root)
                actual_read, actual_fstat = facade.read, facade.fstat

                def path_model(current, *args, **kwargs):
                    if current == path and replaced[0]:
                        return self.stat_model(original_info, st_ino=original_info.st_ino + 1)
                    return actual_lstat(current, *args, **kwargs)

                def replace_after_read(fd, size):
                    part = actual_read(fd, size)
                    replaced[0] = replaced[0] or bool(part)
                    return part

                def held_fstat(fd):
                    info = actual_fstat(fd)
                    observed_stamps.append(checks.stamp(info))
                    return info

                with patch.object(Path, "lstat", new=path_model), patch.object(facade, "read", side_effect=replace_after_read), \
                        patch.object(facade, "fstat", side_effect=held_fstat), patch.object(checks, "os", facade):
                    with self.assertRaises(checks.Problem) as caught:
                        if operation == "file_pin":
                            checks.file_pin(path, checks.Budget.start(30))
                        else:
                            checks.bounded_read(path, checks.Budget.start(30), original_info.st_size)
                self.assertEqual("native_throws_file_changed", str(caught.exception))
                self.assertTrue(replaced[0])
                self.assertTrue(all(value == checks.stamp(original_info) for value in observed_stamps))
                facade.fixture_io.assert_closed()

    def test_real_inventory_membership_change_remains_rejected(self):
        write(self.root / "inputs/first", b"inert enrolled bytes")
        with patch.object(checks, "os", self.facade):
            pins = checks.inventory(self.root, ["inputs"], checks.Budget.start(30))
            write(self.root / "inputs/extra", b"inert unselected bytes")
            with self.assertRaises(checks.Problem):
                checks.verify_inventory(self.root, ["inputs"], pins, checks.Budget.start(30))

    def test_module_local_fixture_facade_restores_after_exception_without_host_api_changes(self):
        path = self.root / "input"
        write(path, b"inert owned bytes")
        host_os = checks.os
        host_calls = {name: getattr(os, name) for name in ("open", "read", "fstat", "write", "close")}
        with self.assertRaisesRegex(RuntimeError, "synthetic unwind"):
            with patch.object(checks, "os", self.facade):
                self.assertEqual(pin(path), checks.file_pin(path, checks.Budget.start(30)))
                raise RuntimeError("synthetic unwind")
        self.assertIs(checks.os, host_os)
        self.assertEqual({}, self.facade.fixture_io.opened)
        for name, value in host_calls.items():
            self.assertIs(value, getattr(os, name))


class SourceContractTests(unittest.TestCase):
    def test_collector_and_tests_are_parseable_source_only(self):
        for path in (Path(checks.__file__), Path(__file__)):
            ast.parse(path.read_text())

    def test_frozen_manifest_and_authored20_expectations(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve(strict=True)
            fixture = root / checks.FIXTURE
            shutil.copytree(checks.REPO_ROOT / checks.FIXTURE, fixture)
            facade = synthetic_posix_os(root)
            with patch.object(checks, "os", facade):
                cohort, pins = checks.fixture_input(root, fixture, checks.Budget.start(30))
            facade.fixture_io.assert_closed()
        self.assertEqual(28, len(pins))
        self.assertEqual({"named_error": 7, "compatible": 11, "observation_only": 2}, Counter(case["expectation"] for case in cohort["cases"]))

    def test_first_mac_is_four_errors_and_four_counterparts_not_eight_errors(self):
        cases = [row for row in checks.AUTHORED_CONTROLS if row[0] in checks.FIRST_MAC]
        self.assertEqual(8, len(cases))
        self.assertEqual({"named_error": 4, "compatible": 4}, Counter(row[1] for row in cases))

    def test_direct_owner_classpath_launch_has_no_build_or_discovery(self):
        source = Path(checks.__file__).read_text()
        self.assertNotIn("shutil.which", source)
        self.assertNotIn("build/classes", source)
        self.assertNotIn("nativeThrowsFixture\"", source)
        self.assertIn("source_to_binary_provenance_verified\": False", source)
        self.assertIn("fresh_source_compilation_verified\": False", source)

    def test_held_leader_source_has_no_reaping_popen_method_before_signal(self):
        tree = ast.parse(Path(checks.__file__).read_text())
        bounded = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "run_bounded")
        forbidden = {"poll", "wait", "communicate", "send_signal", "terminate", "kill", "__exit__"}
        calls = [node.func.attr for node in ast.walk(bounded) if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)]
        self.assertFalse(forbidden.intersection(calls))
        self.assertIn("waitid", calls)
        self.assertIn("waitpid", calls)
        self.assertIn("killpg", calls)

    def test_public_error_codes_are_closed_and_every_static_code_is_registered(self):
        tree = ast.parse(Path(checks.__file__).read_text())
        codes = {node.value for node in ast.walk(tree) if isinstance(node, ast.Constant) and isinstance(node.value, str)
                 and node.value.startswith("native_throws_") and " " not in node.value and "\n" not in node.value}
        self.assertTrue(codes <= checks.PUBLIC_ERROR_CODES)
        self.assertEqual("native_throws_evidence_invalid", str(checks.Problem(PRIVATE)))


class SyntheticAuthorityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.fixture = Fixture(Path(self.temp.name).resolve(strict=True))
        self.context = self.fixture.context()
        self.context.__enter__()
        self.addCleanup(self.context.__exit__, None, None, None)

    def budget(self):
        return checks.Budget.start(30)

    def test_exact_synthetic_enrollment_pins_validate_without_execution(self):
        fixture = self.fixture
        with patch.object(checks.subprocess, "Popen", side_effect=AssertionError("No actual process authorized")):
            value = checks.enrollment_input(fixture.args, fixture.root, self.budget())
            launch = checks.verify_enrollment(value, fixture.args, fixture.root, self.budget())
        self.assertEqual(fixture.authority["supervisor_identity"], launch["supervisor_identity"])

    def test_duplicate_json_keys_are_rejected(self):
        with self.assertRaises(checks.Problem):
            checks.strict_json(b'{"status":"executed","status":"unavailable"}')

    def test_nonfinite_literal_and_overflow_are_rejected(self):
        for raw in (b'{"x":NaN}', b'{"x":Infinity}', b'{"x":-Infinity}', b'{"x":1e999}'):
            with self.assertRaises(checks.Problem):
                checks.strict_json(raw)

    def test_invalid_unicode_and_json_size_or_structure_are_rejected(self):
        for raw in (b'{"x":"\\ud800"}', b'[]', b'{"x":', b'{"x":"\xff"}'):
            with self.assertRaises(checks.Problem):
                checks.strict_json(raw)
        with self.assertRaises(checks.Problem):
            checks.strict_json(b'{"x":1}', limit=2)

    def test_external_authority_digest_is_mandatory_and_not_self_enrolled(self):
        self.fixture.args.authority_digest = checks.digest_bytes(b"different authority")
        with self.assertRaises(checks.Problem):
            checks.enrollment_input(self.fixture.args, self.fixture.root, self.budget())

    def test_wrong_job_source_schema_and_unknown_authority_fields_are_denied(self):
        fixture = self.fixture
        original = copy.deepcopy(fixture.authority)
        for edit in (lambda v: v["context"].update(job="other_job"), lambda v: v.update(schema_version=True),
                     lambda v: v["source"].update(file_count=True), lambda v: v.update(extra=PRIVATE)):
            fixture.authority = copy.deepcopy(original)
            edit(fixture.authority)
            fixture.save_authority()
            with self.assertRaises(checks.Problem):
                checks.enrollment_input(fixture.args, fixture.root, self.budget())

    def test_missing_same_job_context_is_unavailable(self):
        with patch.dict(os.environ, {"GITHUB_RUN_ATTEMPT": ""}):
            with self.assertRaises(checks.Problem):
                checks.ci_context()

    def test_boolean_component_index_and_unknown_role_fields_are_denied(self):
        value = copy.deepcopy(self.fixture.authority)
        value["components"]["runner"]["classpath_index"] = True
        with self.assertRaises(checks.Problem):
            checks.classpath_input(value, self.budget())
        value["components"]["runner"]["classpath_index"] = 1
        value["components"]["runner"]["extra"] = PRIVATE
        with self.assertRaises(checks.Problem):
            checks.classpath_input(value, self.budget())

    def test_changed_jar_bytes_and_duplicate_jar_paths_are_denied(self):
        value = copy.deepcopy(self.fixture.authority)
        value["classpath"].append(value["classpath"][0])
        with self.assertRaises(checks.Problem):
            checks.classpath_input(value, self.budget())
        with self.fixture.jars[0].open("ab") as file:
            file.write(b"changed")
        with self.assertRaises(checks.Problem):
            checks.classpath_input(self.fixture.authority, self.budget())

    def jar_alter(self, index, entries):
        path = self.fixture.jars[index]
        with zipfile.ZipFile(path) as jar:
            old = {name: jar.read(name) for name in jar.namelist()}
        old.update(entries)
        with zipfile.ZipFile(path, "w") as jar:
            for name, raw in old.items():
                jar.writestr(name, raw)
        self.fixture.authority["classpath"][index] = pin(path, True)

    def test_owned_class_shadowing_is_denied(self):
        self.jar_alter(3, {checks.COMPONENT_ENTRIES["provider"]: inert_class("provider")})
        with self.assertRaises(checks.Problem):
            checks.classpath_input(self.fixture.authority, self.budget())

    def test_owned_multirelease_override_is_denied(self):
        self.jar_alter(0, {"META-INF/versions/21/" + checks.COMPONENT_ENTRIES["driver"]: inert_class("driver")})
        with self.assertRaises(checks.Problem):
            checks.classpath_input(self.fixture.authority, self.budget())

    def test_manifest_classpath_including_folded_value_is_denied(self):
        self.jar_alter(0, {"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\nClass-Path: extra\r\n .jar\r\n\r\n"})
        with self.assertRaises(checks.Problem):
            checks.classpath_input(self.fixture.authority, self.budget())

    def test_loaded_fixture_and_supervisor_binary_pins_are_required(self):
        self.jar_alter(1, {checks.NATIVE_RESOURCE + "macos-aarch64/supervisor": b"not Mach-O"})
        with self.assertRaises(checks.Problem):
            checks.classpath_input(self.fixture.authority, self.budget())

    def test_jdk25_cannot_replace_explicit_broker_jdk21(self):
        with self.assertRaises(checks.Problem):
            checks.java_input(self.fixture.java[25], self.fixture.args.project_java_home, 21, self.budget())

    def test_jdk_release_and_nonlauncher_modules_are_rehashed(self):
        write(self.fixture.args.broker_java_home / "lib/modules", b"changed")
        with self.assertRaises(checks.Problem):
            checks.java_input(self.fixture.java[21], self.fixture.args.broker_java_home, 21, self.budget())

    def test_gradle_requires_exact_existing_checksum_selected_wrapper_cache(self):
        value = copy.deepcopy(self.fixture.authority["gradle"])
        value["distribution_home"] = str(self.fixture.root)
        with self.assertRaises(checks.Problem):
            checks.gradle_input(value, self.fixture.args, self.fixture.root, self.budget())

    def test_missing_wrapper_cache_marker_never_bootstraps(self):
        Path(self.fixture.authority["gradle"]["cache_marker"]["path"]).unlink()
        with self.assertRaises((checks.Problem, OSError)):
            checks.gradle_input(self.fixture.authority["gradle"], self.fixture.args, self.fixture.root, self.budget())

    def test_native_extra_selected_file_is_not_silently_ignored(self):
        write(self.fixture.args.native_home / "konan/lib/extra.jar", b"extra")
        with self.assertRaises(checks.Problem):
            checks.native_input(self.fixture.authority["native"], self.fixture.args, self.budget())

    def test_native_airplane_mode_is_preexisting_not_auto_written(self):
        path = self.fixture.args.native_home / "konan/konan.properties"
        raw = path.read_bytes().replace(b"airplaneMode=true", b"airplaneMode=false")
        write(path, raw)
        self.fixture.authority["native"]["files"]["konan/konan.properties"] = pin(path)
        with self.assertRaises(checks.Problem):
            checks.native_input(self.fixture.authority["native"], self.fixture.args, self.budget())
        self.assertEqual(raw, path.read_bytes())

    def test_missing_external_native_dependency_is_unavailable(self):
        (self.fixture.args.konan_data_dir / "dependencies/synthetic-llvm").rmdir()
        with self.assertRaises((checks.Problem, OSError)):
            checks.native_input(self.fixture.authority["native"], self.fixture.args, self.budget())

    def test_wrong_xcode_sdk_and_tool_origin_are_denied(self):
        value = copy.deepcopy(self.fixture.authority["xcode"])
        value["iphonesimulator_sdk"] = "26.3"
        with self.assertRaises(checks.Problem):
            checks.xcode_input(value, self.fixture.args, self.budget())
        value["iphonesimulator_sdk"] = "26.4"
        value["files"]["xcrun"] = pin(self.fixture.git, True)
        with self.assertRaises(checks.Problem):
            checks.xcode_input(value, self.fixture.args, self.budget())

    def test_symlink_file_and_parent_aliases_are_denied(self):
        alias = self.fixture.root / "alias"
        alias.symlink_to(self.fixture.git)
        with self.assertRaises(checks.Problem):
            checks.absolute(alias)

    def test_injection_variables_and_credentials_are_not_inherited(self):
        hostile = {name: PRIVATE for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH",
                   "GRADLE_OPTS", "LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "GITHUB_TOKEN")}
        with patch.dict(os.environ, hostile):
            env = checks.safe_environment(self.fixture.args, self.fixture.args.broker_java_home)
        self.assertFalse(set(hostile).intersection(env))
        self.assertNotIn(PRIVATE, json.dumps(env))


class SyntheticDriverReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.fixture = Fixture(Path(self.temp.name).resolve(strict=True))
        self.private = self.fixture.root / "build/case"
        self.case = self.fixture.case()

    def validate(self, value, case=None, target="iosArm64"):
        return checks.validate_driver(value, case or self.case, target, self.fixture.authority, self.fixture.args,
                                      self.fixture.pins, self.private)

    def value(self, name="empty-list", **kwargs):
        return self.fixture.envelope(self.private, self.fixture.case(name), **kwargs)

    def rehash(self, value):
        for row in value["processReceipts"]:
            row["stdoutDigest"] = checks.digest_bytes(row["stdout"].encode("utf-8"))
            row["stderrDigest"] = checks.digest_bytes(row["stderr"].encode("utf-8"))
            row["metrics"]["outputBytes"] = len((row["stdout"] + row["stderr"]).encode("utf-8"))
        process = value["processReceipts"][-1]
        value["providerResult"]["coverage"][0]["certificateId"] = checks.digest_bytes(
            (process["analysisKey"] + "\n" + process["stdout"] + "\n" + process["stderr"]).encode("utf-8"))

    def test_synthetic_named_error_and_compatible_shapes_validate_not_execute(self):
        self.assertEqual([], self.validate(self.value())[0])
        case = self.fixture.case("empty-list-nonempty")
        self.assertEqual([], self.validate(self.value(case["name"]), case)[0])

    def test_three_complete_receipts_and_loaded_identity_are_mandatory(self):
        for edit in (lambda v: v["processReceipts"].pop(), lambda v: v["processReceipts"][0].update(executionConfirmed=False),
                     lambda v: v["processReceipts"][1].update(status="unavailable"),
                     lambda v: v["processReceipts"][2]["supervisorIdentity"].update(binaryDigest=checks.digest_bytes(b"different")),
                     lambda v: v["processReceipts"][2].update(cancelled=True)):
            value = self.value()
            edit(value)
            errors, projection = self.validate(value)
            self.assertIn("native_throws_driver_process_evidence_unconfirmed", errors)
            self.assertFalse(projection["provider_process_execution_confirmed"])

    def test_boolean_exit_metrics_and_modified_raw_output_are_denied(self):
        for edit in (lambda v: v["processReceipts"][0].update(exitCode=False),
                     lambda v: v["processReceipts"][0]["metrics"].update(elapsedMillis=True),
                     lambda v: v["processReceipts"][2].update(stdout=PRIVATE)):
            value = self.value()
            edit(value)
            self.assertTrue(self.validate(value)[0])

    def test_wrong_scope_model_digest_and_extra_compilation_member_are_denied(self):
        for edit in (lambda v: v.update(target="iosSimulatorArm64"),
                     lambda v: v["modelBinding"].update(modelDigest=checks.digest_bytes(b"wrong model")),
                     lambda v: v["modelBinding"]["sourceSetByFile"].update({"unselected.kt": "iosMain"})):
            value = self.value()
            edit(value)
            self.assertTrue(self.validate(value)[0])

    def test_stale_phase_output_and_escaping_provider_path_are_denied(self):
        for edit in (lambda v: v["processReceipts"][0].update(outputDirectory=str(self.fixture.root / "stale")),
                     lambda v: v["processReceipts"][2]["command"].__setitem__(7, str(self.private / "tmp/../../outside"))):
            value = self.value()
            edit(value)
            self.assertTrue(self.validate(value)[0])

    def test_task_cache_duplicate_sibling_failure_and_missing_target_are_denied(self):
        required = checks.tasks("iosArm64")
        for output in (f"> Task {required[0]} FROM-CACHE\n> Task {required[1]} FAILED\n",
                       f"> Task {required[0]}\n> Task {required[1]} FAILED\n> Task {required[1]} FAILED\n",
                       f"> Task {required[0]}\n> Task {required[1]} FAILED\n> Task :compileKotlinJvm FAILED\n",
                       f"> Task {required[0]}\n"):
            value = self.value()
            value["processReceipts"][-1]["stdout"] = output
            self.rehash(value)
            self.assertIn("native_throws_driver_actual_task_proof_invalid", self.validate(value)[0])

    def test_diagnostic_anchor_rule_source_digest_and_semantic_key_are_bound(self):
        for edit in (lambda f: f["location"].update(line=1000), lambda f: f["location"].update(column=True),
                     lambda f: f["location"].update(fileDigest=checks.digest_bytes(b"different source")),
                     lambda f: f.update(upstreamId="OTHER_DIAGNOSTIC"), lambda f: f.update(semanticDigest=checks.digest_bytes(b"wrong key"))):
            value = self.value()
            edit(value["providerResult"]["findings"][0])
            self.assertIn("native_throws_named_error_binding_invalid", self.validate(value)[0])

    def test_compatible_case_needs_exit_zero_no_diagnostics_and_real_task_markers(self):
        case = self.fixture.case("empty-list-nonempty")
        value = self.value(case["name"])
        value["processReceipts"][-1]["stderr"] = "warning: unrelated synthetic compiler warning\n"
        self.rehash(value)
        self.assertTrue(self.validate(value, case)[0])

    def test_compatible_failed_common_or_target_marker_contradicts_success_even_when_rebound(self):
        case = self.fixture.case("empty-list-nonempty")
        for task in checks.tasks("iosArm64"):
            with self.subTest(task=task):
                value = self.value(case["name"])
                process = value["processReceipts"][-1]
                process["stdout"] = process["stdout"].replace("> Task " + task + "\n", "> Task " + task + " FAILED\n")
                self.rehash(value)
                errors, projection = self.validate(value, case)
                self.assertIn("native_throws_driver_actual_task_proof_invalid", errors)
                self.assertNotIn("native_throws_driver_process_evidence_unconfirmed", errors)
                self.assertNotIn("native_throws_driver_gaps_or_false_qualification", errors)
                self.assertFalse(projection["native_compilation_task_execution_observed"])

    def test_named_error_needs_executed_common_and_failed_target_even_when_rebound(self):
        required = checks.tasks("iosArm64")
        for before, after in (("> Task " + required[0] + "\n", "> Task " + required[0] + " FAILED\n"),
                              ("> Task " + required[1] + " FAILED\n", "> Task " + required[1] + "\n")):
            value = self.value()
            process = value["processReceipts"][-1]
            process["stdout"] = process["stdout"].replace(before, after)
            self.rehash(value)
            errors, projection = self.validate(value)
            self.assertIn("native_throws_driver_actual_task_proof_invalid", errors)
            self.assertNotIn("native_throws_driver_process_evidence_unconfirmed", errors)
            self.assertNotIn("native_throws_driver_gaps_or_false_qualification", errors)
            self.assertFalse(projection["native_compilation_task_execution_observed"])

    def test_missing_or_duplicate_tool_identity_is_denied(self):
        for edit in (lambda v: v["artifactIdentities"].pop(), lambda v: v["artifactIdentities"].append(v["artifactIdentities"][0])):
            value = self.value()
            edit(value)
            self.assertIn("native_throws_driver_tool_artifacts_invalid", self.validate(value)[0])

    def test_successful_coverage_or_tested_qualified_flag_never_passes(self):
        for key, replacement in (("state", "passed"), ("tested", True), ("qualified", True), ("provenance", "not_executed")):
            value = self.value()
            value["providerResult"]["coverage"][0][key] = replacement
            self.assertIn("native_throws_driver_gaps_or_false_qualification", self.validate(value)[0])

    def test_observation_only_keeps_missing_task_proof_explicit_and_never_passes(self):
        case = self.fixture.case("override-filter-demotion-denied")
        value = self.value(case["name"], task_proof=False)
        errors, projection = self.validate(value, case)
        self.assertEqual([], errors)
        self.assertTrue(projection["observation_task_proof_missing"])
        self.assertFalse(projection["native_compilation_task_execution_observed"])
        value["state"], value["assertionFailures"] = "passed", []
        self.assertIn("native_throws_observation_not_preserved", self.validate(value, case)[0])

    def test_public_driver_projection_never_contains_hostile_private_strings(self):
        _, projection = self.validate(self.value())
        self.assertNotIn(PRIVATE, json.dumps(projection))
        self.assertNotIn(str(self.fixture.root), json.dumps(projection))


class FakeSerialCollectorTests(unittest.TestCase):
    def fixture(self, cohort="first-mac", targets=None):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        return Fixture(Path(temporary.name).resolve(strict=True), cohort, targets)

    def test_missing_authority_has_requested_eight_no_attempt(self):
        fixture = self.fixture()
        fixture.args.authority.unlink()
        result = fixture.run_fake()
        self.assertEqual("unavailable", result["state"])
        self.assertEqual(8, result["counts"]["selected_invocations"])
        self.assertEqual(8, result["counts"]["not_attempted"])
        self.assertEqual([], fixture.calls)

    def test_missing_external_authority_digest_has_requested_eight_no_attempt(self):
        fixture = self.fixture()
        fixture.args.authority_digest = None
        result = fixture.run_fake()
        self.assertEqual("unavailable", result["state"])
        self.assertEqual(8, result["counts"]["not_attempted"])
        self.assertIsNone(result["owner_enrollment_digest"])
        self.assertEqual([], fixture.calls)

    def test_non_apple_host_has_requested_scope_and_no_child_attempt(self):
        fixture = self.fixture("full", list(checks.TARGETS))
        with fixture.context(), patch.object(checks.platform, "system", return_value="Linux"), \
                patch.object(checks, "run_bounded", side_effect=AssertionError("No process authorized")):
            result = checks.run(fixture.args, fixture.root)
        self.assertEqual("unavailable", result["state"])
        self.assertEqual(40, result["counts"]["not_attempted"])

    def test_first_eight_are_serial_direct_prebuilt_jdk21_controls(self):
        fixture = self.fixture()
        result = fixture.run_fake()
        self.assertEqual("passed_controls_only", result["state"])
        self.assertEqual(8, result["counts"]["passed"])
        self.assertFalse(result["comprehensive_cohort_completed"])
        self.assertTrue(result["source_and_launch_tuple_verified_final"])
        self.assertEqual(list(checks.FIRST_MAC), [argv[argv.index("--case") + 1] for argv in fixture.calls])
        self.assertTrue(all(argv[0] == str(fixture.args.broker_java_home / "bin/java") and "-Xshare:off" in argv for argv in fixture.calls))

    def test_full20_one_target_is_incomplete_not_comprehensive_or_whole_scope_pass(self):
        fixture = self.fixture("full")
        result = fixture.run_fake()
        self.assertEqual("incomplete_controls_with_observations", result["state"])
        self.assertEqual("incomplete", result["required_status"])
        self.assertEqual(18, result["counts"]["passed"])
        self.assertEqual(2, result["counts"]["observed_incomplete"])
        self.assertTrue(result["full_selected_target_cohort_completed"])
        self.assertFalse(result["comprehensive_cohort_completed"])
        self.assertFalse(result["cases"][-1]["passing_control"])

    def test_full20_both_is_40_serial_invocations_not_40_distinct_controls(self):
        fixture = self.fixture("full", list(checks.TARGETS))
        result = fixture.run_fake()
        self.assertEqual(40, len(fixture.calls))
        self.assertEqual(20, result["selected_authored_control_count"])
        self.assertEqual(36, result["counts"]["passed"])
        self.assertEqual(4, result["counts"]["observed_incomplete"])
        self.assertTrue(result["comprehensive_cohort_completed"])
        self.assertEqual("incomplete", result["required_status"])

    def test_first_mac_two_targets_and_oversized_budget_are_rejected(self):
        for targets, timeout in ((list(checks.TARGETS), 1200), (["iosArm64"], 1201)):
            fixture = self.fixture(targets=targets)
            fixture.args.global_timeout = timeout
            with self.assertRaises(checks.Problem):
                checks.normalize_arguments(fixture.args, fixture.root)

    def test_duplicate_target_and_boolean_bounds_are_rejected(self):
        fixture = self.fixture()
        fixture.args.target = ["iosArm64", "iosArm64"]
        with self.assertRaises(checks.Problem):
            checks.normalize_arguments(fixture.args, fixture.root)
        fixture.args.target, fixture.args.case_timeout = ["iosArm64"], True
        with self.assertRaises(checks.Problem):
            checks.normalize_arguments(fixture.args, fixture.root)

    def test_outputs_are_fresh_and_second_attempt_cannot_overwrite(self):
        fixture = self.fixture()
        first = fixture.run_fake()
        raw = fixture.args.output.read_bytes()
        with self.assertRaises(checks.Problem):
            fixture.run_fake()
        self.assertEqual(raw, fixture.args.output.read_bytes())
        self.assertEqual(8, first["counts"]["attempted"])

    def test_missing_nested_receipt_stops_before_any_later_case(self):
        fixture = self.fixture()
        result = fixture.run_fake(lambda value, *_: value["processReceipts"].pop())
        self.assertEqual(1, len(fixture.calls))
        self.assertEqual(7, result["counts"]["not_attempted"])
        self.assertEqual("failed", result["state"])

    def test_wrong_source_before_case_has_no_provider_attempt(self):
        fixture = self.fixture()
        altered = {**IDENTITY, "working_tree_digest": checks.digest_bytes(b"changed")}
        result = fixture.run_fake(sources=[IDENTITY, altered])
        self.assertEqual([], fixture.calls)
        self.assertEqual("unavailable", result["state"])

    def test_post_case_launch_byte_change_stops_and_cannot_pass(self):
        fixture = self.fixture()
        result = fixture.run_fake(lambda *_: write(fixture.args.broker_java_home / "lib/modules", b"changed"))
        self.assertEqual(1, len(fixture.calls))
        self.assertEqual(0, result["counts"]["passed"])
        self.assertEqual("failed", result["state"])

    def test_real_private_snapshot_locations_and_bytes_are_rechecked(self):
        fixture = self.fixture()
        result = fixture.run_fake(lambda value, _, private: write(
            private / "tmp/work/snapshot" / fixture.case(value["case"])["sourcePath"], b"changed synthetic snapshot"))
        self.assertEqual(1, len(fixture.calls))
        self.assertIn("native_throws_driver_frozen_sources_changed", result["errors"])
        self.assertEqual(0, result["counts"]["passed"])

    def test_outer_timeout_or_unverified_cleanup_stops_all_later_cases(self):
        fixture = self.fixture()
        calls = []
        def failed(argv, **_):
            calls.append(argv)
            return {"state": "failed", "return_code": None, "execution_started": True,
                    "cleanup_protocol_completed": False, "code": "native_throws_cleanup_unverified"}
        result = fixture.run_fake(runner=failed)
        self.assertEqual(1, len(calls))
        self.assertEqual(7, result["counts"]["not_attempted"])

    def test_global_expiry_before_driver_launch_is_not_a_partial_pass(self):
        fixture = self.fixture()
        with fixture.context(), patch.object(checks.Budget, "remaining", side_effect=checks.Problem("native_throws_global_timeout")), \
                patch.object(checks, "run_bounded", side_effect=AssertionError("No process authorized")):
            result = checks.run(fixture.args, fixture.root)
        self.assertFalse(result["provider_controls_attempted"])
        self.assertEqual("unavailable", result["state"])

    def test_case_and_cohort_output_limits_include_driver_json_and_tmp_siblings(self):
        for limit in ("max_output_bytes", "max_cohort_bytes"):
            fixture = self.fixture()
            setattr(fixture.args, limit, 1024)
            result = fixture.run_fake()
            self.assertEqual(1, len(fixture.calls))
            self.assertEqual("failed", result["state"])

    def test_retained_raw_log_change_prevents_later_continuation(self):
        fixture = self.fixture()
        def alter(_, count, private):
            if count == 2:
                write(private.parent / "case-00/command/stdout.log", b"modified old raw log")
        result = fixture.run_fake(alter)
        self.assertEqual(2, len(fixture.calls))
        self.assertIn("native_throws_retained_originals_changed", result["errors"])
        self.assertEqual("failed", result["state"])

    def test_final_source_check_is_required_before_cohort_completion(self):
        fixture = self.fixture()
        sources = [IDENTITY] * 17 + [{**IDENTITY, "working_tree_digest": checks.digest_bytes(b"final change")}]
        result = fixture.run_fake(sources=sources)
        self.assertEqual(8, len(fixture.calls))
        self.assertFalse(result["source_and_launch_tuple_verified_final"])
        self.assertEqual("failed", result["state"])
        self.assertEqual(0, result["counts"]["passed"])

    def test_intermediate_publications_only_have_candidates_not_passing_controls(self):
        fixture = self.fixture()
        written = []
        original = checks.SummaryFile.write
        def capture(file, value, budget=None):
            written.append(copy.deepcopy(value))
            return original(file, value, budget)
        with patch.object(checks.SummaryFile, "write", capture):
            result = fixture.run_fake()
        self.assertTrue(any(stage["state"] == "control_candidate" for value in written[:-1] for stage in value["cases"]))
        self.assertFalse(any(stage["passing_control"] or stage["state"] == "passed" for value in written[:-1] for stage in value["cases"]))
        self.assertTrue(all(stage["passing_control"] for stage in written[-1]["cases"]))
        self.assertEqual("passed_controls_only", result["state"])

    def test_terminal_final_hash_expiry_prevents_promotion(self):
        fixture = self.fixture()
        now = [100.0]
        original = checks.private_tree_pins
        def pins(directory, budget):
            result = original(directory, budget)
            if directory == fixture.args.private_dir:
                now[0] = budget.deadline + 1
            return result
        with patch.object(checks.time, "monotonic", side_effect=lambda: now[0]), \
                patch.object(checks, "private_tree_pins", side_effect=pins):
            result = fixture.run_fake()
        self.assertEqual("failed", result["state"])
        self.assertEqual(0, result["counts"]["passed"])
        self.assertFalse(result["source_and_launch_tuple_verified_final"])

    def test_terminal_serialization_or_fsync_expiry_rewrites_failed_not_passed(self):
        for phase in ("serialization", "fsync"):
            fixture = self.fixture()
            now, final = [100.0], [False]
            original_dumps, original_fsync = checks.json.dumps, checks.os.fsync
            def dumps(value, *args, **kwargs):
                raw = original_dumps(value, *args, **kwargs)
                if isinstance(value, dict) and value.get("state") == "passed_controls_only":
                    final[0] = True
                    if phase == "serialization":
                        now[0] = 1301.0
                return raw
            def fsync(fd):
                original_fsync(fd)
                if final[0] and phase == "fsync":
                    now[0] = 1301.0
            with patch.object(checks.time, "monotonic", side_effect=lambda: now[0]), \
                    patch.object(checks.json, "dumps", side_effect=dumps), patch.object(checks.os, "fsync", side_effect=fsync):
                result = fixture.run_fake()
            self.assertEqual("failed", result["state"])
            self.assertEqual(0, result["counts"]["passed"])
            self.assertEqual("failed", json.loads(fixture.args.output.read_bytes())["state"])

    def assert_failed_publication(self, fixture, result):
        self.assertEqual("failed", result["state"])
        self.assertEqual("failed", result["required_status"])
        self.assertEqual(0, result["counts"]["passed"])
        self.assertFalse(result["source_and_launch_tuple_verified_final"])
        self.assertFalse(result["full_selected_target_cohort_completed"])
        self.assertFalse(result["comprehensive_cohort_completed"])
        self.assertFalse(any(stage["passing_control"] or stage["state"] == "passed" for stage in result["cases"]))
        self.assertNotIn(PRIVATE, json.dumps(result) + fixture.public_stdout)

    def test_terminal_serialization_error_downgrades_and_rewrites_once(self):
        fixture = self.fixture("full", list(checks.TARGETS))
        states = []
        original = checks.json.dumps
        def dumps(value, *args, **kwargs):
            if isinstance(value, dict) and value.get("cohort_id") == checks.COHORT:
                states.append(value["state"])
                if value["state"] == "incomplete_controls_with_observations":
                    raise ValueError(PRIVATE)
            return original(value, *args, **kwargs)
        with patch.object(checks.json, "dumps", side_effect=dumps):
            result = fixture.run_fake()
        self.assert_failed_publication(fixture, result)
        self.assertEqual(["incomplete_controls_with_observations", "failed"], states[-2:])
        self.assertIn("native_throws_public_summary_invalid", result["errors"])
        self.assertEqual("failed", json.loads(fixture.args.output.read_bytes())["state"])

    def test_terminal_ordinary_write_or_fsync_error_rewrites_failed_not_positive(self):
        for phase in ("write", "fsync"):
            fixture = self.fixture()
            states, active = [], [None]
            original_publish = checks.SummaryFile.write
            original_io = getattr(checks.os, phase)
            def publish(file, value, budget=None):
                states.append(value["state"])
                active[0] = value["state"]
                return original_publish(file, value, budget)
            def injected_io(*args):
                result = original_io(*args)
                if active[0] == "passed_controls_only":
                    raise OSError(PRIVATE)
                return result
            with patch.object(checks.SummaryFile, "write", publish), patch.object(checks.os, phase, side_effect=injected_io):
                result = fixture.run_fake()
            self.assert_failed_publication(fixture, result)
            self.assertEqual(["passed_controls_only", "failed"], states[-2:])
            self.assertIn("native_throws_public_summary_io_unavailable", result["errors"])
            self.assertEqual("failed", json.loads(fixture.args.output.read_bytes())["state"])
            self.assertEqual(8, len(fixture.calls))

    def test_terminal_failed_metadata_rewrite_returns_non_success_without_retry(self):
        fixture = self.fixture()
        states, terminal = [], [False]
        original = checks.SummaryFile.write
        def publish(file, value, budget=None):
            states.append(value["state"])
            if value["state"] == "passed_controls_only":
                terminal[0] = True
            result = original(file, value, budget)
            if terminal[0]:
                raise OSError(PRIVATE)
            return result
        with patch.object(checks.SummaryFile, "write", publish):
            result = fixture.run_fake()
        self.assert_failed_publication(fixture, result)
        self.assertEqual(["passed_controls_only", "failed"], states[-2:])
        self.assertEqual(2, sum(state in {"passed_controls_only", "failed"} for state in states))
        self.assertEqual(8, len(fixture.calls))
        with patch.object(checks, "parser", return_value=SimpleNamespace(parse_args=lambda _: fixture.args)), \
                patch.object(checks, "run", return_value=result), redirect_stdout(io.StringIO()):
            self.assertEqual(1, checks.main([]))

    def test_argument_parser_never_prints_invalid_private_option_values(self):
        with redirect_stdout(io.StringIO()) as stdout, redirect_stderr(io.StringIO()) as stderr, \
                patch.object(checks, "run", side_effect=AssertionError("No process authorized")):
            code = checks.main(["--unknown=" + PRIVATE])
        self.assertEqual(1, code)
        self.assertNotIn(PRIVATE, stdout.getvalue() + stderr.getvalue())
        self.assertIn("unavailable", stdout.getvalue())

    def test_summary_and_stdout_are_allowlisted_not_private_raw_results(self):
        fixture = self.fixture()
        result = fixture.run_fake()
        public = json.dumps(result) + fixture.public_stdout + fixture.args.output.read_text()
        self.assertNotIn(PRIVATE, public)
        self.assertNotIn(str(fixture.root), public)
        for key in ("source_to_binary_provenance_verified", "fresh_source_compilation_verified", "target_qualified", "release_qualified",
                    "external_native_dependency_closure_qualified", "raw_logs_uploaded"):
            self.assertFalse(result[key])
        self.assertIn(PRIVATE, (fixture.args.private_dir / "case-00/driver.json").read_text())

    def test_observation_cohort_main_exit_is_non_success(self):
        fixture = self.fixture("full")
        result = fixture.run_fake()
        with patch.object(checks, "run", return_value=result), redirect_stdout(io.StringIO()):
            argv = []
            for name in ("authority", "fixture", "broker_java_home", "project_java_home", "native_home", "gradle", "gradle_user_home",
                         "konan_data_dir", "developer_dir", "home", "private_dir", "output"):
                argv += ["--" + name.replace("_", "-"), str(getattr(fixture.args, name))]
            argv += ["--authority-digest", fixture.args.authority_digest, "--target", "iosArm64", "--cohort", "full", "--global-timeout", "7200"]
            self.assertEqual(1, checks.main(argv))


class HeldLeaderFakeProcessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve(strict=True)
        self.now, self.events = 100.0, []
        self.process = SimpleNamespace(pid=4242, returncode=None)

    @contextmanager
    def context(self, *, ready=True, lost=False, output=None, monitor=None, group="gone"):
        def popen(argv, **kwargs):
            self.events.append(("start", self.now, kwargs))
            if output:
                output(kwargs)
            return self.process
        def waitid(*args):
            self.events.append(("held", self.now, args))
            if lost:
                raise ChildProcessError(PRIVATE)
            return SimpleNamespace(si_pid=self.process.pid) if ready or any(event[0] == "signal" and event[2] == FAKE_SIGKILL for event in self.events) else None
        def killpg(pid, number):
            if number == 0:
                self.events.append(("probe", self.now, pid))
                if group == "gone":
                    raise ProcessLookupError()
                if group == "unknown":
                    raise PermissionError(PRIVATE)
                return
            self.events.append(("signal", self.now, number))
        def waitpid(pid, flags):
            self.events.append(("reap", self.now, flags))
            return pid, 0
        def sleep(delay):
            self.assertGreaterEqual(delay, 0)
            self.now += max(delay, 0.0001)
        facade = synthetic_posix_os(self.root, waitid=waitid, killpg=killpg, waitpid=waitpid)
        with ExitStack() as stack:
            stack.callback(facade.fixture_io.assert_closed)
            stack.enter_context(patch.object(checks, "os", facade))
            stack.enter_context(patch.object(checks, "signal", synthetic_posix_signal()))
            stack.enter_context(patch.object(checks, "subprocess", SimpleNamespace(Popen=popen, DEVNULL=checks.subprocess.DEVNULL)))
            stack.enter_context(patch.object(checks, "UNREAPED_CHILDREN", []))
            stack.enter_context(patch.object(checks, "sigchld_kernel_disposition", return_value=None))
            stack.enter_context(patch.object(checks.time, "monotonic", side_effect=lambda: self.now))
            stack.enter_context(patch.object(checks.time, "sleep", side_effect=sleep))
            if monitor:
                stack.enter_context(patch.object(checks, "private_size", side_effect=monitor))
            yield

    def run_fake(self, **kwargs):
        return checks.run_bounded(["FAKE CI child; no platform execution"], cwd=self.root, directory=self.root / "command",
                                  env={}, budget=checks.Budget(self.now + 30), timeout=10, max_bytes=1024, **kwargs)

    def test_full_grace_and_final_kill_precede_reap_even_for_exited_leader(self):
        with self.context():
            result = self.run_fake()
        signals = [event for event in self.events if event[0] == "signal"]
        self.assertEqual([FAKE_SIGTERM, FAKE_SIGKILL], [event[2] for event in signals])
        self.assertGreaterEqual(signals[1][1] - signals[0][1], checks.CLEANUP_SECONDS)
        self.assertGreaterEqual(next(event[1] for event in self.events if event[0] == "reap"), signals[1][1])
        self.assertTrue(checks.command_ok(result))
        self.assertFalse(result["escaped_descendants_proven_absent"])

    def test_sigchld_ignored_and_missing_wnowait_are_denied_before_launch(self):
        with self.context():
            with patch.object(checks.signal, "getsignal", return_value=checks.signal.SIG_IGN):
                with self.assertRaises(checks.Problem) as caught:
                    self.run_fake()
            self.assertEqual("native_throws_sigchld_ownership_unavailable", str(caught.exception))
            with patch.object(checks.os, "WNOWAIT", None):
                # Presence alone is not enough: a usable numeric WNOWAIT is required.
                with self.assertRaises(checks.Problem) as caught:
                    self.run_fake()
            self.assertEqual("native_throws_unreaped_process_guard_unavailable", str(caught.exception))
        self.assertFalse(any(event[0] == "start" for event in self.events))

    def test_each_missing_wait_capability_denies_launch_even_in_the_fake_facade(self):
        for name in ("waitid", "P_PID", "WEXITED", "WNOHANG", "WNOWAIT", "waitpid", "waitstatus_to_exitcode"):
            with self.subTest(name=name), self.context():
                delattr(checks.os, name)
                with self.assertRaises(checks.Problem) as caught:
                    self.run_fake()
                self.assertEqual("native_throws_unreaped_process_guard_unavailable", str(caught.exception))
        self.assertFalse(any(event[0] == "start" for event in self.events))

    def test_unconfigured_fake_wait_and_signal_apis_never_delegate_to_the_host(self):
        facade = synthetic_posix_os()
        for name in ("waitid", "waitpid", "killpg"):
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "no real process authorized"):
                getattr(facade, name)(4242, 0)

    def test_fake_facades_leave_host_apis_untouched_and_restore_after_failure(self):
        host_os, host_signal, host_subprocess = checks.os, checks.signal, checks.subprocess
        missing = object()
        host_waitid = getattr(host_os, "waitid", missing)
        host_getsignal, host_popen = host_signal.getsignal, host_subprocess.Popen
        with self.assertRaisesRegex(RuntimeError, "synthetic unwind"):
            with self.context():
                self.assertIsNot(checks.os, host_os)
                self.assertIsNot(checks.signal, host_signal)
                self.assertIsNot(checks.subprocess, host_subprocess)
                self.assertIs(host_waitid, getattr(host_os, "waitid", missing))
                self.assertIs(host_getsignal, host_signal.getsignal)
                self.assertIs(host_popen, host_subprocess.Popen)
                self.assertTrue(checks.command_ok(self.run_fake()))
                raise RuntimeError("synthetic unwind")
        self.assertIs(checks.os, host_os)
        self.assertIs(checks.signal, host_signal)
        self.assertIs(checks.subprocess, host_subprocess)
        self.assertIs(host_waitid, getattr(host_os, "waitid", missing))

    def test_lost_wait_ownership_never_signals_or_launches_a_later_child(self):
        with self.context(lost=True):
            result = self.run_fake()
            with self.assertRaises(checks.Problem):
                self.run_fake()
        self.assertFalse(result["cleanup_protocol_completed"])
        self.assertTrue(result["stop_further_launches"])
        self.assertFalse(any(event[0] == "signal" for event in self.events))
        self.assertEqual(1, sum(event[0] == "start" for event in self.events))

    def test_monitor_oserror_cannot_skip_full_owned_cleanup(self):
        def monitor(*_, **__):
            raise OSError(PRIVATE)
        with self.context(monitor=monitor):
            result = self.run_fake()
        self.assertEqual("failed", result["state"])
        self.assertTrue(result["cleanup_protocol_completed"])
        self.assertEqual([FAKE_SIGTERM, FAKE_SIGKILL], [event[2] for event in self.events if event[0] == "signal"])
        self.assertNotIn(PRIVATE, json.dumps(result))

    def test_live_case_sibling_output_limit_triggers_cleanup(self):
        sibling = self.root / "tmp"
        sibling.mkdir()
        def output(_):
            write(sibling / "large-driver-output", b"x" * 2048)
        with self.context(ready=False, output=output):
            result = self.run_fake(output_scopes=((self.root, 1024, "native_throws_case_output_limit"),))
        self.assertEqual("native_throws_case_output_limit", result["code"])
        self.assertTrue(result["cleanup_protocol_completed"])

    def test_live_global_cohort_output_limit_is_not_just_a_postcheck(self):
        def output(_):
            write(self.root / "earlier-case/raw", b"x" * 2048)
        with self.context(ready=False, output=output):
            result = self.run_fake(output_scopes=((self.root, 1024, "native_throws_cohort_output_limit"),))
        self.assertEqual("native_throws_cohort_output_limit", result["code"])
        self.assertTrue(result["cleanup_protocol_completed"])

    def test_timeout_reserves_full_grace_and_reap_inside_child_cap(self):
        with self.context(ready=False):
            result = self.run_fake()
        self.assertEqual("native_throws_command_timeout", result["code"])
        self.assertGreaterEqual(result["cleanup_seconds"], checks.CLEANUP_SECONDS)
        self.assertLessEqual(result["elapsed_seconds"], 10)
        self.assertTrue(result["cleanup_protocol_completed"])

    def test_insufficient_global_cleanup_budget_denies_launch(self):
        with self.context():
            with self.assertRaises(checks.Problem):
                checks.run_bounded(["FAKE"], cwd=self.root, directory=self.root / "command", env={},
                                   budget=checks.Budget(self.now + 5), timeout=10, max_bytes=1024)
        self.assertFalse(any(event[0] == "start" for event in self.events))

    def test_hidden_autoreap_change_during_held_wait_forbids_further_signaling(self):
        observations = [None, None, checks.Problem("native_throws_sigchld_auto_reap_prohibited")]
        with self.context(), patch.object(checks, "sigchld_kernel_disposition", side_effect=observations):
            result = self.run_fake()
        self.assertEqual("native_throws_wait_ownership_lost", result["code"])
        self.assertFalse(any(event[0] == "signal" for event in self.events))
        self.assertTrue(result["stop_further_launches"])

    def test_group_still_alive_or_unknown_after_reap_denies_continuation(self):
        for group in ("alive", "unknown"):
            self.now, self.events = 100.0, []
            command = self.root / "command"
            if command.exists():
                shutil.rmtree(command)
            with self.context(group=group):
                result = self.run_fake()
                with self.assertRaises(checks.Problem):
                    self.run_fake()
            self.assertFalse(checks.command_ok(result))
            self.assertFalse(result["group_quiescence_observed"])
            self.assertTrue(result["stop_further_launches"])
            reap = next(index for index, event in enumerate(self.events) if event[0] == "reap")
            self.assertFalse(any(event[0] == "signal" for event in self.events[reap + 1:]))

    def test_reap_that_crosses_child_deadline_never_completes_cleanup(self):
        def reap(pid, _):
            self.now = 111.0
            self.events.append(("reap", self.now, 0))
            return pid, 0
        with self.context(), patch.object(checks.os, "waitpid", side_effect=reap):
            result = self.run_fake()
        self.assertFalse(result["cleanup_protocol_completed"])
        self.assertTrue(result["stop_further_launches"])


class SyntheticDarwinSigactionTests(unittest.TestCase):
    def fake_api(self, flags=0, handler=None, status=0):
        calls = []
        class Observe:
            def __call__(self, number, new, old):
                calls.append((number, new))
                old._obj.handler = handler
                old._obj.flags = flags
                return status
        api = Observe()
        return api, calls

    def observe(self, api):
        with patch.object(checks, "signal", synthetic_posix_signal()), patch.object(checks.sys, "platform", "darwin"), \
                patch.object(checks.ctypes, "CDLL", return_value=SimpleNamespace(sigaction=api)):
            checks.sigchld_kernel_disposition()

    def test_synthetic_darwin_read_is_null_new_action_never_repaired(self):
        api, calls = self.fake_api()
        self.observe(api)
        self.assertEqual([(FAKE_SIGCHLD, None)], calls)
        self.assertEqual(3, len(api.argtypes))
        self.assertIs(checks.ctypes.c_int, api.restype)

    def test_synthetic_repeated_observations_reuse_one_sigaction_and_pointer_type(self):
        api, calls = self.fake_api()
        self.observe(api)
        first = tuple(api.argtypes)
        self.observe(api)
        self.assertEqual(2, len(calls))
        self.assertIs(checks.DarwinSigaction, first[1]._type_)
        self.assertIs(first[1], api.argtypes[1])
        self.assertIs(first[2], api.argtypes[2])

    def test_synthetic_hidden_autoreap_flag_and_raw_nondefault_handler_are_denied(self):
        for flags, handler in ((checks.DARWIN_SA_NOCLDWAIT, None), (0, 1)):
            api, _ = self.fake_api(flags, handler)
            with self.assertRaises(checks.Problem):
                self.observe(api)

    def test_sigaction_lookup_read_or_unknown_host_fails_closed(self):
        api, _ = self.fake_api(status=-1)
        with self.assertRaises(checks.Problem):
            self.observe(api)
        with patch.object(checks.sys, "platform", "darwin"), patch.object(checks.ctypes, "CDLL", side_effect=OSError(PRIVATE)):
            with self.assertRaises(checks.Problem):
                checks.sigchld_kernel_disposition()
        with patch.object(checks.sys, "platform", "linux"), \
                patch.object(checks.ctypes, "CDLL", side_effect=AssertionError("No Darwin API authorized")):
            with self.assertRaises(checks.Problem):
                checks.sigchld_kernel_disposition()

    def test_pointer_width_mismatch_denies_api_observation(self):
        with patch.object(checks.sys, "platform", "darwin"), patch.object(checks.ctypes, "sizeof", return_value=4), \
                patch.object(checks.ctypes, "CDLL", side_effect=AssertionError("No mismatched ABI authorized")):
            with self.assertRaises(checks.Problem):
                checks.sigchld_kernel_disposition()


if __name__ == "__main__":
    unittest.main()
