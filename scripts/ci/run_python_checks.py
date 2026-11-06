#!/usr/bin/env python3
"""Run standard unittest discovery with bounded, source-bound, text-free reports.

The worker executes trusted repository tests, not a sandbox. Only test IDs,
exception types and repository-relative stack coordinates enter JSON/JUnit.
Console bytes stay in a fresh owner-local raw directory and are never embedded.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import signal
import stat
import subprocess
import sys
import threading
import time
import traceback
import unittest
import uuid
import xml.etree.ElementTree as ET


REPO_ROOT = Path(__file__).resolve().parents[2]
MAX_SOURCE_FILES = 4096
MAX_SOURCE_BYTES = 32 * 1024 * 1024
MAX_SOURCE_FILE_BYTES = 8 * 1024 * 1024
MAX_CASES = 4096
MAX_SUITE_DEPTH = 128
MAX_EVENTS = 8192
MAX_FRAMES = 16
MAX_REPORT_BYTES = 8 * 1024 * 1024
PIPE_DRAIN_SECONDS = 1.0
CLEANUP_WAIT_SECONDS = 2.0
HEX = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*\Z")
RELATIVE = re.compile(r"[A-Za-z0-9_./-]{1,512}\Z")
COUNTS = ("tests_run", "successful_tests", "failures", "errors", "skipped",
          "expected_failures", "unexpected_successes")
EVENTS = {"success", "failure", "error", "skipped", "expected_failure", "unexpected_success"}
FIXTURES = {"setUpClass", "tearDownClass", "setUpModule", "tearDownModule", "unidentified"}
WORKER_DIAGNOSTIC_CODES = {
    "unsupported_checkout_metadata", "nonregular_input", "input_byte_limit", "invalid_checkout_head",
    "invalid_checkout_ref", "missing_or_ambiguous_checkout_ref", "invalid_checkout_commit",
    "symlinked_path_ancestor", "junctioned_path_ancestor", "checkout_commit_mismatch",
    "symlinked_test_directory", "junctioned_test_directory",
    "source_verification_timeout", "unreportable_source_path", "selected_source_limit",
    "selected_source_walk_incomplete", "selected_test_tree_unavailable", "checkout_commit_changed_during_inventory",
    "structured_report_limit", "discovery_case_limit", "worker_or_discovery_exception", "post_execution_source_unavailable",
    "discovery_suite_shape_limit", "unsupported_discovery_leaf", "unsupported_discovery_suite",
    "discovery_suite_identity_changed", "unsupported_selection_callback", "selected_suite_changed",
    "selected_source_changed", "zero_tests_discovered", "zero_tests_executed", "worker_stopped_early",
    "unsupported_framework_callback", "selected_cases_not_accounted_for",
}
LIMITATIONS = [
    "Trusted standard-unittest execution, not a sandbox; tests inherit the normal CI process environment.",
    "The wall deadline covers worker execution; pipe draining and direct-child cleanup have separate fixed bounds.",
    "POSIX process-group kill is best effort; Windows cleanup targets the direct worker only. Neither proves descendant absence.",
    "Owner-local console permissions are not Windows ACL proof. Console, exception messages, skip reasons, locals and environment values are not uploaded.",
    "Source hashes bind selected Python files and checked-out commit before/after execution, not loaded bytecode, external fixtures, toolchains or artifacts.",
    "Incomplete counts are a worker callback prefix, not a claimed final suite total. No incomplete or zero-executed-test run passes.",
    "Standard unittest import and fixture hooks are used. Exact standard TestSuite trees are snapshotted into recording suites without flattening; custom suite execution is retained but never qualifies as complete.",
    "Repeated TestSuite-object aliases are unsupported and execute as originally supplied. Original suite child identity/order/state is checked at callbacks and finalization; an observed change or failed check permanently invalidates completion. Newly added selections are not silently enrolled by the snapshot.",
    "Discovery occurrences, actual starts and actually visited setup-blocked selections are separate. A fixture label, teardown event or unvisited selection is never denominator proof.",
    "Fixture events are retained separately, not fabricated as started tests. Active outcome callbacks checkpoint after standard unittest updates its counters.",
    "JUnit testcase counters are a projection; properties retain actual unittest callback counts and fixture events. XML alone is not a completion claim: require the matching complete JSON and a successful wrapper exit.",
    "Two report files cannot be published as one filesystem transaction. An XML file can remain after JSON publication fails; the wrapper then fails and never qualifies that XML alone.",
    "Process spawn and local filesystem operations are synchronous. The deadline is not a hard containment or kernel-stall guarantee, and readback is not crash-durability proof.",
]


class GuardError(Exception):
    """An internal fixed diagnostic code; never serialize an exception message."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class ReportLimitReached(KeyboardInterrupt):
    pass


@dataclass(frozen=True)
class Config:
    root: Path
    start: Path
    output: Path
    junit: Path
    pattern: str = "test_*.py"
    timeout: float = 480.0
    max_output_bytes: int = 1024 * 1024
    max_report_bytes: int = MAX_REPORT_BYTES
    expected_commit: str | None = None
    ci_context: dict | None = None
    require_ci_context: bool = False


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds")


def digest(raw: bytes) -> str:
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def encoded(value: object, limit: int) -> bytes:
    raw = (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + "\n").encode()
    if len(raw) > limit:
        raise GuardError("structured_report_limit")
    return raw


def strict_json(raw: bytes, limit: int) -> object:
    if len(raw) > limit:
        raise GuardError("structured_report_limit")

    def pairs(items):
        keys = [key for key, _ in items]
        if len(keys) != len(set(keys)):
            raise GuardError("duplicate_json_key")
        return dict(items)

    def constant(_):
        raise GuardError("nonfinite_json_number")

    return json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=constant)


def read_small(path: Path, limit: int) -> bytes:
    mode = path.lstat().st_mode
    if not stat.S_ISREG(mode) or path.is_symlink():
        raise GuardError("nonregular_input")
    with path.open("rb") as handle:
        raw = handle.read(limit + 1)
    if len(raw) > limit:
        raise GuardError("input_byte_limit")
    return raw


def checkout_commit(root: Path) -> str:
    """Read only HEAD/ref bytes, not Git config, credentials, or a Git command.

    Ordinary checkout directories and detached HEAD are supported. Worktree
    gitfiles/alternate metadata fail closed rather than guessing a commit.
    """
    git = root / ".git"
    if not git.is_dir() or git.is_symlink() or git.is_junction():
        raise GuardError("unsupported_checkout_metadata")
    head = read_small(git / "HEAD", 4096).decode("ascii").strip()
    if HEX.fullmatch(head):
        return head
    if not head.startswith("ref: "):
        raise GuardError("invalid_checkout_head")
    reference = head[5:]
    if not re.fullmatch(r"refs/heads/[A-Za-z0-9_./-]{1,240}", reference) \
            or ".." in PurePosixPath(reference).parts or PurePosixPath(reference).as_posix() != reference:
        raise GuardError("invalid_checkout_ref")
    ref = git / reference
    reject_symlink_ancestors(ref, git)
    if ref.exists():
        value = read_small(ref, 4096).decode("ascii").strip()
    else:
        packed = read_small(git / "packed-refs", 1024 * 1024).decode("ascii")
        values = [parts[0] for line in packed.splitlines()
                  if len(parts := line.split()) == 2 and parts[1] == reference]
        if len(values) != 1:
            raise GuardError("missing_or_ambiguous_checkout_ref")
        value = values[0]
    if not HEX.fullmatch(value):
        raise GuardError("invalid_checkout_commit")
    return value


def portable_relative(value: str) -> bool:
    return isinstance(value, str) and bool(RELATIVE.fullmatch(value)) \
        and not PurePosixPath(value).is_absolute() and not PureWindowsPath(value).drive \
        and ".." not in PurePosixPath(value).parts


def reject_symlink_ancestors(path: Path, base: Path) -> None:
    """Reject symlinks and Windows junctions below an already-resolved base."""
    cursor = base
    for part in path.relative_to(base).parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise GuardError("symlinked_path_ancestor")
        if cursor.is_junction():
            raise GuardError("junctioned_path_ancestor")


