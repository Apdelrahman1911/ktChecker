#!/usr/bin/env python3
"""Synthetic probe-provisioning controls; no compiler, child, or native execution.

Only self-created bounded ASCII fixtures are cleaned, after all mocks exit.
These tests do not authorize retry/deletion of real retained attempts, qualify
compiler descendants, or establish native integration or publication readiness.
Fourteen controls use mocked exact-path permission observations on every host;
one additional POSIX-only symlink control is an explicit skip on Windows.
Root may separately admit this file with python3 -B, also under -O.
"""
from __future__ import annotations

from contextlib import ExitStack, contextmanager, redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import patch


SUBJECT_PATH = Path(__file__).resolve().with_name("build_live_probe.py")


def load_subject():
    module = ModuleType("_ktchecker_probe_ci_subject")
    module.__file__ = str(SUBJECT_PATH)
    exec(compile(SUBJECT_PATH.read_bytes(), str(SUBJECT_PATH), "exec"), module.__dict__)
    return module


subject = load_subject()


class SyntheticInterruption(BaseException):
    pass


class WriteFault:
    def __init__(self, stream, fixture, path):
        self.stream, self.fixture, self.path = stream, fixture, path
        self.close_error = None

    def __enter__(self):
        self.stream.__enter__()
        return self

    def write(self, data):
        label = data.decode("ascii").strip() if self.path.name == "phase" else self.path.name
        if label == self.fixture.close_at:
            self.close_error = self.fixture.close_error
        if label == self.fixture.short_at:
            return self.stream.write(data[:-1])
        return self.stream.write(data)

    def __exit__(self, kind, error, traceback):
        result = self.stream.__exit__(kind, error, traceback)
        if self.close_error is not None:
            raise self.close_error
        return result


class Fixture:
    FLAGS = ("-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-pedantic")

    def __init__(self, root):
        self.root, self.output = root, root / "attempt"
        self.source, self.builder, self.provisioner = (root / name for name in ("source.c", "builder.py", "tool.py"))
        self.source.write_bytes(b"synthetic probe source\n")
        self.builder.write_bytes(b"SENTINEL = 'verified source bytes'\n")
        self.provisioner.write_bytes(b"synthetic provisioning source\n")
        self.cc = root / "private-driver"
        self.cc.write_bytes(b"synthetic direct driver, never executed\n")
        self.modes, self.mkdir_calls = {self.cc: 0o700}, []
        self.host = {"os": "linux", "architecture": "x86_64", "target": "linux-x86_64"}
        self.calls, self.loads, self.errors, self.callbacks = [], [], {}, {}
        self.short_at = self.close_at = None
        self.close_error = OSError("private synthetic close detail")
        self.binary_kind = "regular"
        self.open_original = Path.open
        self.mkdir_original, self.chmod_original = Path.mkdir, Path.chmod
        self.lstat_original, self.access_original = Path.lstat, os.access
        self.driver = ModuleType("synthetic_local_driver")
        self.driver.FLAGS = self.FLAGS
        self.driver.host_identity = lambda: dict(self.host)
        self.driver.bounded_command = self.command

    @staticmethod
    def forbidden(*_args, **_kwargs):
        raise AssertionError("real process operation forbidden in synthetic probe controls")

    def load_driver(self, path):
        self.loads.append(path)
        return self.driver

    def open_file(self, path, mode="r", *args, **kwargs):
        stream = self.open_original(path, mode, *args, **kwargs)
        return WriteFault(stream, self, path) if path.parent == self.output and mode in ("ab", "xb") else stream

    def mkdir_file(self, path, mode=0o777, parents=False, exist_ok=False):
        if path == self.output:
            self.mkdir_calls.append((mode, parents, exist_ok))
        return self.mkdir_original(path, mode, parents, exist_ok)

    def chmod_file(self, path, mode, **kwargs):
        if path.is_relative_to(self.root):
            self.modes[path] = mode  # POSIX permission model, not Windows chmod evidence
            return None
        return self.chmod_original(path, mode, **kwargs)

    def lstat_file(self, path):
        info = self.lstat_original(path)
        if path == self.output / "probe" and path in self.modes and stat.S_ISREG(info.st_mode):
            return SimpleNamespace(st_mode=(info.st_mode & ~0o7777) | self.modes[path],
                                   st_dev=info.st_dev, st_ino=info.st_ino, st_size=info.st_size,
                                   st_mtime_ns=info.st_mtime_ns)
        return info

    def access_file(self, path, mode):
        path = Path(path)
        if path.is_relative_to(self.root):
            return (mode == os.X_OK and path.is_file() and
                    bool(self.modes.get(path.resolve(strict=True), 0) & stat.S_IXUSR))
        return self.access_original(path, mode)

    def command(self, argv, timeout):
        stage = "version" if argv == [str(self.cc), "--version"] else "compile"
        pending = "version_probe_pending" if stage == "version" else "compile_pending"
        if not (self.output / "phase").read_bytes().endswith((pending + "\n").encode("ascii")):
            raise AssertionError("synthetic compiler invoked without completed pending phase")
        if stage == "compile" and argv != [str(self.cc), *self.FLAGS, str(self.source), "-o", str(self.output / "probe")]:
            raise AssertionError("unexpected synthetic compiler arguments")
        self.calls.append((stage, argv, timeout))
        (self.output / "compiler-private-residue").write_bytes(b"private residue, not publishable\n")
        if stage == "compile":
            binary = self.output / "probe"
            if self.binary_kind == "regular":
                binary.write_bytes(b"synthetic output, never executed\n")
            elif self.binary_kind == "empty":
                binary.write_bytes(b"")
            elif self.binary_kind == "directory":
                binary.mkdir()
            elif self.binary_kind == "symlink":
                binary.symlink_to(self.source)
            elif self.binary_kind == "oversize":
                binary.write_bytes(b"x" * (subject.MAX_INPUT + 1))
        if stage in self.callbacks:
            self.callbacks[stage]()
        if stage in self.errors:
            raise self.errors[stage]
        return b"private compiler banner and transcript\n" if stage == "version" else b"private compiler output\n"

    def run(self):
        return subject.provision(self.output, self.cc)

    def receipt(self, name="receipt.json"):
        return json.loads((self.output / name).read_text(encoding="ascii"))


