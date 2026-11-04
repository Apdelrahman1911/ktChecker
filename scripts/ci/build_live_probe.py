#!/usr/bin/env python3
"""Explicit build-time provisioning of the reviewed Linux live integration probe.

Requires --cc and a NEW absolute --output directory with an existing trusted
parent. Every acquired attempt is retained, including failures and successes;
there is no clearing, retry, recovery, or automatic deletion. On exit zero only,
the owner may pass <output>/probe explicitly as KTC_OWNED_LIVE_PROBE. This tool
never runs the probe, packages it, or authorizes binary publication. Only the
bounded sanitized receipt.json is intended for CI evidence upload.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from types import ModuleType


SCHEMA = "ktchecker-owned-live-probe-build/1"
REVIEWED_SOURCE_SHA256 = "sha256:3b655411076252a170b9cd8ad9e71d0c4571ec1887e3643420321d628dd50ef3"
REVIEWED_BUILDER_SHA256 = "sha256:3cc2cae897cb5e034311ec6feac533c97bf1b4c9cbafc1c105742d53b8b8f91c"
MAX_INPUT = 1_048_576
MAX_DRIVER = 256 * 1024 * 1024
MAX_RECEIPT = 16_384
PHASES = ("preflight", "version_probe_pending", "version_probe_direct_return_observed",
          "compile_pending", "compile_direct_return_observed", "complete")
PUBLIC_REASONS = frozenset({
    "owned_live_probe_output_invalid", "owned_live_probe_attempt_retained",
    "owned_live_probe_attempt_unavailable", "owned_live_probe_input_invalid",
    "owned_live_probe_input_changed", "owned_live_probe_reviewed_input_mismatch",
    "owned_live_probe_unsupported_host", "owned_live_probe_compiler_unavailable",
    "owned_live_probe_phase_failed", "owned_live_probe_receipt_failed",
    "native_supervisor_compiler_launch_unknown",
    "native_supervisor_compiler_cleanup_unknown", "native_supervisor_compiler_timeout",
    "native_supervisor_compiler_failed", "native_supervisor_compiler_output_limit",
})


class ProbeProblem(Exception):
    """Fixed public reason only; never a path or compiler transcript."""


def _digest(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def _snapshot(path: Path, maximum: int) -> dict:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= maximum:
        raise ProbeProblem("owned_live_probe_input_invalid")
    value = hashlib.sha256()
    total = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(65_536), b""):
            total += len(chunk)
            if total > maximum:
                raise ProbeProblem("owned_live_probe_input_invalid")
            value.update(chunk)
    after = path.lstat()
    if (total != info.st_size or (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) !=
            (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns) or not stat.S_ISREG(after.st_mode)):
        raise ProbeProblem("owned_live_probe_input_changed")
    return {"sha256": "sha256:" + value.hexdigest(), "bytes": total}


def _input_paths() -> tuple[Path, Path, Path]:
    provisioner = Path(__file__).resolve()
    native = provisioner.parents[2] / "modules/provider-runtime/native"
    return native / "live_supervisor_probe.c", native / "build_live_supervisor.py", provisioner


def _load_driver(path: Path) -> ModuleType:
    # Execute these SAME verified bytes, not an ambient module or cached pyc.
    # The local module is never installed in sys.modules or mutated by provision.
    with path.open("rb") as stream:
        data = stream.read(MAX_INPUT + 1)
    if not 0 < len(data) <= MAX_INPUT or _digest(data) != REVIEWED_BUILDER_SHA256:
        raise ProbeProblem("owned_live_probe_reviewed_input_mismatch")
    driver = ModuleType("_ktchecker_live_probe_private_driver")
    driver.__file__ = str(path)
    exec(compile(data, str(path), "exec"), driver.__dict__)
    return driver


def _append_phase(output: Path, phase: str) -> None:
    if phase not in PHASES:
        raise ProbeProblem("owned_live_probe_phase_failed")
    data = (phase + "\n").encode("ascii")
    # Append, never truncate: a later write/close/diagnostic failure cannot erase
    # a preceding pending record. No launch follows an unsuccessful append/close.
    with (output / "phase").open("ab") as stream:
        if stream.write(data) != len(data):
            raise ProbeProblem("owned_live_probe_phase_failed")


def _write_receipt(path: Path, receipt: dict) -> str:
    data = (json.dumps(receipt, ensure_ascii=True, sort_keys=True, indent=2) + "\n").encode("ascii")
    if len(data) > MAX_RECEIPT:
        raise ProbeProblem("owned_live_probe_receipt_failed")
    with path.open("xb") as stream:
        if stream.write(data) != len(data):
            raise ProbeProblem("owned_live_probe_receipt_failed")
    return _digest(data)


def _public_reason(error: BaseException) -> str:
    reason = error.args[0] if len(error.args) == 1 else None
    return reason if isinstance(reason, str) and reason in PUBLIC_REASONS else "owned_live_probe_build_failed"


def _failed_exit(reason: str, error: BaseException) -> int:
    try:
        print(reason, file=sys.stderr)
        sys.stderr.flush()
    except BaseException:
        pass
    return 130 if isinstance(error, KeyboardInterrupt) else 1


def provision(output: Path, cc: Path) -> Path:
    if not output.is_absolute() or ".." in output.parts:
        raise ProbeProblem("owned_live_probe_output_invalid")
    try:
        output.mkdir(mode=0o700, exist_ok=False)
    except FileExistsError:
        raise ProbeProblem("owned_live_probe_attempt_retained") from None
    except OSError:
        raise ProbeProblem("owned_live_probe_attempt_unavailable") from None
    # The exclusive directory itself is permanent custody. Never remove it or
    # an output file on ANY path; even an ambiguous mkdir outcome is not retried.
    try:
        _append_phase(output, "preflight")
        source, builder, provisioner = _input_paths()
        paths = {"source": source, "builder": builder, "provisioner": provisioner}
        before_inputs = {name: _snapshot(path, MAX_INPUT) for name, path in paths.items()}
        if (before_inputs["source"]["sha256"] != REVIEWED_SOURCE_SHA256 or
                before_inputs["builder"]["sha256"] != REVIEWED_BUILDER_SHA256):
            raise ProbeProblem("owned_live_probe_reviewed_input_mismatch")
        driver = _load_driver(builder)
        host = driver.host_identity()
        if host["target"] not in {"linux-x86_64", "linux-aarch64"}:
            raise ProbeProblem("owned_live_probe_unsupported_host")
        if not cc.is_absolute() or not cc.is_file() or not os.access(cc, os.X_OK):
            raise ProbeProblem("owned_live_probe_compiler_unavailable")
        resolved_cc = cc.resolve(strict=True)
        before_cc = _snapshot(resolved_cc, MAX_DRIVER)
        binary = output / "probe"
        flags = tuple(driver.FLAGS)
        version_argv = [str(cc), "--version"]
        compile_argv = [str(cc), *flags, str(source), "-o", str(binary)]
        invocations = {}
        for name, argv, public_argv, seconds in (
                ("version", version_argv, ["<compiler>", "--version"], 10),
                ("compile", compile_argv, ["<compiler>", *flags, "<source>", "-o", "<output>"], 60)):
            exact = json.dumps(argv, ensure_ascii=True, separators=(",", ":")).encode("ascii")
            invocations[name] = {"argv": public_argv, "exactArgvSha256": _digest(exact), "timeoutSeconds": seconds}
        compiler = {"selectedPath": "<owner-selected-compiler>", "resolvedPath": "<direct-driver>",
                    "selectedPathSha256": _digest(str(cc).encode("utf-8")),
                    "resolvedPathSha256": _digest(str(resolved_cc).encode("utf-8")), "before": before_cc}
        receipt = {
            "schemaVersion": SCHEMA, "status": "prepared_not_launched", "host": host,
            "inputsBefore": before_inputs, "compiler": compiler, "flags": list(flags),
            "inputPaths": {"source": "modules/provider-runtime/native/live_supervisor_probe.c",
                           "builder": "modules/provider-runtime/native/build_live_supervisor.py",
                           "provisioner": "scripts/ci/build_live_probe.py"},
            "invocations": invocations, "output": {"path": "probe", "status": "absent"},
            "exactArgvEncoding": "utf8-json-compact-ascii", "privateArgvRetained": False,
            "provisioningSuccessRequiresZeroExit": True,
            "compilerDriverIdentityOnly": True, "transitiveCompilerToolchainVerified": False,
            "sourceToBinaryReproducibilityEstablished": False, "nativeProbeExecuted": False,
            "nativeQualificationEstablished": False, "binaryPublicationAuthorized": False,
            "licensing": "unresolved", "privatePathsRetained": False, "rawCompilerTranscriptRetained": False,
        }
        before_sha = _write_receipt(output / "before.json", receipt)
        _append_phase(output, "version_probe_pending")
        version = driver.bounded_command(version_argv, 10)
        _append_phase(output, "version_probe_direct_return_observed")
        if binary.exists() or binary.is_symlink():
            raise ProbeProblem("owned_live_probe_output_invalid")
        _append_phase(output, "compile_pending")
        driver.bounded_command(compile_argv, 60)
        _append_phase(output, "compile_direct_return_observed")
        after_inputs = {name: _snapshot(path, MAX_INPUT) for name, path in paths.items()}
        after_resolved = cc.resolve(strict=True)
        after_cc = _snapshot(after_resolved, MAX_DRIVER)
        if after_inputs != before_inputs or after_cc != before_cc or after_resolved != resolved_cc:
            raise ProbeProblem("owned_live_probe_input_changed")
        built = _snapshot(binary, MAX_INPUT)
        binary.chmod(0o700)
        if (_snapshot(binary, MAX_INPUT) != built or stat.S_IMODE(binary.lstat().st_mode) != 0o700 or
                not os.access(binary, os.X_OK)):
            raise ProbeProblem("owned_live_probe_output_invalid")
        receipt = {
            **receipt, "status": "direct_compile_return_observed", "inputsAfter": after_inputs,
            "beforeReceiptSha256": before_sha, "compiler": {**compiler, "after": after_cc},
            "version": {"status": "direct_return_observed", "exitCode": 0,
                        "outputSha256": _digest(version), "outputBytes": len(version)},
            "compile": {"status": "direct_return_observed", "exitCode": 0},
            "output": {"path": "probe", **built, "mode": "0700", "regularNonSymlinkExecutable": True},
        }
        _write_receipt(output / "receipt.json", receipt)
        _append_phase(output, "complete")
        return binary
    except BaseException as error:
        # Best-effort diagnostics never alter phase/inputs/output or replace the
        # primary failure. Missing/partial receipts never establish success.
        try:
            _write_receipt(output / "failure.json", {
                "schemaVersion": SCHEMA, "status": "failed_or_custody_unknown",
                "reason": _public_reason(error), "compilerRetirementInferred": False,
                "binaryPublicationAuthorized": False, "nativeProbeExecuted": False,
            })
        except BaseException:
            pass
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cc", type=Path, required=True, help="absolute owner-selected driver; no PATH fallback")
    parser.add_argument("--output", type=Path, required=True, help="new absolute private attempt directory")
    args = parser.parse_args(argv)
    try:
        binary = provision(args.output, args.cc)
    except BaseException as error:
        return _failed_exit(_public_reason(error), error)
    # This path is an owner-local handoff, not publishable diagnostics. The
    # caller must require exit zero; a complete receipt alone is insufficient.
    try:
        print(binary)
        sys.stdout.flush()
    except BaseException as error:
        return _failed_exit("owned_live_probe_handoff_failed", error)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
