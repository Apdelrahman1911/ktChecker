#!/usr/bin/env python3
"""Synthetic trusted-compiler-driver controls; no real child or compilation.

Discovered by the existing scripts/ci unittest runner on every CI host, or run
separately from native/helper controls, including without assertions:
  python3 -B scripts/ci/test_supervisor_builder_lifecycle_ci.py
  python3 -B -O scripts/ci/test_supervisor_builder_lifecycle_ci.py

These controls do not qualify constructor preemption, compiler descendants,
native-helper containment, real signal delivery, or OS cleanup latency. Custody
controls use fresh, bounded ASCII-only file fixtures, never a real compiler or
native binary. Only those self-owned synthetic fixtures are cleaned afterward;
no pre-existing retained output or unresolved real child is reclaimed.
"""
from __future__ import annotations

from contextlib import ExitStack, contextmanager
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


_BUILDER_SOURCE = Path(__file__).resolve().parents[2] / "modules/provider-runtime/native/build_supervisor.py"
_BUILDER_MODULE_NAME = "_ktchecker_v1_builder_lifecycle_ci_subject"


def _load_builder():
    # Exact source file, not a sys.path fallback or a cached similarly named
    # module. The subject needs no package-relative imports or sys.modules entry.
    spec = importlib.util.spec_from_file_location(_BUILDER_MODULE_NAME, _BUILDER_SOURCE)
    if spec is None or spec.loader is None:
        raise ImportError("trusted builder source loader unavailable")
    subject = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(subject)
    return subject


builder = _load_builder()


class SyntheticInterruption(BaseException):
    """Exercise ownership even when ordinary Exception handling is bypassed."""


class SyntheticStream:
    def __init__(self, harness):
        self.harness = harness

    def close(self):
        self.harness.step("stdout.close")


class SyntheticProcess:
    def __init__(self, harness):
        self.harness = harness
        self.returncode = None
        self.killed = False

    @property
    def stdout(self):
        self.harness.step("stdout.access")
        return self.harness.stream if self.harness.has_stdout else None

    def poll(self):
        self.harness.step("poll")
        return self.returncode

    def kill(self):
        self.harness.step("kill")
        self.killed = True

    def wait(self, timeout):
        self.harness.wait_timeouts.append(timeout)
        self.harness.step("wait")
        if self.harness.wait_results:
            result = self.harness.wait_results.pop(0)
        else:
            result = -9 if self.killed else self.harness.exit_code
        self.returncode = result
        return result


class SyntheticKey:
    fd = 73


class SyntheticSelector:
    def __init__(self, harness):
        self.harness = harness
        self.registered = False

    def register(self, stream, events):
        if stream is not self.harness.stream or events != builder.selectors.EVENT_READ:
            raise AssertionError("unexpected synthetic registration")
        self.harness.step("register")
        self.registered = True

    def get_map(self):
        self.harness.step("get_map")
        return {SyntheticKey.fd: None} if self.registered else {}

    def select(self, timeout):
        if not 0 < timeout <= 0.1:
            raise AssertionError("unbounded synthetic select")
        self.harness.step("select")
        if self.harness.readiness and not self.harness.readiness.pop(0):
            return []
        return [(SyntheticKey(), builder.selectors.EVENT_READ)]

    def unregister(self, fd):
        if fd != SyntheticKey.fd:
            raise AssertionError("unexpected synthetic descriptor")
        self.harness.step("unregister")
        self.registered = False

    def close(self):
        self.harness.step("selector.close")


class Harness:
    def __init__(self):
        self.clock = 100.0
        self.events = []
        self.faults = {}
        self.delays = {}
        self.chunks = [b"ok", b""]
        self.readiness = []
        self.wait_timeouts = []
        self.wait_results = []
        self.exit_code = 0
        self.has_stdout = True
        self.launch_arguments = None
        self.stream = SyntheticStream(self)
        self.process = SyntheticProcess(self)
        self.selector = SyntheticSelector(self)

    @staticmethod
    def take(mapping, name, default):
        value = mapping.get(name, default)
        return (value.pop(0) if value else default) if isinstance(value, list) else value

    def step(self, name):
        if len(self.events) >= 1024:
            raise AssertionError("synthetic operation bound exceeded")
        self.events.append(name)
        self.clock += self.take(self.delays, name, 0)
        fault = self.take(self.faults, name, None)
        if fault is not None:
            raise fault

    def monotonic(self):
        self.step("clock")
        return self.clock

    def launch(self, *args, **kwargs):
        self.launch_arguments = (args, kwargs)
        self.step("launch")
        return self.process

    def open_selector(self):
        self.step("selector.open")
        return self.selector

    def read(self, fd, maximum):
        if fd != SyntheticKey.fd or maximum != 8192 or not self.chunks:
            raise AssertionError("unexpected synthetic read")
        self.step("read")
        chunk = self.chunks.pop(0)
        if len(chunk) > maximum:
            raise AssertionError("synthetic read exceeds its request")
        return chunk

    @staticmethod
    def forbidden(*_args, **_kwargs):
        raise AssertionError("real process or unowned signal/observation attempted")

    def run(self, timeout=10.0, argv=None):
        return builder.bounded_command(["/trusted/compiler", "--version"] if argv is None else argv, timeout)


@contextmanager
def synthetic():
    harness = Harness()
    # No test invocation can reach the real constructor/read/selector/clock.
    # Low-level process discovery/signals are also forbidden, not simulated.
    with patch.object(builder.subprocess, "Popen", side_effect=harness.launch), \
            patch.object(builder.selectors, "DefaultSelector", side_effect=harness.open_selector), \
            patch.object(builder.os, "read", side_effect=harness.read), \
            patch.object(builder.time, "monotonic", side_effect=harness.monotonic), \
            patch.object(builder.os, "kill", side_effect=harness.forbidden), \
            patch.object(builder.os, "killpg", side_effect=harness.forbidden, create=True), \
            patch.object(builder.os, "waitpid", side_effect=harness.forbidden, create=True):
        yield harness


