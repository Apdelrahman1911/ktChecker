#!/usr/bin/env python3
"""Tiny C/Python synthetic controls, not provider or cross-platform qualification.

Run with an explicit existing compiler (no downloads):
  python3 native/test_supervisor.py --cc /usr/bin/cc
Linux additionally requires strace for the real wait/signal ordering control.
All temporary binaries and syscall traces are deleted, not public transcripts.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import selectors
import signal
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import build_supervisor as builder


CC = Path("/usr/bin/cc")
F_EXIT, F_REAPED, F_QUIESCENT, F_GROUP, F_EXEC = 1, 2, 4, 8, 16
F_CANCEL, F_TIMEOUT, F_OUTPUT, F_PARENT, F_IO = 32, 64, 128, 256, 512
F_EXEC_FAILED, F_PROTOCOL, F_OWNERSHIP, F_SHUTDOWN = 1024, 2048, 4096, 8192


def read_wire(data: bytes, limit: int = 65_536) -> dict:
    if data[:8] != b"KTCOWN1\n":
        raise AssertionError("bad native wire magic")
    offset = 8
    output = {1: bytearray(), 2: bytearray()}
    final = None
    while offset < len(data):
        if final is not None or len(data) - offset < 5:
            raise AssertionError("trailing or partial native frame")
        tag, size = struct.unpack_from(">BI", data, offset)
        offset += 5
        if size > len(data) - offset:
            raise AssertionError("partial native payload")
        payload = data[offset:offset + size]
        offset += size
        if tag in (1, 2):
            if not 1 <= size <= 8192 or sum(map(len, output.values())) + size > limit:
                raise AssertionError("native output bound violated")
            output[tag].extend(payload)
        elif tag == 3 and size == 32:
            cause, flags, child_exit, reserved, observed, captured = struct.unpack(">IIiIQQ", payload)
            if not 0 <= cause <= 10 or flags & ~0x3fff or reserved or not -1 <= child_exit <= 255:
                raise AssertionError("bad native terminal fields")
            if captured != sum(map(len, output.values())) or not captured <= observed <= (1 << 63) - 1:
                raise AssertionError("native terminal output mismatch")
            if bool(flags & F_EXIT) != (child_exit >= 0):
                raise AssertionError("native exit flag mismatch")
            if cause == 0 and (flags != 31 or child_exit < 0 or observed != captured):
                raise AssertionError("unqualified native completed record")
            final = {"cause": cause, "flags": flags, "exit": child_exit, "observed": observed, "captured": captured}
        else:
            raise AssertionError("unknown native frame")
    if final is None:
        raise AssertionError("native terminal missing")
    return {**final, "stdout": bytes(output[1]), "stderr": bytes(output[2])}


def collect(process: subprocess.Popen, timeout: float = 5) -> tuple[int, bytes, bytes]:
    """Do not communicate(): closing control stdin would request parent loss."""
    selector = selectors.DefaultSelector()
    output = {"stdout": bytearray(), "stderr": bytearray()}
    for name in output:
        stream = getattr(process, name)
        if stream is not None and not stream.closed:
            selector.register(stream, selectors.EVENT_READ, name)
    deadline = time.monotonic() + timeout
    try:
        while selector.get_map():
            if time.monotonic() >= deadline:
                raise AssertionError("native output did not close within test deadline")
            for key, _ in selector.select(0.05):
                data = os.read(key.fd, 8192)
                if not data:
                    selector.unregister(key.fd)
                else:
                    output[key.data].extend(data)
                    if sum(map(len, output.values())) > 2 * 1024 * 1024:
                        raise AssertionError("test wire exceeded retention limit")
        code = process.wait(timeout=max(0.1, deadline - time.monotonic()))
        return code, bytes(output["stdout"]), bytes(output["stderr"])
    finally:
        selector.close()


class BuildReceiptTest(unittest.TestCase):
    def test_normalized_host_targets(self):
        for system, machine, target in (("Linux", "amd64", "linux-x86_64"), ("Darwin", "arm64", "macos-aarch64"),
                                        ("Windows", "AMD64", "windows-x86_64"), ("Windows", "ARM64", "windows-aarch64"),
                                        ("Linux", "riscv64", "unsupported"), ("FreeBSD", "x86_64", "unsupported")):
            self.assertEqual(target, builder.host_identity(system, machine)["target"])

    def test_unsupported_host_writes_explicit_manifest_without_compiler(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stale = root / builder.PREFIX / "linux-x86_64/supervisor"
            stale.parent.mkdir(parents=True)
            stale.write_bytes(b"stale")
            with patch.object(builder, "bounded_command", side_effect=AssertionError("compiler called")):
                receipt = builder.build(root, Path("/not-a-compiler"), host=builder.host_identity("Windows", "AMD64"))
            resource = root / builder.PREFIX
            lines = (resource / "manifest").read_text().splitlines()
            self.assertEqual([builder.PROTOCOL, "windows-x86_64"], lines[:2])
            self.assertEqual("unavailable", lines[3])
            self.assertEqual(4, len(lines))
            self.assertEqual("unavailable", receipt["status"])
            self.assertIsNone(receipt["compiler"])
            self.assertIsNone(receipt["invocation"])
            self.assertIsNone(receipt["output"])
            self.assertFalse(stale.exists())
            self.assertEqual([], list(resource.glob("*/supervisor")))

    def test_failed_rebuild_does_not_reuse_stale_resources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            resource = root / builder.PREFIX
            resource.mkdir(parents=True)
            (resource / "manifest").write_bytes(b"stale")
            with self.assertRaisesRegex(builder.BuildProblem, "compiler_unavailable"):
                builder.build(root, Path("cc"), host=builder.host_identity("Linux", "x86_64"))
            self.assertFalse((resource / "manifest").exists())
            self.assertEqual([], list(resource.rglob("supervisor")))

    def test_private_paths_and_untrusted_banner_are_not_retained(self):
        self.assertEqual("<private-compiler>", builder.public_compiler_path(Path("/home/private-owner/tools/cc")))
        self.assertEqual("/usr/bin/cc", builder.public_compiler_path(Path("/usr/bin/cc")))
        self.assertEqual(("<redacted>", True), builder.public_version_line(b"clang /home/private-owner/tool\n"))
        self.assertEqual(("<redacted>", True), builder.public_version_line(b"secret-token\n"))
        self.assertEqual(("<redacted>", True), builder.public_version_line(b"gcc secret-token\n"))
        self.assertEqual(("<redacted>", True), builder.public_version_line(b"clang version 17.0.0 private-token\n"))
        self.assertEqual(("<redacted>", True), builder.public_version_line(b"Apple clang version 17.0.0\n"))


@unittest.skipUnless(builder.host_identity()["target"] in builder.SUPPORTED, "native supervisor unavailable on this host")
class NativeSupervisorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="ktchecker-native-test-")
        cls.root = Path(cls.temporary.name)
        cls.receipt = builder.build(cls.root / "resources", CC)
        cls.supervisor = cls.root / "resources" / cls.receipt["output"]["path"]
        cls.probe = cls.root / "probe"
        source = Path(__file__).resolve().with_name("supervisor_probe.c")
        builder.bounded_command([str(CC), *builder.FLAGS, str(source), "-o", str(cls.probe)], 30)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.case = Path(tempfile.mkdtemp(dir=self.root, prefix="case-"))
        self.processes = []

    def tearDown(self):
        # Normal native outcomes already exit. A test failure closes control and
        # waits for autonomous cleanup; only a broken test helper after 5s may
        # be forcibly lost. Every probe self-expires, and no PGID is signaled here.
        for process in self.processes:
            if process.stdin is not None and not process.stdin.closed:
                process.stdin.close()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)
            for stream in (process.stdout, process.stderr):
                if stream is not None:
                    stream.close()

    def start(self, args, *, timeout=1000, limit=65_536, prefix=(), **kwargs):
        command = [*prefix, str(self.supervisor), "--ktc-owned-child-v1", str(timeout), str(limit), "--", *map(str, args)]
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
        self.processes.append(process)
        return process

    def finish(self, process, *, limit=65_536):
        code, wire, diagnostics = collect(process)
        self.assertEqual(b"", diagnostics)
        self.assertEqual(0, code)
        result = read_wire(wire, limit)
        self.assertTrue(result["flags"] & F_QUIESCENT, result)
        self.assertFalse(result["flags"] & (F_OWNERSHIP | F_SHUTDOWN), result)
        return result

    def wait_ready(self, path):
        deadline = time.monotonic() + 1.5
        while not path.exists() and time.monotonic() < deadline:
            time.sleep(0.005)
        self.assertTrue(path.exists(), "probe never became ready")

    def resistant(self, mode="resist", **kwargs):
        marker, ready = self.case / "late", self.case / "ready"
        process = self.start([self.probe, mode, marker, ready], **kwargs)
        self.wait_ready(ready)
        return process, marker, ready

    def test_built_receipt_binds_current_source_builder_driver_and_binary(self):
        receipt = self.receipt
        self.assertEqual("built", receipt["status"])
        self.assertEqual(builder.file_digest(self.supervisor, builder.MAX_BINARY), receipt["output"]["sha256"])
        self.assertEqual(builder.file_digest(Path(__file__).with_name("owned_process_supervisor.c"), builder.MAX_BINARY),
                         receipt["inputs"]["source"]["sha256"])
        self.assertEqual(builder.file_digest(Path(builder.__file__), builder.MAX_BINARY), receipt["inputs"]["builder"]["sha256"])
        self.assertEqual(builder.file_digest(CC.resolve(), 256 * 1024 * 1024), receipt["compiler"]["driverSha256"])
        self.assertEqual(["<compiler>", *builder.FLAGS, "<source>", "-o", "<output>"], receipt["invocation"]["argv"])
        self.assertTrue(receipt["compilerDriverIdentityOnly"])
        self.assertFalse(receipt["transitiveCompilerToolchainVerified"])
        self.assertFalse(receipt["sourceToBinaryReproducibilityEstablished"])
        resource = self.root / "resources" / builder.PREFIX
        retained = (resource / "build-provenance.json").read_bytes()
        self.assertLessEqual(len(retained), builder.MAX_RECEIPT)
        self.assertNotIn(str(self.root).encode(), retained)
        self.assertEqual([builder.PROTOCOL, receipt["host"]["target"], receipt["inputs"]["source"]["sha256"],
                          receipt["output"]["sha256"]], (resource / "manifest").read_text().splitlines())

    def test_exact_argv_nonzero_exit_and_separate_output_channels(self):
        arguments = ["", "space value", "single'quote", 'double"quote', "*?[glob]", "$(not-expanded)", "line\nbreak", "ユニコード"]
        result = self.finish(self.start([self.probe, "argv", *arguments]))
        expected = b"".join(struct.pack(">I", len(value.encode())) + value.encode() for value in arguments)
        self.assertEqual(expected, result["stdout"])
        self.assertEqual(b"probe-stderr\n", result["stderr"])
        self.assertEqual((0, 31, 23), (result["cause"], result["flags"], result["exit"]))

    def test_fast_leader_exit_kills_term_resistant_orphan_before_late_write(self):
        process, marker, _ = self.resistant("orphan")
        result = self.finish(process)
        self.assertEqual((0, 31, 0), (result["cause"], result["flags"], result["exit"]))
        time.sleep(1)
        self.assertFalse(marker.exists())

    def test_other_retained_process_group_is_not_signaled(self):
        marker = self.case / "unrelated"
        unrelated = subprocess.Popen([str(self.probe), "unrelated", str(marker)], start_new_session=True,
                                     stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.processes.append(unrelated)
        self.assertEqual(b"ready\n", unrelated.stdout.readline())
        process, late, _ = self.resistant("orphan")
        self.assertEqual(0, self.finish(process)["cause"])
        self.assertEqual(0, unrelated.wait(timeout=2))
        self.assertEqual("unrelated-survived", marker.read_text())
        self.assertFalse(late.exists())

    def test_c_then_eof_is_cancellation_not_parent_loss_across_grace(self):
        process, marker, _ = self.resistant()
        started = time.monotonic()
        process.stdin.write(b"CC"); process.stdin.flush(); process.stdin.close()
        result = self.finish(process)
        self.assertEqual(1, result["cause"])
        self.assertTrue(result["flags"] & F_CANCEL)
        self.assertFalse(result["flags"] & F_PARENT)
        self.assertFalse(result["flags"] & F_EXEC)
        self.assertEqual(137, result["exit"])
        self.assertGreaterEqual(time.monotonic() - started, 0.20)
        self.assertFalse(marker.exists())

    def test_timeout_is_autonomous_with_control_kept_open(self):
        process, marker, _ = self.resistant(timeout=120)
        result = self.finish(process)
        self.assertEqual(2, result["cause"])
        self.assertTrue(result["flags"] & F_TIMEOUT)
        self.assertFalse(result["flags"] & F_PARENT)
        self.assertFalse(marker.exists())

    def test_output_limit_is_combined_and_never_clean_success(self):
        limit = 8193
        result = self.finish(self.start([self.probe, "flood"], limit=limit), limit=limit)
        self.assertEqual(3, result["cause"])
        self.assertTrue(result["flags"] & F_OUTPUT)
        self.assertEqual(limit, result["captured"])
        self.assertGreater(result["observed"], limit)

    def test_unsolicited_control_eof_reports_parent_loss_and_cleans_child(self):
        process, marker, _ = self.resistant()
        process.stdin.close()
        result = self.finish(process)
        self.assertEqual(4, result["cause"])
        self.assertTrue(result["flags"] & F_PARENT)
        self.assertFalse(marker.exists())

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux subreaper test harness owns the orphaned helper")
    def test_actual_owner_death_closes_control_and_helper_cleans_owned_child(self):
        # The test harness becomes a subreaper solely to collect the actual
        # orphaned helper after its owner dies; it never signals a discovered PID.
        import ctypes
        libc = ctypes.CDLL(None, use_errno=True)
        previous = ctypes.c_int()
        self.assertEqual(0, libc.prctl(37, ctypes.byref(previous), 0, 0, 0))  # PR_GET_CHILD_SUBREAPER
        self.assertEqual(0, libc.prctl(36, 1, 0, 0, 0))  # PR_SET_CHILD_SUBREAPER
        marker, ready, helper_pid = self.case / "late", self.case / "ready", self.case / "helper-pid"
        owner_code = """