@contextmanager
def synthetic():
    # No production output or unknown child can enter this root. Cleanup is
    # exclusively of these synthetic fixtures and runs after mocks are removed.
    with tempfile.TemporaryDirectory(prefix="ktc-live-probe-synthetic-") as temporary:
        fixture = Fixture(Path(temporary).resolve())
        with ExitStack() as stack:
            stack.enter_context(patch.object(subject, "_input_paths", return_value=(fixture.source, fixture.builder, fixture.provisioner)))
            stack.enter_context(patch.object(subject, "REVIEWED_SOURCE_SHA256", subject._digest(fixture.source.read_bytes())))
            stack.enter_context(patch.object(subject, "REVIEWED_BUILDER_SHA256", subject._digest(fixture.builder.read_bytes())))
            stack.enter_context(patch.object(subject, "_load_driver", side_effect=fixture.load_driver))
            stack.enter_context(patch.object(Path, "open", new=lambda path, *a, **kw: fixture.open_file(path, *a, **kw)))
            stack.enter_context(patch.object(Path, "mkdir", new=lambda path, *a, **kw: fixture.mkdir_file(path, *a, **kw)))
            stack.enter_context(patch.object(Path, "chmod", new=lambda path, *a, **kw: fixture.chmod_file(path, *a, **kw)))
            stack.enter_context(patch.object(Path, "lstat", new=lambda path: fixture.lstat_file(path)))
            stack.enter_context(patch.object(os, "access", side_effect=fixture.access_file))
            for name in ("Popen", "run", "call", "check_call", "check_output"):
                stack.enter_context(patch.object(subprocess, name, side_effect=Fixture.forbidden))
            for name in ("kill", "killpg", "fork", "system", "posix_spawn", "posix_spawnp"):
                if hasattr(os, name):
                    stack.enter_context(patch.object(os, name, side_effect=Fixture.forbidden))
            yield fixture


