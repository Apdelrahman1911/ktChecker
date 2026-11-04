#!/usr/bin/env python3
"""Internal actual native unittest runner; emits outcomes, never exception bodies.

The parent CI wrapper supplies an existing explicit compiler and keeps stdout
private. This runner does execute the trusted C/Python fixture tests; its result
is not provider, adversarial-sandbox, Darwin syscall or release qualification.
"""

import argparse
from pathlib import Path
import platform
import sys
import unittest

if __package__:
    from . import run_supervisor_checks as checks
else:
    import run_supervisor_checks as checks


def suite_cases(suite):
    for value in suite:
        if isinstance(value, unittest.TestSuite):
            yield from suite_cases(value)
        else:
            yield value


class OutcomeResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.outcomes = {name: {"id": name, "state": "not_run", "skip_reason": None} for name in checks.CASES}
        self.fixture_errors = 0

    def record(self, test, state, reason=None):
        name = test.id()
        if name not in self.outcomes:
            # Setup/teardown failures and unrecognized subtest IDs have no safe
            # public method label. Retain only their count, not arbitrary text.
            self.fixture_errors += 1
            return
        if self.outcomes[name]["state"] == "error" and state == "failed":
            return
        self.outcomes[name] = {"id": name, "state": state, "skip_reason": reason}

    def addSuccess(self, test):
        super().addSuccess(test)
        self.record(test, "passed")

    def addFailure(self, test, error):
        super().addFailure(test, error)
        self.record(test, "failed")

    def addError(self, test, error):
        super().addError(test, error)
        self.record(test, "error")

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        expected = checks.LINUX_ONLY.get(test.id())
        code = expected[1] if expected and reason == expected[0] else \
            "native_supervisor_unavailable_on_host" if reason == "native supervisor unavailable on this host" else "unrecognized_skip_reason"
        self.record(test, "skipped", code)

    def addSubTest(self, test, subtest, error):
        super().addSubTest(test, subtest, error)
        if error is not None:
            self.record(test, "failed" if issubclass(error[0], test.failureException) else "error")

    def addExpectedFailure(self, test, error):
        super().addExpectedFailure(test, error)
        self.record(test, "failed")

    def addUnexpectedSuccess(self, test):
        super().addUnexpectedSuccess(test)
        self.record(test, "failed")


def run(output: Path, cc: Path, root: Path = checks.REPO_ROOT) -> dict:
    result = {"schema_version": 1, "evidence_kind": "native_supervisor_unittest_outcomes", "recorded_at": checks.utc_now(),
              "state": "failed", "source": checks.source_identity(root),
              "host": checks.normalized_host(platform.system(), platform.machine()), "test_inputs": {},
              "selected_method_count": len(checks.CASES), "tests_run": 0, "fixture_errors": 0,
              "cases": [{"id": name, "state": "not_run", "skip_reason": None} for name in sorted(checks.CASES)],
              "raw_exception_messages_retained": False, "provider_controls_executed": False,
              "native_abi_qualified": False, "release_qualified": False}
    try:
        result["test_inputs"] = checks.native_test_inputs(root)
        if result["host"]["target"] not in checks.SUPERVISOR_TARGETS or not cc.is_absolute() or not cc.is_file():
            raise ValueError("actual native fixture runner requires a supported host and explicit compiler")
        native = root / checks.MODULE / "native"
        suite = unittest.TestLoader().discover(str(native), pattern="test_*.py")
        selected = [case.id() for case in suite_cases(suite)]
        if len(selected) != len(checks.CASES) or set(selected) != set(checks.CASES):
            raise ValueError("actual native fixture method inventory differs")
        # Discovery does not invoke the file's CLI. Bind its explicit existing
        # compiler before any setUpClass can build the temporary C fixtures.
        sys.modules["test_supervisor"].CC = cc
        outcome = unittest.TextTestRunner(verbosity=2, stream=sys.stdout, resultclass=OutcomeResult).run(suite)
        result.update(tests_run=outcome.testsRun, fixture_errors=outcome.fixture_errors,
                      cases=[outcome.outcomes[name] for name in sorted(outcome.outcomes)])
        if not checks.matching_source(checks.source_identity(root), result["source"]) or checks.native_test_inputs(root) != result["test_inputs"]:
            result["fixture_errors"] += 1
        counts = {state: sum(row["state"] == state for row in result["cases"])
                  for state in ("passed", "failed", "error", "skipped", "not_run")}
        result["counts"] = counts
        expected_skips = checks.LINUX_ONLY if result["host"]["os"] == "macos" else {}
        if outcome.wasSuccessful() and not result["fixture_errors"] and all(
            row["state"] == ("skipped" if row["id"] in expected_skips else "passed")
            and row["skip_reason"] == (expected_skips[row["id"]][1] if row["id"] in expected_skips else None)
            for row in result["cases"]
        ):
            result["state"] = "passed"
    except (OSError, ValueError, TypeError, AttributeError, KeyError, ImportError):
        result["fixture_errors"] += 1
    result["counts"] = {state: sum(row["state"] == state for row in result["cases"])
                        for state in ("passed", "failed", "error", "skipped", "not_run")}
    checks.write_json(output, result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cc", type=Path, required=True)
    args = parser.parse_args()
    result = run(args.output, args.cc)
    print(f"Native fixture unittest report: {result['state']}; {result['counts']}; no qualification")
    return 0 if result["state"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
