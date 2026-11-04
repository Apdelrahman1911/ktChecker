#!/usr/bin/env python3
"""Internal actual native unittest runner; emits outcomes, never exception bodies.

The parent CI wrapper supplies an existing explicit compiler and keeps stdout
private. This runner does execute the trusted C/Python fixture tests; its result
is not provider, adversarial-sandbox, Darwin syscall or release qualification.
"""

import argparse
from functools import partial
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
    def __init__(self, *args, diagnostic_sources=None, **kwargs):
        super().__init__(*args, **kwargs)
        self.outcomes = {name: {"id": name, "state": "not_run", "skip_reason": None} for name in checks.CASES}
        self.fixture_errors = 0
        self.failure_diagnostics = {}
        self.diagnostic_paths = {path: (role, entry["source_digest"], entry["line_count"])
                                 for role, entry in (diagnostic_sources or {}).items() for path in entry["paths"]}

    def failure_diagnostic(self, event, error):
        """No exception formatting, values, locals, source text or arbitrary filenames.

        The innermost bound fixture frame can be a call site, not the exception
        origin. Unknown sources or overlong traces have no public location.
        """
        if event not in checks.FAILURE_EVENTS:
            raise ValueError("native failure diagnostic event invalid")
        result = {"event": event, "source": None, "source_digest": None, "line": None}
        trace = error[2] if error is not None else None
        for _ in range(64):
            if trace is None:
                return result
            bound = self.diagnostic_paths.get(trace.tb_frame.f_code.co_filename)
            if bound is not None and type(trace.tb_lineno) is int and 1 <= trace.tb_lineno <= bound[2]:
                result.update(source=bound[0], source_digest=bound[1], line=trace.tb_lineno)
            trace = trace.tb_next
        return result if trace is None else {"event": event, "source": None, "source_digest": None, "line": None}

    def record(self, test, state, reason=None, *, event=None, error=None):
        name = test.id()
        if name not in self.outcomes:
            # Setup/teardown failures and unrecognized subtest IDs have no safe
            # public method label. Retain only their count, not arbitrary text.
            self.fixture_errors += 1
            return
        if self.outcomes[name]["state"] == "error" and state == "failed":
            return
        self.outcomes[name] = {"id": name, "state": state, "skip_reason": reason}
        if event is not None and name not in self.failure_diagnostics:
            # Subtests/teardown can fail more than once; preserve just the first
            # nonpassing event, without exposing subtest parameters or counts.
            self.failure_diagnostics[name] = self.failure_diagnostic(event, error)

    def addSuccess(self, test):
        super().addSuccess(test)
        self.record(test, "passed")

    def addFailure(self, test, error):
        super().addFailure(test, error)
        self.record(test, "failed", event="assertion_failure", error=error)

    def addError(self, test, error):
        super().addError(test, error)
        self.record(test, "error", event="test_error", error=error)

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        expected = checks.LINUX_ONLY.get(test.id())
        code = expected[1] if expected and reason == expected[0] else \
            "native_supervisor_unavailable_on_host" if reason == "native supervisor unavailable on this host" else "unrecognized_skip_reason"
        self.record(test, "skipped", code)

    def addSubTest(self, test, subtest, error):
        super().addSubTest(test, subtest, error)
        if error is not None:
            failed = issubclass(error[0], test.failureException)
            self.record(test, "failed" if failed else "error", event="assertion_failure" if failed else "test_error", error=error)

    def addExpectedFailure(self, test, error):
        super().addExpectedFailure(test, error)
        self.record(test, "failed", event="expected_failure", error=error)

    def addUnexpectedSuccess(self, test):
        super().addUnexpectedSuccess(test)
        self.record(test, "failed", event="unexpected_success")


def run(output: Path, cc: Path, root: Path = checks.REPO_ROOT) -> dict:
    result = {"schema_version": 1, "evidence_kind": "native_supervisor_unittest_outcomes", "recorded_at": checks.utc_now(),
              "state": "failed", "source": checks.source_identity(root),
              "host": checks.normalized_host(platform.system(), platform.machine()), "test_inputs": {},
              "selected_method_count": len(checks.CASES), "tests_run": 0, "fixture_errors": 0,
              "failure_diagnostics": {},
              "cases": [{"id": name, "state": "not_run", "skip_reason": None} for name in sorted(checks.CASES)],
              "raw_exception_messages_retained": False, "provider_controls_executed": False,
              "native_abi_qualified": False, "release_qualified": False}
    try:
        result["test_inputs"] = checks.native_test_inputs(root)
        if result["host"]["target"] not in checks.SUPERVISOR_TARGETS or not cc.is_absolute() or not cc.is_file():
            raise ValueError("actual native fixture runner requires a supported host and explicit compiler")
        native = root / checks.MODULE / "native"
        diagnostic_sources = checks.native_diagnostic_sources(result["test_inputs"], root)
        suite = unittest.TestLoader().discover(str(native), pattern="test_*.py")
        selected = [case.id() for case in suite_cases(suite)]
        if len(selected) != len(checks.CASES) or set(selected) != set(checks.CASES):
            raise ValueError("actual native fixture method inventory differs")
        # Discovery does not invoke the file's CLI. Bind its explicit existing
        # compiler before any setUpClass can build the temporary C fixtures.
        sys.modules["test_supervisor"].CC = cc
        outcome = unittest.TextTestRunner(verbosity=2, stream=sys.stdout,
                                         resultclass=partial(OutcomeResult, diagnostic_sources=diagnostic_sources)).run(suite)
        result.update(tests_run=outcome.testsRun, fixture_errors=outcome.fixture_errors,
                      failure_diagnostics=outcome.failure_diagnostics,
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