def normalized(config: Config) -> Config:
    root = config.root.resolve(strict=True)
    lexical_root = Path(os.path.abspath(config.root))
    lexical_start = Path(os.path.abspath(config.start))
    if not lexical_start.is_relative_to(lexical_root):
        raise GuardError("test_tree_outside_checkout")
    reject_symlink_ancestors(root / lexical_start.relative_to(lexical_root), root)
    start = config.start.resolve(strict=True)
    if not start.is_dir() or not start.is_relative_to(root):
        raise GuardError("test_tree_outside_checkout")
    if not isinstance(config.pattern, str) or not config.pattern or len(config.pattern) > 128 \
            or not re.fullmatch(r"[A-Za-z0-9_.*?\[\]-]+", config.pattern):
        raise GuardError("invalid_discovery_pattern")
    if type(config.timeout) not in {int, float} or not math.isfinite(config.timeout) or not 0.1 <= config.timeout <= 2400 \
            or type(config.max_output_bytes) is not int or not 1 <= config.max_output_bytes <= MAX_REPORT_BYTES \
            or type(config.max_report_bytes) is not int or not 512 <= config.max_report_bytes <= MAX_REPORT_BYTES:
        raise GuardError("invalid_execution_bound")
    if config.expected_commit is not None and (not isinstance(config.expected_commit, str) or not HEX.fullmatch(config.expected_commit)):
        raise GuardError("invalid_expected_commit")
    paths = []
    for path in (config.output, config.junit):
        lexical = Path(os.path.abspath(path))
        if not lexical.is_relative_to(lexical_root / "build" / "ci-evidence"):
            raise GuardError("report_destination_outside_evidence")
        reject_symlink_ancestors(root / lexical.relative_to(lexical_root), root)
        resolved = path.resolve()
        if not resolved.is_relative_to(root / "build" / "ci-evidence") or path.is_symlink():
            raise GuardError("report_destination_outside_evidence")
        paths.append(resolved)
    if paths[0] == paths[1]:
        raise GuardError("report_destinations_overlap")
    return Config(root, start, *paths, config.pattern, config.timeout, config.max_output_bytes,
                  config.max_report_bytes, config.expected_commit, config.ci_context,
                  config.require_ci_context)


