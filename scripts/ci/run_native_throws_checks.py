#!/usr/bin/env python3
"""Bounded, opt-in installed-byte Native throws controls; no component builds.

This collector requires an externally trusted owner enrollment digest. It does
not produce build authority, discover a classpath/toolchain, infer that current
sources produced old JARs, or turn same-job JSON labels into build provenance.
Only the two explicitly selected iOS targets are supported on actual Darwin.
The historical Native primary lane and its three-case driver are unrelated.
The collector never builds/provisions the driver or its JVM components; the
prebuilt driver intentionally invokes the selected Native fixture compile tasks.
Inputs must be quiescent and owner-controlled. Before/after hashes neither
attest loaded memory nor defeat ABA or concurrent same-UID replacement.

Private enrollment schema (all paths absolute/canonical; unknown keys rejected):
  schema_version=1, evidence_kind="owner_enrolled_native_throws_build",
  context={run_id, run_attempt, job}, source=<actual ci_common source identity>,
  fixture_manifest_digest=<the fixed authored manifest>,
  git={path,size,digest},
  classpath=[{path,size,digest}, ...] in the owner's exact launch order,
  components={<COMPONENT_ENTRIES role>:{classpath_index,digest}, ...},
  java21/java25={home,version,files:{relative:{size,digest}, ...}},
  gradle={wrapper,user_home,distribution_home,wrapper_files:{relative:pin,...},
          distribution_files:{relative:pin,...},cache_marker:pin},
  native={home,data_dir,targets,files:{relative:pin,...},
          dependencies:{llvm/libffi/sysroot-<target>:configured_package,...}},
  xcode={developer_dir,version,build,iphoneos_sdk,iphonesimulator_sdk,
         files:{xcodebuild/xcrun/info_plist:{path,size,digest},...}},
  supervisor_identity={protocolVersion,target,sourceDigest,binaryDigest}.
Pin means {size,digest}; cache_marker additionally has an absolute path.
Java files are bin/java, release, lib/modules, lib/server/libjvm.dylib.
Gradle wrapper_files are gradlew and both gradle/wrapper files. Its distribution
is the existing checksum-selected wrapper cache, not an arbitrary system Gradle.
Native files list exactly bin/tools/konan/common KLIB/selected target KLIB inputs.
Preexisting konan.properties must have airplaneMode=true. Cached external Native
dependencies must already exist; their entire SDK/LLVM/libffi closure is NOT
qualified by this selected-component inventory.

Use --cohort first-mac with ONE explicit target, <=1200 seconds total and <=180
seconds per child: four named-error cases and their four compatible counterparts.
--cohort full retains all 20 authored controls (7 errors, 11 compatible, 2
observation-only). Both explicit targets mean 40 serial invocations, not 40
different controls. Full scheduled/release coverage remains promised; a first
Mac sample never claims comprehensive completion or target/release qualification.

All originals stay in a NEW private directory under ignored build/. Only a
small allowlisted metadata summary is written to --output or printed. There are
no compiler transcripts, source excerpts, commands, local paths, or arbitrary
exception/reason strings in that public projection. Tests are Python source,
synthetic enrollment/receipt and fake CI-process checks, not Native execution.
A JSON artifact alone is not success authority: collector completion and exit
status must also be confirmed. Failed/unconfirmed publication is not successful
evidence; best-effort metadata rewrites do not promise crash/hostile-FS durability.
"""

from __future__ import annotations

import argparse
from collections import Counter
import ctypes
from dataclasses import dataclass
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import platform
import re
import signal
import stat
import subprocess
import sys
import time
from urllib.parse import unquote, urlparse
import zipfile

if __package__:
    from .ci_common import REPO_ROOT, utc_now
else:
    from ci_common import REPO_ROOT, utc_now


COHORT = "native-throws-2.4.21"
VERSION = "2.4.21"
FIXTURE = "providers/platform-fixtures/native/throws"
MANIFEST_DIGEST = "sha256:eec299987a5b35c8d4ae65c69317c66c4702b1227428231c41546aeccb13071f"
MAIN_CLASS = "dev.ktchecker.providers.native.NativeThrowsFixtureMain"
PROTOCOL = "ktchecker-owned-child-supervisor/preview-1"
NATIVE_RESOURCE = "dev/ktchecker/providers/runtime/native/"
TARGETS = {"iosArm64": "ios_arm64", "iosSimulatorArm64": "ios_simulator_arm64"}
FIRST_MAC = (
    "empty-list", "empty-list-nonempty", "override-filter", "override-filter-matching",
    "inherited-filter", "inherited-filter-matching", "suspend-cancellation", "suspend-cancellation-exact",
)
AUTHORED_CONTROLS = (
    ("empty-list", "named_error", "THROWS_LIST_EMPTY"),
    ("empty-list-nonempty", "compatible", "THROWS_LIST_EMPTY"),
    ("empty-list-omitted", "compatible", "THROWS_LIST_EMPTY"),
    ("override-filter", "named_error", "INCOMPATIBLE_THROWS_OVERRIDE"),
    ("override-filter-matching", "compatible", "INCOMPATIBLE_THROWS_OVERRIDE"),
    ("override-filter-inherited", "compatible", "INCOMPATIBLE_THROWS_OVERRIDE"),
    ("override-filter-reordered", "compatible", "INCOMPATIBLE_THROWS_OVERRIDE"),
    ("override-filter-subtype", "named_error", "INCOMPATIBLE_THROWS_OVERRIDE"),
    ("inherited-filter", "named_error", "INCOMPATIBLE_THROWS_INHERITED"),
    ("inherited-filter-matching", "compatible", "INCOMPATIBLE_THROWS_INHERITED"),
    ("inherited-filter-diamond", "compatible", "INCOMPATIBLE_THROWS_INHERITED"),
    ("suspend-cancellation", "named_error", "MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND"),
    ("suspend-cancellation-exact", "compatible", "MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND"),
    ("suspend-cancellation-superclass", "compatible", "MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND"),
    ("suspend-cancellation-nonsuspend", "compatible", "MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND"),
    ("suspend-implicit-override", "named_error", "INCOMPATIBLE_THROWS_OVERRIDE"),
    ("suspend-implicit-inherited", "compatible", "INCOMPATIBLE_THROWS_OVERRIDE"),
    ("override-filter-nowarn", "named_error", "INCOMPATIBLE_THROWS_OVERRIDE"),
    ("override-filter-suppression", "observation_only", "INCOMPATIBLE_THROWS_OVERRIDE"),
    ("override-filter-demotion-denied", "observation_only", "INCOMPATIBLE_THROWS_OVERRIDE"),
)
RULES = {
    "THROWS_LIST_EMPTY": "ktc.compiler.native.throws_list_empty",
    "INCOMPATIBLE_THROWS_OVERRIDE": "ktc.compiler.native.incompatible_throws_override",
    "INCOMPATIBLE_THROWS_INHERITED": "ktc.compiler.native.incompatible_throws_inherited",
    "MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND": "ktc.compiler.native.missing_exception_in_throws_on_suspend",
}
COMPONENT_ENTRIES = {
    "driver": "dev/ktchecker/providers/native/NativeThrowsFixtureMain.class",
    "fixture_model": "dev/ktchecker/providers/native/NativeThrowsFixtureCases.class",
    "provider": "dev/ktchecker/providers/native/KotlinNativeProvider.class",
    "runner": "dev/ktchecker/providers/runtime/IsolatedProcessRunner.class",
    "supervisor_loader": "dev/ktchecker/providers/runtime/OwnedProcessSupervisor.class",
    "snapshot": "dev/ktchecker/snapshot/SnapshotStore.class",
    "contracts": "dev/ktchecker/contracts/AnalysisInput.class",
}
JDK_FILES = {"bin/java", "release", "lib/modules", "lib/server/libjvm.dylib"}
PROCESS_RECEIPT_KEYS = {
    "phase", "snapshotId", "analysisKey", "status", "reasons", "exitCode", "executionConfirmed",
    "supervisorIdentity", "cancelled", "timedOut", "truncated", "metrics", "command", "outputDirectory",
    "stdoutDigest", "stderrDigest", "stdout", "stderr",
}
WRAPPER_FILES = {"gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"}
GRADLE_URL = "https://services.gradle.org/distributions/gradle-9.6.1-bin.zip"
GRADLE_SHA256 = "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"
XCODEBUILD = Path("/usr/bin/xcodebuild")
XCRUN = Path("/usr/bin/xcrun")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
COMMIT = re.compile(r"[0-9a-f]{40}(?:[0-9a-f]{24})?")
TASK_LINE = re.compile(r"^> Task (:[^ ]+)(?: (UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE|FAILED))?\s*$")
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
QUALIFICATION_REASON = "native_per_id_apple_precision_qualification_pending"
MAX_JSON = 16 * 1024 * 1024
MAX_ENROLLMENT = 4 * 1024 * 1024
MAX_JAR = 128 * 1024 * 1024
MAX_FILE = 2 * 1024 * 1024 * 1024
MAX_FILES = 40_000
MAX_SOURCE_FILES = 10_000
CLEANUP_SECONDS = 4.0
REAP_SECONDS = 2.0
POLL_SECONDS = 0.05
SOURCE_SCOPE = "tracked + untracked nonignored actual bytes, missing files, symlinks and executable bits"
DARWIN_SA_NOCLDWAIT = 0x0020


class DarwinSigaction(ctypes.Structure):
    # Stable type: ctypes.POINTER caches by class identity, so never recreate it
    # per held poll. No Darwin library call occurs merely by defining this type.
    _fields_ = [("handler", ctypes.c_void_p), ("mask", ctypes.c_uint32), ("flags", ctypes.c_int32)]


UNREAPED_CHILDREN: list[subprocess.Popen] = []
LIMITATIONS = [
    "Exact owner-enrolled installed launch bytes are not independent source-to-binary or fresh-compilation proof.",
    "Inputs require quiescent owner control; before/after hashes do not attest loaded memory or defeat ABA/same-UID replacement.",
    "First-Mac controls do not replace the comprehensive authored20 scheduled/release cohort.",
    "Observation-only suppression/demotion controls never count as passing or clean evidence.",
    "Selected component hashes and Xcode inventory do not qualify the external Native SDK/LLVM/libffi/plugin/cache closure.",
    "Trusted cooperative CI group cleanup does not prove escaped descendants absent or survive forced helper loss.",
    "Failed/unconfirmed collector publication is not success authority; metadata rewrites do not promise crash/hostile-FS durability.",
    "Nested runtime process confirmation and loaded supervisor identities are separate from outer command completion.",
    "No framework/header/linking/cinterop/Swift/simulator/device, target, rule precision, or release qualification.",
    "Raw originals, source text, paths, commands and arbitrary exception transcripts are private and not uploaded.",
]