class BuildCustodyHarness:
    """Real private directory entries, but wholly synthetic compiler behavior."""

    SOURCE_SHA = "sha256:" + "a" * 64
    BUILDER_SHA = "sha256:" + "b" * 64
    COMPILER_SHA = "sha256:" + "c" * 64
    VERSION = b"synthetic compiler PRIVATE-BANNER-TOKEN\n"

    def __init__(self, directory):
        self.output = directory / "private-output"
        # A space makes this a private path even under an unusual public TMPDIR.
        self.cc = directory / "private synthetic compiler.txt"
        self.cc.write_bytes(b"synthetic compiler identity only; never executed\n")
        self.host = {"os": "linux", "architecture": "x86_64", "target": "linux-x86_64"}
        self.payload = b"synthetic supervisor payload only; never executed\n"
        self.events = []
        self.phases = []
        self.calls = []
        self.publication_writes = []
        self.housekeeping = []
        self.digest_counts = {}
        self.on_phase = None
        self.on_command = None
        self.on_write = None
        self.on_digest = None
        self.forbid_housekeeping = False
        self.housekeeping_faults = {}
        self.raw_phase = builder._write_attempt_phase
        self.raw_write = Path.write_bytes
        self.raw_unlink = Path.unlink
        self.raw_rmdir = Path.rmdir
        self.raw_rmtree = builder.shutil.rmtree

    @property
    def root(self):
        return self.output.absolute() / builder.PREFIX

    @property
    def attempt(self):
        return self.root.with_name(f".{self.root.name}.compiler-attempt")

    @property
    def candidate(self):
        return self.attempt / "supervisor"

    @staticmethod
    def phase_bytes(phase):
        return f"ktchecker-native-compiler-attempt/1\n{phase}\n".encode("ascii")

    def record(self, event):
        if len(self.events) >= 1024:
            raise AssertionError("synthetic custody operation bound exceeded")
        self.events.append(event)

    def require_guard(self):
        if not self.attempt.is_dir():
            raise AssertionError("synthetic operation occurred without custody guard")

    def write_phase(self, attempt, phase):
        self.record("phase:" + phase)
        self.require_guard()
        if attempt != self.attempt:
            raise AssertionError("unexpected synthetic custody path")
        if self.on_phase is not None:
            self.on_phase(attempt, phase)
        self.raw_phase(attempt, phase)
        self.phases.append(phase)

    def write_bytes(self, path, data):
        self.record("write:" + path.name)
        if not isinstance(data, bytes) or len(data) > 65_536:
            raise AssertionError("synthetic custody file bound exceeded")
        if path in (self.root / "build-provenance.json", self.root / "manifest"):
            self.require_guard()
            self.publication_writes.append((path.name, (self.attempt / "phase").read_bytes()))
        if self.on_write is not None:
            result = self.on_write(path, data)
            if result is not None:
                return result
        return self.raw_write(path, data)

    def command(self, argv, timeout):
        self.require_guard()
        if argv == [str(self.cc), "--version"]:
            kind, pending, expected_timeout = "version", "version_probe_pending", 10
        else:
            kind, pending, expected_timeout = "compile", "compile_pending", 60
            if (len(argv) != len(builder.FLAGS) + 4 or argv[0] != str(self.cc) or
                    argv[1:-3] != list(builder.FLAGS) or Path(argv[-3]).suffix != ".c" or
                    argv[-2:] != ["-o", str(self.candidate)]):
                raise AssertionError("unexpected synthetic compile arguments")
        if timeout != expected_timeout or (self.attempt / "phase").read_bytes() != self.phase_bytes(pending):
            raise AssertionError("compiler call preceded its pending custody record")
        self.record("command:" + kind)
        self.calls.append(kind)
        if kind == "compile":
            self.candidate.write_bytes(self.payload)
        if self.on_command is not None:
            self.on_command(kind)
        return self.VERSION if kind == "version" else b""

    def file_digest(self, path, maximum):
        self.require_guard()
        self.record("digest:" + path.name)
        self.digest_counts[path] = self.digest_counts.get(path, 0) + 1
        if self.on_digest is not None:
            result = self.on_digest(path, maximum)
            if result is not None:
                return result
        if path == self.candidate:
            return builder.digest(self.payload)
        if path == self.cc.resolve():
            return self.COMPILER_SHA
        if path == Path(builder.__file__).resolve():
            return self.BUILDER_SHA
        if path.parent == Path(builder.__file__).resolve().parent and path.suffix == ".c":
            return self.SOURCE_SHA
        raise AssertionError("unexpected synthetic digest input")

    def access(self, path, mode):
        if path != self.cc or mode != builder.os.X_OK:
            raise AssertionError("unexpected synthetic access check")
        return True

    def housekeeping_step(self, operation, path):
        self.record(operation)
        self.housekeeping.append((operation, path))
        if self.forbid_housekeeping:
            raise AssertionError("exception-path housekeeping attempted")
        fault = self.housekeeping_faults.get(operation)
        if fault is not None:
            raise fault

    def unlink(self, path, *args, **kwargs):
        self.housekeeping_step("unlink", path)
        return self.raw_unlink(path, *args, **kwargs)

    def rmdir(self, path, *args, **kwargs):
        self.housekeeping_step("rmdir", path)
        return self.raw_rmdir(path, *args, **kwargs)

    def rmtree(self, path, *args, **kwargs):
        self.housekeeping_step("rmtree", path)
        return self.raw_rmtree(path, *args, **kwargs)

    def seed_stale_output(self):
        self.root.mkdir(parents=True)
        sentinel = self.root / "previous-output.txt"
        sentinel.write_bytes(b"previous synthetic output\n")
        return sentinel

    def build(self, *, host=None):
        return builder.build(self.output, self.cc, host=self.host if host is None else host)


@contextmanager
def synthetic_build():
    # Constructor/compiler and low-level process operations are forbidden. This
    # fresh fixture has no real process custody, so its own cleanup is safe after
    # all subject patches are removed, even for simulated retained attempts.
    with tempfile.TemporaryDirectory(prefix="ktc-builder-custody-synthetic-") as temporary:
        harness = BuildCustodyHarness(Path(temporary))

        def write_bytes(path, data):
            return harness.write_bytes(path, data)

        def unlink(path, *args, **kwargs):
            return harness.unlink(path, *args, **kwargs)

        def rmdir(path, *args, **kwargs):
            return harness.rmdir(path, *args, **kwargs)

        with ExitStack() as patches:
            patches.enter_context(patch.object(builder, "bounded_command", side_effect=harness.command))
            patches.enter_context(patch.object(builder, "file_digest", side_effect=harness.file_digest))
            patches.enter_context(patch.object(builder, "_write_attempt_phase", side_effect=harness.write_phase))
            patches.enter_context(patch.object(builder.os, "access", side_effect=harness.access))
            patches.enter_context(patch.object(builder.subprocess, "Popen", side_effect=Harness.forbidden))
            patches.enter_context(patch.object(builder.os, "kill", side_effect=Harness.forbidden))
            patches.enter_context(patch.object(builder.os, "killpg", side_effect=Harness.forbidden, create=True))
            patches.enter_context(patch.object(builder.os, "waitpid", side_effect=Harness.forbidden, create=True))
            patches.enter_context(patch.object(Path, "write_bytes", new=write_bytes))
            patches.enter_context(patch.object(Path, "unlink", new=unlink))
            patches.enter_context(patch.object(Path, "rmdir", new=rmdir))
            patches.enter_context(patch.object(builder.shutil, "rmtree", side_effect=harness.rmtree))
            yield harness


