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


# This is a deterministic C state model, not an OS syscall experiment. System
# headers are loaded before selecting the production read-only group branch;
# every wait/signal and clock observation is replaced, on every actual host.
# The production entry point, fork, and exec are never invoked.
MOCKED_SHUTDOWN_SOURCE = r"""
#define _POSIX_C_SOURCE 200809L
#define _DARWIN_C_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
static int mock_clock(clockid_t id, struct timespec *value);
static int mock_kill(pid_t pid, int number);
static int mock_waitid(idtype_t kind, id_t id, siginfo_t *info, int options);
static pid_t mock_waitpid(pid_t pid, int *status, int options);
static pid_t forbidden_fork(void);
static int forbidden_execv(const char *path, char *const arguments[]);
#define clock_gettime mock_clock
#define kill mock_kill
#define waitid mock_waitid
#define waitpid mock_waitpid
#define fork forbidden_fork
#define execv forbidden_execv
#define main uncalled_owned_supervisor_main
/* Select algorithm code only, never pretend this binary has a Darwin ABI. */
#ifdef __linux__
#undef __linux__
#endif
#ifndef __APPLE__
#define __APPLE__ 1
#endif
#include "owned_process_supervisor.c"
#undef main
#undef clock_gettime
#undef kill
#undef waitid
#undef waitpid
#undef fork
#undef execv

#define REQUIRE(value, code) do { if (!(value)) return (code); } while (0)
static const pid_t held_child = 41; /* synthetic identity: never a real signal */
static uint64_t clock_value;
static int signal_error, query_error, observe_error, reap_error, reap_status;
static int signals, queries, observations, waits, bad_order;
static bool reap_pending, did_reap;

static int mock_clock(clockid_t id, struct timespec *value) {
    if (id != CLOCK_MONOTONIC) bad_order = 1;
    if (clock_value == UINT64_MAX) { errno = EIO; return -1; }
    value->tv_sec = (time_t)(clock_value / 1000u);
    value->tv_nsec = (long)(clock_value % 1000u) * 1000000L;
    return 0;
}
static int mock_kill(pid_t pid, int number) {
    if (pid != -held_child) bad_order = 1;
    if (number == 0) {
        if (!did_reap) bad_order = 1;
        ++queries;
        if (query_error) { errno = query_error; return -1; }
        return 0; /* could be the original or a reused group: never empty */
    }
    if (waits || (number != SIGTERM && number != SIGKILL)) bad_order = 1;
    if ((signals == 0 && number != SIGTERM) || (signals == 1 && number != SIGKILL) || signals > 1) bad_order = 1;
    ++signals;
    if (signal_error) { errno = signal_error; return -1; }
    return 0;
}
static int mock_waitid(idtype_t kind, id_t id, siginfo_t *info, int options) {
    if (waits || kind != P_PID || id != (id_t)held_child || options != (WEXITED | WNOHANG | WNOWAIT)) bad_order = 1;
    ++observations;
    memset(info, 0, sizeof(*info));
    if (observe_error) { errno = observe_error; return -1; }
    info->si_pid = held_child; info->si_code = CLD_EXITED; info->si_status = 0;
    return 0;
}
static pid_t mock_waitpid(pid_t pid, int *status, int options) {
    if (pid != held_child || options != WNOHANG || did_reap) bad_order = 1;
    ++waits;
    if (reap_error) { errno = reap_error; return -1; }
    if (reap_pending) return 0;
    *status = reap_status; did_reap = true; return held_child;
}
static pid_t forbidden_fork(void) { _exit(120); }
static int forbidden_execv(const char *path, char *const arguments[]) {
    (void)path; (void)arguments; _exit(121);
}
static void reset_state(struct supervisor *s) {
    memset(s, 0, sizeof(*s));
    s->child = held_child; s->child_exit = -1;
    s->out = s->err = s->ready = s->gate = -1;
    s->owned = s->group = s->granted = s->ready_seen = true;
    s->flags = F_GROUP;
    clock_value = 100;
    signal_error = query_error = observe_error = reap_error = reap_status = 0;
    signals = queries = observations = waits = bad_order = 0;
    reap_pending = did_reap = false;
}
static void stop_signals(struct supervisor *s) {
    begin_stop(s, 100);
    finish_signals(s);
}
static int signal_uncertainty(void) {
    struct supervisor s; reset_state(&s);
    signal_error = EPERM; query_error = ESRCH;
    stop_signals(&s);
    REQUIRE(signals == 2 && waits == 0 && observations == 2 && !bad_order, 10);
    REQUIRE(s.signal_delivery_uncertain && !s.group_observation_uncertain, 11);
    REQUIRE(s.cause == COMPLETED && !(s.flags & (F_QUIESCENT | F_SHUTDOWN | F_OWNERSHIP)), 12);
    reap_after_last_signal(&s);
    REQUIRE(did_reap && waits == 1 && queries == 1 && !bad_order, 13);
    REQUIRE((s.flags & (F_EXIT | F_REAPED | F_QUIESCENT)) == (F_EXIT | F_REAPED | F_QUIESCENT), 14);
    REQUIRE(s.signal_delivery_uncertain && !s.wait_failed && !(s.flags & (F_SHUTDOWN | F_OWNERSHIP)), 15);
    return 0;
}
static int observation_uncertainty(void) {
    struct supervisor s; reset_state(&s); stop_signals(&s);
    query_error = EPERM;
    reap_after_last_signal(&s);
    REQUIRE(did_reap && s.group_observation_uncertain && !s.wait_failed && !bad_order, 20);
    REQUIRE(!(s.flags & (F_QUIESCENT | F_SHUTDOWN)) && s.cause == COMPLETED, 21);
    query_error = EIO; clock_value = 300;
    reap_after_last_signal(&s);
    REQUIRE(queries == 2 && !(s.flags & F_QUIESCENT), 22);
    query_error = ESRCH; clock_value = 400;
    reap_after_last_signal(&s);
    REQUIRE(queries == 3 && waits == 1 && signals == 2 && !bad_order, 23);
    REQUIRE((s.flags & F_QUIESCENT) && s.group_observation_uncertain && !(s.flags & F_SHUTDOWN), 24);
    return 0;
}
static int unresolved_group(void) {
    const int errors[] = {0, EPERM, EIO, EINTR};
    for (size_t index = 0; index < sizeof(errors) / sizeof(errors[0]); ++index) {
        struct supervisor s; reset_state(&s); signal_error = EPERM; stop_signals(&s);
        uint64_t deadline = s.deadline;
        REQUIRE(deadline == 100 + CLEANUP_MS, 30);
        query_error = errors[index];
        for (int turn = 0; turn < 3; ++turn) {
            clock_value = 200 + (uint64_t)turn * 500;
            reap_after_last_signal(&s); begin_stop(&s, clock_value);
            REQUIRE(!cleanup_done(&s, clock_value) && s.deadline == deadline, 31);
            REQUIRE(!(s.flags & (F_QUIESCENT | F_SHUTDOWN)) && !s.wait_failed, 32);
        }
        REQUIRE(s.signal_delivery_uncertain && s.group_observation_uncertain == (errors[index] != 0), 33);
        clock_value = deadline;
        REQUIRE(cleanup_done(&s, clock_value) && s.cause == SHUTDOWN_UNVERIFIED && (s.flags & F_SHUTDOWN), 34);
        query_error = ESRCH; clock_value = deadline - 1;
        reap_after_last_signal(&s);
        REQUIRE(!(s.flags & F_QUIESCENT) && (s.flags & F_SHUTDOWN) && signals == 2 && waits == 1 && !bad_order, 35);
    }
    return 0;
}
static int custody_failures(void) {
    for (int failure = 0; failure < 3; ++failure) {
        struct supervisor s; reset_state(&s);
        if (failure == 0) observe_error = ECHILD;
        if (failure == 1) reap_error = ECHILD;
        if (failure == 2) reap_status = 1 << 8; /* contradict retained exit 0 */
        stop_signals(&s); query_error = ESRCH;
        reap_after_last_signal(&s);
        REQUIRE(s.wait_failed && (s.flags & F_SHUTDOWN) && !(s.flags & F_QUIESCENT), 40);
        int prior_signals = signals;
        observe_error = reap_error = 0;
        reap_after_last_signal(&s); signal_owned(&s, SIGKILL);
        REQUIRE(s.wait_failed && (s.flags & F_SHUTDOWN) && !(s.flags & F_QUIESCENT), 41);
        REQUIRE(queries == 0 && signals == prior_signals && !bad_order, 42);
    }
    return 0;
}
static int no_post_reap_signals(void) {
    struct supervisor s; reset_state(&s); stop_signals(&s);
    reap_pending = true; reap_after_last_signal(&s);
    REQUIRE(s.reaping_started && !did_reap && queries == 0 && waits == 1, 50);
    signal_owned(&s, SIGTERM); finish_signals(&s);
    REQUIRE(signals == 2 && !bad_order, 51);
    reap_pending = false; reap_after_last_signal(&s); /* group exists/reused */
    signal_owned(&s, SIGKILL); begin_stop(&s, 900);
    REQUIRE(did_reap && signals == 2 && queries == 1 && !(s.flags & F_QUIESCENT) && !bad_order, 52);
    REQUIRE(cleanup_done(&s, s.deadline) && (s.flags & F_SHUTDOWN), 53);
    return 0;
}
static int proof_deadline(void) {
    for (int offset = -1; offset <= 1; ++offset) {
        struct supervisor s; reset_state(&s); stop_signals(&s); query_error = ESRCH;
        clock_value = (uint64_t)((int64_t)s.deadline + offset);
        reap_after_last_signal(&s);
        REQUIRE(did_reap && !bad_order && s.deadline == 100 + CLEANUP_MS, 60);
        if (offset < 0) {
            REQUIRE((s.flags & F_QUIESCENT) && !(s.flags & F_SHUTDOWN), 61);
            int prior_queries = queries; clock_value = UINT64_MAX;
            reap_after_last_signal(&s);
            REQUIRE(queries == prior_queries && (s.flags & F_QUIESCENT) && !(s.flags & (F_SHUTDOWN | F_IO)), 62);
        } else {
            REQUIRE(!(s.flags & F_QUIESCENT) && (s.flags & F_SHUTDOWN), 63);
            clock_value = s.deadline - 1; reap_after_last_signal(&s);
            REQUIRE(!(s.flags & F_QUIESCENT) && (s.flags & F_SHUTDOWN), 64);
        }
    }
    struct supervisor s; reset_state(&s); stop_signals(&s); query_error = ESRCH;
    clock_value = UINT64_MAX; reap_after_last_signal(&s);
    REQUIRE(s.cause == IO_FAILURE && (s.flags & (F_IO | F_SHUTDOWN)) == (F_IO | F_SHUTDOWN) && !(s.flags & F_QUIESCENT), 65);
    clock_value = 101; reap_after_last_signal(&s);
    REQUIRE(!(s.flags & F_QUIESCENT) && (s.flags & F_SHUTDOWN) && !bad_order, 66);
    return 0;
}
static int substantive_flags(void) {
    const uint32_t flags[] = {F_IO, F_PROTOCOL, F_OWNERSHIP | F_SHUTDOWN, F_SHUTDOWN};
    for (size_t index = 0; index < sizeof(flags) / sizeof(flags[0]); ++index) {
        struct supervisor s; reset_state(&s);
        note(&s, IO_FAILURE, flags[index]); signal_error = EPERM;
        stop_signals(&s); query_error = ESRCH; reap_after_last_signal(&s);
        REQUIRE(s.cause == IO_FAILURE && (s.flags & flags[index]) == flags[index] && s.signal_delivery_uncertain, 70);
        REQUIRE(!!(s.flags & F_QUIESCENT) == !(flags[index] & (F_OWNERSHIP | F_SHUTDOWN)) && !bad_order, 71);
    }
    return 0;
}
int main(int argc, char **argv) {
    struct sigaction action; memset(&action, 0, sizeof(action)); action.sa_handler = SIG_DFL;
    sigset_t unblocked;
    if (sigemptyset(&action.sa_mask) != 0 || sigemptyset(&unblocked) != 0 || sigaddset(&unblocked, SIGALRM) != 0 ||
        sigaction(SIGALRM, &action, NULL) != 0 || sigprocmask(SIG_UNBLOCK, &unblocked, NULL) != 0) return 119;
    (void)alarm(3); /* owned no-child mock worker self-expiry, not target cleanup */
    if (argc != 2) return 64;
    int result = 64;
    if (strcmp(argv[1], "signal") == 0) result = signal_uncertainty();
    else if (strcmp(argv[1], "observation") == 0) result = observation_uncertainty();
    else if (strcmp(argv[1], "unresolved") == 0) result = unresolved_group();
    else if (strcmp(argv[1], "custody") == 0) result = custody_failures();
    else if (strcmp(argv[1], "ordering") == 0) result = no_post_reap_signals();
    else if (strcmp(argv[1], "deadline") == 0) result = proof_deadline();
    else if (strcmp(argv[1], "flags") == 0) result = substantive_flags();
    if (result == 0) (void)puts("mocked-shutdown-state-pass");
    return result;
}
"""