PUBLIC_ERROR_CODES = frozenset("""
    native_throws_actual_apple_host_required
    native_throws_actual_tool_version_mismatch
    native_throws_alternative_fixture_prohibited
    native_throws_arguments_invalid
    native_throws_authored_cohort_invalid
    native_throws_authored_input_alias
    native_throws_authored_manifest_invalid
    native_throws_authored_manifest_mismatch
    native_throws_authored_sources_changed
    native_throws_authority_identity_invalid
    native_throws_authority_invalid
    native_throws_bounds_invalid
    native_throws_case_evidence_unavailable
    native_throws_case_output_limit
    native_throws_classpath_ambiguous
    native_throws_classpath_invalid
    native_throws_cleanup_deadline_invalid
    native_throws_cleanup_sleep_unverified
    native_throws_cleanup_unverified
    native_throws_cohort_final_tuple_unverified
    native_throws_cohort_or_case_output_limit
    native_throws_cohort_output_limit
    native_throws_cohort_selection_invalid
    native_throws_cohort_stopped_before_attempt
    native_throws_command_directory_not_fresh
    native_throws_command_interrupted
    native_throws_command_output_limit
    native_throws_command_start_failed
    native_throws_command_timeout
    native_throws_compatible_assertions_failed
    native_throws_component_class_invalid
    native_throws_component_or_supervisor_origin_invalid
    native_throws_component_origin_invalid
    native_throws_components_invalid
    native_throws_diagnostic_location_invalid
    native_throws_driver_actual_task_proof_invalid
    native_throws_driver_assertion_shape_invalid
    native_throws_driver_command_invalid
    native_throws_driver_exact_model_invalid
    native_throws_driver_frozen_sources_changed
    native_throws_driver_gaps_or_false_qualification
    native_throws_driver_identity_invalid
    native_throws_driver_not_object
    native_throws_driver_output_or_command_invalid
    native_throws_driver_private_paths_invalid
    native_throws_driver_process_evidence_unconfirmed
    native_throws_driver_process_flag_invalid
    native_throws_driver_provider_invalid
    native_throws_driver_provider_shape_invalid
    native_throws_driver_tool_artifacts_invalid
    native_throws_driver_validation_failed
    native_throws_enrolled_bytes_changed
    native_throws_enrolled_inventory_changed
    native_throws_evidence_invalid
    native_throws_expectation_invalid
    native_throws_external_owner_enrollment_required
    native_throws_file_changed
    native_throws_file_limit
    native_throws_first_mac_scope_or_budget_invalid
    native_throws_global_timeout
    native_throws_gradle_authority_invalid
    native_throws_gradle_cache_not_ready
    native_throws_gradle_cache_selection_invalid
    native_throws_gradle_distribution_unavailable
    native_throws_gradle_selection_invalid
    native_throws_group_quiescence_unavailable
    native_throws_group_quiescence_unverified
    native_throws_group_signal_unverified
    native_throws_input_alias
    native_throws_input_not_regular
    native_throws_installed_driver_controls
    native_throws_insufficient_cleanup_budget
    native_throws_inventory_ambiguous_or_limit
    native_throws_inventory_invalid
    native_throws_jar_entries_ambiguous
    native_throws_jar_entry_limit
    native_throws_java_authority_invalid
    native_throws_java_launcher_unavailable
    native_throws_java_release_mismatch
    native_throws_json_duplicate_key
    native_throws_json_invalid
    native_throws_json_limit
    native_throws_json_nonfinite
    native_throws_json_not_object
    native_throws_json_structure_limit
    native_throws_loaded_fixture_pin_mismatch
    native_throws_loaded_supervisor_mismatch
    native_throws_manifest_classpath_prohibited
    native_throws_metadata_probe_failed
    native_throws_named_error_assertions_failed
    native_throws_named_error_binding_invalid
    native_throws_native_airplane_mode_required
    native_throws_native_authority_invalid
    native_throws_native_dependencies_invalid
    native_throws_native_distribution_unavailable
    native_throws_native_selection_invalid
    native_throws_observation_not_preserved
    native_throws_observation_only_not_a_passing_control
    native_throws_outer_driver_failed
    native_throws_output_alias
    native_throws_output_monitor_unavailable
    native_throws_output_not_fresh_or_overlap
    native_throws_output_not_ignored_build
    native_throws_output_path_invalid
    native_throws_owned_class_shadowing
    native_throws_owned_multirelease_prohibited
    native_throws_owner_enrollment_changed
    native_throws_owner_enrollment_digest_mismatch
    native_throws_path_invalid
    native_throws_pin_invalid
    native_throws_preflight_or_evidence_unavailable
    native_throws_prior_cleanup_unverified
    native_throws_private_or_input_walk_unavailable
    native_throws_private_output_file_limit
    native_throws_private_output_unsafe
    native_throws_public_summary_invalid
    native_throws_public_summary_io_unavailable
    native_throws_public_summary_limit
    native_throws_relative_path_invalid
    native_throws_retained_originals_changed
    native_throws_retained_output_unavailable
    native_throws_same_job_context_changed
    native_throws_same_job_context_missing
    native_throws_selection_invalid
    native_throws_sigchld_auto_reap_prohibited
    native_throws_sigchld_kernel_disposition_unavailable
    native_throws_sigchld_ownership_unavailable
    native_throws_source_authority_invalid
    native_throws_source_changed_after_case
    native_throws_source_changed_at_final_verification
    native_throws_source_changed_before_case
    native_throws_source_commit_invalid
    native_throws_source_differs_from_owner_enrollment
    native_throws_source_identity_unavailable
    native_throws_source_inventory_invalid
    native_throws_source_inventory_limit
    native_throws_supervisor_authority_invalid
    native_throws_supervisor_binary_mismatch
    native_throws_supervisor_binary_target_invalid
    native_throws_supervisor_manifest_invalid
    native_throws_supervisor_origin_ambiguous
    native_throws_target_invalid
    native_throws_unreaped_process_guard_unavailable
    native_throws_wait_ownership_lost
    native_throws_wrapper_pin_mismatch
    native_throws_xcode_authority_invalid
    native_throws_xcode_pin_mismatch
    native_throws_xcode_tool_selection_invalid
""".split())


def public_code(value: object) -> str:
    return value if isinstance(value, str) and value in PUBLIC_ERROR_CODES else "native_throws_evidence_invalid"


class Problem(Exception):
    """Fixed public code only; never retain a raw exception message."""

    def __init__(self, code: object):
        super().__init__(public_code(code))


def require(condition: bool, code: str) -> None:
    if not condition:
        raise Problem(code)


def digest_bytes(raw: bytes) -> str:
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def canonical_digest(value: object) -> str:
    return digest_bytes(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"),
                                  allow_nan=False).encode("utf-8"))


def valid_digest(value: object) -> bool:
    return isinstance(value, str) and DIGEST.fullmatch(value) is not None


def integer(value: object, low: int = 0, high: int = MAX_FILE) -> bool:
    return type(value) is int and low <= value <= high


def keys(value: object, expected: set[str], code: str) -> dict:
    require(isinstance(value, dict) and set(value) == expected, code)
    return value


def strict_json(raw: bytes, limit: int = MAX_JSON) -> dict:
    require(0 < len(raw) <= limit, "native_throws_json_limit")

    def unique(pairs: list) -> dict:
        value = {}
        for key, item in pairs:
            try:
                key.encode("utf-8")
            except UnicodeError:
                raise Problem("native_throws_json_invalid") from None
            require(key not in value, "native_throws_json_duplicate_key")
            value[key] = item
        return value

    def nonfinite(_: str) -> None:
        raise Problem("native_throws_json_nonfinite")

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=unique, parse_constant=nonfinite)
    except (ValueError, UnicodeError, RecursionError):
        raise Problem("native_throws_json_invalid") from None
    require(isinstance(value, dict), "native_throws_json_not_object")
    pending, visited = [(value, 0)], 0
    while pending:
        item, depth = pending.pop()
        visited += 1
        require(depth <= 48 and visited <= 300_000, "native_throws_json_structure_limit")
        if isinstance(item, dict):
            pending.extend((entry, depth + 1) for entry in item.values())
        elif isinstance(item, list):
            pending.extend((entry, depth + 1) for entry in item)
        elif isinstance(item, float):
            require(math.isfinite(item), "native_throws_json_nonfinite")
        elif isinstance(item, str):
            try:
                item.encode("utf-8")
            except UnicodeError:
                raise Problem("native_throws_json_invalid") from None
    return value


@dataclass
class Budget:
    deadline: float

    @classmethod
    def start(cls, seconds: float) -> Budget:
        return cls(time.monotonic() + seconds)

    def remaining(self, reserve: float = 0) -> float:
        remaining = self.deadline - time.monotonic() - reserve
        require(remaining > 0, "native_throws_global_timeout")
        return remaining


def absolute(path: Path | str, *, directory: bool = False) -> Path:
    require(isinstance(path, (str, Path)), "native_throws_path_invalid")
    path = Path(path)
    require(path.is_absolute() and ".." not in path.parts and "\x00" not in str(path), "native_throws_path_invalid")
    canonical = path.resolve(strict=True)
    require(str(path) == str(canonical), "native_throws_input_alias")
    info = canonical.lstat()
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode), "native_throws_input_not_regular")
    return canonical


def relative(value: object) -> str:
    require(isinstance(value, str) and 0 < len(value) <= 2048 and "\\" not in value and ":" not in value
            and not any(ord(char) < 32 for char in value), "native_throws_relative_path_invalid")
    parts = value.split("/")
    require(all(part and part not in {".", ".."} for part in parts) and not PurePosixPath(value).is_absolute(),
            "native_throws_relative_path_invalid")
    return value


def selected(base: Path, value: str, *, directory: bool = False) -> Path:
    current = base
    for part in relative(value).split("/"):
        current /= part
        require(not current.is_symlink(), "native_throws_input_alias")
    info = current.lstat()
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode), "native_throws_input_not_regular")
    return current


def stamp(info: os.stat_result) -> tuple:
    return info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns


def file_pin(path: Path, budget: Budget, maximum: int = MAX_FILE) -> dict:
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and 0 <= info.st_size <= maximum, "native_throws_file_limit")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        require(stamp(os.fstat(fd)) == stamp(info), "native_throws_file_changed")
        hasher, total = hashlib.sha256(), 0
        while True:
            budget.remaining()
            data = os.read(fd, 1024 * 1024)
            if not data:
                break
            total += len(data)
            require(total <= maximum, "native_throws_file_limit")
            hasher.update(data)
        require(total == info.st_size and stamp(os.fstat(fd)) == stamp(info) and stamp(path.lstat()) == stamp(info),
                "native_throws_file_changed")
        return {"size": total, "digest": "sha256:" + hasher.hexdigest()}
    finally:
        os.close(fd)


def bounded_read(path: Path, budget: Budget, limit: int) -> bytes:
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and 0 <= info.st_size <= limit, "native_throws_file_limit")
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        require(stamp(os.fstat(fd)) == stamp(info), "native_throws_file_changed")
        data = bytearray()
        while len(data) <= limit:
            budget.remaining()
            part = os.read(fd, min(65_536, limit + 1 - len(data)))
            if not part:
                break
            data.extend(part)
        require(len(data) == info.st_size and len(data) <= limit and stamp(os.fstat(fd)) == stamp(info)
                and stamp(path.lstat()) == stamp(info), "native_throws_file_changed")
        return bytes(data)
    finally:
        os.close(fd)


def verify_pin(path: Path, pin: object, budget: Budget, maximum: int = MAX_FILE) -> dict:
    pin = keys(pin, {"size", "digest"}, "native_throws_pin_invalid")
    require(integer(pin["size"], high=maximum) and valid_digest(pin["digest"]), "native_throws_pin_invalid")
    require(file_pin(path, budget, maximum) == pin, "native_throws_enrolled_bytes_changed")
    return pin


def verify_absolute_pin(pin: object, budget: Budget, maximum: int = MAX_FILE) -> Path:
    pin = keys(pin, {"path", "size", "digest"}, "native_throws_pin_invalid")
    path = absolute(pin["path"])
    verify_pin(path, {key: pin[key] for key in ("size", "digest")}, budget, maximum)
    return path


def inventory(base: Path, prefixes: list[str], budget: Budget) -> dict:
    files = {}
    for prefix in prefixes:
        start = selected(base, prefix, directory=True)
        for current, directories, names in os.walk(start, followlinks=False, onerror=walk_error):
            budget.remaining()
            for name in directories:
                require(not (Path(current) / name).is_symlink(), "native_throws_input_alias")
            for name in names:
                path = Path(current) / name
                value = path.relative_to(base).as_posix()
                relative(value)
                require(value not in files and len(files) < MAX_FILES, "native_throws_inventory_ambiguous_or_limit")
                files[value] = file_pin(path, budget)
    return dict(sorted(files.items()))