class BuilderImportIsolationTest(unittest.TestCase):
    def test_exact_source_import_does_not_replace_registry_or_search_path(self):
        old_path = list(sys.path)
        unrelated = object()
        with patch.dict(sys.modules, {_BUILDER_MODULE_NAME: unrelated}):
            isolated = _load_builder()
            self.assertIs(unrelated, sys.modules[_BUILDER_MODULE_NAME])
        self.assertEqual(old_path, sys.path)
        self.assertIsNot(builder, isolated)
        self.assertEqual(_BUILDER_MODULE_NAME, isolated.__name__)
        self.assertEqual(_BUILDER_SOURCE, Path(isolated.__file__).resolve())

    def test_missing_import_spec_fails_closed_without_path_fallback(self):
        with patch.object(importlib.util, "spec_from_file_location", return_value=None):
            with self.assertRaisesRegex(ImportError, "^trusted builder source loader unavailable$"):
                _load_builder()


class BoundedCompilerCommandTest(unittest.TestCase):
    def assert_cleanup_attempts(self, harness, *, kill, selector=True, stdout=True):
        self.assertIn("poll", harness.events)
        self.assertEqual(kill, "kill" in harness.events)
        self.assertTrue(harness.wait_timeouts)
        self.assertEqual(2, harness.wait_timeouts[-1])
        last_wait = max(index for index, event in enumerate(harness.events) if event == "wait")
        self.assertLess(harness.events.index("poll"), last_wait)
        if kill:
            self.assertLess(harness.events.index("poll"), harness.events.index("kill"))
            self.assertLess(harness.events.index("kill"), last_wait)
        self.assertEqual(selector, "selector.close" in harness.events)
        self.assertEqual(stdout, "stdout.close" in harness.events)
        if selector:
            self.assertLess(last_wait, harness.events.index("selector.close"))
        if stdout:
            self.assertLess(last_wait, harness.events.index("stdout.close"))
        if selector and stdout:
            self.assertLess(harness.events.index("selector.close"), harness.events.index("stdout.close"))

    def test_success_preserves_exact_argv_environment_and_bytes_without_shell(self):
        with synthetic() as harness:
            argv = ["/trusted/compiler", "", "space value", "$(not-expanded)"]
            harness.chunks = [b"one", b"\x00two", b""]
            self.assertEqual(b"one\x00two", harness.run(argv=argv))
            self.assertEqual(((argv,), {
                "stdin": builder.subprocess.DEVNULL, "stdout": builder.subprocess.PIPE,
                "stderr": builder.subprocess.STDOUT, "env": builder.COMPILER_ENVIRONMENT,
            }), harness.launch_arguments)
            self.assertEqual([10.0, 2], harness.wait_timeouts)
            self.assertEqual(0, harness.process.returncode)
            self.assert_cleanup_attempts(harness, kill=False)

    def test_invalid_timeouts_never_call_constructor(self):
        for timeout in (0.0, -1.0, float("nan"), float("inf"), -float("inf")):
            with self.subTest(timeout=timeout), synthetic() as harness:
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_compiler_timeout$"):
                    harness.run(timeout)
                self.assertNotIn("launch", harness.events)
                self.assertNotIn("poll", harness.events)

    def test_nonfinite_computed_deadline_never_calls_constructor(self):
        with synthetic() as harness:
            harness.clock = 1.7e308
            with self.assertRaisesRegex(builder.BuildProblem, "compiler_timeout$"):
                harness.run(1.7e308)
            self.assertNotIn("launch", harness.events)

    def test_prelaunch_deadline_expiry_never_calls_constructor(self):
        with synthetic() as harness:
            harness.delays["clock"] = [0, 10]
            with self.assertRaisesRegex(builder.BuildProblem, "compiler_timeout$"):
                harness.run()
            self.assertNotIn("launch", harness.events)

    def test_constructor_exception_is_unknown_not_proof_of_no_child(self):
        for fault in (OSError("synthetic launch failure"), SyntheticInterruption("synthetic interruption")):
            with self.subTest(kind=type(fault).__name__), synthetic() as harness:
                harness.faults["launch"] = fault
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_compiler_launch_unknown$"):
                    harness.run()
                self.assertEqual(1, harness.events.count("launch"))
                # No returned handle exists: no retry or PID-based recovery.
                self.assertNotIn("poll", harness.events)
                self.assertNotIn("kill", harness.events)
                self.assertNotIn("wait", harness.events)

    def test_post_return_baseexception_keeps_obtained_handle(self):
        with synthetic() as harness:
            fault = SyntheticInterruption("synthetic post-return interruption")
            harness.faults["stdout.access"] = [fault, None]
            with self.assertRaises(SyntheticInterruption) as raised:
                harness.run()
            self.assertIs(fault, raised.exception)
            self.assert_cleanup_attempts(harness, kill=True, selector=False)

    def test_missing_stdout_is_not_assert_dependent_and_retires_child(self):
        with synthetic() as harness:
            harness.has_stdout = False
            with self.assertRaisesRegex(builder.BuildProblem, "compiler_failed$"):
                harness.run()
            self.assert_cleanup_attempts(harness, kill=True, selector=False, stdout=False)

    def test_output_allocation_failure_retires_obtained_child(self):
        with synthetic() as harness:
            fault = MemoryError("synthetic allocation failure")
            with patch.object(builder, "bytearray", side_effect=fault, create=True):
                with self.assertRaises(MemoryError) as raised:
                    harness.run()
            self.assertIs(fault, raised.exception)
            self.assert_cleanup_attempts(harness, kill=True, selector=False)

    def test_post_return_clock_failure_retires_obtained_child(self):
        with synthetic() as harness:
            fault = SyntheticInterruption("synthetic clock failure")
            harness.faults["clock"] = [None, None, fault]
            with self.assertRaises(SyntheticInterruption) as raised:
                harness.run()
            self.assertIs(fault, raised.exception)
            self.assert_cleanup_attempts(harness, kill=True, selector=False)

    def test_selector_allocation_failure_retires_obtained_child(self):
        for fault in (MemoryError("synthetic selector failure"), SyntheticInterruption("synthetic selector interruption")):
            with self.subTest(kind=type(fault).__name__), synthetic() as harness:
                harness.faults["selector.open"] = fault
                with self.assertRaises(type(fault)) as raised:
                    harness.run()
                self.assertIs(fault, raised.exception)
                self.assert_cleanup_attempts(harness, kill=True, selector=False)

    def test_register_and_output_path_failures_attempt_all_cleanup(self):
        for site in ("register", "get_map", "select", "read", "unregister"):
            for kind in (OSError, SyntheticInterruption):
                with self.subTest(site=site, kind=kind.__name__), synthetic() as harness:
                    fault = kind("synthetic output-path failure")
                    harness.faults[site] = fault
                    with self.assertRaises(kind) as raised:
                        harness.run()
                    self.assertIs(fault, raised.exception)
                    self.assert_cleanup_attempts(harness, kill=True)

    def test_exact_output_limit_is_accepted_and_one_more_byte_is_rejected(self):
        for extra in (0, 1):
            with self.subTest(extra=extra), synthetic() as harness:
                data = b"x" * (builder.MAX_COMPILER_OUTPUT + extra)
                harness.chunks = [data[offset:offset + 8192] for offset in range(0, len(data), 8192)] + [b""]
                if extra:
                    with self.assertRaisesRegex(builder.BuildProblem, "compiler_output_limit$"):
                        harness.run()
                else:
                    self.assertEqual(data, harness.run())
                self.assert_cleanup_attempts(harness, kill=bool(extra))

    def test_nonzero_exit_is_not_success_and_is_not_signaled_after_reap(self):
        with synthetic() as harness:
            harness.exit_code = 7
            with self.assertRaisesRegex(builder.BuildProblem, "compiler_failed$"):
                harness.run()
            self.assert_cleanup_attempts(harness, kill=False)

    def test_normal_wait_timeout_still_kills_and_reaps_owned_child(self):
        with synthetic() as harness:
            harness.faults["wait"] = [builder.subprocess.TimeoutExpired("synthetic", 10), None]
            with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_compiler_timeout$"):
                harness.run()
            self.assert_cleanup_attempts(harness, kill=True)

    def test_normal_wait_interruption_still_kills_and_reaps_owned_child(self):
        with synthetic() as harness:
            fault = SyntheticInterruption("synthetic wait interruption")
            harness.faults["wait"] = [fault, None]
            with self.assertRaises(SyntheticInterruption) as raised:
                harness.run()
            self.assertIs(fault, raised.exception)
            self.assert_cleanup_attempts(harness, kill=True)

    def test_result_allocation_failure_still_closes_after_reap(self):
        with synthetic() as harness:
            fault = MemoryError("synthetic result allocation failure")
            with patch.object(builder, "bytes", side_effect=fault, create=True):
                with self.assertRaises(MemoryError) as raised:
                    harness.run()
            self.assertIs(fault, raised.exception)
            self.assert_cleanup_attempts(harness, kill=False)

    def test_exact_and_late_constructor_return_cannot_succeed(self):
        for elapsed in (10.0, 10.01):
            with self.subTest(elapsed=elapsed), synthetic() as harness:
                harness.delays["launch"] = elapsed
                with self.assertRaisesRegex(builder.BuildProblem, "compiler_timeout$"):
                    harness.run()
                self.assertNotIn("selector.open", harness.events)
                self.assert_cleanup_attempts(harness, kill=True, selector=False)

    def test_exact_and_late_readiness_and_reads_cannot_succeed(self):
        for site in ("select", "read"):
            for elapsed in (10.0, 10.01):
                with self.subTest(site=site, elapsed=elapsed), synthetic() as harness:
                    harness.delays[site] = elapsed
                    with self.assertRaisesRegex(builder.BuildProblem, "compiler_timeout$"):
                        harness.run()
                    if site == "select":
                        self.assertNotIn("read", harness.events)
                    self.assert_cleanup_attempts(harness, kill=True)

    def test_no_readiness_at_exact_or_late_deadline_cannot_succeed(self):
        for elapsed in (10.0, 10.01):
            with self.subTest(elapsed=elapsed), synthetic() as harness:
                harness.readiness = [False]
                harness.delays["select"] = elapsed
                with self.assertRaisesRegex(builder.BuildProblem, "compiler_timeout$"):
                    harness.run()
                self.assertNotIn("read", harness.events)
                self.assert_cleanup_attempts(harness, kill=True)

    def test_transient_no_readiness_before_deadline_can_complete(self):
        with synthetic() as harness:
            harness.readiness = [False]
            harness.chunks = [b""]
            self.assertEqual(b"", harness.run())
            self.assertEqual(2, harness.events.count("select"))
            self.assert_cleanup_attempts(harness, kill=False)

    def test_exact_and_late_successful_wait_cannot_succeed(self):
        for elapsed in (10.0, 10.01):
            with self.subTest(elapsed=elapsed), synthetic() as harness:
                harness.delays["wait"] = [elapsed, 0]
                with self.assertRaisesRegex(builder.BuildProblem, "compiler_timeout$"):
                    harness.run()
                self.assertEqual(0, harness.process.returncode)
                self.assert_cleanup_attempts(harness, kill=False)

    def test_exact_and_late_cleanup_cannot_turn_into_success(self):
        for site in ("wait", "selector.close", "stdout.close"):
            for elapsed in (10.0, 10.01):
                with self.subTest(site=site, elapsed=elapsed), synthetic() as harness:
                    harness.delays[site] = [0, elapsed] if site == "wait" else elapsed
                    with self.assertRaisesRegex(builder.BuildProblem, "compiler_timeout$"):
                        harness.run()
                    self.assert_cleanup_attempts(harness, kill=False)

    def test_poll_and_kill_failures_do_not_skip_wait_or_closes(self):
        for site in ("poll", "kill"):
            for kind in (OSError, SyntheticInterruption):
                with self.subTest(site=site, kind=kind.__name__), synthetic() as harness:
                    harness.faults["register"] = OSError("synthetic setup failure")
                    fault = kind("synthetic retirement failure")
                    harness.faults[site] = fault
                    with self.assertRaises(kind) as raised:
                        harness.run()
                    self.assertIs(fault, raised.exception)
                    self.assert_cleanup_attempts(harness, kill=(site == "kill"))

    def test_cleanup_wait_timeout_is_unknown_not_swallowed(self):
        with synthetic() as harness:
            harness.faults["register"] = OSError("synthetic setup failure")
            harness.faults["wait"] = builder.subprocess.TimeoutExpired("synthetic", 2)
            with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_compiler_cleanup_unknown$"):
                harness.run()
            self.assertIsNone(harness.process.returncode)
            self.assert_cleanup_attempts(harness, kill=True)

    def test_cleanup_wait_without_observation_is_unknown_not_success(self):
        with synthetic() as harness:
            harness.wait_results = [0, None]
            with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_compiler_cleanup_unknown$"):
                harness.run()
            self.assert_cleanup_attempts(harness, kill=False)

    def test_cleanup_wait_failure_does_not_skip_closes_or_return_success(self):
        for fault in (OSError("synthetic wait failure"), SyntheticInterruption("synthetic cleanup interruption")):
            with self.subTest(kind=type(fault).__name__), synthetic() as harness:
                harness.faults["wait"] = [None, fault]
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_compiler_cleanup_unknown$"):
                    harness.run()
                self.assert_cleanup_attempts(harness, kill=False)

    def test_close_failures_cannot_skip_other_close_or_return_success(self):
        for site in ("selector.close", "stdout.close"):
            for kind in (OSError, SyntheticInterruption):
                with self.subTest(site=site, kind=kind.__name__), synthetic() as harness:
                    fault = kind("synthetic close failure")
                    harness.faults[site] = fault
                    with self.assertRaises(kind) as raised:
                        harness.run()
                    self.assertIs(fault, raised.exception)
                    self.assert_cleanup_attempts(harness, kill=False)

    def test_multiple_cleanup_failures_still_attempt_every_independent_stage(self):
        for wait_fault in (builder.subprocess.TimeoutExpired("synthetic", 2),
                           OSError("synthetic wait failure"), SyntheticInterruption("synthetic wait interruption"), None):
            with self.subTest(kind=type(wait_fault).__name__), synthetic() as harness:
                harness.faults["register"] = OSError("synthetic setup failure")
                harness.faults["poll"] = OSError("synthetic poll failure")
                if wait_fault is None:
                    harness.wait_results = [None]
                else:
                    harness.faults["wait"] = wait_fault
                harness.faults["selector.close"] = OSError("synthetic selector close failure")
                harness.faults["stdout.close"] = SyntheticInterruption("synthetic stream close failure")
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_compiler_cleanup_unknown$"):
                    harness.run()
                self.assert_cleanup_attempts(harness, kill=False)