@unittest.skipUnless(builder.host_identity()["target"] in builder.SUPPORTED, "native supervisor unavailable on this host")
class MockedShutdownStateTest(unittest.TestCase):
    """Portable modeled syscall outcomes, never Darwin or actual child proof."""

    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="ktchecker-shutdown-model-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        source = cls.root / "shutdown-model.c"
        source.write_text(MOCKED_SHUTDOWN_SOURCE, encoding="utf-8")
        cls.worker = cls.root / "shutdown-model"
        builder.bounded_command([str(CC), *builder.FLAGS, "-I", str(Path(__file__).resolve().parent),
                                 str(source), "-o", str(cls.worker)], 30)

    def check_state(self, scenario):
        # subprocess owns only this newly-created no-child model worker. Timeout
        # cleanup never discovers or signals a target PID/process group.
        result = subprocess.run([str(self.worker), scenario], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=5, env=builder.COMPILER_ENVIRONMENT)
        self.assertEqual(0, result.returncode, "mocked shutdown state assertion failed")
        self.assertEqual(b"", result.stderr)
        self.assertEqual(b"mocked-shutdown-state-pass\n", result.stdout)

    def test_mocked_signal_uncertainty_preserves_history_until_independent_empty_group(self):
        self.check_state("signal")

    def test_mocked_observation_uncertainty_can_resolve_only_after_empty_group(self):
        self.check_state("observation")

    def test_mocked_live_or_inaccessible_group_expires_without_quiescence(self):
        self.check_state("unresolved")

    def test_mocked_wait_and_ownership_failures_cannot_recover(self):
        self.check_state("custody")

    def test_mocked_no_real_signal_after_reaping_even_for_reused_group(self):
        self.check_state("ordering")

    def test_mocked_quiescence_cannot_be_confirmed_at_or_after_deadline(self):
        self.check_state("deadline")

    def test_mocked_other_failure_flags_are_not_cleared_by_quiescence(self):
        self.check_state("flags")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--cc", type=Path, required=True)
    args, unittest_args = parser.parse_known_args()
    CC = args.cc
    unittest.main(argv=[sys.argv[0], *unittest_args])