def verify_inventory(base: Path, prefixes: list[str], pins: object, budget: Budget) -> dict:
    require(isinstance(pins, dict) and 0 < len(pins) <= MAX_FILES, "native_throws_inventory_invalid")
    for value, pin in pins.items():
        relative(value)
        require(isinstance(pin, dict) and set(pin) == {"size", "digest"} and integer(pin["size"])
                and valid_digest(pin["digest"]), "native_throws_inventory_invalid")
    observed = inventory(base, prefixes, budget)
    require(observed == pins, "native_throws_enrolled_inventory_changed")
    return observed


def sigchld_kernel_disposition() -> None:
    """Read-only Darwin64 sigaction observation; never clear or repair flags.

    Python getsignal cannot expose SA_NOCLDWAIT with SIG_DFL. Darwin's published
    user ABI is an 8-byte handler union, uint32 sigset_t mask, int32 flags. This
    narrow host assumption and libc inventory are not a platform qualification.
    Synthetic tests mock the API; they do not execute an Apple runtime/provider.
    """
    require(sys.platform == "darwin" and ctypes.sizeof(ctypes.c_void_p) == 8
            and ctypes.sizeof(ctypes.c_uint32) == ctypes.sizeof(ctypes.c_int32) == 4,
            "native_throws_sigchld_kernel_disposition_unavailable")

    require(ctypes.sizeof(DarwinSigaction) == 16 and DarwinSigaction.handler.offset == 0
            and DarwinSigaction.mask.offset == 8 and DarwinSigaction.flags.offset == 12,
            "native_throws_sigchld_kernel_disposition_unavailable")
    try:
        library = ctypes.CDLL("/usr/lib/libSystem.B.dylib", use_errno=True)
        observe = library.sigaction
        observe.argtypes = [ctypes.c_int, ctypes.POINTER(DarwinSigaction), ctypes.POINTER(DarwinSigaction)]
        observe.restype = ctypes.c_int
        action = DarwinSigaction()
        require(observe(int(signal.SIGCHLD), None, ctypes.byref(action)) == 0,
                "native_throws_sigchld_kernel_disposition_unavailable")
        require(action.handler is None and action.flags & DARWIN_SA_NOCLDWAIT == 0,
                "native_throws_sigchld_auto_reap_prohibited")
    except Problem:
        raise
    except (AttributeError, OSError, ValueError, TypeError):
        raise Problem("native_throws_sigchld_kernel_disposition_unavailable") from None


def process_gate() -> None:
    require(os.name == "posix" and all(hasattr(os, name) for name in
            ("waitid", "P_PID", "WEXITED", "WNOHANG", "WNOWAIT", "waitpid", "waitstatus_to_exitcode")),
            "native_throws_unreaped_process_guard_unavailable")
    require(all(callable(getattr(os, name)) for name in ("waitid", "waitpid", "waitstatus_to_exitcode"))
            and all(isinstance(getattr(os, name), int) and not isinstance(getattr(os, name), bool)
                    and getattr(os, name) > 0 for name in ("P_PID", "WEXITED", "WNOHANG", "WNOWAIT")),
            "native_throws_unreaped_process_guard_unavailable")
    require(signal.getsignal(signal.SIGCHLD) == signal.SIG_DFL, "native_throws_sigchld_ownership_unavailable")
    require(not UNREAPED_CHILDREN, "native_throws_prior_cleanup_unverified")
    sigchld_kernel_disposition()


def walk_error(_: OSError) -> None:
    raise Problem("native_throws_private_or_input_walk_unavailable")


def private_size(directory: Path, *, deadline: float | None = None,
                 timeout_code: str = "native_throws_global_timeout") -> int:
    require(directory.is_dir() and not directory.is_symlink(), "native_throws_private_output_unsafe")
    total, count = 0, 0
    for current, directories, names in os.walk(directory, followlinks=False, onerror=walk_error):
        if deadline is not None:
            require(time.monotonic() < deadline, timeout_code)
        count += 1 + len(names)
        require(count <= MAX_FILES, "native_throws_private_output_file_limit")
        for name in directories:
            require(not (Path(current) / name).is_symlink(), "native_throws_private_output_unsafe")
        for name in names:
            if deadline is not None:
                require(time.monotonic() < deadline, timeout_code)
            info = (Path(current) / name).lstat()
            require(stat.S_ISREG(info.st_mode), "native_throws_private_output_unsafe")
            total += info.st_size
    return total


