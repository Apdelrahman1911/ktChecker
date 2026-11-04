#!/usr/bin/env python3
"""Validate ktChecker's planning registry. This does NOT test checker rules.

Uses only Python's standard library so the exact same validation runs on
Windows, Linux and macOS. External sources are not refetched in this check.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
import re
import sys
from typing import Any
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_CATALOGS = {"core", "libraries", "platform", "ecosystem"}
FIELDS = {
    "id", "category", "problem", "rationale", "sources", "applicability",
    "platforms", "version_scope", "implementation", "provider", "depth",
    "cost", "severity", "kind", "certainty", "limitations", "validation",
    "status", "default_activation", "disposition_reason",
}
ENUMS = {
    "implementation": {"existing", "adapted", "custom"},
    "depth": {"syntax", "semantic", "graph", "build", "runtime"},
    "cost": {"fast", "medium", "slow"},
    "severity": {"error", "warning", "info"},
    "kind": {"correctness", "probable_defect", "policy", "style"},
    "certainty": {"high", "medium", "low", "intent_dependent"},
    "status": {"planned", "implemented", "tested", "validated"},
    "default_activation": {
        "enabled_when_validated", "advisory", "opt_in", "excluded",
    },
}
PLATFORMS = {"common", "android", "ios", "desktop", "jvm"}
VALIDATION_CASES = {
    "positive", "negative", "boundary", "platform_version", "suppression",
    "regression",
}
RULE_ID = re.compile(r"ktc\.[A-Za-z0-9][A-Za-z0-9_.-]*\Z")
EVIDENCE_GATES = {
    "implemented": ("implementation",),
    "tested": ("implementation", "integration_tests", "test_run"),
    "validated": (
        "implementation", "integration_tests", "test_run", "validation_report",
    ),
}


def local_file(root: Path, value: Any) -> bool:
    """Evidence must be a file inside the checked-out repository, not a URL."""
    if not isinstance(value, str) or not value.strip():
        return False
    try:
        if urlsplit(value).scheme:
            return False
    except ValueError:
        return False
    candidate = (root / value).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError:
        return False
    return candidate.is_file()


def validate_rule(rule: Any, root: Path = ROOT) -> list[str]:
    if not isinstance(rule, dict):
        return ["rule must be a JSON object"]
    label = str(rule.get("id", "<missing id>"))
    errors: list[str] = []

    def fail(message: str) -> None:
        errors.append(f"{label}: {message}")

    for field in sorted(FIELDS - rule.keys()):
        fail(f"missing {field}")
    if not isinstance(rule.get("id"), str) or not RULE_ID.fullmatch(rule["id"]):
        fail("id must be a stable ktc.* identifier")
    for field in FIELDS - {"sources", "platforms", "validation"}:
        if field in rule and (
            not isinstance(rule[field], str) or not rule[field].strip()
        ):
            fail(f"{field} must be a non-empty string")
    for field, choices in ENUMS.items():
        if field in rule and (
            not isinstance(rule[field], str) or rule[field] not in choices
        ):
            fail(f"invalid {field}: {rule[field]!r}")
    platforms = rule.get("platforms")
    if not isinstance(platforms, list) or not platforms:
        fail("platforms must be a non-empty array")
    elif any(not isinstance(p, str) or p not in PLATFORMS for p in platforms):
        fail("unknown platform")
    elif len(platforms) != len(set(platforms)):
        fail("duplicate platforms")

    sources = rule.get("sources")
    if not isinstance(sources, list) or not sources:
        fail("sources must contain at least one evidence URL")
    else:
        for source in sources:
            if not isinstance(source, str):
                fail("source must be a URL string")
                continue
            try:
                parsed = urlsplit(source)
            except ValueError:
                fail(f"invalid source URL: {source!r}")
                continue
            if parsed.scheme not in {"https", "http"} or not parsed.netloc:
                fail(f"invalid source URL: {source!r}")
            if parsed.username or parsed.password:
                fail("source URL must not contain credentials")

    validation = rule.get("validation")
    if not isinstance(validation, dict):
        fail("validation must be a case-plan object")
    else:
        for case in sorted(VALIDATION_CASES):
            if not isinstance(validation.get(case), str) or not validation[case].strip():
                fail(f"validation.{case} must describe cases or justified non-applicability")

    status = rule.get("status")
    if isinstance(status, str) and status in EVIDENCE_GATES:
        evidence = rule.get("evidence", {})
        if not isinstance(evidence, dict):
            evidence = {}
        for gate in EVIDENCE_GATES[status]:
            paths = evidence.get(gate, [])
            if isinstance(paths, str):
                paths = [paths]
            if not isinstance(paths, list) or not paths or any(
                not local_file(root, p) for p in paths
            ):
                fail(f"{status} requires repository evidence.{gate} file(s)")
    if "runtime_enabled" in rule and not isinstance(rule["runtime_enabled"], bool):
        fail("runtime_enabled must be a boolean")
    if rule.get("runtime_enabled") is True and status != "validated":
        fail("a planned, implemented-only or tested-only rule cannot be runtime enabled")
    if rule.get("runtime_enabled") is True and rule.get("default_activation") == "excluded":
        fail("an excluded rule cannot be runtime enabled")
    return errors


def read_registry(root: Path = ROOT) -> tuple[dict[str, list[dict]], list[str]]:
    groups: dict[str, list[dict]] = {}
    errors: list[str] = []
    seen: dict[str, str] = {}
    paths = sorted((root / "data").glob("rules-*.json"))
    if not paths:
        return {}, ["no data/rules-*.json inventory files found"]
    for path in paths:
        try:
            rules = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            errors.append(f"{path.name}: invalid JSON: {exc}")
            continue
        if not isinstance(rules, list) or not rules:
            errors.append(f"{path.name}: expected a non-empty JSON array")
            continue
        groups[path.stem.removeprefix("rules-")] = rules
        for rule in rules:
            errors.extend(validate_rule(rule, root))
            if not isinstance(rule, dict) or not isinstance(rule.get("id"), str):
                continue
            rule_id = rule["id"]
            if rule_id in seen:
                errors.append(f"duplicate id {rule_id}: {seen[rule_id]} and {path.name}")
            seen[rule_id] = path.name
    return groups, errors


def markdown_cell(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def check_catalog_baseline(groups: dict[str, list[dict]], root: Path = ROOT) -> list[str]:
    """Detect silent scope changes, not certify launch completeness or reliability."""
    errors: list[str] = []
    for name in sorted(EXPECTED_CATALOGS - groups.keys()):
        errors.append(f"required planning catalog rules-{name}.json is missing")
    path = root / "data" / "catalog-baseline.json"
    try:
        baseline = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        return errors + [f"catalog-baseline.json unavailable: {exc}"]
    if not isinstance(baseline, dict) or not isinstance(baseline.get("rule_modes"), dict):
        return errors + ["catalog-baseline.json must have a rule_modes mapping"]
    if baseline.get("release_qualified") is not False:
        errors.append("planning baseline must not claim release qualification")
    if not isinstance(baseline.get("change_reason"), str) or not baseline["change_reason"].strip():
        errors.append("baseline scope changes must have an explicit change_reason")
    actual = {r["id"]: r["default_activation"] for rs in groups.values() for r in rs}
    previous = baseline["rule_modes"]
    for rule_id in sorted(previous.keys() - actual.keys()):
        errors.append(f"catalog baseline rule removed: {rule_id}; explicit scope review required")
    for rule_id in sorted(actual.keys() - previous.keys()):
        errors.append(f"catalog baseline missing new rule: {rule_id}; explicit scope review required")
    for rule_id in sorted(actual.keys() & previous.keys()):
        if actual[rule_id] != previous[rule_id]:
            errors.append(f"catalog disposition changed: {rule_id}; update baseline with reason after review")
    return errors


def render_catalog(name: str, rules: list[dict]) -> str:
    counts = Counter(r["default_activation"] for r in rules)
    states = Counter(r["status"] for r in rules)
    lines = [
        f"# {name.title()} rule index", "",
        "<!-- Generated by scripts/validate_plan.py --write-catalogs. Do not edit. -->", "",
        f"Source of truth: [full records](../../data/rules-{name}.json). "
        "Each record includes rationale, primary sources, version/applicability limits, "
        "six validation-case plans, and implementation status.", "",
        "**Catalog membership is not implemented or validated coverage.** "
        "Platform labels are conditional scope, not tested support. Costs are estimates. "
        "`enabled_when_validated` is an intended default, never a current enablement. "
        "No test recipe or upstream test is evidence of ktChecker integration.", "",
        f"Records: {len(rules)}. Dispositions: "
        + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())) + ".", "",
        "Statuses: " + ", ".join(f"{k}={v}" for k, v in sorted(states.items())) + ".", "",
        "| Stable ID | Problem | Platforms (conditional) | Depth / cost | Kind / severity / certainty | Intended activation | Status |",
        "|---|---|---|---|---|---|---|",
    ]
    for rule in sorted(rules, key=lambda r: r["id"]):
        values = [
            f'`{rule["id"]}`', rule["problem"], ", ".join(rule["platforms"]),
            f'{rule["depth"]} / {rule["cost"]}',
            f'{rule["kind"]} / {rule["severity"]} / {rule["certainty"]}',
            rule["default_activation"], rule["status"],
        ]
        lines.append("| " + " | ".join(markdown_cell(v) for v in values) + " |")
    return "\n".join(lines) + "\n"


def check_markdown_links(root: Path = ROOT) -> list[str]:
    errors: list[str] = []
    files = [root / "README.md", *sorted((root / "docs").rglob("*.md"))]
    for path in files:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        # Ignore fenced future repository trees and shell command examples.
        text = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
        for link in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
            link = link.split(' "', 1)[0].strip("<>")
            if not link or link.startswith("#") or urlsplit(link).scheme:
                continue
            filename = unquote(link.split("#", 1)[0])
            if not (path.parent / filename).exists():
                errors.append(f"{path.relative_to(root)}: broken local link {link}")
    return errors


def check_json_examples(root: Path = ROOT) -> list[str]:
    """Syntax-check JSON fences, without claiming protocol/schema conformance."""
    errors: list[str] = []
    for path in sorted((root / "docs").rglob("*.md")):
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r"^```json\s*\n(.*?)\n```", text, flags=re.MULTILINE | re.DOTALL):
            try:
                json.loads(match.group(1))
            except ValueError as exc:
                line = text[:match.start()].count("\n") + 1
                errors.append(f"{path.relative_to(root)}:{line}: invalid JSON example: {exc}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-catalogs", action="store_true")
    args = parser.parse_args()
    groups, errors = read_registry()
    if not errors:
        for name, rules in groups.items():
            path = ROOT / "docs" / "rules" / f"{name}.md"
            expected = render_catalog(name, rules)
            if args.write_catalogs:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(expected, encoding="utf-8", newline="\n")
            elif not path.is_file() or path.read_text(encoding="utf-8") != expected:
                errors.append(f"{path.relative_to(ROOT)} is missing or stale; run --write-catalogs")
        errors.extend(check_catalog_baseline(groups))
    errors.extend(check_markdown_links())
    errors.extend(check_json_examples())
    if errors:
        print("Planning validation FAILED:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    total = sum(len(rules) for rules in groups.values())
    print(f"Planning registry OK: {total} records across {len(groups)} catalogs; local links OK.")
    print("This verifies metadata consistency, NOT rule behavior, engine portability or accuracy.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