class LiveProbeProvisioningTest(unittest.TestCase):
    def test_success_has_exact_calls_private_receipts_and_actual_output_identity(self):
        with synthetic() as fixture:
            binary = fixture.run()
            self.assertEqual(binary, fixture.output / "probe")
            self.assertEqual([(stage, seconds) for stage, _argv, seconds in fixture.calls], [("version", 10), ("compile", 60)])
            self.assertEqual((fixture.output / "phase").read_text().splitlines(), list(subject.PHASES))
            self.assertEqual(fixture.mkdir_calls, [(0o700, False, False)])
            self.assertEqual(fixture.modes[binary], 0o700)
            self.assertTrue(os.access(binary, os.X_OK))
            before, receipt = fixture.receipt("before.json"), fixture.receipt()
            self.assertEqual(before["status"], "prepared_not_launched")
            self.assertEqual(before["output"], {"path": "probe", "status": "absent"})
            self.assertEqual(receipt["status"], "direct_compile_return_observed")
            self.assertTrue(receipt["provisioningSuccessRequiresZeroExit"])
            self.assertEqual(receipt["beforeReceiptSha256"], subject._digest((fixture.output / "before.json").read_bytes()))
            self.assertEqual(receipt["inputsBefore"], receipt["inputsAfter"])
            self.assertEqual(receipt["compiler"]["before"], receipt["compiler"]["after"])
            self.assertEqual(receipt["output"]["sha256"], subject._digest(binary.read_bytes()))
            self.assertEqual(receipt["output"]["bytes"], binary.stat().st_size)
            self.assertEqual(receipt["flags"], list(Fixture.FLAGS))
            for stage, argv, _seconds in fixture.calls:
                exact = json.dumps(argv, ensure_ascii=True, separators=(",", ":")).encode("ascii")
                self.assertEqual(receipt["invocations"][stage]["exactArgvSha256"], subject._digest(exact))
            encoded = (fixture.output / "receipt.json").read_bytes()
            self.assertLessEqual(len(encoded), subject.MAX_RECEIPT)
            self.assertNotIn(str(fixture.root).encode(), encoded)
            self.assertNotIn(b"private compiler", encoded)
            for field in ("privatePathsRetained", "rawCompilerTranscriptRetained", "privateArgvRetained",
                          "nativeProbeExecuted", "nativeQualificationEstablished", "binaryPublicationAuthorized",
                          "transitiveCompilerToolchainVerified", "sourceToBinaryReproducibilityEstablished"):
                self.assertFalse(receipt[field])
            self.assertFalse((fixture.output / "failure.json").exists())
            self.assertEqual(fixture.driver.FLAGS, Fixture.FLAGS)

    def test_existing_directory_and_file_are_never_reclaimed(self):
        for kind in ("directory", "file"):
            with self.subTest(kind=kind), synthetic() as fixture:
                if kind == "directory":
                    fixture.output.mkdir()
                    (fixture.output / "sentinel").write_bytes(b"preserve prior attempt")
                else:
                    fixture.output.write_bytes(b"preserve prior entry")
                with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_attempt_retained"):
                    fixture.run()
                self.assertEqual(fixture.calls, [])
                self.assertEqual(fixture.loads, [])
                if kind == "directory":
                    self.assertEqual(list(fixture.output.iterdir()), [fixture.output / "sentinel"])
                else:
                    self.assertEqual(fixture.output.read_bytes(), b"preserve prior entry")

    def test_same_output_reentry_refuses_before_touching_first_attempt(self):
        with synthetic() as fixture:
            def reenter():
                prior = (fixture.output / "phase").read_bytes()
                with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_attempt_retained"):
                    fixture.run()
                self.assertEqual((fixture.output / "phase").read_bytes(), prior)
                self.assertTrue((fixture.output / "compiler-private-residue").is_file())
            fixture.callbacks["version"] = reenter
            fixture.run()
            self.assertEqual(len(fixture.calls), 2)
            self.assertEqual(len(fixture.loads), 1)

    def test_host_compiler_and_fresh_absolute_output_gates_prevent_launch(self):
        for case in ("host", "relative_cc", "missing_cc", "nonexecutable_cc", "relative_output", "missing_parent"):
            with self.subTest(case=case), synthetic() as fixture:
                if case == "host":
                    fixture.host = {"os": "macos", "architecture": "aarch64", "target": "macos-aarch64"}
                elif case == "relative_cc":
                    fixture.cc = Path("cc")
                elif case == "missing_cc":
                    fixture.cc = fixture.root / "absent-driver"
                elif case == "nonexecutable_cc":
                    fixture.cc.chmod(0o600)
                elif case == "relative_output":
                    fixture.output = Path("relative-attempt")
                else:
                    fixture.output = fixture.root / "absent-parent" / "attempt"
                with self.assertRaises(subject.ProbeProblem):
                    fixture.run()
                self.assertEqual(fixture.calls, [])
                if case not in ("relative_output", "missing_parent"):
                    self.assertTrue(fixture.output.is_dir())
                    self.assertEqual(fixture.receipt("failure.json")["status"], "failed_or_custody_unknown")

    def test_reviewed_source_and_builder_pins_are_required_before_loading(self):
        for name in ("source", "builder"):
            with self.subTest(name=name), synthetic() as fixture:
                getattr(fixture, name).write_bytes(b"changed synthetic input\n")
                with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_reviewed_input_mismatch"):
                    fixture.run()
                self.assertEqual(fixture.calls, [])
                self.assertEqual(fixture.loads, [])
                self.assertTrue((fixture.output / "phase").is_file())

    def test_both_compiler_unknowns_and_interruptions_preserve_primary_and_residue(self):
        for stage in ("version", "compile"):
            for error in (subject.ProbeProblem("native_supervisor_compiler_launch_unknown"),
                          subject.ProbeProblem("native_supervisor_compiler_cleanup_unknown"),
                          RuntimeError("private compiler failure detail"), SyntheticInterruption()):
                with self.subTest(stage=stage, error=type(error).__name__, reason=error.args), synthetic() as fixture:
                    fixture.errors[stage] = error
                    with self.assertRaises(BaseException) as caught:
                        fixture.run()
                    self.assertIs(caught.exception, error)
                    pending = "version_probe_pending" if stage == "version" else "compile_pending"
                    self.assertTrue((fixture.output / "phase").read_bytes().endswith((pending + "\n").encode()))
                    self.assertTrue((fixture.output / "compiler-private-residue").is_file())
                    self.assertFalse((fixture.output / "receipt.json").exists())
                    self.assertEqual(fixture.receipt("failure.json")["reason"], subject._public_reason(error))
                    self.assertNotIn("private compiler failure detail", (fixture.output / "failure.json").read_text())
                    if stage == "compile":
                        self.assertTrue((fixture.output / "probe").is_file())

    def test_phase_short_writes_stop_launch_or_completion_without_truncating_pending(self):
        expected_calls = dict(zip(subject.PHASES, (0, 0, 1, 1, 2, 2)))
        for phase, calls in expected_calls.items():
            with self.subTest(phase=phase), synthetic() as fixture:
                fixture.short_at = phase
                with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_phase_failed"):
                    fixture.run()
                self.assertEqual(len(fixture.calls), calls)
                state = (fixture.output / "phase").read_bytes()
                if calls:
                    self.assertIn(b"version_probe_pending\n", state)
                if calls == 2:
                    self.assertIn(b"compile_pending\n", state)
                if phase == "complete":
                    self.assertEqual(fixture.receipt()["status"], "direct_compile_return_observed")
                    self.assertTrue(fixture.receipt()["provisioningSuccessRequiresZeroExit"])
                self.assertTrue((fixture.output / "failure.json").is_file())

    def test_receipt_short_writes_and_phase_or_receipt_close_errors_are_failures(self):
        for label, calls in (("before.json", 0), ("receipt.json", 2), ("version_probe_pending", 0), ("complete", 2)):
            for fault in ("short", "close"):
                if fault == "short" and label not in ("before.json", "receipt.json"):
                    continue
                with self.subTest(label=label, fault=fault), synthetic() as fixture:
                    setattr(fixture, "short_at" if fault == "short" else "close_at", label)
                    with self.assertRaises(BaseException) as caught:
                        fixture.run()
                    if fault == "close":
                        self.assertIs(caught.exception, fixture.close_error)
                    else:
                        self.assertEqual(caught.exception.args, ("owned_live_probe_receipt_failed",))
                    self.assertEqual(len(fixture.calls), calls)
                    self.assertTrue((fixture.output / "failure.json").is_file())
                    if calls:
                        self.assertIn(b"compile_pending\n", (fixture.output / "phase").read_bytes())

    def test_failed_diagnostic_write_or_close_does_not_mask_primary_unknown(self):
        for fault in ("short", "close"):
            with self.subTest(fault=fault), synthetic() as fixture:
                primary = subject.ProbeProblem("native_supervisor_compiler_cleanup_unknown")
                fixture.errors["compile"] = primary
                setattr(fixture, "short_at" if fault == "short" else "close_at", "failure.json")
                with self.assertRaises(subject.ProbeProblem) as caught:
                    fixture.run()
                self.assertIs(caught.exception, primary)
                self.assertTrue((fixture.output / "probe").is_file())
                self.assertTrue((fixture.output / "phase").read_bytes().endswith(b"compile_pending\n"))

    def test_post_compile_source_builder_provisioner_and_driver_changes_refuse_success(self):
        for name in ("source", "builder", "provisioner", "cc"):
            with self.subTest(name=name), synthetic() as fixture:
                fixture.callbacks["compile"] = lambda: getattr(fixture, name).write_bytes(b"changed after compiler return\n")
                with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_input_changed"):
                    fixture.run()
                self.assertTrue((fixture.output / "probe").is_file())
                self.assertFalse((fixture.output / "receipt.json").exists())

    @unittest.skipUnless(os.name == "posix", "POSIX-only real symlink fixtures; not executed coverage on Windows")
    def test_posix_symlink_refusal_and_compiler_retarget_without_reclamation(self):
        with synthetic() as fixture:
            fixture.output.symlink_to(fixture.root / "missing")
            with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_attempt_retained"):
                fixture.run()
            self.assertTrue(fixture.output.is_symlink())
            self.assertEqual(fixture.calls, [])
            self.assertEqual(fixture.loads, [])
        with synthetic() as fixture:
            fixture.binary_kind = "symlink"
            with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_input_invalid"):
                fixture.run()
            self.assertTrue((fixture.output / "probe").is_symlink())
            self.assertFalse((fixture.output / "receipt.json").exists())
        with synthetic() as fixture:
            original = fixture.cc
            alternate = fixture.root / "alternate-driver"
            alternate.write_bytes(original.read_bytes())
            alternate.chmod(0o700)
            fixture.cc = fixture.root / "selected-driver"
            fixture.cc.symlink_to(original)
            def retarget():
                fixture.cc.unlink()  # self-owned synthetic link, never a real attempt
                fixture.cc.symlink_to(alternate)
            fixture.callbacks["compile"] = retarget
            with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_input_changed"):
                fixture.run()
            self.assertFalse((fixture.output / "receipt.json").exists())

    def test_missing_nonregular_empty_or_oversized_binary_is_retained_not_qualified(self):
        for kind in ("missing", "empty", "directory", "oversize"):
            with self.subTest(kind=kind), synthetic() as fixture:
                fixture.binary_kind = kind
                with self.assertRaises((subject.ProbeProblem, FileNotFoundError)):
                    fixture.run()
                self.assertTrue(fixture.output.is_dir())
                self.assertFalse((fixture.output / "receipt.json").exists())
                self.assertTrue((fixture.output / "failure.json").is_file())

    def test_unexpected_version_output_and_nonexecutable_final_binary_refuse_success(self):
        with synthetic() as fixture:
            fixture.callbacks["version"] = lambda: (fixture.output / "probe").write_bytes(b"unexpected prior output")
            with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_output_invalid"):
                fixture.run()
            self.assertEqual(len(fixture.calls), 1)
            self.assertEqual((fixture.output / "probe").read_bytes(), b"unexpected prior output")
        with synthetic() as fixture:
            original_access = os.access
            with patch.object(os, "access", side_effect=lambda path, mode: False if path == fixture.output / "probe" else original_access(path, mode)):
                with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_output_invalid"):
                    fixture.run()
            self.assertFalse((fixture.output / "receipt.json").exists())

    def test_driver_loader_uses_same_verified_bytes_without_ambient_module_or_pyc(self):
        loader = subject._load_driver
        with synthetic() as fixture:
            name = "_ktchecker_live_probe_private_driver"
            ambient = ModuleType(name)
            ambient.SENTINEL = "ambient must not run"
            (fixture.root / "builder.pyc").write_bytes(b"synthetic stale cache, not bytecode\n")
            expected = subject._digest(fixture.builder.read_bytes())
            with patch.dict(sys.modules, {name: ambient}), patch.object(subject, "REVIEWED_BUILDER_SHA256", expected):
                loaded = loader(fixture.builder)
                self.assertEqual(loaded.SENTINEL, "verified source bytes")
                self.assertIs(sys.modules[name], ambient)
                self.assertIsNot(loaded, ambient)
                fixture.builder.write_bytes(b"SENTINEL = 'changed file'\n")
                with self.assertRaisesRegex(subject.ProbeProblem, "owned_live_probe_reviewed_input_mismatch"):
                    loader(fixture.builder)

    def test_cli_requires_explicit_arguments_hands_off_only_success_and_redacts_errors(self):
        for argv in ([], ["--cc", "/usr/bin/cc"], ["--output", "/owner/private/attempt"]):
            with self.subTest(argv=argv), patch.object(subject, "provision", side_effect=Fixture.forbidden), redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as caught:
                    subject.main(argv)
                self.assertEqual(caught.exception.code, 2)
        argv = ["--cc", "/owner/private/cc", "--output", "/owner/private/attempt"]
        with patch.object(subject, "provision", return_value=Path("/owner/private/attempt/probe")) as run, redirect_stdout(io.StringIO()) as output:
            self.assertEqual(subject.main(argv), 0)
            run.assert_called_once_with(Path("/owner/private/attempt"), Path("/owner/private/cc"))
            self.assertEqual(output.getvalue(), str(Path("/owner/private/attempt/probe")) + "\n")
        with patch.object(subject, "provision", side_effect=RuntimeError("do not print this private path")), redirect_stdout(io.StringIO()) as output, redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(subject.main(argv), 1)
            self.assertEqual(output.getvalue(), "")
            self.assertEqual(errors.getvalue(), "owned_live_probe_build_failed\n")
        class BrokenHandoff:
            def __init__(self, fault):
                self.fault = fault

            def write(self, data):
                if self.fault == "write":
                    raise OSError("private handoff write detail")
                return len(data)

            def flush(self):
                if self.fault == "flush":
                    raise OSError("private handoff flush detail")

        for fault in ("write", "flush"):
            with self.subTest(handoff=fault), synthetic() as fixture:
                with redirect_stdout(BrokenHandoff(fault)), redirect_stderr(io.StringIO()) as errors:
                    result = subject.main(["--cc", str(fixture.cc), "--output", str(fixture.output)])
                self.assertEqual(result, 1)
                self.assertEqual(errors.getvalue(), "owned_live_probe_handoff_failed\n")
                for name in ("probe", "phase", "before.json", "receipt.json"):
                    self.assertTrue((fixture.output / name).is_file())
                self.assertEqual(fixture.receipt()["output"]["sha256"], subject._digest((fixture.output / "probe").read_bytes()))
                self.assertEqual((fixture.output / "phase").read_text().splitlines(), list(subject.PHASES))
                self.assertTrue(fixture.receipt()["provisioningSuccessRequiresZeroExit"])
                self.assertFalse((fixture.output / "failure.json").exists())


if __name__ == "__main__":
    unittest.main()