def run_bounded(argv: list[str], *, cwd: Path, directory: Path, env: dict[str, str],
                budget: Budget, timeout: float, max_bytes: int,
                output_scopes: tuple[tuple[Path, int, str], ...] = ()) -> dict:
    """CI held-leader protocol only, not nested Native ownership/escape proof.

    Never call poll/wait/communicate/send_signal/terminate or Popen's context
    manager: those may reap before the final numeric-PGID signal. Lost wait
    ownership irrevocably ends the signal path and all further cohort launches.
    Four-second grace and final reap are reserved inside the capped deadline.
    File-backed output avoids pipe-drain hangs; limits are polled cooperative CI
    bounds, not a hostile host/filesystem/network resource sandbox.
    """
    process_gate()
    require(not directory.exists(), "native_throws_command_directory_not_fresh")
    directory.mkdir(mode=0o700)
    child_deadline = min(budget.deadline, time.monotonic() + timeout)
    useful_deadline = child_deadline - CLEANUP_SECONDS - REAP_SECONDS - 2 * POLL_SECONDS
    require(useful_deadline > time.monotonic(), "native_throws_insufficient_cleanup_budget")
    result = {"state": "not_started", "return_code": None, "execution_started": False,
              "elapsed_seconds": 0.0, "cleanup_seconds": 0.0, "cleanup_protocol_completed": False,
              "group_quiescence_observed": False,
              "escaped_descendants_proven_absent": False, "output_bytes": 0,
              "stdout_digest": None, "stderr_digest": None, "code": None,
              "stop_further_launches": False}
    stdout_path, stderr_path = directory / "stdout.log", directory / "stderr.log"
    started = time.monotonic()
    process = None
    owned = True
    interrupted = None

    def fail(code: str) -> None:
        result.update(state="failed", code=code)

    def held_exit() -> bool:
        nonlocal owned
        try:
            require(signal.getsignal(signal.SIGCHLD) == signal.SIG_DFL,
                    "native_throws_wait_ownership_lost")
            sigchld_kernel_disposition()
            value = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            require(value is None or value.si_pid in {0, process.pid}, "native_throws_wait_ownership_lost")
            return value is not None and value.si_pid == process.pid
        except (Problem, ChildProcessError, OSError, ValueError):
            owned = False
            raise Problem("native_throws_wait_ownership_lost") from None

    def group_signal(number: int) -> None:
        nonlocal owned
        require(owned, "native_throws_wait_ownership_lost")
        held_exit()  # Includes an exited-but-unreaped leader; never frees its PGID.
        try:
            os.killpg(process.pid, number)
        except ProcessLookupError:
            pass
        except OSError:
            owned = False
            raise Problem("native_throws_group_signal_unverified") from None

    def check_bounds(deadline: float, code: str) -> None:
        require(private_size(directory, deadline=deadline, timeout_code=code) <= max_bytes,
                "native_throws_command_output_limit")
        for path, limit, limit_code in output_scopes:
            require(private_size(path, deadline=deadline, timeout_code=code) <= limit, limit_code)

    def pause(deadline: float, *, cleanup: bool = False) -> None:
        # Clamp after the second clock read; the deadline may have just elapsed.
        delay = max(0.0, min(POLL_SECONDS, deadline - time.monotonic()))
        try:
            time.sleep(delay)
        except InterruptedError:
            pass
        except Exception:
            if not cleanup:
                raise
            fail("native_throws_cleanup_sleep_unverified")

    def cleanup() -> None:
        nonlocal owned
        cleanup_started = time.monotonic()
        try:
            if not owned:
                fail("native_throws_wait_ownership_lost")
                return
            group_signal(signal.SIGTERM)
            grace_deadline = time.monotonic() + CLEANUP_SECONDS
            late = grace_deadline > child_deadline - REAP_SECONDS
            # Never shorten the four-second grace merely because the leader exited.
            # Synchronous host/FS stalls remain an unqualified cooperative-CI limit.
            while time.monotonic() < grace_deadline:
                held_exit()
                pause(grace_deadline, cleanup=True)
            group_signal(signal.SIGKILL)
            if late:
                fail("native_throws_cleanup_deadline_invalid")
            reap_deadline = min(child_deadline, grace_deadline + REAP_SECONDS)
            while not held_exit():
                require(time.monotonic() < reap_deadline, "native_throws_cleanup_unverified")
                pause(reap_deadline, cleanup=True)
            pid, status = os.waitpid(process.pid, os.WNOHANG)
            require(pid == process.pid, "native_throws_cleanup_unverified")
            process.returncode = os.waitstatus_to_exitcode(status)
            owned = False  # Never perform another real PGID signal after this reap.
            result["return_code"] = process.returncode
            while True:
                require(time.monotonic() < reap_deadline, "native_throws_group_quiescence_unverified")
                try:
                    os.killpg(process.pid, 0)  # Read-only after reap; PGID reuse can only deny continuation.
                except ProcessLookupError:
                    result["group_quiescence_observed"] = True
                    break
                except OSError:
                    raise Problem("native_throws_group_quiescence_unavailable") from None
                pause(reap_deadline, cleanup=True)
            require(time.monotonic() < child_deadline, "native_throws_cleanup_deadline_invalid")
            result["cleanup_protocol_completed"] = True
        except Problem as error:
            fail(str(error))
        except Exception:
            owned = False
            fail("native_throws_wait_ownership_lost")
        finally:
            result["cleanup_seconds"] = round(time.monotonic() - cleanup_started, 3)
            if not result["cleanup_protocol_completed"]:
                UNREAPED_CHILDREN.append(process)  # Keep the object; no destructor poll during later work.
                result["stop_further_launches"] = True
            elif result["state"] == "running":
                result["state"] = "completed"

    out_fd, err_fd = None, None
    try:
        out_fd = os.open(stdout_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        err_fd = os.open(stderr_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        try:
            process = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                       stdout=out_fd, stderr=err_fd, close_fds=True, start_new_session=True)
            result["execution_started"] = True
            result["state"] = "running"
        except OSError:
            result.update(state="unavailable", code="native_throws_command_start_failed")
        if process is not None:
            try:
                while True:
                    check_bounds(useful_deadline, "native_throws_command_timeout")
                    if held_exit():
                        break
                    require(time.monotonic() < useful_deadline, "native_throws_command_timeout")
                    pause(useful_deadline)
            except Problem as error:
                fail(str(error))
            except Exception:
                fail("native_throws_output_monitor_unavailable")
            except BaseException as error:
                interrupted = error
                fail("native_throws_command_interrupted")
            finally:
                cleanup()  # Filesystem/monitor/sleep exceptions cannot skip owned cleanup.
    finally:
        for fd in (out_fd, err_fd):
            if fd is not None:
                os.close(fd)
    if interrupted is not None:
        raise interrupted
    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
    try:
        result["output_bytes"] = private_size(directory, deadline=budget.deadline)
        check_bounds(budget.deadline, "native_throws_global_timeout")
        result["stdout_digest"] = file_pin(stdout_path, budget, max_bytes)["digest"]
        result["stderr_digest"] = file_pin(stderr_path, budget, max_bytes)["digest"]
    except Problem as error:
        if result["state"] != "failed":
            fail(str(error))
    except (OSError, ValueError, RuntimeError):
        if result["state"] != "failed":
            fail("native_throws_retained_output_unavailable")
    return result


def command_ok(value: dict) -> bool:
    return value.get("state") == "completed" and value.get("execution_started") is True \
        and value.get("cleanup_protocol_completed") is True and value.get("group_quiescence_observed") is True \
        and type(value.get("return_code")) is int \
        and value["return_code"] == 0


def safe_environment(args: argparse.Namespace, java_home: Path) -> dict[str, str]:
    # Explicit allowlist: no JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS,
    # CLASSPATH/Gradle opts/agents/LD_PRELOAD/DYLD injections or inherited tokens.
    return {"PATH": str(java_home / "bin") + ":/usr/bin:/bin", "JAVA_HOME": str(java_home),
            "GRADLE_USER_HOME": str(args.gradle_user_home), "KONAN_DATA_DIR": str(args.konan_data_dir),
            "DEVELOPER_DIR": str(args.developer_dir), "HOME": str(args.home), "LANG": "C", "LC_ALL": "C",
            "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null"}


def source_identity(root: Path, git: Path, env: dict, directory: Path, budget: Budget,
                    output_scopes: tuple[tuple[Path, int, str], ...] = ()) -> dict:
    commands = (["rev-parse", "HEAD"], ["ls-files", "-c", "-o", "--exclude-standard", "-z"],
                ["status", "--porcelain", "--untracked-files=normal"])
    outputs = []
    for index, args in enumerate(commands):
        command_dir = directory / f"git-{index}"
        result = run_bounded([str(git), "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false", *args],
                             cwd=root, directory=command_dir, env=env, budget=budget,
                             timeout=min(30, budget.remaining()), max_bytes=2 * 1024 * 1024,
                             output_scopes=output_scopes)
        require(command_ok(result), "native_throws_source_identity_unavailable")
        outputs.append(bounded_read(command_dir / "stdout.log", budget, 2 * 1024 * 1024))
    commit = outputs[0].decode("ascii").strip()
    require(COMMIT.fullmatch(commit) is not None, "native_throws_source_commit_invalid")
    require(not outputs[1] or outputs[1].endswith(b"\x00"), "native_throws_source_inventory_invalid")
    paths = sorted(set(outputs[1].split(b"\x00")[:-1]))
    require(all(paths), "native_throws_source_inventory_invalid")
    require(len(paths) <= MAX_SOURCE_FILES, "native_throws_source_inventory_limit")
    entries = []
    for encoded in paths:
        budget.remaining()
        value = os.fsdecode(encoded)
        relative(value)
        path = root / value
        if path.is_symlink():
            target = os.fsencode(os.readlink(path))
            entry = {"path": value, "kind": "symlink", "size": len(target), "digest": digest_bytes(target)}
        elif path.is_file():
            pin = file_pin(path, budget)
            entry = {"path": value, "kind": "file", **pin, "executable": bool(path.stat().st_mode & 0o111)}
        elif not path.exists():
            entry = {"path": value, "kind": "missing"}
        else:
            entry = {"path": value, "kind": "unsupported_directory"}
        entries.append(entry)
    return {"commit": commit, "dirty": bool(outputs[2]), "file_count": len(entries),
            "working_tree_digest": canonical_digest(entries), "digest_scope": SOURCE_SCOPE,
            "unsupported_directories": sum(item["kind"] == "unsupported_directory" for item in entries)}


def ci_context() -> dict:
    names = {"run_id": "GITHUB_RUN_ID", "run_attempt": "GITHUB_RUN_ATTEMPT", "job": "GITHUB_JOB"}
    result = {key: os.environ.get(name) for key, name in names.items()}
    require(all(isinstance(result[key], str) and re.fullmatch(r"[0-9]{1,30}", result[key])
                for key in ("run_id", "run_attempt")) and isinstance(result["job"], str)
            and re.fullmatch(r"[A-Za-z0-9_-]{1,100}", result["job"]), "native_throws_same_job_context_missing")
    return result


def validate_source(value: object) -> dict:
    value = keys(value, {"commit", "dirty", "file_count", "working_tree_digest", "digest_scope", "unsupported_directories"},
                 "native_throws_source_authority_invalid")
    require(isinstance(value["commit"], str) and COMMIT.fullmatch(value["commit"]) is not None
            and type(value["dirty"]) is bool and integer(value["file_count"], 1, MAX_SOURCE_FILES)
            and valid_digest(value["working_tree_digest"]) and value["digest_scope"] == SOURCE_SCOPE
            and type(value["unsupported_directories"]) is int and value["unsupported_directories"] == 0,
            "native_throws_source_authority_invalid")
    return value


def java_input(value: object, home: Path, major: int, budget: Budget) -> dict:
    value = keys(value, {"home", "version", "files"}, "native_throws_java_authority_invalid")
    require(value["home"] == str(home) and isinstance(value["version"], str)
            and re.fullmatch(str(major) + r"(?:\.[0-9]+)*(?:[-+][A-Za-z0-9._+-]+)?", value["version"]),
            "native_throws_java_authority_invalid")
    files = keys(value["files"], JDK_FILES, "native_throws_java_authority_invalid")
    for name, pin in files.items():
        verify_pin(selected(home, name), pin, budget)
    release = bounded_read(selected(home, "release"), budget, 65_536).decode("utf-8")
    versions = re.findall(r'^JAVA_VERSION="([^"\r\n]+)"$', release, re.MULTILINE)
    require(versions == [value["version"]], "native_throws_java_release_mismatch")
    require(os.access(selected(home, "bin/java"), os.X_OK), "native_throws_java_launcher_unavailable")
    return value


def base36(value: int) -> str:
    digits, text = "0123456789abcdefghijklmnopqrstuvwxyz", ""
    while value:
        value, remainder = divmod(value, 36)
        text = digits[remainder] + text
    return text or "0"


def wrapper_cache(user_home: Path) -> Path:
    url_hash = base36(int.from_bytes(hashlib.md5(GRADLE_URL.encode("utf-8"), usedforsecurity=False).digest(), "big"))
    return user_home / "wrapper/dists/gradle-9.6.1-bin" / url_hash


def properties(raw: str) -> dict:
    result = {}
    for original in raw.splitlines():
        line = original.strip()
        if not line or line.startswith(("#", "!")):
            continue
        name, separator, value = line.partition("=")
        if separator:
            result[name.strip()] = value.strip().replace("\\:", ":")
    return result


def gradle_input(value: object, args: argparse.Namespace, root: Path, budget: Budget) -> dict:
    value = keys(value, {"wrapper", "user_home", "distribution_home", "wrapper_files", "distribution_files", "cache_marker"},
                 "native_throws_gradle_authority_invalid")
    require(value["wrapper"] == str(args.gradle) and value["user_home"] == str(args.gradle_user_home)
            and args.gradle == root / "gradlew", "native_throws_gradle_selection_invalid")
    for name, pin in keys(value["wrapper_files"], WRAPPER_FILES, "native_throws_gradle_authority_invalid").items():
        verify_pin(selected(root, name), pin, budget)
    profile = properties(bounded_read(selected(root, "gradle/wrapper/gradle-wrapper.properties"), budget, 65_536).decode("utf-8"))
    required = {"distributionUrl": GRADLE_URL, "distributionSha256Sum": GRADLE_SHA256,
                "distributionBase": "GRADLE_USER_HOME", "distributionPath": "wrapper/dists",
                "zipStoreBase": "GRADLE_USER_HOME", "zipStorePath": "wrapper/dists"}
    require(all(profile.get(key) == item for key, item in required.items()), "native_throws_wrapper_pin_mismatch")
    cache = wrapper_cache(args.gradle_user_home)
    home = absolute(value["distribution_home"], directory=True)
    require(home == cache / "gradle-9.6.1", "native_throws_gradle_cache_selection_invalid")
    marker = verify_absolute_pin(value["cache_marker"], budget, 512)
    require(marker == cache / "gradle-9.6.1-bin.zip.ok", "native_throws_gradle_cache_not_ready")
    observed = verify_inventory(home, ["bin", "lib"], value["distribution_files"], budget)
    require("bin/gradle" in observed and "lib/gradle-launcher-9.6.1.jar" in observed and os.access(args.gradle, os.X_OK),
            "native_throws_gradle_distribution_unavailable")
    return value


def native_prefixes(targets: list[str]) -> list[str]:
    return ["bin", "tools", "konan", "klib/common", *["klib/platform/" + TARGETS[target] for target in targets]]


def native_input(value: object, args: argparse.Namespace, budget: Budget) -> dict:
    value = keys(value, {"home", "data_dir", "targets", "files", "dependencies"}, "native_throws_native_authority_invalid")
    targets = value["targets"]
    require(value["home"] == str(args.native_home) and value["data_dir"] == str(args.konan_data_dir)
            and isinstance(targets, list) and 1 <= len(targets) <= 2
            and all(isinstance(target, str) and target in TARGETS for target in targets)
            and len(targets) == len(set(targets)) and set(args.target) <= set(targets),
            "native_throws_native_selection_invalid")
    observed = verify_inventory(args.native_home, native_prefixes(targets), value["files"], budget)
    require("bin/konanc" in observed and "konan/konan.properties" in observed and os.access(args.native_home / "bin/konanc", os.X_OK),
            "native_throws_native_distribution_unavailable")
    text = bounded_read(selected(args.native_home, "konan/konan.properties"), budget, 1024 * 1024).decode("utf-8")
    airplane = re.findall(r"^\s*airplaneMode\s*=\s*(\S+)\s*$", text, re.MULTILINE)
    require(airplane == ["true"], "native_throws_native_airplane_mode_required")
    config = properties(text)
    host = "macos_arm64" if platform.machine().lower() in {"arm64", "aarch64"} else "macos_x64"
    wanted = {"llvm": "llvmHome." + host, "libffi": "libffiDir." + host,
              **{"sysroot-" + target: "targetSysRoot." + TARGETS[target] for target in targets}}
    dependencies = keys(value["dependencies"], set(wanted), "native_throws_native_dependencies_invalid")
    for kind, key in wanted.items():
        package = config.get(key, "")
        for _ in range(16):
            if re.fullmatch(r"\$[A-Za-z0-9_.-]+", package):
                package = config.get(package[1:], "")
            else:
                break
        require(re.fullmatch(r"[A-Za-z0-9_.+-]{1,200}", package) is not None and package not in {".", ".."}
                and dependencies[kind] == package, "native_throws_native_dependencies_invalid")
        selected(args.konan_data_dir, "dependencies/" + package, directory=True)
    return value


def xcode_input(value: object, args: argparse.Namespace, budget: Budget) -> dict:
    value = keys(value, {"developer_dir", "version", "build", "iphoneos_sdk", "iphonesimulator_sdk", "files"},
                 "native_throws_xcode_authority_invalid")
    require(value["developer_dir"] == str(args.developer_dir) and value["version"] == "26.4.1"
            and value["build"] == "17E202" and value["iphoneos_sdk"] == "26.4" and value["iphonesimulator_sdk"] == "26.4",
            "native_throws_xcode_pin_mismatch")
    expected = {"xcodebuild": XCODEBUILD, "xcrun": XCRUN,
                "info_plist": args.developer_dir.parent / "Info.plist"}
    for name, pin in keys(value["files"], set(expected), "native_throws_xcode_authority_invalid").items():
        require(verify_absolute_pin(pin, budget) == expected[name], "native_throws_xcode_tool_selection_invalid")
    return value


def jar_bytes(archive: zipfile.ZipFile, name: str, limit: int) -> bytes:
    item = archive.getinfo(name)
    require(not item.is_dir() and 0 <= item.file_size <= limit, "native_throws_jar_entry_limit")
    with archive.open(item) as handle:
        raw = handle.read(limit + 1)
    require(len(raw) == item.file_size and len(raw) <= limit, "native_throws_jar_entry_limit")
    return raw


def classpath_input(value: dict, budget: Budget) -> dict:
    rows = value["classpath"]
    require(isinstance(rows, list) and 4 <= len(rows) <= 64, "native_throws_classpath_invalid")
    components = keys(value["components"], set(COMPONENT_ENTRIES), "native_throws_components_invalid")
    for row in components.values():
        row = keys(row, {"classpath_index", "digest"}, "native_throws_components_invalid")
        require(integer(row["classpath_index"], high=len(rows) - 1) and valid_digest(row["digest"]),
                "native_throws_components_invalid")
    paths, inodes, owned_classes, selected_entries = [], set(), {}, {}
    runtime_index = None
    supervisor = None
    for index, row in enumerate(rows):
        path = verify_absolute_pin(row, budget, MAX_JAR)
        require(path.suffix == ".jar" and ":" not in str(path) and path not in paths, "native_throws_classpath_ambiguous")
        info = path.stat()
        require((info.st_dev, info.st_ino) not in inodes, "native_throws_classpath_ambiguous")
        inodes.add((info.st_dev, info.st_ino))
        paths.append(path)
        with zipfile.ZipFile(path) as archive:
            items = archive.infolist()
            names = [item.filename for item in items]
            require(len(items) <= 100_000 and len(names) == len(set(names)), "native_throws_jar_entries_ambiguous")
            for name in names:
                relative(name.rstrip("/"))
            if "META-INF/MANIFEST.MF" in names:
                text = jar_bytes(archive, "META-INF/MANIFEST.MF", 65_536).decode("utf-8")
                unfolded = re.sub(r"\r?\n ", "", text)
                require(re.search(r"^Class-Path\s*:", unfolded, re.I | re.M) is None,
                        "native_throws_manifest_classpath_prohibited")
            for name in names:
                if re.fullmatch(r"META-INF/versions/[0-9]+/dev/ktchecker/.+", name):
                    raise Problem("native_throws_owned_multirelease_prohibited")
                if name.startswith("dev/ktchecker/") and name.endswith(".class"):
                    require(name not in owned_classes, "native_throws_owned_class_shadowing")
                    owned_classes[name] = index
            for role, entry in COMPONENT_ENTRIES.items():
                if entry in names:
                    raw = jar_bytes(archive, entry, 4 * 1024 * 1024)
                    require(raw.startswith(b"\xca\xfe\xba\xbe") and len(raw) >= 8
                            and 45 <= int.from_bytes(raw[6:8], "big") <= 65, "native_throws_component_class_invalid")
                    selected_entries[role] = {"classpath_index": index, "digest": digest_bytes(raw)}
                    if role == "fixture_model":
                        require(MANIFEST_DIGEST.encode("ascii") in raw, "native_throws_loaded_fixture_pin_mismatch")
                    if role == "supervisor_loader":
                        runtime_index = index
            if NATIVE_RESOURCE + "manifest" in names:
                require(supervisor is None, "native_throws_supervisor_origin_ambiguous")
                lines = jar_bytes(archive, NATIVE_RESOURCE + "manifest", 512).decode("utf-8").split("\n")
                require(len(lines) == 5 and lines[-1] == "" and lines[0] == PROTOCOL and lines[1] in
                        {"macos-aarch64", "macos-x86_64"} and all(valid_digest(item) for item in lines[2:4]),
                        "native_throws_supervisor_manifest_invalid")
                binary_name = NATIVE_RESOURCE + lines[1] + "/supervisor"
                binary = jar_bytes(archive, binary_name, 1_048_576)
                require(binary and digest_bytes(binary) == lines[3], "native_throws_supervisor_binary_mismatch")
                cpu = 0x100000C if lines[1] == "macos-aarch64" else 0x1000007
                require(len(binary) >= 16 and binary[:4] == b"\xcf\xfa\xed\xfe"
                        and int.from_bytes(binary[4:8], "little") == cpu and int.from_bytes(binary[12:16], "little") == 2,
                        "native_throws_supervisor_binary_target_invalid")
                supervisor = ({"protocolVersion": lines[0], "target": lines[1], "sourceDigest": lines[2], "binaryDigest": lines[3]}, index)
    require(selected_entries == components and runtime_index is not None and supervisor is not None
            and supervisor[1] == runtime_index, "native_throws_component_or_supervisor_origin_invalid")
    require(components["driver"]["classpath_index"] == components["fixture_model"]["classpath_index"]
            == components["provider"]["classpath_index"] and components["runner"]["classpath_index"] == runtime_index,
            "native_throws_component_origin_invalid")
    target = "macos-aarch64" if platform.machine().lower() in {"arm64", "aarch64"} else "macos-x86_64"
    identity = keys(value["supervisor_identity"], {"protocolVersion", "target", "sourceDigest", "binaryDigest"},
                    "native_throws_supervisor_authority_invalid")
    require(identity == supervisor[0] and identity["target"] == target, "native_throws_loaded_supervisor_mismatch")
    return {"ordered_classpath_digest": canonical_digest(rows), "component_digest": canonical_digest(components),
            "supervisor_identity": identity}


def fixture_input(root: Path, fixture: Path, budget: Budget) -> tuple[dict, dict]:
    require(fixture == root / FIXTURE, "native_throws_alternative_fixture_prohibited")
    raw = bounded_read(selected(fixture, "source-manifest.json"), budget, MAX_ENROLLMENT)
    require(digest_bytes(raw) == MANIFEST_DIGEST, "native_throws_authored_manifest_mismatch")
    manifest = strict_json(raw, MAX_ENROLLMENT)
    require(manifest.get("schemaVersion") == 1 and type(manifest["schemaVersion"]) is int
            and manifest.get("cohortId") == COHORT and manifest.get("kotlinVersion") == VERSION
            and manifest.get("targets") == list(TARGETS) and manifest.get("sourceProvenance") == "authored_unexecuted",
            "native_throws_authored_manifest_invalid")
    rows = manifest.get("files")
    require(isinstance(rows, list) and len(rows) == 28, "native_throws_authored_manifest_invalid")
    pins = {}
    for row in rows:
        row = keys(row, {"path", "size", "digest"}, "native_throws_authored_manifest_invalid")
        name = relative(row["path"])
        require(name not in pins, "native_throws_authored_manifest_invalid")
        pins[name] = {key: row[key] for key in ("size", "digest")}
    actual = {}
    for current, directories, names in os.walk(fixture, followlinks=False, onerror=walk_error):
        budget.remaining()
        for name in directories:
            require(not (Path(current) / name).is_symlink(), "native_throws_authored_input_alias")
        for name in names:
            path = Path(current) / name
            name = path.relative_to(fixture).as_posix()
            if name != "source-manifest.json":
                actual[name] = file_pin(path, budget)
    require(actual == pins, "native_throws_authored_sources_changed")
    cohort = strict_json(bounded_read(selected(fixture, "cohort.json"), budget, MAX_ENROLLMENT), MAX_ENROLLMENT)
    cases = cohort.get("cases")
    require(cohort.get("cohortId") == COHORT and cohort.get("kotlinVersion") == VERSION
            and cohort.get("sourceProvenance") == "authored_unexecuted" and isinstance(cases, list) and len(cases) == 20,
            "native_throws_authored_cohort_invalid")
    require(Counter(case.get("expectation") for case in cases) == {"named_error": 7, "compatible": 11, "observation_only": 2}
            and [(case["name"], case["expectation"], case["upstreamId"]) for case in cases] == list(AUTHORED_CONTROLS)
            and all(RULES.get(case["upstreamId"]) == case["ruleId"] for case in cases),
            "native_throws_authored_cohort_invalid")
    return cohort, pins


def enrollment_input(args: argparse.Namespace, root: Path, budget: Budget) -> dict:
    raw = bounded_read(args.authority, budget, MAX_ENROLLMENT)
    require(digest_bytes(raw) == args.authority_digest, "native_throws_owner_enrollment_digest_mismatch")
    value = strict_json(raw, MAX_ENROLLMENT)
    keys(value, {"schema_version", "evidence_kind", "context", "source", "fixture_manifest_digest", "git", "classpath", "components",
                 "java21", "java25", "gradle", "native", "xcode", "supervisor_identity"}, "native_throws_authority_invalid")
    require(type(value["schema_version"]) is int and value["schema_version"] == 1
            and value["evidence_kind"] == "owner_enrolled_native_throws_build" and value["context"] == ci_context()
            and value["fixture_manifest_digest"] == MANIFEST_DIGEST, "native_throws_authority_identity_invalid")
    validate_source(value["source"])
    return value


def verify_enrollment(value: dict, args: argparse.Namespace, root: Path, budget: Budget) -> dict:
    require(digest_bytes(bounded_read(args.authority, budget, MAX_ENROLLMENT)) == args.authority_digest,
            "native_throws_owner_enrollment_changed")
    require(value["context"] == ci_context(), "native_throws_same_job_context_changed")
    verify_absolute_pin(value["git"], budget)
    launch = classpath_input(value, budget)
    java_input(value["java21"], args.broker_java_home, 21, budget)
    java_input(value["java25"], args.project_java_home, 25, budget)
    gradle_input(value["gradle"], args, root, budget)
    native_input(value["native"], args, budget)
    xcode_input(value["xcode"], args, budget)
    fixture_input(root, args.fixture, budget)
    return launch


def metadata_preflight(value: dict, args: argparse.Namespace, root: Path, directory: Path, budget: Budget,
                       output_scopes: tuple[tuple[Path, int, str], ...] = ()) -> None:
    env21 = safe_environment(args, args.broker_java_home)
    env25 = safe_environment(args, args.project_java_home)
    probes = [
        ("java21", [str(args.broker_java_home / "bin/java"), "-version"], env21,
         lambda text: java_version(text) == value["java21"]["version"]),
        ("java25", [str(args.project_java_home / "bin/java"), "-version"], env25,
         lambda text: java_version(text) == value["java25"]["version"]),
        ("gradle", [str(args.gradle), "--offline", "--no-daemon", "--version"], env25,
         lambda text: re.findall(r"^Gradle ([0-9.]+)$", text, re.M) == ["9.6.1"]),
        ("native", [str(args.native_home / "bin/konanc"), "-version"], env25, native_version),
        ("xcode", [str(XCODEBUILD), "-version"], env25,
         lambda text: re.findall(r"^Xcode ([0-9.]+)$", text, re.M) == ["26.4.1"]
         and re.findall(r"^Build version ([A-Za-z0-9.]+)$", text, re.M) == ["17E202"]),
        *[(sdk, [str(XCRUN), "--sdk", sdk, "--show-sdk-version"], env25,
           lambda text: text.strip() == "26.4") for sdk in ("iphoneos", "iphonesimulator")],
    ]
    for name, argv, env, accepts in probes:
        target = directory / name
        result = run_bounded(argv, cwd=root, directory=target, env=env, budget=budget,
                             timeout=min(60, budget.remaining()), max_bytes=1024 * 1024, output_scopes=output_scopes)
        require(command_ok(result), "native_throws_metadata_probe_failed")
        text = (bounded_read(target / "stdout.log", budget, 1024 * 1024) + b"\n"
                + bounded_read(target / "stderr.log", budget, 1024 * 1024)).decode("utf-8")
        require(accepts(text), "native_throws_actual_tool_version_mismatch")


def java_version(text: str) -> str | None:
    matches = re.findall(r'(?:openjdk|java) version "([^"\r\n]+)"', text)
    return matches[0] if len(matches) == 1 else None


def native_version(text: str) -> bool:
    return re.search(r"(?:kotlinc-native|Kotlin/Native(?: compiler)?(?: version)?)\s+2\.4\.21(?:\s|$|\()",
                     text, re.I) is not None


def scope(target: str) -> dict:
    require(target in TARGETS, "native_throws_target_invalid")
    return {"target": target, "sourceSet": "iosMain", "module": ":", "compilation": "main"}


def tasks(target: str) -> list[str]:
    scope(target)
    return sorted([":compileCommonMainKotlinMetadata", ":compileKotlin" + target[0].upper() + target[1:]])


def model_binding(snapshot: str, case: dict, target: str, pins: dict) -> dict:
    members = {"src/commonMain/kotlin/CommonBoundary.kt": "commonMain", case["sourcePath"]: "iosMain"}
    source_files = [{"path": name, "digest": pins[name]["digest"], "size": pins[name]["size"]} for name in members]
    model = {"producer": "authored-native-throws-model/v1", "snapshotId": snapshot, "sourceManifestDigest": MANIFEST_DIGEST,
             "scope": scope(target), "case": case, "members": source_files, "sourceSetByFile": members,
             "requiredTaskPaths": tasks(target)}
    return {"scope": scope(target), "modelDigest": canonical_digest(model), "sourceSetByFile": members,
            "requiredTaskPaths": tasks(target), "provenance": "explicit_fixture_producer_model_not_general_project_import"}


def diagnostics(stdout: str, stderr: str) -> tuple[list, list, bool]:
    result, observed = [], []
    prefix = re.compile(r"^([ewi]):\s+(.+?):(\d+):(\d+)\s*:?\s*(.*)$")
    old = re.compile(r"^(error|warning|info):\s+(.+?):\s*\((\d+),\s*(\d+)\):\s*(.*)$")
    direct = re.compile(r"^(.+?):(\d+):(\d+):\s*(error|warning|info):\s*(.*)$")
    global_line = re.compile(r"^([ewi]|error|warning|info):\s*(.*)$")
    named = re.compile(r"^\[([A-Z][A-Z0-9_]*)]\s*(.*)$")
    severity = {"e": "error", "error": "error", "w": "warning", "warning": "warning", "i": "info", "info": "info"}
    for original in (stdout + "\n" + stderr).splitlines():
        line = ANSI.sub("", original).rstrip()
        task = TASK_LINE.fullmatch(line)
        if task:
            observed.append({"path": task[1], "outcome": task[2] or "EXECUTED"})
            continue
        first, legacy, raw, general = prefix.fullmatch(line), old.fullmatch(line), direct.fullmatch(line), global_line.fullmatch(line)
        if first or legacy:
            match = first or legacy
            level, path, row, column, message = match.groups()
        elif raw:
            path, row, column, level, message = raw.groups()
        elif general:
            level, message = general.groups()
            path, row, column = None, None, None
        else:
            continue
        name = named.fullmatch(message)
        require(row is None or 1 <= int(row) <= 2_147_483_647, "native_throws_diagnostic_location_invalid")
        require(column is None or 1 <= int(column) <= 2_147_483_647, "native_throws_diagnostic_location_invalid")
        result.append({"name": name[1] if name else None, "severity": severity[level], "message": name[2] if name else message,
                       "path": path, "line": int(row) if row else None, "column": int(column) - 1 if column else None})
    return result, observed, any(line.startswith("BUILD SUCCESSFUL") for line in stdout.splitlines())


def private_reported_path(value: object, root: Path) -> str | None:
    if not isinstance(value, str):
        return None
    try:
        if value.startswith("file:"):
            uri = urlparse(value)
            if uri.netloc or uri.query or uri.fragment:
                return None
            path = Path(unquote(uri.path))
        else:
            path = Path(value)
        if path.is_absolute():
            return path.relative_to(root).as_posix() if ".." not in path.parts else None
        return relative(value)
    except (ValueError, Problem):
        return None


def metrics_valid(value: object) -> bool:
    return isinstance(value, dict) and set(value) == {"elapsedMillis", "cpuMillis", "peakRssBytes", "outputBytes"} \
        and all(integer(item, high=1_000_000_000_000) or item is None and key in {"cpuMillis", "peakRssBytes"}
                for key, item in value.items())


def expected_artifacts(authority: dict, args: argparse.Namespace, target: str) -> dict:
    result = {}
    wanted = native_prefixes([target])
    for name, pin in authority["native"]["files"].items():
        if any(name.startswith(prefix + "/") for prefix in wanted):
            path = str(args.native_home / name)
            result[path] = {"coordinate": "org.jetbrains.kotlin.native:" + name + ":2.4.21", "digest": pin["digest"], "path": path}
    for name, pin in authority["gradle"]["wrapper_files"].items():
        path = str(args.gradle.parent / name)
        result[path] = {"coordinate": "trusted-gradle:" + Path(name).name, "digest": pin["digest"], "path": path}
    for name, pin in authority["java25"]["files"].items():
        path = str(args.project_java_home / name)
        result[path] = {"coordinate": "project-jdk:" + name, "digest": pin["digest"], "path": path}
    return result


def provider_command(args: argparse.Namespace, case: dict, target: str, root: Path, output: Path) -> list[str]:
    return [str(args.gradle), "--no-daemon", "--no-configuration-cache", "--console=plain", "--rerun-tasks", "--max-workers=1",
            "-p", str(root), "--project-cache-dir", str(output / "project-cache"), "--init-script", str(output / "relocate.gradle"),
            "-PfixtureBuildDir=" + str(output / "build"), "-Pkotlin.project.persistent.dir=" + str(output / "kotlin-state"),
            "-Dkotlin.project.persistent.dir=" + str(output / "kotlin-state"), "-PfixtureNegativeCase=" + case["name"], *tasks(target),
            "--offline", "-Dorg.gradle.java.home=" + str(args.project_java_home), "-Pkotlin.native.home=" + str(args.native_home),
            "-Porg.gradle.java.installations.auto-download=false", "-Porg.gradle.java.installations.auto-detect=false",
            "-Porg.gradle.java.installations.paths=" + str(args.project_java_home)]


def validate_driver(value: object, case: dict, target: str, authority: dict, args: argparse.Namespace,
                    pins: dict, case_directory: Path) -> tuple[list[str], dict]:
    """Independent structural/output binding; all originals remain private."""
    errors = []
    public = {"snapshot_id": None, "model_digest": None, "provider_process_execution_confirmed": False,
              "native_compilation_task_execution_observed": False, "loaded_supervisor_identity": None,
              "finding_count": 0, "raw_diagnostic_count": 0, "private_gap_count": 0,
              "observation_task_proof_missing": False}
    if not isinstance(value, dict):
        return ["native_throws_driver_not_object"], public
    snapshot = value.get("snapshotId")
    if type(value.get("schemaVersion")) is not int or value["schemaVersion"] != 1 or value.get("cohortId") != COHORT \
            or value.get("case") != case["name"] or value.get("target") != target or not valid_digest(snapshot) \
            or value.get("releaseQualified") is not False or value.get("targetQualificationEstablished") is not False \
            or value.get("sourceManifestDigest") != MANIFEST_DIGEST or value.get("requestedRuleIds") != [case["ruleId"]]:
        errors.append("native_throws_driver_identity_invalid")
    if valid_digest(snapshot):
        public["snapshot_id"] = snapshot
        binding = model_binding(snapshot, case, target, pins)
        if value.get("modelBinding") != binding:
            errors.append("native_throws_driver_exact_model_invalid")
        else:
            public["model_digest"] = binding["modelDigest"]
    rows = value.get("processReceipts")
    process = None
    confirmed = isinstance(rows, list) and len(rows) == 3 and [row.get("phase") for row in rows if isinstance(row, dict)] \
        == ["jdk-version", "native-version", "provider"]
    if confirmed:
        for row in rows:
            if set(row) != PROCESS_RECEIPT_KEYS or row.get("status") != "executed" or row.get("executionConfirmed") is not True or row.get("reasons") != [] \
                    or row.get("supervisorIdentity") != authority["supervisor_identity"] or row.get("snapshotId") != snapshot \
                    or not valid_digest(row.get("analysisKey")) or any(row.get(key) is not False for key in ("cancelled", "timedOut", "truncated")) \
                    or not metrics_valid(row.get("metrics")) or type(row.get("exitCode")) is not int \
                    or not isinstance(row.get("stdout"), str) or not isinstance(row.get("stderr"), str) \
                    or not isinstance(row.get("command"), list) or not all(isinstance(part, str) for part in row["command"]) \
                    or not isinstance(row.get("outputDirectory"), str):
                confirmed = False
                break
            if row.get("stdoutDigest") != digest_bytes(row["stdout"].encode("utf-8")) \
                    or row.get("stderrDigest") != digest_bytes(row["stderr"].encode("utf-8")) \
                    or row["metrics"]["outputBytes"] != len((row["stdout"] + row["stderr"]).encode("utf-8")):
                confirmed = False
        if confirmed:
            java, native, process = rows
            if java["exitCode"] != 0 or native["exitCode"] != 0 \
                    or java.get("command") != [str(args.project_java_home / "bin/java"), "-version"] \
                    or native.get("command") != [str(args.native_home / "bin/konanc"), "-version"] \
                    or java_version(java["stdout"] + "\n" + java["stderr"]) != authority["java25"]["version"] \
                    or not native_version(native["stdout"] + "\n" + native["stderr"]):
                confirmed = False
    if not confirmed:
        errors.append("native_throws_driver_process_evidence_unconfirmed")
    else:
        public["provider_process_execution_confirmed"] = value.get("providerProcessExecutionConfirmed") is True
        public["loaded_supervisor_identity"] = authority["supervisor_identity"]
        if not public["provider_process_execution_confirmed"]:
            errors.append("native_throws_driver_process_flag_invalid")
    provider = value.get("providerResult")
    if not isinstance(provider, dict) or provider.get("providerId") != "kotlin-native" or provider.get("providerVersion") != VERSION:
        return errors + ["native_throws_driver_provider_invalid"], public
    findings, raw, gaps = provider.get("findings"), provider.get("rawDiagnostics"), provider.get("reasons")
    if not isinstance(findings, list) or not isinstance(raw, list) or not isinstance(gaps, list):
        return errors + ["native_throws_driver_provider_shape_invalid"], public
    public.update(finding_count=len(findings), raw_diagnostic_count=len(raw), private_gap_count=len(gaps))
    artifacts = value.get("artifactIdentities")
    expected = expected_artifacts(authority, args, target)
    if not isinstance(artifacts, list) or len(artifacts) != len(expected) or any(not isinstance(row, dict) for row in artifacts) \
            or any(not isinstance(row.get("path"), str) for row in artifacts) \
            or {row.get("path"): row for row in artifacts} != expected:
        errors.append("native_throws_driver_tool_artifacts_invalid")
    if process is None:
        return errors, public
    command = process.get("command")
    if not isinstance(command, list) or not all(isinstance(part, str) for part in command) or not command or command[0] != str(args.gradle):
        return errors + ["native_throws_driver_command_invalid"], public
    try:
        root = Path(command[command.index("-p") + 1])
        output = Path(process["outputDirectory"])
        require(root.is_absolute() and output.is_absolute() and root.is_relative_to(case_directory / "tmp")
                and output.is_relative_to(case_directory / "tmp") and ".." not in root.parts and ".." not in output.parts,
                "native_throws_driver_private_paths_invalid")
        private_outputs = [Path(row["outputDirectory"]) for row in rows]
        require(len(set(private_outputs)) == 3 and all(path.is_absolute() and ".." not in path.parts
                and path.is_relative_to(case_directory / "tmp") for path in private_outputs), "native_throws_driver_private_paths_invalid")
        required = tasks(target)
        require(command == provider_command(args, case, target, root, output), "native_throws_driver_command_invalid")
        emitted, observed, successful = diagnostics(process["stdout"], process["stderr"])
        expected_outcomes = {required[0]: {"EXECUTED"}, required[1]: {"FAILED" if case["expectation"] == "named_error" else "EXECUTED"}}
        if case["expectation"] == "observation_only":
            # Record these exact observations separately, never as passing task proof.
            expected_outcomes = {task: {"EXECUTED", "FAILED"} for task in required}
        observed_ok = all(len([row for row in observed if row["path"] == task]) == 1
                          and next(row["outcome"] for row in observed if row["path"] == task) in expected_outcomes[task] for task in required)
        unrelated = any(row["outcome"] == "FAILED" and row["path"] not in required for row in observed)
        public["native_compilation_task_execution_observed"] = observed_ok and value.get("nativeCompilationTaskExecutionObserved") is True
        duplicate_or_cached = any(len([row for row in observed if row["path"] == task]) > 1
            or any(row["outcome"] not in {"EXECUTED", "FAILED"} for row in observed if row["path"] == task) for task in required)
        if unrelated or duplicate_or_cached or type(value.get("nativeCompilationTaskExecutionObserved")) is not bool \
                or value["nativeCompilationTaskExecutionObserved"] != observed_ok \
                or case["expectation"] != "observation_only" and not public["native_compilation_task_execution_observed"]:
            errors.append("native_throws_driver_actual_task_proof_invalid")
    except (ValueError, KeyError, TypeError, Problem, OverflowError):
        return errors + ["native_throws_driver_output_or_command_invalid"], public
    expectation = case["expectation"]
    failures = value.get("assertionFailures")
    if not isinstance(failures, list) or not all(isinstance(item, str) for item in failures):
        errors.append("native_throws_driver_assertion_shape_invalid")
    if expectation == "observation_only":
        public["observation_task_proof_missing"] = not public["native_compilation_task_execution_observed"]
        if value.get("state") != "observed_incomplete" or not failures \
                or "native_throws_observation_only_not_a_passing_control" not in failures or process["exitCode"] not in {0, 1}:
            errors.append("native_throws_observation_not_preserved")
        coverage = provider.get("coverage")
        if not isinstance(coverage, list) or any(not isinstance(row, dict) or row.get("tested") is not False
                or row.get("qualified") is not False or row.get("state") in {"passed", "findings", "not_applicable"} for row in coverage):
            errors.append("native_throws_driver_gaps_or_false_qualification")
        # No apparently clean suppression/demotion output is converted to pass.
        return errors, public
    coverage = provider.get("coverage")
    certificate = digest_bytes((process["analysisKey"] + "\n" + process["stdout"] + "\n" + process["stderr"]).encode("utf-8"))
    expected_row = {"ruleId": case["ruleId"], "context": scope(target), "state": "incomplete", "reason": QUALIFICATION_REASON,
                    "required": True, "depth": "build", "provenance": "executed", "certificateId": certificate,
                    "implemented": True, "tested": False, "qualified": False}
    if value.get("state") != "passed" or failures != [] or provider.get("status") != "incomplete" \
            or raw != [] or gaps != [QUALIFICATION_REASON] or coverage != [expected_row]:
        errors.append("native_throws_driver_gaps_or_false_qualification")
    if expectation == "compatible":
        if process["exitCode"] != 0 or not successful or emitted or findings \
                or "BUILD FAILED" in process["stdout"] + process["stderr"]:
            errors.append("native_throws_compatible_assertions_failed")
    elif expectation == "named_error":
        target_task = required[1]
        if process["exitCode"] != 1 or successful or len(findings) != 1 or len(emitted) != 1 \
                or next((row["outcome"] for row in observed if row["path"] == target_task), None) != "FAILED":
            errors.append("native_throws_named_error_assertions_failed")
        else:
            finding, diagnostic = findings[0], emitted[0]
            location = finding.get("location") if isinstance(finding, dict) else None
            if not isinstance(location, dict) or finding.get("ruleId") != case["ruleId"] \
                    or finding.get("upstreamId") != case["upstreamId"] or finding.get("providerId") != "kotlin-native" \
                    or finding.get("context") != scope(target) or finding.get("severity") != "error" \
                    or finding.get("semanticDigest") != process["analysisKey"] or not valid_digest(finding.get("fingerprint")) \
                    or location.get("path") != case["sourcePath"] or location.get("fileDigest") != pins[case["sourcePath"]]["digest"] \
                    or not any(location.get("line") == anchor["line"] and location.get("endLine") == anchor["line"]
                               and integer(location.get("column"), anchor["firstColumn"], anchor["lastColumn"])
                               and integer(location.get("endColumn"), anchor["firstColumn"], anchor["lastColumn"]) for anchor in case["anchors"]) \
                    or diagnostic["name"] != case["upstreamId"] or diagnostic["severity"] != "error" \
                    or diagnostic["message"] != finding.get("diagnosis") or diagnostic["line"] != location.get("line") \
                    or diagnostic["column"] != location.get("column") or private_reported_path(diagnostic["path"], root) != case["sourcePath"]:
                errors.append("native_throws_named_error_binding_invalid")
    else:
        errors.append("native_throws_expectation_invalid")
    return sorted(set(errors)), public


def verify_driver_files(value: dict, case_directory: Path, pins: dict, budget: Budget) -> None:
    """Recheck real frozen source/output locations; synthetic tests create inert files only."""
    rows = value["processReceipts"]
    command = rows[-1]["command"]
    root = absolute(command[command.index("-p") + 1], directory=True)
    require(root.is_relative_to(case_directory / "tmp"), "native_throws_driver_private_paths_invalid")
    for row in rows:
        path = absolute(row["outputDirectory"], directory=True)
        require(path.is_relative_to(case_directory / "tmp"), "native_throws_driver_private_paths_invalid")
    observed = private_tree_pins(root, budget)
    manifest_pin = observed.pop("source-manifest.json", None)
    require(manifest_pin is not None and manifest_pin["digest"] == MANIFEST_DIGEST and observed == pins,
            "native_throws_driver_frozen_sources_changed")


def private_tree_pins(directory: Path, budget: Budget) -> dict:
    private_size(directory, deadline=budget.deadline)
    pins = {}
    for current, directories, names in os.walk(directory, followlinks=False, onerror=walk_error):
        budget.remaining()
        for name in directories:
            require(not (Path(current) / name).is_symlink(), "native_throws_private_output_unsafe")
        for name in names:
            path = Path(current) / name
            pins[relative(path.relative_to(directory).as_posix())] = file_pin(path, budget)
    return dict(sorted(pins.items()))


def driver_command(args: argparse.Namespace, authority: dict, case: dict, target: str, directory: Path) -> list[str]:
    return [str(args.broker_java_home / "bin/java"), "-Xshare:off", "-Xmx512m", "-XX:MaxMetaspaceSize=256m",
            "-Djava.io.tmpdir=" + str(directory / "tmp"), "-XX:ErrorFile=" + str(directory / "jvm-error-%p.log"),
            "-cp", ":".join(row["path"] for row in authority["classpath"]), MAIN_CLASS,
            "--fixture", str(args.fixture), "--gradle", str(args.gradle), "--target", target,
            "--case", case["name"], "--result", str(directory / "driver.json"),
            "--native-home", str(args.native_home), "--java-home", str(args.project_java_home)]


def output_path(path: Path, root: Path) -> Path:
    require(path.is_absolute() and ".." not in path.parts, "native_throws_output_path_invalid")
    relative_path = path.relative_to(root)
    require(relative_path.parts and relative_path.parts[0] == "build", "native_throws_output_not_ignored_build")
    current = root
    for part in relative_path.parts:
        current /= part
        require(not current.is_symlink(), "native_throws_output_alias")
    return path


class SummaryFile:
    """NEW private metadata output; hold the opened file instead of following replacements."""

    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)

    def write(self, value: dict, budget: Budget | None = None) -> None:
        value["errors"] = sorted(set(public_code(code) for code in value.get("errors", [])))
        for stage in value.get("cases", []):
            stage["errors"] = sorted(set(public_code(code) for code in stage.get("errors", [])))
        raw = (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n").encode("utf-8")
        require(len(raw) <= 512 * 1024, "native_throws_public_summary_limit")
        if budget is not None:
            budget.remaining()  # Serialization is part of the cooperative terminal budget.
        os.lseek(self.fd, 0, os.SEEK_SET)
        offset = 0
        while offset < len(raw):
            written = os.write(self.fd, raw[offset:])
            require(written > 0, "native_throws_public_summary_io_unavailable")
            offset += written
        os.ftruncate(self.fd, len(raw))
        os.fsync(self.fd)
        if budget is not None:
            budget.remaining()

    def close(self) -> None:
        os.close(self.fd)


def normalize_arguments(args: argparse.Namespace, root: Path) -> None:
    require(args.cohort in {"first-mac", "full"} and isinstance(args.target, list) and 1 <= len(args.target) <= 2
            and all(target in TARGETS for target in args.target) and len(set(args.target)) == len(args.target),
            "native_throws_selection_invalid")
    require(integer(args.global_timeout, 30, 7200) and integer(args.case_timeout, 10, 180)
            and integer(args.max_output_bytes, 1024, 64 * 1024 * 1024)
            and integer(args.max_cohort_bytes, 1024, 1024 * 1024 * 1024), "native_throws_bounds_invalid")
    require(args.cohort != "first-mac" or len(args.target) == 1 and args.global_timeout <= 1200,
            "native_throws_first_mac_scope_or_budget_invalid")
    args.output = output_path(args.output.absolute(), root)
    args.private_dir = output_path(args.private_dir.absolute(), root)
    require(not args.output.exists() and not args.private_dir.exists() and not args.output.is_relative_to(args.private_dir)
            and not args.private_dir.is_relative_to(args.output), "native_throws_output_not_fresh_or_overlap")


def base_result(args: argparse.Namespace) -> dict:
    selected_controls = [row for row in AUTHORED_CONTROLS if args.cohort == "full" or row[0] in FIRST_MAC]
    cases = [{"name": name, "target": target, "rule_id": RULES[upstream], "expectation": expectation,
              "state": "not_attempted", "passing_control": False, "source_and_launch_tuple_verified_before": False,
              "source_and_launch_tuple_verified_after": False, "private_driver_digest": None,
              "command": None, "errors": [], "driver": None}
             for target in args.target for name, expectation, upstream in selected_controls]
    return {"schema_version": 1, "evidence_kind": "native_throws_installed_driver_controls", "recorded_at": utc_now(),
            "state": "unavailable", "cohort_id": COHORT, "selection": args.cohort, "targets": args.target,
            "required_status": "unavailable", "selected_authored_control_count": len(selected_controls),
            "selected_expectations_per_target": dict(Counter(row[1] for row in selected_controls)),
            "authored_control_count": 20, "authored_expectations": {"named_error": 7, "compatible": 11, "observation_only": 2},
            "source_manifest_digest": MANIFEST_DIGEST, "source": None,
            "owner_enrollment_digest": args.authority_digest if valid_digest(args.authority_digest) else None,
            "ordered_classpath_digest": None, "component_digest": None, "expected_loaded_supervisor_identity": None,
            "provider_controls_attempted": False, "comprehensive_cohort_completed": False,
            "full_selected_target_cohort_completed": False, "source_and_launch_tuple_verified_final": False,
            "private_originals_digest": None,
            "source_to_binary_provenance_verified": False, "fresh_source_compilation_verified": False,
            "external_native_dependency_closure_qualified": False, "target_qualified": False, "release_qualified": False,
            "raw_logs_uploaded": False, "limits": {"global_seconds": args.global_timeout, "case_seconds": args.case_timeout,
                "case_output_bytes": args.max_output_bytes, "cohort_output_bytes": args.max_cohort_bytes, "cleanup_seconds": CLEANUP_SECONDS},
            "counts": {"selected_invocations": len(cases), "attempted": 0, "passed": 0, "observed_incomplete": 0,
                       "pending_final_verification": 0, "failed": 0, "not_attempted": len(cases)},
            "cases": cases, "errors": [], "limitations": LIMITATIONS}


def run(args: argparse.Namespace, root: Path = REPO_ROOT) -> dict:
    root = root.resolve(strict=True)
    normalize_arguments(args, root)
    summary = SummaryFile(args.output)
    budget = Budget.start(args.global_timeout)
    result = base_result(args)
    try:
        summary.write(result)
        require(valid_digest(args.authority_digest), "native_throws_external_owner_enrollment_required")
        require(platform.system() == "Darwin" and platform.machine().lower() in {"arm64", "aarch64", "x86_64", "amd64"},
                "native_throws_actual_apple_host_required")
        process_gate()
        for name in ("authority", "gradle"):
            setattr(args, name, absolute(getattr(args, name)))
        for name in ("fixture", "broker_java_home", "project_java_home", "native_home", "gradle_user_home", "konan_data_dir", "developer_dir", "home"):
            setattr(args, name, absolute(getattr(args, name), directory=True))
        args.private_dir.parent.mkdir(parents=True, exist_ok=True)
        args.private_dir.mkdir(mode=0o700)
        preflight = args.private_dir / "preflight"
        preflight.mkdir(mode=0o700)
        cohort_scope = ((args.private_dir, args.max_cohort_bytes, "native_throws_cohort_output_limit"),)
        authority = enrollment_input(args, root, budget)
        launch = verify_enrollment(authority, args, root, budget)
        result.update(ordered_classpath_digest=launch["ordered_classpath_digest"], component_digest=launch["component_digest"],
                      expected_loaded_supervisor_identity=launch["supervisor_identity"])
        cohort, pins = fixture_input(root, args.fixture, budget)
        cases = [case for case in cohort["cases"] if args.cohort == "full" or case["name"] in FIRST_MAC]
        require(len(cases) == (20 if args.cohort == "full" else 8), "native_throws_cohort_selection_invalid")
        env = safe_environment(args, args.broker_java_home)
        initial_source = source_identity(root, Path(authority["git"]["path"]), env, preflight, budget, cohort_scope)
        require(initial_source == authority["source"], "native_throws_source_differs_from_owner_enrollment")
        result["source"] = validate_source(initial_source)
        metadata_preflight(authority, args, root, preflight, budget, cohort_scope)
        verify_enrollment(authority, args, root, budget)
        summary.write(result)
        stop = False
        retained = [(preflight, private_tree_pins(preflight, budget))]

        def verify_retained() -> None:
            for path, expected in retained:
                require(private_tree_pins(path, budget) == expected, "native_throws_retained_originals_changed")

        for index, stage in enumerate(result["cases"]):
            if stop:
                stage["errors"] = ["native_throws_cohort_stopped_before_attempt"]
                continue
            private = args.private_dir / f"case-{index:02d}"
            private.mkdir(mode=0o700)
            (private / "tmp").mkdir(mode=0o700)
            identity_dir = private / "identity-before"
            identity_dir.mkdir(mode=0o700)
            case = next(case for case in cases if case["name"] == stage["name"])
            output_scopes = ((private, args.max_output_bytes, "native_throws_case_output_limit"), *cohort_scope)
            try:
                budget.remaining(CLEANUP_SECONDS + REAP_SECONDS)
                verify_retained()
                verify_enrollment(authority, args, root, budget)
                require(source_identity(root, Path(authority["git"]["path"]), env, identity_dir, budget, output_scopes) == initial_source,
                        "native_throws_source_changed_before_case")
                stage["source_and_launch_tuple_verified_before"] = True
                command_dir = private / "command"
                command = run_bounded(driver_command(args, authority, case, stage["target"], private), cwd=root,
                    directory=command_dir, env=env, budget=budget, timeout=min(args.case_timeout, budget.remaining()),
                    max_bytes=args.max_output_bytes, output_scopes=output_scopes)
                stage["command"] = command
                stage["state"] = "failed"
                result["provider_controls_attempted"] |= command["execution_started"]
                require(command_ok(command), command.get("code") or "native_throws_outer_driver_failed")
                raw_path = private / "driver.json"
                raw = bounded_read(raw_path, budget, MAX_JSON)
                value = strict_json(raw)
                stage["private_driver_digest"] = digest_bytes(raw)
                errors, projection = validate_driver(value, case, stage["target"], authority, args, pins, private)
                stage["errors"].extend(errors)
                stage["driver"] = projection
                require(not errors, "native_throws_driver_validation_failed")  # No later child after unsafe/missing receipts.
                verify_driver_files(value, private, pins, budget)
                verify_enrollment(authority, args, root, budget)
                after = private / "identity-after"
                after.mkdir(mode=0o700)
                require(source_identity(root, Path(authority["git"]["path"]), env, after, budget, output_scopes) == initial_source,
                        "native_throws_source_changed_after_case")
                stage["source_and_launch_tuple_verified_after"] = True
                require(private_size(private, deadline=budget.deadline) <= args.max_output_bytes
                        and private_size(args.private_dir, deadline=budget.deadline) <= args.max_cohort_bytes,
                        "native_throws_cohort_or_case_output_limit")
                verify_retained()
                retained.append((private, private_tree_pins(private, budget)))
                if not stage["errors"]:
                    stage["state"] = "observed_incomplete" if case["expectation"] == "observation_only" else "control_candidate"
            except Problem as error:
                stage["errors"].append(str(error))
                stage["state"] = "failed" if stage["command"] is not None and stage["command"]["execution_started"] else "not_attempted"
                stop = True  # Even a timeout with a completed outer protocol is not escaped-child absence proof.
            except (OSError, ValueError, KeyError, TypeError, RuntimeError, zipfile.BadZipFile):
                stage["errors"].append("native_throws_case_evidence_unavailable")
                stage["state"] = "failed" if stage["command"] is not None and stage["command"]["execution_started"] else "not_attempted"
                stop = True
            result["errors"].extend(stage["errors"])
            summary.write(result)
            print(f"Native throws {stage['target']}/{stage['name']}: {stage['state']}", flush=True)
        if not stop and not result["errors"]:
            final = args.private_dir / "final-identity"
            final.mkdir(mode=0o700)
            require(source_identity(root, Path(authority["git"]["path"]), env, final, budget, cohort_scope) == initial_source,
                    "native_throws_source_changed_at_final_verification")
            verify_enrollment(authority, args, root, budget)
            verify_retained()
            require(private_size(args.private_dir, deadline=budget.deadline) <= args.max_cohort_bytes,
                    "native_throws_cohort_output_limit")
            result["private_originals_digest"] = canonical_digest(private_tree_pins(args.private_dir, budget))
            budget.remaining()  # Never promote a cohort after hashing/serialization crossed the deadline.
            result["source_and_launch_tuple_verified_final"] = True
        if result["source_and_launch_tuple_verified_final"] and all(stage["state"] in {"control_candidate", "observed_incomplete"} for stage in result["cases"]):
            observed = any(stage["state"] == "observed_incomplete" for stage in result["cases"])
            for stage in result["cases"]:
                if stage["state"] == "control_candidate":
                    stage.update(state="passed", passing_control=True)
            result["state"] = "incomplete_controls_with_observations" if observed else "passed_controls_only"
            result["required_status"] = "incomplete" if observed else "passing_controls_only"
            result["full_selected_target_cohort_completed"] = args.cohort == "full"
            result["comprehensive_cohort_completed"] = args.cohort == "full" and set(args.target) == set(TARGETS)
        else:
            result["state"] = "failed" if result["provider_controls_attempted"] else "unavailable"
    except Problem as error:
        result["errors"].append(str(error))
        result["state"] = "failed" if result["provider_controls_attempted"] else "unavailable"
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, UnicodeError, zipfile.BadZipFile):
        result["errors"].append("native_throws_preflight_or_evidence_unavailable")
        result["state"] = "failed" if result["provider_controls_attempted"] else "unavailable"
    finally:
        if not result["source_and_launch_tuple_verified_final"]:
            for stage in result["cases"]:
                if stage["state"] in {"passed", "control_candidate"}:
                    stage.update(state="failed", passing_control=False)
                    stage["errors"].append("native_throws_cohort_final_tuple_unverified")
                    result["errors"].append("native_throws_cohort_final_tuple_unverified")
        states = Counter(stage["state"] for stage in result["cases"])
        result["counts"].update(attempted=sum(stage["command"] is not None and stage["command"].get("execution_started") is True for stage in result["cases"]),
                               passed=states["passed"], observed_incomplete=states["observed_incomplete"],
                               pending_final_verification=states["control_candidate"],
                               failed=states["failed"], not_attempted=states["not_attempted"])
        result["errors"] = sorted(set(result["errors"]))
        if result["state"] in {"failed", "unavailable"}:
            result["required_status"] = result["state"]
        result["finished_at"] = utc_now()
        result["elapsed_seconds"] = round(args.global_timeout - (budget.deadline - time.monotonic()), 3)
        try:
            terminal_budget = budget if result["state"] in {"passed_controls_only", "incomplete_controls_with_observations"} else None
            summary.write(result, terminal_budget)
        except (Problem, OSError, ValueError, TypeError, RuntimeError, OverflowError) as error:
            code = str(error) if isinstance(error, Problem) else "native_throws_public_summary_io_unavailable" \
                if isinstance(error, OSError) else "native_throws_public_summary_invalid"
            result["errors"] = sorted(set([*result["errors"], code]))
            result.update(state="failed" if result["provider_controls_attempted"] else "unavailable",
                          required_status="failed" if result["provider_controls_attempted"] else "unavailable",
                          source_and_launch_tuple_verified_final=False, comprehensive_cohort_completed=False,
                          full_selected_target_cohort_completed=False)
            for stage in result["cases"]:
                if stage["state"] in {"passed", "control_candidate"}:
                    stage.update(state="failed", passing_control=False)
                    stage["errors"].append("native_throws_cohort_final_tuple_unverified")
                    result["errors"].append("native_throws_cohort_final_tuple_unverified")
            states = Counter(stage["state"] for stage in result["cases"])
            result["counts"].update(passed=0, pending_final_verification=0, failed=states["failed"])
            result["finished_at"] = utc_now()
            result["elapsed_seconds"] = round(args.global_timeout - (budget.deadline - time.monotonic()), 3)
            try:
                summary.write(result)  # At most one fail-closed metadata rewrite, never another child.
            except (Problem, OSError, ValueError, TypeError, RuntimeError, OverflowError) as rewrite_error:
                code = str(rewrite_error) if isinstance(rewrite_error, Problem) else "native_throws_public_summary_io_unavailable" \
                    if isinstance(rewrite_error, OSError) else "native_throws_public_summary_invalid"
                result["errors"] = sorted(set([*result["errors"], code]))
                # The returned state stays non-success even if the on-disk rewrite is unconfirmed.
        finally:
            summary.close()
    return result