def context_valid(context: dict | None) -> bool:
    return isinstance(context, dict) and set(context) == {"run_id", "run_attempt", "job", "host", "runner_os"} \
        and all(type(context[key]) is int and context[key] > 0 for key in ("run_id", "run_attempt")) \
        and all(isinstance(context[key], str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", context[key])
                for key in ("job", "host")) and context["runner_os"] in {"Linux", "Windows", "macOS"}


def source_snapshot(config: Config, deadline: float | None = None) -> dict:
    if deadline is not None and time.monotonic() >= deadline:
        raise GuardError("source_verification_timeout")
    commit = checkout_commit(config.root)
    if config.expected_commit is not None and commit != config.expected_commit:
        raise GuardError("checkout_commit_mismatch")
    reject_symlink_ancestors(config.start, config.root)
    if not config.start.is_dir():
        raise GuardError("selected_test_tree_unavailable")
    entries, total = [], 0

    def walk_error(_):
        raise GuardError("selected_source_walk_incomplete")

    for directory, dirs, names in os.walk(config.start, followlinks=False, onerror=walk_error):
        if deadline is not None and time.monotonic() >= deadline:
            raise GuardError("source_verification_timeout")
        dirs[:] = sorted(name for name in dirs if name != "__pycache__")
        if any((Path(directory) / name).is_symlink() for name in dirs):
            raise GuardError("symlinked_test_directory")
        # Python 3.12 os.walk(followlinks=False) still traverses NTFS junctions.
        if any((Path(directory) / name).is_junction() for name in dirs):
            raise GuardError("junctioned_test_directory")
        for name in sorted(names):
            if not name.endswith(".py"):
                continue
            if deadline is not None and time.monotonic() >= deadline:
                raise GuardError("source_verification_timeout")
            path = Path(directory) / name
            raw = read_small(path, MAX_SOURCE_FILE_BYTES)
            relative = path.relative_to(config.root).as_posix()
            if not portable_relative(relative):
                raise GuardError("unreportable_source_path")
            total += len(raw)
            entries.append({"path": relative, "size_bytes": len(raw), "digest": digest(raw),
                            "executable": bool(path.stat().st_mode & 0o111)})
            if len(entries) > MAX_SOURCE_FILES or total > MAX_SOURCE_BYTES:
                raise GuardError("selected_source_limit")
    manifest = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()
    if checkout_commit(config.root) != commit:
        raise GuardError("checkout_commit_changed_during_inventory")
    if deadline is not None and time.monotonic() >= deadline:
        raise GuardError("source_verification_timeout")
    return {"commit": commit, "expected_commit": config.expected_commit,
            "expected_commit_verified": config.expected_commit == commit,
            "selected_test_tree": config.start.relative_to(config.root).as_posix(),
            "selected_test_tree_digest": digest(manifest), "python_file_count": len(entries),
            "python_source_bytes": total,
            "scope": "Actual .py files under the selected test tree; caches and external fixture dependencies are not enrolled"}


def snapshot_valid(value: object, config: Config) -> bool:
    keys = {"commit", "expected_commit", "expected_commit_verified", "selected_test_tree", "selected_test_tree_digest",
            "python_file_count", "python_source_bytes", "scope"}
    return isinstance(value, dict) and set(value) == keys \
        and isinstance(value["commit"], str) and bool(HEX.fullmatch(value["commit"])) \
        and value["expected_commit"] == config.expected_commit \
        and type(value["expected_commit_verified"]) is bool \
        and value["expected_commit_verified"] == (config.expected_commit == value["commit"]) \
        and value["selected_test_tree"] == config.start.relative_to(config.root).as_posix() \
        and isinstance(value["selected_test_tree_digest"], str) and bool(DIGEST.fullmatch(value["selected_test_tree_digest"])) \
        and type(value["python_file_count"]) is int and 0 <= value["python_file_count"] <= MAX_SOURCE_FILES \
        and type(value["python_source_bytes"]) is int and 0 <= value["python_source_bytes"] <= MAX_SOURCE_BYTES \
        and value["scope"] == "Actual .py files under the selected test tree; caches and external fixture dependencies are not enrolled"


def identifier(value: object, fallback: str = "unidentified") -> str:
    if isinstance(value, str) and len(value) <= 512 and IDENTIFIER.fullmatch(value) \
            and not value.startswith(("ghp_", "github_pat_")):
        return value
    return fallback


def exception_details(error: BaseException, tb, root: Path) -> dict:
    frames = []

    def add_frame(filename, line, function):
        try:
            path = Path(filename).resolve()
        except (OSError, TypeError, ValueError):
            return
        if not path.is_relative_to(root) or type(line) is not int or line < 1:
            return
        relative = path.relative_to(root).as_posix()
        if not portable_relative(relative):
            return
        if function not in {"<module>", "<lambda>", "<listcomp>", "<genexpr>", "<dictcomp>", "<setcomp>"}:
            function = identifier(function)
        coordinate = {"path": relative, "line": line, "function": function}
        if coordinate not in frames:
            frames.append(coordinate)

    for frame, line in traceback.walk_tb(tb):
        add_frame(frame.f_code.co_filename, line, frame.f_code.co_name)
    if isinstance(error, SyntaxError):
        # SyntaxError's location is not normally a traceback frame. Its source
        # text, offset and message remain omitted.
        add_frame(error.filename, error.lineno, "<module>")
    return {"exception_type": identifier(type(error).__module__ + "." + type(error).__name__),
            "frames": frames[-MAX_FRAMES:]}


def diagnostic(code: str, error: BaseException | None, root: Path) -> dict:
    details = exception_details(error, error.__traceback__, root) if error is not None \
        else {"exception_type": None, "frames": []}
    return {"code": code, **details}


def replace_private(path: Path, raw: bytes) -> None:
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with temporary.open("xb") as handle:
            os.chmod(temporary, 0o600)
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def publish_exclusive(path: Path, raw: bytes) -> None:
    """Atomically publish a new report without replacing an earlier run's bytes."""
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    published = False
    try:
        with temporary.open("xb") as handle:
            os.chmod(temporary, 0o600)
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.link(temporary, path)
        published = True
        if read_small(path, len(raw)) != raw:
            raise GuardError("report_persistence_mismatch")
    except BaseException:
        if published:
            try:
                if temporary.samefile(path):
                    path.unlink()
            except OSError:
                pass
        raise
    finally:
        temporary.unlink(missing_ok=True)


class RecordingLoader(unittest.TestLoader):
    """Keep original import coordinates before unittest wraps them as text."""

    def __init__(self, root: Path, on_import_failure=None):
        super().__init__()
        self.root = root
        self.on_import_failure = on_import_failure
        self.import_failure_details: list[dict] = []
        self.detail_limit_reached = False

    def _get_module_from_name(self, name):
        try:
            return super()._get_module_from_name(name)
        except unittest.SkipTest:
            raise
        except BaseException as error:
            if len(self.import_failure_details) >= MAX_CASES:
                self.detail_limit_reached = True
            else:
                self.import_failure_details.append({"id": identifier(name),
                    **exception_details(error, error.__traceback__, self.root)})
                if self.on_import_failure is not None:
                    self.on_import_failure()
            raise


def structural_names(test) -> tuple[str, str, str]:
    """Never call a customizable TestCase.id() or serialize its result."""
    cls = type(test)
    module = identifier(cls.__module__)
    class_name = identifier(cls.__module__ + "." + cls.__qualname__)
    name = cls.__module__ + "." + cls.__qualname__ + "." + getattr(test, "_testMethodName", "unidentified")
    return identifier(name), class_name, module


def discovery_inventory(suite) -> tuple[list[dict], list[unittest.TestCase], bool]:
    """Inventory leaf occurrences, not customizable countTestCases()/id() claims.

    The same object can appear more than once. Each appearance gets a separate
    ordinal; object identity is kept privately solely to bind its actual visit.
    Custom suites are not rewritten or considered standard execution.
    """
    inventory, selected_objects = [], []
    supported = type(suite) is unittest.TestSuite
    pending, visited, seen_suites = [(suite, 0)], 0, set()
    while pending:
        item, depth = pending.pop()
        visited += 1
        if depth > MAX_SUITE_DEPTH or visited > MAX_CASES * 4 + MAX_SOURCE_FILES * 4:
            raise GuardError("discovery_suite_shape_limit")
        if isinstance(item, unittest.TestSuite):
            # An exact standard object with instance-defined behavior is also
            # unsupported. Do not silently discard custom run/hook semantics.
            if id(item) in seen_suites or type(item) is not unittest.TestSuite \
                    or not set(vars(item)) <= {"_tests", "_removed_tests", "_cleanup"} \
                    or type(item._tests) is not list or type(item._cleanup) is not bool \
                    or type(item._removed_tests) is not int or item._removed_tests < 0:
                supported = False
            seen_suites.add(id(item))
            children = []
            for child in item:
                children.append((child, depth + 1))
                if len(children) + len(pending) > MAX_CASES * 4 + MAX_SOURCE_FILES * 4:
                    raise GuardError("discovery_suite_shape_limit")
            pending.extend(reversed(children))
        elif isinstance(item, unittest.TestCase):
            if len(inventory) >= MAX_CASES:
                raise GuardError("discovery_case_limit")
            name, class_name, module = structural_names(item)
            inventory.append({"ordinal": len(inventory) + 1, "id": name, "class_id": class_name,
                              "module_id": module, "status": "pending", "case_ordinal": None,
                              "blocking_fixture_event_ordinal": None})
            selected_objects.append(item)
        else:
            raise GuardError("unsupported_discovery_leaf")
    return inventory, selected_objects, supported


class RecordingSuite(unittest.TestSuite):
    """Observe leaf visits while retaining inherited unittest run/fixture order.

    Only an exact standard suite tree is copied into these wrappers. The
    inherited run loop calls _tearDownPreviousClass for each visited leaf,
    followed by module/class setup and then either a setup-blocked continue or
    the original TestCase. No custom suite run method is flattened or replaced.
    """

    def __init__(self, entries, cleanup):
        super().__init__([test for test, _ in entries])
        self._cleanup = cleanup
        self.leaf_visits = [(test, ordinal) for test, ordinal in entries if ordinal is not None]
        self.next_leaf_visit = 0

    def _tearDownPreviousClass(self, test, result):
        if test is not None:
            binding = self.leaf_visits[self.next_leaf_visit] if self.next_leaf_visit < len(self.leaf_visits) else None
            self.next_leaf_visit += 1
            result.bind_visit(test, binding)
        return super()._tearDownPreviousClass(test, result)

    def _handleModuleFixture(self, test, result):
        result.fixture_context = ("setUpModule", result.current_selection)
        try:
            return super()._handleModuleFixture(test, result)
        finally:
            result.fixture_context = None

    def _handleClassSetUp(self, test, result):
        result.fixture_context = ("setUpClass", result.current_selection)
        try:
            super()._handleClassSetUp(test, result)
        finally:
            result.fixture_context = None
        if getattr(result, "_moduleSetUpFailed", False):
            result.account_setup_blocked(test, "setUpModule")
        elif getattr(type(test), "_classSetupFailed", False):
            result.account_setup_blocked(test, "setUpClass")


def recording_suite(suite, selected_objects):
    """Copy a supported tree and keep a non-mutating original-selection guard."""
    ordinal = 0
    originals, seen_originals = [], set()

    def copy_suite(item, depth):
        nonlocal ordinal
        if depth > MAX_SUITE_DEPTH or type(item) is not unittest.TestSuite or id(item) in seen_originals \
                or not set(vars(item)) <= {"_tests", "_removed_tests", "_cleanup"} \
                or type(item._tests) is not list or type(item._cleanup) is not bool \
                or type(item._removed_tests) is not int or item._removed_tests < 0:
            raise GuardError("discovery_suite_identity_changed")
        seen_originals.add(id(item))
        children = tuple(item._tests)
        originals.append((item, item._tests, children, set(vars(item)), item._cleanup, item._removed_tests))
        entries = []
        for child in children:
            if type(child) is unittest.TestSuite:
                entries.append((copy_suite(child, depth + 1), None))
            elif isinstance(child, unittest.TestCase):
                if ordinal >= len(selected_objects) or child is not selected_objects[ordinal]:
                    raise GuardError("discovery_suite_identity_changed")
                ordinal += 1
                entries.append((child, ordinal))
            else:
                raise GuardError("discovery_suite_identity_changed")
        return RecordingSuite(entries, item._cleanup)

    result = copy_suite(suite, 0)
    if ordinal != len(selected_objects):
        raise GuardError("discovery_suite_identity_changed")

    def unchanged():
        for original, container, children, fields, cleanup, removed in originals:
            if type(original) is not unittest.TestSuite or set(vars(original)) != fields \
                    or original._tests is not container or type(container) is not list \
                    or original._cleanup is not cleanup or type(original._removed_tests) is not int \
                    or original._removed_tests != removed or len(container) != len(children) \
                    or any(actual is not selected for actual, selected in zip(container, children)):
                return False
        return True

    return result, unchanged


class RecordingResult(unittest.TestResult):
    def __init__(self, root: Path, checkpoint, inventory=None, selected_objects=None, standard_execution=False):
        super().__init__()
        self.root, self.checkpoint = root, checkpoint
        self.cases: list[dict] = []
        self.framework_events: list[dict] = []
        self.active: dict[int, tuple[dict, float]] = {}
        self.event_count = 0
        self.unsupported_callback = False
        self.unsupported_selection = False
        self.inventory = inventory if inventory is not None else []
        self.selected_objects = selected_objects if selected_objects is not None else []
        self.standard_execution = standard_execution
        self.current_selection = None
        self.fixture_context = None
        self.setup_failure_events = {}
        self.selection_guard = None
        self.original_suite_shape_unchanged = None
        self.allowed_fixtures = {"class": {row["class_id"] for row in self.inventory if row["class_id"] != "unidentified"},
                                 "module": {row["module_id"] for row in self.inventory if row["module_id"] != "unidentified"}}

    def observe_original_selection(self):
        if self.selection_guard is None or self.original_suite_shape_unchanged is False:
            return self.original_suite_shape_unchanged
        try:
            self.original_suite_shape_unchanged = self.selection_guard() is True
        except Exception:
            # Failed guard enumeration is not evidence of an unchanged tree.
            # No exception message enters the checkpoint, and outcome counts
            # already observed by unittest are never removed.
            self.original_suite_shape_unchanged = False
        return self.original_suite_shape_unchanged

    def bind_visit(self, test, binding):
        self.current_selection = None
        if not self.standard_execution or binding is None or binding[0] is not test:
            self.unsupported_selection = True
            return
        ordinal = binding[1]
        if not 1 <= ordinal <= len(self.inventory) or self.selected_objects[ordinal - 1] is not test \
                or self.inventory[ordinal - 1]["status"] != "pending":
            self.unsupported_selection = True
            return
        self.current_selection = (test, ordinal)

    def account_setup_blocked(self, test, fixture):
        binding, self.current_selection = self.current_selection, None
        if binding is None or binding[0] is not test:
            self.unsupported_selection = True
            return
        row = self.inventory[binding[1] - 1]
        target = row["class_id"] if fixture == "setUpClass" else row["module_id"]
        scope_key = id(type(test)) if fixture == "setUpClass" else target
        event_ordinal = self.setup_failure_events.get((fixture, scope_key))
        if event_ordinal is None or row["status"] != "pending":
            self.unsupported_selection = True
            return
        event = self.framework_events[event_ordinal - 1]
        if event["fixture"] != fixture or event["id"] != target or event["kind"] not in {"error", "skipped"}:
            self.unsupported_selection = True
            return
        row.update(status="setup_blocked", blocking_fixture_event_ordinal=event_ordinal)
        event["blocked_discovery_ordinals"].append(row["ordinal"])
        self.checkpoint()

    def _exc_info_to_string(self, err, test):
        # Standard TestResult still owns its failure/error/skip counters. Its
        # internal text is not needed and must not become an uploaded report.
        return "Exception text omitted"

    def startTest(self, test):
        if len(self.cases) >= MAX_CASES:
            raise ReportLimitReached()
        super().startTest(test)
        name, _, _ = structural_names(test)
        binding, self.current_selection = self.current_selection, None
        discovery_ordinal = None
        if self.standard_execution:
            if binding is not None and binding[0] is test and self.inventory[binding[1] - 1]["id"] == name \
                    and self.inventory[binding[1] - 1]["status"] == "pending" and id(test) not in self.active:
                discovery_ordinal = binding[1]
                self.inventory[discovery_ordinal - 1].update(status="started", case_ordinal=len(self.cases) + 1)
            else:
                self.unsupported_selection = True
        case = {"ordinal": len(self.cases) + 1, "discovery_ordinal": discovery_ordinal, "id": name, "complete": False,
                "stop_callback_observed": False, "elapsed_seconds": 0.0, "events": []}
        self.cases.append(case)
        self.active[id(test)] = case, time.monotonic()
        self.checkpoint()

    def stopTest(self, test):
        case, started = self.active.pop(id(test))
        case.update(complete=bool(case["events"]), stop_callback_observed=True,
                    elapsed_seconds=round(time.monotonic() - started, 6))
        super().stopTest(test)
        self.checkpoint()

    def event(self, test, kind, err=None, subtest=False):
        base = test.test_case if isinstance(test, unittest.case._SubTest) else test
        if self.event_count >= MAX_EVENTS:
            raise ReportLimitReached()
        details = exception_details(err[1], err[2], self.root) if err is not None \
            else {"exception_type": None, "frames": []}
        if id(base) in self.active:
            self.active[id(base)][0]["events"].append({"kind": kind, "subtest": subtest or base is not test, **details})
        else:
            fixture, target = "unidentified", "unidentified"
            if type(test) is unittest.suite._ErrorHolder:
                match = re.fullmatch(r"(setUpClass|tearDownClass|setUpModule|tearDownModule) \(([A-Za-z_][A-Za-z0-9_.]*)\)",
                                     test.description)
                if match:
                    fixture, target = match.groups()
                    scope = "class" if fixture.endswith("Class") else "module"
                    if target not in self.allowed_fixtures[scope]:
                        fixture, target = "unidentified", "unidentified"
                    else:
                        target = identifier(target)
            if fixture == "unidentified" or target == "unidentified" or kind not in {"error", "skipped"}:
                self.unsupported_callback = True
            event_ordinal = len(self.framework_events) + 1
            self.framework_events.append({"ordinal": event_ordinal, "id": target, "fixture": fixture,
                "kind": kind, "blocked_discovery_ordinals": [], **details})
            if self.standard_execution and self.fixture_context is not None and fixture in {"setUpClass", "setUpModule"} \
                    and kind in {"error", "skipped"}:
                phase, binding = self.fixture_context
                if phase == fixture and binding is not None:
                    selected_test, selected_ordinal = binding
                    selected = self.inventory[selected_ordinal - 1]
                    expected = selected["class_id"] if fixture == "setUpClass" else selected["module_id"]
                    if target == expected:
                        scope_key = id(type(selected_test)) if fixture == "setUpClass" else target
                        self.setup_failure_events[(fixture, scope_key)] = event_ordinal
        self.event_count += 1

    def after_callback(self, test):
        # Outcome information must survive a later body/tearDown hang while
        # this case remains active. Counts have already been updated by super.
        self.checkpoint()

    def addSuccess(self, test):
        self.event(test, "success")
        super().addSuccess(test)
        self.after_callback(test)

    def addFailure(self, test, err):
        self.event(test, "failure", err)
        super().addFailure(test, err)
        self.after_callback(test)

    def addError(self, test, err):
        self.event(test, "error", err)
        super().addError(test, err)
        self.after_callback(test)

    def addSkip(self, test, reason):
        self.event(test, "skipped")
        super().addSkip(test, "Skip reason omitted")
        self.after_callback(test)

    def addExpectedFailure(self, test, err):
        self.event(test, "expected_failure", err)
        super().addExpectedFailure(test, err)
        self.after_callback(test)

    def addUnexpectedSuccess(self, test):
        self.event(test, "unexpected_success")
        super().addUnexpectedSuccess(test)
        self.after_callback(test)

    def addSubTest(self, test, subtest, err):
        if err is not None:
            kind = "failure" if issubclass(err[0], test.failureException) else "error"
            self.event(test, kind, err, subtest=True)
        super().addSubTest(test, subtest, err)
        self.after_callback(test)

    def actual_counts(self):
        return {"tests_run": self.testsRun,
                "successful_tests": sum(event["kind"] == "success" for case in self.cases for event in case["events"])
                    + sum(event["kind"] == "success" for event in self.framework_events),
                "failures": len(self.failures), "errors": len(self.errors), "skipped": len(self.skipped),
                "expected_failures": len(self.expectedFailures), "unexpected_successes": len(self.unexpectedSuccesses)}


def worker_run(config: Config, final: Path, progress: Path) -> int:
    source_before = source_after = None
    discovery = {"completed": False, "tests_selected": 0, "loader_errors": 0, "import_errors": 0,
                 "import_failure_details": [], "execution_shape_supported": False, "test_inventory": [],
                 "original_suite_shape_unchanged": None}
    diagnostics = []
    result = None
    loader = None
    execution_returned = False

    def report(phase, complete=False):
        counts = result.actual_counts() if result is not None else {key: 0 for key in COUNTS}
        state = "incomplete"
        if complete:
            state = "passed" if result.wasSuccessful() and not discovery["loader_errors"] else "failed"
        return {"schema_version": 1, "phase": phase, "state": state,
                "source_before": source_before, "source_after": source_after,
                "ci_context": config.ci_context, "selection": {"start_directory": config.start.relative_to(config.root).as_posix(), "pattern": config.pattern},
                "discovery": discovery, "counts": counts, "cases": result.cases if result else [],
                "framework_events": result.framework_events if result else [],
                "diagnostics": diagnostics, "results_complete": complete}

    def checkpoint():
        if result is not None:
            discovery["original_suite_shape_unchanged"] = result.observe_original_selection()
        replace_private(progress, encoded(report("checkpoint"), config.max_report_bytes))

    def import_checkpoint():
        discovery.update(loader_errors=len(loader.errors), import_errors=len(loader.import_failure_details),
                         import_failure_details=loader.import_failure_details)
        checkpoint()

    try:
        source_before = source_snapshot(config)
        checkpoint()
        loader = RecordingLoader(config.root, import_checkpoint)
        suite = loader.discover(str(config.start), pattern=config.pattern)
        inventory, selected_objects, standard_execution = discovery_inventory(suite)
        discovery.update(completed=True, tests_selected=len(inventory), loader_errors=len(loader.errors),
                         execution_shape_supported=standard_execution, test_inventory=inventory,
                         import_errors=len(loader.import_failure_details), import_failure_details=loader.import_failure_details)
        if discovery["tests_selected"] > MAX_CASES or discovery["loader_errors"] > MAX_CASES or loader.detail_limit_reached:
            raise GuardError("discovery_case_limit")
        result = RecordingResult(config.root, checkpoint, inventory, selected_objects, standard_execution)
        if standard_execution:
            suite, result.selection_guard = recording_suite(suite, selected_objects)
        else:
            diagnostics.append(diagnostic("unsupported_discovery_suite", None, config.root))
        checkpoint()
        result.startTestRun()
        try:
            suite.run(result)
            execution_returned = not result.shouldStop
        finally:
            result.stopTestRun()
    except BaseException as error:
        if loader is not None:
            discovery.update(loader_errors=min(len(loader.errors), MAX_CASES),
                             import_errors=len(loader.import_failure_details),
                             import_failure_details=loader.import_failure_details)
        code = error.code if isinstance(error, GuardError) else "worker_or_discovery_exception"
        diagnostics.append(diagnostic(code, error, config.root))
    try:
        source_after = source_snapshot(config)
        if source_before != source_after:
            diagnostics.append(diagnostic("selected_source_changed", None, config.root))
    except BaseException as error:
        diagnostics.append(diagnostic("post_execution_source_unavailable", error, config.root))
    if discovery["tests_selected"] == 0:
        diagnostics.append(diagnostic("zero_tests_discovered", None, config.root))
    if result is not None and result.testsRun == 0 and discovery["tests_selected"] > 0:
        diagnostics.append(diagnostic("zero_tests_executed", None, config.root))
    if result is not None and result.shouldStop:
        diagnostics.append(diagnostic("worker_stopped_early", None, config.root))
    if result is not None and result.unsupported_callback:
        diagnostics.append(diagnostic("unsupported_framework_callback", None, config.root))
    if result is not None and result.unsupported_selection:
        diagnostics.append(diagnostic("unsupported_selection_callback", None, config.root))
    if result is not None:
        discovery["original_suite_shape_unchanged"] = result.observe_original_selection()
        if discovery["original_suite_shape_unchanged"] is False:
            diagnostics.append(diagnostic("selected_suite_changed", None, config.root))
    selected_accounted_for = result is not None and discovery["execution_shape_supported"] \
        and discovery["original_suite_shape_unchanged"] is True \
        and not result.unsupported_selection and all(row["status"] != "pending" for row in discovery["test_inventory"]) \
        and all(case["discovery_ordinal"] is not None for case in result.cases) \
        and result.testsRun == sum(row["status"] == "started" for row in discovery["test_inventory"])
    if result is not None and not selected_accounted_for:
        diagnostics.append(diagnostic("selected_cases_not_accounted_for", None, config.root))
    complete = result is not None and discovery["completed"] and execution_returned and discovery["tests_selected"] > 0 \
        and selected_accounted_for and all(case["complete"] for case in result.cases) \
        and all(row[key] != "unidentified" for row in discovery["test_inventory"] for key in ("id", "class_id", "module_id")) \
        and all(case["id"] != "unidentified" for case in result.cases) and not diagnostics
    value = report("final", complete)
    try:
        replace_private(final, encoded(value, config.max_report_bytes))
    except BaseException:
        return 2
    return 0 if value["state"] == "passed" else 1 if value["state"] == "failed" else 2


def validate_worker(value: object, source: dict, source_after: dict | None, config: Config) -> dict:
    """Validate text-free protocol structure, not a claim of post-check success.

    Parent/worker source mismatches are surfaced separately by run_checks so a
    valid failure record is not discarded merely because source verification
    was incomplete or changed. They can never produce a successful wrapper.
    """
    keys = {"schema_version", "phase", "state", "source_before", "source_after", "ci_context", "selection",
            "discovery", "counts", "cases", "framework_events", "diagnostics", "results_complete"}
    if not isinstance(value, dict) or set(value) != keys or type(value["schema_version"]) is not int or value["schema_version"] != 1 \
            or not isinstance(value["phase"], str) or value["phase"] not in {"checkpoint", "final"} \
            or not isinstance(value["state"], str) or value["state"] not in {"passed", "failed", "incomplete"} \
            or not snapshot_valid(value["source_before"], config) or value["ci_context"] != config.ci_context \
            or value["selection"] != {"start_directory": config.start.relative_to(config.root).as_posix(), "pattern": config.pattern}:
        raise GuardError("invalid_worker_envelope")
    if value["source_after"] is not None and not snapshot_valid(value["source_after"], config):
        raise GuardError("invalid_worker_source_after")
    if value["phase"] == "checkpoint" and (value["source_after"] is not None or value["results_complete"] is not False or value["state"] != "incomplete"):
        raise GuardError("invalid_worker_checkpoint")
    discovery, counts, cases, framework = value["discovery"], value["counts"], value["cases"], value["framework_events"]
    if not isinstance(discovery, dict) or set(discovery) != {"completed", "tests_selected", "loader_errors", "import_errors", "import_failure_details",
                                                           "execution_shape_supported", "test_inventory", "original_suite_shape_unchanged"} \
            or type(discovery["completed"]) is not bool \
            or type(discovery["execution_shape_supported"]) is not bool \
            or (discovery["original_suite_shape_unchanged"] is not None and type(discovery["original_suite_shape_unchanged"]) is not bool) \
            or (not discovery["execution_shape_supported"] and discovery["original_suite_shape_unchanged"] is not None) \
            or any(type(discovery[key]) is not int or not 0 <= discovery[key] <= MAX_CASES for key in ("tests_selected", "loader_errors", "import_errors")) \
            or not isinstance(discovery["import_failure_details"], list) or len(discovery["import_failure_details"]) != discovery["import_errors"] \
            or not isinstance(discovery["test_inventory"], list) or len(discovery["test_inventory"]) != discovery["tests_selected"] \
            or (not discovery["completed"] and (discovery["test_inventory"] or discovery["execution_shape_supported"])) \
            or (discovery["completed"] and discovery["import_errors"] > discovery["loader_errors"]) \
            or not isinstance(counts, dict) or set(counts) != set(COUNTS) \
            or any(type(n) is not int or not 0 <= n <= MAX_EVENTS for n in counts.values()) \
            or not isinstance(cases, list) or len(cases) != counts["tests_run"] or len(cases) > MAX_CASES \
            or not isinstance(framework, list) or len(framework) > MAX_EVENTS \
            or type(value["results_complete"]) is not bool:
        raise GuardError("invalid_worker_counts")
    inventory = discovery["test_inventory"]
    for ordinal, row in enumerate(inventory, 1):
        if not isinstance(row, dict) or set(row) != {"ordinal", "id", "class_id", "module_id", "status", "case_ordinal", "blocking_fixture_event_ordinal"} \
                or type(row["ordinal"]) is not int or row["ordinal"] != ordinal \
                or any(identifier(row[key]) != row[key] for key in ("id", "class_id", "module_id")) \
                or (row["id"] != "unidentified" and row["class_id"] != "unidentified" and not row["id"].startswith(row["class_id"] + ".")) \
                or (row["class_id"] != "unidentified" and row["module_id"] != "unidentified" and not row["class_id"].startswith(row["module_id"] + ".")) \
                or not isinstance(row["status"], str) or row["status"] not in {"pending", "started", "setup_blocked"}:
            raise GuardError("invalid_discovery_occurrence")
        if row["status"] == "pending":
            valid_pointers = row["case_ordinal"] is None and row["blocking_fixture_event_ordinal"] is None
        elif row["status"] == "started":
            valid_pointers = type(row["case_ordinal"]) is int and 1 <= row["case_ordinal"] <= len(cases) \
                and row["blocking_fixture_event_ordinal"] is None
        else:
            valid_pointers = row["case_ordinal"] is None and type(row["blocking_fixture_event_ordinal"]) is int \
                and 1 <= row["blocking_fixture_event_ordinal"] <= len(framework)
        if not valid_pointers or (not discovery["execution_shape_supported"] and row["status"] != "pending"):
            raise GuardError("invalid_discovery_accounting")
    observed = {key: 0 for key in COUNTS}
    observed["tests_run"] = len(cases)
    mapping = {"success": "successful_tests", "failure": "failures", "error": "errors", "skipped": "skipped",
               "expected_failure": "expected_failures", "unexpected_success": "unexpected_successes"}

    def details(row):
        kind = row["exception_type"]
        if kind is not None and identifier(kind) != kind:
            raise GuardError("invalid_exception_type")
        frames = row["frames"]
        if not isinstance(frames, list) or len(frames) > MAX_FRAMES:
            raise GuardError("invalid_stack_frames")
        for frame in frames:
            if not isinstance(frame, dict) or set(frame) != {"path", "line", "function"} \
                    or not portable_relative(frame["path"]) or type(frame["line"]) is not int or not 1 <= frame["line"] <= 10**8 \
                    or not isinstance(frame["function"], str) or len(frame["function"]) > 512 \
                    or not (IDENTIFIER.fullmatch(frame["function"]) or frame["function"] in {"<module>", "<lambda>", "<listcomp>", "<genexpr>", "<dictcomp>", "<setcomp>"}):
                raise GuardError("invalid_stack_frame")

    event_count = 0
    for row in discovery["import_failure_details"]:
        if not isinstance(row, dict) or set(row) != {"id", "exception_type", "frames"} \
                or identifier(row["id"]) != row["id"] or row["exception_type"] is None:
            raise GuardError("invalid_import_failure")
        details(row)

    def observe(event):
        details(event)
        if event["kind"] in {"success", "skipped", "unexpected_success"}:
            if event["exception_type"] is not None or event["frames"]:
                raise GuardError("unexpected_event_exception")
        elif event["exception_type"] is None:
            raise GuardError("missing_event_exception")
        observed[mapping[event["kind"]]] += 1

    bound_starts = set()
    for ordinal, case in enumerate(cases, 1):
        if not isinstance(case, dict) or set(case) != {"ordinal", "discovery_ordinal", "id", "complete", "stop_callback_observed", "elapsed_seconds", "events"} \
                or type(case["ordinal"]) is not int or case["ordinal"] != ordinal \
                or identifier(case["id"]) != case["id"] or type(case["complete"]) is not bool \
                or type(case["stop_callback_observed"]) is not bool or (case["complete"] and not case["stop_callback_observed"]) \
                or type(case["elapsed_seconds"]) not in {int, float} or not math.isfinite(case["elapsed_seconds"]) or case["elapsed_seconds"] < 0 \
                or not isinstance(case["events"], list):
            raise GuardError("invalid_worker_case")
        selected_ordinal = case["discovery_ordinal"]
        if selected_ordinal is not None:
            if type(selected_ordinal) is not int or not 1 <= selected_ordinal <= len(inventory) \
                    or not discovery["execution_shape_supported"] or selected_ordinal in bound_starts:
                raise GuardError("invalid_started_occurrence")
            selected = inventory[selected_ordinal - 1]
            if selected["status"] != "started" or selected["case_ordinal"] != ordinal or selected["id"] != case["id"]:
                raise GuardError("started_occurrence_mismatch")
            bound_starts.add(selected_ordinal)
        for event in case["events"]:
            if not isinstance(event, dict) or set(event) != {"kind", "subtest", "exception_type", "frames"} \
                    or not isinstance(event["kind"], str) or event["kind"] not in EVENTS or type(event["subtest"]) is not bool \
                    or (event["subtest"] and event["kind"] not in {"failure", "error", "skipped"}):
                raise GuardError("invalid_worker_event")
            observe(event)
            event_count += 1
        if case["complete"] != (case["stop_callback_observed"] and bool(case["events"])):
            raise GuardError("case_completion_callback_mismatch")
    if bound_starts != {row["ordinal"] for row in inventory if row["status"] == "started"}:
        raise GuardError("discovery_start_accounting_mismatch")
    blocked_occurrences = set()
    allowed_targets = {"class": {row["class_id"] for row in inventory}, "module": {row["module_id"] for row in inventory}}
    for ordinal, event in enumerate(framework, 1):
        if not isinstance(event, dict) or set(event) != {"ordinal", "id", "fixture", "kind", "blocked_discovery_ordinals", "exception_type", "frames"} \
                or type(event["ordinal"]) is not int or event["ordinal"] != ordinal or identifier(event["id"]) != event["id"] \
                or not isinstance(event["fixture"], str) or event["fixture"] not in FIXTURES \
                or not isinstance(event["kind"], str) or event["kind"] not in EVENTS:
            raise GuardError("invalid_framework_event")
        if (event["fixture"] == "unidentified") != (event["id"] == "unidentified") \
                or (event["fixture"] != "unidentified" and event["id"] not in allowed_targets["class" if event["fixture"].endswith("Class") else "module"]):
            raise GuardError("framework_target_mismatch")
        blocked = event["blocked_discovery_ordinals"]
        if not isinstance(blocked, list) or len(blocked) > MAX_CASES \
                or any(type(n) is not int or not 1 <= n <= len(inventory) for n in blocked) \
                or blocked != sorted(set(blocked)) \
                or (blocked and (not discovery["execution_shape_supported"] or event["fixture"] not in {"setUpClass", "setUpModule"}
                                 or event["kind"] not in {"error", "skipped"})):
            raise GuardError("invalid_fixture_block_accounting")
        for selected_ordinal in blocked:
            selected = inventory[selected_ordinal - 1]
            target = selected["class_id"] if event["fixture"] == "setUpClass" else selected["module_id"]
            if selected_ordinal in blocked_occurrences or selected["status"] != "setup_blocked" \
                    or selected["blocking_fixture_event_ordinal"] != ordinal or target != event["id"]:
                raise GuardError("fixture_block_occurrence_mismatch")
            blocked_occurrences.add(selected_ordinal)
        observe(event)
        event_count += 1
    if blocked_occurrences != {row["ordinal"] for row in inventory if row["status"] == "setup_blocked"}:
        raise GuardError("discovery_fixture_accounting_mismatch")
    if event_count > MAX_EVENTS or counts != observed:
        raise GuardError("worker_count_event_mismatch")
    if not isinstance(value["diagnostics"], list) or len(value["diagnostics"]) > 16:
        raise GuardError("invalid_worker_diagnostics")
    for row in value["diagnostics"]:
        if not isinstance(row, dict) or set(row) != {"code", "exception_type", "frames"} \
                or not isinstance(row["code"], str) or row["code"] not in WORKER_DIAGNOSTIC_CODES:
            raise GuardError("invalid_worker_diagnostic")
        details(row)
    if value["results_complete"]:
        if value["phase"] != "final" or not discovery["completed"] or not discovery["execution_shape_supported"] \
                or discovery["original_suite_shape_unchanged"] is not True \
                or any(row["status"] == "pending" or any(row[key] == "unidentified" for key in ("id", "class_id", "module_id")) for row in inventory) \
                or any(case["discovery_ordinal"] is None for case in cases) \
                or len(bound_starts) + len(blocked_occurrences) != discovery["tests_selected"] \
                or counts["tests_run"] == 0 or not all(case["complete"] for case in cases) or value["diagnostics"] \
                or any(not case["events"] or case["id"] == "unidentified" for case in cases) \
                or any(event["fixture"] == "unidentified" or event["id"] == "unidentified" or event["kind"] not in {"error", "skipped"} for event in framework) \
                or value["source_before"] != value["source_after"]:
            raise GuardError("worker_completion_mismatch")
        expected = "failed" if any(counts[key] for key in ("failures", "errors", "unexpected_successes")) or discovery["loader_errors"] else "passed"
        if value["state"] != expected:
            raise GuardError("worker_outcome_mismatch")
    elif value["state"] != "incomplete":
        raise GuardError("incomplete_worker_claimed_outcome")
    return value


class ConsoleCapture:
    def __init__(self, directory: Path, limit: int):
        self.limit, self.lock = limit, threading.Lock()
        self.total, self.overflow = 0, False
        self.streams, self.threads = {}, []
        try:
            for name in ("stdout", "stderr"):
                path = directory / ("console." + name + ".log")
                handle = path.open("xb")
                self.streams[name] = {"handle": handle, "hash": hashlib.sha256(), "bytes": 0,
                                      "observed": 0, "eof": False, "error": False, "attached": False}
                os.chmod(path, 0o600)
        except BaseException:
            for row in self.streams.values():
                row["handle"].close()
            raise

    def attach(self, process):
        def read(name, pipe):
            row = self.streams[name]
            try:
                while True:
                    chunk = pipe.read1(65536)
                    if not chunk:
                        row["eof"] = True
                        break
                    with self.lock:
                        row["observed"] += len(chunk)
                        retained = chunk[:max(0, self.limit - self.total)]
                        row["handle"].write(retained)
                        row["hash"].update(retained)
                        row["bytes"] += len(retained)
                        self.total += len(retained)
                        if len(retained) != len(chunk):
                            self.overflow = True
                            break
                row["handle"].flush()
                os.fsync(row["handle"].fileno())
            except BaseException:
                row["error"] = True
            finally:
                for handle in (row["handle"], pipe):
                    try:
                        handle.close()
                    except BaseException:
                        row["error"] = True
        for name in ("stdout", "stderr"):
            thread = threading.Thread(target=read, args=(name, getattr(process, name)), daemon=True)
            thread.start()
            self.streams[name]["attached"] = True
            self.threads.append(thread)

    def finish(self):
        deadline = time.monotonic() + PIPE_DRAIN_SECONDS
        for thread in self.threads:
            thread.join(max(0.0, deadline - time.monotonic()))
        for row in self.streams.values():
            if not row["attached"]:
                row["handle"].close()
        with self.lock:
            return {"max_total_bytes": self.limit, "retained_total_bytes": self.total,
                    "truncated": self.overflow, "raw_console_uploaded": False,
                    "drain_completed": all(not thread.is_alive() for thread in self.threads),
                    "digests_scope": "Owner-local retained bytes; a truncated stream is only a prefix",
                    "streams": {name: {"bytes": row["bytes"], "observed_bytes_lower_bound": row["observed"],
                                       "digest": "sha256:" + row["hash"].hexdigest(),
                                       "eof_observed": row["eof"], "reader_error": row["error"]}
                                for name, row in self.streams.items()}}


def junit_bytes(report: dict) -> bytes:
    cases = report["cases"]
    kinds = [{event["kind"] for event in case["events"]} for case in cases]
    errors = sum("error" in row for row in kinds)
    failures = sum("error" not in row and bool(row & {"failure", "unexpected_success"}) for row in kinds)
    skipped = sum(not row & {"error", "failure", "unexpected_success"} and bool(row & {"skipped", "expected_failure"}) for row in kinds)
    errors += sum(event["kind"] == "error" for event in report["framework_events"])
    failures += sum(event["kind"] in {"failure", "unexpected_success"} for event in report["framework_events"])
    skipped += sum(event["kind"] in {"skipped", "expected_failure"} for event in report["framework_events"])
    suite = ET.Element("testsuite", name="controlled-python-unittest", tests=str(len(cases)),
                       failures=str(failures), errors=str(errors), skipped=str(skipped))
    properties = ET.SubElement(suite, "properties")
    for name, value in (("harness_state", report["state"]), ("results_complete", str(report["results_complete"]).lower()),
                        ("counts_scope", report["counts_scope"]), ("exception_text_and_console_omitted", "true"),
                        ("requires_matching_complete_json_and_successful_wrapper_exit", "true"),
                        ("framework_events_are_not_started_tests", "true"), ("all_qualification_flags_false", "true"),
                        ("junit_counter_scope", "testcase projections plus separate framework callbacks; actual unittest counts are properties")):
        ET.SubElement(properties, "property", name=name, value=value)
    if report["counts"] is not None:
        for name, value in report["counts"].items():
            ET.SubElement(properties, "property", name="unittest_" + name, value=str(value))
    if report["test_denominators"] is not None:
        for name, value in report["test_denominators"].items():
            ET.SubElement(properties, "property", name=name, value="unavailable" if value is None else str(value))

    def coordinates(prefix, entry):
        ET.SubElement(properties, "property", name=prefix + "id", value=entry["id"])
        ET.SubElement(properties, "property", name=prefix + "exception_type", value=entry["exception_type"] or "none")
        for index, frame in enumerate(entry["frames"], 1):
            for key in ("path", "line", "function"):
                ET.SubElement(properties, "property", name=prefix + f"frame_{index}_" + key, value=str(frame[key]))

    for index, event in enumerate(report["framework_events"], 1):
        prefix = f"framework_event_{index}_"
        for key in ("fixture", "kind"):
            ET.SubElement(properties, "property", name=prefix + key, value=event[key])
        ET.SubElement(properties, "property", name=prefix + "blocked_discovery_ordinals",
                      value=",".join(str(n) for n in event["blocked_discovery_ordinals"]))
        coordinates(prefix, event)
    if report["discovery"] is not None:
        for key in ("completed", "execution_shape_supported", "original_suite_shape_unchanged"):
            value = report["discovery"][key]
            ET.SubElement(properties, "property", name="discovery_" + key, value="unavailable" if value is None else str(value).lower())
        for entry in report["discovery"]["test_inventory"]:
            prefix = f"discovered_case_{entry['ordinal']}_"
            for key in ("id", "class_id", "module_id", "status", "case_ordinal", "blocking_fixture_event_ordinal"):
                value = entry[key]
                ET.SubElement(properties, "property", name=prefix + key, value="unavailable" if value is None else str(value))
        for index, entry in enumerate(report["discovery"]["import_failure_details"], 1):
            coordinates(f"import_failure_{index}_", entry)
    for case in cases:
        row = ET.SubElement(suite, "testcase", name=case["id"], classname=case["id"].rsplit(".", 1)[0],
                            ordinal=str(case["ordinal"]), discovery_ordinal="unbound" if case["discovery_ordinal"] is None else str(case["discovery_ordinal"]),
                            time=str(case["elapsed_seconds"]), complete=str(case["complete"]).lower(),
                            stop_callback_observed=str(case["stop_callback_observed"]).lower())
        for index, event in enumerate(case["events"], 1):
            kind = event["kind"]
            if kind == "success":
                continue
            tag = "error" if kind == "error" else "failure" if kind in {"failure", "unexpected_success"} else "skipped"
            node = ET.SubElement(row, tag, type=event["exception_type"] or kind,
                                 event=str(index), subtest=str(event["subtest"]).lower())
            node.text = "\n".join(f"{frame['path']}:{frame['line']}:{frame['function']}" for frame in event["frames"])
    return ET.tostring(suite, encoding="utf-8", xml_declaration=True) + b"\n"


def stop_worker(process, cleanup: dict) -> None:
    if process.poll() is not None:
        return
    try:
        if os.name == "nt":
            process.kill()
            cleanup["direct_worker_kill_requested"] = True
        else:
            os.killpg(process.pid, signal.SIGKILL)
            cleanup["process_group_kill_requested"] = True
    except (OSError, ProcessLookupError):
        cleanup["kill_request_failed"] = True
    # No second numeric-group signal after poll/wait can have reaped the leader.
    try:
        process.wait(timeout=CLEANUP_WAIT_SECONDS)
    except subprocess.TimeoutExpired:
        cleanup["direct_worker_wait_complete"] = False


def run_checks(original: Config) -> dict:
    started, monotonic = utc_now(), time.monotonic()
    report = {"schema_version": 1, "evidence_kind": "executed_python_controlled_tests", "state": "incomplete",
              "started_at": started, "finished_at": None, "source": None, "source_after": None,
              "worker_source_before": None, "worker_source_after": None,
              "ci_context": None, "selection": None, "discovery": None, "counts": None, "cases": [], "framework_events": [],
              "diagnostics": [], "results_complete": False, "counts_scope": "unavailable", "test_denominators": None,
              "raw_console": None, "worker_return_code": None, "junit_report": None,
              "persistence_complete": False, "unsafe_text_output_locals_and_environment_omitted": True,
              "qualification": {"release_qualified": False, "rule_qualification": False,
                                "production_native_supervision_qualification": False, "apple_abi_qualification": False,
                                "native_throws_qualification": False, "loaded_artifact_enrollment_verified_by_this_record": False,
                                "original_junit_and_stress_artifacts_verified_by_this_record": False},
              "limitations": LIMITATIONS.copy(), "bounds": {"wall_timeout_seconds": original.timeout,
                  "max_console_bytes": original.max_output_bytes, "max_report_bytes_each": original.max_report_bytes,
                  "pipe_drain_seconds": PIPE_DRAIN_SECONDS, "cleanup_wait_seconds": CLEANUP_WAIT_SECONDS},
              "cleanup": {"scope": "direct worker on Windows; best-effort worker process group on POSIX",
                  "process_group_kill_requested": False, "direct_worker_kill_requested": False,
                  "kill_request_failed": False, "direct_worker_wait_complete": True, "descendant_absence_verified": False}}
    config = None
    capture = process = None
    worker_value = None
    final = progress = None
    publication_allowed = False
    deadline = monotonic + original.timeout

    def retain_worker(value):
        for key in ("discovery", "counts", "cases", "framework_events"):
            report[key] = value[key]
        report["worker_source_before"] = value["source_before"]
        report["worker_source_after"] = value["source_after"]
        report["counts_scope"] = "final standard-unittest callbacks" if value["results_complete"] else "worker callback prefix; not a claimed final suite total"
        report["test_denominators"] = {
            "discovered_test_cases": value["discovery"]["tests_selected"] if value["discovery"]["completed"] else None,
            "started_test_cases": value["counts"]["tests_run"],
            "stop_callbacks_observed": sum(case["stop_callback_observed"] for case in value["cases"]),
            "completed_test_cases": sum(case["complete"] for case in value["cases"]),
            "setup_blocked_test_cases": sum(row["status"] == "setup_blocked" for row in value["discovery"]["test_inventory"]),
            "pending_selected_test_cases": sum(row["status"] == "pending" for row in value["discovery"]["test_inventory"]) if value["discovery"]["completed"] else None,
            "unbound_started_test_cases": sum(case["discovery_ordinal"] is None for case in value["cases"]),
            "fixture_or_framework_events": len(value["framework_events"]),
        }
        report["diagnostics"].extend(value["diagnostics"])
    try:
        config = normalized(original)
        for target in (config.output, config.junit):
            if target.exists():
                raise GuardError("earlier_report_already_exists")
            target.parent.mkdir(parents=True, exist_ok=True)
        publication_allowed = True
        report["ci_context"] = config.ci_context if context_valid(config.ci_context) else None
        if config.require_ci_context and (not context_valid(config.ci_context) or config.expected_commit is None):
            raise GuardError("required_ci_context_missing")
        if config.ci_context is not None and not context_valid(config.ci_context):
            raise GuardError("invalid_ci_context")
        report["selection"] = {"start_directory": config.start.relative_to(config.root).as_posix(), "pattern": config.pattern}
        report["source"] = source_snapshot(config, deadline)
        directory = config.output.parent / "raw" / ("python-checks-" + uuid.uuid4().hex)
        directory.parent.mkdir(parents=True, exist_ok=True)
        reject_symlink_ancestors(directory, config.root)
        directory.mkdir(mode=0o700)
        final, progress = directory / "worker-result.json", directory / "worker-progress.json"
        control = directory / "worker-config.json"
        private = {"root": str(config.root), "start": str(config.start), "output": str(config.output),
                   "junit": str(config.junit), "pattern": config.pattern, "timeout": config.timeout,
                   "max_output_bytes": config.max_output_bytes, "max_report_bytes": config.max_report_bytes,
                   "expected_commit": config.expected_commit, "ci_context": config.ci_context,
                   "require_ci_context": config.require_ci_context, "final": str(final), "progress": str(progress)}
        publish_exclusive(control, encoded(private, 16384))
        capture = ConsoleCapture(directory, config.max_output_bytes)
        optimization = ["-OO"] if sys.flags.optimize == 2 else ["-O"] if sys.flags.optimize else []
        process = subprocess.Popen([sys.executable, *optimization, str(Path(__file__).resolve()), "--_worker", str(control)],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=os.name != "nt",
            creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0)
        capture.attach(process)
        while process.poll() is None:
            if capture.overflow:
                report["diagnostics"].append(diagnostic("console_output_limit", None, config.root))
                stop_worker(process, report["cleanup"])
                break
            if time.monotonic() >= deadline:
                report["diagnostics"].append(diagnostic("worker_wall_timeout", None, config.root))
                stop_worker(process, report["cleanup"])
                break
            time.sleep(min(0.01, max(0.0, deadline - time.monotonic())))
        report["worker_return_code"] = process.poll()
        report["raw_console"] = capture.finish()
        capture = None
        console = report["raw_console"]
        if console["truncated"] and not any(row["code"] == "console_output_limit" for row in report["diagnostics"]):
            report["diagnostics"].append(diagnostic("console_output_limit", None, config.root))
        if console["truncated"] or not console["drain_completed"] \
                or not all(row["eof_observed"] and not row["reader_error"] for row in console["streams"].values()):
            report["diagnostics"].append(diagnostic("console_capture_incomplete", None, config.root))
        try:
            report["source_after"] = source_snapshot(config, deadline)
        except (OSError, ValueError, GuardError) as error:
            report["diagnostics"].append(diagnostic("parent_source_verification_incomplete", error, config.root))
        for chosen in (final, progress):
            try:
                worker_value = validate_worker(strict_json(read_small(chosen, config.max_report_bytes), config.max_report_bytes),
                                               report["source"], report["source_after"], config)
                break
            except (OSError, ValueError, TypeError, GuardError) as error:
                if chosen == final:
                    report["diagnostics"].append(diagnostic("worker_final_protocol_unavailable_or_invalid", error, config.root))
                else:
                    raise GuardError("worker_protocol_unavailable_or_invalid") from None
        retain_worker(worker_value)
        expected_exit = {"passed": 0, "failed": 1, "incomplete": 2}[worker_value["state"]]
        if worker_value["phase"] != "final" or report["worker_return_code"] != expected_exit:
            report["diagnostics"].append(diagnostic("worker_finalization_incomplete", None, config.root))
        if report["source"] != worker_value["source_before"]:
            report["diagnostics"].append(diagnostic("worker_source_before_mismatch", None, config.root))
        if worker_value["phase"] == "final" and report["source_after"] != worker_value["source_after"]:
            report["diagnostics"].append(diagnostic("worker_source_after_mismatch", None, config.root))
        if report["source"] != report["source_after"]:
            report["diagnostics"].append(diagnostic("parent_selected_source_changed_or_unverified", None, config.root))
        if not report["diagnostics"] and worker_value["results_complete"]:
            report["state"] = worker_value["state"]
            report["results_complete"] = True
    except BaseException as error:
        root = config.root if config else original.root.resolve()
        code = error.code if isinstance(error, GuardError) else "wrapper_exception"
        report["diagnostics"].append(diagnostic(code, error, root))
    finally:
        if process is not None and process.poll() is None:
            stop_worker(process, report["cleanup"])
        if process is not None:
            report["worker_return_code"] = process.poll()
        if capture is not None:
            report["raw_console"] = capture.finish()
        if worker_value is None and config is not None and report["source"] is not None and final is not None:
            for chosen in (final, progress):
                try:
                    worker_value = validate_worker(strict_json(read_small(chosen, config.max_report_bytes), config.max_report_bytes),
                                                   report["source"], report["source_after"], config)
                    retain_worker(worker_value)
                    break
                except BaseException:
                    pass
        report["finished_at"] = utc_now()
        report["elapsed_seconds"] = round(time.monotonic() - monotonic, 6)
    if config is not None and publication_allowed:
        try:
            xml = junit_bytes(report)
            if len(xml) > config.max_report_bytes:
                raise GuardError("junit_report_limit")
            publish_exclusive(config.junit, xml)
            report["junit_report"] = {"path": config.junit.relative_to(config.root).as_posix(),
                                      "size_bytes": len(xml), "digest": digest(xml)}
            report["persistence_complete"] = True
            publish_exclusive(config.output, encoded(report, config.max_report_bytes))
        except BaseException as error:
            report.update(state="incomplete", results_complete=False, persistence_complete=False)
            report["diagnostics"].append(diagnostic("report_persistence_incomplete", error, config.root))
            if not config.output.exists():
                try:
                    config.output.parent.mkdir(parents=True, exist_ok=True)
                    publish_exclusive(config.output, encoded(report, config.max_report_bytes))
                except BaseException:
                    pass
    return report


def ci_context_from_environment(host: str | None) -> dict | None:
    # Explicit public CI metadata allowlist only; never read an auth/token key.
    def number(key):
        value = os.environ.get(key, "")
        return int(value) if re.fullmatch(r"[0-9]{1,20}", value) and int(value) > 0 else None
    values = {"run_id": number("GITHUB_RUN_ID"), "run_attempt": number("GITHUB_RUN_ATTEMPT"),
              "job": os.environ.get("GITHUB_JOB"), "host": host, "runner_os": os.environ.get("RUNNER_OS")}
    return values if context_valid(values) else None


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) == 2 and argv[0] == "--_worker":
        try:
            control = Path(argv[1])
            private = strict_json(read_small(control, 16384), 16384)
            if not isinstance(private, dict) or set(private) != {"root", "start", "output", "junit", "pattern", "timeout",
                    "max_output_bytes", "max_report_bytes", "expected_commit", "ci_context", "require_ci_context", "final", "progress"} \
                    or any(not isinstance(private[key], str) for key in ("root", "start", "output", "junit", "final", "progress")):
                return 2
            final, progress = Path(private.pop("final")), Path(private.pop("progress"))
            if final.name != "worker-result.json" or progress.name != "worker-progress.json" \
                    or final.parent.resolve() != control.parent.resolve() or progress.parent.resolve() != control.parent.resolve() \
                    or final.is_symlink() or progress.is_symlink():
                return 2
            for key in ("root", "start", "output", "junit"):
                private[key] = Path(private[key])
            config = normalized(Config(**private))
            if config.require_ci_context and (not context_valid(config.ci_context) or config.expected_commit is None) \
                    or config.ci_context is not None and not context_valid(config.ci_context):
                return 2
            return worker_run(config, final, progress)
        except BaseException:
            return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start-directory", required=True)
    parser.add_argument("--pattern", default="test_*.py")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--junit", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=480)
    parser.add_argument("--max-output-bytes", type=int, default=1024 * 1024)
    parser.add_argument("--max-report-bytes", type=int, default=MAX_REPORT_BYTES)
    parser.add_argument("--host")
    parser.add_argument("--require-ci-context", action="store_true")
    args = parser.parse_args(argv)
    if not portable_relative(args.start_directory):
        parser.error("start directory must be a portable checkout-relative path")
    config = Config(REPO_ROOT, REPO_ROOT / args.start_directory, REPO_ROOT / args.output,
                    REPO_ROOT / args.junit, args.pattern, args.timeout, args.max_output_bytes,
                    args.max_report_bytes, os.environ.get("GITHUB_SHA"),
                    ci_context_from_environment(args.host), args.require_ci_context)
    result = run_checks(config)
    counts = result["counts"]
    print("Python controlled-test evidence: " + result["state"] +
          (f"; tests={counts['tests_run']}, failures={counts['failures']}, errors={counts['errors']}, skipped={counts['skipped']}" if counts else "; counts unavailable"))
    return 0 if result["state"] == "passed" and result["persistence_complete"] else 1 if result["state"] == "failed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