class CompilerBuildCustodyTest(unittest.TestCase):
    PHASES = ["preflight", "version_probe_pending", "version_probe_direct_return_observed",
              "compile_pending", "compile_direct_return_observed"]

    def assert_phase(self, harness, phase):
        self.assertTrue(harness.attempt.is_dir())
        recorded = (harness.attempt / "phase").read_bytes()
        self.assertEqual(harness.phase_bytes(phase), recorded)
        self.assertLessEqual(len(recorded), 128)

    def assert_no_receipt(self, harness):
        self.assertFalse((harness.root / "build-provenance.json").exists())
        self.assertFalse((harness.root / "manifest").exists())

    def assert_reuse_refused(self, harness, *, host=None):
        history = list(harness.events)
        with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_attempt_retained$"):
            harness.build(host=host)
        # No validation, state write, compiler call, clear, or release follows
        # refusal. Only nondestructive parent setup and exclusive mkdir precede it.
        self.assertEqual(history, harness.events)

    def test_success_holds_guard_through_publication_then_allows_rebuild(self):
        with synthetic_build() as harness:
            stale = harness.seed_stale_output()
            receipt = harness.build()
            self.assertFalse(stale.exists())
            self.assertEqual(self.PHASES, harness.phases)
            self.assertEqual(["version", "compile"], harness.calls)
            self.assertEqual([
                ("build-provenance.json", harness.phase_bytes("compile_direct_return_observed")),
                ("manifest", harness.phase_bytes("compile_direct_return_observed")),
            ], harness.publication_writes)
            self.assertEqual([
                ("rmtree", harness.root), ("unlink", harness.attempt / "phase"), ("rmdir", harness.attempt),
            ], harness.housekeeping)
            self.assertFalse(harness.attempt.exists())
            installed = harness.root / harness.host["target"] / "supervisor"
            self.assertEqual(harness.payload, installed.read_bytes())
            self.assertEqual("built", receipt["status"])
            self.assertIsNone(receipt["reason"])
            self.assertEqual(harness.SOURCE_SHA, receipt["inputs"]["source"]["sha256"])
            self.assertEqual(harness.BUILDER_SHA, receipt["inputs"]["builder"]["sha256"])
            self.assertEqual(harness.COMPILER_SHA, receipt["compiler"]["driverSha256"])
            self.assertEqual("<private-compiler>", receipt["compiler"]["selectedPath"])
            self.assertEqual("<private-compiler>", receipt["compiler"]["resolvedPath"])
            self.assertEqual("<redacted>", receipt["compiler"]["versionFirstLine"])
            self.assertEqual(builder.digest(harness.VERSION), receipt["compiler"]["versionOutputSha256"])
            self.assertEqual(["<compiler>", *builder.FLAGS, "<source>", "-o", "<output>"],
                             receipt["invocation"]["argv"])
            self.assertFalse(receipt["invocation"]["privateArgvRetained"])
            self.assertEqual({"privatePathsRetained": False, "rawCompilerTranscriptRetained": False,
                              "versionFirstLineRedacted": True}, receipt["privacy"])
            encoded = (harness.root / "build-provenance.json").read_bytes()
            self.assertEqual(receipt, builder.json.loads(encoded))
            self.assertLessEqual(len(encoded), builder.MAX_RECEIPT)
            for private_value in (str(harness.output), str(harness.cc), str(harness.attempt), "PRIVATE-BANNER-TOKEN"):
                self.assertNotIn(private_value, encoded.decode("ascii"))
            expected_manifest = (f"{builder.PROTOCOL}\n{harness.host['target']}\n{harness.SOURCE_SHA}\n"
                                 f"{builder.digest(harness.payload)}\n").encode("ascii")
            self.assertEqual(expected_manifest, (harness.root / "manifest").read_bytes())
            self.assertEqual(receipt, harness.build())
            self.assertEqual(self.PHASES * 2, harness.phases)
            self.assertEqual(["version", "compile"] * 2, harness.calls)
            self.assertFalse(harness.attempt.exists())

    def test_unsupported_host_also_guards_clear_and_releases_only_after_writes(self):
        with synthetic_build() as harness:
            stale = harness.seed_stale_output()
            unsupported = {"os": "windows", "architecture": "x86_64", "target": "windows-x86_64"}
            receipt = harness.build(host=unsupported)
            self.assertFalse(stale.exists())
            self.assertEqual([], harness.calls)
            self.assertEqual(["preflight"], harness.phases)
            self.assertEqual([
                ("build-provenance.json", harness.phase_bytes("preflight")),
                ("manifest", harness.phase_bytes("preflight")),
            ], harness.publication_writes)
            self.assertEqual("unavailable", receipt["status"])
            self.assertEqual("unsupported_host", receipt["reason"])
            self.assertIsNone(receipt["compiler"])
            self.assertIsNone(receipt["invocation"])
            self.assertIsNone(receipt["output"])
            self.assertFalse(harness.attempt.exists())

    def test_version_unknown_retains_pending_guard_without_cleanup_or_compile(self):
        for reason in ("launch_unknown", "cleanup_unknown"):
            with self.subTest(reason=reason), synthetic_build() as harness:
                stale = harness.seed_stale_output()
                fault = builder.BuildProblem("native_supervisor_compiler_" + reason)

                def fail(kind):
                    self.assertEqual("version", kind)
                    harness.forbid_housekeeping = True
                    raise fault

                harness.on_command = fail
                with self.assertRaises(builder.BuildProblem) as raised:
                    harness.build()
                self.assertIs(fault, raised.exception)
                self.assertFalse(stale.exists())
                self.assertEqual(["version"], harness.calls)
                self.assertEqual(self.PHASES[:2], harness.phases)
                self.assert_phase(harness, "version_probe_pending")
                self.assertFalse(harness.candidate.exists())
                self.assert_no_receipt(harness)
                self.assertEqual([("rmtree", harness.root)], harness.housekeeping)
                self.assert_reuse_refused(harness)

    def test_compile_unknown_preserves_candidate_and_original_reason(self):
        for reason in ("launch_unknown", "cleanup_unknown"):
            with self.subTest(reason=reason), synthetic_build() as harness:
                harness.payload = b"partial synthetic compiler output\n"
                fault = builder.BuildProblem("native_supervisor_compiler_" + reason)

                def fail(kind):
                    if kind == "compile":
                        harness.forbid_housekeeping = True
                        raise fault

                harness.on_command = fail
                with self.assertRaises(builder.BuildProblem) as raised:
                    harness.build()
                self.assertIs(fault, raised.exception)
                self.assertEqual(["version", "compile"], harness.calls)
                self.assertEqual(self.PHASES[:4], harness.phases)
                self.assert_phase(harness, "compile_pending")
                self.assertEqual(harness.payload, harness.candidate.read_bytes())
                self.assertFalse((harness.root / harness.host["target"]).exists())
                self.assert_no_receipt(harness)
                self.assertEqual([], harness.housekeeping)
                self.assert_reuse_refused(harness)
                self.assertEqual(harness.payload, harness.candidate.read_bytes())

    def test_preflight_validation_failures_conservatively_retain_without_launch(self):
        for kind in (OSError, MemoryError, SyntheticInterruption):
            with self.subTest(kind=kind.__name__), synthetic_build() as harness:
                fault = kind("synthetic input failure")

                def fail(_path, _maximum):
                    harness.forbid_housekeeping = True
                    raise fault

                harness.on_digest = fail
                with self.assertRaises(kind) as raised:
                    harness.build()
                self.assertIs(fault, raised.exception)
                self.assert_phase(harness, "preflight")
                self.assertEqual([], harness.calls)
                self.assertEqual([], harness.housekeeping)
                self.assert_no_receipt(harness)
                self.assert_reuse_refused(harness)

    def test_post_version_ordinary_and_base_exceptions_do_not_release_guard(self):
        for kind in (OSError, MemoryError, SyntheticInterruption):
            with self.subTest(kind=kind.__name__), synthetic_build() as harness:
                fault = kind("synthetic post-version failure")

                def fail(_output):
                    harness.forbid_housekeeping = True
                    raise fault

                with patch.object(builder, "public_version_line", side_effect=fail):
                    with self.assertRaises(kind) as raised:
                        harness.build()
                self.assertIs(fault, raised.exception)
                self.assert_phase(harness, "version_probe_direct_return_observed")
                self.assertEqual(["version"], harness.calls)
                self.assertEqual([], harness.housekeeping)
                self.assert_no_receipt(harness)
                self.assert_reuse_refused(harness)

    def test_existing_directory_malformed_state_or_file_refuses_before_clear(self):
        for state in ("missing", "malformed", "file"):
            with self.subTest(state=state), synthetic_build() as harness:
                stale = harness.seed_stale_output()
                if state == "file":
                    harness.attempt.write_bytes(b"synthetic retained entry\n")
                else:
                    harness.attempt.mkdir()
                    if state == "malformed":
                        (harness.attempt / "phase").write_bytes(b"unrecognized synthetic state\n")
                harness.forbid_housekeeping = True
                # Existing state is never opened or parsed, including malformed
                # content. Ownership refusal depends only on exclusive mkdir.
                with patch.object(Path, "open", side_effect=Harness.forbidden):
                    self.assert_reuse_refused(harness)
                self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
                self.assertEqual([], harness.calls)
                self.assertEqual([], harness.phases)
                self.assertEqual([], harness.housekeeping)
                if state == "file":
                    self.assertEqual(b"synthetic retained entry\n", harness.attempt.read_bytes())
                elif state == "malformed":
                    self.assertEqual(b"unrecognized synthetic state\n", (harness.attempt / "phase").read_bytes())
                else:
                    self.assertFalse((harness.attempt / "phase").exists())

    def test_guard_acquisition_errors_never_clear_or_launch(self):
        for acquired in (False, True):
            for kind in (OSError, PermissionError):
                with self.subTest(acquired=acquired, kind=kind.__name__), synthetic_build() as harness:
                    stale = harness.seed_stale_output()
                    raw_mkdir = Path.mkdir

                    def mkdir(path, *args, **kwargs):
                        if path == harness.attempt:
                            if acquired:
                                raw_mkdir(path, *args, **kwargs)
                            raise kind("synthetic acquisition failure")
                        return raw_mkdir(path, *args, **kwargs)

                    harness.forbid_housekeeping = True
                    with patch.object(Path, "mkdir", new=mkdir):
                        with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_attempt_unavailable$"):
                            harness.build()
                    self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
                    self.assertEqual(acquired, harness.attempt.is_dir())
                    self.assertEqual([], harness.calls)
                    self.assertEqual([], harness.phases)
                    self.assertEqual([], harness.housekeeping)
                    if acquired:
                        self.assert_reuse_refused(harness)

    def test_parent_creation_failure_is_unavailable_without_touching_output(self):
        with synthetic_build() as harness:
            stale = harness.seed_stale_output()
            raw_mkdir = Path.mkdir

            def mkdir(path, *args, **kwargs):
                if path == harness.attempt.parent:
                    raise PermissionError("synthetic parent setup failure")
                return raw_mkdir(path, *args, **kwargs)

            harness.forbid_housekeeping = True
            with patch.object(Path, "mkdir", new=mkdir):
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_attempt_unavailable$"):
                    harness.build()
            self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
            self.assertFalse(harness.attempt.exists())
            self.assertEqual([], harness.calls)
            self.assertEqual([], harness.phases)
            self.assertEqual([], harness.housekeeping)

    def test_preflight_phase_failure_or_short_write_guards_unchanged_output(self):
        for mode in ("error", "short"):
            with self.subTest(mode=mode), synthetic_build() as harness:
                stale = harness.seed_stale_output()
                fault = OSError("synthetic phase write failure")

                def write(path, data):
                    if path == harness.attempt / "phase":
                        self.assertEqual(harness.phase_bytes("preflight"), data)
                        if mode == "error":
                            raise fault
                        harness.raw_write(path, b"p")
                        return 1
                    return None

                harness.on_write = write
                harness.forbid_housekeeping = True
                if mode == "error":
                    with self.assertRaises(OSError) as raised:
                        harness.build()
                    self.assertIs(fault, raised.exception)
                    self.assertFalse((harness.attempt / "phase").exists())
                else:
                    with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_attempt_state_failed$"):
                        harness.build()
                    self.assertEqual(b"p", (harness.attempt / "phase").read_bytes())
                self.assertTrue(harness.attempt.is_dir())
                self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
                self.assertEqual([], harness.calls)
                self.assertEqual([], harness.housekeeping)
                self.assert_reuse_refused(harness)

    def test_invalid_phase_is_rejected_without_persisting_caller_text(self):
        with synthetic_build() as harness:
            harness.attempt.mkdir(parents=True)
            with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_attempt_state_invalid$"):
                builder._write_attempt_phase(harness.attempt, "private-path-or-error-text")
            self.assertFalse((harness.attempt / "phase").exists())
            self.assertEqual([], harness.calls)
            self.assert_reuse_refused(harness)

    def test_direct_return_phase_write_failure_prevents_next_stage_and_retains(self):
        for phase, pending, calls in (
                ("version_probe_direct_return_observed", "version_probe_pending", ["version"]),
                ("compile_direct_return_observed", "compile_pending", ["version", "compile"])):
            with self.subTest(phase=phase), synthetic_build() as harness:
                fault = SyntheticInterruption("synthetic post-return state interruption")

                def write(path, data):
                    if path == harness.attempt / "phase" and data == harness.phase_bytes(phase):
                        harness.forbid_housekeeping = True
                        raise fault
                    return None

                harness.on_write = write
                with self.assertRaises(SyntheticInterruption) as raised:
                    harness.build()
                self.assertIs(fault, raised.exception)
                self.assert_phase(harness, pending)
                self.assertEqual(calls, harness.calls)
                self.assertEqual("compile" in calls, harness.candidate.exists())
                self.assertEqual([], harness.housekeeping)
                self.assert_no_receipt(harness)
                self.assert_reuse_refused(harness)

    def test_second_caller_at_acquisition_refuses_before_first_destructive_clear(self):
        with synthetic_build() as harness:
            stale = harness.seed_stale_output()
            interleavings = []

            def phase_hook(attempt, phase):
                if phase == "preflight":
                    self.assertTrue(attempt.is_dir())
                    self.assertFalse((attempt / "phase").exists())
                    self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
                    self.assert_reuse_refused(harness)
                    self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
                    self.assertEqual([], harness.housekeeping)
                    self.assertEqual([], harness.calls)
                    interleavings.append("refused-before-clear")

            harness.on_phase = phase_hook
            receipt = harness.build()
            self.assertEqual(["refused-before-clear"], interleavings)
            self.assertEqual("built", receipt["status"])
            self.assertFalse(stale.exists())
            self.assertEqual(["version", "compile"], harness.calls)
            self.assertFalse(harness.attempt.exists())

    def test_second_unsupported_caller_during_compile_preserves_first_candidate(self):
        with synthetic_build() as harness:
            interleavings = []
            unsupported = {"os": "unsupported", "architecture": "unsupported", "target": "unsupported"}

            def command_hook(kind):
                if kind == "compile":
                    self.assert_phase(harness, "compile_pending")
                    self.assertEqual(harness.payload, harness.candidate.read_bytes())
                    self.assert_reuse_refused(harness, host=unsupported)
                    self.assert_phase(harness, "compile_pending")
                    self.assertEqual(harness.payload, harness.candidate.read_bytes())
                    interleavings.append("refused-during-compile")

            harness.on_command = command_hook
            receipt = harness.build()
            self.assertEqual(["refused-during-compile"], interleavings)
            self.assertEqual("built", receipt["status"])
            self.assertEqual(["version", "compile"], harness.calls)
            self.assertFalse(harness.attempt.exists())

    def test_native_and_native_live_sibling_custody_are_isolated_both_directions(self):
        common = Path("dev/ktchecker/providers/runtime")
        for retained_name, other_name in (("native", "native-live"), ("native-live", "native")):
            with self.subTest(retained=retained_name), synthetic_build() as harness:
                # Parameterize the namespace under the same subject. Each real
                # builder's own declared PREFIX still needs source review.
                with patch.object(builder, "PREFIX", common / retained_name):
                    fault = builder.BuildProblem("native_supervisor_compiler_cleanup_unknown")

                    def fail(kind):
                        if kind == "compile":
                            raise fault

                    harness.on_command = fail
                    with self.assertRaises(builder.BuildProblem) as raised:
                        harness.build()
                    self.assertIs(fault, raised.exception)
                    self.assert_phase(harness, "compile_pending")
                    retained_attempt, retained_candidate = harness.attempt, harness.candidate
                    retained_sentinel = harness.root / "unresolved-owner-sentinel.txt"
                    retained_sentinel.write_bytes(b"synthetic unresolved owner\n")
                harness.on_command = None
                with patch.object(builder, "PREFIX", common / other_name):
                    self.assertNotEqual(retained_attempt, harness.attempt)
                    receipt = harness.build()
                    self.assertEqual("built", receipt["status"])
                    self.assertFalse(harness.attempt.exists())
                    self.assertTrue(retained_attempt.is_dir())
                    self.assertEqual(harness.phase_bytes("compile_pending"), (retained_attempt / "phase").read_bytes())
                    self.assertEqual(harness.payload, retained_candidate.read_bytes())
                    self.assertEqual(b"synthetic unresolved owner\n", retained_sentinel.read_bytes())
                with patch.object(builder, "PREFIX", common / retained_name):
                    self.assert_reuse_refused(harness)
                self.assertEqual(["version", "compile"] * 2, harness.calls)

    def test_binary_rejection_and_input_drift_retain_direct_return_phase(self):
        for site in ("binary", "source", "builder", "compiler"):
            with self.subTest(site=site), synthetic_build() as harness:
                def digest_hook(path, _maximum):
                    if site == "binary" and path == harness.candidate:
                        harness.forbid_housekeeping = True
                        raise builder.BuildProblem("native_supervisor_build_input_invalid")
                    selected = ((site == "source" and path.suffix == ".c") or
                                (site == "builder" and path == Path(builder.__file__).resolve()) or
                                (site == "compiler" and path == harness.cc.resolve()))
                    if selected and harness.digest_counts[path] == 2:
                        harness.forbid_housekeeping = True
                        return "sha256:" + "d" * 64
                    return None

                harness.on_digest = digest_hook
                reason = "input_invalid" if site == "binary" else "input_changed"
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_" + reason + "$"):
                    harness.build()
                self.assert_phase(harness, "compile_direct_return_observed")
                self.assertEqual(harness.payload, harness.candidate.read_bytes())
                self.assertEqual([], harness.housekeeping)
                self.assert_no_receipt(harness)
                self.assert_reuse_refused(harness)

    def test_receipt_limit_failure_retains_guard_after_output_installation(self):
        with synthetic_build() as harness:
            with patch.object(builder, "MAX_RECEIPT", 1):
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_receipt_limit$"):
                    harness.build()
            self.assert_phase(harness, "compile_direct_return_observed")
            self.assertFalse(harness.candidate.exists())
            self.assertTrue((harness.root / harness.host["target"] / "supervisor").is_file())
            self.assert_no_receipt(harness)
            self.assertEqual([], harness.housekeeping)
            self.assert_reuse_refused(harness)

    def test_publication_write_exceptions_preserve_guard_and_original_exception(self):
        for filename in ("build-provenance.json", "manifest"):
            for kind in (OSError, SyntheticInterruption):
                with self.subTest(filename=filename, kind=kind.__name__), synthetic_build() as harness:
                    fault = kind("synthetic publication write failure")

                    def write(path, _data):
                        if path == harness.root / filename:
                            harness.forbid_housekeeping = True
                            raise fault
                        return None

                    harness.on_write = write
                    with self.assertRaises(kind) as raised:
                        harness.build()
                    self.assertIs(fault, raised.exception)
                    self.assert_phase(harness, "compile_direct_return_observed")
                    self.assertEqual([], harness.housekeeping)
                    # Publication is not atomic: an installed output or earlier
                    # receipt may exist, but the build failed and remains guarded.
                    self.assertTrue((harness.root / harness.host["target"] / "supervisor").is_file())
                    self.assertEqual(filename == "manifest", (harness.root / "build-provenance.json").exists())
                    self.assertFalse((harness.root / "manifest").exists())
                    self.assert_reuse_refused(harness)

    def test_short_publication_write_never_releases_guard(self):
        for filename in ("build-provenance.json", "manifest"):
            with self.subTest(filename=filename), synthetic_build() as harness:
                def write(path, data):
                    if path == harness.root / filename:
                        harness.raw_write(path, data[:1])
                        harness.forbid_housekeeping = True
                        return 1
                    return None

                harness.on_write = write
                with self.assertRaisesRegex(builder.BuildProblem, "^native_supervisor_build_publication_failed$"):
                    harness.build()
                self.assert_phase(harness, "compile_direct_return_observed")
                self.assertEqual(1, (harness.root / filename).stat().st_size)
                self.assertEqual([], harness.housekeeping)
                if filename == "build-provenance.json":
                    self.assertFalse((harness.root / "manifest").exists())
                self.assert_reuse_refused(harness)

    def test_pre_removal_release_failures_preserve_existing_guard(self):
        for operation in ("unlink", "rmdir"):
            with self.subTest(operation=operation), synthetic_build() as harness:
                fault = PermissionError("synthetic release failure")
                # Inject before the primitive changes the directory. This does
                # not promise that a real ambiguous rmdir exception retains it.
                harness.housekeeping_faults[operation] = fault
                with self.assertRaises(PermissionError) as raised:
                    harness.build()
                self.assertIs(fault, raised.exception)
                self.assertTrue(harness.attempt.is_dir())
                self.assertEqual(operation == "unlink", (harness.attempt / "phase").exists())
                expected = [("unlink", harness.attempt / "phase")]
                if operation == "rmdir":
                    expected.append(("rmdir", harness.attempt))
                self.assertEqual(expected, harness.housekeeping)
                self.assertEqual(2, len(harness.publication_writes))
                self.assert_reuse_refused(harness)

    def test_unexpected_residue_prevents_release_without_recursive_attempt_cleanup(self):
        with synthetic_build() as harness:
            residue = harness.attempt / "unexpected-synthetic-residue.txt"

            def command_hook(kind):
                if kind == "compile":
                    residue.write_bytes(b"unexpected synthetic residue\n")

            harness.on_command = command_hook
            with self.assertRaises(OSError):
                harness.build()
            self.assertTrue(harness.attempt.is_dir())
            self.assertFalse((harness.attempt / "phase").exists())
            self.assertEqual(b"unexpected synthetic residue\n", residue.read_bytes())
            self.assertEqual([("unlink", harness.attempt / "phase"), ("rmdir", harness.attempt)], harness.housekeeping)
            self.assert_reuse_refused(harness)
            self.assertEqual(b"unexpected synthetic residue\n", residue.read_bytes())

    def test_resource_clear_failure_preserves_guard_and_original_exception(self):
        with synthetic_build() as harness:
            stale = harness.seed_stale_output()
            fault = OSError("synthetic resource clear failure")
            harness.housekeeping_faults["rmtree"] = fault
            with self.assertRaises(OSError) as raised:
                harness.build()
            self.assertIs(fault, raised.exception)
            self.assert_phase(harness, "preflight")
            self.assertEqual(b"previous synthetic output\n", stale.read_bytes())
            self.assertEqual([], harness.calls)
            self.assertEqual([("rmtree", harness.root)], harness.housekeeping)
            self.assert_reuse_refused(harness)


if __name__ == "__main__":
    unittest.main()