class SafeParser(argparse.ArgumentParser):
    def error(self, _: str) -> None:
        raise Problem("native_throws_arguments_invalid")


def parser() -> argparse.ArgumentParser:
    result = SafeParser(prog="run_native_throws_checks.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("authority", "fixture", "broker-java-home", "project-java-home", "native-home", "gradle", "gradle-user-home",
                 "konan-data-dir", "developer-dir", "home", "private-dir", "output"):
        result.add_argument("--" + name, type=Path, required=True)
    result.add_argument("--authority-digest", required=True, help="externally trusted owner enrollment SHA256; never self-inferred")
    result.add_argument("--target", choices=tuple(TARGETS), action="append", required=True)
    result.add_argument("--cohort", choices=("first-mac", "full"), required=True)
    result.add_argument("--global-timeout", type=int, required=True)
    result.add_argument("--case-timeout", type=int, default=180)
    result.add_argument("--max-output-bytes", type=int, default=32 * 1024 * 1024)
    result.add_argument("--max-cohort-bytes", type=int, default=256 * 1024 * 1024)
    return result


def main(argv: list[str] | None = None) -> int:
    try:
        args = parser().parse_args(argv)
        result = run(args)
    except (Problem, OSError, ValueError, TypeError):
        print('Native throws collector: unavailable (native_throws_arguments_or_new_output_invalid)')
        return 1
    print(f"Native throws collector: {result['state']} ({result['counts']['passed']} passing controls, "
          f"{result['counts']['observed_incomplete']} observation-only, {result['counts']['not_attempted']} not attempted)")
    return 0 if result["state"] == "passed_controls_only" else 1


if __name__ == "__main__":
    raise SystemExit(main())