import os, pathlib, subprocess, sys, time
helper, probe, marker, ready, pidfile = sys.argv[1:]
process = subprocess.Popen([helper, '--ktc-owned-child-v1', '1000', '65536', '--', probe, 'resist', marker, ready],
    stdin=subprocess.PIPE, stdout=sys.stdout.buffer, stderr=sys.stderr.buffer, close_fds=True)
pathlib.Path(pidfile).write_text(str(process.pid))
deadline = time.monotonic() + 1.5
while not pathlib.Path(ready).exists() and time.monotonic() < deadline:
    time.sleep(0.005)
os._exit(17)  # actual owner death, no explicit close or cancellation write
"""
        try:
            owner = subprocess.Popen([sys.executable, "-c", owner_code, str(self.supervisor), str(self.probe),
                                      str(marker), str(ready), str(helper_pid)], stdin=subprocess.DEVNULL,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.processes.append(owner)
            code, wire, diagnostics = collect(owner)
            self.assertEqual(17, code)
            self.assertEqual(b"", diagnostics)
            result = read_wire(wire)
            self.assertEqual(4, result["cause"])
            self.assertTrue(result["flags"] & F_PARENT)
            self.assertTrue(result["flags"] & F_QUIESCENT)
            self.assertFalse(marker.exists())
            child = int(helper_pid.read_text())
            deadline = time.monotonic() + 1
            waited = (0, 0)
            while waited[0] == 0 and time.monotonic() < deadline:
                waited = os.waitpid(child, os.WNOHANG)
                if waited[0] == 0:
                    time.sleep(0.005)
            self.assertEqual(child, waited[0])
            self.assertTrue(os.WIFEXITED(waited[1]))
            self.assertEqual(0, os.WEXITSTATUS(waited[1]))
        finally:
            self.assertEqual(0, libc.prctl(36, previous.value, 0, 0, 0))

    def test_helper_term_int_hup_run_cleanup_instead_of_dying(self):
        for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            with self.subTest(signal=number):
                case = self.case / str(number); case.mkdir()
                marker, ready = case / "late", case / "ready"
                process = self.start([self.probe, "resist", marker, ready])
                self.wait_ready(ready)
                process.send_signal(number)  # our still-unreaped direct helper
                result = self.finish(process)
                self.assertEqual(1, result["cause"])
                self.assertTrue(result["flags"] & F_CANCEL)
                self.assertFalse(marker.exists())

    def test_invalid_control_byte_is_protocol_failure_with_cleanup(self):
        process, marker, _ = self.resistant()
        process.stdin.write(b"X"); process.stdin.flush()
        result = self.finish(process)
        self.assertEqual(9, result["cause"])
        self.assertTrue(result["flags"] & F_PROTOCOL)
        self.assertFalse(marker.exists())

    def test_exec_failure_retains_exit_but_never_confirms_start(self):
        result = self.finish(self.start([self.case / "absent-executable"]))
        self.assertEqual(6, result["cause"])
        self.assertEqual(126, result["exit"])
        self.assertTrue(result["flags"] & F_EXEC_FAILED)
        self.assertFalse(result["flags"] & F_EXEC)

    def test_signaled_and_reserved_126_exits_are_start_unconfirmed(self):
        for args, code in ((["signal"], 137), (["exit", "126"], 126)):
            with self.subTest(exit=code):
                result = self.finish(self.start([self.probe, *args]))
                self.assertEqual(7, result["cause"])
                self.assertEqual(code, result["exit"])
                self.assertFalse(result["flags"] & F_EXEC)
                self.assertTrue(result["stdout"])

    def test_broken_owner_output_still_runs_native_cleanup(self):
        process, marker, _ = self.resistant()
        process.stdout.close()
        # Give the helper a reason to flush again; losing its output must not
        # terminate it through SIGPIPE before its last group signal and reap.
        process.stdin.write(b"C"); process.stdin.flush()
        code, _, diagnostics = collect(process)
        self.assertEqual(74, code)
        self.assertEqual(b"", diagnostics)
        self.assertFalse(marker.exists())

    def test_blocked_owner_output_returns_bounded_unknown_not_success(self):
        process = self.start([self.probe, "flood"], timeout=120, limit=262_144)
        # Deliberately do not read frames until native execution+cleanup+flush
        # have expired. The helper must never block indefinitely on its owner.
        self.assertEqual(74, process.wait(timeout=4))
        code, wire, diagnostics = collect(process)
        self.assertEqual(74, code)
        self.assertEqual(b"", diagnostics)
        with self.assertRaises(AssertionError):
            read_wire(wire, 262_144)

    def test_inherited_ignored_sigchld_is_reset_before_fork(self):
        def ignore_sigchld():
            signal.signal(signal.SIGCHLD, signal.SIG_IGN)
        result = self.finish(self.start([self.probe, "exit", "0"], preexec_fn=ignore_sigchld))
        self.assertEqual((0, 31, 0), (result["cause"], result["flags"], result["exit"]))

    def test_invalid_limits_exit_before_start_without_fake_terminal(self):
        process = self.start([self.probe, "exit", "0"], timeout=0)
        code, wire, diagnostics = collect(process)
        self.assertEqual(64, code)
        self.assertEqual(b"", wire)
        self.assertEqual(b"", diagnostics)

    @unittest.skipUnless(sys.platform.startswith("linux"), "Linux-only syscall observation, not a Darwin proof")
    def test_syscalls_every_real_group_signal_precedes_any_leader_reap(self):
        trace_tool = Path("/usr/bin/strace")
        self.assertTrue(trace_tool.is_file(), "Linux native validation requires an explicit strace installation")
        trace = self.case / "syscalls"
        marker, ready = self.case / "late", self.case / "ready"
        process = self.start([self.probe, "orphan", marker, ready], prefix=[str(trace_tool), "-f", "-qq", "-o", str(trace),
                             "-e", "trace=clone,fork,vfork,kill,wait4,waitid"])
        result = self.finish(process)
        self.assertEqual((0, 31, 0), (result["cause"], result["flags"], result["exit"]))
        lines = trace.read_text().splitlines()
        signals = []
        for index, line in enumerate(lines):
            match = re.search(r"kill\(-(\d+), (SIGTERM|SIGKILL)\)\s*=\s*0", line)
            if match:
                signals.append((index, int(match[1]), match[2]))
        self.assertEqual(["SIGTERM", "SIGKILL"], [entry[2] for entry in signals])
        leader = signals[0][1]
        self.assertTrue(all(entry[1] == leader for entry in signals))
        reaps = [index for index, line in enumerate(lines) if re.search(r"wait4\(.*\)\s*=\s*" + str(leader) + r"(?:\s|$)", line)]
        self.assertEqual(1, len(reaps), "actual leader reap must appear exactly once")
        self.assertLess(max(entry[0] for entry in signals), reaps[0])
        self.assertTrue(any("waitid(P_PID, " + str(leader) in line and "WNOWAIT" in line for line in lines))
        self.assertTrue(any("wait4(" in line and "ECHILD" in line for line in lines[reaps[0] + 1:]))
        self.assertFalse(marker.exists())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--cc", type=Path, required=True)
    args, unittest_args = parser.parse_known_args()
    CC = args.cc
    unittest.main(argv=[sys.argv[0], *unittest_args])
